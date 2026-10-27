package dev.toros.yde.app.skill

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
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

/** xlsx / docx の読み書き。中核（app-core）には POI を入れず、入口（desktop の Main・CLI）が実装を `Tables.binary` に登録する。 */
interface BinaryTables {
    fun readXlsx(file: Path, sheet: String?, headerRow: Int): Table
    fun readDocx(file: Path, tableIndex: Int = 1): Table
    fun writeXlsx(table: Table, file: Path, sheet: String)
}

object Tables {

    /** xlsx / docx の読み書きの実装。入口が起動時に登録する（無ければ読み書きの時点で知らせる）。 */
    @Volatile var binary: BinaryTables? = null

    private fun binary(): BinaryTables = binary ?: error("xlsx / docx の読み書きが登録されていません（入口が Tables.binary を設定します）")

    // ---------------------------------------------------------------- 読み込み

    fun read(file: Path, def: ImportDef): Table = when (def.format) {
        TableFormat.XLSX -> binary().readXlsx(file, def.sheet, def.headerRow)
        TableFormat.CSV -> readCsv(readText(file, def), def.columns.takeIf { it.isNotEmpty() })
        TableFormat.JSON -> readJson(readText(file, def), def.jsonPath)
        TableFormat.MARKDOWN -> readMarkdown(readText(file, def))
        TableFormat.DOCX -> binary().readDocx(file, def.tableIndex)
        TableFormat.HTML -> error("HTML はエクスポート専用の形式です")
    }

    /** 文字コード（input.encoding。既定 UTF-8）で読む。合わない文字コードは、化けた表にせず誤りとして知らせる。 */
    private fun readText(file: Path, def: ImportDef): String =
        try {
            Files.readString(file, def.encoding)
        } catch (e: java.nio.charset.CharacterCodingException) {
            error("文字コード ${def.encoding.name()} として読めません: ${file.fileName}（銀行の CSV などは input.encoding: windows-31j を試してください）")
        }

    /**
     * CSV（RFC 4180 準拠: ダブルクォート・埋め込み改行・引用符の二重化）。
     * columns を渡すと見出しの行が無い CSV として読む（列の名前は columns の並び。1 行目からデータ）。
     */
    fun readCsv(text: String, columns: List<String>? = null): Table {
        val records = parseCsv(text.removePrefix("﻿"))
        if (records.isEmpty()) error("CSVが空です")
        val headers = columns ?: records.first().map { it.trim() }
        val rows = (if (columns == null) records.drop(1) else records)
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

    // ---------------------------------------------------------------- 書き出し

    fun write(table: Table, file: Path, format: TableFormat, sheet: String) {
        Files.createDirectories(file.parent ?: file.toAbsolutePath().parent)
        when (format) {
            TableFormat.CSV -> Files.writeString(file, writeCsv(table))
            TableFormat.MARKDOWN -> Files.writeString(file, writeMarkdown(table))
            TableFormat.XLSX -> binary().writeXlsx(table, file, sheet)
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

}
