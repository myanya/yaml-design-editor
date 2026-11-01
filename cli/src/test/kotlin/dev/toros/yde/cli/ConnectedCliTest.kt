package dev.toros.yde.cli

import dev.toros.yde.server.client.*
import kotlin.test.*

class ConnectedCliTest {
    private class FakeConnection(val response: Map<String, Any?> = mapOf("data" to mapOf("state" to "working"))) : LocalApiConnection {
        val requests = mutableListOf<LocalApiRequest>()
        var closed = false
        override suspend fun execute(request: LocalApiRequest): Map<String, Any?> { requests += request; return response }
        override fun close() { closed = true }
    }

    @Test fun `ヘルプと不正な命令は資格情報も接続も作業ファイルも読まない`() {
        fun run(args: List<String>) = runConnectedCli(args, { error("環境変数を読んだ") },
            { _, _ -> error("接続した") }, { error("ファイルを読んだ") })
        assertContains(runCli(arrayOf("connect", "help")), "YDE_API_TOKEN")
        assertContains(run(emptyList()), "direct")
        for (args in listOf(
            listOf("apply", "workspace", "change.yaml"), listOf("workspace", "--token", "秘密"),
            listOf("work", "begin", "--title", "題"), listOf("work", "commit", "id"),
            listOf("work", "edit", "id", "missing.yaml", "--apply", "true"),
            listOf("operation", "commit", "id"), listOf("operation", "plan", "name", "--param", "x=1", "--param", "x=2"),
            listOf("documents", "--port", "0"), listOf("documents", "--offset", "-1"),
            listOf("documents", "--format", "json", "--format", "yaml"),
            listOf("workspace", "--token-env", "BAD=NAME"), listOf("work", "result", "../workspace"),
        )) {
            val failure = assertFails { run(args) }
            assertFalse(failure.message.orEmpty().contains("秘密"))
            assertFalse(failure.message.orEmpty() in setOf("環境変数を読んだ", "接続した", "ファイルを読んだ"), args.toString())
        }
    }

    @Test fun `認証情報は環境変数だけから取得し応答と保存版を変えずに出力する`() {
        val fake = FakeConnection(mapOf("workspaceId" to "ws", "epoch" to "世代", "revision" to "版",
            "data" to mapOf("items" to listOf("日本語"), "nextOffset" to 2)))
        val output = runConnectedCli(listOf("--port", "12345", "--token-env", "MY_TOKEN", "documents", "--limit", "2", "--format", "json"),
            { name -> assertEquals("MY_TOKEN", name); "secret" }, { port, token ->
                assertEquals(12345, port); assertEquals("secret", token); fake
            })
        assertContains(output, "\"revision\":\"版\"")
        assertContains(output, "日本語")
        assertFalse("secret" in output)
        assertEquals(mapOf("limit" to "2"), fake.requests.single().query)
        assertTrue(fake.closed)
    }

    @Test fun `YAML の値は計算せず変更セットと操作引数として送る`() {
        val fake = FakeConnection()
        runConnectedCli(listOf("work", "edit", "id", "-"), { "token" }, { _, _ -> fake }, {
            assertEquals("-", it)
            "changes: [{file: a.yaml, ops: [{op: set, path: title, value: 日本語}]}]"
        })
        assertEquals(listOf("works", "id", "changes"), fake.requests.single().path)
        assertTrue(fake.requests.single().body?.get("changes") is List<*>)
        val request = connectedCommand(listOf("operation", "plan", "計算", "--param", "数=2", "--param", "条件=true", "--target", "台帳/a.yaml")).request
        assertEquals(mapOf("数" to 2, "条件" to true), request.body?.get("params"))
        assertEquals("台帳/a.yaml", request.body?.get("target"))
    }

    @Test fun `通信断の後は自動再送せず接続を閉じる`() {
        var attempts = 0
        var closed = false
        val fake = object : LocalApiConnection {
            override suspend fun execute(request: LocalApiRequest): Map<String, Any?> { attempts++; throw LocalApiFailure(null, "通信断") }
            override fun close() { closed = true }
        }
        assertFailsWith<LocalApiFailure> {
            runConnectedCli(listOf("operation", "commit", "plan", "--request-id", "one"), { "token" }, { _, _ -> fake })
        }
        assertEquals(1, attempts)
        assertTrue(closed)
    }

    @Test fun `検証と保存の失敗は結果を残して非ゼロで返す`() {
        val cases = listOf(
            mapOf("valid" to false) to 2, mapOf("canCommit" to false) to 2,
            mapOf("state" to "rejected") to 2, mapOf("state" to "partial") to 5,
            mapOf("state" to "unknown") to 5, mapOf("state" to "reconciled") to 5,
            mapOf("state" to "applying") to 5, mapOf("state" to "failed") to 5,
            mapOf("items" to listOf(mapOf("issues" to listOf(mapOf("severity" to "error"))))) to 2,
        )
        for ((data, code) in cases) {
            val failure = assertFailsWith<ConnectedResultFailure> {
                runConnectedCli(listOf("work", "result", "id", "--format", "json"), { "token" }, { _, _ -> FakeConnection(mapOf("data" to data)) })
            }
            assertEquals(code, failure.exitCode)
            assertContains(failure.output, "\"data\"")
        }
        for (state in listOf("awaiting_approval", "applied", "committed", "discarded")) {
            assertEquals(0, connectedExitCode(mapOf("data" to mapOf("state" to state))))
        }
        assertEquals(0, connectedExitCode(mapOf("data" to mapOf("items" to listOf(mapOf("issues" to listOf(mapOf("severity" to "warning"))))))))
    }
}
