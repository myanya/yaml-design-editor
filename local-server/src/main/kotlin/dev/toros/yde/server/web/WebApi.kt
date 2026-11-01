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
                try {
                    val grant = call.requireLocalAccess(ConnectionCapability.READ)
                    val result = reads.read(grant.caller, ReadQuery(ReadResource.WORKSPACE))
                    call.requireLocalAccess(ConnectionCapability.READ)
                    call.respondText(externalJson(mapOf("workspaceId" to result.workspaceId, "epoch" to result.epoch,
                        "revision" to result.revision, "data" to result.data)).toString(), ContentType.Application.Json)
                } catch (failure: CancellationException) { throw failure }
                catch (failure: LocalAccessFailure) { throw failure }
                catch (_: Exception) { throw LocalAccessFailure(409, "ワークスペースの概要を取得できませんでした。デスクトップの状態を確認して再試行してください") }
            }
        }
    }
}
