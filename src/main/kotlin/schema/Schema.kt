package schema

import workspace.BuiltinSchemaResources
import workspace.CheckCatalog
import workspace.FileSystemDocumentStore
import workspace.SchemaDefinitionChecks
import workspace.SchemaDocuments
import workspace.SchemaIssue
import workspace.WorkspaceSchema
import workspace.WorkspaceReader
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import model.YNode
import model.parseYPath
import model.scalarText

// 詳細文書の存在（coverage）は、組込み定義体（builtin-definitions/coverage.yaml）が作る検査になった（P29 Phase 6 第3段）。
// 専用のデータ型（CoverageRule）と読み取りコード（parseCoverage）は削除した——coverage は constraints へ展開される語彙。

// DeriveDef / DeriveTypeRule（R11-03）は semantic-core の schema/SchemaDerivation.kt へ移動（C-2。
// 展開コアを reader / session と共有するため）。同一パッケージのためこのファイルの利用箇所は変更なし。

/**
 * スキーマ 1 件。`match` の glob にマッチするファイルへ適用される。
 * スキーマの中身の原本はカーネルの WorkspaceSchema（kernel）で、ここは、アプリに要るもの（スキーマ定義文書のファイルの場所）を
 * 足すだけの薄い型（P27 段C の C-3b・意図的差分 D-32）。スキーマを読むのはカーネルの経路だけで、アプリとカーネルは同じ中身を見る。
 * 中身は変えられない（写しを作って一部を変えるには withDefinitionCatalog など、カーネルの型の写しを包み直す）。
 */
class SchemaDef(
    /** スキーマの中身の原本（カーネルの型）。 */
    val kernel: WorkspaceSchema,
    /** スキーマ定義文書のファイル（派生スキーマでは導出元の文書・組込みスキーマでは `(builtin)/名前.yaml`）。 */
    val source: Path,
) {
    /** 中身を直接渡して作る（テストと、1件だけを組み立てる場面用）。ふつうは SchemaLoader・Workspace が、カーネルの読み込みの結果から作る。 */
    constructor(
        id: String,
        name: String,
        description: String? = null,
        match: String,
        root: FieldDef,
        definitions: Map<String, FieldDef> = emptyMap(),
        constraints: List<ConstraintRule> = emptyList(),
        expandedChecks: List<ExpandedCheck> = emptyList(),
        checkers: List<CheckerRule> = emptyList(),
        phases: PhasesDef? = null,
        entities: List<EntityDecl> = emptyList(),
        relations: List<RelationDef> = emptyList(),
        entityTypes: List<EntityTypeDef> = emptyList(),
        enforcement: EnforcementDef? = null,
        source: Path,
        kind: SchemaKind = SchemaKind.DOCUMENT,
        derive: DeriveDef? = null,
        definitionCatalog: SchemaCatalog = SchemaCatalog.local(id, definitions),
        checkLibrary: CheckLibrary = CheckLibrary(),
        vocabularySource: VocabularySource? = null,
    ) : this(
        WorkspaceSchema(
            id = id, kind = kind, match = match,
            tree = SchemaTree(id, root, definitions, phases, definitionCatalog),
            entities = entities, isTemplate = derive != null,
            source = model.DocumentId(source.toString().replace('\\', '/')),
            derive = derive, constraints = constraints, checkers = checkers, expandedChecks = expandedChecks,
            vocabularySource = vocabularySource, name = name, description = description,
            relations = relations, entityTypes = entityTypes, enforcement = enforcement, checkLibrary = checkLibrary,
        ),
        source,
    )

    val id: String get() = kernel.id
    val name: String get() = kernel.name
    val description: String? get() = kernel.description
    val match: String get() = kernel.match
    val root: FieldDef get() = kernel.tree.root
    val definitions: Map<String, FieldDef> get() = kernel.tree.definitions

    /**
     * 検査（基本の書き方へ展開済み。P29 Phase 6）: `constraints` に直接書いた検査と、定義を名前で使った検査、
     * 語彙（`consistency` など。組込み定義体・利用者の定義が作る）から展開された検査を、展開順に持つ。
     */
    val constraints: List<ConstraintRule> get() = kernel.constraints

    /**
     * constraints の各検査の、展開後の姿（YAML を読んだままの値）と由来（どこに書かれ、どの定義から展開されたか）。
     * 定義エラーの場所の表示と、CLI `checks`（展開の透明性。CALC-29）が使う。constraints と同じ並び。
     */
    val expandedChecks: List<ExpandedCheck> get() = kernel.expandedChecks

    /** 専用チェッカーを使う検査（SP-13。遷移検証 flow など。語彙と定義の使用を展開した後のもの）。 */
    val checkers: List<CheckerRule> get() = kernel.checkers
    val phases: PhasesDef? get() = kernel.tree.phases

    /** エンティティ型の宣言（R20-01。ARCHITECTURE §4.30）。空 = 宣言なし（従来どおり）。 */
    val entities: List<EntityDecl> get() = kernel.entities

    /** 関係レジストリの内容（kind: relations のみ。R20-02）。 */
    val relations: List<RelationDef> get() = kernel.relations

    /** エンティティ型のレジストリ宣言（kind: relations のみ。表示名・アスペクト順序。R23-01。§4.33）。 */
    val entityTypes: List<EntityTypeDef> get() = kernel.entityTypes

    /** 強制スイッチ（kind: relations のみ。R21-01。ARCHITECTURE §4.31）。 */
    val enforcement: EnforcementDef? get() = kernel.enforcement
    val kind: SchemaKind get() = kernel.kind

    /** 派生スキーマの宣言（R11-03）。非 null のスキーマはテンプレートであり、自身は文書にマッチしない。 */
    val derive: DeriveDef? get() = kernel.derive

    /** このスキーマ文書が宣言する検査の定義と語彙の定義（functions / vocabulary）。 */
    val checkLibrary: CheckLibrary get() = kernel.checkLibrary

    /** constraints の各検査の由来（constraints と同じ並び）。 */
    val constraintOrigins: List<String> get() = expandedChecks.filter { !it.usesChecker }.map { it.origin }

    /** checkers の各検査の由来（checkers と同じ並び）。 */
    val checkerOrigins: List<String> get() = expandedChecks.filter { it.usesChecker }.map { it.origin }

    /** 利用者が書く項目の木と、その読み方（$ref解決・cases合成・フェーズに応じた必須・パス解決）。カーネルの型のものをそのまま使う。 */
    val tree: SchemaTree get() = kernel.tree

    /** このスキーマから導出した、最小の構造のスキーマ（構造の検証はここを通す。P27 段C の C-1。カーネルの型のものをそのまま使う）。 */
    val structures: workspace.DerivedStructures get() = kernel.structures

    /** ワークスペース相対パス（区切りは '/'）がこのスキーマの対象か。derive 宣言スキーマはマッチしない。 */
    fun matches(relPath: String): Boolean = kernel.matches(relPath)

    /** `$ref` の参照先。非修飾名はフィールドの定義元、修飾名は `module#name` から引く。 */
    internal fun definitionTarget(field: FieldDef): Pair<DefinitionKey, FieldDef>? = tree.definitionTarget(field)

    /** 他のモジュールの定義もつないだ写し（スキーマ編集画面のプレビューなど、1件だけを読んだ場面用）。 */
    internal fun withDefinitionCatalog(catalog: SchemaCatalog): SchemaDef =
        SchemaDef(kernel.copy(tree = kernel.tree.copy(definitionCatalog = catalog)), source)

    /**
     * フィールドが参照する named definition の修飾名（`module#name`）。`$ref` でなければ null。
     * ビュー定義の一級参照・リント（R14-02）が「このフィールドはどの定義か」を外部から判定するのに使う。
     */
    fun definitionRefOf(field: FieldDef?): String? = field?.let { f -> definitionTarget(f)?.first?.toString() }

    /** $ref を1段ずつ解決する。循環はデータ走査側が有限なので許容する。 */
    fun resolve(field: FieldDef?): FieldDef? = tree.resolve(field)

    override fun toString(): String = "SchemaDef($id, $source)"
}

/** 文書ルートから現在フェーズの申告値を読む（phases 未宣言なら null）。 */
fun SchemaDef.currentPhaseValue(root: YNode?): String? = tree.currentPhaseValue(root)

/** 現在フェーズの順序位置。未申告・未知の値は先頭フェーズ（最も緩い判定）。phases 未宣言なら null。 */
fun SchemaDef.currentPhaseIndex(root: YNode?): Int? = tree.currentPhaseIndex(root)

/**
 * フェーズを考慮した実効 required。requiredFrom は「そのフェーズ以降で必須」（累積的）。
 * 検証・進捗計算・フォーム表示はこの判定を共有する（ARCHITECTURE §4.15）。
 */
fun SchemaDef.requiredAt(field: FieldDef, phaseIndex: Int?): Boolean = tree.requiredAt(field, phaseIndex)

/**
 * object の子フィールド一覧に、データノードの enum 値に応じた case フィールドを合成して返す。
 * object のフィールドを列挙する側（検証・フォーム・索引・AI変換・初期値生成）は必ずこの関数を通すこと。
 * 実装は yaml-core の SchemaTree（YM-004b）。
 */
fun SchemaDef.effectiveFields(fields: List<FieldDef>, node: YNode.Map?): EffectiveFields =
    tree.effectiveFields(fields, node)

/**
 * データパスに対応するフィールド定義をスキーマを辿って解決する（object の子は effectiveFields＝
 * enum の cases 合成、$ref は resolve を通す）。旧 core/Views.fieldAt の移設先（R23-01。ARCHITECTURE §4.33）。
 * 実装は yaml-core の SchemaTree（YM-004b）。`Views.fieldAt` は互換のためこの関数へ委譲する。
 */
fun SchemaDef.fieldAt(root: YNode, path: model.YPath): FieldDef? = tree.fieldAt(root, path)

/**
 * スキーマ定義自体を検証するメタスキーマ検証。実体はカーネルの SchemaDefinitionChecks（P27 段D の D-a で移した。意図的差分 D-33）。
 * ワークスペースを開くときの点検は、カーネルのスキーマの読み込み（WorkspaceReader.buildSchemas）が行う。ここは、1件だけを読んだ場面
 * （スキーマ編集画面のプレビュー・スキーマの生成の入口の保証）の窓口。
 */
fun SchemaDef.validateDefinition(): List<SchemaIssue> = SchemaDefinitionChecks.issuesOf(kernel)

/** プレビューや新規作成に使うサンプルYAMLツリーを生成する。 */
fun SchemaDef.generateSample(): YNode {
    fun create(original: FieldDef, depth: Int, stack: Set<DefinitionKey>): YNode {
        if (depth > 16) return YNode.Map()
        val reference = definitionTarget(original)?.first
        if (reference != null && reference in stack) return YNode.Map()
        val nextStack = if (reference == null) stack else stack + reference
        val field = resolve(original) ?: original
        if (field.default != null) return YamlIO.fromAny(field.default)
        return when (field.type) {
            FieldType.OBJECT -> {
                val entries = LinkedHashMap<String, YNode>().apply {
                    field.fields.forEach { child -> child.key?.let { put(it, create(child, depth + 1, nextStack)) } }
                }
                // enum の生成値（default または先頭の選択肢）に応じた case フィールドを実効順で合成する
                val ordered = effectiveFields(field.fields, YNode.Map(entries)).fields
                YNode.Map(LinkedHashMap<String, YNode>().apply {
                    ordered.forEach { child ->
                        val key = child.key ?: return@forEach
                        put(key, entries[key] ?: create(child, depth + 1, nextStack))
                    }
                })
            }
            FieldType.ARRAY -> YNode.Seq(
                if (field.item?.let { item -> definitionTarget(item)?.first?.let { it in nextStack } } == true) mutableListOf()
                else field.item?.let { mutableListOf(create(it, depth + 1, nextStack)) } ?: mutableListOf()
            )
            FieldType.MAP -> YNode.Map()
            FieldType.BOOLEAN -> YNode.Scalar(false)
            FieldType.INT, FieldType.NUMBER -> YNode.Scalar(0)
            FieldType.ENUM -> YNode.Scalar(field.values.firstOrNull() ?: "")
            FieldType.DATE -> YNode.Scalar("2026-01-01")
            FieldType.ID -> YNode.Scalar("SAMPLE-001")
            // 必須スカラーは空文字にせず型別の例値を入れる（UX-28。空文字は必須エラーになりプレビューが赤くなる）
            FieldType.REF -> YNode.Scalar(if (field.required || field.requiredFrom != null) "SAMPLE-001" else "")
            FieldType.TEXT, FieldType.STRING ->
                YNode.Scalar(if (field.required || field.requiredFrom != null) "サンプル" else "")
            FieldType.ANY -> YNode.Scalar("")
        }
    }
    return create(root, 0, emptySet())
}

/**
 * ワークスペース直下の `_schemas/` からスキーマ定義（*.yaml / *.yml）を読み込む。
 */
object SchemaLoader {

    // 予約ディレクトリ名の正は WorkspaceLayout.Zone（workspace-core。WS-001）。ここは互換の同値リテラル
    const val SCHEMA_DIR = "_schemas"

    /** エージェント実行の予約ディレクトリ（セルフホスティング。ROADMAP §4.2）。 */
    const val AGENTS_DIR = "_agents"
    const val ACTIVITIES_DIR = "_activities"
    const val RUNS_DIR = "_runs"
    const val EVALS_DIR = "_evals"
    const val SKILLS_DIR = "_skills"
    const val VIEWS_DIR = "_views"

    data class LoadResult(
        val schemas: List<SchemaDef>,
        val errors: List<String>,
        /** 関係レジストリ（R20-02）: `kind: relations` 文書から集約した name → RelationDef。 */
        val relations: Map<String, RelationDef> = emptyMap(),
        /** 強制スイッチ（R21-01）: レジストリ文書の enforcement 節から集約（未宣言キーは null）。 */
        val enforcement: EnforcementDef = EnforcementDef(),
        /** エンティティ型レジストリ（R23-01。§4.33）: `entities:` 節から集約した type → EntityTypeDef。 */
        val entityTypes: Map<String, EntityTypeDef> = emptyMap(),
    )

    fun loadAll(workspace: Path): List<SchemaDef> = loadAllWithErrors(workspace).schemas

    /**
     * ワークスペースのスキーマを読む。読むのはカーネルの経路（WorkspaceReader＝スキーマ文書・組込みスキーマ・derive の展開）だけで、
     * ここは、その結果から SchemaDef を作るだけ（P27 段C の C-3b・意図的差分 D-32。スキーマの定義の点検と、宣言のない関係名の点検も、
     * カーネルの読み込みが行う＝P27 段D の D-a・意図的差分 D-33）。
     * アプリ本体（core.Workspace）は、開いたセッションが読んだ結果を fromKernel へ渡す（スキーマを読み直さない）。
     */
    fun loadAllWithErrors(workspace: Path, layout: WorkspaceLayout = WorkspaceLayout.of(workspace)): LoadResult {
        if (!Files.isDirectory(workspace)) return LoadResult(emptyList(), emptyList())
        val loaded = WorkspaceReader.load(
            FileSystemDocumentStore(workspace), builtins = BuiltinSchemaResources.kernelSchemas(layout),
        )
        return fromKernel(workspace, loaded.schemas, loaded.declarations, loaded.schemaErrors)
    }

    /**
     * カーネルが読んだスキーマの一式から、アプリの形（SchemaDef と、関係レジストリの集約）を作る。
     * 読み込みエラーは、カーネルのものをそのまま使う（スキーマの定義の点検と、宣言のない関係名の点検も、カーネルの読み込みが行う。
     * P27 段D の D-a・意図的差分 D-33）。
     */
    fun fromKernel(
        workspace: Path,
        kernelSchemas: List<WorkspaceSchema>,
        declarations: semantic.SemanticDeclarations,
        kernelErrors: List<String>,
    ): LoadResult = LoadResult(
        kernelSchemas.map { k -> SchemaDef(k, sourcePathOf(workspace, k)) },
        kernelErrors,
        declarations.relations,
        declarations.enforcement,
        declarations.entityTypes.associateBy { it.type },
    )

    /** カーネルの座標（DocumentId）を、ファイルの場所へ写す。組込みスキーマ（`(builtin)/名前.yaml`）は、そのままの名前のパス。 */
    private fun sourcePathOf(workspace: Path, schema: WorkspaceSchema): Path =
        if (schema.source.value.startsWith("(builtin)/")) Path.of(schema.source.value) else workspace.resolve(schema.source.value)

    /** 組込み定義体に直接書かれた検査の、出どころの文書名（RuleOrigin.owner）。実体は workspace-core の CheckCatalog。 */
    const val BUILTIN_OWNER = CheckCatalog.BUILTIN_OWNER

    /** 組込み定義体（同梱の YAML。workspace-core の資源）。利用者の定義と同じ仕組みで読み込む。 */
    val builtinLibrary: CheckLibrary get() = CheckCatalog.builtinLibrary

    /**
     * 組込み定義体に直接書かれた検査（constraints）。特定のスキーマに属さない検査として、ワークスペースのどの文書に
     * 定義された要素にも適用する（エンティティの整合など）。利用者も、kind: library の文書に同じように書ける。
     */
    val builtinRules: List<ConstraintRule> get() = CheckCatalog.builtinRules

    /** 組込み定義体に直接書かれた、専用チェッカーを使う検査。 */
    val builtinCheckers: List<CheckerRule> get() = CheckCatalog.builtinCheckers

    /** builtinRules の展開後の姿と由来（CLI `checks` の表示用。展開の透明性 CALC-29）。 */
    val builtinChecks: List<ExpandedCheck> get() = CheckCatalog.builtinChecks

    /**
     * スキーマ1件をパースする。library は検査の展開に使う定義の一式（ワークスペース全体で集めたもの）。
     * null のとき（単体のパース）は、組込み定義体とこの文書自身の定義で展開する。
     */
    fun parseSchemaText(
        text: String,
        source: Path,
        availableSchemas: List<SchemaDef> = emptyList(),
        library: CheckLibrary? = null,
    ): SchemaDef {
        // スキーマ文書1件の読み取りは、カーネルと同じ1実装（workspace-core の SchemaDocuments。P27 段C の C-3a）。
        // ここは、読み取りの結果に、アプリに要るもの（ファイルの場所）を足して SchemaDef にするだけ
        val fileName = source.fileName.toString()
        val document = SchemaDocuments.parse(text, fileName.substringBeforeLast('.'), fileName, library)
        val parsed = SchemaDef(WorkspaceSchema.of(document, model.DocumentId(source.toString().replace('\\', '/'))), source)
        if (availableSchemas.isEmpty()) return parsed
        val modules = availableSchemas.filter { it.id != parsed.id }.map { it.id to it.definitions } +
            (parsed.id to parsed.definitions)
        val catalog = SchemaCatalog.linked(modules)
        return parsed.withDefinitionCatalog(catalog)
    }

    /** 導出宣言（R16-02/03a 共通語彙）のパース。ビューの computed（Views.parseView）と共有する。 */
    internal fun parseDerivationSelector(raw: Any?, where: String): DerivationSelector {
        val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります")
        return DerivationSelector(
            schemaId = m["schemaId"]?.toString(),
            fileGlob = m["fileGlob"]?.toString(),
        )
    }

    internal fun parseDerivation(raw: Any?, where: String): DerivationDef {
        val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります")
        val source = parseDerivationSelector(m["source"] ?: error("$where.source が必要です"), "$where.source")
        require(!source.isEmpty) { "$where.source には schemaId か fileGlob が必要です" }
        val lookup = (m["lookup"] as? Map<*, *>)?.let { lm ->
            DerivationLookup(
                from = DerivationSelector(lm["schemaId"]?.toString(), lm["fileGlob"]?.toString()),
                via = parseYPath(lm["via"]?.toString() ?: error("$where.lookup.via が必要です")),
            )
        }
        return DerivationDef(
            lookup = lookup,
            source = source,
            path = parseYPath(m["path"]?.toString() ?: error("$where.path が必要です")),
            values = (m["values"] as? List<*>)?.mapIndexed { i, v ->
                parseDerivationSelector(v, "$where.values[$i]")
            } ?: emptyList(),
            map = (m["map"] as? Map<*, *>)?.entries
                ?.associate { it.key.toString() to (it.value?.toString() ?: "") } ?: emptyMap(),
            default = m["default"]?.toString(),
        )
    }

    private fun parseSchemaFile(file: Path): SchemaDef = parseSchemaText(Files.readString(file), file)
}
