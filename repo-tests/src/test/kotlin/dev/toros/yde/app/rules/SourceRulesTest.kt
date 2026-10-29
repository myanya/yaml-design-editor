package dev.toros.yde.app.rules

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ソースの規則（SourceRules）を全モジュールの main のソース（テストの書き方の規則は test のソース）に当てる。
 * 落ちたらメッセージの「説明」の見出しを読む。規則を足すときは SourceRules に 1 つと、ここにテストを 1 つ。
 */
class SourceRulesTest {
    private fun check(number: Int) {
        val rule = SourceRules.all.first { it.number == number }
        val hits = rule.check(if (rule.scope == Rule.Scope.TEST) SourceScanner.testSources else SourceScanner.mainSources)
        val knownKeys = rule.known.map { it.key }.toSet()
        val violations = hits.filter { it.key !in knownKeys }
        val stale = rule.known.filter { k -> hits.none { it.key == k.key } }
        val message = buildString {
            if (violations.isNotEmpty()) {
                appendLine("規則 ${rule.number}「${rule.name}」に違反しています:")
                violations.forEach { appendLine("  $it") }
                appendLine("理由: ${rule.why}")
                appendLine("説明: ${rule.doc}")
            }
            if (stale.isNotEmpty()) {
                appendLine("規則 ${rule.number}「${rule.name}」の既知の違反は解消しています。SourceRules の known から消してください:")
                stale.forEach { appendLine("  ${it.key}（${it.reason}）") }
            }
        }
        assertTrue(violations.isEmpty() && stale.isEmpty(), message)
    }

    // ================================================================ 走査そのもの

    @Test
    fun `全モジュールの main のソースを読める`() {
        val sources = SourceScanner.mainSources
        assertEquals(SourceScanner.modules.keys, sources.map { it.module }.toSet())
        assertTrue(sources.size > 200, "ソースが少なすぎる: ${sources.size}")
        val appState = sources.first { it.name == "AppState.kt" }
        assertEquals("dev.toros.yde.desktop", appState.packageName)
        assertEquals("desktop/src/main/kotlin/dev/toros/yde/desktop/AppState.kt", appState.path)
    }

    @Test
    fun `全モジュールの test のソースを読める`() {
        val sources = SourceScanner.testSources
        assertEquals(SourceScanner.testModules.keys, sources.map { it.module }.toSet())
        assertTrue(sources.any { it.path == "repo-tests/src/test/kotlin/dev/toros/yde/app/rules/SourceRulesTest.kt" })
    }

    @Test
    fun `package と import を行番号つきで読む`() {
        val text = """
            package a.b

            import x.y.Z
            import x.y.W as V // 別名
            import x.z.*
        """.trimIndent()
        val file = SourceScanner.parse("desktop", "a/b/F.kt", text)
        assertEquals("a.b", file.packageName)
        assertEquals(listOf("x.y.Z" to 3, "x.y.W" to 4, "x.z" to 5), file.imports.map { it.name to it.line })
        assertEquals(listOf(3, 4), file.importsStartingWith("x.y.").map { it.line })
    }

    @Test
    fun `コメントを空白に置き換えて行番号を保つ`() {
        val text = """
            package a
            // 行コメント JFileChooser
            import x.y.Z // 後ろのコメント
            /* ブロック /* 入れ子 */ まだコメント */ val a = 1
            val b = 2 /* 行の途中
            次の行まで */ val c = 3
        """.trimIndent()
        val code = SourceScanner.stripComments(text)
        assertEquals(text.lines().size, code.lines().size)
        assertFalse(code.contains("コメント"))
        assertFalse(code.contains("JFileChooser"))
        assertTrue(code.contains("import x.y.Z"))
        assertTrue(code.contains("val a = 1"))
        assertTrue(code.lines()[5].contains("val c = 3"))
    }

    @Test
    fun `文字列と文字リテラルの中の記号はコメントにしない`() {
        val q3 = "\"\"\""
        val text = "val s = \"http://x // 文字列の中\"\nval c = '\"'; val d = '\\''; // 本当のコメント\nval t = " + q3 + " 生 // そのまま " + q3 + " // 後ろ"
        val code = SourceScanner.stripComments(text)
        assertEquals(3, code.lines().size)
        assertTrue(code.lines()[0].contains("// 文字列の中"))
        assertTrue(code.lines()[1].contains("val d = '\\''"))
        assertFalse(code.contains("本当のコメント"))
        assertTrue(code.lines()[2].contains("生 // そのまま"))
        assertFalse(code.contains("// 後ろ"))
    }

    @Test
    fun `文字列テンプレートの中の文字列とコメントを扱える`() {
        val text = "val s = \"a \${foo(\"x // y\") /* 中のコメント */} b\" // 外のコメント\nval n = 1"
        val code = SourceScanner.stripComments(text)
        assertEquals(2, code.lines().size)
        assertTrue(code.lines()[0].contains("x // y"))
        assertFalse(code.contains("中のコメント"))
        assertFalse(code.contains("外のコメント"))
        assertTrue(code.lines()[0].endsWith("b\" " + " ".repeat("// 外のコメント".length)))
        assertEquals("val n = 1", code.lines()[1])
    }

    // ================================================================ 規則

    @Test fun `規則1 純粋ロジック層に Compose を持ち込まない`() = check(1)
    @Test fun `規則2 app の層は一方向`() = check(2)

    @Test
    fun `規則2 は * の import も見て、入口の Main_kt は除く`() {
        val dir = "desktop/src/main/kotlin/dev/toros/yde/desktop"
        val text = "package dev.toros.yde.desktop\nimport dev.toros.yde.desktop.ui.common.*\n"
        val state = SourceScanner.parse("desktop", "$dir/X.kt", text)
        val main = SourceScanner.parse("desktop", "$dir/Main.kt", text)
        val ui = SourceScanner.parse("desktop", "$dir/ui/common/Y.kt", "package dev.toros.yde.desktop.ui.common\n")
        val hits = SourceRules.all.first { it.number == 2 }.check(listOf(state, main, ui))
        assertEquals(listOf(state.path), hits.map { it.file.path })
    }
    @Test fun `規則3 yaml-core と semantic-core にファイルの場所を持ち込まない`() = check(3)
    @Test fun `規則4 等幅の文字は AppFonts Mono`() = check(4)
    @Test fun `規則5 外部の UI ライブラリは包む 1 ファイルの外へ出さない`() = check(5)
    @Test fun `規則6 AI の HTTP は Http kt に一元化`() = check(6)
    @Test fun `規則7 JFileChooser と Dispatchers Main を使わない`() = check(7)
    @Test fun `規則8 生の色は Palette だけ`() = check(8)
    @Test fun `規則9 UI は state index でなく overlayIndex を読む`() = check(9)
    @Test fun `規則10 FieldType は基底の種類だけ`() = check(10)
    @Test fun `規則11 機能側で Files exists を直接見ない`() = check(11)
    @Test fun `規則12 同じ裸の名前のファイルを複数作らない`() = check(12)
    @Test fun `規則13 テストに OS 固有の絶対パスを書かない`() = check(13)
    @Test fun `規則14 settings の全モジュールを規則テストが走査する`() = check(14)
    @Test fun `規則15 拡張の実装は入口の登録の一覧に載せる`() = check(15)
    @Test fun `規則16 拡張は app-core とカーネルだけを使う`() = check(16)
    @Test fun `規則17 拡張は決まった形にする`() = check(17)
    @Test fun `規則18 agent のファイルの間に循環を作らない`() = check(18)

    @Test
    fun `規則18 はファイルの相互の参照を循環として見つける`() {
        val a = SourceScanner.parse("agent", "agent/A.kt", "package p\nclass A { val b: B? = null }\n")
        val b = SourceScanner.parse("agent", "agent/B.kt", "package p\nclass B { val a: A? = null }\n")
        val c = SourceScanner.parse("agent", "agent/C.kt", "package p\nclass C { val a: A? = null }\n")
        val hits = ModuleRules.agentFileCycles(listOf(a, b, c))
        assertEquals(setOf("agent/A.kt", "agent/B.kt"), hits.map { it.file.path }.toSet())
        assertTrue(ModuleRules.agentFileCycles(listOf(a, c)).isEmpty(), "一方向なら循環ではない")
    }

    @Test
    fun `規則15 は入口の一覧に無い拡張の実装を見つける`() {
        val codec = SourceScanner.parse("format-x", "format-x/X.kt", "package dev.toros.yde.format.x\nobject XFormat : FormatCodec\nobject YFormat : FormatCodec\n")
        val desktop = SourceScanner.parse("desktop", "desktop/src/main/kotlin/dev/toros/yde/desktop/ext/DesktopExtensions.kt", "val l = listOf(XFormat, YFormat)\n")
        val cli = SourceScanner.parse("cli", "cli/src/main/kotlin/dev/toros/yde/cli/CliExtensions.kt", "val l = listOf(XFormat)\n")
        val hits = ModuleRules.unregisteredExtensions(listOf(codec, desktop, cli))
        assertEquals(1, hits.size, hits.toString())
        assertTrue("YFormat" in hits.single().text && "CliExtensions.kt" in hits.single().text, hits.toString())
    }

    @Test
    fun `規則の番号は 1 から順で名前が重ならない`() {
        assertEquals((1..SourceRules.all.size).toList(), SourceRules.all.map { it.number })
        assertEquals(SourceRules.all.size, SourceRules.all.map { it.name }.toSet().size)
    }

    // ================================================================ 目安（失敗にしない）

    /** 800 行を超える main のファイルを報告する。文書の字数と同じく目安で、repo-tests/build/reports/source-rules/large-files.txt にも書く（pre-commit が表示する）。 */
    @Test
    fun `大きな main のファイルを目安として報告する`() {
        val limit = SourceRules.LARGE_FILE_LINES
        val large = SourceScanner.mainSources.filter { it.lines.size > limit }.sortedByDescending { it.lines.size }
        val report = buildString {
            appendLine("目安（失敗にしない）: $limit 行を超える main のファイル ${large.size} 件")
            large.forEach { appendLine("LINES  ${it.path}: ${it.lines.size} 行") }
        }
        val out = SourceScanner.repoRoot.resolve("repo-tests/build/reports/source-rules/large-files.txt")
        Files.createDirectories(out.parent)
        Files.writeString(out, report)
        println(report)
    }
}
