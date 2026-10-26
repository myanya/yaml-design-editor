package dev.toros.yde.app.ui

import dev.toros.yde.app.text.LineMerge
/**
 * カーソル位置の文脈（純ロジック。P43 段階 2 の補完が使う）。
 * 解析できない書きかけ（打っている最中の行）でも働くよう、木ではなく文字列のインデントから親のパスを読む。
 * 正確な位置の表（解析できたときの `YamlPositions`）とは役割が違い、こちらは「いま何を打っているか」を知るためのもの。
 */
internal object SourceContext {
    enum class Mode {
        /** キー名を打っている（行頭の空白の後、`:` の前）。 */
        KEY,
        /** 値を打っている（`key:` の後）。 */
        VALUE,
        /** 補完の対象にならない所（ブロックスカラーの中・コメントなど）。 */
        NONE,
    }

    /**
     * @param parentPath 打っている項目の親（キーなら親のオブジェクト、値ならその項目を含むオブジェクト）。
     * @param key 値を打っているときの項目のキー。
     * @param prefix 打ちかけの語（置き換える範囲 replaceStart..replaceEnd の文字列）。
     */
    data class Context(
        val mode: Mode,
        val parentPath: List<Any>,
        val key: String?,
        val prefix: String,
        val replaceStart: Int,
        val replaceEnd: Int,
    )

    private class Frame(val indent: Int, val seg: Any, val blockScalar: Boolean = false) {
        /** この枠の下に現れた配列要素の数（次の要素の添字）。 */
        var items = 0
    }

    fun at(text: String, caret: Int): Context {
        val lines = text.split('\n')
        val starts = LineMerge.lineStarts(text)
        val lineIndex = SourceEditing.lineOf(starts, caret)
        val lineStart = starts[lineIndex]
        val line = lines[lineIndex]
        val col = (caret - lineStart).coerceIn(0, line.length)
        val before = line.substring(0, col)
        val stack = outline(lines, lineIndex)
        var rootItems = stack.rootItems
        val frames = stack.frames

        val leading = line.takeWhile { it == ' ' }.length
        val indent = minOf(leading, col)
        if (before.trimStart().startsWith("#")) return Context(Mode.NONE, emptyList(), null, "", caret, caret)
        // ブロックスカラーの中（親より深い行）は対象外
        frames.lastOrNull()?.let { top -> if (top.blockScalar && indent > top.indent) return Context(Mode.NONE, emptyList(), null, "", caret, caret) }
        while (frames.isNotEmpty() && frames.last().indent >= indent) frames.removeLast()

        var at = indent
        var rest = before.substring(minOf(indent, before.length))
        val path = frames.map { it.seg }.toMutableList()
        // 配列要素（`- `）: 親の枠の要素数が添字
        while (rest.startsWith("- ") || rest == "-") {
            val container = frames.lastOrNull()
            val index = if (container != null) container.items else rootItems
            path += index
            val stripped = rest.removePrefix("-").let { if (it.startsWith(" ")) it.substring(1) else it }
            at += rest.length - stripped.length
            rest = stripped
            frames += Frame(at - 2, index)   // 添字の枠（同じ行の続きのキーの親）
        }
        val colon = rest.indexOf(':')
        val isValue = colon >= 0 && (colon + 1 >= rest.length || rest[colon + 1] == ' ')
        return if (isValue) {
            val key = rest.substring(0, colon).trim()
            val afterColon = rest.substring(colon + 1)
            val valueText = afterColon.trimStart()
            val valueStart = lineStart + at + colon + 1 + (afterColon.length - valueText.length)
            Context(Mode.VALUE, path, key, valueText, valueStart, caret)
        } else {
            Context(Mode.KEY, path, null, rest, lineStart + at, caret)
        }
    }

    private class Outline(val frames: MutableList<Frame>, val rootItems: Int)

    /** 指定した行の前までを読んだときの枠（開いている親のキー・配列の添字）。 */
    private fun outline(lines: List<String>, until: Int): Outline {
        val frames = mutableListOf<Frame>()
        var rootItems = 0
        for (i in 0 until until) {
            val line = lines[i]
            if (line.isBlank()) continue
            val indent = line.takeWhile { it == ' ' }.length
            val trimmed = line.substring(indent)
            if (trimmed.startsWith("#")) continue
            frames.lastOrNull()?.let { top -> if (top.blockScalar && indent > top.indent) return@let null }?.let { } // ブロックスカラーの内容は読まない
            val top = frames.lastOrNull()
            if (top != null && top.blockScalar && indent > top.indent) continue
            while (frames.isNotEmpty() && frames.last().indent >= indent) frames.removeLast()
            var at = indent
            var rest = trimmed
            while (rest.startsWith("- ") || rest == "-") {
                val container = frames.lastOrNull()
                val index = if (container != null) container.items++ else rootItems++
                val stripped = rest.removePrefix("-").let { if (it.startsWith(" ")) it.substring(1) else it }
                at += rest.length - stripped.length
                rest = stripped
                frames += Frame(at - 2, index)
            }
            if (rest.isBlank()) continue
            val commentStart = findCommentStart(rest)
            val content = if (commentStart >= 0) rest.substring(0, commentStart) else rest
            val colon = content.indexOf(':')
            if (colon < 0 || !(colon + 1 >= content.length || content[colon + 1] == ' ')) continue
            val key = content.substring(0, colon).trim()
            val value = content.substring(colon + 1).trim()
            val block = value.isNotEmpty() && (value[0] == '|' || value[0] == '>')
            frames += Frame(at, key, block)
        }
        return Outline(frames, rootItems)
    }
}
