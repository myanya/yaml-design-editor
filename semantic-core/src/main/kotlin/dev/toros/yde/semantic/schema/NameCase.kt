package dev.toros.yde.semantic.schema

/**
 * 名前の書き方をそろえる（変換のひな型の `$case`）。業務の ID（`complete-todo`・`todo-task.finish`・`IN_PROGRESS`）を、
 * 機械的な規則だけでコードの名前にする。英語の文法（複数形など）は入れない。
 *
 * 語の区切り: 英数字以外の文字（`-` `_` `.` 空白など）と、小文字か数字の後の大文字（`completeTodo` → complete / Todo）と、
 * 大文字の並びの後の「大文字＋小文字」（`HTTPServer` → HTTP / Server）。英数字以外の文字（日本語など）は語の中にそのまま残す。
 */
object NameCase {
    /** 書き方の名前: camel＝completeTodo・pascal＝CompleteTodo・snake＝complete_todo・kebab＝complete-todo・constant＝COMPLETE_TODO。 */
    val STYLES: List<String> = listOf("camel", "pascal", "snake", "kebab", "constant")

    fun convert(text: String, style: String): String {
        val words = words(text)
        if (words.isEmpty()) return ""
        return when (style) {
            "camel" -> words.first().lowercase() + words.drop(1).joinToString("") { capitalize(it) }
            "pascal" -> words.joinToString("") { capitalize(it) }
            "snake" -> words.joinToString("_") { it.lowercase() }
            "kebab" -> words.joinToString("-") { it.lowercase() }
            "constant" -> words.joinToString("_") { it.uppercase() }
            else -> error("名前の書き方「$style」はありません（${STYLES.joinToString("・")}）")
        }
    }

    /** 語に分ける。 */
    fun words(text: String): List<String> =
        text.split(SEPARATORS).filter { it.isNotEmpty() }.flatMap { part -> part.split(BOUNDARY).filter { it.isNotEmpty() } }

    private fun capitalize(word: String): String = word.lowercase().replaceFirstChar { it.uppercase() }

    /** 区切りの文字: ASCII の英数字と、ASCII の外の文字（日本語など）以外。 */
    private val SEPARATORS = Regex("""[\x00-\x2F\x3A-\x40\x5B-\x60\x7B-\x7F]+""")

    /** 語の境目（区切りの文字を持たない）: 小文字・数字の後の大文字、大文字の並びの後の「大文字＋小文字」。 */
    private val BOUNDARY = Regex("""(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])""")
}
