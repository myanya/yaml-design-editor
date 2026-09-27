package dev.toros.yde.semantic.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 数の表示の形（display: grouped＝桁区切り）: 見せ方の文字の作り方と、宣言の読み方。 */
class NumberDisplayTest {

    @Test
    fun `桁区切りは整数の部分だけを 3 桁ごとに区切る`() {
        val g = NumberDisplay.GROUPED
        assertEquals("45,000", g.format("45000"))
        assertEquals("100", g.format("100"))
        assertEquals("1,000", g.format("1000"))
        assertEquals("-1,234,567.5", g.format("-1234567.5"))
        assertEquals("+12", g.format("+12"))
        assertEquals("0", g.format("0"))
        assertEquals("12,345.678", g.format("12345.678"))
    }

    @Test
    fun `数の表記でなければそのまま`() {
        val g = NumberDisplay.GROUPED
        assertEquals("abc", g.format("abc"))
        assertEquals("", g.format(""))
        assertEquals("1e3", g.format("1e3"))
        assertEquals("（未設定）", g.format("（未設定）"))
        assertEquals("2021-08-21", g.format("2021-08-21"))
    }

    @Test
    fun `宣言は grouped だけ`() {
        assertEquals(NumberDisplay.GROUPED, NumberDisplay.parse("grouped"))
        assertNull(NumberDisplay.parse(null))
        assertFailsWith<IllegalStateException> { NumberDisplay.parse("table") }
    }
}
