package dev.toros.yde.app.ui

import dev.toros.yde.app.text.LineMerge
/**
 * ソースの基本操作（純ロジック。P43 段階 1）。文字列と選択範囲（文字位置。end は排他）を受け取り、
 * 新しい文字列と選択範囲を返す。行は `\n` で区切り、末尾の改行の有無はそのまま保つ。
 * 画面（SourcePane）はキー操作をここへ渡して結果を欄に当てるだけで、規則はここに閉じる。
 */
internal object SourceEditing {
    /** 操作の結果: 新しい文字列と選択範囲（end は排他。カーソルだけなら start == end）。 */
    data class Result(val text: String, val start: Int, val end: Int)

    const val INDENT = "  "

    // ---------------------------------------------------------------- 行の位置

    /** 位置を含む行の番号（0 始まり）。 */
    fun lineOf(starts: IntArray, offset: Int): Int {
        val lineCount = starts.size - 1
        var lo = 0
        var hi = lineCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo.coerceIn(0, maxOf(lineCount - 1, 0))
    }

    /** 選択が触れる行の範囲（両端含む）。選択の終わりが行頭にあって始まりと別の行なら、その行は含めない。 */
    fun touchedLines(text: String, start: Int, end: Int): IntRange {
        val starts = LineMerge.lineStarts(text)
        val first = lineOf(starts, minOf(start, end))
        var last = lineOf(starts, maxOf(start, end))
        if (last > first && starts[last] == maxOf(start, end)) last--
        return first..last
    }

    // ---------------------------------------------------------------- Enter

    /**
     * Enter で挿入する文字列: 改行と、前の行に合わせたインデント。前の行（カーソルまで）の内容が `:` で終わるか
     * `-` だけなら 1 段深くする。カーソルが行頭の空白の中にあれば、その位置までのインデントに留める。
     */
    fun newlineInsertion(text: String, caret: Int): String {
        val starts = LineMerge.lineStarts(text)
        val lineStart = starts[lineOf(starts, caret)]
        val before = text.substring(lineStart, caret)
        val leading = before.takeWhile { it == ' ' }.length
        val commentStart = findCommentStart(before)
        val content = (if (commentStart >= 0) before.substring(0, commentStart) else before).trimEnd()
        val deeper = content.isNotEmpty() && (content.endsWith(":") || content.trimStart() == "-")
        val indent = if (before.length == leading) leading else leading + (if (deeper) INDENT.length else 0)
        return "\n" + " ".repeat(indent)
    }

    // ---------------------------------------------------------------- 行の操作

    /** 選択範囲の行を 1 段インデント（outdent なら 1 段戻す。空行は触らない）。 */
    fun indent(text: String, start: Int, end: Int, outdent: Boolean): Result {
        val lines = text.split('\n').toMutableList()
        val range = touchedLines(text, start, end)
        val deltas = IntArray(lines.size)   // 各行の長さの増減
        for (i in range) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (outdent) {
                val remove = line.takeWhile { it == ' ' }.length.coerceAtMost(INDENT.length)
                lines[i] = line.substring(remove)
                deltas[i] = -remove
            } else {
                lines[i] = INDENT + line
                deltas[i] = INDENT.length
            }
        }
        return Result(lines.joinToString("\n"), shift(text, start, deltas), shift(text, end, deltas))
    }

    /**
     * コメントの切替。範囲の空でない行がすべて `#` で始まっていれば外し、そうでなければ最も浅いインデントの位置に `# ` を入れる。
     */
    fun toggleComment(text: String, start: Int, end: Int): Result {
        val lines = text.split('\n').toMutableList()
        val range = touchedLines(text, start, end)
        val targets = range.filter { lines[it].isNotBlank() }
        if (targets.isEmpty()) return Result(text, start, end)
        val deltas = IntArray(lines.size)
        val allCommented = targets.all { lines[it].trimStart().startsWith("#") }
        if (allCommented) {
            for (i in targets) {
                val line = lines[i]
                val lead = line.takeWhile { it == ' ' }.length
                val after = line.substring(lead + 1)
                val removed = if (after.startsWith(" ")) 2 else 1
                lines[i] = line.substring(0, lead) + after.removePrefix(" ")
                deltas[i] = -removed
            }
        } else {
            val minIndent = targets.minOf { lines[it].takeWhile { c -> c == ' ' }.length }
            for (i in targets) {
                val line = lines[i]
                lines[i] = line.substring(0, minIndent) + "# " + line.substring(minIndent)
                deltas[i] = 2
            }
        }
        return Result(lines.joinToString("\n"), shift(text, start, deltas), shift(text, end, deltas))
    }

    /** 選択範囲の行を上（up）か下へ 1 行分動かす。端にあれば何もしない。 */
    fun moveLines(text: String, start: Int, end: Int, up: Boolean): Result {
        val lines = text.split('\n').toMutableList()
        val range = touchedLines(text, start, end)
        if (up && range.first == 0) return Result(text, start, end)
        if (!up && range.last >= lines.lastIndex) return Result(text, start, end)
        val block = lines.subList(range.first, range.last + 1).toList()
        val neighbor = if (up) lines[range.first - 1] else lines[range.last + 1]
        val delta = (neighbor.length + 1) * (if (up) -1 else 1)
        val rebuilt = lines.toMutableList()
        if (up) {
            rebuilt.removeAt(range.first - 1)
            rebuilt.add(range.last, neighbor)
        } else {
            rebuilt.removeAt(range.last + 1)
            rebuilt.add(range.first, neighbor)
        }
        check(rebuilt.subList(range.first + (if (up) -1 else 1), range.last + 1 + (if (up) -1 else 1)) == block)
        return Result(rebuilt.joinToString("\n"), start + delta, end + delta)
    }

    /** 選択範囲の行を、その下に複製する。選択は複製した側へ移る。 */
    fun duplicateLines(text: String, start: Int, end: Int): Result {
        val lines = text.split('\n').toMutableList()
        val range = touchedLines(text, start, end)
        val block = lines.subList(range.first, range.last + 1).toList()
        lines.addAll(range.last + 1, block)
        val delta = block.sumOf { it.length + 1 }
        return Result(lines.joinToString("\n"), start + delta, end + delta)
    }

    /** 行の長さの増減（deltas）を、位置に写す。増減は各行の先頭で起きたとみなす（行頭にある位置も動く）。 */
    private fun shift(text: String, offset: Int, deltas: IntArray): Int {
        val starts = LineMerge.lineStarts(text)
        var result = offset
        for (i in deltas.indices) {
            if (deltas[i] == 0) continue
            if (starts[i] <= offset) {
                // 行頭からの距離より大きく戻さない
                val col = offset - starts[i]
                result += if (deltas[i] < 0) -minOf(-deltas[i], col) else deltas[i]
            }
        }
        return result.coerceAtLeast(0)
    }

    // ---------------------------------------------------------------- 検索

    /** 検索の一致（start..endInclusive）。正規表現が不正なら null。空の検索は一致なし。 */
    fun matches(text: String, query: String, regex: Boolean, caseSensitive: Boolean): List<IntRange>? {
        if (query.isEmpty()) return emptyList()
        val pattern = compile(query, regex, caseSensitive) ?: return null
        return pattern.findAll(text).mapNotNull { m -> m.range.takeIf { !it.isEmpty() } }.toList()
    }

    private fun compile(query: String, regex: Boolean, caseSensitive: Boolean): Regex? {
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return runCatching { if (regex) Regex(query, options) else Regex(Regex.escape(query), options) }.getOrNull()
    }

    /** 位置 from 以降の最初の一致の添字（無ければ先頭へ戻る。一致が無ければ -1）。 */
    fun nextMatch(matches: List<IntRange>, from: Int): Int {
        if (matches.isEmpty()) return -1
        val i = matches.indexOfFirst { it.first >= from }
        return if (i < 0) 0 else i
    }

    /** 位置 before より前の最後の一致の添字（無ければ末尾へ回る。一致が無ければ -1）。 */
    fun prevMatch(matches: List<IntRange>, before: Int): Int {
        if (matches.isEmpty()) return -1
        val i = matches.indexOfLast { it.first < before }
        return if (i < 0) matches.lastIndex else i
    }

    /**
     * 1 つの一致を置き換える。正規表現なら `$1` のような群の参照を展開する
     * （一致から始まる部分文字列に replaceFirst を当て、一致の分だけ取り出す）。
     */
    fun replaceMatch(text: String, match: IntRange, query: String, replaceWith: String, regex: Boolean, caseSensitive: Boolean): Result {
        val replacement = if (!regex) replaceWith else {
            val pattern = compile(query, regex, caseSensitive) ?: return Result(text, match.first, match.last + 1)
            val suffix = text.substring(match.first)
            val replaced = runCatching { pattern.replaceFirst(suffix, replaceWith) }.getOrNull()
                ?: return Result(text, match.first, match.last + 1)
            replaced.substring(0, replaced.length - (suffix.length - (match.last + 1 - match.first)))
        }
        val newText = text.substring(0, match.first) + replacement + text.substring(match.last + 1)
        return Result(newText, match.first, match.first + replacement.length)
    }

    /** すべての一致を置き換える。戻り値は新しい文字列と件数（正規表現が不正なら元のまま 0 件）。 */
    fun replaceAll(text: String, query: String, replaceWith: String, regex: Boolean, caseSensitive: Boolean): Pair<String, Int> {
        if (query.isEmpty()) return text to 0
        val pattern = compile(query, regex, caseSensitive) ?: return text to 0
        val count = pattern.findAll(text).count()
        if (count == 0) return text to 0
        val replacement = if (regex) replaceWith else Regex.escapeReplacement(replaceWith)
        return runCatching { pattern.replace(text, replacement) to count }.getOrDefault(text to 0)
    }
}
