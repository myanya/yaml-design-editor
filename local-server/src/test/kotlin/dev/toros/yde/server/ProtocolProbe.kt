package dev.toros.yde.server

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 依存の組合せを試すだけのツール。製品の経路・ツールには登録しない。 */
internal fun probeMcpServer(): Server = Server(
    Implementation(name = "yde-compatibility", version = "1"),
    ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
).apply {
    addTool(name = "probe", description = "日本語と JSON の往復を検査する") {
        CallToolResult(content = listOf(TextContent("日本語\n検査")))
    }
}

internal fun Application.protocolProbe(server: Server) {
    mcpStreamableHttp(path = "/mcp", maxSessions = 4) { server }
    routing {
        get("/probe") {
            val body = buildJsonObject {
                put("text", "日本語\n検査")
                put("count", 3)
                put("enabled", true)
                put("missing", JsonNull)
            }
            call.respondText(body.toString(), ContentType.Application.Json)
        }
    }
}
