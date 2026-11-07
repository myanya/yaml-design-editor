package dev.toros.yde.server.transport

import dev.toros.yde.app.service.ConnectionCapability
import dev.toros.yde.app.service.ConnectionGrant
import dev.toros.yde.server.LocalConnections
import dev.toros.yde.server.LocalFeature
import dev.toros.yde.server.auth.*
import dev.toros.yde.yaml.model.DocumentId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.AttributeKey
import io.ktor.utils.io.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.Base64

internal const val WEB_SESSION_COOKIE = "yde_web_session"
internal const val CSRF_HEADER = "X-YDE-CSRF"
private val identityKey = AttributeKey<LocalIdentity>("LocalIdentity")
private val accessKey = AttributeKey<LocalAccess>("LocalAccess")
private val rejectedKey = AttributeKey<Boolean>("LocalAccessRejected")
internal class McpSessionHooks(val bound: (String) -> Unit, val finish: suspend (Boolean) -> Unit)
internal val McpSessionHooksKey = AttributeKey<McpSessionHooks>("McpSessionHooks")
internal val WebStyleNonceKey = AttributeKey<String>("WebStyleNonce")
private val webNonceRandom = SecureRandom()

internal data class LocalRequestLimits(val bodyBytes: Int = 4 * 1024 * 1024, val headerBytes: Int = 16 * 1024, val bodyTimeoutMillis: Long = 10_000)

/** 機能ゲートの後に組み込む。静的資源とコード交換以外は毎要求認証し、Cookie・Bearer を経路や本文へ置かない。 */
internal fun Application.localAuthentication(connections: LocalConnections, access: LocalAccess, limits: LocalRequestLimits = LocalRequestLimits()) {
    intercept(ApplicationCallPipeline.Plugins) {
        var completed = false
        try {
            val feature = featureForPath(call.request.path()) ?: throw LocalAccessFailure(404, "接続経路が見つかりません")
            val port = connections.status.value.boundPort ?: throw LocalAccessFailure(404, "接続は停止しています")
            checkHeaders(call, port, limits)
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.response.headers.append("Referrer-Policy", "no-referrer")
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            val policy = "default-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'none'; form-action 'self'"
            if (call.request.path() == "/app" || call.request.path().startsWith("/app/")) {
                val nonce = Base64.getEncoder().encodeToString(ByteArray(24).also(webNonceRandom::nextBytes))
                call.attributes.put(WebStyleNonceKey, nonce)
                call.response.headers.append("Content-Security-Policy", "$policy; style-src-elem 'self' 'nonce-$nonce'; style-src-attr 'unsafe-inline'")
                if (call.request.httpMethod !in setOf(HttpMethod.Get, HttpMethod.Head)) throw LocalAccessFailure(405, "この要求方法は使えません")
                proceed()
            } else {
                call.response.headers.append("Content-Security-Policy", policy)
                if (call.request.path() == "/web-api/session/login") {
                    if (call.request.httpMethod != HttpMethod.Post || call.request.headers.getAll(HttpHeaders.Origin) != listOf(origin(port)) ||
                        call.request.contentType().withoutParameters() != ContentType.Application.Json ||
                        call.request.headers.getAll("X-YDE-Login") != listOf("1") || call.request.headers.contains(HttpHeaders.Authorization)) {
                        throw LocalAccessFailure(403, "Web の接続画面から操作してください")
                    }
                    proceed()
                    completed = true
                    return@intercept
                }
                val identity = access.authenticate(feature, credential(call, feature))
                access.check(identity, ConnectionCapability.READ)
                val sessionIds = call.request.headers.getAll("Mcp-Session-Id")
                if (sessionIds != null) {
                    if (feature != LocalFeature.MCP || sessionIds.size != 1) throw LocalAccessFailure(400, "MCP セッションの指定が不正です")
                    access.checkMcpSession(identity, sessionIds.single())
                }
                if (feature == LocalFeature.WEB && call.request.httpMethod !in setOf(HttpMethod.Get, HttpMethod.Head)) {
                    if (call.request.headers.getAll(HttpHeaders.Origin) != listOf(origin(port))) throw LocalAccessFailure(403, "同じ画面から操作してください")
                    access.checkCsrf(identity, single(call, CSRF_HEADER))
                }
                call.attributes.put(identityKey, identity)
                call.attributes.put(accessKey, access)
                call.attributes[LocalRequestKey].authorizeWith { access.check(identity) }
                access.withIdentity(identity) { proceed() }
            }
            completed = true
        } catch (failure: LocalAccessFailure) {
            call.attributes.put(rejectedKey, true)
            if (failure.status == 401 && featureForPath(call.request.path()) != LocalFeature.WEB) {
                call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer realm=\"yde-local\"")
            }
            if (featureForPath(call.request.path()) in setOf(LocalFeature.API, LocalFeature.WEB)) {
                val code = when (failure.status) { 401 -> "unauthorized"; 403 -> "forbidden"; 413 -> "limit_exceeded"; else -> "invalid_request" }
                val body = buildJsonObject { put("error", buildJsonObject { put("code", code); put("message", failure.message.orEmpty()) }) }
                call.respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.fromValue(failure.status))
            } else call.respondText(failure.message.orEmpty(), status = HttpStatusCode.fromValue(failure.status))
            finish()
        } finally {
            // 応答を確定した後の HTTP 取消は MCP セッション終了ではない。エンジンは本文を書き切ってから送信完了（isSent）を記すので、
            // その間に HttpRequestLifecycle が取り消すと、セッション ID を受け取って先へ進んだクライアントのセッションを閉じてしまう。
            // ヘッダを確定した成功の応答は届いたものとして扱う（本文が届かなかったセッションは無操作の期限で閉じる）。
            // SDK は認証で拒否された初期化も保持するため、確定前・拒否された応答は終了口へ戻す。
            call.attributes.getOrNull(McpSessionHooksKey)?.let { hooks ->
                val sent = (call.response.isSent || call.response.isCommitted) &&
                    (call.response.status()?.value ?: 0) in 200..299 && !call.attributes.contains(rejectedKey)
                withContext(NonCancellable) { hooks.finish(completed || sent) }
            }
        }
    }
    receivePipeline.intercept(ApplicationReceivePipeline.Before) { body ->
        if (body is ByteReadChannel) {
            val bytes = withTimeoutOrNull(limits.bodyTimeoutMillis) { body.readRemaining(limits.bodyBytes.toLong() + 1).readByteArray() }
                ?: throw LocalAccessFailure(408, "要求本文の受信が時間内に完了しませんでした")
            if (bytes.size > limits.bodyBytes) throw LocalAccessFailure(413, "要求本文が大きすぎます")
            call.attributes.getOrNull(identityKey)?.let { access.check(it) }
            proceedWith(ByteReadChannel(bytes))
        }
    }
    sendPipeline.intercept(ApplicationSendPipeline.Before) {
        if (!call.attributes.contains(rejectedKey)) call.attributes.getOrNull(identityKey)?.let { identity ->
            access.check(identity)
            if (identity.client.feature == LocalFeature.MCP) {
                call.response.headers["Mcp-Session-Id"]?.let {
                    access.bindMcpSession(identity, it)
                    call.attributes.getOrNull(McpSessionHooksKey)?.bound(it)
                }
                if (call.request.httpMethod == HttpMethod.Delete && (call.response.status()?.value ?: 200) in 200..299) {
                    single(call, "Mcp-Session-Id")?.let { access.endMcpSession(identity, it) }
                }
            }
        }
    }
}

/** 各業務ハンドラは、対象を解決した後とサービスへ渡す直前に必要な能力・文書・操作を照合する。 */
internal fun ApplicationCall.requireLocalAccess(capability: ConnectionCapability, documents: Set<DocumentId> = emptySet(), action: String? = null): ConnectionGrant {
    val identity = authenticatedIdentity()
    return attributes[accessKey].check(identity, capability, documents, action)
}

/** SDK のセッションへ渡す証明は、認証済みの HTTP 属性からのみ取得する。引数からは作らない。 */
internal fun ApplicationCall.authenticatedIdentity(): LocalIdentity =
    attributes.getOrNull(identityKey) ?: throw LocalAccessFailure(401, "接続の認証が必要です")

/** Cookie の配送先と保護属性を固定する。loopback の HTTP 専用なので Secure は付けない。 */
internal fun ApplicationCall.setLocalWebSession(credential: IssuedCredential) {
    require(credential.csrfToken != null) { "Web セッションではありません" }
    response.cookies.append(Cookie(WEB_SESSION_COOKIE, credential.value, path = "/web-api", httpOnly = true,
        extensions = mapOf("SameSite" to "Strict")))
}

/** 一回限りのコード交換後も、Cookie 応答の直前まで同じ証明を検査する。 */
internal suspend fun ApplicationCall.acceptLocalWebSession(access: LocalAccess, credential: IssuedCredential, block: suspend () -> Unit) {
    val identity = access.authenticate(LocalFeature.WEB, credential.value)
    attributes.put(identityKey, identity)
    attributes.put(accessKey, access)
    attributes[LocalRequestKey].authorizeWith { access.check(identity) }
    access.withIdentity(identity) { setLocalWebSession(credential); block() }
}

/** 認証・CSRF 検査済みのログアウトでのみ呼ぶ。失効済み証明で再認証せず、Cookie の解除だけを返す。 */
internal fun ApplicationCall.forgetLocalWebSession() {
    attributes.remove(identityKey)
    attributes.remove(accessKey)
    response.cookies.append(Cookie(WEB_SESSION_COOKIE, "", path = "/web-api", httpOnly = true, maxAge = 0,
        extensions = mapOf("SameSite" to "Strict")))
}

private fun origin(port: Int) = "http://127.0.0.1" + if (port == 80) "" else ":$port"
private fun single(call: ApplicationCall, name: String): String? = call.request.headers.getAll(name)?.singleOrNull()

private fun credential(call: ApplicationCall, feature: LocalFeature): String {
    if (feature == LocalFeature.WEB) {
        if (call.request.headers.contains(HttpHeaders.Authorization)) throw LocalAccessFailure(401, "Web セッションの認証が必要です")
        val values = call.request.headers.getAll(HttpHeaders.Cookie).orEmpty().flatMap { it.split(';') }
            .map { it.trim().split('=', limit = 2) }.filter { it.first() == WEB_SESSION_COOKIE }
        return values.singleOrNull()?.getOrNull(1) ?: throw LocalAccessFailure(401, "Web セッションの認証が必要です")
    }
    val value = single(call, HttpHeaders.Authorization) ?: throw LocalAccessFailure(401, "接続の認証が必要です")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw LocalAccessFailure(401, "Bearer 認証が必要です")
    return value.substring(7)
}

private fun checkHeaders(call: ApplicationCall, port: Int, limits: LocalRequestLimits) {
    val headers = call.request.headers
    if (call.request.uri.length > 4096 || headers.entries().sumOf { (key, values) ->
        key.toByteArray(Charsets.UTF_8).size + values.sumOf { it.toByteArray(Charsets.UTF_8).size }
    } > limits.headerBytes) {
        throw LocalAccessFailure(431, "要求ヘッダーまたは経路が大きすぎます")
    }
    val host = "127.0.0.1:$port"
    if (single(call, HttpHeaders.Host) !in if (port == 80) setOf(host, "127.0.0.1") else setOf(host)) throw LocalAccessFailure(403, "接続先が許可されていません")
    if (headers.names().any { it.equals("Forwarded", true) || it.startsWith("X-Forwarded-", true) }) throw LocalAccessFailure(403, "中継された要求は受け付けません")
    val origins = headers.getAll(HttpHeaders.Origin)
    if (origins != null && origins != listOf(origin(port))) throw LocalAccessFailure(403, "この Origin は許可されていません")
    val sites = headers.getAll("Sec-Fetch-Site")
    if (sites != null && sites.singleOrNull() !in setOf("same-origin", "none")) throw LocalAccessFailure(403, "別のサイトからの要求は受け付けません")
    if (call.request.httpMethod.value in setOf("TRACE", "CONNECT")) throw LocalAccessFailure(405, "この要求方法は使えません")
    if (call.request.queryParameters.names().any { it.lowercase() in setOf("token", "access_token", "authorization", "csrf", "session", "code") }) {
        throw LocalAccessFailure(400, "資格情報を URL に含めないでください")
    }
    val lengths = headers.getAll(HttpHeaders.ContentLength)
    if (lengths != null) {
        val length = lengths.singleOrNull()?.takeIf { it.matches(Regex("[0-9]{1,19}")) }?.toLongOrNull()
            ?: throw LocalAccessFailure(400, "本文の長さが不正です")
        if (headers.contains(HttpHeaders.TransferEncoding)) throw LocalAccessFailure(400, "本文の形式が不正です")
        if (length > limits.bodyBytes) throw LocalAccessFailure(413, "要求本文が大きすぎます")
    }
    if (headers.contains(HttpHeaders.ContentEncoding) && single(call, HttpHeaders.ContentEncoding) != "identity") {
        throw LocalAccessFailure(415, "圧縮された要求本文は受け付けません")
    }
}
