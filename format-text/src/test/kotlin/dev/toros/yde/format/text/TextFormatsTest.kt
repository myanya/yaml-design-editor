package dev.toros.yde.format.text

import dev.toros.yde.app.ext.ReadOptions
import dev.toros.yde.app.ext.Table
import dev.toros.yde.app.ext.WriteOptions
import java.nio.charset.CharacterCodingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 文字の形式（R5-02/03）: CSV・Markdown・JSON のパースと往復、HTML の書き出し、文字コードの厳密な解読。
 */
class TextFormatsTest {

    @Test
    fun `csv parses quotes embedded commas and newlines`() {
        val text = "ID,件名,備考\r\n" +
            "A-1,\"カンマ,入り\",\"改行\n入り\"\r\n" +
            "A-2,\"引用符\"\"入り\",\r\n"
        val table = CsvFormat.readCsv(text)
        assertEquals(listOf("ID", "件名", "備考"), table.headers)
        assertEquals(2, table.rows.size)
        assertEquals("カンマ,入り", table.rows[0][1])
        assertEquals("改行\n入り", table.rows[0][2])
        assertEquals("引用符\"入り", table.rows[1][1])
        assertNull(table.rows[1][2])  // 空セルは null
    }

    @Test
    fun `csv write then read is lossless`() {
        val table = Table(listOf("ID", "値"), listOf(listOf("A-1", "カンマ,と\"引用符\""), listOf("A-2", "複数\n行")))
        val round = CsvFormat.readCsv(CsvFormat.writeCsv(table))
        assertEquals(table.headers, round.headers)
        assertEquals(table.rows.map { it.map { v -> v?.toString() } }, round.rows.map { it.map { v -> v?.toString() } })
    }

    @Test
    fun `見出しの無い csv は columns で列の名前を与えて 1 行目から読む`() {
        val table = CsvFormat.read("2021/8/21,x,100\n".toByteArray(), ReadOptions(columns = listOf("日付", "摘要", "金額")))
        assertEquals(listOf("日付", "摘要", "金額"), table.headers)
        assertEquals(listOf("2021/8/21", "x", "100"), table.rows.single())
    }

    @Test
    fun `合わない文字コードは化けた表にせず誤りとして知らせる`() {
        val sjis = "ID,件名\nA-1,日本語\n".toByteArray(charset("windows-31j"))
        assertFailsWith<CharacterCodingException> { CsvFormat.read(sjis, ReadOptions()) }
        val ok = CsvFormat.read(sjis, ReadOptions(encoding = charset("windows-31j")))
        assertEquals("日本語", ok.rows.single()[1])
    }

    @Test
    fun `markdown table write then read is lossless for pipe cells`() {
        val table = Table(listOf("ID", "説明"), listOf(listOf("A-1", "縦棒|入り")))
        val text = MarkdownFormat.writeMarkdown(table)
        val round = MarkdownFormat.readMarkdown(text)
        assertEquals(listOf("ID", "説明"), round.headers)
        assertEquals("縦棒|入り", round.rows[0][1])
    }

    @Test
    fun `json reads typed values from nested path`() {
        val text = """{"data": {"rows": [
            {"id": "A-1", "priority": 3, "done": true},
            {"id": "A-2", "score": 1.5}
        ]}}"""
        val table = JsonFormat.readJson(text, "data.rows")
        assertEquals(listOf("id", "priority", "done", "score"), table.headers)
        assertEquals(3L, table.rows[0][1])
        assertEquals(true, table.rows[0][2])
        assertEquals(1.5, table.rows[1][3])
        assertNull(table.rows[1][1])
        // 書いて読み直しても型が保たれる
        val round = JsonFormat.readJson(JsonFormat.writeJson(table), null)
        assertEquals(table.rows, round.rows)
    }

    @Test
    fun `html は書くだけで セルはエスケープされる`() {
        assertFalse(HtmlFormat.canRead)
        val html = String(HtmlFormat.write(Table(listOf("題"), listOf(listOf("<b>&"))), WriteOptions(title = "一覧")), Charsets.UTF_8)
        assertTrue("<title>一覧</title>" in html && "<td>&lt;b&gt;&amp;</td>" in html, html)
        assertFailsWith<IllegalStateException> { HtmlFormat.read(ByteArray(0), ReadOptions()) }
    }
}
