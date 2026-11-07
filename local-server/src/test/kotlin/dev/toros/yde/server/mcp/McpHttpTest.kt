package dev.toros.yde.server.mcp

import dev.toros.yde.app.service.OperationState
import dev.toros.yde.server.*
import dev.toros.yde.server.protocol.externalJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class McpHttpTest {
    @Test fun `公式SDKの実通信で通信セッションを終了しても作業と提出と確定結果を引き継ぐ`() = withMcpHttp { f ->
        val token = f.access.issueBearer(f.register().id)
        val first = f.peer(token)
        assertEquals(17, first.client.listTools().tools.size)
        val proposal = first.data("begin_work", mapOf("title" to "提案", "mode" to "proposal")).text("id")
        first.data("edit_work", mapOf("workId" to proposal, "changes" to changes("提案内容")))
        first.data("validate_work", mapOf("workId" to proposal))
        assertEquals("awaiting_approval", first.data("submit_work", mapOf("workId" to proposal)).text("state"))
        val direct = first.data("begin_work", mapOf("title" to "直接確定", "mode" to "direct")).text("id")
        first.data("edit_work", mapOf("workId" to direct, "changes" to changes("確定内容")))
        val oldSession = assertNotNull(first.transport.sessionId)
        first.transport.terminateSession()
        first.close()
        assertEquals(HttpStatusCode.NotFound, f.post(token, oldSession, ping).status)

        val next = f.peer(token)
        assertNotEquals(oldSession, next.transport.sessionId)
        assertEquals("awaiting_approval", next.data("work_result", mapOf("workId" to proposal)).text("state"))
        next.data("validate_work", mapOf("workId" to direct))
        val plan = next.data("plan_work", mapOf("workId" to direct)).text("planId")
        val result = next.data("commit_work", mapOf("workId" to direct, "planId" to plan))
        next.transport.terminateSession(); next.close()
        val last = f.peer(token)
        assertEquals(result, last.data("commit_work", mapOf("workId" to direct, "planId" to plan)))
        assertEquals(result, last.data("work_result", mapOf("workId" to direct)))
        assertTrue(Files.readString(f.root.resolve("a.yaml")).contains("確定内容"))
    }

    @Test fun `SDKの取消通知が実行中の読取を終了し同じセッションで後続を実行できる`() {
        val armed = AtomicBoolean(true)
        val entered = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Unit>()
        withMcpHttp(afterCapture = {
            if (armed.getAndSet(false)) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { ended.complete(Unit) }
            }
        }) { f ->
            val peer = f.peer(f.access.issueBearer(f.register().id))
            val call = launch { peer.call("list_documents") }
            entered.await()
            call.cancelAndJoin()
            withTimeout(5_000) { ended.await() }
            assertFalse(peer.call("list_documents").isError == true)
        }
    }

    @Test fun `無操作のセッションが期限切れになり認証の枠を返して再初期化できる`() = withMcpHttp(400.milliseconds) { f ->
        val token = f.access.issueBearer(f.register().id)
        val old = f.initialize(token)
        // 有効な ping は無操作の時計を戻すため、照会せず期限を越えてから確認する。
        delay(1_000)
        assertEquals(HttpStatusCode.NotFound, f.post(token, old, ping).status)
        repeat(10) {
            val session = f.initialize(token)
            assertNotEquals(old, session)
            assertEquals(HttpStatusCode.OK, f.terminate(token, session).status)
        }
    }

    @Test fun `資格情報の期限切れが実行中のSDK処理を終了し再発行後は新しい通信セッションだけ使える`() {
        val armed = AtomicBoolean(true)
        val entered = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Unit>()
        withMcpHttp(afterCapture = {
            if (armed.getAndSet(false)) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { ended.complete(Unit) }
            }
        }) { f ->
            val registration = f.register()
            val token = f.access.issueBearer(registration.id, Duration.ofSeconds(3))
            val peer = f.peer(token)
            val old = assertNotNull(peer.transport.sessionId)
            val call = launch { runCatching { peer.call("list_documents") } }
            entered.await()
            withTimeout(6_000) { ended.await() }
            assertEquals(HttpStatusCode.Unauthorized, f.post(token, old, ping).status)
            call.cancelAndJoin()
            val fresh = f.access.issueBearer(registration.id)
            assertEquals(HttpStatusCode.NotFound, f.post(fresh, old, ping).status)
            assertFalse(f.peer(fresh).call("list_documents").isError == true)
        }
    }

    @Test fun `MCP停止はストリームを終了しAPIを維持して最後のOFFでポートを解放する`() = withMcpHttp { f ->
        f.configure(LocalConnectionSettings(apiEnabled = true, mcpEnabled = true))
        val registration = f.register()
        val token = f.access.issueBearer(registration.id)
        val peer = f.peer(token)
        val session = assertNotNull(peer.transport.sessionId)
        val api = f.access.issueBearer(f.access.register(LocalFeature.API, "API試験", f.permissions).id)
        val port = assertNotNull(f.connections.status.value.boundPort)
        f.configure(LocalConnectionSettings(apiEnabled = true))
        assertEquals(HttpStatusCode.NotFound, f.post(token, session, ping).status)
        assertEquals(HttpStatusCode.OK, f.http.get("http://127.0.0.1:$port/api/v1/workspace") { bearerAuth(api.value) }.status)
        f.configure(LocalConnectionSettings(apiEnabled = true, mcpEnabled = true))
        assertEquals(HttpStatusCode.Unauthorized, f.post(token, session, ping).status)
        val fresh = f.access.issueBearer(registration.id)
        assertEquals(HttpStatusCode.NotFound, f.post(fresh, session, ping).status)
        assertFalse(f.peer(fresh).call("list_documents").isError == true)
        f.configure(LocalConnectionSettings())
        assertNull(f.connections.status.value.boundPort)
        ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress("127.0.0.1", port)) }
    }

    @Test fun `認証前の初期化や別接続者のセッションと不正なOriginや版を実通信でも拒否する`() = withMcpHttp { f ->
        assertEquals(HttpStatusCode.Unauthorized, f.http.post(f.url).status)
        val token = f.access.issueBearer(f.register().id)
        val session = f.initialize(token)
        val other = f.access.issueBearer(f.register().id)
        assertEquals(HttpStatusCode.NotFound, f.post(other, session, ping).status)
        assertEquals(HttpStatusCode.Forbidden, f.post(token, session, ping, origin = "http://localhost:6274").status)
        assertEquals(HttpStatusCode.BadRequest, f.post(token, session, ping, version = "1900-01-01").status)
        assertEquals(HttpStatusCode.OK, f.post(token, session, ping).status)
    }

    @Test fun `初期化の上限拒否を繰り返してもセッションと同時要求の枠が漏れない`() = withMcpHttp { f ->
        val token = f.access.issueBearer(f.register().id)
        repeat(3) {
            val sessions = List(8) { f.initialize(token) }
            repeat(12) { assertEquals(HttpStatusCode.TooManyRequests, f.post(token, null, initialization).status) }
            sessions.forEach { assertEquals(HttpStatusCode.OK, f.terminate(token, it).status) }
        }
        // 拒否されたセッションが寿命の登録を残すと、ここで同時要求上限に達する。
        repeat(8) { f.initialize(token) }
    }

    @Test fun `初期化の本文送信後にHTTPが取り消されてもMCPセッションを維持する`() = withMcpHttp(cancelAfterInitializationResponse = true) { f ->
        val token = f.access.issueBearer(f.register().id)
        val session = f.initialize(token)
        assertEquals(HttpStatusCode.OK, f.post(token, session, ping).status)
        assertEquals(HttpStatusCode.OK, f.terminate(token, session).status)
        assertEquals(HttpStatusCode.NotFound, f.post(token, session, ping).status)
    }

    @Test fun `初期化の応答を書き終えた後で送信完了の前にHTTPが取り消されてもMCPセッションを維持する`() = withMcpHttp(cancelAfterInitializationBody = true) { f ->
        val token = f.access.issueBearer(f.register().id)
        // 取り消した呼び出しの接続はサーバが閉じるので、後続の要求で使い回さないよう初期化だけ別のクライアントで送る。
        val session = HttpClient(CIO).use { it.preparePost(f.url) {
            bearerAuth(token.value)
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            header("MCP-Protocol-Version", "2025-11-25")
            setBody(externalJson(initialization).toString())
        }.execute { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            assertNotNull(response.headers["Mcp-Session-Id"])
        } }
        withTimeout(5_000) { f.initializationEnded.await() }
        val notification = f.post(token, session, mapOf("jsonrpc" to "2.0", "method" to "notifications/initialized"))
        assertEquals(HttpStatusCode.Accepted, notification.status, notification.bodyAsText())
        assertEquals(HttpStatusCode.OK, f.post(token, session, ping).status)
        assertEquals(HttpStatusCode.OK, f.terminate(token, session).status)
    }

    @Test fun `確定中の取消とMCP停止は開始した保存と記録を待ち再接続後に同じ結果を返す`() {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        withMcpHttp(afterJournal = { record ->
            if (record.state == OperationState.APPLYING) {
                entered.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS)) { "試験の保存待機が解除されませんでした" }
            }
        }) { f ->
            try {
                val registration = f.register()
                val peer = f.peer(f.access.issueBearer(registration.id))
                val work = peer.data("begin_work", mapOf("title" to "取消中の保存", "mode" to "direct")).text("id")
                peer.data("edit_work", mapOf("workId" to work, "changes" to changes("完了まで保存")))
                val plan = peer.data("plan_work", mapOf("workId" to work)).text("planId")
                val commit = launch { runCatching { peer.call("commit_work", mapOf("workId" to work, "planId" to plan)) } }
                entered.await()
                commit.cancel()
                val stop = async { f.configure(LocalConnectionSettings(apiEnabled = true)) }
                delay(100)
                assertFalse(stop.isCompleted)
                assertEquals(ConnectionPhase.STOPPING, f.connections.status.value.features.getValue(LocalFeature.MCP).phase)
                release.countDown()
                stop.await(); commit.join()
                f.configure(LocalConnectionSettings(mcpEnabled = true))
                val next = f.peer(f.access.issueBearer(registration.id))
                val result = next.data("work_result", mapOf("workId" to work))
                assertEquals("applied", result.text("state"))
                assertEquals(result, next.data("commit_work", mapOf("workId" to work, "planId" to plan)))
                assertTrue(Files.readString(f.root.resolve("a.yaml")).contains("完了まで保存"))
            } finally { release.countDown() }
        }
    }
}
