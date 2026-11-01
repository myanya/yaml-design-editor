package dev.toros.yde.server.mcp

import dev.toros.yde.app.service.*
import dev.toros.yde.server.*
import dev.toros.yde.server.api.readApi
import dev.toros.yde.server.api.writeApi
import dev.toros.yde.server.auth.*
import dev.toros.yde.server.protocol.externalJson
import dev.toros.yde.server.transport.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.*
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** 製品と同じゲート・認証・アダプターを CIO の実ポートで組み立てる。一時領域以外は操作しない。 */
internal class McpHttpFixture(idleTimeout: Duration = 15.minutes, afterCapture: suspend () -> Unit = {},
    afterJournal: (OperationRecord) -> Unit = {}, cancelAfterInitializationResponse: Boolean = false,
) {
    val root = Files.createTempDirectory("yde-mcp-http")
    private val owner = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val host = WorkspaceHost(owner = owner)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val grants = ConnectionGrants()
    val reads = WorkspaceReads(host, grants::get, { "wire" }, afterCapture = afterCapture)
    val work = WorkspaceWork(host, grants::workGrant, { "wire" }, directGrants = grants::directWorkGrant,
        operationJournals = { root, layout ->
            val journal = FileOperationJournal(root, layout)
            object : OperationJournal by journal {
                override fun write(record: OperationRecord) { journal.write(record); afterJournal(record) }
            }
        })
    val operations = WorkspaceOperations(host, grants::operationGrant, { "wire" })
    val connections: LocalConnections = LocalConnections(scope, { _, connections ->
        LoopbackEngine(0) {
            localConnectionGate(connections)
            localAuthentication(connections, access)
            readApi(reads)
            writeApi(work, operations)
            workspaceMcp(connections, access, reads, work, operations, idleTimeout)
            if (cancelAfterInitializationResponse) sendPipeline.intercept(ApplicationSendPipeline.After) {
                if (call.request.headers["Mcp-Session-Id"] == null && call.response.headers["Mcp-Session-Id"] != null && call.response.status() == HttpStatusCode.OK) {
                    currentCoroutineContext().cancel(CancellationException("試験: 本文を送信した直後の HTTP 終了"))
                    currentCoroutineContext().ensureActive()
                }
            }
        }
    }, onDisabled = { access.featureStopped(it) })
    val access = LocalAccess(scope, connections, grants, {
        host.status.value.let { if (it.workspace != null) AccessWorkspace("wire", it.epoch) else null }
    })
    val http = HttpClient(CIO)
    val peers = mutableListOf<McpHttpPeer>()
    val url: String get() = "http://127.0.0.1:${connections.status.value.boundPort}/mcp"
    val permissions = ConnectionPermissions(readScope = ConnectionReadScope.WORKSPACE,
        proposalScope = ConnectionWriteScope.LEDGER, commitScope = ConnectionWriteScope.LEDGER)

    suspend fun start() {
        Files.writeString(root.resolve("a.yaml"), "title: before\n")
        Files.createDirectories(root.resolve("_schemas"))
        Files.writeString(root.resolve("_schemas/item.yaml"), """
            id: item
            name: 項目
            match: '*.yaml'
            root:
              type: object
              fields:
                - {key: title, type: string, required: true}
        """.trimIndent())
        withContext(owner) { host.openWorkspace(root) }
        configure(LocalConnectionSettings(mcpEnabled = true))
    }

    suspend fun configure(settings: LocalConnectionSettings) {
        val status = withTimeout(10_000) { connections.awaitSettled(connections.configure(settings)) }
        assertNull(status.error)
    }

    fun register() = access.register(LocalFeature.MCP, "相互運用試験", permissions)

    suspend fun peer(token: IssuedCredential): McpHttpPeer {
        val peer = McpHttpPeer(url, token)
        peers += peer
        withTimeout(10_000) { peer.client.connect(peer.transport) }
        return peer
    }

    suspend fun post(token: IssuedCredential, session: String?, payload: Map<String, Any?>,
        version: String = "2025-11-25", origin: String? = null,
    ): HttpResponse = http.post(url) {
        bearerAuth(token.value)
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        header("MCP-Protocol-Version", version)
        if (session != null) header("Mcp-Session-Id", session)
        if (origin != null) header(HttpHeaders.Origin, origin)
        setBody(externalJson(payload).toString())
    }

    suspend fun initialize(token: IssuedCredential): String {
        val response = post(token, null, initialization)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val session = assertNotNull(response.headers["Mcp-Session-Id"])
        val notification = post(token, session, mapOf("jsonrpc" to "2.0", "method" to "notifications/initialized"))
        assertEquals(HttpStatusCode.Accepted, notification.status, notification.bodyAsText())
        return session
    }

    suspend fun terminate(token: IssuedCredential, session: String): HttpResponse = http.delete(url) {
        bearerAuth(token.value); header("Mcp-Session-Id", session); header("MCP-Protocol-Version", "2025-11-25")
    }

    suspend fun close() {
        try {
            withTimeout(10_000) { connections.shutdown() }
        } finally {
            peers.reversed().forEach { runCatching { it.close() } }
            http.close(); scope.cancel()
            withContext(owner) { host.close() }
            owner.close()
            root.toFile().deleteRecursively()
        }
    }
}

internal class McpHttpPeer(url: String, token: IssuedCredential) {
    private val http = HttpClient(CIO) { install(SSE) }
    val client = Client(Implementation("公式SDKによる相互運用試験", "1"))
    val transport = StreamableHttpClientTransport(http, url) { bearerAuth(token.value) }
    suspend fun call(name: String, args: Map<String, Any?> = emptyMap()) = withTimeout(10_000) {
        client.callTool(CallToolRequest(CallToolRequestParams(name, externalJson(args).jsonObject)))
    }
    suspend fun data(name: String, args: Map<String, Any?> = emptyMap()): JsonObject {
        val result = call(name, args)
        assertFalse(result.isError == true, result.content.toString())
        return assertNotNull(result.structuredContent).getValue("data").jsonObject
    }
    suspend fun close() { try { client.close() } finally { http.close() } }
}

internal fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
internal val ping = mapOf("jsonrpc" to "2.0", "id" to 9, "method" to "ping")
internal val initialization = mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "initialize", "params" to mapOf(
    "protocolVersion" to "2025-11-25", "capabilities" to emptyMap<String, Any>(),
    "clientInfo" to mapOf("name" to "HTTP試験", "version" to "1")))
internal fun changes(title: String) = listOf(mapOf("file" to "a.yaml", "ops" to listOf(mapOf("op" to "set", "path" to "title", "value" to title))))

internal fun withMcpHttp(idleTimeout: Duration = 15.minutes, afterCapture: suspend () -> Unit = {},
    afterJournal: (OperationRecord) -> Unit = {}, cancelAfterInitializationResponse: Boolean = false,
    block: suspend CoroutineScope.(McpHttpFixture) -> Unit,
): Unit = runBlocking {
    val fixture = McpHttpFixture(idleTimeout, afterCapture, afterJournal, cancelAfterInitializationResponse)
    try { withTimeout(30_000) { fixture.start(); block(fixture) } }
    finally { withContext(NonCancellable) { fixture.close() } }
}
