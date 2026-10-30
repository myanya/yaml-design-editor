package dev.toros.yde.desktop.ui.view

import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.yaml.model.YNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ビューの表の列幅（以前は全部の列が 230dp）。テーブルモデルのサンプルの「カラム定義」の 14 列を、実際の値で見積もる。
 */
class GridWidthsTest {

    private fun read(label: String, vararg values: String) = GridWidths.Column(label, GridWidths.Kind.READ, null, values.toList())

    @Test
    fun `値の短い列は細く 長い列は上限まで 編集できる列は入力欄の分を確保する`() {
        assertEquals(48f, GridWidths.cellWidth(read("PK", "0", "1"), readOnly = false), "0 か 1 の列は最小幅")
        assertEquals(320f, GridWidths.cellWidth(read("元となる情報", "あ".repeat(60)), readOnly = false), "長い文は上限で止め、セルの中で折り返す")
        val physical = GridWidths.Column("物理名", GridWidths.Kind.EDITABLE, FieldType.STRING, listOf("category_id"))
        assertEquals(130f, GridWidths.cellWidth(physical, readOnly = false), "編集できる文字の列は 130 以上")
        assertTrue(GridWidths.cellWidth(physical, readOnly = true) < 130f, "閲覧専用では入力欄の分が要らない")
        val ref = GridWidths.Column("参照先", GridWidths.Kind.EDITABLE, FieldType.REF, listOf("ENT-TODO"))
        assertEquals(180f, GridWidths.cellWidth(ref, readOnly = false), "参照は候補の ▼ と → の分")
    }

    @Test
    fun `サンプルのカラム定義 14 列は 3480dp から半分以下になる`() {
        val columns = listOf(
            read("テーブルID", "ENT-TODO", "ENT-COMMENT", "ENT-CATEGORY"),
            read("テーブル", "todos", "comments", "categories"),
            GridWidths.Column("物理名", GridWidths.Kind.EDITABLE, FieldType.STRING, listOf("todo_id", "completed_on", "category_id")),
            read("論理名", "TODO番号", "コメント番号", "カテゴリ名"),
            read("型", "TEXT NOT NULL", "TEXT NULL", "INTEGER NOT NULL"),
            read("PK", "PK", ""),
            read("FK先テーブル", "categories", "todos"),
            read("FK先カラム", "category_id", "todo_id"),
            read("業務型", "カテゴリ番号", "一行の文", "ステータス"),
            read("最大文字数", "100"),
            read("形式", "^TODO-[0-9]{3,}$", "^CAT-[0-9]{3,}$"),
            read("選択肢", "OPEN, IN_PROGRESS, CLOSED, CANCELED"),
            read("元となる情報", "todos[*].id / 個票.id", "コメントの配列位置から採番する"),
        )
        val id = GridWidths.idWidth(listOf("ATTR-TODO-COMPLETED", "ATTR-COMMENT-NUMBER"), listOf("完了日", "コメント番号"))
        val total = id + columns.sumOf { GridWidths.cellWidth(it, readOnly = false).toDouble() }.toFloat()
        // 以前: ID 170 ＋ 14 列×230 ＋ 状態 90 ＝ 3480（ここでは「型」を 1 列にまとめた 13 列。状態の列は検査が無いので出ない）
        assertTrue(total < 1740f, "合計 $total dp")
    }

    @Test
    fun `計算セルのスカラーの配列は値をつないで見せ 0 件は横棒`() {
        assertEquals("categories", computedCellText(YNode.Seq(mutableListOf(YNode.Scalar("categories")))))
        assertEquals("OPEN, CLOSED", computedCellText(YNode.Seq(mutableListOf(YNode.Scalar("OPEN"), YNode.Scalar("CLOSED")))))
        assertEquals("—", computedCellText(YNode.Seq(mutableListOf())))
        assertEquals("PK", computedCellText(YNode.Scalar("PK")))
    }
}
