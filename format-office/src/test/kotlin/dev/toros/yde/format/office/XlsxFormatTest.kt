package dev.toros.yde.format.office

import dev.toros.yde.app.format.ReadOptions
import dev.toros.yde.app.format.Table
import dev.toros.yde.app.format.WriteOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Excel の表: 書いて読み直すと型（文字・整数・小数・真偽・空）とシート名・ヘッダ行が保たれる。 */
class XlsxFormatTest {

    private val table = Table(
        listOf("ID", "件名", "優先度", "率", "完了"),
        listOf(
            listOf("A-1", "最初", 2L, 0.5, true),
            listOf("A-2", null, 3L, 1.0, false),
        ),
    )

    @Test
    fun `書いて読み直すと値の型とシート名が保たれる`() {
        val bytes = XlsxFormat.write(table, WriteOptions(sheet = "課題一覧"))
        val round = XlsxFormat.read(bytes, ReadOptions(sheet = "課題一覧", headerRow = 1))
        assertEquals(table.headers, round.headers)
        assertEquals(listOf("A-1", "最初", 2L, 0.5, true), round.rows[0])
        assertEquals(listOf("A-2", null, 3L, 1L, false), round.rows[1], "1.0 は整数として Long に落ちる")
        // シート名を省くと先頭のシート
        assertEquals(2, XlsxFormat.read(bytes, ReadOptions()).rows.size)
    }

    @Test
    fun `無いシートとヘッダ行は誤りとして知らせる`() {
        val bytes = XlsxFormat.write(table, WriteOptions(sheet = "S"))
        assertTrue("シート「無い」が見つかりません" in assertFailsWith<IllegalStateException> { XlsxFormat.read(bytes, ReadOptions(sheet = "無い")) }.message.orEmpty())
        assertTrue("ヘッダ行" in assertFailsWith<IllegalStateException> { XlsxFormat.read(bytes, ReadOptions(headerRow = 9)) }.message.orEmpty())
    }
}
