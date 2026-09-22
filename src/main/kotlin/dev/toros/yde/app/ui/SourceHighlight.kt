package dev.toros.yde.app.ui

/**
 * ソースエディタ用の YAML 簡易トークナイザ（R8-04）。
 * 表示上の色分けだけが目的の近似実装であり、YAML の完全な構文解析はしない
 * （正確な解析は反映時の YamlIO が担う）。Compose 非依存の純ロジックとしてテスト可能にする。
 */

internal enum class YamlTokenType { KEY, STRING, NUMBER, KEYWORD, COMMENT, INDICATOR }

/** テキスト全体に対するオフセット範囲（end は排他）。 */
internal data class YamlToken(val start: Int, val end: Int, val type: YamlTokenType)

private val keyRegex = Regex("""^(\s*(?:-\s+)?)([^\s#'"\[{][^:#]*?):(?=\s|$)""")
private val numberRegex = Regex("""^-?\d+(\.\d+)?([eE][+-]?\d+)?$""")
private val keywordRegex = Regex("""^(true|false|null|~|yes|no|on|off)$""", RegexOption.IGNORE_CASE)
private val blockScalarRegex = Regex("""^[|>][+-]?\d*$""")

/** テキスト全体をトークン化する。行単位の近似で、ブロックスカラー（| / >）の内容は文字列として扱う。 */
internal fun yamlHighlightTokens(text: String): List<YamlToken> {
    val tokens = mutableListOf<YamlToken>()
    var offset = 0
    // ブロックスカラー継続中: その親キーのインデント幅（これより深い行は内容＝文字列）
    var blockScalarParentIndent: Int? = null
    for (line in text.lineSequence()) {
        val lineStart = offset
        offset += line.length + 1
        val indent = line.indexOfFirst { it != ' ' }.let { if (it < 0) line.length else it }
        if (line.isBlank()) continue

        val parent = blockScalarParentIndent
        if (parent != null) {
            if (indent > parent) {
                tokens += YamlToken(lineStart + indent, lineStart + line.length, YamlTokenType.STRING)
                continue
            }
            blockScalarParentIndent = null
        }

        // 文書区切り
        if (line.trimEnd() == "---" || line.trimEnd() == "...") {
            tokens += YamlToken(lineStart, lineStart + line.trimEnd().length, YamlTokenType.INDICATOR)
            continue
        }

        // コメント開始位置（引用符の外で、行頭または空白の直後の #）
        val commentStart = findCommentStart(line)
        val content = if (commentStart >= 0) line.substring(0, commentStart) else line
        if (commentStart >= 0) {
            tokens += YamlToken(lineStart + commentStart, lineStart + line.length, YamlTokenType.COMMENT)
        }

        var valueStart = indent
        val keyMatch = keyRegex.find(content)
        if (keyMatch != null) {
            val lead = keyMatch.groupValues[1]
            val key = keyMatch.groupValues[2]
            if (lead.contains('-')) {
                val dash = lineStart + content.indexOf('-', indent)
                tokens += YamlToken(dash, dash + 1, YamlTokenType.INDICATOR)
            }
            val keyStart = lineStart + lead.length
            tokens += YamlToken(keyStart, keyStart + key.length + 1, YamlTokenType.KEY)
            valueStart = lead.length + key.length + 1
        } else if (content.trimStart().startsWith("- ") || content.trim() == "-") {
            val dash = lineStart + content.indexOf('-', indent)
            tokens += YamlToken(dash, dash + 1, YamlTokenType.INDICATOR)
            valueStart = content.indexOf('-', indent) + 1
        }

        val value = content.substring(minOf(valueStart, content.length)).trim()
        if (value.isNotEmpty()) {
            val vStart = lineStart + content.indexOf(value, minOf(valueStart, content.length))
            val vEnd = vStart + value.length
            when {
                blockScalarRegex.matches(value) -> {
                    tokens += YamlToken(vStart, vEnd, YamlTokenType.INDICATOR)
                    blockScalarParentIndent = indent
                }
                value.startsWith("\"") || value.startsWith("'") ->
                    tokens += YamlToken(vStart, vEnd, YamlTokenType.STRING)
                numberRegex.matches(value) -> tokens += YamlToken(vStart, vEnd, YamlTokenType.NUMBER)
                keywordRegex.matches(value) -> tokens += YamlToken(vStart, vEnd, YamlTokenType.KEYWORD)
                else -> Unit  // プレーン文字列は地の色のまま（フォームの入力欄と同じ見え方）
            }
        }
    }
    return tokens
}

/** 引用符の外にあるコメント開始 # の位置（なければ -1）。行頭または空白の直後だけを開始とみなす。 */
internal fun findCommentStart(line: String): Int {
    var inSingle = false
    var inDouble = false
    for (i in line.indices) {
        when (line[i]) {
            '\'' -> if (!inDouble) inSingle = !inSingle
            '"' -> if (!inSingle) inDouble = !inDouble
            '#' -> if (!inSingle && !inDouble && (i == 0 || line[i - 1] == ' ' || line[i - 1] == '\t')) return i
        }
    }
    return -1
}

// ---------------------------------------------------------------- 置換（R8-04）

/** 大文字小文字を無視して最初の一致を置換する。一致がなければ null。 */
internal fun replaceFirstIgnoreCase(text: String, search: String, replacement: String): String? {
    if (search.isEmpty()) return null
    val index = text.indexOf(search, ignoreCase = true)
    if (index < 0) return null
    return text.substring(0, index) + replacement + text.substring(index + search.length)
}

/** 大文字小文字を無視して全一致を置換する。置換後テキストと件数を返す。 */
internal fun replaceAllIgnoreCase(text: String, search: String, replacement: String): Pair<String, Int> {
    if (search.isEmpty()) return text to 0
    val regex = Regex(Regex.escape(search), RegexOption.IGNORE_CASE)
    val count = regex.findAll(text).count()
    return regex.replace(text, Regex.escapeReplacement(replacement)) to count
}
