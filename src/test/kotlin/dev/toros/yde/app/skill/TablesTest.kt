package dev.toros.yde.app.skill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 表形式の読み書き（R5-02/03）のテスト。CSV・Markdown・JSON のパースと往復を検証する。
 */
class TablesTest {

    @Test
    fun `csv parses quotes embedded commas and newlines`() {
        val text = "ID,件名,備考\r\n" +
            "A-1,\"カンマ,入り\",\"改行\n入り\"\r\n" +
            "A-2,\"引用符\"\"入り\",\r\n"
        val table = Tables.readCsv(text)
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
        val round = Tables.readCsv(Tables.writeCsv(table))
        assertEquals(table.headers, round.headers)
        assertEquals(table.rows.map { it.map { v -> v?.toString() } }, round.rows.map { it.map { v -> v?.toString() } })
    }

    @Test
    fun `markdown table write then read is lossless for pipe cells`() {
        val table = Table(listOf("ID", "説明"), listOf(listOf("A-1", "縦棒|入り")))
        val text = Tables.writeMarkdown(table)
        val round = Tables.readMarkdown(text)
        assertEquals(listOf("ID", "説明"), round.headers)
        assertEquals("縦棒|入り", round.rows[0][1])
    }

    @Test
    fun `json reads typed values from nested path`() {
        val text = """{"data": {"rows": [
            {"id": "A-1", "priority": 3, "done": true},
            {"id": "A-2", "score": 1.5}
        ]}}"""
        val table = Tables.readJson(text, "data.rows")
        assertEquals(listOf("id", "priority", "done", "score"), table.headers)
        assertEquals(3L, table.rows[0][1])
        assertEquals(true, table.rows[0][2])
        assertEquals(1.5, table.rows[1][3])
        assertNull(table.rows[1][1])
    }
}
