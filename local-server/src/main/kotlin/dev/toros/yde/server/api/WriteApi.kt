package dev.toros.yde.server.api

import dev.toros.yde.app.service.*
import dev.toros.yde.server.auth.LocalAccessFailure
import dev.toros.yde.server.transport.LocalRequestKey
import dev.toros.yde.server.transport.requireLocalAccess
import dev.toros.yde.workspace.ActionRequest
import dev.toros.yde.workspace.changeset.ChangeSets
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
            checkChanges(body)
            val changes = try { ChangeSets.parse(body.toString()) } catch (_: Exception) { invalidWrite() }
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
private fun actionRequestId(id: String): String {
    if (!id.matches(Regex("[A-Za-z0-9_-]{1,180}"))) invalidWrite()
    return "action/$id"
}
private fun invalidWrite(): Nothing = throw LocalAccessFailure(400, "要求の JSON 形式・項目を確認してください")
private fun JsonObject.string(key: String, maxLength: Int = 200): String = (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && it.length <= maxLength } ?: invalidWrite()
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
private fun jsonValue(value: JsonElement, depth: Int = 0): Any? {
    if (depth > 64) invalidWrite()
    return when (value) {
        JsonNull -> null
        is JsonObject -> value.mapValues { jsonValue(it.value, depth + 1) }
        is JsonArray -> value.map { jsonValue(it, depth + 1) }
        is JsonPrimitive -> if (value.isString) value.content else value.booleanOrNull ?: value.longOrNull ?: value.doubleOrNull ?: invalidWrite()
    }
}
private fun checkChanges(body: JsonObject) {
    val changes = body["changes"] as? JsonArray ?: invalidWrite()
    if (changes.isEmpty()) invalidWrite()
    for (item in changes) {
        val file = item as? JsonObject ?: invalidWrite()
        if (file.keys.any { it !in setOf("file", "base", "create", "content", "ops") }) invalidWrite()
        file.string("file", 1024)
        file["base"]?.let { file.string("base") }
        val create = file["create"]?.let { (it as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: invalidWrite() } ?: false
        if (create) {
            if (file["content"] == null || file["content"] == JsonNull || "ops" in file || "base" in file) invalidWrite()
        } else {
            if ("content" in file) invalidWrite()
            for (entry in file["ops"] as? JsonArray ?: invalidWrite()) {
                val op = entry as? JsonObject ?: invalidWrite()
                val keys = when (op.string("op")) {
                    "set", "insert" -> setOf("op", "path", "value")
                    "delete" -> setOf("op", "path")
                    "move" -> setOf("op", "from", "to")
                    "rename" -> setOf("op", "path", "to")
                    "reorder" -> setOf("op", "path", "order")
                    else -> invalidWrite()
                }
                if (op.keys != keys) invalidWrite()
                for (key in keys - setOf("op", "value", "order")) {
                    if ((op[key] as? JsonPrimitive)?.isString != true) invalidWrite()
                }
                op["order"]?.let { order ->
                    if (order !is JsonArray || order.any { (it as? JsonPrimitive)?.isString != true }) invalidWrite()
                }
            }
        }
    }
}
private suspend fun ApplicationCall.writeResponse(block: suspend ApplicationCall.() -> Map<String, Any?>) {
    try {
        if (!request.queryParameters.isEmpty()) invalidWrite()
        val data = block()
        val body = apiJson(mapOf("data" to data)).toString()
        if (body.toByteArray(Charsets.UTF_8).size > 4 * 1024 * 1024) throw LocalAccessFailure(413, "応答が大きすぎます。作業を分割してください")
        requireLocalAccess(ConnectionCapability.READ)
        respondText(body, ContentType.Application.Json)
    } catch (failure: CancellationException) { throw failure }
    catch (failure: LocalAccessFailure) { throw failure }
    catch (_: Exception) {
        // 定義・ファイル IO の例外から原本や絶対パスを漏らさない。詳しい結果は検証かデスクトップで確認する。
        respondText(apiJson(mapOf("error" to mapOf("code" to "work_conflict",
            "message" to "作業を進められません。権限・作業方式・検証結果・未保存の編集・計画の有効性を確認してください"))).toString(),
            ContentType.Application.Json, HttpStatusCode.Conflict)
    }
}
