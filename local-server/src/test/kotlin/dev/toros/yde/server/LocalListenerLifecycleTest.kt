package dev.toros.yde.server

import dev.toros.yde.server.transport.LoopbackEngine
import dev.toros.yde.server.transport.localConnectionGate
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlin.test.*

/** 実ソケットはこの試験が起動した loopback だけ。通常の組合せ検査は testApplication で行う。 */
class LocalListenerLifecycleTest {
    @Test fun `共有待受の部分停止と全停止後の切断とポート解放を確認する`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val created = AtomicInteger()
        val connections = LocalConnections(scope, { _, control ->
            created.incrementAndGet()
            LoopbackEngine(0) {
                localConnectionGate(control)
                routing {
                    get("/api/v1") { call.respondText("api") }
                    get("/mcp") { call.respondText("mcp") }
                    get("/app") { call.respondText("web") }
                }
            }
        })
        try {
            assertTrue(connections.status.value.stopped)
            assertEquals(0, created.get())
            val started = withTimeout(15_000) { connections.awaitSettled(connections.configure(LocalConnectionSettings(true, true, true))) }
            val port = assertNotNull(started.boundPort)
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().use { client ->
                fun get(path: String) = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                    .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString()).statusCode()
                assertEquals(200, get("/api/v1"))
                withTimeout(5_000) { connections.awaitSettled(connections.configure(LocalConnectionSettings(false, true, true))) }
                assertEquals(port, connections.status.value.boundPort)
                assertEquals(404, get("/api/v1"))
                assertEquals(200, get("/mcp"))
                assertEquals(200, get("/app"))
                assertEquals(1, created.get())
                // keep-alive の接続を残したまま最後の機能を停止する。
                withTimeout(15_000) { connections.awaitSettled(connections.configure(LocalConnectionSettings())) }
                assertTrue(connections.status.value.stopped)
                assertFails { get("/app") }
            }
            val address = InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)
            assertFails { Socket().use { it.connect(address, 500) } }
            ServerSocket().use { it.reuseAddress = true; it.bind(address) }
        } finally { try { withTimeout(15_000) { connections.shutdown() } } finally { scope.cancel() } }
    }

    @Test fun `指定ポートが占有されている場合は別ポートへ逃げず非稼働を返す`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connections = LocalConnections(scope)
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { occupied ->
                val requested = LocalConnectionSettings(apiEnabled = true, port = occupied.localPort)
                val state = withTimeout(15_000) { connections.awaitSettled(connections.configure(requested)) }
                assertEquals(ConnectionPhase.ERROR, state.listener)
                assertTrue(state.desired.apiEnabled)
                assertEquals(occupied.localPort, state.desired.port)
                assertNull(state.boundPort)
                assertFalse(connections.withRequest(LocalFeature.API) {})
            }
            assertTrue(withTimeout(15_000) { connections.awaitSettled(connections.configure(LocalConnectionSettings(port = connections.status.value.desired.port))) }.stopped)
        } finally { try { connections.shutdown() } finally { scope.cancel() } }
    }
}
