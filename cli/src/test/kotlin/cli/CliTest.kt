package cli

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 簡易CLI（WS-003）の契約テスト。dispatcher と exit code（0 / 1 / 2 / 3）を
 * 標準出力の文字列だけでなく戻り値で確認する（計画書 Phase 10 の CLI テスト方針）。
 */
class CliTest {

    private fun fixture(vararg segments: String): String {
        val root = System.getProperty("fixtures.root")
            ?: error("システムプロパティ fixtures.root が未設定（cli/build.gradle.kts が渡す）")
        return Path.of(root, *segments).toString()
    }

    private fun capture(vararg args: String): Pair<Int, String> {
        val sb = StringBuilder()
        val code = run(args.toList().toTypedArray()) { sb.appendLine(it) }
        return code to sb.toString()
    }

    @Test
    fun `意味検証エラーのあるワークスペースはexit 3になる`() {
        val (code, output) = capture("workspace", "validate", fixture("workspaces", "legacy-basic"))
        assertEquals(3, code, output)
        assertTrue("IT-404" in output, output)
        assertTrue("PT-001" in output, output)
        assertTrue("意味検証: エラー 3 件" in output, output)
        assertTrue("構造検証: エラー 0 件" in output, output)
    }

    @Test
    fun `問題のないワークスペースはexit 0になる`() {
        val (code, output) = capture("workspace", "validate", fixture("workspaces", "yde-basic"))
        assertEquals(0, code, output)
        assertTrue("台帳内 2 件" in output, output)
    }

    @Test
    fun `yaml roundtripとparseがfixtureで通る`() {
        assertEquals(0, capture("yaml", "roundtrip", fixture("yaml", "comments.yaml")).first)
        assertEquals(0, capture("yaml", "roundtrip", fixture("yaml", "multidoc.yaml")).first)
        val (code, output) = capture("yaml", "parse", fixture("yaml", "multidoc.yaml"))
        assertEquals(0, code, output)
        assertTrue("3 ドキュメント" in output, output)
    }

    @Test
    fun `未知のコマンド・存在しない入力はexit 1になる`() {
        val (unknown, usage) = capture("unknown")
        assertEquals(1, unknown)
        assertTrue("使い方" in usage)
        assertEquals(1, capture("yaml", "parse", "missing-file.yaml").first)
        assertEquals(1, capture("workspace", "validate", "missing-dir").first)
    }
}
