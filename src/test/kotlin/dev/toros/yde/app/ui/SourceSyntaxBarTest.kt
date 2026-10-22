package dev.toros.yde.app.ui

import dev.toros.yde.app.core.EditBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SourceSyntaxBarTest {
    @Test
    fun `実際の解析エラーから文脈の開始位置でなく問題の位置を取る`() {
        val error = EditBuffer.parse("root:\n  name: ok\n broken: value\n").error
        assertNotNull(error)
        assertEquals(SourceErrorLocation(3, 2), sourceErrorLocation(error))
    }

    @Test
    fun `位置情報がないエラーではジャンプ先を推測しない`() {
        assertNull(sourceErrorLocation(null))
        assertNull(sourceErrorLocation("解析できません"))
        assertNull(sourceErrorLocation("値に line 12, column 4: が含まれています"))
        assertNull(sourceErrorLocation("in 'reader', line 0, column 4:"))
        assertNull(sourceErrorLocation("in 'reader', line 999999999999999, column 4:"))
    }

    @Test
    fun `改行形式と長いエラーにかかわらず最後の位置を使う`() {
        val error = "in 'reader', line 1, column 1:\r\n" + "  context\r\n".repeat(100) +
            " in 'reader', line 25, column 8:\r\n    broken\r\n       ^\r\n"
        assertEquals(SourceErrorLocation(25, 8), sourceErrorLocation(error))
    }
}
