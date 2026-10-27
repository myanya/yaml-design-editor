package dev.toros.yde.format.docx

import dev.toros.yde.app.ext.ReadOptions
import dev.toros.yde.app.ext.Table
import dev.toros.yde.app.ext.WriteOptions
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Word の表: 指定した番目の表を 1 行目をヘッダにして読む。書くことはできない。 */
class DocxFormatTest {

    private fun docx(vararg tables: List<List<String>>): ByteArray {
        XWPFDocument().use { doc ->
            doc.createParagraph().createRun().setText("前書き")
            tables.forEach { rows ->
                val table = doc.createTable(rows.size, rows.first().size)
                rows.forEachIndexed { r, row -> row.forEachIndexed { c, v -> table.getRow(r).getCell(c).setText(v) } }
            }
            return ByteArrayOutputStream().also { doc.write(it) }.toByteArray()
        }
    }

    @Test
    fun `指定した番目の表を読み 1 行目をヘッダにする`() {
        val bytes = docx(
            listOf(listOf("ID", "件名", "担当"), listOf("ISSUE-201", "Wordからの課題", "suzuki"), listOf("ISSUE-202", "2件目", "")),
            listOf(listOf("名前", "値"), listOf("x", "1")),
        )
        val first = DocxFormat.read(bytes, ReadOptions())
        assertEquals(listOf("ID", "件名", "担当"), first.headers)
        assertEquals(listOf("ISSUE-201", "Wordからの課題", "suzuki"), first.rows[0])
        assertEquals(listOf("ISSUE-202", "2件目", null), first.rows[1], "空のセルは null")
        val second = DocxFormat.read(bytes, ReadOptions(tableIndex = 2))
        assertEquals(listOf("名前", "値"), second.headers)
        assertTrue("3 番目の表がありません" in assertFailsWith<IllegalStateException> { DocxFormat.read(bytes, ReadOptions(tableIndex = 3)) }.message.orEmpty())
    }

    @Test
    fun `書くことはできない`() {
        assertFalse(DocxFormat.canWrite)
        assertFailsWith<IllegalStateException> { DocxFormat.write(Table(listOf("a"), emptyList()), WriteOptions()) }
    }
}
