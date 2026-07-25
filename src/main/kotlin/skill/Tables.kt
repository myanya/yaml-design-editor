package skill

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.nio.file.Files
import java.nio.file.Path

/**
 * 表形式データの共通抽象（R5-02 / R5-03）。
 * xlsx / csv / json / markdown を同じ Table（ヘッダ＋行）へ正規化し、
 * インポータ・エクスポータを形式非依存にする。セル値は型を保持する（String / Long / Double / Boolean / null）。
 */
class Table(val headers: List<String>, val rows: List<List<Any?>>) {
    fun columnIndex(name: String): Int = headers.indexOf(name)
}

object Tables {

    init {
        // POI が参照する Log4j2 の実装を同梱していないため、SimpleLogger を明示して起動時のERROR表示を抑止する
        System.setProperty("log4j2.loggerContextFactory", "org.apache.logging.log4j.simple.SimpleLoggerContextFactory")
    }

    // ---------------------------------------------------------------- 読み込み

    fun read(file: Path, def: ImportDef): Table = when (def.format) {
        TableFormat.XLSX -> readXlsx(file, def.sheet, def.headerRow)
        TableFormat.CSV -> readCsv(Files.readString(file))
        TableFormat.JSON -> readJson(Files.readString(file), def.jsonPath)
        TableFormat.MARKDOWN -> readMarkdown(Files.readString(file))
        TableFormat.DOCX -> readDocx(file, def.tableIndex)
        TableFormat.HTML -> error("HTML はエクスポート専用の形式です")
    }

    /** CSV（RFC 4180 準拠: ダブルクォート・埋め込み改行・引用符の二重化）。 */
    fun readCsv(text: String): Table {
        val records = parseCsv(text.removePrefix("﻿"))
        if (records.isEmpty()) error("CSVが空です")
        val headers = records.first().map { it.trim() }
        val rows = records.drop(1)
            .filter { row -> row.any { it.isNotBlank() } }
            .map { row -> headers.indices.map { i -> row.getOrNull(i)?.takeIf { it.isNotEmpty() } } }
        return Table(headers, rows)
    }

    private fun parseCsv(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var field = StringBuilder()
        var record = mutableListOf<String>()
        var inQuotes = false
        var i = 0
        fun endField() { record.add(field.toString()); field = StringBuilder() }
        fun endRecord() { endField(); records.add(record); record = mutableListOf() }
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> endField()
                c == '\r' -> { if (i + 1 < text.length && text[i + 1] == '\n') i++; endRecord() }
                c == '\n' -> endRecord()
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || record.isNotEmpty()) endRecord()
        return records
    }

    /** Markdown の表（1行目ヘッダ・2行目区切り・以降データ）。`\|` はエスケープとして扱う。 */
    fun readMarkdown(text: String): Table {
        val lines = text.lineSequence().map { it.trim() }.filter { it.startsWith("|") }.toList()
        if (lines.size < 2) error("Markdownの表が見つかりません（| で始まるヘッダ行と区切り行が必要です）")
        fun cells(line: String): List<String> {
            val result = mutableListOf<String>()
            val sb = StringBuilder()
            var i = 1  // 先頭の | を飛ばす
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '\\' && i + 1 < line.length && line[i + 1] == '|' -> { sb.append('|'); i++ }
                    c == '|' -> { result.add(sb.toString().trim()); sb.clear() }
                    else -> sb.append(c)
                }
                i++
            }
            if (sb.isNotBlank()) result.add(sb.toString().trim())
            return result
        }
        val headers = cells(lines.first())
        val dataLines = lines.drop(1).dropWhile { line -> cells(line).all { cell -> cell.all { it == '-' || it == ':' } } }
        val rows = dataLines.map { line ->
            val c = cells(line)
            headers.indices.map { i -> c.getOrNull(i)?.takeIf { it.isNotEmpty() } }
        }
        return Table(headers, rows)
    }

    /** JSON（オブジェクトの配列）。path 指定時はルートオブジェクト内の配列を辿る。値の型を保持する。 */
    fun readJson(text: String, path: String?): Table {
        var element: JsonElement = Json.parseToJsonElement(text)
        path?.takeIf { it.isNotBlank() }?.split('.')?.forEach { key ->
            element = (element as? JsonObject)?.get(key) ?: error("JSONのパス「$path」が見つかりません")
        }
        val array = element as? JsonArray ?: error("JSONの行データは配列である必要があります")
        val objects = array.map { it as? JsonObject ?: error("配列の要素はオブジェクトである必要があります") }
        val headers = objects.flatMap { it.keys }.distinct()
        val rows = objects.map { obj -> headers.map { key -> jsonScalar(obj[key]) } }
        return Table(headers, rows)
    }

    private fun jsonScalar(e: JsonElement?): Any? = when (e) {
        null, is JsonNull -> null
        is JsonPrimitive ->
            if (e.isString) e.content
            else e.booleanOrNull ?: e.longOrNull ?: e.doubleOrNull ?: e.content
        else -> Json.encodeToString(JsonElement.serializer(), e)  // ネストは文字列として保持
    }

    /** Excel（.xlsx）。シート名省略時は先頭シート。数値は整数なら Long に落とす。 */
    fun readXlsx(file: Path, sheet: String?, headerRow: Int): Table {
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
    fun readDocx(file: Path, tableIndex: Int = 1): Table {
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

    // ---------------------------------------------------------------- 書き出し

    fun write(table: Table, file: Path, format: TableFormat, sheet: String) {
        Files.createDirectories(file.parent ?: file.toAbsolutePath().parent)
        when (format) {
            TableFormat.CSV -> Files.writeString(file, writeCsv(table))
            TableFormat.MARKDOWN -> Files.writeString(file, writeMarkdown(table))
            TableFormat.XLSX -> writeXlsx(table, file, sheet)
            TableFormat.JSON -> Files.writeString(file, writeJson(table))
            TableFormat.HTML -> Files.writeString(file, writeHtml(table, sheet))
            TableFormat.DOCX -> error("Wordへのエクスポートは未対応です")
        }
    }

    fun writeCsv(table: Table): String = buildString {
        fun cell(v: Any?): String {
            val s = v?.toString() ?: ""
            return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                "\"" + s.replace("\"", "\"\"") + "\""
            } else s
        }
        appendLine(table.headers.joinToString(",") { cell(it) })
        table.rows.forEach { row -> appendLine(row.joinToString(",") { cell(it) }) }
    }

    /** JSON（オブジェクトの配列。R9-05）。セルの型を保持し、空セルのキーは出力しない。readJson と往復可能。 */
    fun writeJson(table: Table): String {
        val array = JsonArray(table.rows.map { row ->
            JsonObject(buildMap {
                table.headers.forEachIndexed { i, header ->
                    when (val v = row.getOrNull(i)) {
                        null -> Unit
                        is Boolean -> put(header, JsonPrimitive(v))
                        is Long -> put(header, JsonPrimitive(v))
                        is Double -> put(header, JsonPrimitive(v))
                        is Number -> put(header, JsonPrimitive(v))
                        else -> put(header, JsonPrimitive(v.toString()))
                    }
                }
            })
        })
        return prettyJson.encodeToString(JsonArray.serializer(), array) + "\n"
    }

    private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** HTML（R9-05）。単体で開ける派生成果物。セルはエスケープし、改行は <br> にする。 */
    fun writeHtml(table: Table, title: String): String {
        fun esc(v: Any?): String = (v?.toString() ?: "")
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            .replace("\n", "<br>")
        return buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang=\"ja\">")
            appendLine("<head>")
            appendLine("<meta charset=\"UTF-8\">")
            appendLine("<title>${esc(title)}</title>")
            appendLine("<style>")
            appendLine("body { font-family: sans-serif; margin: 24px; }")
            appendLine("table { border-collapse: collapse; }")
            appendLine("th, td { border: 1px solid #bbb; padding: 4px 10px; font-size: 14px; text-align: left; vertical-align: top; }")
            appendLine("th { background: #f0f0f0; }")
            appendLine("</style>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("<table>")
            appendLine("<thead><tr>" + table.headers.joinToString("") { "<th>${esc(it)}</th>" } + "</tr></thead>")
            appendLine("<tbody>")
            table.rows.forEach { row ->
                appendLine("<tr>" + table.headers.indices.joinToString("") { i -> "<td>${esc(row.getOrNull(i))}</td>" } + "</tr>")
            }
            appendLine("</tbody>")
            appendLine("</table>")
            appendLine("</body>")
            appendLine("</html>")
        }
    }

    fun writeMarkdown(table: Table): String = buildString {
        fun cell(v: Any?): String = (v?.toString() ?: "").replace("|", "\\|").replace("\n", "<br>")
        appendLine("| " + table.headers.joinToString(" | ") { cell(it) } + " |")
        appendLine("|" + table.headers.joinToString("|") { " --- " } + "|")
        table.rows.forEach { row -> appendLine("| " + row.joinToString(" | ") { cell(it) } + " |") }
    }

    private fun writeXlsx(table: Table, file: Path, sheet: String) {
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
