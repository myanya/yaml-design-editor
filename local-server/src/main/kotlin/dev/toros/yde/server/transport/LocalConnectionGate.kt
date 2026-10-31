package dev.toros.yde.server.transport

import dev.toros.yde.server.LocalConnections
import dev.toros.yde.server.LocalFeature
import dev.toros.yde.server.LocalRequest
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey

internal val LocalRequestKey = AttributeKey<LocalRequest>("LocalRequest")

/** 未登録・停止した機能はルーティング前に 404。Web の二経路は同じスイッチで受付する。 */
internal fun Application.localConnectionGate(connections: LocalConnections) {
    intercept(ApplicationCallPipeline.Plugins) {
        val feature = featureForPath(call.request.path())
        if (feature == null || !connections.withRequest(feature) { request ->
            call.attributes.put(LocalRequestKey, request)
            proceed()
        }) {
            call.respond(HttpStatusCode.NotFound)
            finish()
        }
    }
}

internal fun featureForPath(path: String): LocalFeature? {
    // ルーターと機能判定で異なる場所に解決される表記を受付前に拒否する。
    if (!path.startsWith('/') || path.contains("//") || path.split('/').drop(1).any {
        val part = runCatching { it.decodeURLPart() }.getOrNull()
        part == null || part in setOf(".", "..") || part.any { char -> char in "/\\%" || char.code < 32 }
    }) return null
    fun under(prefix: String) = path == prefix || path.startsWith("$prefix/")
    return when {
        under("/api/v1") -> LocalFeature.API
        under("/mcp") -> LocalFeature.MCP
        under("/app") || under("/web-api") -> LocalFeature.WEB
        else -> null
    }
}
