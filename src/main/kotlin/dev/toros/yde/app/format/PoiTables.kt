package dev.toros.yde.app.format

import dev.toros.yde.app.skill.BinaryTables
import dev.toros.yde.app.skill.Table
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.nio.file.Files
import java.nio.file.Path

/**
 * xlsx / docx の読み書き（Apache POI）。中核（app-core）には入れず、入口（desktop の Main・CLI）が `Tables.binary` に登録する。
 * 形式の差し込み口（ADR-0013 の `FormatCodec`）へ寄せるまでの置き場。
 */
object PoiTables : BinaryTables {

    init {
        // POI が参照する Log4j2 の実装を同梱していないため、SimpleLogger を明示して起動時のERROR表示を抑止する
        System.setProperty("log4j2.loggerContextFactory", "org.apache.logging.log4j.simple.SimpleLoggerContextFactory")
    }

    /** Excel（.xlsx）。シート名省略時は先頭シート。数値は整数なら Long に落とす。 */
    override fun readXlsx(file: Path, sheet: String?, headerRow: Int): Table {
        Files.newInputStream(file).use { input ->
            XSSFWorkbook(input).use { book ->
                val ws = sheet?.let { book.getSheet(it) ?: error("シート「$it」が見つかりません") } ?: book.getSheetAt(0)
                val headerCells = ws.getRow(headerRow - 1) ?: error("ヘッダ行（$headerRow 行目）がありません")
                val headers = (0 until headerCells.lastCellNum)
                    .map { cellValue(headerCells.getCell(it))?.toString()?.trim().orEmpty() }
                    .toMutableList()
                while (headers.isNotEmpty() && headers.last().isEmpty()) headers.removeAt(headers.lastIndex)
                if (headers.isEmpty()) error("ヘッダ行が空です")
                val rows = (headerRow..ws.lastRowNum).mapNotNull { r ->
                    val row = ws.getRow(r) ?: return@mapNotNull null
                    val values = headers.indices.map { c -> cellValue(row.getCell(c)) }
                    if (values.all { it == null || it.toString().isBlank() }) null else values
                }
                return Table(headers, rows)
            }
        }
    }

    private fun cellValue(cell: Cell?): Any? {
        if (cell == null) return null
        val type = if (cell.cellType == CellType.FORMULA) cell.cachedFormulaResultType else cell.cellType
        return when (type) {
            CellType.STRING -> cell.stringCellValue.takeIf { it.isNotBlank() }
            CellType.BOOLEAN -> cell.booleanCellValue
            CellType.NUMERIC ->
                if (DateUtil.isCellDateFormatted(cell)) cell.localDateTimeCellValue.toLocalDate().toString()
                else cell.numericCellValue.let { d -> if (d % 1.0 == 0.0) d.toLong() else d }
            else -> null
        }
    }

    /** Word（.docx）文書内の表（R9-05）。tableIndex 番目（1始まり）の表を読み、1行目をヘッダにする。値は文字列。 */
    override fun readDocx(file: Path, tableIndex: Int): Table {
        Files.newInputStream(file).use { input ->
            XWPFDocument(input).use { doc ->
                val tables = doc.tables
                if (tables.isEmpty()) error("Word文書に表がありません")
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
    }

    override fun writeXlsx(table: Table, file: Path, sheet: String) {
        XSSFWorkbook().use { book ->
            val ws = book.createSheet(sheet)
            val header = ws.createRow(0)
            table.headers.forEachIndexed { c, name -> header.createCell(c).setCellValue(name) }
            table.rows.forEachIndexed { r, row ->
                val xr = ws.createRow(r + 1)
                row.forEachIndexed { c, value ->
                    when (value) {
                        null -> Unit
                        is Boolean -> xr.createCell(c).setCellValue(value)
                        is Number -> xr.createCell(c).setCellValue(value.toDouble())
                        else -> xr.createCell(c).setCellValue(value.toString())
                    }
                }
            }
            Files.newOutputStream(file).use { book.write(it) }
        }
    }
}
