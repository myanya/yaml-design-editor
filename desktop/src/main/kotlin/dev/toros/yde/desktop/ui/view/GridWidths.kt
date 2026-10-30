package dev.toros.yde.desktop.ui.view

import dev.toros.yde.app.core.Views
import dev.toros.yde.desktop.ui.common.ColumnWidths
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.yaml.model.YNode

/**
 * 計算セル（値の式の列）の表示の文字。スカラーの配列は要素を「, 」でつないで見せ（0 件は「—」）、それ以外は Views.cellText。
 * 以前は配列が「（配列 1 件）」と出て、外部キーの参照先のような 1 件の値も読めなかった。見せ方だけで、CLI・read_view の値は変えない。
 */
internal fun computedCellText(value: YNode?): String =
    if (value is YNode.Seq && value.items.all { it is YNode.Scalar }) value.items.joinToString(", ") { Views.cellText(it) }.ifEmpty { "—" }
    else Views.cellText(value)

/**
 * ビューの表（グリッド・クロス集計・ダッシュボードと文書ビューに埋め込んだ表）の列幅を、見出しと値から決める純粋な計算。
 * 以前は全部の列が 230dp だった。値が「0」か「1」の列も、長い文の列も同じ幅で、表が横に長くなっていた。
 * - 表示だけの列（参照・計算・被参照）は、値の文字の幅＋セルの余白。上限を超える値はセルの中で 2 行まで折り返す
 * - 編集できる列は、入力欄の飾り（余白・候補の ▼・参照先へ → など）を足し、型ごとの最小幅を下回らない
 * - 見出しは太字の 10sp と、編集できる列の印（鉛筆）の分
 */
internal object GridWidths {
    /** 見出しの文字の大きさ・値の文字の大きさ（sp）。 */
    private const val HEADER_SP = 10f
    private const val VALUE_SP = 12f
    private const val TITLE_SP = 10f

    /** セルの右の余白（GridCell の padding）と、見出しの右の余白。 */
    private const val CELL_PAD = 12f

    /** 見出しの印（鉛筆・矢印）の幅。 */
    private const val MARK = 14f

    /** 列の種類。 */
    enum class Kind { EDITABLE, READ, BACKLINKS }

    /** 1 列分の材料: 見出し・種類・編集できる列の値の型（分からなければ null）・値の文字（先頭から）。 */
    data class Column(val label: String, val kind: Kind, val type: FieldType?, val values: List<String>)

    /** 編集できる列の型ごとの飾りの幅と最小幅（dp）。 */
    private fun editorChrome(type: FieldType?): Pair<Float, Float> = when (type) {
        FieldType.BOOLEAN -> 16f to 64f
        FieldType.INT, FieldType.NUMBER -> 40f to 90f
        FieldType.DATE -> 76f to 160f                 // 入力欄＋日付のピッカー
        FieldType.TIME -> 76f to 130f
        FieldType.DATETIME -> 76f to 210f
        FieldType.ENUM -> 56f to 120f                 // 選択の ▼
        FieldType.ID -> 84f to 160f                   // 採番の ▼ と横ぐしの印
        FieldType.REF -> 98f to 180f                  // 候補の ▼ と参照先へ →
        FieldType.FILE -> 96f to 240f                 // 選ぶ・OS で開く
        else -> 40f to 130f
    }

    /** 列幅（dp）。readOnly は閲覧専用モード（どの列も表示だけになる）。 */
    fun cellWidth(column: Column, readOnly: Boolean): Float {
        val editable = column.kind == Kind.EDITABLE && !readOnly
        val mark = if (editable || column.kind == Kind.BACKLINKS) MARK else 0f
        val header = ColumnWidths.textWidth(column.label, HEADER_SP) * 1.05f + mark + CELL_PAD
        val values = column.values.asSequence().take(ColumnWidths.SAMPLE_ROWS)
            .maxOfOrNull { ColumnWidths.textWidth(it, VALUE_SP) } ?: 0f
        val (chrome, min, max) = when {
            editable -> editorChrome(column.type).let { (c, m) -> Triple(c, m, 340f) }
            column.kind == Kind.BACKLINKS -> Triple(CELL_PAD, 80f, 260f)
            else -> Triple(CELL_PAD, 48f, 320f)
        }
        return maxOf(header, values + chrome).coerceIn(min, max)
    }

    /** ID の列の幅（dp）: 行の ID（太字 12sp）と横ぐし・操作の印、下の段のタイトル（10sp）。 */
    fun idWidth(ids: List<String>, titles: List<String>): Float {
        val id = ids.asSequence().take(ColumnWidths.SAMPLE_ROWS).maxOfOrNull { ColumnWidths.textWidth(it, VALUE_SP) * 1.05f } ?: 0f
        val title = titles.asSequence().take(ColumnWidths.SAMPLE_ROWS).maxOfOrNull { ColumnWidths.textWidth(it, TITLE_SP) } ?: 0f
        return maxOf(id + 44f + 8f, title + 8f).coerceIn(110f, 260f)
    }
}
