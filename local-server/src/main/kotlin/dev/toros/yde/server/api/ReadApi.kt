package dev.toros.yde.server.api

import dev.toros.yde.app.service.*
import dev.toros.yde.server.transport.requireLocalAccess
import dev.toros.yde.server.auth.LocalAccessFailure
import dev.toros.yde.yaml.model.DocumentId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal object ReadApiContract {
    val text: String by lazy { requireNotNull(javaClass.getResourceAsStream("/api/openapi.json")).bufferedReader().use { it.readText() } }
}

/** 入口は条件と応答を変換するだけ。権限・保存版・意味の計算は共通の読取サービスが担う。 */
internal fun Application.readApi(reads: WorkspaceReads, responseBytes: Int = 4 * 1024 * 1024) {
    routing {
        route("/api/v1") {
            get("/openapi.json") {
                call.requireLocalAccess(ConnectionCapability.READ)
                if (!call.request.queryParameters.isEmpty()) throw LocalAccessFailure(400, "契約の取得にはパラメーターを指定できません")
                call.respondText(ReadApiContract.text, ContentType.Application.Json)
            }
            for (resource in ReadResource.entries) {
                get("/" + resource.name.lowercase()) {
                    try {
                        val grant = call.requireLocalAccess(ConnectionCapability.READ)
                        val result = reads.read(grant.caller, call.readQuery(resource))
                        val body = apiJson(mapOf("workspaceId" to result.workspaceId, "epoch" to result.epoch,
                            "revision" to result.revision, "data" to result.data)).toString()
                        if (body.toByteArray(Charsets.UTF_8).size > responseBytes) {
                            throw ReadFailure(ReadFailureCode.LIMIT_EXCEEDED, "応答が大きすぎます。対象やページを絞ってください")
                        }
                        call.requireLocalAccess(ConnectionCapability.READ)
                        call.respondText(body, ContentType.Application.Json)
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: LocalAccessFailure) { throw failure }
                    catch (failure: ReadFailure) { call.readFailure(failure) }
                    catch (_: IOException) {
                        call.readFailure(ReadFailure(ReadFailureCode.CHANGED_DURING_READ, "保存内容を読み取れませんでした。再試行してください"))
                    } catch (_: Exception) {
                        // 定義や OS の例外に含まれる原本本文・絶対パスを応答へ流さない。
                        call.readFailure(ReadFailure(ReadFailureCode.INVALID_DEFINITIONS, "読み取りを解決できません。デスクトップで定義と文書を確認してください"))
                    }
                }
            }
        }
    }
}

private fun ApplicationCall.readQuery(resource: ReadResource): ReadQuery {
    val page = setOf(ReadResource.DOCUMENTS, ReadResource.SEARCH, ReadResource.VIEWS, ReadResource.VIEW, ReadResource.VALIDATION, ReadResource.ACTIONS)
    val allowed = buildSet {
        add("revision")
        if (resource in page) addAll(listOf("offset", "limit"))
        if (resource == ReadResource.DOCUMENT || resource == ReadResource.VALIDATION) add("document")
        if (resource == ReadResource.ENTITY || resource == ReadResource.VIEW) add("id")
        if (resource == ReadResource.SEARCH) add("q")
        if (resource == ReadResource.VIEW) add("date")
    }
    val parameters = request.queryParameters
    if (parameters.names().any { it !in allowed || parameters.getAll(it)?.size != 1 }) invalidQuery()
    fun integer(name: String, default: Int) = parameters[name]?.let { value ->
        if (!value.matches(Regex("[0-9]{1,5}"))) invalidQuery()
        value.toIntOrNull() ?: invalidQuery()
    } ?: default
    val date = parameters["date"]?.let {
        if (!it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) invalidQuery()
        try { LocalDate.parse(it) } catch (_: Exception) { invalidQuery() }
    }
    return ReadQuery(resource, parameters["document"]?.let(::DocumentId), parameters["id"], parameters["q"],
        integer("offset", 0), integer("limit", 50), date, parameters["revision"])
}

private fun invalidQuery(): Nothing = throw ReadFailure(ReadFailureCode.INVALID_REQUEST, "要求のパラメーターが不正です")

private suspend fun ApplicationCall.readFailure(failure: ReadFailure) {
    val status = when (failure.code) {
        ReadFailureCode.INVALID_REQUEST -> 400
        ReadFailureCode.FORBIDDEN -> 403
        ReadFailureCode.NOT_FOUND -> 404
        ReadFailureCode.STALE_REVISION, ReadFailureCode.CHANGED_DURING_READ -> 409
        ReadFailureCode.LIMIT_EXCEEDED -> 413
        ReadFailureCode.INVALID_DEFINITIONS, ReadFailureCode.UNSUPPORTED_VIEW -> 422
        ReadFailureCode.BUSY -> 503
    }
    respondText(apiJson(mapOf("error" to mapOf("code" to failure.code.name.lowercase(), "message" to failure.message))).toString(),
        ContentType.Application.Json, HttpStatusCode.fromValue(status))
}

/** YAML の値を JSON の値へ写す。JSON にない非有限数は表記を残し、未知の実装型は漏らさない。 */
internal fun apiJson(value: Any?, depth: Int = 0): JsonElement {
    if (depth > 128) throw ReadFailure(ReadFailureCode.LIMIT_EXCEEDED, "値の入れ子が深すぎます")
    return when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Double -> if (value.isFinite()) JsonPrimitive(value) else JsonPrimitive(value.toString())
        is Float -> if (value.isFinite()) JsonPrimitive(value) else JsonPrimitive(value.toString())
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> require(key is String); key to apiJson(item, depth + 1) })
        is List<*> -> JsonArray(value.map { apiJson(it, depth + 1) })
        else -> error("公開できない値の型です")
    }
}
