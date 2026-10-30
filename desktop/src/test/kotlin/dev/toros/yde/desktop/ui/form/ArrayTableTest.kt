package dev.toros.yde.desktop.ui.form

import androidx.compose.ui.unit.dp
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldHints
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.TextDisplay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 配列テーブルの列幅割付（R12-06）のテスト。
 * 列幅は見出しと値の文字の幅＋型ごとの飾りを、型ごとの最小と上限の間に収める（cellSizing）。
 * 余白は文字系の列へ等分して表を右端まで使い、数値だけの表は伸ばさない。
 */
class ArrayTableTest {

    private fun cols(vararg types: FieldType) = types.map { FieldDef(type = it) }

    /** 意味の基底型 date の列（直列化は string だが、基底の種類は date）。 */
    private val dateColumn = FieldDef(type = FieldType.DATE)

    @Test
    fun `余白は文字系の列で等分され表が右端まで使われる`() {
        val types = cols(FieldType.STRING, FieldType.BOOLEAN, FieldType.STRING)
        // 見出しも値も無ければ最小幅 = 96 + 56 + 96、固定部 100 → 自然幅 348。余白 652 を string 2列で等分
        val widths = tableColumnWidths(types, available = 1000.dp, fixed = 100.dp)
        assertEquals(listOf(422.dp, 56.dp, 422.dp), widths)
        // 合計が利用可能幅に一致する（右端に余白を残さない）
        assertEquals(1000.dp, widths.fold(100.dp) { acc, w -> acc + w })
    }

    @Test
    fun `利用可能幅に収まらなければ基本の幅のまま横スクロールに委ねる`() {
        val types = cols(FieldType.STRING, FieldType.STRING)
        // 全角 20 字（13sp で 260dp）＋入力欄の飾り 32 = 292 の列が 2 つ。300 には収まらない
        val long = listOf("あ".repeat(20))
        assertEquals(listOf(292.dp, 292.dp), tableColumnWidths(types, available = 300.dp, fixed = 28.dp, values = listOf(long, long)))
    }

    @Test
    fun `文字系の列がなければ伸ばさない（数値だけの表を間延びさせない）`() {
        // 日付の列（意味の基底型 date）はピッカーのボタン分を含む最小幅 150 で、余白の配分先にはならない
        val types = cols(FieldType.INT, FieldType.BOOLEAN) + dateColumn
        assertEquals(listOf(72.dp, 56.dp, 150.dp), tableColumnWidths(types, available = 1000.dp, fixed = 28.dp))
    }

    @Test
    fun `複数行の文字列（組込みの型 text のヒント）は広い最小幅にする`() {
        // 解決済みの列は型から引き継いだヒント display: multiline を持つ（基底の種類は string のまま）
        val text = FieldDef(type = FieldType.STRING, hints = FieldHints(textDisplay = TextDisplay.MULTILINE))
        assertEquals(listOf(120.dp, 96.dp), tableColumnWidths(listOf(text, FieldDef(type = FieldType.STRING)), available = 100.dp, fixed = 28.dp))
    }

    @Test
    fun `値と見出しで列が広がり 上限で止まる`() {
        val name = FieldDef(key = "physical_name", type = FieldType.STRING, hints = FieldHints(label = "物理名"))
        // 「completed_on」12 字×0.62em×13sp ≒ 96.7 ＋ 32 ≒ 128.7（見出しの「物理名」より広い）
        val width = tableColumnWidths(listOf(name), available = 0.dp, fixed = 0.dp, values = listOf(listOf("todo_id", "completed_on"))).single()
        assertTrue(width.value in 128f..130f, "値でいちばん長いものに合わせる: $width")
        // 長い文は上限 320 で止める（セルの中で省略される）
        val capped = tableColumnWidths(listOf(name), available = 0.dp, fixed = 0.dp, values = listOf(listOf("あ".repeat(80)))).single()
        assertEquals(320.dp, capped)
    }

    @Test
    fun `参照の列は編集中は候補と移動のボタン分 閲覧専用は文字と横ぐしの印の分`() {
        val ref = FieldDef(type = FieldType.REF)
        assertEquals(170.dp, tableColumnWidths(listOf(ref), available = 0.dp, fixed = 0.dp).single(), "以前は 240（ボタン 3 つ分）")
        assertEquals(80.dp, tableColumnWidths(listOf(ref), available = 0.dp, fixed = 0.dp, readOnly = true).single())
    }
}
