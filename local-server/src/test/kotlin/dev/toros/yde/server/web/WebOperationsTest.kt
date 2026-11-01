package dev.toros.yde.server.web

import dev.toros.yde.app.service.*
import dev.toros.yde.server.*
import dev.toros.yde.server.api.OpenApiAssertions
import dev.toros.yde.server.auth.*
import dev.toros.yde.server.protocol.externalJson
import dev.toros.yde.server.transport.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class WebOperationsTest {
    private val permission = ConnectionPermissions(readScope = ConnectionReadScope.WORKSPACE,
        actionScope = ConnectionWriteScope.LEDGER, actions = setOf("rename", "bad"))
    private val contract = OpenApiAssertions(Json.parseToJsonElement(requireNotNull(javaClass.getResource("/web-api/openapi.json")).readText()).jsonObject)
    private class Fixture(val root: Path, val host: WorkspaceHost, val access: LocalAccess, val http: HttpClient)
    private fun withWeb(block: suspend (Fixture) -> Unit) = testApplication {
        val root = Files.createTempDirectory("web-operations")
        val connection = AccessFixture()
        val host = WorkspaceHost()
        try {
            Files.createDirectories(root.resolve("_schemas"))
            Files.writeString(root.resolve("a.yaml"), "id: I-1\ntitle: original\n")
            Files.writeString(root.resolve("_schemas/item.yaml"), """
                id: item
                match: '*.yaml'
                entity: {type: item, idPattern: 'I-*', defines: true}
                root:
                  type: object
                  fields:
                    - {key: id, type: id, required: true}
                    - {key: title, type: enum, values: [original, updated], required: true}
                actions:
                  - id: rename
                    label: 名前を変更
                    target: {entity: item}
                    input:
                      initial:
                        title: {${'$'}copy: title, ${'$'}of: self}
                    params:
                      - {key: title, label: 名前, type: enum, values: [original, updated], required: true}
                    do:
                      - set: {of: self, path: title, value: ${'$'}title}
                  - id: bad
                    target: {entity: item}
                    do:
                      - set: {of: self, path: title, value: invalid}
            """.trimIndent())
            host.openWorkspace(root)
            connection.workspace = AccessWorkspace("web-test", host.currentStatus().epoch)
            connection.enable(LocalConnectionSettings(webEnabled = true))
            val reads = WorkspaceReads(host, connection.grants::get, { "web-test" })
            val operations = WorkspaceOperations(host, connection.grants::operationGrant, { "web-test" })
            application {
                localConnectionGate(connection.connections); localAuthentication(connection.connections, connection.access)
                webApi(connection.access, reads, operations)
            }
            val http = createClient { defaultRequest { header(HttpHeaders.Host, "127.0.0.1:18765") } }
            block(Fixture(root, host, connection.access, http))
        } finally { connection.close(); host.close(); root.toFile().deleteRecursively() }
    }
    private inner class Browser(val f: Fixture) {
        lateinit var cookie: String
        lateinit var csrf: String
        lateinit var revision: String
        lateinit var clientId: String
        suspend fun login(p: ConnectionPermissions = permission) {
            clientId = f.access.register(LocalFeature.WEB, "業務操作", p).id
            val code = f.access.webLogins.issue(clientId)
            val response = f.http.post("/web-api/session/login") {
                header(HttpHeaders.Origin, "http://127.0.0.1:18765"); header("X-YDE-Login", "1")
                contentType(ContentType.Application.Json); setBody(externalJson(mapOf("code" to code.value)).toString())
            }
            cookie = response.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
            csrf = response.json().getValue("csrfToken").jsonPrimitive.content
            revision = get("workspace").json().getValue("revision").jsonPrimitive.content
        }
        suspend fun get(path: String) = f.http.get("/web-api/$path") { header(HttpHeaders.Cookie, cookie) }
        suspend fun post(path: String, body: Map<String, Any?>, token: String? = csrf) = f.http.post("/web-api/operations/$path") {
            header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, "http://127.0.0.1:18765")
            token?.let { header(CSRF_HEADER, it) }; contentType(ContentType.Application.Json); setBody(externalJson(body).toString())
        }
        suspend fun prepare(name: String = "rename") = post("prepare", mapOf("name" to name, "target" to "I-1", "revision" to revision))
        suspend fun checked(path: String, body: Map<String, Any?>): JsonObject {
            val response = post(path, body)
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            return response.json().also { contract.response("/web-api/operations/$path", 200, it, "post") }.getValue("data").jsonObject
        }
    }
    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.data(): JsonObject {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        return json().getValue("data").jsonObject
    }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    @Test fun `Webだけで入力準備と確認と保存が完結し契約と再送と結果照会が一致する`() = withWeb { f ->
        val b = Browser(f).also { it.login() }
        val listing = b.get("operations?entity=I-1&revision=${b.revision}").json()
        contract.response("/web-api/operations", 200, listing)
        assertEquals(2, listing.getValue("data").jsonObject.getValue("items").jsonArray.size)
        val input = b.checked("prepare", mapOf("name" to "rename", "target" to "I-1", "revision" to b.revision))
        assertEquals("original", input.getValue("values").jsonObject.text("title"))
        val plan = b.checked("plan", mapOf("inputId" to input.text("inputId"), "params" to mapOf("title" to "updated")))
        assertTrue(plan.getValue("canCommit").jsonPrimitive.boolean)
        assertContains(Files.readString(f.root.resolve("a.yaml")), "original")
        val body = mapOf("planId" to plan.text("planId"), "requestId" to "web-save")
        assertEquals(HttpStatusCode.Forbidden, b.post("commit", body, token = null).status)
        val result = b.checked("commit", body)
        assertEquals("committed", result.text("state"))
        assertEquals(result, b.checked("commit", body))
        val saved = b.get("operations/results/web-save").json()
        contract.response("/web-api/operations/results/{id}", 200, saved)
        assertEquals(result, saved.getValue("data"))
        assertContains(Files.readString(f.root.resolve("a.yaml")), "updated")
        assertEquals(HttpStatusCode.NotFound, f.http.post("/api/v1/operations/commit").status)
        f.access.setPermissions(b.clientId, permission.copy(actions = emptySet()))
        assertEquals(HttpStatusCode.Unauthorized, b.get("operations/results/web-save").status)
    }

    @Test fun `閲覧だけの接続と他人の入力と古い確認を拒否する`() = withWeb { f ->
        val read = Browser(f).also { it.login(ConnectionPermissions(readScope = ConnectionReadScope.WORKSPACE)) }
        assertEquals(0, read.get("operations?entity=I-1&revision=${read.revision}").data().getValue("items").jsonArray.size)
        assertEquals(HttpStatusCode.Forbidden, read.prepare().status)
        val b = Browser(f).also { it.login() }
        val input = b.prepare().data()
        val other = Browser(f).also { it.login() }
        assertEquals(HttpStatusCode.Conflict, other.post("plan", mapOf("inputId" to input.text("inputId"), "params" to mapOf("title" to "updated"))).status)
        val plan = b.checked("plan", mapOf("inputId" to input.text("inputId"), "params" to mapOf("title" to "updated")))
        Files.writeString(f.root.resolve("a.yaml"), "id: I-1\ntitle: original # 外部変更\n")
        assertEquals(HttpStatusCode.Conflict, b.prepare().status)
        assertEquals(HttpStatusCode.Conflict, b.post("plan", mapOf("inputId" to input.text("inputId"), "params" to mapOf("title" to "updated"))).status)
        val result = b.checked("commit", mapOf("planId" to plan.text("planId"), "requestId" to "stale"))
        assertEquals("rejected", result.text("state"))
        assertContains(Files.readString(f.root.resolve("a.yaml")), "外部変更")
    }

    @Test fun `未保存の入力を上書きせず保存前に拒否する`() = withWeb { f ->
        val b = Browser(f).also { it.login() }
        val input = b.prepare().data()
        val plan = b.checked("plan", mapOf("inputId" to input.text("inputId"), "params" to mapOf("title" to "updated")))
        val doc = f.host.documents.ensureDocument(f.root.resolve("a.yaml"))
        doc.noteSourceInput("id: I-1\ntitle: 未保存")
        assertEquals("rejected", b.checked("commit", mapOf("planId" to plan.text("planId"), "requestId" to "dirty")).text("state"))
        assertTrue(doc.hasPendingSourceInput)
        assertContains(Files.readString(f.root.resolve("a.yaml")), "original")
    }

    @Test fun `変更後に検証エラーが残る操作は確認を見せても確定できない`() = withWeb { f ->
        val b = Browser(f).also { it.login() }
        val input = b.prepare("bad").data()
        val plan = b.checked("plan", mapOf("inputId" to input.text("inputId"), "params" to emptyMap<String, Any?>()))
        assertFalse(plan.getValue("canCommit").jsonPrimitive.boolean)
        assertTrue(plan.getValue("issues").jsonArray.isNotEmpty())
        assertEquals("rejected", b.checked("commit", mapOf("planId" to plan.text("planId"), "requestId" to "invalid")).text("state"))
        assertContains(Files.readString(f.root.resolve("a.yaml")), "original")
    }
}
