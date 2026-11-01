package dev.toros.yde.server.web

import dev.toros.yde.server.*
import dev.toros.yde.server.api.OpenApiAssertions
import dev.toros.yde.server.auth.WebLoginCode
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class WebHttpTest {
    private suspend fun WebHttpFixture.login(code: WebLoginCode, origin: String? = url, marker: Boolean = true): HttpResponse = http.post("$url/web-api/session/login") {
        origin?.let { header(HttpHeaders.Origin, it) }
        if (marker) header("X-YDE-Login", "1")
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject { put("code", code.value) }.toString())
    }
    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test fun `Web単独で接続して再読込しCSRF付きログアウトでCookieとサーバ資格情報を失効する`() = runBlocking {
        val f = WebHttpFixture()
        try {
            f.start()
            val code = f.access.webLogins.issue(f.register().id)
            assertEquals(HttpStatusCode.Forbidden, f.login(code, origin = null).status)
            assertEquals(HttpStatusCode.Forbidden, f.login(code, origin = "https://example.com").status)
            assertEquals(HttpStatusCode.Forbidden, f.login(code, marker = false).status)
            val login = f.login(code)
            assertEquals(HttpStatusCode.OK, login.status, login.bodyAsText())
            val setCookie = assertNotNull(login.headers[HttpHeaders.SetCookie])
            assertTrue(setCookie.contains("HttpOnly", true)); assertTrue(setCookie.contains("SameSite=Strict")); assertTrue(setCookie.contains("Path=/web-api", true))
            val cookie = setCookie.substringBefore(';')
            val data = login.json()
            val assertions = OpenApiAssertions(Json.parseToJsonElement(requireNotNull(javaClass.getResource("/web-api/openapi.json")).readText()).jsonObject)
            assertions.response("/web-api/session/login", 200, data, "post")
            assertFalse(data.containsKey("token"))
            assertEquals(HttpStatusCode.Unauthorized, f.login(code).status)
            val session = f.http.get("${f.url}/web-api/session") { header(HttpHeaders.Cookie, cookie) }
            assertEquals(data, session.json())
            assertions.response("/web-api/session", 200, session.json())
            val workspace = f.http.get("${f.url}/web-api/workspace") { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.OK, workspace.status, workspace.bodyAsText())
            assertions.response("/web-api/workspace", 200, workspace.json())
            assertEquals(1, workspace.json().getValue("data").jsonObject.getValue("documents").jsonPrimitive.int)
            assertEquals(HttpStatusCode.NotFound, f.http.get("${f.url}/api/v1/workspace") { header(HttpHeaders.Cookie, cookie) }.status)
            assertEquals(HttpStatusCode.Forbidden, f.http.post("${f.url}/web-api/session/logout") { header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, f.url) }.status)
            val logout = f.http.post("${f.url}/web-api/session/logout") {
                header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, f.url)
                header("X-YDE-CSRF", data.getValue("csrfToken").jsonPrimitive.content)
            }
            assertEquals(HttpStatusCode.NoContent, logout.status, logout.bodyAsText())
            assertTrue(assertNotNull(logout.headers[HttpHeaders.SetCookie]).contains("Max-Age=0", true))
            assertEquals(HttpStatusCode.Unauthorized, f.http.get("${f.url}/web-api/session") { header(HttpHeaders.Cookie, cookie) }.status)
        } finally { withContext(NonCancellable) { f.close() } }
    }

    @Test fun `同梱資源だけを配信しWeb停止で画面と通信を閉じ再開しても旧Cookieは使えない`() = runBlocking {
        val f = WebHttpFixture()
        try {
            f.start()
            val page = f.http.get("${f.url}/app/")
            assertEquals(HttpStatusCode.OK, page.status)
            assertEquals("no-store", page.headers[HttpHeaders.CacheControl])
            assertEquals("no-referrer", page.headers["Referrer-Policy"])
            val html = page.bodyAsText()
            assertFalse(html.contains("__YDE_STYLE_NONCE__"))
            val nonce = Regex("name=\"yde-style-nonce\" content=\"([^\"]+)\"").find(html)!!.groupValues[1]
            assertTrue(assertNotNull(page.headers["Content-Security-Policy"]).contains("'nonce-$nonce'"))
            assertNotEquals(html, f.http.get("${f.url}/app/").bodyAsText())
            val js = Regex("src=\"(/app/assets/[^\"]+\\.js)\"").find(html)!!.groupValues[1]
            assertEquals(HttpStatusCode.OK, f.http.get(f.url + js).status)
            for (path in listOf("/app/a.yaml", "/app/index.html/extra", "/app/assets.txt", "/app/../a.yaml")) {
                assertEquals(HttpStatusCode.NotFound, f.http.get(f.url + path).status, path)
            }
            val cookie = f.login(f.access.webLogins.issue(f.register().id)).headers[HttpHeaders.SetCookie]!!.substringBefore(';')
            f.configure(LocalConnectionSettings(apiEnabled = true, webEnabled = true))
            f.configure(LocalConnectionSettings(apiEnabled = true))
            for (path in listOf("/app/", js, "/web-api/session", "/web-api/workspace")) {
                assertEquals(HttpStatusCode.NotFound, f.http.get(f.url + path) { header(HttpHeaders.Cookie, cookie) }.status)
            }
            f.configure(LocalConnectionSettings(webEnabled = true))
            assertEquals(HttpStatusCode.Unauthorized, f.http.get("${f.url}/web-api/session") { header(HttpHeaders.Cookie, cookie) }.status)
        } finally { withContext(NonCancellable) { f.close() } }
    }
}
