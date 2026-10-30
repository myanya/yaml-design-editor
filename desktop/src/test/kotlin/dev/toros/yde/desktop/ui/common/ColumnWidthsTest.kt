package dev.toros.yde.desktop.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 列幅の見積もり（文字の幅と、見出し・値・飾りから列幅を決める）。 */
class ColumnWidthsTest {

    @Test
    fun `全角は 1em 半角は 0点62em で数え 改行はいちばん長い行`() {
        assertEquals(20f, ColumnWidths.textWidth("あい", 10f))
        assertEquals(12.4f, ColumnWidths.textWidth("ab", 10f), 0.001f)
        assertEquals(20f, ColumnWidths.textWidth("a\nあい", 10f), "改行を含む文字はいちばん長い行")
        assertTrue(ColumnWidths.isWide('漢') && ColumnWidths.isWide('Ａ') && !ColumnWidths.isWide('A'))
    }

    @Test
    fun `見出しと値の広い方を最小と上限の間に収める`() {
        // 値「NOT NULL」8 字×0.62×12 ≒ 59.5 ＋ 飾り 12 ≒ 71.5、見出し「型」10×1.05＋8 ＝ 18.5 → 71.5
        assertEquals(71.52f, ColumnWidths.fit("型", listOf("TEXT", "NOT NULL"), 10f, 12f, chrome = 12f, min = 48f, max = 320f), 0.01f)
        assertEquals(48f, ColumnWidths.fit("PK", listOf("1"), 10f, 12f, chrome = 12f, min = 48f, max = 320f), "最小幅を下回らない")
        assertEquals(320f, ColumnWidths.fit("本文", listOf("あ".repeat(100)), 10f, 12f, chrome = 12f, min = 48f, max = 320f), "上限で止める")
    }
}
