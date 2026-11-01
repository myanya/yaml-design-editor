package dev.toros.yde.server.mcp

import dev.toros.yde.app.service.*
import dev.toros.yde.server.*
import dev.toros.yde.server.api.readApi
import dev.toros.yde.server.auth.*
import dev.toros.yde.server.protocol.externalJson
import dev.toros.yde.server.transport.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspaceMcpTest {
    private val read = ConnectionPermissions(readScope = ConnectionReadScope.WORKSPACE)
    private val both = read.copy(proposalScope = ConnectionWriteScope.LEDGER, commitScope = ConnectionWriteScope.LEDGER,
        actionScope = ConnectionWriteScope.LEDGER, actions = setOf("rename"))
    private class Fixture(val root: Path, val host: WorkspaceHost, val access: AccessFixture, val work: WorkspaceWork) {
        val clients = mutableListOf<Peer>()
        fun saved() = Files.readString(root.resolve("a.yaml"))
        suspend fun close() { clients.reversed().forEach { it.close() } }
    }
    private class Peer(val client: Client, val http: HttpClient, val registration: LocalClient, val credential: IssuedCredential) {
        suspend fun close() { try { client.close() } finally { http.close() } }
        suspend fun call(name: String, args: Map<String, Any?> = emptyMap()) = withTimeout(10_000) {
            client.callTool(CallToolRequest(CallToolRequestParams(name, externalJson(args).jsonObject)))
        }
        suspend fun data(name: String, args: Map<String, Any?> = emptyMap()): JsonObject {
            val result = call(name, args)
            assertFalse(result.isError == true, result.content.toString())
            val json = assertNotNull(result.structuredContent)
            assertEquals(json.toString(), (result.content.single() as TextContent).text)
            return json.getValue("data").jsonObject
        }
        suspend fun begin(mode: String = "direct") = data("begin_work", mapOf("mode" to mode, "title" to "タイトル変更")).text("id")
        suspend fun edit(id: String, value: Any? = "変更後", file: String = "a.yaml") = call("edit_work", mapOf("workId" to id,
            "changes" to listOf(mapOf("file" to file, "ops" to listOf(mapOf("op" to "set", "path" to "title", "value" to value))))))
    }
    private fun withMcp(block: suspend ApplicationTestBuilder.(Fixture) -> Unit) = testApplication {
        val root = Files.createTempDirectory("workspace-mcp")
        val host = WorkspaceHost()
        val access = AccessFixture()
        val work = WorkspaceWork(host, access.grants::workGrant, { "ws" }, directGrants = access.grants::directWorkGrant)
        val fixture = Fixture(root, host, access, work)
        try {
            Files.writeString(root.resolve("a.yaml"), "title: original\n")
            Files.createDirectories(root.resolve("_schemas"))
            Files.writeString(root.resolve("_schemas/item.yaml"), """
                id: item
                name: 項目
                match: '*.yaml'
                root:
                  type: object
                  fields:
                    - {key: title, type: string, required: true}
                actions:
                  - id: rename
                    target: {schema: item}
                    do:
                      - set: {path: title, value: renamed}
            """.trimIndent())
            host.openWorkspace(root)
            access.workspace = AccessWorkspace("ws", host.currentStatus().epoch)
            access.enable(LocalConnectionSettings(mcpEnabled = true))
            val reads = WorkspaceReads(host, access.grants::get, { "ws" })
            val operations = WorkspaceOperations(host, access.grants::operationGrant, { "ws" })
            application {
                localConnectionGate(access.connections)
                localAuthentication(access.connections, access.access)
                workspaceMcp(access.connections, access.access, reads, work, operations)
                readApi(reads)
            }
            block(fixture)
        } finally { try { fixture.close() } finally { access.close(); host.close(); root.toFile().deleteRecursively() } }
    }
    private suspend fun ApplicationTestBuilder.peer(f: Fixture, permissions: ConnectionPermissions = both): Peer {
        val registration = f.access.client(LocalFeature.MCP, permissions)
        val token = f.access.access.issueBearer(registration.id)
        val http = createClient {
            install(SSE)
            defaultRequest { header(HttpHeaders.Host, "127.0.0.1:18765"); bearerAuth(token.value) }
        }
        val client = Client(Implementation("外部 AI の試験", "1"))
        val peer = Peer(client, http, registration, token)
        f.clients += peer
        withTimeout(10_000) { client.connect(StreamableHttpClientTransport(http, "http://localhost/mcp")) }
        return peer
    }

    @Test fun `MCPだけを有効にして公式クライアントから保存済みの参照と権限別のツール一覧を取得する`() = withMcp { f ->
        val peer = peer(f, read)
        val tools = peer.client.listTools().tools
        assertEquals(9, tools.size)
        assertTrue(tools.all { it.annotations?.readOnlyHint == true && it.annotations?.openWorldHint == false })
        assertEquals(listOf("document"), tools.single { it.name == "read_document" }.inputSchema.required)
        val docs = peer.call("list_documents", mapOf("limit" to 1)).structuredContent!!
        assertEquals("ws", docs.text("workspaceId"))
        assertTrue(docs.text("revision").isNotBlank())
        assertTrue(peer.data("read_document", mapOf("document" to "a.yaml")).toString().contains("original"))
        assertTrue(peer.data("search", mapOf("q" to "original")).toString().contains("a.yaml"))
        assertFalse(peer.call("validate_workspace", mapOf("document" to "a.yaml")).isError == true)
        assertTrue(peer.call("begin_work", mapOf("mode" to "direct", "title" to "不許可")).isError == true)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/workspace") {
            header(HttpHeaders.Host, "127.0.0.1:18765"); bearerAuth(peer.credential.value)
        }.status)
    }

    @Test fun `作業コピーを検証し直接確定の再送と結果照会が同じ結果を返す`() = withMcp { f ->
        val peer = peer(f)
        val tools = peer.client.listTools().tools
        assertEquals(20, tools.size)
        assertEquals(true, tools.single { it.name == "commit_work" }.annotations?.idempotentHint)
        assertEquals(true, tools.single { it.name == "commit_work" }.annotations?.destructiveHint)
        val edit = tools.single { it.name == "edit_work" }.inputSchema
        assertEquals(setOf("JsonValue", "PatchOperation", "FileChange", "ChangeSet"), edit.defs!!.keys)
        assertFalse(edit.toString().contains("#/components/"))
        val id = peer.begin()
        assertFalse(peer.edit(id).isError == true)
        assertContains(f.saved(), "original")
        val validation = peer.data("validate_work", mapOf("workId" to id))
        assertEquals(true, validation.getValue("valid").jsonPrimitive.boolean)
        assertContains(validation.toString(), "変更後")
        val plan = peer.data("plan_work", mapOf("workId" to id)).text("planId")
        val args = mapOf("workId" to id, "planId" to plan)
        val result = peer.data("commit_work", args)
        assertEquals("applied", result.text("state"))
        assertEquals(result, peer.data("commit_work", args))
        assertEquals(result, peer.data("work_result", mapOf("workId" to id)))
        assertContains(f.saved(), "変更後")
        assertTrue(f.work.inbox.value.isEmpty())
    }

    @Test fun `提案はデスクトップ承認を待ち外部から直接確定へ切り替えられない`() = withMcp { f ->
        val peer = peer(f)
        val id = peer.begin("proposal")
        assertFalse(peer.edit(id).isError == true)
        assertEquals("awaiting_approval", peer.data("submit_work", mapOf("workId" to id)).text("state"))
        assertEquals(id, f.work.inbox.value.single().id)
        assertContains(f.saved(), "original")
        assertTrue(peer.call("plan_work", mapOf("workId" to id)).isError == true)
        val review = f.work.review(id)
        assertTrue(peer.call("commit_work", mapOf("workId" to id, "planId" to review.id)).isError == true)
        assertTrue(peer.call("approve_work", mapOf("workId" to id)).isError == true)
        assertEquals(WorkState.APPLIED, f.work.approve(id, review.id).state)
        assertEquals("applied", peer.data("work_result", mapOf("workId" to id)).text("state"))
    }

    @Test fun `検証エラーと古い計画は成功扱いせず破棄できる`() = withMcp { f ->
        val peer = peer(f)
        val id = peer.begin()
        assertFalse(peer.edit(id, null).isError == true)
        val validation = peer.call("validate_work", mapOf("workId" to id))
        assertTrue(validation.isError == true)
        assertEquals(false, validation.structuredContent!!.getValue("data").jsonObject.getValue("valid").jsonPrimitive.boolean)
        val invalid = peer.call("plan_work", mapOf("workId" to id))
        assertTrue(invalid.isError == true)
        assertFalse(peer.edit(id, "修正済み").isError == true)
        val plan = peer.data("plan_work", mapOf("workId" to id)).text("planId")
        peer.edit(id, "再編集")
        assertTrue(peer.call("commit_work", mapOf("workId" to id, "planId" to plan)).isError == true)
        assertEquals("discarded", peer.data("discard_work", mapOf("workId" to id)).text("state"))
        assertContains(f.saved(), "original")
    }

    @Test fun `定義済み操作は許可した名前だけを計画し要求IDで再送できる`() = withMcp { f ->
        val peer = peer(f)
        assertContains(peer.data("list_actions").toString(), "rename")
        assertTrue(peer.call("plan_action", mapOf("name" to "other")).isError == true)
        val plan = peer.data("plan_action", mapOf("name" to "rename", "target" to "a.yaml")).text("planId")
        val args = mapOf("planId" to plan, "requestId" to "mcp-rename-1")
        val result = peer.data("commit_action", args)
        assertEquals("committed", result.text("state"))
        assertEquals(result, peer.data("commit_action", args))
        assertEquals(result, peer.data("action_result", mapOf("requestId" to "mcp-rename-1")))
        assertContains(f.saved(), "renamed")
    }

    @Test fun `権限外文書と他の接続者の作業と不明な引数を拒否する`() = withMcp { f ->
        val peer = peer(f)
        val id = peer.begin()
        val other = peer(f)
        assertTrue(other.call("work_result", mapOf("workId" to id)).isError == true)
        assertTrue(other.edit(id).isError == true)
        assertTrue(peer.edit(id, file = "_schemas/item.yaml").isError == true)
        assertTrue(peer.edit(id, file = "../outside.yaml").isError == true)
        assertTrue(peer.call("begin_work", mapOf("mode" to "direct", "title" to "題名", "allowErrors" to true)).isError == true)
        assertTrue(peer.call("list_documents", mapOf("limit" to "2")).isError == true)
        val malformed = peer.call("edit_work", mapOf("workId" to id, "changes" to listOf(mapOf("file" to "a.yaml", "ops" to listOf(mapOf("op" to "execute", "code" to "secret"))))))
        assertTrue(malformed.isError == true)
        assertFalse(malformed.toString().contains("secret"))
        val limited = peer(f, ConnectionPermissions(readable = setOf(f.access.a)))
        assertTrue(limited.call("read_document", mapOf("document" to "_schemas/item.yaml")).isError == true)
        assertContains(f.saved(), "original")
    }

    @Test fun `MCPを停止してもAPIは継続し再開後は新しい認証で接続する`() = withMcp { f ->
        val old = peer(f, read)
        assertFalse(old.call("get_workspace").isError == true)
        f.access.enable(LocalConnectionSettings(apiEnabled = true, mcpEnabled = true))
        val api = f.access.access.issueBearer(f.access.client(LocalFeature.API, read).id)
        withTimeout(10_000) { f.access.enable(LocalConnectionSettings(apiEnabled = true)) }
        assertEquals(HttpStatusCode.NotFound, client.get("/mcp") {
            header(HttpHeaders.Host, "127.0.0.1:18765"); bearerAuth(old.credential.value)
        }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/workspace") {
            header(HttpHeaders.Host, "127.0.0.1:18765"); bearerAuth(api.value)
        }.status)
        f.access.enable(LocalConnectionSettings(apiEnabled = true, mcpEnabled = true))
        assertEquals(HttpStatusCode.Unauthorized, client.post("/mcp") {
            header(HttpHeaders.Host, "127.0.0.1:18765"); bearerAuth(old.credential.value)
        }.status)
        assertFalse(peer(f, read).call("get_workspace").isError == true)
    }

    private companion object {
        fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    }
}
