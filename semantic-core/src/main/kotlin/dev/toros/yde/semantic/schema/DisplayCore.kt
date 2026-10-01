package dev.toros.yde.semantic.schema

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.scalarText

/**
 * 表示解決（R10-01）の実装本体（3層コア移行 SM-001 で Display から分離）。
 * 構造コア（SchemaTree）だけに依存し、ファイルI/O・SchemaDef を知らない。
 * ルート側の `Display` は SchemaDef 向けの委譲ファサード（最終リネーム時に統合する）。
 *
 * 優先順位: スキーマの `role` 指定 ＞ 予約語（title / name / label、description / summary）＞ id ＞ キー数の要約。
 */
object DisplayCore {

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
     * titleLookup（任意）は「要素自身にタイトルがないとき、論理IDから他出現のタイトルを借りる」注入点（UX-71）。
     */
    fun summarize(
        node: YNode?,
        schema: SchemaTree?,
        field: FieldDef? = null,
        titleLookup: ((String) -> String?)? = null,
    ): Summary {
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
            title = roleValue(FieldRole.TITLE) ?: reserved(TITLE_KEYS) ?: id?.let { titleLookup?.invoke(it) },
            subtitle = roleValue(FieldRole.SUBTITLE) ?: reserved(SUBTITLE_KEYS),
            keyCount = map.entries.size,
        )
    }

    /** 要素のタイトルのみ（role ＞ 予約語。なければ null）。索引のタイトル抽出（走査カーネル）と共用する。 */
    fun titleOf(node: YNode?, schema: SchemaTree?, field: FieldDef? = null): String? =
        summarize(node, schema, field).title

    /**
     * object の畳み込みヘッダ（R10-02 / UX-71）:「ID タイトル — サブタイトル（n項目）」。
     * タイトルも ID もなければ fallback（例:「詳細」）にキー数を添える。
     */
    fun headerText(
        node: YNode?,
        schema: SchemaTree?,
        field: FieldDef? = null,
        fallback: String = "詳細",
        titleLookup: ((String) -> String?)? = null,
    ): String {
        val summary = summarize(node, schema, field, titleLookup)
        val head = listOfNotNull(summary.id, summary.title?.let { clip(it, 40) }).joinToString(" ").ifBlank { fallback }
        val sub = summary.subtitle?.let { " — " + clip(it, 40) } ?: ""
        return "$head$sub（${summary.keyCount}項目）"
    }

    /** 配列の畳み込みヘッダ（R10-02）:「n件: 先頭要素の要約, …」。 */
    fun seqHeaderText(
        seq: YNode.Seq,
        schema: SchemaTree?,
        itemField: FieldDef? = null,
        maxItems: Int = 3,
        titleLookup: ((String) -> String?)? = null,
    ): String {
        if (seq.items.isEmpty()) return "0 件"
        val head = seq.items.take(maxItems).joinToString(", ") { inline(it, schema, itemField, titleLookup = titleLookup) }
        val more = if (seq.items.size > maxItems) ", …" else ""
        return "${seq.items.size} 件: $head$more"
    }

    /** 任意ノードの1行要約（スカラー=値、オブジェクト=タイトル解決、配列=件数）。タイトルがなければ subtitle で補う（UX-77）。 */
    fun inline(
        node: YNode?,
        schema: SchemaTree? = null,
        field: FieldDef? = null,
        maxLength: Int = 40,
        titleLookup: ((String) -> String?)? = null,
    ): String =
        when (node) {
            null -> "（未設定）"
            is YNode.Scalar -> clip(node.value?.toString() ?: "null", maxLength)
            is YNode.Seq -> "（配列 ${node.items.size} 件）"
            is YNode.Map -> {
                val summary = summarize(node, schema, field, titleLookup)
                listOfNotNull(summary.id, (summary.title ?: summary.subtitle)?.let { clip(it, maxLength) })
                    .joinToString(" ")
                    .ifBlank { "（${summary.keyCount}項目）" }
            }
        }

    private fun clip(text: String, maxLength: Int): String {
        val single = text.replace("\n", " ")
        return if (single.length > maxLength) single.take(maxLength) + "…" else single
    }

    // ---------------------------------------------------------------- 配列のテーブル表示（R12-06）

    /** テーブルのセルとして1行で表示・編集できる型（ビューグリッドの直接編集対象と同種のスカラー系）。 */
    val CELL_TYPES = setOf(
        FieldType.STRING, FieldType.INT, FieldType.NUMBER,
        FieldType.BOOLEAN, FieldType.ENUM, FieldType.DATE, FieldType.ID, FieldType.REF,
    )

    /** 自動判定でテーブル表示にする列数の上限（超えると走査性が落ちるため縦リストへ倒す）。 */
    const val MAX_AUTO_TABLE_COLUMNS = 8

    /**
     * 配列（object 要素）または map（object 値）をテーブル表示すべきときの列定義（`$ref` 解決済み）を返す。
     * 向かなければ null。判定規則は移動前の Display.tableColumns と同一（R12-06）。
     */
    fun tableColumns(schema: SchemaTree?, field: FieldDef?): List<FieldDef>? {
        val container = schema?.resolve(field) ?: field ?: return null
        if (container.display == CollectionDisplay.LIST) return null
        val elementDef = when (container.type) {
            FieldType.ARRAY -> container.item
            FieldType.MAP -> container.value
            else -> return null
        }
        val item = schema?.resolve(elementDef) ?: elementDef ?: return null
        if (item.type != FieldType.OBJECT) return null
        val fields = item.fields.filter { it.key != null }.map { schema?.resolve(it) ?: it }
        val columns = fields.filter { it.type in CELL_TYPES }
        return when {
            columns.isEmpty() -> null
            container.display == CollectionDisplay.TABLE -> columns
            columns.size in 2..MAX_AUTO_TABLE_COLUMNS && columns.size * 2 >= fields.size -> columns
            else -> null
        }
    }
}
