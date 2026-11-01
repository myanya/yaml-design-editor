package dev.toros.yde.server.client

import dev.toros.yde.server.transport.LoopbackEngine
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class LocalApiClientTest {
    @Test fun `日本語の検索と本文を認証付きで往復し版と数値型を保つ`(): Unit = runBlocking {
        LoopbackEngine(0) { routing {
            get("/api/v1/search") {
                assertEquals("Bearer secret", call.request.headers[HttpHeaders.Authorization])
                assertEquals("日本語 & + /", call.request.queryParameters["q"])
                call.respondText("""{"revision":"r1","data":{"items":["日本語"],"count":12345678901,"ok":true}}""", ContentType.Application.Json)
            }
            post("/api/v1/works") {
                assertEquals(ContentType.Application.Json, call.request.contentType().withoutParameters())
                assertContains(call.receiveText(), "題名")
                call.respondText("""{"data":{"id":"work-1"}}""", ContentType.Application.Json)
            }
        } }.use { server ->
            LocalApiClient(server.start(), "secret").use { client ->
                val result = client.execute(LocalApiRequest(LocalApiMethod.GET, listOf("search"), mapOf("q" to "日本語 & + /")))
                assertEquals("r1", result["revision"])
                assertEquals(12345678901L, (result["data"] as Map<*, *>)["count"])
                assertEquals(mapOf("id" to "work-1"), client.execute(LocalApiRequest(LocalApiMethod.POST, listOf("works"), body = mapOf("title" to "題名")))["data"])
            }
        }
    }

    @Test fun `別ポートへの転送も自動再試行もせずエラー本文の秘密を表示しない`(): Unit = runBlocking {
        val redirected = AtomicInteger()
        val attempts = AtomicInteger()
        LoopbackEngine(0) { routing { get("/stolen") { redirected.incrementAndGet(); call.respondText("secret") } } }.use { other ->
            val target = other.start()
            LoopbackEngine(0) { routing {
                get("/api/v1/redirect") { attempts.incrementAndGet(); call.respondRedirect("http://127.0.0.1:$target/stolen") }
                get("/api/v1/reject") { call.respondText("secret", status = HttpStatusCode.Forbidden) }
                get("/api/v1/broken") { call.respondText("secret", ContentType.Application.Json) }
            } }.use { server ->
                LocalApiClient(server.start(), "secret").use { client ->
                    for (path in listOf("redirect", "reject", "broken")) {
                        val failure = assertFailsWith<LocalApiFailure> { client.execute(LocalApiRequest(LocalApiMethod.GET, listOf(path))) }
                        assertFalse("secret" in failure.message.orEmpty())
                        if (path == "reject") assertEquals(403, failure.status)
                    }
                }
            }
        }
        assertEquals(0, redirected.get())
        assertEquals(1, attempts.get())
    }

    @Test fun `応答の上限と受信期限で打ち切り要求の不正値は送信前に拒否する`(): Unit = runBlocking {
        val attempts = AtomicInteger()
        LoopbackEngine(0) { routing {
            get("/api/v1/large") { call.respondText("""{"data":{"value":"${"a".repeat(200)}"}}""", ContentType.Application.Json) }
            get("/api/v1/slow") { delay(2_000); call.respondText("""{"data":{}}""", ContentType.Application.Json) }
            post("/api/v1/works") { attempts.incrementAndGet(); call.respondText("""{"data":{}}""", ContentType.Application.Json) }
        } }.use { server ->
            val port = server.start()
            LocalApiClient(port, "secret", responseBytes = 100).use {
                assertFailsWith<LocalApiFailure> { it.execute(LocalApiRequest(LocalApiMethod.GET, listOf("large"))) }
                assertFailsWith<IllegalArgumentException> { it.execute(LocalApiRequest(LocalApiMethod.GET, listOf(".."))) }
                assertFailsWith<IllegalArgumentException> { it.execute(LocalApiRequest(LocalApiMethod.POST, listOf("works"), body = mapOf("bad" to Double.NaN))) }
                assertFailsWith<IllegalArgumentException> { it.execute(LocalApiRequest(LocalApiMethod.POST, listOf("works"), body = mapOf("bad" to mapOf(1 to "value")))) }
            }
            LocalApiClient(port, "secret", timeoutMillis = 150).use {
                assertFailsWith<LocalApiFailure> { it.execute(LocalApiRequest(LocalApiMethod.GET, listOf("slow"))) }
            }
        }
        assertEquals(0, attempts.get())
    }
}
