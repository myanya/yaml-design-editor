package dev.toros.yde.server.api

import dev.toros.yde.app.service.*
import dev.toros.yde.server.transport.respondSavedRead
import dev.toros.yde.server.transport.requireLocalAccess
import dev.toros.yde.server.auth.LocalAccessFailure
import dev.toros.yde.yaml.model.DocumentId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.LocalDate

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
                    call.respondSavedRead(reads, responseBytes) { call.readQuery(resource) }
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
