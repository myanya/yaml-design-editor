package dev.toros.yde.server.web

import dev.toros.yde.server.transport.WebStyleNonceKey
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** ビルドで列挙した資源だけを classpath から返し、ワークスペースや任意の資源を配信しない。 */
internal fun Application.webAssets() {
    val loader = object {}.javaClass
    val names = requireNotNull(loader.getResourceAsStream("/web-ui/assets.txt")) { "Web の同梱資源がありません" }
        .bufferedReader().use { it.readLines().filter(String::isNotBlank).toSet() }
    routing {
        suspend fun ApplicationCall.asset(path: String) {
            if (path !in names) { respond(HttpStatusCode.NotFound); return }
            val bytes = loader.getResourceAsStream("/web-ui/$path")?.use { it.readBytes() }
                ?: error("Web の同梱資源がありません")
            if (path == "index.html") {
                respondText(bytes.toString(Charsets.UTF_8).replace("__YDE_STYLE_NONCE__", attributes[WebStyleNonceKey]), ContentType.Text.Html)
            } else respondBytes(bytes, ContentType.defaultForFilePath(path))
        }
        get("/app") { call.asset("index.html") }
        get("/app/{path...}") {
            val path = call.parameters.getAll("path").orEmpty().joinToString("/")
            call.asset(if (path in setOf("", "connect")) "index.html" else path)
        }
    }
}
