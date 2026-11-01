package dev.toros.yde.server.api

import dev.toros.yde.app.service.*
import dev.toros.yde.server.*
import dev.toros.yde.server.auth.*
import dev.toros.yde.server.transport.*
import dev.toros.yde.yaml.model.DocumentId
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class WriteApiTest {
    private val read = ConnectionPermissions(readScope = ConnectionReadScope.WORKSPACE)
    private val both = read.copy(proposalScope = ConnectionWriteScope.LEDGER, commitScope = ConnectionWriteScope.LEDGER,
        actionScope = ConnectionWriteScope.LEDGER, actions = setOf("rename"))
    private class Fixture(val root: Path, val host: WorkspaceHost, val access: AccessFixture, val work: WorkspaceWork) {
        fun token(p: ConnectionPermissions): IssuedCredential = access.access.issueBearer(access.client(LocalFeature.API, p).id)
        fun saved() = Files.readString(root.resolve("a.yaml"))
    }
    private fun withApi(block: suspend ApplicationTestBuilder.(Fixture) -> Unit) = testApplication {
        val root = Files.createTempDirectory("write-api")
        val host = WorkspaceHost()
        val f = AccessFixture()
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
            f.workspace = AccessWorkspace("workspace", host.currentStatus().epoch)
            f.enable()
            val work = WorkspaceWork(host, f.grants::workGrant, { "workspace" }, directGrants = f.grants::directWorkGrant)
            val operations = WorkspaceOperations(host, f.grants::operationGrant, { "workspace" })
            application {
                localConnectionGate(f.connections); localAuthentication(f.connections, f.access)
                readApi(WorkspaceReads(host, f.grants::get, { "workspace" }))
                writeApi(work, operations)
            }
            block(Fixture(root, host, f, work))
        } finally { f.close(); host.close(); root.toFile().deleteRecursively() }
    }
    private suspend fun ApplicationTestBuilder.post(path: String, token: IssuedCredential, body: String = "{}") =
        client.post("/api/v1/$path") {
            header(HttpHeaders.Host, "127.0.0.1:18765"); bearerAuth(token.value)
            contentType(ContentType.Application.Json); setBody(body)
        }
    private suspend fun ApplicationTestBuilder.get(path: String, token: IssuedCredential) = client.get("/api/v1/$path") {
        header(HttpHeaders.Host, "127.0.0.1:18765"); bearerAuth(token.value)
    }
    private suspend fun HttpResponse.data(): JsonObject {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        return Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("data").jsonObject
    }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private suspend fun ApplicationTestBuilder.begin(token: IssuedCredential, mode: String) =
        post("works", token, """{"title":"タイトル変更","mode":"$mode"}""").data().text("id")
    private fun patch(file: String = "a.yaml", value: String = "updated") =
        """{"changes":[{"file":"$file","ops":[{"op":"set","path":"title","value":"$value"}]}]}"""

    @Test fun `直接確定は差分確認の後に保存し再送しても二重実行しない`() = withApi { f ->
        val token = f.token(read.copy(commitScope = ConnectionWriteScope.LEDGER))
        val id = begin(token, "direct")
        post("works/$id/changes", token, patch()).data()
        val validation = post("works/$id/validate", token).data()
        assertTrue(validation.getValue("valid").jsonPrimitive.boolean)
        assertContains(f.saved(), "original")
        val plan = post("works/$id/plan", token).data()
        val planId = plan.text("planId")
        assertTrue(plan.getValue("canCommit").jsonPrimitive.boolean)
        val result = post("works/$id/commit", token, """{"planId":"$planId"}""").data()
        assertEquals("applied", result.text("state"))
        assertEquals(result, post("works/$id/commit", token, """{"planId":"$planId"}""").data())
        assertEquals(result, get("works/$id", token).data())
        assertContains(f.saved(), "updated")
        assertTrue(f.work.inbox.value.isEmpty())
        val contract = OpenApiAssertions(Json.parseToJsonElement(ReadApiContract.text).jsonObject)
        contract.response("/api/v1/works/{id}/commit", 200, buildJsonObject { put("data", result) }, "post")
        contract.response("/api/v1/works/{id}/plan", 200, buildJsonObject { put("data", plan) }, "post")
    }

    @Test fun `提案は受信箱での承認を待ち両権限があっても外部から承認へ迂回できない`() = withApi { f ->
        val token = f.token(both)
        val id = begin(token, "proposal")
        post("works/$id/changes", token, patch()).data()
        assertEquals("awaiting_approval", post("works/$id/submit", token).data().text("state"))
        assertEquals(1, f.work.inbox.value.size)
        assertContains(f.saved(), "original")
        assertEquals(HttpStatusCode.Conflict, post("works/$id/plan", token).status)
        val review = f.work.review(id)
        assertEquals(HttpStatusCode.Conflict, post("works/$id/commit", token, """{"planId":"${review.id}"}""").status)
        assertEquals(HttpStatusCode.NotFound, post("works/$id/approve", token).status)
        assertEquals(WorkState.APPLIED, f.work.approve(id, review.id).state)
        assertEquals("applied", get("works/$id", token).data().text("state"))
    }

    @Test fun `業務全体の許可で新規文書を作業内で編集してから確定する`() = withApi { f ->
        val token = f.token(both)
        val id = begin(token, "direct")
        post("works/$id/changes", token, """{"changes":[
            {"file":"new.yaml","create":true,"content":{"title":"created"}},
            {"file":"new.yaml","ops":[{"op":"set","path":"title","value":"revised"}]}
        ]}""").data()
        assertFalse(Files.exists(f.root.resolve("new.yaml")))
        val plan = post("works/$id/plan", token).data().text("planId")
        assertEquals("applied", post("works/$id/commit", token, """{"planId":"$plan"}""").data().text("state"))
        assertContains(Files.readString(f.root.resolve("new.yaml")), "revised")
    }

    @Test fun `閲覧と提案と直接確定の権限を区別し他者と定義と範囲外を拒否する`() = withApi { f ->
        val reader = f.token(read)
        assertEquals(HttpStatusCode.Forbidden, post("works", reader, """{"title":"変更","mode":"direct"}""").status)
        val proposal = f.token(read.copy(proposalScope = ConnectionWriteScope.LEDGER))
        assertEquals(HttpStatusCode.Forbidden, post("works", proposal, """{"title":"変更","mode":"direct"}""").status)
        val token = f.token(read.copy(committable = setOf(DocumentId("a.yaml"))))
        val id = begin(token, "direct")
        assertEquals(HttpStatusCode.Forbidden, post("works/$id/submit", token).status)
        val other = f.token(both)
        assertEquals(HttpStatusCode.Conflict, post("works/$id/changes", other, patch()).status)
        for (file in listOf("_schemas/item.yaml", "b.yaml", "../a.yaml")) {
            val response = post("works/$id/changes", token, patch(file))
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertFalse(response.bodyAsText().contains(f.root.toString()))
        }
        val restricted = f.token(ConnectionPermissions(readable = setOf(DocumentId("a.yaml")), committable = setOf(DocumentId("a.yaml"))))
        assertEquals(HttpStatusCode.Conflict, post("works", restricted, """{"title":"閲覧不足","mode":"direct"}""").status)
        assertContains(f.saved(), "original")
    }

    @Test fun `検証エラーと破棄と古い計画は保存できず追加の編集では再計画が要る`() = withApi { f ->
        val token = f.token(both)
        val id = begin(token, "direct")
        post("works/$id/changes", token, """{"changes":[{"file":"a.yaml","ops":[{"op":"delete","path":"title"}]}]}""").data()
        assertFalse(post("works/$id/validate", token).data().getValue("valid").jsonPrimitive.boolean)
        val invalid = post("works/$id/plan", token).data()
        assertFalse(invalid.getValue("canCommit").jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.Conflict, post("works/$id/commit", token, """{"planId":"${invalid.text("planId")}"}""").status)
        post("works/$id/changes", token, """{"changes":[{"file":"a.yaml","ops":[{"op":"insert","path":"title","value":"fixed"}]}]}""").data()
        val plan = post("works/$id/plan", token).data().text("planId")
        post("works/$id/changes", token, patch()).data()
        assertEquals(HttpStatusCode.Conflict, post("works/$id/commit", token, """{"planId":"$plan"}""").status)
        assertEquals("discarded", post("works/$id/discard", token).data().text("state"))
        assertEquals(HttpStatusCode.Conflict, post("works/$id/plan", token).status)
        assertContains(f.saved(), "original")
    }

    @Test fun `定義済み操作は選択した名前だけ計画でき明示確定の再送は同じ結果を返す`() = withApi { f ->
        val token = f.token(both)
        assertEquals("rename", get("actions", token).data().getValue("items").jsonArray.single().jsonObject.text("name"))
        assertEquals(HttpStatusCode.Forbidden, post("operations/plan", token, """{"name":"other","target":"a.yaml"}""").status)
        val plan = post("operations/plan", token, """{"name":"rename","target":"a.yaml"}""").data().text("planId")
        assertContains(f.saved(), "original")
        val body = """{"planId":"$plan","requestId":"rename-1"}"""
        val result = post("operations/commit", token, body).data()
        assertEquals("committed", result.text("state"))
        assertEquals(result, post("operations/commit", token, body).data())
        assertEquals(result, get("operations/rename-1", token).data())
        assertContains(f.saved(), "renamed")
        assertEquals(HttpStatusCode.BadRequest, post("operations/commit", token, """{"planId":"$plan","requestId":"work/other"}""").status)
    }

    @Test fun `不明項目と機能OFFを拒否し無権限の要求は保存しない`() = withApi { f ->
        val token = f.token(both)
        assertEquals(HttpStatusCode.BadRequest, post("works", token, """{"title":"変更","mode":"direct","approve":true}""").status)
        val id = begin(token, "direct")
        assertEquals(HttpStatusCode.BadRequest, post("works/$id/changes", token, patch().replace("\"ops\":", "\"unknown\":true,\"ops\":")).status)
        f.access.enable(LocalConnectionSettings(apiEnabled = false, mcpEnabled = true))
        assertEquals(HttpStatusCode.NotFound, post("works/$id/changes", token, patch()).status)
        assertContains(f.saved(), "original")
    }

    @Test fun `Windowsの大小文字別名と業務文書以外を使って定義や添付を書き換えない`() = withApi { f ->
        val token = f.token(both)
        Files.writeString(f.root.resolve("private.txt"), "title: attachment\n")
        val id = begin(token, "direct")
        assertEquals(HttpStatusCode.Conflict, post("works/$id/changes", token, patch("private.txt")).status)
        // 大小文字を区別するファイルシステムでは別名の実在性がないため、この部分は対象外。
        if (Files.exists(f.root.resolve("_SCHEMAS/item.yaml"))) {
            assertEquals(HttpStatusCode.Conflict, post("works/$id/changes", token, patch("_SCHEMAS/item.yaml")).status)
            assertEquals(HttpStatusCode.Conflict, post("works/$id/changes", token,
                """{"changes":[{"file":"_SCHEMAS/new.yaml","create":true,"content":{"title":"secret"}}]}""").status)
            assertFalse(Files.exists(f.root.resolve("_schemas/new.yaml")))
        }
        assertContains(Files.readString(f.root.resolve("private.txt")), "attachment")
    }
}
