package dev.toros.yde.app.ui

/**
 * 行の差分と 3 者マージ（純ロジック。P43 段階 0）。
 * ソースの下書きに、他所（フォーム・Undo・操作）で変わった木の正規化テキストの差分だけを差し込むために使う。
 * 元＝前回の正規化テキスト、自分＝下書き、相手＝今回の正規化テキスト。Git の 3 者マージと同じ考え方で、
 * 重なる変更は自分（下書き）を優先し、件数だけ返す。
 */
internal object LineMerge {
    /** 行の編集 1 つ: 元の [start, end) 行（0 始まり）を lines に置き換える。 */
    data class Hunk(val start: Int, val end: Int, val lines: List<String>)

    data class Merge(val lines: List<String>, val conflicts: Int)

    /** 中央部を LCS で解く上限（行数の積）。超えたら中央部を 1 つの置換にする（差し込みの正しさは変わらない。粒度が粗くなるだけ）。 */
    private const val LCS_LIMIT = 4_000_000L

    /**
     * a → b の行差分。先頭と末尾の共通部分を落としてから中央を解くので、1 か所の変更なら 1 万行でも速い。
     * 結果は start 昇順で重ならない。
     */
    fun diff(a: List<String>, b: List<String>): List<Hunk> {
        var prefix = 0
        val max = minOf(a.size, b.size)
        while (prefix < max && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < max - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
        val aMid = a.subList(prefix, a.size - suffix)
        val bMid = b.subList(prefix, b.size - suffix)
        if (aMid.isEmpty() && bMid.isEmpty()) return emptyList()
        if (aMid.isEmpty() || bMid.isEmpty() || aMid.size.toLong() * bMid.size > LCS_LIMIT) {
            return listOf(Hunk(prefix, prefix + aMid.size, bMid.toList()))
        }
        return lcsHunks(aMid, bMid).map { Hunk(it.start + prefix, it.end + prefix, it.lines) }
    }

    private fun lcsHunks(a: List<String>, b: List<String>): List<Hunk> {
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.lastIndex downTo 0) {
            for (j in b.lastIndex downTo 0) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val hunks = mutableListOf<Hunk>()
        var i = 0
        var j = 0
        var open: Int? = null          // 進行中の hunk の開始行（a 側）
        val replacement = mutableListOf<String>()
        fun close(endExclusive: Int) {
            val s = open ?: return
            hunks += Hunk(s, endExclusive, replacement.toList())
            replacement.clear()
            open = null
        }
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> { close(i); i++; j++ }
                j < b.size && (i == a.size || lcs[i][j + 1] >= lcs[i + 1][j]) -> {
                    if (open == null) open = i
                    replacement += b[j]; j++
                }
                else -> { if (open == null) open = i; i++ }
            }
        }
        close(i)
        return hunks
    }

    /** 3 者マージの結果: ours の行番号に写した当てられる hunk と、当てなかった（重なった）数。 */
    data class MergeHunks(val hunks: List<Hunk>, val conflicts: Int)

    /**
     * 3 者マージ。base → theirs の各 hunk を ours に当てる。base → ours の hunk と範囲が重なる hunk は当てず、
     * conflicts に数える（その箇所は ours のまま）。
     */
    fun merge3(base: List<String>, ours: List<String>, theirs: List<String>): Merge {
        val m = mergeHunks(base, ours, theirs)
        return Merge(applyHunks(ours, m.hunks), m.conflicts)
    }

    /** `merge3` の、当てる hunk（ours の行番号）だけを返す版。文字列とカーソルへの適用は `applyToText` で行う。 */
    fun mergeHunks(
        base: List<String>, ours: List<String>, theirs: List<String>,
        splitIncomingReplacements: Boolean = false,
    ): MergeHunks {
        val oursHunks = diff(base, ours)
        // 履歴への書式の重ね戻しでは、隣接するだけの変更まで競合として捨てない。
        // 行数が同じ置換だけ分ける。構造として安全かの検査は呼び出し元が行う。
        val theirsHunks = diff(base, theirs).flatMap { h ->
            if (splitIncomingReplacements && h.end - h.start == h.lines.size) {
                h.lines.mapIndexed { i, line -> Hunk(h.start + i, h.start + i + 1, listOf(line)) }
            } else listOf(h)
        }
        if (theirsHunks.isEmpty()) return MergeHunks(emptyList(), 0)
        val applicable = mutableListOf<Hunk>()   // ours の行番号に写した hunk
        var conflicts = 0
        for (t in theirsHunks) {
            if (oursHunks.any { o -> overlaps(o, t) }) { conflicts++; continue }
            // t より前にある ours 側の hunk の行数の増減ぶんだけ、位置をずらす
            val shift = oursHunks.filter { it.end <= t.start && !(it.start == t.start && it.end == t.end) }
                .sumOf { it.lines.size - (it.end - it.start) }
            applicable += Hunk(t.start + shift, t.end + shift, t.lines)
        }
        return MergeHunks(applicable, conflicts)
    }

    /** 空の範囲（挿入）は、相手の範囲の内側にあるときと、同じ位置の挿入どうしのときに重なるとみなす。 */
    private fun overlaps(o: Hunk, t: Hunk): Boolean {
        val oEmpty = o.start == o.end
        val tEmpty = t.start == t.end
        return when {
            oEmpty && tEmpty -> o.start == t.start
            oEmpty -> o.start > t.start && o.start < t.end
            tEmpty -> t.start > o.start && t.start < o.end
            else -> o.start < t.end && t.start < o.end
        }
    }

    /** hunk（対象の行番号で表したもの。重ならない）を後ろから当てる。 */
    fun applyHunks(lines: List<String>, hunks: List<Hunk>): List<String> {
        if (hunks.isEmpty()) return lines
        val out = lines.toMutableList()
        for (h in hunks.sortedByDescending { it.start }) {
            repeat(h.end - h.start) { out.removeAt(h.start) }
            out.addAll(h.start, h.lines)
        }
        return out
    }

    /**
     * 文字列への適用。hunk の行番号を文字の範囲に直して後ろから置き換え、カーソル（文字位置）も写す。
     * カーソルが置き換えた範囲の後ろなら増減ぶんずれ、中なら範囲の先頭へ寄せる。
     */
    data class TextResult(val text: String, val caret: Int)

    fun applyToText(text: String, hunks: List<Hunk>, caret: Int): TextResult {
        if (hunks.isEmpty()) return TextResult(text, caret)
        val starts = lineStarts(text)
        val sb = StringBuilder(text)
        var newCaret = caret
        for (h in hunks.sortedByDescending { it.start }) {
            val from = starts[h.start]
            val to = if (h.end < starts.size - 1) starts[h.end] else text.length
            val replacement = buildString {
                h.lines.forEach { append(it).append('\n') }
                // 末尾の行を置き換えるとき、元に末尾の改行が無ければ足さない
                if (h.end >= starts.size - 1 && !text.endsWith("\n") && isNotEmpty()) setLength(length - 1)
            }
            sb.replace(from, to, replacement)
            newCaret = when {
                newCaret >= to -> newCaret + (replacement.length - (to - from))
                newCaret > from -> from
                else -> newCaret
            }
        }
        return TextResult(sb.toString(), newCaret.coerceIn(0, sb.length))
    }

    /** 各行の開始位置（末尾に text.length の番兵を足す）。 */
    fun lineStarts(text: String): IntArray {
        val starts = ArrayList<Int>()
        starts += 0
        for (i in text.indices) if (text[i] == '\n') starts += i + 1
        starts += text.length
        return starts.toIntArray()
    }

    /** `lines()` と同じ分割（末尾が改行で終わる文字列は最後に空行を持つ）。 */
    fun splitLines(text: String): List<String> = text.split('\n')
}
