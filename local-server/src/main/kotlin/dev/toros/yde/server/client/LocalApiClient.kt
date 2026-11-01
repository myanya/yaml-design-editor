package dev.toros.yde.server.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*

/** 通信の型を入口へ公開しない、接続 CLI 用の窓口。自動再送・待受起動・ローカル保存は持たない。 */
interface LocalApiConnection : AutoCloseable {
    suspend fun execute(request: LocalApiRequest): Map<String, Any?>
}

enum class LocalApiMethod { GET, POST }
data class LocalApiRequest(
    val method: LocalApiMethod,
    val path: List<String>,
    val query: Map<String, String> = emptyMap(),
    val body: Map<String, Any?>? = null,
)

/** 原本・トークン・通信例外をメッセージに含めない。HTTP の拒否と通信断を区別する。 */
class LocalApiFailure(val status: Int?, message: String) : RuntimeException(message)

class LocalApiClient(
    private val port: Int,
    private val token: String,
    private val timeoutMillis: Long = 30_000,
    private val responseBytes: Int = 4 * 1024 * 1024,
) : LocalApiConnection {
    init {
        require(port in 1..65535) { "ポート番号は 1〜65535 で指定してください" }
        require(token.length in 1..4096 && token.all { it.code in 33..126 }) { "API トークンの形式を確認してください" }
        require(timeoutMillis > 0 && responseBytes in 1..4 * 1024 * 1024) { "通信の上限が不正です" }
    }

    private val http = HttpClient(CIO) {
        followRedirects = false
        expectSuccess = false
        engine {
            proxy = null
            endpoint.connectAttempts = 1
        }
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMillis
            connectTimeoutMillis = minOf(3_000, timeoutMillis)
            socketTimeoutMillis = timeoutMillis
        }
    }

    override suspend fun execute(request: LocalApiRequest): Map<String, Any?> {
        require(request.path.isNotEmpty() && request.path.all { it.matches(Regex("[A-Za-z0-9_-]{1,180}")) }) {
            "API の対象が不正です"
        }
        require(request.method == LocalApiMethod.POST || request.body == null) { "読取に本文は指定できません" }
        // JSON への変換と量の検査を送信前に終える。接続先は指定ポートの loopback のみ。
        val body = request.body?.let { clientJson(it).toString() }
        require(body == null || body.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "要求が大きすぎます。作業を分割してください" }
        try {
            return withTimeout(timeoutMillis) {
                http.prepareRequest {
                    method = if (request.method == LocalApiMethod.GET) HttpMethod.Get else HttpMethod.Post
                    url {
                        protocol = URLProtocol.HTTP
                        host = "127.0.0.1"
                        port = this@LocalApiClient.port
                        path("api", "v1", *request.path.toTypedArray())
                        request.query.forEach { (key, value) -> parameters.append(key, value) }
                    }
                    bearerAuth(token)
                    accept(ContentType.Application.Json)
                    if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
                }.execute { response ->
                    if (response.status.value != 200) throw httpFailure(response.status.value)
                    if (response.contentType()?.match(ContentType.Application.Json) != true) invalidResponse()
                    val bytes = response.bodyAsChannel().readRemaining(responseBytes.toLong() + 1).readByteArray()
                    if (bytes.size > responseBytes) throw LocalApiFailure(null, "API の応答が大きすぎます。対象やページを絞ってください")
                    val json = try { Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) }
                        catch (_: Exception) { invalidResponse() }
                    if (json !is JsonObject || json["data"] !is JsonObject) invalidResponse()
                    json.mapValues { clientValue(it.value) }
                }
            }
        } catch (failure: LocalApiFailure) { throw failure }
        catch (_: TimeoutCancellationException) { throw connectionFailure() }
        catch (failure: CancellationException) { throw failure }
        catch (_: Exception) { throw connectionFailure() }
    }

    override fun close() { http.close() }
}

private fun connectionFailure() = LocalApiFailure(null,
    "API に接続できないか、通信が完了しませんでした。API の稼働状態・ポート・トークンを確認してください。確定の送信後は結果を確認してから同じ ID で再送してください")
private fun invalidResponse(): Nothing = throw LocalApiFailure(null, "API の応答形式を確認できませんでした")
private fun httpFailure(status: Int) = LocalApiFailure(status, "API が要求を拒否しました（HTTP $status）。" + when (status) {
    401 -> "API 用トークンの期限・失効・接続先ワークスペースを確認してください"
    403 -> "接続者の権限・対象範囲・操作名を確認してください"
    404 -> "API の ON/OFF と対象の ID を確認してください"
    409 -> "権限・作業方式・検証結果・未保存の編集・計画の有効性を確認してください"
    413 -> "対象やページを絞り、作業を分割してください"
    422 -> "デスクトップで定義と文書を確認してください"
    429, 503 -> "処理中のため、状態を確認してから再実行してください"
    in 300..399 -> "別の接続先への転送は行いません"
    else -> "要求の形式と API の状態を確認してください"
})

/** 入出力は JSON の値に限定する。YAML のキーの文字列化や未知の型の toString はしない。 */
private fun clientJson(value: Any?, depth: Int = 0): JsonElement {
    require(depth <= 64) { "要求の入れ子が深すぎます" }
    return when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> {
            require(value.toDouble().isFinite()) { "JSON にできない数値です" }
            JsonPrimitive(value)
        }
        is Map<*, *> -> JsonObject(value.entries.associate { (key, item) ->
            require(key is String) { "要求のキーは文字列で指定してください" }
            key to clientJson(item, depth + 1)
        })
        is List<*> -> JsonArray(value.map { clientJson(it, depth + 1) })
        else -> error("JSON にできない値の型です")
    }
}
private fun clientValue(value: JsonElement, depth: Int = 0): Any? {
    if (depth > 128) invalidResponse()
    return when (value) {
        JsonNull -> null
        is JsonObject -> value.mapValues { clientValue(it.value, depth + 1) }
        is JsonArray -> value.map { clientValue(it, depth + 1) }
        is JsonPrimitive -> if (value.isString) value.content else value.booleanOrNull ?: value.longOrNull
            ?: value.doubleOrNull?.takeIf { it.isFinite() } ?: invalidResponse()
    }
}
