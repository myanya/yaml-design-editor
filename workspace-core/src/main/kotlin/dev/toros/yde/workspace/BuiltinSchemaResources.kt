package dev.toros.yde.workspace

import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.semantic.schema.SchemaCatalog
import dev.toros.yde.semantic.schema.SchemaTree
import dev.toros.yde.yaml.model.DocumentId

/**
 * 組込みスキーマの資源供給（C-4・RC-06 で root schema/BuiltinSchemas.kt のコード内文字列から移動＝YM-006b）。
 * 原文（YAML）は resources の builtin-schemas ディレクトリ配下（各 .yaml）が正で、root の BuiltinSchemas（SchemaDef 形）と
 * コア側の注入（WorkspaceSchema 形。C-4 第2弾で cli へ接続）が同じ原文を読む。
 * 資源名 = 組込みID の接尾辞（builtin-schema → schema.yaml。メタスキーマだけ慣例で schema）。
 */
object BuiltinSchemaResources {

    /** 資源名（読み込み・パースの順序もこの並び＝従来の BuiltinSchemas.all と同一）。 */
    val names: List<String> = listOf("agent", "activity", "eval", "skill", "view", "report", "schema", "relations")

    /** 1件の原文を読む（読み込み失敗はあり得ない前提。失敗時は例外で気付かせる）。 */
    fun text(name: String): String =
        requireNotNull(BuiltinSchemaResources::class.java.getResourceAsStream("/builtin-schemas/$name.yaml")) {
            "組込みスキーマ資源が見つかりません: $name"
        }.use { it.readBytes().decodeToString() }

    /**
     * メタスキーマ（builtin-schema）とビューのメタスキーマ（builtin-view）の項目の木。スキーマ文書・ビュー定義のどの項目が
     * 値の式を受け取るか（expression: true の印。P31）は、この木だけが知る——読み取り（ExpressionFields）が沿って辿る。
     */
    val metaSchemaTree: SchemaTree by lazy { tree("schema") }
    val viewSchemaTree: SchemaTree by lazy { tree("view") }

    private fun tree(name: String): SchemaTree {
        val parsed = WorkspaceReader.parseSchemas(listOf(DocumentId("(builtin)/$name.yaml") to text(name)))
        require(parsed.errors.isEmpty()) { "組込みスキーマのパースに失敗しました: ${parsed.errors.first()}" }
        return parsed.schemas.single().tree
    }

    /** 組込みスキーマID → 適用先ゾーン（match の書き換え規則は root BuiltinSchemas.forLayout と同一。R13-02）。 */
    private val zoneById = mapOf(
        "builtin-agent" to WorkspaceLayout.Zone.AGENTS,
        "builtin-activity" to WorkspaceLayout.Zone.ACTIVITIES,
        "builtin-eval" to WorkspaceLayout.Zone.EVALS,
        "builtin-skill" to WorkspaceLayout.Zone.SKILLS,
        "builtin-view" to WorkspaceLayout.Zone.VIEWS,
        "builtin-report" to WorkspaceLayout.Zone.REPORTS,
        WorkspaceReader.META_SCHEMA_ID to WorkspaceLayout.Zone.SCHEMAS,
        WorkspaceReader.RELATIONS_SCHEMA_ID to WorkspaceLayout.Zone.SCHEMAS,
    )

    /**
     * 組込みスキーマをコアの WorkspaceSchema 形で供給する（C-4 第2弾・RC-07。cli / ヘッドレスの
     * セッション注入用）。レイアウトに合わせて match を各ゾーンの実ディレクトリへ書き換える。
     * root（アプリ）側は併存期は従来どおり root SchemaLoader のパース（BuiltinSchemas.kernelSchemas）を
     * 使う——同じ資源原文の2経路パースで、統一は P27（SchemaDef 解体）で行う。
     */
    fun kernelSchemas(layout: WorkspaceLayout): List<WorkspaceSchema> {
        val parsed = WorkspaceReader.parseSchemas(names.map { DocumentId("(builtin)/$it.yaml") to text(it) })
        require(parsed.errors.isEmpty()) { "組込みスキーマのパースに失敗しました: ${parsed.errors.first()}" }
        // メタスキーマの type には、組込みの型の名前（text など。P33）を足す（利用者の型は WorkspaceReader.buildSchemas が足す）
        val builtinTypeNames = MetaSchemaTypes.typeNames(SchemaCatalog.linked(emptyList()))
        return parsed.schemas.map { b ->
            val withTypes = if (b.id == WorkspaceReader.META_SCHEMA_ID) b.copy(tree = MetaSchemaTypes.withTypeNames(b.tree, builtinTypeNames)) else b
            val zone = zoneById[b.id]
            if (layout.configDir == null || zone == null) withTypes else withTypes.copy(match = "${layout.dir(zone)}/**/*.{yaml,yml}")
        }
    }
}
