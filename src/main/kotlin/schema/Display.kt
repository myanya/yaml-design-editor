package schema

import model.YNode

/**
 * 表示解決（R10-01）の SchemaDef 向けファサード。実装本体は semantic-core の DisplayCore
 * （3層コア移行 SM-001 で分離。最終リネーム時に統合する）。
 * フォームの畳み込みヘッダ・参照候補・バックリンク・ID横ぐし・ビューグリッド・ID検索・
 * エージェントの要約が共有する「要素をどう1行で見せるか」の単一機構であることは従来どおり。
 */
object Display {

    /** タイトルの予約語（先勝ち）。 */
    val TITLE_KEYS get() = DisplayCore.TITLE_KEYS

    /** サブタイトルの予約語（先勝ち）。 */
    val SUBTITLE_KEYS get() = DisplayCore.SUBTITLE_KEYS

    /** テーブルのセルとして1行で表示・編集できる型。 */
    val CELL_TYPES get() = DisplayCore.CELL_TYPES

    /** 自動判定でテーブル表示にする列数の上限。 */
    const val MAX_AUTO_TABLE_COLUMNS = DisplayCore.MAX_AUTO_TABLE_COLUMNS

    fun summarize(
        node: YNode?,
        schema: SchemaDef?,
        field: FieldDef? = null,
        titleLookup: ((String) -> String?)? = null,
    ): DisplayCore.Summary = DisplayCore.summarize(node, schema?.structural, field, titleLookup)

    /** 要素のタイトルのみ（role ＞ 予約語。なければ null）。 */
    fun titleOf(node: YNode?, schema: SchemaDef?, field: FieldDef? = null): String? =
        DisplayCore.titleOf(node, schema?.structural, field)

    /** object の畳み込みヘッダ（R10-02 / UX-71）。 */
    fun headerText(
        node: YNode?,
        schema: SchemaDef?,
        field: FieldDef? = null,
        fallback: String = "詳細",
        titleLookup: ((String) -> String?)? = null,
    ): String = DisplayCore.headerText(node, schema?.structural, field, fallback, titleLookup)

    /** 配列の畳み込みヘッダ（R10-02）。 */
    fun seqHeaderText(
        seq: YNode.Seq,
        schema: SchemaDef?,
        itemField: FieldDef? = null,
        maxItems: Int = 3,
        titleLookup: ((String) -> String?)? = null,
    ): String = DisplayCore.seqHeaderText(seq, schema?.structural, itemField, maxItems, titleLookup)

    /** 任意ノードの1行要約（スカラー=値、オブジェクト=タイトル解決、配列=件数）。 */
    fun inline(
        node: YNode?,
        schema: SchemaDef? = null,
        field: FieldDef? = null,
        maxLength: Int = 40,
        titleLookup: ((String) -> String?)? = null,
    ): String = DisplayCore.inline(node, schema?.structural, field, maxLength, titleLookup)

    /** 配列・map をテーブル表示すべきときの列定義（R12-06）。 */
    fun tableColumns(schema: SchemaDef?, field: FieldDef?): List<FieldDef>? =
        DisplayCore.tableColumns(schema?.structural, field)
}
