package dev.toros.yde.app.format

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// 文字の形式（csv・json・markdown・html）の表の読み書き。app-core の組込みで、登録簿（app.ext.ExtensionRegistry）が最初から持つ（ADR-0015）。

/**
 * 文字の形式に共通の決まり: 読むときは文字コード（`ReadOptions.encoding`）で厳密に解読し（合わなければ `CharacterCodingException`）、
 * 書くときは UTF-8。形式ごとの違いは文字 ↔ 表の変換だけ。
 */
abstract class TextFormat : FormatCodec {
    override fun read(bytes: ByteArray, options: ReadOptions): Table = readText(bytes.decodeStrict(options.encoding), options)
    override fun write(table: Table, options: WriteOptions): ByteArray = writeText(table, options).toByteArray(Charsets.UTF_8)
    abstract fun readText(text: String, options: ReadOptions): Table
    abstract fun writeText(table: Table, options: WriteOptions): String
}

/** CSV（RFC 4180 準拠: ダブルクォート・埋め込み改行・引用符の二重化）。 */
object CsvFormat : TextFormat() {
    override val id = "csv"
    override val extensions = listOf("csv")

    override fun readText(text: String, options: ReadOptions): Table = readCsv(text, options.columns.takeIf { it.isNotEmpty() })
    override fun writeText(table: Table, options: WriteOptions): String = writeCsv(table)

    /** columns を渡すと見出しの行が無い CSV として読む（列の名前は columns の並び。1 行目からデータ）。 */
    fun readCsv(text: String, columns: List<String>? = null): Table {
        val records = parseCsv(text.removePrefix("\uFEFF"))
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
}

/** Markdown の表（1行目ヘッダ・2行目区切り・以降データ）。`\|` はエスケープとして扱う。 */
object MarkdownFormat : TextFormat() {
    override val id = "markdown"
    override val extensions = listOf("md", "markdown")

    override fun readText(text: String, options: ReadOptions): Table = readMarkdown(text)
    override fun writeText(table: Table, options: WriteOptions): String = writeMarkdown(table)

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

    fun writeMarkdown(table: Table): String = buildString {
        fun cell(v: Any?): String = (v?.toString() ?: "").replace("|", "\\|").replace("\n", "<br>")
        appendLine("| " + table.headers.joinToString(" | ") { cell(it) } + " |")
        appendLine("|" + table.headers.joinToString("|") { " --- " } + "|")
        table.rows.forEach { row -> appendLine("| " + row.joinToString(" | ") { cell(it) } + " |") }
    }
}

/** JSON（オブジェクトの配列）。path 指定時はルートオブジェクト内の配列を辿る。値の型を保持し、読み書きで往復できる（R9-05）。 */
object JsonFormat : TextFormat() {
    override val id = "json"
    override val extensions = listOf("json")

    override fun readText(text: String, options: ReadOptions): Table = readJson(text, options.jsonPath)
    override fun writeText(table: Table, options: WriteOptions): String = writeJson(table)

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

    /** セルの型を保持し、空セルのキーは出力しない。 */
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
}

/** HTML（R9-05）。書くだけ（単体で開ける派生成果物）。セルはエスケープし、改行は <br> にする。 */
object HtmlFormat : TextFormat() {
    override val id = "html"
    override val extensions = listOf("html", "htm")
    override val canRead: Boolean get() = false

    override fun readText(text: String, options: ReadOptions): Table = error("HTML はエクスポート専用の形式です")
    override fun writeText(table: Table, options: WriteOptions): String = writeHtml(table, options.title)

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
}
