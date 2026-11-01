package dev.toros.yde.server.api

import dev.toros.yde.app.service.*
import dev.toros.yde.server.auth.LocalAccessFailure
import dev.toros.yde.server.protocol.*
import dev.toros.yde.server.transport.LocalRequestKey
import dev.toros.yde.server.transport.requireLocalAccess
import dev.toros.yde.workspace.ActionRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** 通信は認証・形式の変換だけを担う。任意編集も作業コピーを通り、適用規則を入口へ持たない。 */
internal fun Application.writeApi(work: WorkspaceWork, operations: WorkspaceOperations) {
    routing { route("/api/v1") {
        post("/works") { call.writeResponse {
            val body = writeBody("title", "mode")
            val mode = when (body.string("mode")) {
                "proposal" -> WorkMode.PROPOSAL
                "direct" -> WorkMode.DIRECT
                else -> invalidWrite()
            }
            val caller = requireLocalAccess(if (mode == WorkMode.DIRECT) ConnectionCapability.COMMIT else ConnectionCapability.PROPOSE).caller
            work.begin(caller, body.string("title"), mode).report()
        } }
        get("/works/{id}") { call.writeResponse { work.result(workCaller(), workId()).report() } }
        post("/works/{id}/changes") { call.writeResponse {
            val caller = workCaller()
            val body = writeBody("changes")
            val changes = inputChanges(body)
            work.stage(caller, workId(), changes).report()
        } }
        post("/works/{id}/validate") { call.writeResponse {
            val caller = workCaller(); writeBody()
            work.validate(caller, workId()).report()
        } }
        post("/works/{id}/submit") { call.writeResponse {
            val caller = requireLocalAccess(ConnectionCapability.PROPOSE).caller; writeBody()
            work.submit(caller, workId()).report()
        } }
        post("/works/{id}/plan") { call.writeResponse {
            val caller = requireLocalAccess(ConnectionCapability.COMMIT).caller; writeBody()
            work.planCommit(caller, workId()).report()
        } }
        post("/works/{id}/commit") { call.writeResponse {
            val caller = requireLocalAccess(ConnectionCapability.COMMIT).caller
            val planId = writeBody("planId").string("planId")
            attributes[LocalRequestKey].finishWrite { work.commit(caller, workId(), planId) }.report()
        } }
        post("/works/{id}/discard") { call.writeResponse {
            val caller = workCaller(); writeBody()
            work.discard(caller, workId()).report()
        } }
        post("/operations/plan") { call.writeResponse {
            val body = writeBody("name", "target", "params")
            val name = body.string("name")
            val caller = requireLocalAccess(ConnectionCapability.APPLY, action = name).caller
            val params = body["params"]?.let { it as? JsonObject ?: invalidWrite() } ?: JsonObject(emptyMap())
            operations.planAction(caller, ActionRequest(name, body["target"]?.let { body.string("target", 1024) },
                params.mapValues { jsonValue(it.value) })).report()
        } }
        post("/operations/commit") { call.writeResponse {
            val caller = requireLocalAccess(ConnectionCapability.APPLY).caller
            val body = writeBody("planId", "requestId")
            val requestId = actionRequestId(body.string("requestId"))
            attributes[LocalRequestKey].finishWrite { operations.commit(caller, body.string("planId"), requestId) }.report()
        } }
        get("/operations/{id}") { call.writeResponse {
            val caller = requireLocalAccess(ConnectionCapability.APPLY).caller
            operations.result(caller, actionRequestId(workId()))?.report()
                ?: throw LocalAccessFailure(404, "要求記録がありません")
        } }
    } }
}

private fun ApplicationCall.workCaller(): OperationCaller {
    val grant = requireLocalAccess(ConnectionCapability.READ)
    if (!grant.allows(ConnectionCapability.PROPOSE) && !grant.allows(ConnectionCapability.COMMIT)) {
        throw LocalAccessFailure(403, "作業が許可されていません")
    }
    return grant.caller
}
private fun ApplicationCall.workId() = parameters["id"]?.takeIf { it.isNotBlank() && it.length <= 180 } ?: invalidWrite()
private suspend fun ApplicationCall.writeBody(vararg keys: String): JsonObject {
    if (!request.contentType().match(ContentType.Application.Json)) throw LocalAccessFailure(415, "application/json を指定してください")
    val body = try { Json.parseToJsonElement(receiveText()) as? JsonObject ?: invalidWrite() }
        catch (failure: LocalAccessFailure) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (_: Exception) { invalidWrite() }
    if (body.keys.any { it !in keys }) invalidWrite()
    jsonValue(body) // 値を既存の変更セットへ渡す前に入れ子の上限を検査する。
    return body
}
private suspend fun ApplicationCall.writeResponse(block: suspend ApplicationCall.() -> Map<String, Any?>) {
    try {
        if (!request.queryParameters.isEmpty()) invalidWrite()
        val data = block()
        val body = externalJson(mapOf("data" to data)).toString()
        if (body.toByteArray(Charsets.UTF_8).size > 4 * 1024 * 1024) throw LocalAccessFailure(413, "応答が大きすぎます。作業を分割してください")
        requireLocalAccess(ConnectionCapability.READ)
        respondText(body, ContentType.Application.Json)
    } catch (failure: CancellationException) { throw failure }
    catch (failure: LocalAccessFailure) { throw failure }
    catch (_: Exception) {
        // 定義・ファイル IO の例外から原本や絶対パスを漏らさない。詳しい結果は検証かデスクトップで確認する。
        respondText(externalJson(mapOf("error" to mapOf("code" to "work_conflict",
            "message" to "作業を進められません。権限・作業方式・検証結果・未保存の編集・計画の有効性を確認してください"))).toString(),
            ContentType.Application.Json, HttpStatusCode.Conflict)
    }
}
