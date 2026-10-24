package dev.toros.yde.app.ui

import dev.toros.yde.yaml.YamlIO

/** 履歴が決めた値に、干渉しないソースの書式を重ねる。履歴や木は変更しない。 */
internal object SourceHistorySync {
    data class Result(val text: String, val caret: Int, val normalized: Boolean)

    fun sync(base: String, source: String, target: String, caret: Int): Result {
        val targetLines = LineMerge.splitLines(target)
        val sourceLines = LineMerge.splitLines(source)
        // 通常の下書き同期と逆に、履歴側を優先する。競合しない書式だけを戻す。
        val merge = LineMerge.mergeHunks(LineMerge.splitLines(base), targetLines, sourceLines,
            splitIncomingReplacements = true)
        val merged = LineMerge.applyHunks(targetLines, merge.hunks).joinToString("\n")
        // 末尾行の置換と改行の削除が一つの差分になっても、改行だけを勝手に足さない。
        val candidate = if (!source.endsWith("\n")) merged.removeSuffix("\n") else merged
        // 行のマージだけではインデントや複数行スカラーの意味を保証できない。
        val normalized = candidate != target && !runCatching {
            YamlIO.parseAll(candidate).map(YamlIO::dumpPlain) == YamlIO.parseAll(target).map(YamlIO::dumpPlain)
        }.getOrDefault(false)
        val text = if (normalized) target else candidate
        val hunks = LineMerge.diff(sourceLines, LineMerge.splitLines(text))
        val mappedCaret = LineMerge.applyToText(source, hunks, caret).caret.coerceIn(0, text.length)
        return Result(text, mappedCaret, normalized)
    }
}
