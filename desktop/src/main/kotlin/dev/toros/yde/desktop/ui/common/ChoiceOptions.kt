package dev.toros.yde.desktop.ui.common

/** 値と表示を分ける。検索や説明の開閉では value を書き戻さない。 */
internal data class ChoiceOption<T>(
    val value: T,
    val label: String,
    val subtitle: String = "",
    val description: String? = null,
    val selected: Boolean = false,
    val searchText: String = "",
    /** 選べる候補か（unique の配列で、ほかの要素が使っている値は選べない。一覧には残して理由を添える）。 */
    val enabled: Boolean = true,
)

internal fun <T> filterChoiceOptions(options: List<ChoiceOption<T>>, query: String): List<ChoiceOption<T>> {
    val words = query.trim().split(Regex("(?U)\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return options
    return options.filter { option ->
        val text = listOf(option.label, option.subtitle, option.searchText).joinToString(" ")
        words.all { text.contains(it, ignoreCase = true) }
    }
}

/** IME 確定の Enter と変換取消の Esc を、同じ一押しで一覧の操作に使わない。 */
internal class ChoiceKeyPress {
    private var allowed: Boolean? = null

    fun press(composing: Boolean) {
        if (allowed == null) allowed = !composing
    }

    fun release(composing: Boolean = false): Boolean = (allowed == true && !composing).also { allowed = null }
}
