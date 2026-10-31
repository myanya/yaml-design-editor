package dev.toros.yde.server

import dev.toros.yde.server.transport.LoopbackEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** 同梱 JAR と縮小ランタイムだけで実行する。テスト用 SDK クライアントや Ktor test-host は要らない。 */
object LocalServerDistributionSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        val loopback = InetAddress.getByName("127.0.0.1")
        // 未起動なら同じポートを保持できる。コンストラクターが勝手にバインドしていないことも見る。
        ServerSocket(0, 1, loopback).use { occupied ->
            val dormant = LoopbackEngine(occupied.localPort) {}
            dormant.close()
        }
        val mcp = probeMcpServer()
        val engine = LoopbackEngine(0) { protocolProbe(mcp) }
        var boundPort: Int? = null
        try {
            val port = engine.start()
            boundPort = port
            check(engine.start() == port) { "起動の再要求で別の待受ができています" }
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().use { http ->
                fun request(path: String, body: String? = null, session: String? = null): HttpResponse<String> {
                    val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Accept", "application/json, text/event-stream")
                    if (session != null) builder.header("Mcp-Session-Id", session).header("MCP-Protocol-Version", "2025-11-25")
                    if (body != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
                    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
                }
                val api = request("/probe")
                check(api.statusCode() == 200)
                check(Json.parseToJsonElement(api.body()).jsonObject.getValue("text").jsonPrimitive.content == "日本語\n検査")
                val initialized = request("/mcp", """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"distribution","version":"1"}}}""")
                check(initialized.statusCode() == 200) { "MCP 初期化が失敗しました: ${initialized.statusCode()}" }
                val session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow()
                val initJson = Json.parseToJsonElement(initialized.body()).jsonObject
                check(initJson.getValue("result").jsonObject.getValue("serverInfo").jsonObject.getValue("name").jsonPrimitive.content == "yde-compatibility")
                check(request("/mcp", """{"jsonrpc":"2.0","method":"notifications/initialized"}""", session).statusCode() == 202)
                val called = request("/mcp", """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"probe","arguments":{}}}""", session)
                check(called.statusCode() == 200)
                val content = Json.parseToJsonElement(called.body()).jsonObject.getValue("result").jsonObject.getValue("content").jsonArray
                check(content.single().jsonObject.getValue("text").jsonPrimitive.content == "日本語\n検査")
                check(request("/api/v1").statusCode() == 404) { "検査用サーバに業務 API が公開されています" }
            }
        } finally {
            try { runBlocking { mcp.close() } } finally { engine.close() }
        }
        val port = checkNotNull(boundPort)
        val stillOpen = runCatching { Socket().use { it.connect(InetSocketAddress(loopback, port), 500) } }.isSuccess
        check(!stillOpen) { "停止後も待受に接続できます" }
        ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress(loopback, port)) }
        engine.close()
        // 使用中のポートを選ぶ場合は起動失敗。別ポートへ勝手に逃げない。
        ServerSocket(0, 1, loopback).use { occupied ->
            LoopbackEngine(occupied.localPort) {}.use { contender ->
                check(runCatching { contender.start() }.isFailure) { "使用中のポートで起動に成功しました" }
            }
        }
        println("ローカル接続基盤: HTTP・MCP 初期化/ツール・未起動時の非待受・停止/ポート解放 OK")
    }
}
