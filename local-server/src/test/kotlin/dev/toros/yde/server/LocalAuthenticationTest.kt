package dev.toros.yde.server

import dev.toros.yde.app.service.*
import dev.toros.yde.server.auth.*
import dev.toros.yde.server.transport.*
import dev.toros.yde.yaml.model.DocumentId
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.*
import io.ktor.server.request.receiveText
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.utils.io.ByteReadChannel
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class LocalAuthenticationTest {
    private val touched = AtomicInteger()
    private fun HttpRequestBuilder.local() { header(HttpHeaders.Host, "127.0.0.1:18765") }
    private fun HttpRequestBuilder.bearer(token: IssuedCredential) { local(); bearerAuth(token.value) }
    private fun HttpRequestBuilder.web(token: IssuedCredential) { local(); header(HttpHeaders.Cookie, "$WEB_SESSION_COOKIE=${token.value}") }
    private fun withServer(limits: LocalRequestLimits = LocalRequestLimits(), block: suspend ApplicationTestBuilder.(AccessFixture) -> Unit) = testApplication {
        val f = AccessFixture()
        f.enable()
        application {
            localConnectionGate(f.connections)
            localAuthentication(f.connections, f.access, limits)
            routing {
                get("/app") { call.respondText("ログイン画面の資源") }
                get("/api/v1/read") { call.requireLocalAccess(ConnectionCapability.READ, setOf(f.a)); touched.incrementAndGet(); call.respondText("本文") }
                get("/api/v1/other") { call.requireLocalAccess(ConnectionCapability.READ, setOf(DocumentId("other.yaml"))); touched.incrementAndGet(); call.respondText("非公開") }
                post("/api/v1/propose") {
                    call.requireLocalAccess(ConnectionCapability.PROPOSE, setOf(f.a))
                    val body = call.receiveText()
                    call.requireLocalAccess(ConnectionCapability.PROPOSE, setOf(f.a))
                    touched.incrementAndGet(); call.respondText(body)
                }
                post("/api/v1/apply") {
                    call.requireLocalAccess(ConnectionCapability.APPLY, setOf(f.a), "complete")
                    touched.incrementAndGet(); call.respondText("確定")
                }
                post("/mcp") { call.requireLocalAccess(ConnectionCapability.READ); touched.incrementAndGet(); call.respondText("mcp") }
                get("/web-api/read") { call.requireLocalAccess(ConnectionCapability.READ, setOf(f.a)); touched.incrementAndGet(); call.respondText("web") }
                post("/web-api/apply") {
                    call.requireLocalAccess(ConnectionCapability.APPLY, setOf(f.a), "complete")
                    touched.incrementAndGet(); call.respondText("確定")
                }
                get("/api/v1/changed") {
                    val grant = call.requireLocalAccess(ConnectionCapability.READ)
                    f.grants.remove(grant.caller)
                    call.respondText("失効後に返してはいけない本文")
                }
                post("/api/v1/expired-write") {
                    call.requireLocalAccess(ConnectionCapability.APPLY, setOf(f.a), "complete")
                    f.now = f.now.plusSeconds(9 * 60 * 60)
                    call.attributes[LocalRequestKey].finishWrite { touched.incrementAndGet() }
                    call.respondText("保存")
                }
            }
        }
        try { block(f) } finally { f.close() }
    }

    @Test fun `認証なしと別機能の資格情報を拒否し停止時は404を優先する`() = withServer { f ->
        val api = f.access.issueBearer(f.client(LocalFeature.API).id)
        val mcp = f.access.issueBearer(f.client(LocalFeature.MCP).id)
        val web = f.access.issueWebSession(f.client(LocalFeature.WEB).id)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/read") { local() }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/read") { bearer(mcp) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/mcp") { bearer(api) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/web-api/read") { bearer(api) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/read") { web(web) }.status)
        val empty = f.access.issueBearer(f.client(LocalFeature.API, ConnectionPermissions()).id)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/read") { bearer(empty) }.status)
        assertEquals(0, touched.get())
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/read") { bearer(api) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/mcp") { bearer(mcp) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/web-api/read") { web(web) }.status)
        f.enable(LocalConnectionSettings(webEnabled = true))
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/read") { bearer(api) }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/mcp") { bearer(mcp) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/web-api/read") { web(web) }.status)
        f.enable()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/read") { bearer(api) }.status)
    }

    @Test fun `閲覧から提案や適用へ昇格できず許可文書と操作名を照合する`() = withServer { f ->
        val read = f.access.issueBearer(f.client(LocalFeature.API).id)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/propose") { bearer(read); setBody("変更") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/apply") { bearer(read) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/other") { bearer(read) }.status)
        val proposal = f.access.issueBearer(f.client(LocalFeature.API, ConnectionPermissions(setOf(f.a), proposable = setOf(f.a))).id)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/apply") { bearer(proposal) }.status)
        assertEquals(0, touched.get())
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/propose") { bearer(proposal); setBody("提案") }.status)
        val allowed = f.client(LocalFeature.API, ConnectionPermissions(setOf(f.a), actionable = setOf(f.a), actions = setOf("complete")))
        val action = f.access.issueBearer(allowed.id)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/apply") { bearer(action) }.status)
        f.access.setPermissions(allowed.id, ConnectionPermissions(setOf(f.a), actionable = setOf(f.a), actions = setOf("different")))
        val changed = f.access.issueBearer(allowed.id)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/apply") { bearer(changed) }.status)
        assertEquals(2, touched.get())
    }

    @Test fun `HostとOriginの偽装や中継と別サイトを拒否する`() = withServer { f ->
        val token = f.access.issueBearer(f.client(LocalFeature.API).id)
        for (host in listOf("evil.invalid:18765", "127.0.0.1.evil.invalid:18765", "localhost:18765", "127.0.0.1:18766", "127.0.0.1:18765@evil.invalid")) {
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/read") { header(HttpHeaders.Host, host); bearerAuth(token.value) }.status, host)
        }
        for (origin in listOf("https://127.0.0.1:18765", "http://127.0.0.1:18766", "http://127.0.0.1:18765.evil.invalid", "null", "http://127.0.0.1:18765/")) {
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/read") { bearer(token); header(HttpHeaders.Origin, origin) }.status, origin)
        }
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/read") { bearer(token); header("X-Forwarded-Host", "127.0.0.1:18765") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/read") { bearer(token); header("Sec-Fetch-Site", "same-site") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/read") {
            bearer(token); header(HttpHeaders.Origin, "http://127.0.0.1:18765"); header(HttpHeaders.Origin, "https://evil.invalid")
        }.status)
        assertEquals(0, touched.get())
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/read") { bearer(token); header(HttpHeaders.Origin, "http://127.0.0.1:18765") }.status)
    }

    @Test fun `Webの更新は専用Cookieと同じOriginとCSRFの三つを必要とする`() = withServer { f ->
        val token = f.access.issueWebSession(f.client(LocalFeature.WEB,
            ConnectionPermissions(setOf(f.a), actionable = setOf(f.a), actions = setOf("complete"))).id)
        assertEquals(HttpStatusCode.OK, client.get("/app") { local() }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/web-api/read") { local() }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/web-api/apply") { web(token); header(CSRF_HEADER, token.csrfToken!!) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/web-api/apply") { web(token); header(HttpHeaders.Origin, "http://127.0.0.1:18765") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/web-api/apply") {
            web(token); header(HttpHeaders.Origin, "http://127.0.0.1:18765"); header(CSRF_HEADER, "wrong")
        }.status)
        assertEquals(0, touched.get())
        val response = client.post("/web-api/apply") {
            web(token); header(HttpHeaders.Origin, "http://127.0.0.1:18765"); header(CSRF_HEADER, token.csrfToken!!)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("no-referrer", response.headers["Referrer-Policy"])
        assertEquals(1, touched.get())
    }

    @Test fun `資格情報をURLや曖昧なヘッダーから採用せずエラーにも含めない`() = withServer { f ->
        val token = f.access.issueBearer(f.client(LocalFeature.API).id)
        val response = client.get("/api/v1/read?access_token=${token.value}") { local() }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertFalse(response.bodyAsText().contains(token.value))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/read") { bearer(token); bearerAuth(token.value) }.status)
        val web = f.access.issueWebSession(f.client(LocalFeature.WEB).id)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/web-api/read") { web(web); header(HttpHeaders.Cookie, "$WEB_SESSION_COOKIE=${web.value}") }.status)
        for (path in listOf("/api/v1/../read", "/api/v1/%2e%2e/read", "/api/v1/%2fweb-api", "/api/v1/%5cread", "/api/v1/%252e%252e/read")) {
            assertNull(featureForPath(path))
        }
        assertEquals(0, touched.get())
    }

    @Test fun `既知長と不明長の過大本文と圧縮本文を業務処理の前で拒否する`() = withServer(LocalRequestLimits(bodyBytes = 32)) { f ->
        val token = f.access.issueBearer(f.client(LocalFeature.API, ConnectionPermissions(setOf(f.a), proposable = setOf(f.a))).id)
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/api/v1/propose") { bearer(token); setBody("a".repeat(33)) }.status)
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/api/v1/propose") {
            bearer(token)
            setBody(object : OutgoingContent.ReadChannelContent() { override fun readFrom() = ByteReadChannel("a".repeat(33)) })
        }.status)
        assertEquals(HttpStatusCode.UnsupportedMediaType, client.post("/api/v1/propose") { bearer(token); header(HttpHeaders.ContentEncoding, "gzip"); setBody("a") }.status)
        assertEquals(0, touched.get())
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/propose") { bearer(token); setBody("a".repeat(32)) }.status)
        assertEquals(1, touched.get())
    }

    @Test fun `応答直前にも権限を再確認して失効後の本文を返さない`() = withServer { f ->
        val token = f.access.issueBearer(f.client(LocalFeature.API).id)
        val response = client.get("/api/v1/changed") { bearer(token) }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertFalse(response.bodyAsText().contains("失効後に返してはいけない本文"))
    }

    @Test fun `受付後に期限が切れた要求は取消不能な保存区間へ進めない`() = withServer { f ->
        val token = f.access.issueBearer(f.client(LocalFeature.API,
            ConnectionPermissions(setOf(f.a), actionable = setOf(f.a), actions = setOf("complete"))).id)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/expired-write") { bearer(token) }.status)
        assertEquals(0, touched.get())
    }
}
