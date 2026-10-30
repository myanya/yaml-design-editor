package dev.toros.yde.app.rules

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.streams.asSequence

/**
 * 規則テストのためのソースの走査（テスト専用。製品には入らない）。
 * main のソース（テストの書き方の規則は test のソース）をモジュールごとに読み、package・import・コメントを除いた本文を持つ。
 * 構文解析はしない。import 行と文字の一致で足りる規則だけを扱い、足りなくなったら Konsist のような解析器を検討する。
 */
class SourceFile(
    /** モジュール名（settings.gradle.kts の include の名前。yaml-core / app-core / desktop / repo-tests など） */
    val module: String,
    /** リポジトリのルートからの相対パス（区切りは /） */
    val path: String,
    val packageName: String,
    val imports: List<Import>,
    /** コメントを空白に置き換えた本文。行番号は原文と同じ */
    val code: String,
) {
    val name: String = path.substringAfterLast('/')
    val lines: List<String> = code.lines()

    fun inPackage(vararg prefixes: String): Boolean = prefixes.any { packageName == it || packageName.startsWith("$it.") }

    /** 本文の中で正規表現に当たる行（行番号は 1 始まり）。 */
    fun find(regex: Regex): List<Hit> =
        lines.mapIndexedNotNull { i, line -> if (regex.containsMatchIn(line)) Hit(this, i + 1, line.trim()) else null }

    /** 指定の接頭辞で始まる import。 */
    fun importsStartingWith(vararg prefixes: String): List<Hit> =
        imports.filter { imp -> prefixes.any { imp.name.startsWith(it) } }.map { Hit(this, it.line, "import ${it.name}") }
}

/** import の完全修飾名（`as 別名` と末尾の `.*` は除く）と行番号。 */
class Import(val name: String, val line: Int)

/** 規則に当たった場所。key は既知の違反と突き合わせる鍵（既定はファイルのパス）。 */
data class Hit(val file: SourceFile, val line: Int, val text: String, val key: String = file.path) {
    override fun toString(): String = "${file.path}:$line: $text"
}

object SourceScanner {
    /** リポジトリのルート（settings.gradle.kts のある場所）。テストの作業ディレクトリから上へ探す。 */
    val repoRoot: Path by lazy {
        val start = Paths.get("").toAbsolutePath()
        generateSequence(start) { it.parent }.firstOrNull { Files.exists(it.resolve("settings.gradle.kts")) }
            ?: error("settings.gradle.kts が見つからない（作業ディレクトリ: $start）")
    }

    /** モジュール名と main ソースの場所。 */
    val modules: Map<String, String> = linkedMapOf(
        "yaml-core" to "yaml-core/src/main/kotlin",
        "semantic-core" to "semantic-core/src/main/kotlin",
        "workspace-core" to "workspace-core/src/main/kotlin",
        "cli" to "cli/src/main/kotlin",
        "app-core" to "app-core/src/main/kotlin",
        "agent" to "agent/src/main/kotlin",
        "format-text" to "format-text/src/main/kotlin",
        "format-xlsx" to "format-xlsx/src/main/kotlin",
        "format-docx" to "format-docx/src/main/kotlin",
        "desktop" to "desktop/src/main/kotlin",
    )

    /** モジュール名と test ソースの場所（scope が TEST の規則が読む）。repo-tests はテストだけのモジュールで main が無い。 */
    val testModules: Map<String, String> =
        modules.mapValues { it.value.replace("/main/", "/test/") } + ("repo-tests" to "repo-tests/src/test/kotlin")

    /** 全モジュールの main の Kotlin ソース（1 回だけ読む）。 */
    val mainSources: List<SourceFile> by lazy { collect(modules) }

    /** 全モジュールの test の Kotlin ソース（1 回だけ読む）。 */
    val testSources: List<SourceFile> by lazy { collect(testModules) }

    private fun collect(dirs: Map<String, String>): List<SourceFile> =
        dirs.flatMap { (module, dir) ->
            val base = repoRoot.resolve(dir)
            require(Files.isDirectory(base)) { "モジュール $module のソースが無い: $base" }
            Files.walk(base).use { stream ->
                stream.asSequence().filter { it.isRegularFile() && it.extension == "kt" }.map { parse(module, it) }.toList()
            }
        }.sortedBy { it.path }

    fun parse(module: String, file: Path): SourceFile =
        parse(module, repoRoot.relativize(file).toString().replace('\\', '/'), file.readText())

    fun parse(module: String, path: String, text: String): SourceFile {
        val code = stripComments(text)
        val lines = code.lines()
        val pkg = lines.firstNotNullOfOrNull { PACKAGE.find(it)?.groupValues?.get(1) } ?: ""
        val imports = lines.mapIndexedNotNull { i, line -> IMPORT.find(line)?.let { Import(it.groupValues[1], i + 1) } }
        return SourceFile(module, path, pkg, imports, code)
    }

    private val PACKAGE = Regex("""^\s*package\s+([\w.]*\w)""")
    private val IMPORT = Regex("""^\s*import\s+([\w.]*\w)""")

    /** コメントを空白に置き換える（改行は残すので行番号が変わらない）。文字列と文字リテラルの中は触らない。 */
    fun stripComments(text: String): String = Stripper(text).run()
}

/** コメント除去の本体。入れ子のブロックコメント・生文字列・文字列テンプレート（`${ }`）の中の文字列とコメントに対応する。 */
private class Stripper(private val text: String) {
    private val out = StringBuilder(text.length)
    private var i = 0

    fun run(): String {
        code(stopAtBrace = false)
        return out.toString()
    }

    private fun peek(offset: Int = 0): Char = text.getOrElse(i + offset) { '\u0000' }

    /** コード。stopAtBrace なら対応しない '}' の手前で止まる（テンプレートの中）。 */
    private fun code(stopAtBrace: Boolean) {
        var depth = 0
        while (i < text.length) {
            val c = peek()
            when {
                c == '/' && peek(1) == '/' -> lineComment()
                c == '/' && peek(1) == '*' -> blockComment()
                c == '"' -> string()
                c == '\'' -> charLiteral()
                stopAtBrace && c == '}' && depth == 0 -> return
                else -> {
                    if (c == '{') depth++ else if (c == '}') depth--
                    out.append(c); i++
                }
            }
        }
    }

    private fun lineComment() {
        while (i < text.length && peek() != '\n') { out.append(' '); i++ }
    }

    private fun blockComment() {
        var depth = 0
        while (i < text.length) {
            val c = peek()
            when {
                c == '/' && peek(1) == '*' -> { depth++; out.append("  "); i += 2 }
                c == '*' && peek(1) == '/' -> { depth--; out.append("  "); i += 2; if (depth == 0) return }
                else -> { out.append(if (c == '\n') '\n' else ' '); i++ }
            }
        }
    }

    private fun string() {
        if (text.startsWith("\"\"\"", i)) { rawString(); return }
        out.append('"'); i++
        while (i < text.length) {
            val c = peek()
            when {
                c == '"' -> { out.append('"'); i++; return }
                c == '\n' -> return // 閉じ忘れ: 行末で打ち切る
                c == '\\' -> { out.append(text, i, minOf(i + 2, text.length)); i += 2 }
                c == '$' && peek(1) == '{' -> template()
                else -> { out.append(c); i++ }
            }
        }
    }

    private fun rawString() {
        out.append("\"\"\""); i += 3
        while (i < text.length) {
            val c = peek()
            when {
                c == '"' && text.startsWith("\"\"\"", i) -> {
                    // 閉じは最後の 3 つ。"""" のように続くときは手前の " を内容とみなす
                    var j = i
                    while (j < text.length && text[j] == '"') j++
                    out.append(text, i, j); i = j; return
                }
                c == '$' && peek(1) == '{' -> template()
                else -> { out.append(c); i++ }
            }
        }
    }

    /** 文字列テンプレートの `${ … }`。中はコードなので、文字列もコメントも入れ子で扱う。 */
    private fun template() {
        out.append("\${"); i += 2
        code(stopAtBrace = true)
        if (i < text.length) { out.append('}'); i++ }
    }

    private fun charLiteral() {
        out.append('\''); i++
        while (i < text.length && peek() != '\'' && peek() != '\n') {
            if (peek() == '\\') { out.append(text, i, minOf(i + 2, text.length)); i += 2 } else { out.append(peek()); i++ }
        }
        if (i < text.length && peek() == '\'') { out.append('\''); i++ }
    }
}
