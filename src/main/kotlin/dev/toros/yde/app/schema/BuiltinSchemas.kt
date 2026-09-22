package dev.toros.yde.app.schema

import dev.toros.yde.workspace.WorkspaceReader
import dev.toros.yde.workspace.WorkspaceSchema
import dev.toros.yde.workspace.layout.WorkspaceLayout
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
    const val META_SCHEMA_ID = WorkspaceReader.META_SCHEMA_ID

    /** 関係レジストリ文書（kind: relations。R20-02）に適用されるメタスキーマのID。 */
    const val RELATIONS_SCHEMA_ID = WorkspaceReader.RELATIONS_SCHEMA_ID

    private fun yaml(name: String): String = dev.toros.yde.workspace.BuiltinSchemaResources.text(name)

    /**
     * レイアウトに合わせて match を各ゾーンの実ディレクトリへ書き換えた組込みスキーマ一覧（R13-02）。
     * 読み取りは、カーネルの1経路（workspace.BuiltinSchemaResources.kernelSchemas）——以前は同じ資源の原文を、root とカーネルで
     * 別々に読んでいた（P27 段C の C-3b で統一・意図的差分 D-32）。ここは、アプリの形（SchemaDef）へ包むだけ。
     */
    fun forLayout(layout: WorkspaceLayout): List<SchemaDef> =
        kernelSchemas(layout).map { SchemaDef(it, Path.of(it.source.value)) }

    /** 組込みスキーマ一覧（match はレガシー配置）。 */
    val all: List<SchemaDef> by lazy { forLayout(WorkspaceLayout.LEGACY) }

    /** 組込みスキーマの原文（テスト用: すべてがメタスキーマ検証を通ることのドッグフーディングに使う）。 */
    internal val allYamlTexts: List<String>
        get() = listOf("schema", "relations", "agent", "activity", "eval", "skill", "view").map(::yaml)

    /** 組込みスキーマを、カーネルの reader / session へ注入する形で返す（実体は workspace-core の資源供給）。 */
    fun kernelSchemas(layout: WorkspaceLayout): List<WorkspaceSchema> =
        dev.toros.yde.workspace.BuiltinSchemaResources.kernelSchemas(layout)
}
