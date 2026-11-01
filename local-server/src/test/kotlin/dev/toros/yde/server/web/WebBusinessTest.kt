package dev.toros.yde.server.web

import dev.toros.yde.app.service.*
import dev.toros.yde.server.LocalFeature
import dev.toros.yde.server.LocalConnectionSettings
import dev.toros.yde.server.api.OpenApiAssertions
import dev.toros.yde.server.auth.*
import dev.toros.yde.server.protocol.externalJson
import dev.toros.yde.server.transport.*
import dev.toros.yde.yaml.model.DocumentId
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlinx.serialization.json.*
import kotlin.test.*

class WebBusinessTest {
    private class Fixture(val root: Path, val access: LocalAccess, val reads: WorkspaceReads, val http: HttpClient) {
        val origin = "http://127.0.0.1:18765"
    }
    private fun withWeb(sample: String, block: suspend (Fixture) -> Unit) = testApplication {
        val root = Files.createTempDirectory("web-business")
        val connection = AccessFixture()
        val host = WorkspaceHost()
        try {
            Path.of("samples", sample).toFile().copyRecursively(root.toFile(), overwrite = true)
            host.openWorkspace(root)
            connection.workspace = AccessWorkspace("web-test", host.currentStatus().epoch)
            connection.enable(LocalConnectionSettings(webEnabled = true))
            val reads = WorkspaceReads(host, connection.grants::get, { "web-test" })
            application {
                localConnectionGate(connection.connections)
                localAuthentication(connection.connections, connection.access)
                webApi(connection.access, reads)
            }
            val http = createClient { defaultRequest { header(HttpHeaders.Host, "127.0.0.1:18765") } }
            block(Fixture(root, connection.access, reads, http))
        } finally { connection.close(); host.close(); root.toFile().deleteRecursively() }
    }
    private val contract = OpenApiAssertions(Json.parseToJsonElement(requireNotNull(javaClass.getResource("/web-api/openapi.json")).readText()).jsonObject)
    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.report() = getValue("data").jsonObject.getValue("report").jsonObject
    private fun JsonObject.sections() = report().getValue("sections").jsonArray.map { it.jsonObject }
    private fun JsonObject.section(id: String) = sections().single { it.getValue("id").jsonPrimitive.content == id }

    private inner class Browser(val f: Fixture) {
        lateinit var cookie: String
        lateinit var csrf: String
        lateinit var revision: String
        lateinit var caller: OperationCaller
        suspend fun login(permissions: ConnectionPermissions = ConnectionPermissions(readScope = ConnectionReadScope.WORKSPACE)) {
            val client = f.access.register(LocalFeature.WEB, "業務閲覧", permissions)
            caller = OperationCaller("web-test", client.id)
            val code = f.access.webLogins.issue(client.id)
            val response = f.http.post("/web-api/session/login") {
                header(HttpHeaders.Origin, f.origin); header("X-YDE-Login", "1"); contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("code", code.value) }.toString())
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            cookie = response.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
            csrf = response.json().getValue("csrfToken").jsonPrimitive.content
            revision = get("workspace").json().getValue("revision").jsonPrimitive.content
        }
        suspend fun get(path: String) = f.http.get("/web-api/$path") { header(HttpHeaders.Cookie, cookie) }
        suspend fun view(id: String, date: String? = null, inputs: Map<String, String?> = emptyMap(), token: String? = csrf): HttpResponse =
            f.http.post("/web-api/view") {
                header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, f.origin); token?.let { header("X-YDE-CSRF", it) }
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("id", id); put("revision", revision); date?.let { put("date", it) }
                    put("inputs", externalJson(inputs))
                }.toString())
            }
        suspend fun checkedView(id: String, date: String? = null, inputs: Map<String, String?> = emptyMap()): JsonObject {
            val response = view(id, date, inputs)
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            return response.json().also { contract.response("/web-api/view", 200, it, "post") }
        }
    }

    @Test fun `表と集計と図の実応答が同じ保存版の公開契約に一致する`() = withWeb("orders") { f ->
        val b = Browser(f).also { it.login() }
        val catalog = b.get("views?revision=${b.revision}").json()
        contract.response("/web-api/views", 200, catalog)
        val items = catalog.getValue("data").jsonObject.getValue("items").jsonArray.map { it.jsonObject }
        assertEquals("order-dashboard", items.single { it.getValue("default").jsonPrimitive.boolean }.getValue("id").jsonPrimitive.content)
        val representative = setOf("order-grid", "month-by-category", "order-dashboard", "monthly-sales-chart", "product-scatter-chart")
        assertTrue(items.map { it.getValue("id").jsonPrimitive.content }.containsAll(representative))
        for (item in items.filter { it.getValue("id").jsonPrimitive.content in representative }) {
            val id = item.getValue("id").jsonPrimitive.content
            val web = b.checkedView(id, "2026-01-01")
            assertEquals(b.revision, web.getValue("revision").jsonPrimitive.content)
            if (item.getValue("kind").jsonPrimitive.content == "chart") {
                val common = f.reads.read(b.caller, ReadQuery(ReadResource.VIEW, id = id, revision = b.revision)).data
                assertEquals(externalJson(common["report"]), web.report(), id)
            }
        }
    }

    @Test fun `共有入力を共通評価器へ渡し候補と日付とCSRFを検査する`() = withWeb("orders") { f ->
        val b = Browser(f).also { it.login() }
        val before = b.checkedView("order-dashboard", "2026-01-01")
        val filtered = b.checkedView("order-dashboard", "2026-01-01", mapOf("kind" to "corporate"))
        assertTrue(before.section("customers").getValue("count").jsonPrimitive.int > filtered.section("customers").getValue("count").jsonPrimitive.int)
        val common = f.reads.read(b.caller, ReadQuery(ReadResource.VIEW, id = "order-dashboard", date = LocalDate.parse("2026-01-01"),
            revision = b.revision, inputs = mapOf("kind" to "corporate"))).data
        val shared = externalJson(common["report"]).jsonObject.getValue("sections").jsonArray
        assertEquals(shared.map { it.jsonObject.getValue("count") }, filtered.sections().map { it.getValue("count") })
        val dates = b.checkedView("order-dashboard", inputs = mapOf("from" to "2099-01-01"))
        assertEquals(0, dates.section("all").getValue("count").jsonPrimitive.int)
        assertEquals(HttpStatusCode.BadRequest, b.view("order-dashboard", inputs = mapOf("kind" to "未定義")).status)
        assertEquals(HttpStatusCode.BadRequest, b.view("order-dashboard", inputs = mapOf("from" to "昨日")).status)
        assertEquals(HttpStatusCode.BadRequest, b.view("order-dashboard", inputs = mapOf("unknown" to "x")).status)
        assertEquals(HttpStatusCode.BadRequest, b.view("order-grid", inputs = mapOf("kind" to "corporate")).status)
        assertEquals(HttpStatusCode.Forbidden, b.view("order-dashboard", token = null).status)
        assertEquals(HttpStatusCode.NotFound, f.http.get("/api/v1/workspace").status)
    }

    @Test fun `詳細は実際の参照だけをリンクにしてラベルと逆参照を同じ保存版から返す`() = withWeb("orders") { f ->
        val customer = f.root.resolve("customers/CUS-001.yaml")
        Files.writeString(customer, Files.readString(customer).replace("山田 太郎", "CUS-002"))
        val b = Browser(f).also { it.login() }
        val response = b.get("entity?id=CUS-001&revision=${b.revision}")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        contract.response("/web-api/entity", 200, response.json())
        val entity = response.json().getValue("data").jsonObject
        val fields = entity.getValue("sections").jsonArray.flatMap { it.jsonObject.getValue("fields").jsonArray }.map { it.jsonObject }
        assertEquals("個人", fields.single { it["key"] == JsonPrimitive("kind") }.getValue("display").jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), fields.single { it["key"] == JsonPrimitive("name") }.getValue("references"))
        assertTrue(entity.getValue("backlinks").jsonArray.isNotEmpty())
        val order = b.get("entity?id=ORD-001&revision=${b.revision}").json()
        contract.response("/web-api/entity", 200, order)
        val orderFields = order.getValue("data").jsonObject.getValue("sections").jsonArray.flatMap { it.jsonObject.getValue("fields").jsonArray }.map { it.jsonObject }
        val ref = orderFields.single { it["key"] == JsonPrimitive("customerId") }.getValue("references").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("CUS-002"), ref["id"]); assertEquals(JsonPrimitive(true), ref["resolved"])
        Files.writeString(customer, Files.readString(customer) + "\n# 保存版を更新\n")
        val stale = b.get("entity?id=CUS-001&revision=${b.revision}")
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals("stale_revision", stale.json().getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, b.get("entity?id=CUS-001").status)
    }

    @Test fun `基準日と判定保留と定義不正をゼロ件と区別し部分閲覧では派生情報を返さない`() = withWeb("todos") { f ->
        Files.writeString(f.root.resolve(".yde/views/web-pending.yaml"), """
            id: web-pending
            name: 判定の区別
            kind: dashboard
            sections:
              - id: pending
                label: 保留あり
                view: todo-management
                filter:
                  match: [{node: {entity: todo, bind: t}}]
                  check: [{lt: [{of: t, in: {schemaId: todo-detail}, path: due_on}, '${'$'}today'], type: date}]
        """.trimIndent())
        val b = Browser(f).also { it.login() }
        val old = b.checkedView("todo-dashboard", "1900-01-01")
        val future = b.checkedView("todo-dashboard", "2099-01-01")
        assertEquals(0, old.section("overdue").getValue("count").jsonPrimitive.int)
        assertTrue(future.section("overdue").getValue("count").jsonPrimitive.int > 0)
        val pending = b.checkedView("web-pending")
        assertTrue(pending.section("pending").getValue("pending").jsonArray.isNotEmpty())
        val definition = f.root.resolve(".yde/views/web-pending.yaml")
        Files.writeString(definition, Files.readString(definition) + "\n  - {id: missing, label: 定義なし, view: no-such-view}\n")
        b.revision = b.get("workspace").json().getValue("revision").jsonPrimitive.content
        assertEquals(HttpStatusCode.UnprocessableEntity, b.view("web-pending").status)
        val limited = Browser(f).also { it.login(ConnectionPermissions(readable = setOf(DocumentId(".yde/views/todo-dashboard.yaml")))) }
        assertEquals(HttpStatusCode.Forbidden, limited.get("views?revision=${limited.revision}").status)
        assertEquals(HttpStatusCode.Forbidden, limited.view("todo-dashboard").status)
    }
}
