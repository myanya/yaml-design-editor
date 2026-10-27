package dev.toros.yde.cli

import dev.toros.yde.app.core.BuildInfo
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
}
