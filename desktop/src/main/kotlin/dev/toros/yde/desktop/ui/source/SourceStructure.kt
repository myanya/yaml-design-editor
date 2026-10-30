package dev.toros.yde.desktop.ui.source

import dev.toros.yde.app.text.LineMerge
import dev.toros.yde.yaml.YamlIO

/**
 * ソースの構造に関わる純ロジック（P43 段階 3）: 折り畳める区画・畳んだときの見え方・保存済みとの差分の印・
 * 選択範囲だけの整形。画面（SourcePane）はここで決めた範囲を欄と行番号に当てるだけ。
 */
internal object SourceStructure {
    /** 折り畳める区画: 見出しの行（残る）と、隠れる最後の行（両端含む）。 */
    data class FoldRegion(val header: Int, val end: Int) {
        val hiddenLines: Int get() = end - header
    }

    private fun indentOf(line: String): Int = line.takeWhile { it == ' ' }.length

    /** 見出しの行から始まる区画（次の行が深いときだけ。終わりは、同じかより浅い行の直前。末尾の空行は含めない）。 */
    fun regionAt(lines: List<String>, header: Int): FoldRegion? {
        if (header !in lines.indices) return null
        val line = lines[header]
        if (line.isBlank()) return null
        val indent = indentOf(line)
        var end = -1
        var i = header + 1
        while (i < lines.size) {
            val l = lines[i]
            if (l.isBlank()) { i++; continue }
            if (indentOf(l) <= indent) break
            end = i
            i++
        }
        return if (end > header) FoldRegion(header, end) else null
    }

    /** 折り畳める区画のすべて（見出しの行の昇順）。 */
    fun foldableRegions(lines: List<String>): List<FoldRegion> = lines.indices.mapNotNull { regionAt(lines, it) }

    /**
     * 畳んだときの見え方。隠れる文字の範囲（元の文字列の位置。見出しの改行の直後から、区画の最後の行の改行の直後まで）と、
     * 見出しの行の末尾に添える印の位置。表示の行 → 元の行の対応も返す。
     */
    class FoldView(
        /** 隠れる範囲（元の文字列の位置。from 昇順・重ならない）。 */
        val hidden: List<Hidden>,
        /** 表示の行番号 → 元の行番号。 */
        val displayToOriginal: IntArray,
        /** 表示の文字列（隠れた範囲を抜き、見出しの末尾に印を添えたもの）。 */
        val displayText: String,
    ) {
        /** headerEnd は見出しの行の末尾（改行の位置）で、ここに印（placeholder）が入る。 */
        class Hidden(val headerEnd: Int, val from: Int, val to: Int, val lines: Int, val placeholder: String)

        val displayLineStarts: IntArray = LineMerge.lineStarts(displayText)

        fun originalLine(display: Int): Int = displayToOriginal.getOrElse(display) { displayToOriginal.lastOrNull() ?: 0 }
        fun displayLine(original: Int): Int {
            val i = displayToOriginal.binarySearch(original)
            return if (i >= 0) i else (-i - 2).coerceAtLeast(0)
        }

        /** 元の文字列の位置 → 表示の位置（隠れた範囲の中なら印の直後）。 */
        fun displayOffset(original: Int): Int {
            var delta = 0
            for (h in hidden) {
                if (original <= h.headerEnd) break
                if (original < h.to) return h.headerEnd + delta + h.placeholder.length
                delta += h.placeholder.length - (h.to - h.from)
            }
            return original + delta
        }

        /** 表示の位置 → 元の文字列の位置（印の上なら見出しの末尾）。 */
        fun originalOffset(display: Int): Int {
            var delta = 0
            for (h in hidden) {
                val headerEndDisplay = h.headerEnd + delta
                if (display <= headerEndDisplay) break
                if (display <= headerEndDisplay + h.placeholder.length) return h.headerEnd
                delta += h.placeholder.length - (h.to - h.from)
            }
            return display - delta
        }

        val isEmpty: Boolean get() = hidden.isEmpty()
    }

    /** 畳む区画（見出しの行の集合）から見え方を作る。重なる区画は外側だけ畳む。 */
    fun foldView(text: String, foldedHeaders: Collection<Int>, placeholder: (Int) -> String = { " ⋯ $it 行" }): FoldView {
        val lines = text.split('\n')
        val starts = LineMerge.lineStarts(text)
        val hidden = mutableListOf<FoldView.Hidden>()
        val hiddenFlags = BooleanArray(lines.size)
        var i = 0
        val headers = foldedHeaders.toSortedSet()
        while (i < lines.size) {
            if (i in headers) {
                val region = regionAt(lines, i)
                if (region != null) {
                    val from = starts[region.header + 1]
                    val to = if (region.end + 1 < starts.size - 1) starts[region.end + 1] else text.length
                    hidden += FoldView.Hidden(headerEnd = from - 1, from = from, to = to, lines = region.hiddenLines, placeholder = placeholder(region.hiddenLines))
                    for (l in region.header + 1..region.end) hiddenFlags[l] = true
                    i = region.end + 1
                    continue
                }
            }
            i++
        }
        val map = lines.indices.filter { !hiddenFlags[it] }.toIntArray()
        val display = StringBuilder(text.length)
        var cursor = 0
        for (h in hidden) {
            display.append(text, cursor, h.headerEnd).append(h.placeholder)
            // 隠れた行（それぞれの改行つき）を抜く。後ろに行が続く（隠れた最後の行が改行で終わる）なら、見出しの改行は残す
            if (h.to < text.length || text[h.to - 1] == '\n') display.append('\n')
            cursor = h.to
        }
        display.append(text, cursor, text.length)
        return FoldView(hidden, map, display.toString())
    }

    /** 隠れた範囲の中にある位置か（カーソルを入れないために使う）。 */
    fun isHidden(view: FoldView, offset: Int): Boolean = view.hidden.any { offset > it.headerEnd && offset < it.to }

    // ---------------------------------------------------------------- 保存済みとの差分の印

    enum class DiffMark { ADDED, CHANGED, DELETED_AFTER }

    /**
     * 今の行ごとの印: 追加した行・変えた行、行を消した場所（その直前の行に印）。
     * 共通部分を落として差分を取るので、1 か所の編集なら 1 万行でも速い。
     */
    fun diffMarks(saved: String, current: String): Map<Int, DiffMark> {
        if (saved == current) return emptyMap()
        val hunks = LineMerge.diff(LineMerge.splitLines(saved), LineMerge.splitLines(current))
        val marks = LinkedHashMap<Int, DiffMark>()
        var shift = 0
        for (h in hunks) {
            val at = h.start + shift
            val removed = h.end - h.start
            when {
                h.lines.isEmpty() -> marks[(at - 1).coerceAtLeast(0)] = DiffMark.DELETED_AFTER
                removed == 0 -> for (l in h.lines.indices) marks[at + l] = DiffMark.ADDED
                else -> for (l in h.lines.indices) marks[at + l] = DiffMark.CHANGED
            }
            shift += h.lines.size - removed
        }
        return marks
    }

    // ---------------------------------------------------------------- 選択範囲だけの整形

    /**
     * 選択範囲の行だけを正規化する。範囲の最も浅いインデントを外して 1 つの YAML として読み、整形して同じ深さに戻す。
     * 範囲が YAML の塊になっていなければ（途中で切れているなど）null。
     */
    fun formatSelection(text: String, start: Int, end: Int): SourceEditing.Result? {
        val lines = text.split('\n')
        val range = SourceEditing.touchedLines(text, start, end)
        val block = lines.subList(range.first, range.last + 1)
        val content = block.filter { it.isNotBlank() }
        if (content.isEmpty()) return null
        val indent = content.minOf { indentOf(it) }
        if (content.any { indentOf(it) < indent }) return null
        val fragment = block.joinToString("\n") { if (it.isBlank()) "" else it.substring(indent) }
        val formatted = runCatching { YamlIO.dump(YamlIO.parse(fragment)) }.getOrNull() ?: return null
        val pad = " ".repeat(indent)
        val newLines = formatted.trimEnd('\n').split('\n').map { if (it.isBlank()) "" else pad + it }
        val rebuilt = lines.toMutableList()
        repeat(range.last - range.first + 1) { rebuilt.removeAt(range.first) }
        rebuilt.addAll(range.first, newLines)
        val starts = LineMerge.lineStarts(rebuilt.joinToString("\n"))
        val newStart = starts[range.first]
        val newEnd = if (range.first + newLines.size < starts.size - 1) starts[range.first + newLines.size] - 1 else starts.last()
        return SourceEditing.Result(rebuilt.joinToString("\n"), newStart, newEnd.coerceAtLeast(newStart))
    }
}
