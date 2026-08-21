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
    fun `catalogとentityでワークスペースを検分できる`() {
        val (code, output) = capture("workspace", "catalog", fixture("workspaces", "rename-target"))
        assertEquals(0, code, output)
        assertTrue("fx-req" in output && "reqs/one.yaml" in output, output)
        val (entityCode, entityOut) = capture("workspace", "entity", fixture("workspaces", "rename-target"), "REQ-001")
        assertEquals(0, entityCode, entityOut)
        assertTrue("出現 1 件" in entityOut && "被参照 2 件" in entityOut, entityOut)
        // 見つからないIDは exit 1
        assertEquals(1, capture("workspace", "entity", fixture("workspaces", "rename-target"), "NOPE").first)
    }

    @Test
    fun `formatオプションでyamlとjsonの機械可読出力が選べる`() {
        val (code, yaml) = capture("workspace", "validate", fixture("workspaces", "legacy-basic"), "--format", "yaml")
        assertEquals(3, code, yaml)
        assertTrue("semanticErrors: 3" in yaml, yaml)
        val (jsonCode, json) = capture("workspace", "catalog", fixture("workspaces", "rename-target"), "--format", "json")
        assertEquals(0, jsonCode, json)
        assertTrue(json.trim().startsWith("{") && "\"schemas\":" in json, json)
        // refactor のプレビューも機械可読（workspace は変化しない）
        val (planCode, planJson) = capture(
            "workspace", "refactor", fixture("workspaces", "rename-target"),
            "rename-entity", "REQ-001", "REQ-100", "--format", "json",
        )
        assertEquals(0, planCode, planJson)
        assertTrue("\"applied\":false" in planJson, planJson)
    }

    @Test
    fun `refactorコマンドはスキーマ操作を適用できる`() {
        val temp = kotlin.io.path.createTempDirectory("refactor-apply")
        try {
            Path.of(fixture("workspaces", "rename-target")).toFile().copyRecursively(temp.toFile())
            val (code, output) = capture(
                "workspace", "refactor", temp.toString(), "rename-field", "fx-req", "title", "name", "--apply",
            )
            assertEquals(0, code, output)
            assertTrue("保存しました" in output, output)
            assertTrue("name: 基本要求" in temp.resolve("reqs/one.yaml").toFile().readText())
            assertTrue("key: name" in temp.resolve("_schemas/req.yaml").toFile().readText())
            assertEquals(0, capture("workspace", "validate", temp.toString()).first, "適用後も検証が通る")
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `shellでset-undo-redo-save-reloadとrefactorが確認できる`() {
        val temp = kotlin.io.path.createTempDirectory("shell-test")
        try {
            java.nio.file.Files.createDirectories(temp.resolve("_schemas"))
            java.nio.file.Files.createDirectories(temp.resolve("tasks"))
            java.nio.file.Files.writeString(
                temp.resolve("_schemas").resolve("task.yaml"),
                """
                    id: task-schema
                    match: "tasks/**/*.yaml"
                    root:
                      type: object
                      fields:
                        - key: id
                          type: id
                        - key: title
                          type: string
                """.trimIndent(),
            )
            java.nio.file.Files.writeString(temp.resolve("tasks").resolve("a.yaml"), "id: T-1\ntitle: 古い\n")
            val script = ArrayDeque(
                listOf(
                    "set tasks/a.yaml title 新しい",
                    "status",
                    "undo",
                    "redo",
                    "save",
                    "refactor rename-entity T-1 T-2 --apply",
                    "undo",
                    "save",
                    "reload",
                    "validate",
                    "exit",
                )
            )
            val sb = StringBuilder()
            val code = workspaceShell(temp.toString(), { script.removeFirstOrNull() }, { sb.appendLine(it) })
            val output = sb.toString()
            assertEquals(0, code, output)
            assertTrue("OK（version 1）" in output, "set が適用される: $output")
            assertTrue("dirty: tasks/a.yaml" in output, output)
            assertTrue("戻しました" in output && "やり直しました" in output, output)
            assertTrue("保存しました（書込 1 件・削除 0 件）" in output, output)
            assertTrue("ID「T-1」→「T-2」" in output && "適用しました" in output, output)
            assertTrue("読み直しました" in output, output)
            assertTrue("構造検証: エラー 0 件 / 意味検証: エラー 0 件" in output, output)
            val saved = temp.resolve("tasks").resolve("a.yaml").toFile().readText()
            assertTrue("title: 新しい" in saved && "id: T-1" in saved, "set は保存済み・refactor は undo 済み: $saved")
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `benchコマンドが全再構築と増分更新を計測する`() {
        val (code, output) = capture("workspace", "bench", fixture("workspaces", "legacy-basic"), "--iterations", "1")
        assertEquals(0, code, output)
        assertTrue("全再構築" in output && "増分更新" in output && "speedup" in output, output)
        val (jsonCode, json) = capture(
            "workspace", "bench", fixture("workspaces", "legacy-basic"), "--iterations", "1", "--format", "json",
        )
        assertEquals(0, jsonCode, json)
        assertTrue("\"fullBuildMillis\"" in json && "\"incrementalUpdateMillis\"" in json, json)
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
