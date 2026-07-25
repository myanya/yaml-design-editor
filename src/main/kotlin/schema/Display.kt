package schema

import model.YNode
import model.scalarText

/**
 * 表示解決（R10-01）。フォームの畳み込みヘッダ・参照候補・バックリンク・ID横ぐし・ビューグリッド・
 * ID検索・エージェントの要約が共有する「要素をどう1行で見せるか」の単一機構。
 * 人間UIとエージェント向け要約（AG-25）の共有契約であり、人間とAIが同じ要約を見る状態を作る。
 *
 * 優先順位: スキーマの `role` 指定 ＞ 予約語（title / name / label、description / summary）＞ id ＞ キー数の要約。
 * schema / index より下位の層（DocScanner のタイトル抽出）からも使うため、schema パッケージに置く
 * （ROADMAP の「core層」は UI 非依存の純ロジック層の意。依存方向は ARCHITECTURE §2）。
 */
object Display {

    /** タイトルの予約語（先勝ち）。既存の索引タイトル抽出（title / name）との互換を保つ順序。 */
    val TITLE_KEYS = listOf("title", "name", "label")

    /** サブタイトルの予約語（先勝ち）。 */
    val SUBTITLE_KEYS = listOf("description", "summary")

    /** 要素（マップ）1件の表示要約。 */
    data class Summary(
        val id: String?,
        val title: String?,
        val subtitle: String?,
        val keyCount: Int,
    )

    /**
     * 要素の表示要約を解決する。field はその要素に対応するスキーマ定義（`resolve` 済みでなくてよい）。
     * スキーマがない・object でない場合は予約語規約だけで解決する。
     */
    fun summarize(node: YNode?, schema: SchemaDef?, field: FieldDef? = null): Summary {
        val map = node as? YNode.Map ?: return Summary(null, null, null, 0)
        val effective = schema?.resolve(field)
        val objectFields =
            if (schema != null && effective?.type == FieldType.OBJECT) {
                schema.effectiveFields(effective.fields, map).fields
            } else null

        fun roleValue(role: FieldRole): String? = objectFields?.firstNotNullOfOrNull { child ->
            val key = child.key ?: return@firstNotNullOfOrNull null
            if ((schema?.resolve(child) ?: child).role == role) scalarText(map.entries[key]) else null
        }

        fun reserved(keys: List<String>): String? = keys.firstNotNullOfOrNull { scalarText(map.entries[it]) }

        val id = objectFields?.firstNotNullOfOrNull { child ->
            val key = child.key ?: return@firstNotNullOfOrNull null
            if ((schema?.resolve(child) ?: child).type == FieldType.ID) scalarText(map.entries[key]) else null
        } ?: scalarText(map.entries["id"])

        return Summary(
            id = id,
            title = roleValue(FieldRole.TITLE) ?: reserved(TITLE_KEYS),
            subtitle = roleValue(FieldRole.SUBTITLE) ?: reserved(SUBTITLE_KEYS),
            keyCount = map.entries.size,
        )
    }

    /** 要素のタイトルのみ（role ＞ 予約語。なければ null）。索引のタイトル抽出（DocScanner）と共用する。 */
    fun titleOf(node: YNode?, schema: SchemaDef?, field: FieldDef? = null): String? =
        summarize(node, schema, field).title

    /**
     * object の畳み込みヘッダ（R10-02）:「ID タイトル（n項目）」。
     * タイトルも ID もなければ fallback（例:「詳細」）にキー数を添える。
     */
    fun headerText(node: YNode?, schema: SchemaDef?, field: FieldDef? = null, fallback: String = "詳細"): String {
        val summary = summarize(node, schema, field)
        val head = listOfNotNull(summary.id, summary.title?.let { clip(it, 40) }).joinToString(" ").ifBlank { fallback }
        return "$head（${summary.keyCount}項目）"
    }

    /** 配列の畳み込みヘッダ（R10-02）:「n件: 先頭要素の要約, …」。 */
    fun seqHeaderText(seq: YNode.Seq, schema: SchemaDef?, itemField: FieldDef? = null, maxItems: Int = 3): String {
        if (seq.items.isEmpty()) return "0 件"
        val head = seq.items.take(maxItems).joinToString(", ") { inline(it, schema, itemField) }
        val more = if (seq.items.size > maxItems) ", …" else ""
        return "${seq.items.size} 件: $head$more"
    }

    /** 任意ノードの1行要約（スカラー=値、オブジェクト=タイトル解決、配列=件数）。 */
    fun inline(node: YNode?, schema: SchemaDef? = null, field: FieldDef? = null, maxLength: Int = 40): String =
        when (node) {
            null -> "（未設定）"
            is YNode.Scalar -> clip(node.value?.toString() ?: "null", maxLength)
            is YNode.Seq -> "（配列 ${node.items.size} 件）"
            is YNode.Map -> {
                val summary = summarize(node, schema, field)
                listOfNotNull(summary.id, summary.title?.let { clip(it, maxLength) }).joinToString(" ")
                    .ifBlank { "（${summary.keyCount}項目）" }
            }
        }

    private fun clip(text: String, maxLength: Int): String {
        val single = text.replace("\n", " ")
        return if (single.length > maxLength) single.take(maxLength) + "…" else single
    }
}
