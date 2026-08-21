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
    /** スキーマ定義文書の座標（スキーマ・リファクタリングの書き換え先。RF-002b）。 */
    val source: DocumentId,
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
    /**
     * 保存基準のテキスト（未保存の新規文書は null）。dirty 判定は現在の dump 結果との比較で行う（ADR-0007）。
     * 読取時に正規化（dump）した値を入れる——外部で書かれたファイルの表記ゆれ（末尾改行等）で
     * 偽 dirty にならないための、現行 EditBuffer（savedSnapshot = canonicalText）と同じ基準。
     * ストア上の生テキストとの比較（外部変更検出）は WorkspaceSession が別に追跡する。
     */
    val persistedText: String?,
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

/** スキーマ読込の結果（load と、セッション内のスキーマ再構築＝WS-006 が共有する）。 */
class SchemaLoadResult(
    val schemas: List<WorkspaceSchema>,
    val declarations: SemanticDeclarations,
    val errors: List<String>,
)

object WorkspaceReader {

    /**
     * スキーマ文書のテキスト集合からスキーマ束を組み立てる（構造＋意味宣言・`$ref` の横断リンク）。
     * sources は（文書ID, 本文）の相対パス順。
     */
    fun parseSchemas(sources: List<Pair<DocumentId, String>>): SchemaLoadResult {
        val errors = mutableListOf<String>()
        val schemas = mutableListOf<WorkspaceSchema>()
        val relationsDecl = linkedMapOf<String, schema.RelationDef>()
        val entityTypes = mutableListOf<schema.EntityTypeDef>()
        var enforcement = schema.EnforcementDef()
        for ((id, text) in sources) {
            val rel = id.value
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
                    source = id,
                )
                if (kind == SchemaKind.RELATIONS) {
                    SemanticDeclParser.parseRelations(raw["relations"]).forEach { relationsDecl[it.name] = it }
                    entityTypes += SemanticDeclParser.parseEntityTypes(raw["entities"])
                    SemanticDeclParser.parseEnforcement(raw["enforcement"])?.let { enforcement = it }
                }
            } catch (e: Exception) {
                errors += "$rel: ${e.message}"
            }
        }
        // `$ref` の横断解決（module#name）: 全モジュールの定義をリンクする（SchemaLoader と同じ流儀）
        val catalog = SchemaCatalog.linked(schemas.map { it.id to it.structural.definitions })
        val linked = schemas.map {
            WorkspaceSchema(it.id, it.kind, it.match, it.structural.copy(definitionCatalog = catalog), it.entities, it.isTemplate, it.source)
        }
        return SchemaLoadResult(linked, SemanticDeclarations(relationsDecl, entityTypes, enforcement), errors)
    }

    /** ワークスペースを読み取り、working snapshot と派生状態を組み立てる。 */
    fun load(store: DocumentStore, version: Long = 0): LoadedWorkspace {
        val layout = detectLayout(store)
        val listing = store.list()

        // 1) スキーマ読込（schemas ゾーン。相対パス順＝store.list の順）
        val schemaSources = listing.filter { layout.zoneOf(it.id.value) == WorkspaceLayout.Zone.SCHEMAS }
            .mapNotNull { doc -> store.read(doc.id)?.let { doc.id to it.text } }
        val parsedSchemas = parseSchemas(schemaSources)
        val schemaErrors = parsedSchemas.errors
        val linked = parsedSchemas.schemas

        // 3) 文書ロード（構成ファイルを除く全YAML。schemas / runs / agents 等の定義・記録ゾーンも
        //    エディタ層の編集対象として文書に含む——スキーマ・リファクタリング（RF-002）は
        //    `_schemas` 文書への EditDocument で自身を書き換える。意味解析への載せ方は derive 側で絞る
        val documents = linkedMapOf<DocumentId, WorkspaceDocument>()
        val parseErrors = linkedMapOf<DocumentId, String>()
        for (doc in listing) {
            val rel = doc.id.value
            val zone = layout.zoneOf(rel)
            if (zone == null && layout.configDir != null && (rel == layout.configDir || rel.startsWith("${layout.configDir}/"))) continue
            val text = store.read(doc.id)?.text ?: continue
            val parsed = try {
                DefaultYamlCodec.parse(doc.id, text)
            } catch (e: Exception) {
                parseErrors[doc.id] = e.message ?: e.toString()
                continue
            }
            // `_schemas` 配下の文書には一般スキーマの match を適用しない（メタスキーマだけが適用される規則。
            // メタスキーマ自体の適用は未対応＝WS-002 v1 の残）
            val schema = if (zone == WorkspaceLayout.Zone.SCHEMAS) null else linked.firstOrNull { it.matches(rel) }
            documents[doc.id] = WorkspaceDocument(
                document = parsed,
                persistedText = DefaultYamlCodec.dump(parsed),
                schemaId = schema?.id,
                inLedger = layout.inLedger(rel),
            )
        }

        // 4) 構造検証＋意味解析（WorkspaceSession の apply / undo / redo と共有する導出）
        val declarations = parsedSchemas.declarations
        val derived = derive(documents, linked, declarations, layout, version)

        return LoadedWorkspace(
            layout = layout,
            schemas = linked,
            declarations = declarations,
            schemaErrors = schemaErrors,
            parseErrors = parseErrors,
            snapshot = WorkspaceSnapshot(version, documents),
            derived = derived,
        )
    }

    /**
     * 文書集合から派生状態を導出する（構造検証＋意味解析の全再構築）。
     * 構造検証はスキーマ割当のある文書すべて（エディタ層は台帳外にも効く。R13-03）。
     * 意味解析は索引除外（schemas / runs）を外し、台帳外は定義ゾーンだけ載せる（IdIndex と同じ規則）。
     */
    fun derive(
        documents: Map<DocumentId, WorkspaceDocument>,
        schemas: List<WorkspaceSchema>,
        declarations: SemanticDeclarations,
        layout: WorkspaceLayout,
        version: Long,
    ): WorkspaceDerivedState {
        val byId = schemas.associateBy { it.id }
        val structuralIssues = linkedMapOf<DocumentId, List<StructuralIssue>>()
        for ((id, d) in documents) {
            structuralIssues[id] = validateOne(d, byId) ?: continue
        }
        val semantic = SemanticEngine.build(semanticInput(documents, schemas, declarations, layout, version))
        return WorkspaceDerivedState(version, structuralIssues, semantic)
    }

    /** 1文書の構造検証（スキーマ割当なし・空文書は null）。derive とセッションの増分導出で共有する。 */
    fun validateOne(d: WorkspaceDocument, schemasById: Map<String, WorkspaceSchema>): List<StructuralIssue>? {
        val sc = d.schemaId?.let { schemasById[it] } ?: return null
        val root = d.document.documents.firstOrNull() ?: return null
        return StructuralValidator.validate(root, sc.structural)
    }

    /** 意味解析の入力組立（索引除外・台帳規則の適用。derive・増分導出・計測で共有する）。 */
    fun semanticInput(
        documents: Map<DocumentId, WorkspaceDocument>,
        schemas: List<WorkspaceSchema>,
        declarations: SemanticDeclarations,
        layout: WorkspaceLayout,
        version: Long,
    ): SemanticInput {
        val byId = schemas.associateBy { it.id }
        val docs = documents.mapNotNull { (id, d) -> semanticDocument(id, d, byId, layout) }
        return SemanticInput(version, docs, declarations)
    }

    /** 意味解析へ載せる文書1件の組立（載せない文書＝索引除外・台帳外の一般文書・空文書は null）。 */
    fun semanticDocument(
        id: DocumentId,
        d: WorkspaceDocument,
        schemasById: Map<String, WorkspaceSchema>,
        layout: WorkspaceLayout,
    ): SemanticDocument? {
        val rel = id.value
        if (layout.indexExcluded(rel)) return null
        if (!d.inLedger && layout.zoneOf(rel) == null) return null
        val root = d.document.documents.firstOrNull() ?: return null
        val sc = d.schemaId?.let { schemasById[it] }
        return SemanticDocument(
            id = id,
            root = root,
            schema = sc?.structural,
            schemaId = sc?.id,
            entities = sc?.entities ?: emptyList(),
            documentId = DocumentMeta.idOf(root, rel),
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
