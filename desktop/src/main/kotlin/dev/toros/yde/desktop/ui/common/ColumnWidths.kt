package dev.toros.yde.desktop.ui.common

/**
 * 表の列幅を中身から見積もる純粋な計算（フォームの表とビューの表で共有する。Compose に依存しない）。
 * 文字の幅は、全角（漢字・かな・全角記号）を 1em、それ以外を 0.62em と見積もる（等幅でない字形でもはみ出しにくい側に寄せる）。
 * 列幅は「見出しと値のいちばん広いもの＋欄の飾り」を、型ごとの最小と上限の間に収める。上限を超える値は、セルの中で折り返すか省略する。
 * 値は全部ではなく先頭から [SAMPLE_ROWS] 行だけを見る（行の多い表でも描くたびの計算を軽く保つ）。
 */
object ColumnWidths {
    /** 値を見る行数の上限。 */
    const val SAMPLE_ROWS = 200

    private const val NARROW_EM = 0.62f

    /** 全角として数える文字か（東アジアの幅が広い文字の主な範囲）。 */
    fun isWide(c: Char): Boolean {
        val code = c.code
        return code in 0x1100..0x115F || code in 0x2E80..0xA4CF || code in 0xAC00..0xD7A3 ||
            code in 0xF900..0xFAFF || code in 0xFE30..0xFE4F || code in 0xFF00..0xFF60 || code in 0xFFE0..0xFFE6
    }

    /** 1 行の文字の幅（dp）。改行を含む文字は、いちばん長い行。fontSp は文字の大きさ（sp＝dp とみなす）。 */
    fun textWidth(text: String, fontSp: Float): Float =
        text.lineSequence().maxOfOrNull { line -> line.sumOf { c -> if (isWide(c)) 1.0 else NARROW_EM.toDouble() }.toFloat() * fontSp } ?: 0f

    /**
     * 列幅（dp）: 見出し（headerSp。太字＋見出しの余白 headerPad）と、値（valueSp）のいちばん広いものに欄の飾り（chrome＝入力欄の余白・ボタン・
     * セルの余白）を足したもの、の広い方を min ≦ 幅 ≦ max に収める。値は先頭から [SAMPLE_ROWS] 件。
     */
    fun fit(
        header: String,
        values: List<String>,
        headerSp: Float,
        valueSp: Float,
        chrome: Float,
        min: Float,
        max: Float,
        headerPad: Float = 8f,
    ): Float {
        val headerWidth = textWidth(header, headerSp) * 1.05f + headerPad   // 見出しは太字
        val valueWidth = values.asSequence().take(SAMPLE_ROWS).maxOfOrNull { textWidth(it, valueSp) }?.plus(chrome) ?: chrome
        return maxOf(headerWidth, valueWidth).coerceIn(min, maxOf(min, max))
    }
}
