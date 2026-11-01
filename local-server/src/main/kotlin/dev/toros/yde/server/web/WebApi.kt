package dev.toros.yde.server.web

import dev.toros.yde.app.service.*
import dev.toros.yde.server.auth.*
import dev.toros.yde.server.protocol.externalJson
import dev.toros.yde.server.transport.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.time.LocalDate

/** Web 専用の Cookie と CSRF で共通サービスを呼ぶ。公開 API や任意編集へ迂回しない。 */
internal fun Application.webApi(access: LocalAccess, reads: WorkspaceReads) {
    routing {
        route("/web-api") {
            post("/session/login") {
                val text = call.receiveText()
                if (text.toByteArray(Charsets.UTF_8).size > 1024) throw LocalAccessFailure(413, "接続コードの要求が大きすぎます")
                val body = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                val code = (body?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (body?.keys != setOf("code") || code == null || !code.matches(Regex("[A-Za-z0-9_-]{43}"))) {
                    throw LocalAccessFailure(400, "接続コードを確認してください")
                }
                val credential = access.webLogins.exchange(code)
                call.acceptLocalWebSession(access, credential) {
                    call.respondText(externalJson(access.webLogins.session(call.authenticatedIdentity())).toString(), ContentType.Application.Json)
                }
            }
            get("/session") {
                call.respondText(externalJson(access.webLogins.session(call.authenticatedIdentity())).toString(), ContentType.Application.Json)
            }
            post("/session/logout") {
                val identity = call.authenticatedIdentity()
                // ログアウト自身の取消が、Cookie の解除応答を妨げないよう最後まで送る。
                withContext(NonCancellable) {
                    access.webLogins.logout(identity)
                    call.forgetLocalWebSession()
                    call.respond(HttpStatusCode.NoContent)
                }
            }
            get("/workspace") {
                call.respondSavedRead(reads) { call.businessQuery(ReadResource.WORKSPACE) }
            }
            get("/views") { call.respondSavedRead(reads) { call.businessQuery(ReadResource.VIEWS) } }
            get("/entity") { call.respondSavedRead(reads) { call.businessQuery(ReadResource.ENTITY) } }
            // 共有入力は URL へ置かない。読み取りだけだが POST の Origin・CSRF は共通の入口で照合する。
            post("/view") { call.respondSavedRead(reads) { call.businessViewQuery() } }
        }
    }
}

private fun invalidBusinessQuery(): Nothing = throw ReadFailure(ReadFailureCode.INVALID_REQUEST, "要求の項目・値を確認してください")

private fun ApplicationCall.businessQuery(resource: ReadResource): ReadQuery {
    val p = request.queryParameters
    val allowed = when (resource) { ReadResource.WORKSPACE -> emptySet(); ReadResource.ENTITY -> setOf("id", "revision"); else -> setOf("revision", "offset") }
    if (p.names().any { it !in allowed || p.getAll(it)?.size != 1 }) invalidBusinessQuery()
    if (resource == ReadResource.ENTITY && (p["id"] == null || p["revision"] == null)) invalidBusinessQuery()
    val offset = p["offset"]?.let { it.toIntOrNull() ?: invalidBusinessQuery() } ?: 0
    return ReadQuery(resource, id = p["id"], revision = p["revision"], offset = offset, limit = 200, business = true)
}

private suspend fun ApplicationCall.businessViewQuery(): ReadQuery {
    if (!request.queryParameters.isEmpty() || request.contentType().withoutParameters() != ContentType.Application.Json) invalidBusinessQuery()
    val text = receiveText()
    if (text.toByteArray(Charsets.UTF_8).size > 32 * 1024) throw LocalAccessFailure(413, "共有入力が大きすぎます")
    val body = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: invalidBusinessQuery()
    if (body.keys.any { it !in setOf("id", "revision", "date", "inputs", "offset") }) invalidBusinessQuery()
    fun string(name: String) = body[name]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: invalidBusinessQuery() }
    val date = string("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: invalidBusinessQuery() }
    val inputs = body["inputs"]?.let { raw ->
        (raw as? JsonObject)?.mapValues { (_, v) ->
            if (v == JsonNull) null else (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalidBusinessQuery()
        } ?: invalidBusinessQuery()
    }.orEmpty()
    val offset = body["offset"]?.let { (it as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: invalidBusinessQuery() } ?: 0
    return ReadQuery(ReadResource.VIEW, id = string("id") ?: invalidBusinessQuery(), revision = string("revision") ?: invalidBusinessQuery(),
        date = date, inputs = inputs, offset = offset, business = true)
}
