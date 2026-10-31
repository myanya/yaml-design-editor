package dev.toros.yde.server

import dev.toros.yde.server.transport.ConnectionListener
import dev.toros.yde.server.transport.localConnectionGate
import dev.toros.yde.server.transport.LocalRequestKey
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalConnectionGateTest {
    @Test fun `全八組合せをHTTP受付で強制しWeb専用通信をAPIから独立させる`() = testApplication {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connections = LocalConnections(scope, { _, _ -> object : ConnectionListener {
            override fun start() = 18765
            override fun close() {}
        } })
        val paths = mapOf("/api/v1" to 1, "/api/v1/items" to 1, "/mcp" to 2, "/app" to 4, "/app/assets/app.js" to 4, "/web-api" to 4, "/web-api/items" to 4)
        val forbidden = listOf("/health", "/api/v10", "/mcp-other", "/application", "/web-api-other", "/%61pi/v1")
        application {
            localConnectionGate(connections)
            routing { (paths.keys + forbidden).forEach { path -> get(path) { call.respondText("受付しました") } } }
        }
        try {
            for (mask in 0..7) {
                val revision = connections.configure(LocalConnectionSettings(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0))
                withTimeout(5_000) { connections.awaitSettled(revision) }
                paths.forEach { (path, bit) ->
                    assertEquals(if (mask and bit != 0) HttpStatusCode.OK else HttpStatusCode.NotFound, client.get(path).status, "$mask: $path")
                }
                forbidden.forEach { assertEquals(HttpStatusCode.NotFound, client.get(it).status, "$mask: $it") }
            }
        } finally { try { connections.shutdown() } finally { scope.cancel() } }
    }

    @Test fun `HTTPの長い要求を機能別に取消し保存中のハンドラだけ収束を待つ`() = testApplication {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connections = LocalConnections(scope, { _, _ -> object : ConnectionListener {
            override fun start() = 18765
            override fun close() {}
        } })
        val reading = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val writing = CompletableDeferred<Unit>()
        val finishSave = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<Unit>()
        application {
            localConnectionGate(connections)
            routing {
                get("/mcp/stream") { reading.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
                get("/api/v1/save") {
                    call.attributes[LocalRequestKey].finishWrite { writing.complete(Unit); finishSave.await(); saved.complete(Unit) }
                    call.respondText("保存済み")
                }
                get("/app") { call.respondText("web") }
            }
        }
        try {
            withTimeout(5_000) { connections.awaitSettled(connections.configure(LocalConnectionSettings(true, true, true))) }
            coroutineScope {
                val read = async { runCatching { client.get("/mcp/stream") } }
                val write = async { runCatching { client.get("/api/v1/save") } }
                withTimeout(5_000) { reading.await(); writing.await() }
                val requested = connections.configure(LocalConnectionSettings(webEnabled = true))
                withTimeout(5_000) { cancelled.await() }
                assertEquals(HttpStatusCode.OK, client.get("/app").status)
                assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/save").status)
                assertTrue(connections.status.value.settledRevision < requested)
                finishSave.complete(Unit)
                withTimeout(5_000) { saved.await(); connections.awaitSettled(requested); read.await(); write.await() }
            }
        } finally { finishSave.complete(Unit); try { connections.shutdown() } finally { scope.cancel() } }
    }
}
