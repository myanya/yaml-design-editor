package workspace

/**
 * 組込みスキーマの資源供給（C-4・RC-06 で root schema/BuiltinSchemas.kt のコード内文字列から移動＝YM-006b）。
 * 原文（YAML）は resources の builtin-schemas ディレクトリ配下（各 .yaml）が正で、root の BuiltinSchemas（SchemaDef 形）と
 * コア側の注入（WorkspaceSchema 形。C-4 第2弾で cli へ接続）が同じ原文を読む。
 * 資源名 = 組込みID の接尾辞（builtin-schema → schema.yaml。メタスキーマだけ慣例で schema）。
 */
object BuiltinSchemaResources {

    /** 資源名（読み込み・パースの順序もこの並び＝従来の BuiltinSchemas.all と同一）。 */
    val names: List<String> = listOf("agent", "activity", "eval", "skill", "view", "schema", "relations")

    /** 1件の原文を読む（読み込み失敗はあり得ない前提。失敗時は例外で気付かせる）。 */
    fun text(name: String): String =
        requireNotNull(BuiltinSchemaResources::class.java.getResourceAsStream("/builtin-schemas/$name.yaml")) {
            "組込みスキーマ資源が見つかりません: $name"
        }.use { it.readBytes().decodeToString() }

    /** 組込みスキーマID → 適用先ゾーン（match の書き換え規則は root BuiltinSchemas.forLayout と同一。R13-02）。 */
    private val zoneById = mapOf(
        "builtin-agent" to schema.WorkspaceLayout.Zone.AGENTS,
        "builtin-activity" to schema.WorkspaceLayout.Zone.ACTIVITIES,
        "builtin-eval" to schema.WorkspaceLayout.Zone.EVALS,
        "builtin-skill" to schema.WorkspaceLayout.Zone.SKILLS,
        "builtin-view" to schema.WorkspaceLayout.Zone.VIEWS,
        WorkspaceReader.META_SCHEMA_ID to schema.WorkspaceLayout.Zone.SCHEMAS,
        WorkspaceReader.RELATIONS_SCHEMA_ID to schema.WorkspaceLayout.Zone.SCHEMAS,
    )

    /**
     * 組込みスキーマをコアの WorkspaceSchema 形で供給する（C-4 第2弾・RC-07。cli / ヘッドレスの
     * セッション注入用）。レイアウトに合わせて match を各ゾーンの実ディレクトリへ書き換える。
     * root（アプリ）側は併存期は従来どおり root SchemaLoader のパース（BuiltinSchemas.kernelSchemas）を
     * 使う——同じ資源原文の2経路パースで、統一は P27（SchemaDef 解体）で行う。
     */
    fun kernelSchemas(layout: schema.WorkspaceLayout): List<WorkspaceSchema> {
        val parsed = WorkspaceReader.parseSchemas(names.map { model.DocumentId("(builtin)/$it.yaml") to text(it) })
        require(parsed.errors.isEmpty()) { "組込みスキーマのパースに失敗しました: ${parsed.errors.first()}" }
        if (layout.configDir == null) return parsed.schemas
        return parsed.schemas.map { b ->
            val zone = zoneById[b.id] ?: return@map b
            WorkspaceSchema(
                id = b.id, kind = b.kind, match = "${layout.dir(zone)}/**/*.{yaml,yml}",
                structural = b.structural, entities = b.entities,
                isTemplate = b.isTemplate, source = b.source, derive = b.derive,
            )
        }
    }
}
