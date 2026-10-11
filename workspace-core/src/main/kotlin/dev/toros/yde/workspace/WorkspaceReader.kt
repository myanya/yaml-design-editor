package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.CheckLibrary
import dev.toros.yde.semantic.schema.CheckerRule
import dev.toros.yde.semantic.schema.ConstraintRule
import dev.toros.yde.semantic.schema.DeriveDef
import dev.toros.yde.semantic.schema.EnforcementDef
import dev.toros.yde.semantic.schema.EntityTypeDef
import dev.toros.yde.semantic.schema.ExpressionValue
import dev.toros.yde.semantic.schema.ExpandedCheck
import dev.toros.yde.semantic.schema.RelationDef
import dev.toros.yde.semantic.schema.VocabularySource
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.YDocument
import dev.toros.yde.semantic.schema.EntityDecl
import dev.toros.yde.semantic.schema.SchemaCatalog
import dev.toros.yde.semantic.schema.SchemaDerivation
import dev.toros.yde.semantic.schema.SchemaKind
import dev.toros.yde.semantic.schema.SchemaTree
import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.workspace.layout.FilePolicy
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.semantic.schema.globToRegex
import dev.toros.yde.semantic.DocumentMeta
import dev.toros.yde.semantic.SemanticDeclarations
import dev.toros.yde.semantic.SemanticDocument
import dev.toros.yde.semantic.SemanticEngine
import dev.toros.yde.semantic.SemanticInput
import dev.toros.yde.semantic.SemanticSnapshot
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.yaml.validate.StructuralIssue
import dev.toros.yde.yaml.DefaultYamlCodec
import dev.toros.yde.yaml.YamlIO

/**
 * 読取専用ワークスペースの組み立て（3層コア移行 Phase 7・WS-002。計画書§4.3）。
 * DocumentStore → スキーマ読込（構造＋意味宣言）→ 文書ロード → スキーマ割当（match）→
 * 構造検証 → SemanticInput 構築 → SemanticEngine → WorkspaceDerivedState を一気通貫で行う。
 * v1 の既知の未対応: 派生スキーマ（derive はテンプレート扱い＝どの文書にもマッチしない）・
 * `_schemas` 文書へのメタスキーマ適用（schemas ゾーンは文書集合から除外）。
 */

/**
 * ワークスペースの1スキーマ（スキーマの中身の原本。P27 段C の C-3b で、アプリ側の SchemaDef もこれを包む薄い型になった）。
 * 利用者が書く項目の木（tree）・適用先・エンティティ型の宣言・検査・関係レジストリの宣言・検査の定義を持つ。
 * アプリに要るもの（スキーマ定義文書のファイルの場所）は持たない——座標は DocumentId。
 */
class WorkspaceSchema(
    val id: String,
    val kind: SchemaKind,
    val match: String,
    val tree: SchemaTree,
    /** 書かれたままのエンティティ型の宣言。使う側は entities（ID の型を木で解決したもの）を読む。 */
    val declaredEntities: List<EntityDecl>,
    /** derive 宣言スキーマ（テンプレート）か。テンプレートは文書にマッチしない。 */
    val isTemplate: Boolean,
    /** スキーマ定義文書の座標（スキーマ・リファクタリングの書き換え先。RF-002b）。派生スキーマでは、導出元の文書。 */
    val source: DocumentId,
    /** 派生スキーマの宣言（C-2）。非null のとき isTemplate＝true で、導出元文書の変更はスキーマ再構築の契機になる。 */
    val derive: DeriveDef? = null,
    /**
     * 検査（P27 段B-2）: constraints に書いた検査と語彙を、基本の検査へ展開してパースしたもの（CheckCatalog）。
     * kind: library と kind: relations の文書の検査は、特定のスキーマに属さない検査（どの文書の要素にも適用する）。
     */
    val constraints: List<ConstraintRule> = emptyList(),
    val checkers: List<CheckerRule> = emptyList(),
    /** 展開後の検査の姿と由来（展開の透明性 CALC-29）。 */
    val expandedChecks: List<ExpandedCheck> = emptyList(),
    /** 語彙の再展開に使う原文と定義の一式（派生スキーマが、導出元の語彙を自分の ID で展開し直すために使う）。 */
    val vocabularySource: VocabularySource? = null,
    /** 表示用の名前と説明（スキーマの一覧・編集画面が使う）。 */
    val name: String = id,
    val description: String? = null,
    /** 関係レジストリの内容（kind: relations の文書だけ）。ワークスペース全体の集約は SemanticDeclarations。 */
    val relations: List<RelationDef> = emptyList(),
    val entityTypes: List<EntityTypeDef> = emptyList(),
    val enforcement: EnforcementDef? = null,
    /** このスキーマ文書が宣言する検査の定義と語彙の定義（functions / vocabulary）。 */
    val checkLibrary: CheckLibrary = CheckLibrary(),
    /** 式を受け取る項目（メタスキーマの expression: true の印）の値（場所 → 式。P31）。使う側は expressionAt で引く。 */
    val expressions: Map<YPath, ExpressionValue> = emptyMap(),
    /** 新しい文書のひな型（変換のひな型。いまの場所＝元の要素。P31・R31-03）。 */
    val scaffold: TransformDef? = null,
) {
    /**
     * エンティティ型の宣言（ID の型 idType を、このスキーマの木（定義の目録を含む）で解決したもの。P33）。
     * 木を差し替えた写し（copy）は解決し直す。
     */
    val entities: List<EntityDecl> by lazy { declaredEntities.map { it.resolvedIn(tree) } }

    /** 構造・適用先などを差し替えた写し（ほかは引き継ぐ）。 */
    fun copy(
        match: String = this.match,
        tree: SchemaTree = this.tree,
    ): WorkspaceSchema = WorkspaceSchema(
        id, kind, match, tree, declaredEntities, isTemplate, source, derive, constraints, checkers, expandedChecks, vocabularySource,
        name, description, relations, entityTypes, enforcement, checkLibrary, expressions, scaffold,
    )

    /** 式を受け取る項目の値（ルートからのキーの並びで指す。例: expressionAt("filePattern")）。 */
    fun expressionAt(vararg keys: String): ExpressionValue? = expressions[keys.toList()]

    companion object {
        /** スキーマ文書1件を読んだ結果から作る（アプリ側とカーネル側が共有する）。 */
        fun of(parsed: ParsedSchemaDocument, source: DocumentId): WorkspaceSchema = WorkspaceSchema(
            id = parsed.id,
            kind = parsed.kind,
            match = parsed.match,
            tree = parsed.tree,
            declaredEntities = parsed.entities,
            isTemplate = parsed.derive != null,
            source = source,
            derive = parsed.derive,
            constraints = parsed.checks.constraints,
            checkers = parsed.checks.checkers,
            expandedChecks = parsed.checks.expanded,
            vocabularySource = parsed.vocabularySource,
            name = parsed.name,
            description = parsed.description,
            relations = parsed.relations,
            entityTypes = parsed.entityTypes,
            enforcement = parsed.enforcement,
            checkLibrary = parsed.ownLibrary,
            expressions = parsed.expressions,
            scaffold = parsed.scaffold,
        )
    }

    /** このスキーマから導出した、最小の構造のスキーマ（構造の検証はここを通す。P27 段C の C-1）。 */
    val structures: DerivedStructures by lazy { DerivedStructures(tree) }

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
    /** ファイル（YAML 以外も）があるか（file 型の値の存在の検査。読み込んだストアに聞く）。 */
    val fileExists: (String) -> Boolean = { false },
)

/** スキーマ読込の結果（load と、セッション内のスキーマ再構築＝WS-006 が共有する）。 */
class SchemaLoadResult(
    val schemas: List<WorkspaceSchema>,
    val declarations: SemanticDeclarations,
    val errors: List<String>,
    /** ワークスペースの検査の定義の一式（組込み定義体＋利用者の functions / vocabulary）。 */
    val library: CheckLibrary = CheckCatalog.builtinLibrary,
)

object WorkspaceReader {

    /** 組込メタスキーマのID（実体は当面ルート BuiltinSchemas＝C-4 で資源化予定。ルート側の定数はここを参照する）。 */
    const val META_SCHEMA_ID = "builtin-schema"

    /** 関係レジストリ用メタスキーマのID（kind: relations のスキーマ文書に適用）。 */
    const val RELATIONS_SCHEMA_ID = "builtin-relations"

    /**
     * スキーマ文書のテキスト集合からスキーマ束を組み立てる（構造＋意味宣言・`$ref` の横断リンク）。
     * sources は（文書ID, 本文）の相対パス順。
     */
    fun parseSchemas(sources: List<Pair<DocumentId, String>>, metaschema: SchemaTree? = null): SchemaLoadResult {
        val errors = mutableListOf<String>()
        val schemas = mutableListOf<WorkspaceSchema>()
        val registry = mutableListOf<SchemaDocuments.RegistryPart>()
        // 検査の定義と語彙の定義（functions / vocabulary）は、どのスキーマ文書に書いてもワークスペース全体で使える。
        // 先に全文書から集めてから、各スキーマの検査を展開する（root の SchemaLoader と同じ実装＝CheckCatalog）
        val raws = sources.mapNotNull { (id, text) ->
            (runCatching { YamlIO.parseToRaw(text) }.getOrNull() as? Map<*, *>)?.let { id.value.substringAfterLast('/') to it }
        }
        val library = CheckCatalog.libraryOf(raws) { name, e -> errors += "$name: ${e.message ?: e::class.simpleName}" }
        for ((id, text) in sources) {
            val rel = id.value
            val fileName = rel.substringAfterLast('/')
            try {
                // スキーマ文書1件の読み取りは、アプリ側と同じ1実装（SchemaDocuments。P27 段C の C-3a・意図的差分 D-31）
                val parsed = SchemaDocuments.parse(text, fileName.substringBeforeLast('.'), fileName, library, metaschema)
                schemas += WorkspaceSchema.of(parsed, id)
                if (parsed.kind == SchemaKind.RELATIONS) {
                    registry += SchemaDocuments.RegistryPart(fileName, parsed.relations, parsed.entityTypes, parsed.enforcement)
                }
            } catch (e: Exception) {
                errors += "$rel: ${e.message}"
            }
        }
        // 同じ ID のスキーマが複数あれば誤り（文書への割当ては、先に読んだほうが使われる。意図的差分 D-32 ④）
        schemas.groupBy { it.id }.filterValues { it.size > 1 }.forEach { (schemaId, duplicates) ->
            errors += "スキーマID「$schemaId」が重複しています（${duplicates.joinToString(", ") { it.source.value.substringAfterLast('/') }}）"
        }
        // 関係レジストリの集約（複数の文書での重複は誤りにして、先に読んだほうを採る。アプリ側と同じ実装）
        val registryDeclarations = SchemaDocuments.declarationsOf(registry, errors)
        // `$ref` の横断解決（module#name）: 全モジュールの定義をリンクする（SchemaLoader と同じ流儀）
        val catalog = SchemaCatalog.linked(schemas.map { it.id to it.tree.definitions })
        val linked = schemas.map { it.copy(tree = it.tree.copy(definitionCatalog = catalog)) }
        // エンティティ型の表示名の既定（オブジェクト型。P33 第三弾）: レジストリに宣言の無い型は、object の entity を書いた型の label を表示名にする
        val declarations = registryDeclarations.copy(entityTypes = registryDeclarations.entityTypes + objectEntityTypes(linked, registryDeclarations))
        return SchemaLoadResult(linked, declarations, errors, library)
    }

    /**
     * object の同一性（entity）から導くエンティティ型の宣言（レジストリに無い型だけ）。表示名は型（object の定義）の label で、
     * 同じ型を複数の object が宣言していれば label のある最初のもの。レジストリの宣言（label・アスペクトの順序）が常に勝つ。
     */
    private fun objectEntityTypes(schemas: List<WorkspaceSchema>, registry: SemanticDeclarations): List<EntityTypeDef> {
        val declared = registry.entityTypes.map { it.type }.toSet()
        val labels = LinkedHashMap<String, String?>()
        for (schema in schemas) {
            for (field in schema.tree.objectEntities()) {
                val type = field.entity?.type ?: continue
                if (type in declared) continue
                val current = labels[type]
                if (current == null) labels[type] = field.label
            }
        }
        return labels.map { (type, label) -> EntityTypeDef(type = type, label = label) }
    }

    /**
     * スキーマ束の組み立て（C-2）: 利用者スキーマのパース → 組込スキーマの追加（利用者定義が同IDなら
     * 利用者優先＝SchemaLoader と同じ先勝ち規則）→ derive 展開（導出スキーマは宣言スキーマの直後に挿入）。
     * load とセッションのスキーマ再構築（WS-006）が共有する。
     */
    fun buildSchemas(
        schemaSources: List<Pair<DocumentId, String>>,
        builtins: List<WorkspaceSchema>,
        documents: List<Pair<DocumentId, YDocument>>,
        layout: WorkspaceLayout,
    ): SchemaLoadResult {
        val parsed = parseSchemas(schemaSources, BuiltinSchemaResources.metaSchemaTree)
        // 利用者が定義した語彙のキーは、スキーマ文書のメタスキーマの「知っている項目」へ足す（root の SchemaLoader と同じ実装。
        // これが無いと、語彙として定義された書き方が「スキーマに定義されていない項目」と警告される＝BACKLOG UX-105）。
        // 型の名前（組込みの型・利用者の型。P33）も同じ流儀で、メタスキーマの type の選択肢と cases へ足す
        val catalog = parsed.schemas.firstOrNull()?.tree?.definitionCatalog ?: SchemaCatalog.linked(emptyList())
        val typeNames = MetaSchemaTypes.typeNames(catalog)
        val composed = builtins.map { b ->
            if (b.id != META_SCHEMA_ID && b.id != RELATIONS_SCHEMA_ID) return@map b
            val root = CheckCatalog.withVocabularyKeys(b.tree.root, parsed.library, addNewArrays = b.id == META_SCHEMA_ID)
            val tree = b.tree.copy(root = root).let { t -> if (b.id == META_SCHEMA_ID) MetaSchemaTypes.withTypeNames(t, typeNames) else t }
            if (tree == b.tree) b else b.copy(tree = tree)
        }
        val withBuiltins = parsed.schemas + composed.filter { b -> parsed.schemas.none { it.id == b.id } }
        val errors = parsed.errors.toMutableList()
        val expanded = expandDerived(withBuiltins, documents, layout, errors)
        // スキーマ置き場に書かれたスキーマ（組込みスキーマと、derive から導出されたスキーマを除く）の、定義の点検と、
        // 宣言のない関係名の点検（P27 段D の D-a で root から移した。意図的差分 D-33）
        errors += SchemaDefinitionChecks.loadErrors(parsed.schemas, parsed.declarations.relations)
        return SchemaLoadResult(expanded, parsed.declarations, errors, parsed.library)
    }

    /** 導出元にできる文書＝台帳の一般文書のみ（root DerivedSchemas.listYamlFiles と同じ規則）。 */
    private fun eligibleDeriveSource(rel: String, layout: WorkspaceLayout): Boolean =
        !rel.substringAfterLast('/').startsWith(".") &&
            !rel.startsWith("_") && !rel.startsWith(".") && layout.inLedger(rel)

    /** derive 宣言スキーマを導出元文書から展開し、宣言スキーマの直後に挿入した一覧を返す（C-2）。 */
    private fun expandDerived(
        schemas: List<WorkspaceSchema>,
        documents: List<Pair<DocumentId, YDocument>>,
        layout: WorkspaceLayout,
        errors: MutableList<String>,
    ): List<WorkspaceSchema> {
        val declaring = schemas.filter { it.derive != null }
        if (declaring.isEmpty()) return schemas
        val usedIds = schemas.map { it.id }.toMutableSet()
        val byDeclaring = linkedMapOf<String, List<WorkspaceSchema>>()
        for (template in declaring) {
            val derive = template.derive ?: continue
            val baseRoot = template.tree.resolve(template.tree.root) ?: template.tree.root
            val sources = documents
                .filter { (id, _) -> eligibleDeriveSource(id.value, layout) && derive.matchesSource(id.value) }
                .map { (id, d) -> id.value to d.documents.firstOrNull() }
            byDeclaring[template.id] = SchemaDerivation.compile(template.id, baseRoot, derive, sources, usedIds, errors)
                .map { d ->
                    WorkspaceSchema(
                        id = d.id,
                        kind = SchemaKind.DOCUMENT,
                        match = d.match,
                        // 導出スキーマはテンプレートの definitions / catalog を引き継ぐ（$ref は definitionScope で解決される）
                        tree = template.tree.copy(id = d.id, root = d.root),
                        declaredEntities = emptyList(),  // root DerivedSchemas と同じ（導出スキーマに entity 宣言は継がない）
                        isTemplate = false,
                        source = DocumentId(d.sourceRel),
                        // 導出元の語彙（consistency など）は引き継ぐ。展開後の検査はスキーマIDで文書を指すため、派生スキーマのIDで展開し直す
                        constraints = CheckCatalog.rulesOf(template.vocabularySource?.inheritedChecksFor(d.id).orEmpty(), d.id).constraints,
                        expandedChecks = template.vocabularySource?.inheritedChecksFor(d.id).orEmpty(),
                        name = d.name,
                        description = template.description,
                    )
                }
        }
        return schemas.flatMap { s -> listOf(s) + byDeclaring[s.id].orEmpty() }
    }

    /**
     * 文書1件へのスキーマ割当（C-2 でメタスキーマ対応）。schemas ゾーンにはメタスキーマだけを適用し
     * （kind: relations の文書には関係レジストリのメタスキーマ）、一般スキーマの match は及ばない
     * （root Workspace.schemaFor と同じ規則）。load・セッション再構築・増分取り込みが共有する。
     */
    fun assignSchemaId(rel: String, document: YDocument, schemas: List<WorkspaceSchema>, layout: WorkspaceLayout): String? {
        if (layout.zoneOf(rel) == WorkspaceLayout.Zone.SCHEMAS) {
            val metaId = if (isRelationsDoc(document)) RELATIONS_SCHEMA_ID else META_SCHEMA_ID
            return schemas.firstOrNull { it.id == metaId && it.matches(rel) }?.id
        }
        return schemas.firstOrNull { it.matches(rel) }?.id
    }

    /** schemas ゾーンの文書が関係レジストリ（kind: relations）か。 */
    private fun isRelationsDoc(document: YDocument): Boolean {
        val root = document.documents.firstOrNull() as? YNode.Map ?: return false
        return scalarText(root.entries["kind"]) == SchemaKind.RELATIONS.id
    }

    /**
     * ワークスペースを読み取り、working snapshot と派生状態を組み立てる。
     * builtins は組込スキーマ（メタスキーマ等）の注入口（C-2。実体は当面ルート BuiltinSchemas＝
     * C-4 の資源化で core 自身が持つ予定。未注入時はメタスキーマ適用が無いだけで他は同じ）。
     */
    fun load(store: DocumentStore, version: Long = 0, builtins: List<WorkspaceSchema> = emptyList()): LoadedWorkspace {
        val layout = detectLayout(store)
        val listing = store.list()

        // 1) 文書ロード（構成ファイルを除く全YAML。schemas / runs / agents 等の定義・記録ゾーンも
        //    エディタ層の編集対象として文書に含む——スキーマ・リファクタリング（RF-002）は
        //    `_schemas` 文書への EditDocument で自身を書き換える。意味解析への載せ方は derive 側で絞る
        val rawDocuments = linkedMapOf<DocumentId, YDocument>()
        val parseErrors = linkedMapOf<DocumentId, String>()
        for (doc in listing) {
            val rel = doc.id.value
            if (layout.zoneOf(rel) == null && layout.configDir != null &&
                (rel == layout.configDir || rel.startsWith("${layout.configDir}/"))
            ) continue
            if (hiddenPath(rel, layout)) continue
            val text = store.read(doc.id)?.text ?: continue
            try {
                rawDocuments[doc.id] = DefaultYamlCodec.parse(doc.id, text)
            } catch (e: Exception) {
                parseErrors[doc.id] = e.message ?: e.toString()
            }
        }

        // 2) スキーマ束の組み立て（利用者スキーマ→組込→derive 展開。導出元は台帳の一般文書＝C-2）
        val schemaSources = listing.filter { layout.zoneOf(it.id.value) == WorkspaceLayout.Zone.SCHEMAS }
            .mapNotNull { doc -> store.read(doc.id)?.let { doc.id to it.text } }
        val built = buildSchemas(schemaSources, builtins, rawDocuments.map { (id, d) -> id to d }, layout)
        val linked = built.schemas

        // 3) スキーマ割当（`_schemas` 配下はメタスキーマだけ・一般スキーマの match は及ばない）
        val documents = linkedMapOf<DocumentId, WorkspaceDocument>()
        for ((id, parsed) in rawDocuments) {
            val rel = id.value
            documents[id] = WorkspaceDocument(
                document = parsed,
                persistedText = DefaultYamlCodec.dump(parsed),
                schemaId = assignSchemaId(rel, parsed, linked, layout),
                inLedger = layout.inLedger(rel),
            )
        }

        // 4) 構造検証＋意味解析（WorkspaceSession の apply / undo / redo と共有する導出）
        val declarations = built.declarations
        val derived = derive(documents, linked, declarations, layout, version)

        return LoadedWorkspace(
            layout = layout,
            schemas = linked,
            declarations = declarations,
            schemaErrors = built.errors,
            parseErrors = parseErrors,
            snapshot = WorkspaceSnapshot(version, documents),
            derived = derived,
            fileExists = store::exists,
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
        // 利用者が書いたスキーマから導出した、最小の構造のスキーマで検証する（P27 段C の C-1・意図的差分 D-30）
        return sc.structures.validate(root)
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
            schema = sc?.tree,
            schemaId = sc?.id,
            entities = sc?.entities ?: emptyList(),
            documentId = DocumentMeta.idOf(root, rel),
        )
    }

    /**
     * 隠しパスの文書か（root WorkspaceScan.walk と同じ規則＝RA-02 で一致を明示）:
     * 隠しファイルは常に対象外、隠しディレクトリは構成ディレクトリ（`.yde`）自身だけ許可
     * （その配下の更なる隠しディレクトリには降りない）。store.list は全YAMLを返すため、ここで絞る。
     */
    fun hiddenPath(rel: String, layout: WorkspaceLayout): Boolean {
        val parts = rel.split('/')
        if (parts.last().startsWith(".")) return true
        var prefix = ""
        for (dir in parts.dropLast(1)) {
            prefix = if (prefix.isEmpty()) dir else "$prefix/$dir"
            if (dir.startsWith(".") && prefix != layout.configDir) return true
        }
        return false
    }

    /** ストアの内容から構成（レガシー／.yde）を判定する（WorkspaceLayout.of の DocumentStore 版）。 */
    fun detectLayout(store: DocumentStore): WorkspaceLayout {
        val configFile = store.read(DocumentId("${WorkspaceLayout.CONFIG_DIR}/${WorkspaceLayout.CONFIG_FILE}"))
        val hasConfigDir = configFile != null ||
            store.list().any { it.id.value.startsWith("${WorkspaceLayout.CONFIG_DIR}/") }
        if (!hasConfigDir) return WorkspaceLayout.LEGACY
        val ledger = configFile?.let { runCatching { WorkspaceLayout.parseLedgerText(it.text) }.getOrDefault(emptyList()) }.orEmpty()
        val files = configFile?.let { runCatching { WorkspaceLayout.parseFilesText(it.text) }.getOrDefault(FilePolicy.DEFAULT) } ?: FilePolicy.DEFAULT
        return WorkspaceLayout(configDir = WorkspaceLayout.CONFIG_DIR, ledger = ledger, files = files)
    }
}
