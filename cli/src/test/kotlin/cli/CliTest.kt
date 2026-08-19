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
    fun `rename-entityはプレビューを表示しapplyで保存まで行う`() {
        // プレビューは fixture を変更しない（読み取り専用）
        val (previewCode, preview) = capture("workspace", "rename-entity", fixture("workspaces", "rename-target"), "REQ-001", "REQ-100")
        assertEquals(0, previewCode, preview)
        assertTrue("参照 2 件" in preview, preview)
        assertTrue("プレビューのみ" in preview, preview)
        // 適用は一時コピーの上で行う（fixture は読み取り専用。CONVENTIONS §8）
        val temp = kotlin.io.path.createTempDirectory("rename-apply")
        try {
            Path.of(fixture("workspaces", "rename-target")).toFile().copyRecursively(temp.toFile())
            val (applyCode, applied) = capture("workspace", "rename-entity", temp.toString(), "REQ-001", "REQ-100", "--apply")
            assertEquals(0, applyCode, applied)
            assertTrue("保存しました" in applied, applied)
            assertTrue("REQ-100" in temp.resolve("reqs/one.yaml").toFile().readText())
            assertEquals(0, capture("workspace", "validate", temp.toString()).first, "適用後に参照切れがない")
        } finally {
            temp.toFile().deleteRecursively()
        }
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
