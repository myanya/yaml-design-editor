package schema

import java.nio.file.Path

/**
 * 予約ディレクトリ（_agents / _activities）用の組込みスキーマ。
 * エージェント／アクティビティ定義そのものをスキーマ付きYAML文書として、
 * 既存のフォーム編集・検証・差分レビューで管理できるようにする（セルフホスティング）。
 * 利用者が同じ対象にマッチする独自スキーマを _schemas に置いた場合はそちらが優先される。
 *
 * 原文（YAML）は workspace-core の資源 builtin-schemas 配下（各 .yaml）が正（C-4・RC-06 で
 * コード内文字列から資源化＝YM-006b。供給は workspace.BuiltinSchemaResources）。設計意図の要点:
 * - メタスキーマ（R11-02）: FieldDef の再帰は `$ref`（definitions.field）、「type の値ごとに持てる属性」は
 *   `cases`（S-10）、default・values 要素・phases など単一の型で表せない箇所は `any`（検証しない明示。
 *   ARCHITECTURE §4.23）——自機能のドッグフーディング
 * - 関係レジストリのメタスキーマ（R20-02・§4.30）: `_schemas` の `kind: relations` 文書にだけ適用される
 *   （振り分けは core.Workspace.schemaFor）
 */
object BuiltinSchemas {

    /** `_schemas/` に適用されるメタスキーマのID（R11-02）。core.Workspace.schemaFor が参照する（定数の正はコア。C-2）。 */
    const val META_SCHEMA_ID = workspace.WorkspaceReader.META_SCHEMA_ID

    /** 関係レジストリ文書（kind: relations。R20-02）に適用されるメタスキーマのID。 */
    const val RELATIONS_SCHEMA_ID = workspace.WorkspaceReader.RELATIONS_SCHEMA_ID

    private fun yaml(name: String): String = workspace.BuiltinSchemaResources.text(name)

    /** 組込みスキーマ一覧（読み込み失敗はあり得ない前提。失敗時は例外で気付かせる）。match はレガシー配置。 */
    val all: List<SchemaDef> by lazy {
        workspace.BuiltinSchemaResources.names.map { name ->
            SchemaLoader.parseSchemaText(yaml(name), Path.of("(builtin)/$name.yaml"))
        }
    }

    /** 組込みスキーマID → 適用先ゾーン（match の書き換えに使う。R13-02）。 */
    private val zoneById = mapOf(
        "builtin-agent" to WorkspaceLayout.Zone.AGENTS,
        "builtin-activity" to WorkspaceLayout.Zone.ACTIVITIES,
        "builtin-eval" to WorkspaceLayout.Zone.EVALS,
        "builtin-skill" to WorkspaceLayout.Zone.SKILLS,
        "builtin-view" to WorkspaceLayout.Zone.VIEWS,
        META_SCHEMA_ID to WorkspaceLayout.Zone.SCHEMAS,
        RELATIONS_SCHEMA_ID to WorkspaceLayout.Zone.SCHEMAS,
    )

    /**
     * レイアウトに合わせて match を各ゾーンの実ディレクトリへ書き換えた組込みスキーマ一覧（R13-02）。
     * レガシー構成では `all` と同一。SchemaDef は data class のため copy で match だけ差し替える
     * （glob の正規表現は lazy で再コンパイルされる）。
     */
    fun forLayout(layout: WorkspaceLayout): List<SchemaDef> {
        if (layout.configDir == null) return all
        return all.map { builtin ->
            val zone = zoneById[builtin.id] ?: return@map builtin
            builtin.copy(match = "${layout.dir(zone)}/**/*.{yaml,yml}")
        }
    }

    /** 組込みスキーマの原文（テスト用: すべてがメタスキーマ検証を通ることのドッグフーディングに使う）。 */
    internal val allYamlTexts: List<String>
        get() = listOf("schema", "relations", "agent", "activity", "eval", "skill", "view").map(::yaml)

    /**
     * 組込みスキーマをコア reader / session の注入形式へ写す（C-2）。メタスキーマ適用・定義ゾーンの
     * 構造検証を新経路でも成立させるための併存期ブリッジで、C-4 第2弾（cli 接続）で core 自身の供給に揃える。
     */
    fun kernelSchemas(layout: WorkspaceLayout): List<workspace.WorkspaceSchema> =
        forLayout(layout).map { b ->
            workspace.WorkspaceSchema(
                id = b.id,
                kind = b.kind,
                match = b.match,
                structural = b.structural,
                entities = b.entities,
                isTemplate = false,
                source = model.DocumentId(b.source.toString().replace('\\', '/')),
            )
        }
}
