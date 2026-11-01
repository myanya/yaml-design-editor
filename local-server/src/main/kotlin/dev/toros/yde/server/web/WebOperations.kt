package dev.toros.yde.server.web

import dev.toros.yde.app.service.*
import dev.toros.yde.server.auth.LocalAccessFailure
import dev.toros.yde.server.protocol.*
import dev.toros.yde.server.transport.*
import dev.toros.yde.workspace.ActionFailure
import dev.toros.yde.workspace.ActionRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Cookie・Origin・CSRF の内側で定義済み操作だけを公開する。保存は共通の確定窓口へ渡す。 */
internal fun Application.webOperations(reads: WorkspaceReads, operations: WorkspaceOperations) {
    routing { route("/web-api/operations") {
        get {
            call.respondSavedRead(reads) {
                val p = call.request.queryParameters
                if (p.names().any { it !in setOf("entity", "revision", "offset") || p.getAll(it)?.size != 1 }) invalidWrite()
                ReadQuery(ReadResource.ACTIONS, id = p["entity"], business = true, revision = p["revision"] ?: invalidWrite(),
                    offset = p["offset"]?.let { it.toIntOrNull() ?: invalidWrite() } ?: 0, limit = 200)
            }
        }
        post("/prepare") { call.operationResponse {
            val body = operationBody("name", "target", "revision")
            val name = body.string("name")
            val caller = requireLocalAccess(ConnectionCapability.APPLY, action = name).caller
            val input = operations.prepareActionInput(caller, ActionRequest(name, body["target"]?.let { body.string("target", 1024) }), body.string("revision"))
            mapOf("inputId" to input.id, "name" to input.name, "label" to input.label, "target" to input.target,
                "expiresAt" to input.expiresAt.toString(), "fields" to input.fields, "values" to input.values, "changedOnly" to input.changedOnly)
        } }
        post("/plan") { call.operationResponse {
            val caller = requireLocalAccess(ConnectionCapability.APPLY).caller
            val body = operationBody("inputId", "params")
            val params = body["params"] as? JsonObject ?: invalidWrite()
            operations.planActionInput(caller, body.string("inputId"), params.mapValues { jsonValue(it.value) }).report()
        } }
        post("/commit") { call.operationResponse {
            val caller = requireLocalAccess(ConnectionCapability.APPLY).caller
            val body = operationBody("planId", "requestId")
            val requestId = actionRequestId(body.string("requestId"))
            attributes[LocalRequestKey].finishWrite { operations.commit(caller, body.string("planId"), requestId) }.report()
        } }
        get("/results/{id}") { call.operationResponse {
            val caller = requireLocalAccess(ConnectionCapability.APPLY).caller
            operations.result(caller, actionRequestId(parameters["id"] ?: invalidWrite()))?.report()
                ?: throw LocalAccessFailure(404, "要求記録がまだありません。しばらく待って再照会してください")
        } }
    } }
}

private suspend fun ApplicationCall.operationBody(vararg keys: String): JsonObject {
    if (request.contentType().withoutParameters() != ContentType.Application.Json) invalidWrite()
    val text = receiveText()
    if (text.toByteArray(Charsets.UTF_8).size > 128 * 1024) throw LocalAccessFailure(413, "操作の入力が大きすぎます")
    val body = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: invalidWrite()
    if (body.keys.any { it !in keys }) invalidWrite()
    jsonValue(body)
    return body
}

private suspend fun ApplicationCall.operationResponse(block: suspend ApplicationCall.() -> Map<String, Any?>) {
    try {
        if (!request.queryParameters.isEmpty()) invalidWrite()
        requireLocalAccess(ConnectionCapability.READ)
        val response = externalJson(mapOf("data" to block())).toString()
        if (response.toByteArray(Charsets.UTF_8).size > 4 * 1024 * 1024) throw LocalAccessFailure(413, "操作の結果が大きすぎます。デスクトップで確認してください")
        requireLocalAccess(ConnectionCapability.READ)
        respondText(response, ContentType.Application.Json)
    } catch (failure: CancellationException) { throw failure }
    catch (failure: LocalAccessFailure) { throw failure }
    catch (failure: ReadFailure) { respondReadFailure(failure) }
    catch (failure: ActionFailure) {
        operationError("action_input", failure.message.orEmpty().take(1000), HttpStatusCode.UnprocessableEntity)
    } catch (_: Exception) {
        operationError("operation_conflict", "操作を進められません。デスクトップの未保存編集・検証結果・権限を確認し、操作を開き直してください", HttpStatusCode.Conflict)
    }
}
private suspend fun ApplicationCall.operationError(code: String, message: String, status: HttpStatusCode) =
    respondText(externalJson(mapOf("error" to mapOf("code" to code, "message" to message))).toString(), ContentType.Application.Json, status)
