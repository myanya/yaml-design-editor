package dev.toros.yde.format.docx

import dev.toros.yde.app.ext.FormatCodec
import dev.toros.yde.app.ext.ReadOptions
import dev.toros.yde.app.ext.Table
import dev.toros.yde.app.ext.WriteOptions
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.ByteArrayInputStream

/** Word（.docx）文書内の表（R9-05）。tableIndex 番目（1 始まり）の表を読み、1 行目をヘッダにする。値は文字列。書くことはできない。 */
object DocxFormat : FormatCodec {
    override val id = "docx"
    override val extensions = emptyList<String>()
    override val canWrite: Boolean get() = false

    init {
        // POI が参照する Log4j2 の実装を同梱していないため、SimpleLogger を明示して起動時のERROR表示を抑止する
        System.setProperty("log4j2.loggerContextFactory", "org.apache.logging.log4j.simple.SimpleLoggerContextFactory")
    }

    override fun read(bytes: ByteArray, options: ReadOptions): Table {
        XWPFDocument(ByteArrayInputStream(bytes)).use { doc ->
            val tables = doc.tables
            if (tables.isEmpty()) error("Word文書に表がありません")
            val tableIndex = options.tableIndex
            val table = tables.getOrNull(tableIndex - 1)
                ?: error("Word文書の ${tableIndex} 番目の表がありません（表は ${tables.size} 個）")
            val allRows = table.rows.map { row -> row.tableCells.map { it.text.trim() } }
            if (allRows.isEmpty()) error("Word文書の表が空です")
            val headers = allRows.first().toMutableList()
            while (headers.isNotEmpty() && headers.last().isEmpty()) headers.removeAt(headers.lastIndex)
            if (headers.isEmpty()) error("Word文書の表のヘッダ行が空です")
            val rows = allRows.drop(1)
                .filter { row -> row.any { it.isNotBlank() } }
                .map { row -> headers.indices.map { i -> row.getOrNull(i)?.takeIf { it.isNotEmpty() } } }
            return Table(headers, rows)
        }
    }

    override fun write(table: Table, options: WriteOptions): ByteArray = error("Wordへのエクスポートは未対応です")
}
