package dev.toros.yde.cli

import dev.toros.yde.app.core.BuildInfo
import dev.toros.yde.yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 1 つの CLI の入口: 応用の命令（runCli）とカーネルの命令（run）の見分け、help・version、不明な命令の断り方。
 * 命令ごとの中身は root のテスト（WorkspaceCliTest・ActionCliTest・ViewsTest …）と cli の CliTest が見る。
 */
class AppCliTest {

    @Test
    fun `help は応用の命令とカーネルの命令の両方を載せる`() {
        val help = runCli(arrayOf("help"))
        assertTrue("catalog  <workspace>" in help && "agent    <workspace>" in help, help)
        assertTrue("workspace refactor <root>" in help && "yaml parse <file>" in help, help)
        assertEquals(help, runCli(arrayOf()))
    }

    @Test
    fun `version は 3 つの値を YAML で返す`() {
        val out = runCli(arrayOf("version"))
        assertTrue(out.startsWith("appVersion: ${BuildInfo.appVersion}\n"), out)
        assertTrue("buildVersion: ${BuildInfo.buildVersion}\n" in out, out)
        assertTrue("commit: ${BuildInfo.commit}\n" in out, out)
    }

    @Test
    fun `入口はカーネルの命令と応用の命令を見分ける`() {
        assertTrue(isKernelCommand(arrayOf("workspace", "validate", "x")))
        assertTrue(isKernelCommand(arrayOf("yaml", "parse", "a.yaml")))
        assertFalse(isKernelCommand(arrayOf("catalog", "x")))
        assertFalse(isKernelCommand(arrayOf()))
    }

    @Test
    fun `不明な命令は使い方を添えて断る`() {
        val e = assertFailsWith<IllegalStateException> { runCli(arrayOf("nope")) }
        assertTrue("不明なコマンドです: nope" in e.message.orEmpty() && "catalog" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `--format json で値を JSON にし text は yaml と同じ 文字の結果はそのまま`() {
        val yaml = runCli(arrayOf("catalog", "samples/todos"))
        assertEquals(yaml, runCli(arrayOf("catalog", "samples/todos", "--format", "text")))
        val json = runCli(arrayOf("catalog", "--format", "json", "samples/todos"))
        assertTrue(json.startsWith("[{\"file\":"), json)
        assertEquals(YamlIO.parseToRaw(yaml), YamlIO.parseToRaw(json), "JSON は YAML として読んで同じ値")
        val outline = runCli(arrayOf("outline", "samples/todos", "todos/todo-list.yaml"))
        assertEquals(outline, runCli(arrayOf("outline", "samples/todos", "todos/todo-list.yaml", "--format", "json")))
        val e = assertFailsWith<IllegalStateException> { runCli(arrayOf("catalog", "samples/todos", "--format", "xml")) }
        assertTrue("--format は" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `変更セットと注記は yaml では --- とコメント json では項目になる`() {
        val summary = linkedMapOf<String, Any?>("ok" to true)
        val data = asData(summary, note = "確認してください")
        assertEquals("ok: true\n# 確認してください\n", render(data, OutputFormat.YAML))
        assertEquals("{\"ok\":true,\"note\":\"確認してください\"}\n", render(data, OutputFormat.JSON))
        assertEquals("骨子", render(asText("骨子"), OutputFormat.JSON))
    }
}
