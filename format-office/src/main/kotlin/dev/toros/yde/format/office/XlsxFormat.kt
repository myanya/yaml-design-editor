package dev.toros.yde.format.office

import dev.toros.yde.app.format.FormatCodec
import dev.toros.yde.app.format.ReadOptions
import dev.toros.yde.app.format.Table
import dev.toros.yde.app.format.WriteOptions
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Excel（.xlsx）の表の読み書き（Apache POI）。シート名省略時は先頭シート。数値は整数なら Long に落とす。 */
object XlsxFormat : FormatCodec {
    override val id = "xlsx"
    override val extensions = listOf("xlsx")

    init {
        // POI が参照する Log4j2 の実装を同梱していないため、SimpleLogger を明示して起動時のERROR表示を抑止する
        System.setProperty("log4j2.loggerContextFactory", "org.apache.logging.log4j.simple.SimpleLoggerContextFactory")
    }

    override fun read(bytes: ByteArray, options: ReadOptions): Table {
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { book ->
            val ws = options.sheet?.let { book.getSheet(it) ?: error("シート「$it」が見つかりません") } ?: book.getSheetAt(0)
            val headerRow = options.headerRow
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

    override fun write(table: Table, options: WriteOptions): ByteArray {
        XSSFWorkbook().use { book ->
            val ws = book.createSheet(options.sheet)
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
            return ByteArrayOutputStream().also { book.write(it) }.toByteArray()
        }
    }
}
