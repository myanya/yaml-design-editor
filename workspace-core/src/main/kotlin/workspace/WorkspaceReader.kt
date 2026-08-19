package workspace

import model.DocumentId
import model.YDocument
import schema.EntityDecl
import schema.SchemaCatalog
import schema.SchemaKind
import schema.SemanticDeclParser
import schema.StructuralSchema
import schema.StructuralSchemaParser
import schema.WorkspaceLayout
import schema.globToRegex
import semantic.DocumentMeta
import semantic.SemanticDeclarations
import semantic.SemanticDocument
import semantic.SemanticEngine
import semantic.SemanticInput
import semantic.SemanticSnapshot
import validate.StructuralIssue
import validate.StructuralValidator
import yaml.DefaultYamlCodec
import yaml.YamlIO

/**
 * 読取専用ワークスペースの組み立て（3層コア移行 Phase 7・WS-002。計画書§4.3）。
 * DocumentStore → スキーマ読込（構造＋意味宣言）→ 文書ロード → スキーマ割当（match）→
 * 構造検証 → SemanticInput 構築 → SemanticEngine → WorkspaceDerivedState を一気通貫で行う。
 * v1 の既知の未対応: 派生スキーマ（derive はテンプレート扱い＝どの文書にもマッチしない）・
 * `_schemas` 文書へのメタスキーマ適用（schemas ゾーンは文書集合から除外）。
 */

/** ワークスペースの1スキーマ（読取パイプライン用の意味束＋適用規則）。 */
class WorkspaceSchema(
    val id: String,
    val kind: SchemaKind,
    val match: String,
    val structural: StructuralSchema,
    val entities: List<EntityDecl>,
    /** derive 宣言スキーマ（テンプレート）か。テンプレートは文書にマッチしない。 */
    val isTemplate: Boolean,
) {
    private val regex: Regex? =
        if (match.isBlank()) null else runCatching { globToRegex(match) }.getOrNull()

    /** ワークスペース相対パスがこのスキーマの対象か（SchemaDef.matches と同じ規則）。 */
    fun matches(rel: String): Boolean =
        kind == SchemaKind.DOCUMENT && !isTemplate && regex?.matches(rel) == true
}

/** 文書1件のworking状態（計画書§4.3。読取専用フェーズでは persisted と同値）。 */
data class WorkspaceDocument(
    val document: YDocument,
    /** 保存済みテキスト。dirty 判定は現在の dump 結果との比較で行う（ADR-0007）。 */
    val persistedText: String,
    /** 割当てられたスキーマID（match するスキーマがなければ null）。 */
    val schemaId: String?,
    /** 台帳の文書か（R13-03。台帳外はエディタ層の検証だけが効く）。 */
    val inLedger: Boolean,
)

/** ワークスペースのworking snapshot（計画書§4.3）。 */
class WorkspaceSnapshot(
    val version: Long,
    val documents: Map<DocumentId, WorkspaceDocument>,
)

/** 派生状態（構造検証＋意味解析）。鮮度は sourceVersion の一致で判断する（ADR-0007）。 */
class WorkspaceDerivedState(
    val sourceVersion: Long,
    val structuralIssues: Map<DocumentId, List<StructuralIssue>>,
    val semantic: SemanticSnapshot,
)

/** 読取の結果一式。 */
class LoadedWorkspace(
    val layout: WorkspaceLayout,
    val schemas: List<WorkspaceSchema>,
    val declarations: SemanticDeclarations,
    /** スキーマ文書のパース・読取失敗（相対パス: メッセージ）。 */
    val schemaErrors: List<String>,
    /** 文書のパース失敗（読取は続行し、文書集合から除外される）。 */
    val parseErrors: Map<DocumentId, String>,
    val snapshot: WorkspaceSnapshot,
    val derived: WorkspaceDerivedState,
)

object WorkspaceReader {

    /** ワークスペースを読み取り、working snapshot と派生状態を組み立てる。 */
    fun load(store: DocumentStore, version: Long = 0): LoadedWorkspace {
        val layout = detectLayout(store)
        val listing = store.list()

        // 1) スキーマ読込（schemas ゾーン。相対パス順＝store.list の順）
        val schemaErrors = mutableListOf<String>()
        val schemas = mutableListOf<WorkspaceSchema>()
        var relationsDecl = linkedMapOf<String, schema.RelationDef>()
        val entityTypes = mutableListOf<schema.EntityTypeDef>()
        var enforcement = schema.EnforcementDef()
        for (doc in listing) {
            val rel = doc.id.value
            if (layout.zoneOf(rel) != WorkspaceLayout.Zone.SCHEMAS) continue
            val text = store.read(doc.id)?.text ?: continue
            val fallbackId = rel.substringAfterLast('/').substringBeforeLast('.')
            try {
                val raw = YamlIO.parseToRaw(text) as? Map<*, *> ?: error("スキーマのルートはobjectである必要があります")
                val kind = SchemaKind.parse(raw["kind"]?.toString())
                val structural = StructuralSchemaParser.parse(text, fallbackId)
                val isTemplate = raw["derive"] != null
                val match = raw["match"]?.toString()
                    ?: if (kind == SchemaKind.DOCUMENT && !isTemplate) "**/*.{yaml,yml}" else ""
                schemas += WorkspaceSchema(
                    id = structural.id,
                    kind = kind,
                    match = match,
                    structural = structural,
                    entities = SemanticDeclParser.parseEntities(raw["entity"]),
                    isTemplate = isTemplate,
                )
                if (kind == SchemaKind.RELATIONS) {
                    SemanticDeclParser.parseRelations(raw["relations"]).forEach { relationsDecl[it.name] = it }
                    entityTypes += SemanticDeclParser.parseEntityTypes(raw["entities"])
                    SemanticDeclParser.parseEnforcement(raw["enforcement"])?.let { enforcement = it }
                }
            } catch (e: Exception) {
                schemaErrors += "$rel: ${e.message}"
            }
        }
        // 2) `$ref` の横断解決（module#name）: 全モジュールの定義をリンクする（SchemaLoader と同じ流儀）
        val catalog = SchemaCatalog.linked(schemas.map { it.id to it.structural.definitions })
        val linked = schemas.map {
            WorkspaceSchema(it.id, it.kind, it.match, it.structural.copy(definitionCatalog = catalog), it.entities, it.isTemplate)
        }

        // 3) 文書ロード（schemas ゾーンと構成ファイルを除く。runs / agents 等の記録・定義ゾーンは文書に含む）
        val documents = linkedMapOf<DocumentId, WorkspaceDocument>()
        val parseErrors = linkedMapOf<DocumentId, String>()
        for (doc in listing) {
            val rel = doc.id.value
            val zone = layout.zoneOf(rel)
            if (zone == WorkspaceLayout.Zone.SCHEMAS) continue
            if (zone == null && layout.configDir != null && (rel == layout.configDir || rel.startsWith("${layout.configDir}/"))) continue
            val text = store.read(doc.id)?.text ?: continue
            val parsed = try {
                DefaultYamlCodec.parse(doc.id, text)
            } catch (e: Exception) {
                parseErrors[doc.id] = e.message ?: e.toString()
                continue
            }
            val schema = linked.firstOrNull { it.matches(rel) }
            documents[doc.id] = WorkspaceDocument(
                document = parsed,
                persistedText = text,
                schemaId = schema?.id,
                inLedger = layout.inLedger(rel),
            )
        }

        // 4) 構造検証（スキーマ割当のある文書すべて＝エディタ層は台帳外にも効く。R13-03）
        val byId = linked.associateBy { it.id }
        val structuralIssues = linkedMapOf<DocumentId, List<StructuralIssue>>()
        for ((id, d) in documents) {
            val sc = d.schemaId?.let { byId[it] } ?: continue
            val root = d.document.documents.firstOrNull() ?: continue
            structuralIssues[id] = StructuralValidator.validate(root, sc.structural)
        }

        // 5) 意味解析（台帳層。索引除外＝schemas / runs を外し、台帳外は定義ゾーンだけ載せる＝IdIndex と同じ規則）
        val semanticDocs = documents.mapNotNull { (id, d) ->
            val rel = id.value
            if (layout.indexExcluded(rel)) return@mapNotNull null
            if (!d.inLedger && layout.zoneOf(rel) == null) return@mapNotNull null
            val root = d.document.documents.firstOrNull() ?: return@mapNotNull null
            val sc = d.schemaId?.let { byId[it] }
            SemanticDocument(
                id = id,
                root = root,
                schema = sc?.structural,
                schemaId = sc?.id,
                entities = sc?.entities ?: emptyList(),
                documentId = DocumentMeta.idOf(root, rel),
            )
        }
        val declarations = SemanticDeclarations(relationsDecl, entityTypes, enforcement)
        val semantic = SemanticEngine.build(SemanticInput(version, semanticDocs, declarations))

        return LoadedWorkspace(
            layout = layout,
            schemas = linked,
            declarations = declarations,
            schemaErrors = schemaErrors,
            parseErrors = parseErrors,
            snapshot = WorkspaceSnapshot(version, documents),
            derived = WorkspaceDerivedState(version, structuralIssues, semantic),
        )
    }

    /** ストアの内容から構成（レガシー／.yde）を判定する（WorkspaceLayout.of の DocumentStore 版）。 */
    fun detectLayout(store: DocumentStore): WorkspaceLayout {
        val configFile = store.read(DocumentId("${WorkspaceLayout.CONFIG_DIR}/${WorkspaceLayout.CONFIG_FILE}"))
        val hasConfigDir = configFile != null ||
            store.list().any { it.id.value.startsWith("${WorkspaceLayout.CONFIG_DIR}/") }
        if (!hasConfigDir) return WorkspaceLayout.LEGACY
        val ledger = configFile?.let { runCatching { WorkspaceLayout.parseLedgerText(it.text) }.getOrDefault(emptyList()) }.orEmpty()
        return WorkspaceLayout(configDir = WorkspaceLayout.CONFIG_DIR, ledger = ledger)
    }
}
