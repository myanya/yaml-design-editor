package dev.toros.yde.server.api

import dev.toros.yde.app.service.*
import dev.toros.yde.server.*
import dev.toros.yde.server.auth.*
import dev.toros.yde.server.transport.*
import dev.toros.yde.workspace.FileSystemDocumentStore
import dev.toros.yde.yaml.model.DocumentId
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class ReadApiTest {
    private class Fixture(val root: Path, val access: AccessFixture, val host: WorkspaceHost, val token: IssuedCredential)
    private fun withApi(responseBytes: Int = 4 * 1024 * 1024, sample: Path? = null, block: suspend ApplicationTestBuilder.(Fixture) -> Unit) = testApplication {
        val root = Files.createTempDirectory("read-api")
        val f = AccessFixture()
        val host = WorkspaceHost()
        try {
            fun write(file: String, text: String) {
                val path = root.resolve(file)
                Files.createDirectories(path.parent)
                Files.writeString(path, text.trimIndent() + "\n")
            }
            if (sample != null) {
                sample.toFile().copyRecursively(root.toFile(), overwrite = true)
            } else {
                write("a.yaml", "id: T-1\ntitle: saved\namount: 10")
                write("b.yaml", "id: T-2\ntitle: SECRET\namount: 20")
                write("_schemas/task.yaml", """
                    id: task
                    name: タスク
                    match: '*.yaml'
                    root:
                      type: object
                      fields:
                        - {key: id, type: id, required: true}
                        - {key: title, type: string, required: true}
                        - {key: amount, type: number}
                    """)
                write("_views/tasks.yaml", """
                    id: tasks
                    name: タスク一覧
                    rows: {idPattern: 'T-*'}
                    columns:
                      - {label: 名前, path: title}
                      - {label: 点数, path: amount}
                    """)
                write("_views/chart.yaml", """
                    id: chart
                    name: 点数の図
                    kind: chart
                    chart: bar
                    view: tasks
                    series: [点数]
                    """)
                write("_views/summary.yaml", """
                    id: summary
                    name: 概要
                    kind: dashboard
                    sections:
                      - {id: all, label: すべて, view: tasks}
                      - {id: graph, label: 点数, view: chart}
                    """)
            }
            host.openWorkspace(root)
            f.workspace = AccessWorkspace("workspace", host.currentStatus().epoch)
            f.enable()
            val permissions = ConnectionPermissions(readable = FileSystemDocumentStore(root).list().map { it.id }.toSet())
            val client = f.client(LocalFeature.API, permissions)
            val token = f.access.issueBearer(client.id)
            application {
                localConnectionGate(f.connections)
                localAuthentication(f.connections, f.access)
                readApi(WorkspaceReads(host, f.grants::get, { "workspace" }), responseBytes)
            }
            block(Fixture(root, f, host, token))
        } finally { f.close(); host.close(); root.toFile().deleteRecursively() }
    }

    private suspend fun ApplicationTestBuilder.get(path: String, token: IssuedCredential? = null): HttpResponse = client.get("/api/v1/$path") {
        header(HttpHeaders.Host, "127.0.0.1:18765")
        token?.let { bearerAuth(it.value) }
    }

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test fun `全ての読取経路と業務ビューの実応答が公開契約に一致する`() = withApi { f ->
        val spec = get("openapi.json", f.token).json()
        assertEquals("3.1.1", spec["openapi"]?.jsonPrimitive?.content)
        val assertions = OpenApiAssertions(spec)
        val paths = listOf("workspace", "documents?limit=1", "document?document=a.yaml", "search?q=saved", "entity?id=T-1",
            "views", "view?id=tasks&limit=1", "view?id=chart", "view?id=summary&date=2026-11-01", "validation?document=a.yaml")
        val revisions = mutableSetOf<String>()
        for (path in paths) {
            val response = get(path, f.token)
            assertEquals(HttpStatusCode.OK, response.status, "$path: ${response.bodyAsText()}")
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            val body = response.json()
            assertions.response("/api/v1/" + path.substringBefore('?'), 200, body)
            revisions += body.getValue("revision").jsonPrimitive.content
        }
        assertEquals(1, revisions.size, "検索・詳細・ビュー・検証の入力の版は同じ")
        val grid = get("view?id=tasks&limit=1", f.token).json().getValue("data").jsonObject.getValue("report").jsonObject
        assertEquals(2, grid.getValue("total").jsonPrimitive.int)
        assertEquals(1, grid.getValue("nextOffset").jsonPrimitive.int)
        val cell = grid.getValue("rows").jsonArray.first().jsonObject.getValue("cells").jsonArray.first().jsonObject
        assertEquals("saved", cell.getValue("value").jsonPrimitive.content)
        assertEquals("a.yaml", cell.getValue("origin").jsonObject.getValue("document").jsonPrimitive.content)
        assertEquals("title", cell.getValue("origin").jsonObject.getValue("path").jsonPrimitive.content)
    }

    @Test fun `未保存編集を公開せず保存版の指定とページ境界を検査する`() = withApi { f ->
        val first = get("document?document=a.yaml", f.token).json()
        f.host.documents.ensureDocument(f.root.resolve("a.yaml")).applySourceText("id: T-1\ntitle: editing\n")
        assertEquals(first, get("document?document=a.yaml", f.token).json())
        Files.writeString(f.root.resolve("a.yaml"), "id: T-1\ntitle: changed\n")
        val revision = first.getValue("revision").jsonPrimitive.content
        assertEquals(HttpStatusCode.Conflict, get("documents?revision=$revision", f.token).status)
        for (path in listOf("documents?limit=0", "documents?limit=201", "documents?offset=-1", "documents?limit=1&limit=2", "workspace?extra=1",
            "document?document=../outside.yaml", "document", "entity", "search?q=", "view?id=summary&date=bad")) {
            val response = get(path, f.token)
            assertEquals(HttpStatusCode.BadRequest, response.status, path)
            OpenApiAssertions(Json.parseToJsonElement(ReadApiContract.text).jsonObject).response("/api/v1/" + path.substringBefore('?'), 400, response.json())
        }
    }

    @Test fun `接続資格と文書権限とAPIスイッチを経路の前で検査する`() = withApi { f ->
        assertEquals(HttpStatusCode.Unauthorized, get("openapi.json").status)
        val scoped = f.access.client(LocalFeature.API, ConnectionPermissions(readable = setOf(DocumentId("a.yaml"))))
        val token = f.access.access.issueBearer(scoped.id)
        assertEquals(HttpStatusCode.OK, get("document?document=a.yaml", token).status)
        for (path in listOf("document?document=b.yaml", "document?document=absent.yaml", "entity?id=T-2", "views", "validation")) {
            val response = get(path, token)
            assertEquals(HttpStatusCode.Forbidden, response.status, path)
            assertFalse(response.bodyAsText().contains("SECRET"))
        }
        assertEquals(0, get("search?q=SECRET", token).json().getValue("data").jsonObject.getValue("items").jsonArray.size)
        val mcp = f.access.access.issueBearer(f.access.client(LocalFeature.MCP).id)
        assertEquals(HttpStatusCode.Unauthorized, get("documents", mcp).status)
        f.access.enable(LocalConnectionSettings(apiEnabled = false, mcpEnabled = true, webEnabled = false))
        assertEquals(HttpStatusCode.NotFound, get("documents", f.token).status)
        assertEquals(HttpStatusCode.NotFound, get("openapi.json").status)
    }

    @Test fun `大きな応答は部分的な成功にせず上限エラーを返す`() = withApi(responseBytes = 100) { f ->
        val response = get("documents", f.token)
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("limit_exceeded", response.json().getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
    }

    @Test fun `注文サンプルの動的な列と導出とダッシュボードと散布図も契約に一致する`() = withApi(sample = Path.of("samples/orders")) { f ->
        val contract = OpenApiAssertions(Json.parseToJsonElement(ReadApiContract.text).jsonObject)
        for (id in listOf("month-by-status", "order-grid", "order-dashboard", "product-scatter-chart")) {
            val response = get("view?id=$id&date=2026-11-01", f.token)
            assertEquals(HttpStatusCode.OK, response.status, "$id: ${response.bodyAsText()}")
            contract.response("/api/v1/view", 200, response.json())
        }
        val response = get("validation?document=orders/ORD-001.yaml", f.token)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        contract.response("/api/v1/validation", 200, response.json())
    }

    @Test fun `存在しない対象と未対応ビューと壊れた定義は明示したエラーになる`() = withApi { f ->
        assertEquals(HttpStatusCode.NotFound, get("entity?id=missing", f.token).status)
        assertEquals(HttpStatusCode.NotFound, get("view?id=missing", f.token).status)
        Files.writeString(f.root.resolve("_views/tasks.yaml"), "id: tasks\nname: 文書\nkind: document\nmatch: '*.yaml'\nsections:\n  - {label: 内容, fields: {}}\n")
        // 他の定義が元の表を参照しないように、ここでは定義1件だけを残す。
        Files.delete(f.root.resolve("_views/chart.yaml"))
        Files.delete(f.root.resolve("_views/summary.yaml"))
        val unsupported = get("view?id=tasks", f.token)
        assertEquals(HttpStatusCode.UnprocessableEntity, unsupported.status, unsupported.bodyAsText())
        assertEquals("unsupported_view", unsupported.json().getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        Files.writeString(f.root.resolve("_views/tasks.yaml"), "id: [\n")
        val broken = get("views", f.token)
        assertEquals(HttpStatusCode.UnprocessableEntity, broken.status)
        assertEquals("invalid_definitions", broken.json().getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
    }
}
