package dev.toros.yde.desktop.ui

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
internal fun yamlHighlightTokens(text: String): List<YamlToken> = YamlHighlighter().tokens(text)

/**
 * 行ごとの覚えつきのトークナイザ（P43 R43-05）。1 文字打つたびに全文を解き直さないよう、
 * 「行の文字列＋その行に入るときの状態（ブロックスカラーの親のインデント）」を鍵に、行の結果（行内の相対位置）を覚える。
 * 1 万行でも、変わっていない行は覚えを引くだけで済む。
 */
internal class YamlHighlighter {
    /** 行の結果: 行内の相対位置のトークンと、次の行に渡す状態。 */
    private class LineResult(val tokens: List<YamlToken>, val nextState: Int)

    private val cache = HashMap<Pair<String, Int>, LineResult>()

    fun tokens(text: CharSequence): List<YamlToken> {
        if (cache.size > CACHE_LIMIT) cache.clear()
        val tokens = ArrayList<YamlToken>()
        var offset = 0
        var state = NO_BLOCK   // ブロックスカラー継続中なら親キーのインデント幅
        var from = 0
        val n = text.length
        while (from <= n) {
            var to = from
            while (to < n && text[to] != '\n') to++
            val line = text.subSequence(from, to).toString()
            val key = line to state
            val result = cache.getOrPut(key) { tokenizeLine(line, state) }
            for (t in result.tokens) tokens += YamlToken(t.start + offset, t.end + offset, t.type)
            state = result.nextState
            offset += line.length + 1
            from = to + 1
        }
        return tokens
    }

    private fun tokenizeLine(line: String, incoming: Int): LineResult {
        val tokens = mutableListOf<YamlToken>()
        var blockParent = incoming
        val indent = line.indexOfFirst { it != ' ' }.let { if (it < 0) line.length else it }
        if (line.isBlank()) return LineResult(tokens, blockParent)

        if (blockParent != NO_BLOCK) {
            if (indent > blockParent) {
                tokens += YamlToken(indent, line.length, YamlTokenType.STRING)
                return LineResult(tokens, blockParent)
            }
            blockParent = NO_BLOCK
        }

        // 文書区切り
        if (line.trimEnd() == "---" || line.trimEnd() == "...") {
            tokens += YamlToken(0, line.trimEnd().length, YamlTokenType.INDICATOR)
            return LineResult(tokens, blockParent)
        }

        // コメント開始位置（引用符の外で、行頭または空白の直後の #）
        val commentStart = findCommentStart(line)
        val content = if (commentStart >= 0) line.substring(0, commentStart) else line
        if (commentStart >= 0) tokens += YamlToken(commentStart, line.length, YamlTokenType.COMMENT)

        var valueStart = indent
        val keyMatch = keyRegex.find(content)
        if (keyMatch != null) {
            val lead = keyMatch.groupValues[1]
            val key = keyMatch.groupValues[2]
            if (lead.contains('-')) {
                val dash = content.indexOf('-', indent)
                tokens += YamlToken(dash, dash + 1, YamlTokenType.INDICATOR)
            }
            tokens += YamlToken(lead.length, lead.length + key.length + 1, YamlTokenType.KEY)
            valueStart = lead.length + key.length + 1
        } else if (content.trimStart().startsWith("- ") || content.trim() == "-") {
            val dash = content.indexOf('-', indent)
            tokens += YamlToken(dash, dash + 1, YamlTokenType.INDICATOR)
            valueStart = dash + 1
        }

        val value = content.substring(minOf(valueStart, content.length)).trim()
        if (value.isNotEmpty()) {
            val vStart = content.indexOf(value, minOf(valueStart, content.length))
            val vEnd = vStart + value.length
            when {
                blockScalarRegex.matches(value) -> {
                    tokens += YamlToken(vStart, vEnd, YamlTokenType.INDICATOR)
                    blockParent = indent
                }
                value.startsWith("\"") || value.startsWith("'") -> tokens += YamlToken(vStart, vEnd, YamlTokenType.STRING)
                numberRegex.matches(value) -> tokens += YamlToken(vStart, vEnd, YamlTokenType.NUMBER)
                keywordRegex.matches(value) -> tokens += YamlToken(vStart, vEnd, YamlTokenType.KEYWORD)
                else -> Unit  // プレーン文字列は地の色のまま（フォームの入力欄と同じ見え方）
            }
        }
        return LineResult(tokens, blockParent)
    }

    private companion object {
        const val NO_BLOCK = -1
        const val CACHE_LIMIT = 50_000
    }
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

