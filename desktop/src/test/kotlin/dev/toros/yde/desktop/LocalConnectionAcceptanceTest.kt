package dev.toros.yde.desktop

import dev.toros.yde.app.service.*
import dev.toros.yde.desktop.lifecycle.*
import dev.toros.yde.server.*
import dev.toros.yde.server.auth.LocalClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** 製品の Runtime・所有スレッド・三つの通信入口を往復する。窓と利用者の設定は開かない。 */
class LocalConnectionAcceptanceTest {
    private class Fixture {
        val root = Files.createTempDirectory("yde-local-acceptance")
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val url = "http://127.0.0.1:$port"
        val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        var runtime = DesktopRuntime(monitorFactory = { null })
        val permissions = ConnectionPermissions(readScope = ConnectionReadScope.WORKSPACE,
            proposalScope = ConnectionWriteScope.LEDGER, commitScope = ConnectionWriteScope.LEDGER,
            actionScope = ConnectionWriteScope.LEDGER, actions = setOf("rename"))
        lateinit var api: LocalClient
        lateinit var mcp: LocalClient
        lateinit var web: LocalClient
        var bearer = ""
        var mcpBearer = ""
        var mcpSession = ""
        var cookie = ""
        var csrf = ""
        var rpcId = 0
        suspend fun start() {
            Files.writeString(root.resolve("a.yaml"), "title: original\n")
            Files.writeString(root.resolve("b.yaml"), "title: other\n")
            Files.createDirectories(root.resolve("_schemas"))
            Files.writeString(root.resolve("_schemas/item.yaml"), """
                id: item
                match: '*.yaml'
                root:
                  type: object
                  fields:
                    - {key: title, type: string, required: true}
                actions:
                  - id: rename
                    target: {schema: item}
                    input:
                      initial:
                        title: {${'$'}copy: title, ${'$'}of: self}
                    params:
                      - {key: title, type: string, required: true}
                    do:
                      - set: {path: title, value: ${'$'}title}
            """.trimIndent())
            assertTrue(runtime.openWorkspace(root))
            api = runtime.access.register(LocalFeature.API, "API", permissions)
            mcp = runtime.access.register(LocalFeature.MCP, "MCP", permissions)
            web = runtime.access.register(LocalFeature.WEB, "Web", permissions)
        }
        suspend fun configure(bits: Int) {
            val settings = LocalConnectionSettings(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0, port)
            val status = withTimeout(15_000) { runtime.connections.awaitSettled(runtime.connections.configure(settings)) }
            assertNull(status.error)
            assertEquals(if (bits == 0) null else port, status.boundPort)
        }
        suspend fun request(path: String, body: Any? = null, headers: Map<String, String> = emptyMap()): HttpResponse<String> = withContext(Dispatchers.IO) {
            val builder = HttpRequest.newBuilder(URI(url + path)).timeout(Duration.ofSeconds(15))
            headers.forEach { (key, value) -> builder.header(key, value) }
            if (body == null) builder.GET() else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json(body).toString()))
            http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }
        suspend fun api(path: String, body: Any? = null) = request("/api/v1/$path", body, mapOf("Authorization" to "Bearer $bearer"))
        suspend fun web(path: String, body: Any? = null) = request("/web-api/$path", body, mapOf("Cookie" to cookie, "Origin" to url, "X-YDE-CSRF" to csrf))
        suspend fun loginWeb() {
            val code = runtime.access.webLogins.issue(web.id)
            val response = request("/web-api/session/login", mapOf("code" to code.value), mapOf("Origin" to url, "X-YDE-Login" to "1"))
            assertEquals(200, response.statusCode(), response.body())
            cookie = response.headers().firstValue("set-cookie").orElseThrow().substringBefore(';')
            csrf = response.json().text("csrfToken")
        }
        suspend fun loginMcp() {
            mcpBearer = runtime.access.issueBearer(mcp.id).value
            mcpSession = ""
            val response = rpc("initialize", mapOf("protocolVersion" to "2025-11-25", "capabilities" to emptyMap<String, Any>(),
                "clientInfo" to mapOf("name" to "受入試験", "version" to "1")))
            assertEquals(200, response.statusCode(), response.body())
            mcpSession = response.headers().firstValue("Mcp-Session-Id").orElseThrow()
            assertEquals(202, rpc("notifications/initialized", notification = true).statusCode())
        }
        suspend fun rpc(method: String, params: Any? = null, notification: Boolean = false): HttpResponse<String> {
            val body = mutableMapOf<String, Any?>("jsonrpc" to "2.0", "method" to method)
            if (!notification) body["id"] = ++rpcId
            if (params != null) body["params"] = params
            val headers = mutableMapOf("Authorization" to "Bearer $mcpBearer", "Accept" to "application/json, text/event-stream", "MCP-Protocol-Version" to "2025-11-25")
            if (mcpSession.isNotEmpty()) headers["Mcp-Session-Id"] = mcpSession
            return request("/mcp", body, headers)
        }
        suspend fun tool(name: String, args: Map<String, Any?>, error: Boolean = false): JsonObject {
            val response = rpc("tools/call", mapOf("name" to name, "arguments" to args))
            assertEquals(200, response.statusCode(), response.body())
            val result = response.json().getValue("result").jsonObject
            assertEquals(error, result["isError"]?.jsonPrimitive?.boolean ?: false, result.toString())
            return result.getValue("structuredContent").jsonObject.getValue("data").jsonObject
        }
        suspend fun planApi(title: String) = api("operations/plan", mapOf("name" to "rename", "target" to "a.yaml", "params" to mapOf("title" to title))).data().text("planId")
        suspend fun planWeb(title: String): String {
            val revision = web("workspace").json().text("revision")
            val input = web("operations/prepare", mapOf("name" to "rename", "target" to "a.yaml", "revision" to revision)).data()
            return web("operations/plan", mapOf("inputId" to input.text("inputId"), "params" to mapOf("title" to title))).data().text("planId")
        }
        suspend fun restart() {
            val registrations = runtime.access.registrations()
            runtime.shutdown()
            assertPortFree()
            runtime = DesktopRuntime(monitorFactory = { null })
            runtime.access.restoreRegistrations(registrations)
            assertTrue(runtime.openWorkspace(root))
        }
        fun assertPortFree() {
            assertFails { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) } }
            ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress("127.0.0.1", port)) }
        }
        suspend fun close() { try { runtime.shutdown() } finally { http.close(); root.toFile().deleteRecursively() } }
    }

    private fun runCase(block: suspend Fixture.() -> Unit): Unit = runBlocking {
        withContext(DesktopUiDispatcher) {
            val fixture = Fixture()
            try { withTimeout(90_000) { fixture.start(); fixture.block() } }
            finally { withContext(NonCancellable) { fixture.close() } }
        }
    }

    @Test fun `全八組合せで独立した経路を使い再起動と実行中切替で最後の待受を解放する`() = runCase {
        suspend fun check(bits: Int) {
            configure(bits)
            if (bits == 0) { assertPortFree(); return }
            assertEquals(if (bits and 1 != 0) 401 else 404, request("/api/v1/workspace").statusCode())
            assertEquals(if (bits and 2 != 0) 401 else 404, request("/mcp", emptyMap<String, Any>()).statusCode())
            assertEquals(if (bits and 4 != 0) 200 else 404, request("/app/").statusCode())
            assertEquals(if (bits and 4 != 0) 401 else 404, request("/web-api/workspace").statusCode())
            if (bits and 1 != 0) { bearer = runtime.access.issueBearer(api.id).value; assertEquals(200, api("workspace").statusCode()) }
            if (bits and 2 != 0) { loginMcp(); assertEquals(200, rpc("ping").statusCode()) }
            if (bits and 4 != 0) { loginWeb(); assertEquals(200, web("workspace").statusCode()) }
        }
        for (bits in 0..7) { restart(); check(bits) }
        // 最後の組合せから、一つずつ OFF/ON して途中の機能と keep-alive の接続を維持する。
        for (bits in listOf(5, 4, 6, 2, 3, 1, 0)) check(bits)
    }

    @Test fun `画面非表示で三入口の同じ変更を競合させ無関係な編集と履歴を保持する`() = runCase {
        configure(7)
        bearer = runtime.access.issueBearer(api.id).value; loginMcp(); loginWeb()
        val docs = runtime.host.documents
        val a = docs.ensureDocument(root.resolve("a.yaml"))
        a.applySourceText("title: desktop\n")
        assertTrue(docs.save(a).ok)
        val b = docs.ensureDocument(root.resolve("b.yaml"))
        b.applySourceText("title: 未保存の別文書\n")
        val history = docs.kernel!!.historySize
        val webPlan = planWeb("Web の変更")
        val mcpPlan = tool("plan_action", mapOf("name" to "rename", "target" to "a.yaml", "params" to mapOf("title" to "MCP の変更"))).text("planId")
        val apiPlan = planApi("API の変更")
        val presence = DesktopPresence({ object : DesktopTray {
            override fun update(status: DesktopTrayStatus) {}
            override fun close() {}
        } }, {})
        try {
            presence.start(ResidentSettings(true), StartupVisibility.DEFAULT)
            assertTrue(presence.hide())
            val commit = mapOf("planId" to apiPlan, "requestId" to "api-first")
            val result = api("operations/commit", commit).data()
            assertEquals("committed", result.text("state"))
            assertEquals(result, api("operations/commit", commit).data())
            assertEquals("rejected", web("operations/commit", mapOf("planId" to webPlan, "requestId" to "web-stale")).data().text("state"))
            assertEquals("rejected", tool("commit_action", mapOf("planId" to mcpPlan, "requestId" to "mcp-stale"), error = true).text("state"))
            assertContains(a.canonicalText(), "API の変更")
            assertContains(b.canonicalText(), "未保存の別文書")
            assertTrue(b.dirty)
            assertEquals(history, docs.kernel!!.historySize)
            runtime.state.undo()
            assertContains(b.canonicalText(), "other")
            assertFalse(docs.kernel!!.checkHistory(true).ok, "外部変更と競合する古い一手で停止する")
            runtime.state.redo()
            assertContains(b.canonicalText(), "未保存の別文書")
            val dirtyPlan = planWeb("上書きしない")
            a.noteSourceInput("title: [")
            assertEquals("rejected", web("operations/commit", mapOf("planId" to dirtyPlan, "requestId" to "dirty")).data().text("state"))
            assertEquals("title: [", a.draft)
            assertContains(Files.readString(root.resolve("a.yaml")), "API の変更")
            presence.show()
            assertTrue(presence.status.value.visible)
            assertFalse(presence.requestExit(runtime.state.hasDirtyDocs()))
            presence.cancelExit()
            assertEquals(200, api("workspace").statusCode(), "終了中断では通信を止めない")
        } finally { presence.close() }
    }

    @Test fun `三入口が同じ保存版を同時に確定しても一つだけ保存する`() = runCase {
        configure(7)
        bearer = runtime.access.issueBearer(api.id).value; loginMcp(); loginWeb()
        val apiPlan = planApi("from-api")
        val webPlan = planWeb("from-web")
        val mcpPlan = tool("plan_action", mapOf("name" to "rename", "target" to "a.yaml", "params" to mapOf("title" to "from-mcp"))).text("planId")
        fun outcome(response: HttpResponse<String>, mcp: Boolean = false): String {
            assertTrue(response.statusCode() in if (mcp) setOf(200) else setOf(200, 409), response.body())
            val body = if (mcp) response.json().getValue("result").jsonObject.getValue("structuredContent").jsonObject else response.json()
            body["data"]?.let {
                return it.jsonObject.text("state").also { state -> assertTrue(state in setOf("committed", "rejected"), body.toString()) }
            }
            assertTrue(body.getValue("error").jsonObject.text("code") in setOf("work_conflict", "operation_conflict"), body.toString())
            return "blocked"
        }
        val results = coroutineScope {
            val start = CompletableDeferred<Unit>()
            val calls = listOf(
                async { start.await(); outcome(api("operations/commit", mapOf("planId" to apiPlan, "requestId" to "parallel-api"))) },
                async { start.await(); outcome(web("operations/commit", mapOf("planId" to webPlan, "requestId" to "parallel-web"))) },
                async { start.await(); outcome(rpc("tools/call", mapOf("name" to "commit_action", "arguments" to mapOf("planId" to mcpPlan, "requestId" to "parallel-mcp"))), mcp = true) },
            )
            start.complete(Unit)
            calls.awaitAll()
        }
        assertEquals(1, results.count { it == "committed" }, results.toString())
        val winner = listOf("from-api", "from-web", "from-mcp")[results.indexOf("committed")]
        assertContains(Files.readString(root.resolve("a.yaml")), winner)
        assertContains(runtime.host.documents.ensureDocument(root.resolve("a.yaml")).canonicalText(), winner)
        assertEquals(0, runtime.host.documents.kernel!!.historySize)
    }

    @Test fun `MCPの提出を再起動後も受信箱から承認し切替後に旧資格情報で別の場所へ触れない`() = runCase {
        configure(2); loginMcp()
        val workId = tool("begin_work", mapOf("title" to "提案の引継ぎ", "mode" to "proposal")).text("id")
        tool("edit_work", mapOf("workId" to workId, "changes" to listOf(mapOf("file" to "a.yaml", "ops" to listOf(mapOf("op" to "set", "path" to "title", "value" to "承認後の内容"))))))
        assertEquals("awaiting_approval", tool("submit_work", mapOf("workId" to workId)).text("state"))
        assertContains(Files.readString(root.resolve("a.yaml")), "original")
        restart(); configure(2)
        assertEquals(401, rpc("ping").statusCode(), "再起動前のトークンを復元しない")
        loginMcp()
        assertEquals("awaiting_approval", tool("work_result", mapOf("workId" to workId)).text("state"))
        assertEquals(workId, runtime.work.refresh().single().id)
        runtime.state.openInbox()
        assertEquals(UiSpace.RUNS, runtime.state.uiSpace)
        val review = runtime.work.review(workId)
        assertEquals(WorkState.APPLIED, runtime.work.approve(workId, review.id).state)
        assertEquals("applied", tool("work_result", mapOf("workId" to workId)).text("state"))
        val other = Files.createDirectory(root.resolve("another-workspace"))
        Files.writeString(other.resolve("a.yaml"), "title: 別の場所\n")
        assertTrue(runtime.openWorkspace(other))
        assertEquals(401, rpc("ping").statusCode())
        assertFailsWith<IllegalArgumentException> { runtime.access.issueBearer(mcp.id) }
        assertContains(Files.readString(other.resolve("a.yaml")), "別の場所")
    }

    companion object {
        private fun json(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to json(it.value) })
            is List<*> -> JsonArray(value.map(::json))
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
        private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
        private fun HttpResponse<String>.json() = Json.parseToJsonElement(body()).jsonObject
        private fun HttpResponse<String>.data(): JsonObject {
            assertEquals(200, statusCode(), body())
            return json().getValue("data").jsonObject
        }
    }
}
