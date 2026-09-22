package dev.toros.yde.app.ui

import androidx.compose.ui.unit.dp
import dev.toros.yde.semantic.schema.FieldType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 配列テーブルの列幅割付（R12-06）のテスト。
 * 最小幅の保証・余白の文字系列への等分（表を右端まで使う）・数値だけの表を伸ばさないことを検証する。
 */
class ArrayTableTest {

    @Test
    fun `余白は文字系の列で等分され表が右端まで使われる`() {
        val types = listOf(FieldType.STRING, FieldType.BOOLEAN, FieldType.STRING)
        // 最小幅 = 170 + 90 + 170、固定部 100 → 自然幅 530。余白 470 を string 2列で等分
        val widths = tableColumnWidths(types, available = 1000.dp, fixed = 100.dp)
        assertEquals(listOf(405.dp, 90.dp, 405.dp), widths)
        // 合計が利用可能幅に一致する（右端に余白を残さない）
        assertEquals(1000.dp, widths.fold(100.dp) { acc, w -> acc + w })
    }

    @Test
    fun `利用可能幅に収まらなければ最小幅のまま横スクロールに委ねる`() {
        val types = listOf(FieldType.STRING, FieldType.STRING)
        assertEquals(listOf(170.dp, 170.dp), tableColumnWidths(types, available = 300.dp, fixed = 28.dp))
    }

    @Test
    fun `文字系の列がなければ伸ばさない（数値だけの表を間延びさせない）`() {
        val types = listOf(FieldType.INT, FieldType.BOOLEAN, FieldType.DATE)
        assertEquals(
            listOf(90.dp, 90.dp, 110.dp),
            tableColumnWidths(types, available = 1000.dp, fixed = 28.dp),
        )
    }
}
