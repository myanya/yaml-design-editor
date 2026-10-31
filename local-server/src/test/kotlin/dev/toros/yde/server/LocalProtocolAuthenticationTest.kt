package dev.toros.yde.server

import dev.toros.yde.server.auth.AccessFixture
import dev.toros.yde.server.transport.*
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class LocalProtocolAuthenticationTest {
    @Test fun `公式MCPクライアントで認証し別の接続者から同じセッションを使えない`() = testApplication {
        val f = AccessFixture()
        f.enable()
        val server = probeMcpServer()
        val token = f.access.issueBearer(f.client(LocalFeature.MCP).id)
        val other = f.access.issueBearer(f.client(LocalFeature.MCP).id)
        val session = AtomicReference<String>()
        application { localConnectionGate(f.connections); localAuthentication(f.connections, f.access); protocolProbe(server) }
        val http = createClient {
            install(SSE)
            defaultRequest { header(HttpHeaders.Host, "127.0.0.1:18765"); bearerAuth(token.value) }
            HttpResponseValidator { validateResponse { response -> response.headers["Mcp-Session-Id"]?.let(session::set) } }
        }
        val mcp = Client(Implementation("認証試験", "1"))
        try {
            withTimeout(15_000) {
                mcp.connect(StreamableHttpClientTransport(http, "http://localhost/mcp"))
                assertEquals(listOf("probe"), mcp.listTools().tools.map { it.name })
                val id = assertNotNull(session.get())
                assertEquals(HttpStatusCode.NotFound, client.get("/mcp") {
                    header(HttpHeaders.Host, "127.0.0.1:18765"); bearerAuth(other.value); header("Mcp-Session-Id", id)
                }.status)
                assertEquals(listOf("probe"), mcp.listTools().tools.map { it.name })
            }
        } finally { try { mcp.close() } finally { try { http.close(); server.close() } finally { f.close() } } }
    }

    @Test fun `WebCookieはHttpOnlyとSameSiteと画面用通信のパスを固定する`() = testApplication {
        val f = AccessFixture()
        f.enable()
        val token = f.access.issueWebSession(f.client(LocalFeature.WEB).id)
        application {
            localConnectionGate(f.connections); localAuthentication(f.connections, f.access)
            // 発行済みの値を配送する検査専用の経路。製品のログイン経路ではない。
            routing { get("/app") { call.setLocalWebSession(token); call.respondText("画面") } }
        }
        try {
            val response = client.get("/app") { header(HttpHeaders.Host, "127.0.0.1:18765") }
            assertEquals(HttpStatusCode.OK, response.status)
            val cookie = assertNotNull(response.headers[HttpHeaders.SetCookie])
            assertContains(cookie, "HttpOnly", ignoreCase = true)
            assertContains(cookie, "SameSite=Strict", ignoreCase = true)
            assertContains(cookie, "Path=/web-api", ignoreCase = true)
            assertFalse(cookie.contains("Domain=", ignoreCase = true))
            assertFalse(cookie.contains(token.csrfToken!!))
        } finally { f.close() }
    }
}
