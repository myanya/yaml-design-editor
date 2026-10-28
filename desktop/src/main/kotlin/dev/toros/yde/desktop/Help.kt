package dev.toros.yde.desktop

/**
 * 内蔵ヘルプ。利用者マニュアル（docs/manual の .md ファイル）をビルド時に資源 help へ入れたもの（build.gradle.kts の copyHelp）。
 * 題目は資源名（`schema.md` など）。本文の相対リンク（`views.md` など）は題目どうしの移動として扱う（ui/MarkdownPane.kt）。
 */
object Help {
    /** 入口の題目（マニュアルの目次）。 */
    const val INDEX = "README.md"

    data class Topic(val file: String, val title: String)

    private val loader: ClassLoader get() = Thread.currentThread().contextClassLoader ?: Help::class.java.classLoader

    /** 題目の一覧（目次を先頭に、あとは名前順）。資源が無ければ空。 */
    fun topics(): List<Topic> {
        val names = loader.getResourceAsStream("help/index.txt")?.bufferedReader()?.readLines()
            ?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val ordered = listOf(INDEX).filter { it in names } + names.filter { it != INDEX }
        return ordered.map { Topic(it, titleOf(it)) }
    }

    /** 題目の本文（Markdown）。無ければ null。 */
    fun text(file: String): String? =
        loader.getResourceAsStream("help/$file")?.bufferedReader()?.readText()

    /** 本文の最初の見出し（`# …`）を題名にする。無ければファイル名。 */
    fun titleOf(file: String): String =
        text(file)?.lineSequence()?.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim() ?: file
}
