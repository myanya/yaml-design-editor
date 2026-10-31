package dev.toros.yde.server

import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransportCompatibilityTest {
    @Test
    fun `未設定の三機能はすべて停止し待受を要求しない`() {
        val settings = LocalConnectionSettings()
        assertFalse(settings.apiEnabled)
        assertFalse(settings.mcpEnabled)
        assertFalse(settings.webEnabled)
        assertFalse(settings.requiresListener)
        for (mask in 1..7) {
            assertTrue(LocalConnectionSettings(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0).requiresListener)
        }
        assertFailsWith<IllegalArgumentException> { LocalConnectionSettings(port = 0) }
        assertFailsWith<IllegalArgumentException> { LocalConnectionSettings(port = 65536) }
    }

    @Test
    fun `HTTP と公式 MCP クライアントが同じ JSON 依存で往復できる`() = testApplication {
        val server = probeMcpServer()
        application { protocolProbe(server) }
        val http = createClient {
            install(SSE)
            // testApplication は実ソケットと違って Host を自動で付けない。
            defaultRequest { header("Host", "localhost") }
        }
        val mcp = Client(Implementation(name = "yde-test", version = "1"))
        try {
            val response = http.get("/probe")
            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("日本語\n検査", json.getValue("text").jsonPrimitive.content)
            assertEquals(3, json.getValue("count").jsonPrimitive.int)
            assertTrue(json.getValue("enabled").jsonPrimitive.boolean)
            assertEquals(JsonNull, json["missing"])
            withTimeout(15_000) {
                mcp.connect(StreamableHttpClientTransport(http, "http://localhost/mcp"))
                assertEquals(listOf("probe"), mcp.listTools().tools.map { it.name })
                val result = mcp.callTool(CallToolRequest(CallToolRequestParams(name = "probe")))
                assertEquals("日本語\n検査", (result.content.single() as TextContent).text)
            }
            assertEquals(HttpStatusCode.Forbidden, http.post("/mcp") {
                header("Origin", "https://example.invalid")
            }.status)
        } finally {
            try { mcp.close() } finally {
                try { http.close() } finally { server.close() }
            }
        }
    }

    @Test
    fun `CIO の待受と停止を実際のループバックで検査する`() {
        LocalServerDistributionSmoke.main(emptyArray())
    }
}
