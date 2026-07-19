package ai

import kotlinx.coroutines.future.await
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * ツール使用（function calling）対応のチャットクライアント（R2-03）。
 * エージェントカーネルはこの抽象だけを知り、プロバイダごとの会話形式の差は各セッション実装が吸収する。
 * テストでは台本を返す疑似実装に差し替える。
 */

/** モデルに公開するツール1件。parameters は JSON Schema。 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/** モデルが要求したツール呼び出し1件。 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
)

/** ツールの実行結果（モデルへ返す）。 */
data class ToolResult(
    val callId: String,
    val content: String,
)

data class ChatUsage(val inputTokens: Long = 0, val outputTokens: Long = 0) {
    operator fun plus(other: ChatUsage) =
        ChatUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens)
}

/** 1回の応答。toolCalls が空なら会話上の最終応答。 */
data class ChatTurn(
    val assistantText: String?,
    val toolCalls: List<ToolCall>,
    val usage: ChatUsage,
    val stopReason: String?,
)

/**
 * 1 実行分の会話セッション。実装が会話履歴を保持する。
 * 使い方: addUserText → send → （toolCalls があれば）addToolResults → send → …
 */
interface ToolChatSession {
    fun addUserText(text: String)
    fun addToolResults(results: List<ToolResult>)
    suspend fun send(): ChatTurn
}

/** プロバイダごとのセッション生成。 */
object ToolChat {
    fun newSession(provider: AiProvider, apiKey: String, model: String, system: String, tools: List<ToolSpec>): ToolChatSession {
        require(apiKey.isNotBlank()) { "${provider.label} APIキーを設定してください" }
        require(model.isNotBlank()) { "モデルを指定してください" }
        return when (provider) {
            AiProvider.OPENAI -> OpenAiToolSession(apiKey, model, system, tools)
            AiProvider.ANTHROPIC -> AnthropicToolSession(apiKey, model, system, tools)
        }
    }
}

private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
private val json = Json { ignoreUnknownKeys = true }

private suspend fun post(uri: String, headers: Map<String, String>, body: JsonObject): Pair<Int, String> {
    val builder = HttpRequest.newBuilder(URI.create(uri))
        .timeout(Duration.ofSeconds(300))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
    headers.forEach { (k, v) -> builder.header(k, v) }
    val response = httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).await()
    return response.statusCode() to response.body()
}

private fun errorMessageOf(providerLabel: String, statusCode: Int, body: String): String {
    val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
    val message = parsed?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
    // どのプロバイダを呼んだかを常に明示する（キー・モデルの取り違えに気付きやすくする）
    return "$providerLabel API: ${message ?: "HTTP $statusCode"}"
}

// ================================================================ Anthropic Messages API

internal class AnthropicToolSession(
    private val apiKey: String,
    private val model: String,
    private val system: String,
    private val tools: List<ToolSpec>,
) : ToolChatSession {

    /** messages 配列（role/content の生JSON）。assistant 応答は content ブロックをそのまま保持する。 */
    private val messages = mutableListOf<JsonObject>()

    override fun addUserText(text: String) {
        messages += buildJsonObject { put("role", "user"); put("content", text) }
    }

    override fun addToolResults(results: List<ToolResult>) {
        messages += buildJsonObject {
            put("role", "user")
            putJsonArray("content") {
                results.forEach { r ->
                    addJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", r.callId)
                        put("content", r.content)
                    }
                }
            }
        }
    }

    override suspend fun send(): ChatTurn {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 16_000)
            put("system", system)
            putJsonArray("tools") {
                tools.forEach { t ->
                    addJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                        put("input_schema", t.parameters)
                    }
                }
            }
            putJsonArray("messages") { messages.forEach(::add) }
        }
        val (status, responseBody) = post(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to apiKey.trim(), "anthropic-version" to "2023-06-01"),
            body,
        )
        val turn = parseAnthropicToolResponse(status, responseBody)
        return turn
    }

    internal fun parseAnthropicToolResponse(statusCode: Int, body: String): ChatTurn {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { error("Anthropic APIからJSONではない応答が返されました（HTTP $statusCode）") }
        if (statusCode !in 200..299) error(errorMessageOf("Anthropic", statusCode, body))

        val content = root["content"]?.jsonArray ?: JsonArray(emptyList())
        // 応答の content をそのまま会話履歴へ積む（tool_use ブロックを保持するため）
        messages += buildJsonObject { put("role", "assistant"); put("content", content) }

        val text = content.filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
            .joinToString("\n") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            .ifBlank { null }
        val calls = content.filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool_use" }.map { block ->
            val obj = block.jsonObject
            ToolCall(
                id = obj["id"]?.jsonPrimitive?.contentOrNull ?: error("tool_use に id がありません"),
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: error("tool_use に name がありません"),
                arguments = obj["input"] as? JsonObject ?: buildJsonObject {},
            )
        }
        val usage = root["usage"]?.jsonObject
        return ChatTurn(
            assistantText = text,
            toolCalls = calls,
            usage = ChatUsage(
                inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.longOrNull ?: 0,
                outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.longOrNull ?: 0,
            ),
            stopReason = root["stop_reason"]?.jsonPrimitive?.contentOrNull,
        )
    }
}

// ================================================================ OpenAI Responses API

internal class OpenAiToolSession(
    private val apiKey: String,
    private val model: String,
    private val system: String,
    private val tools: List<ToolSpec>,
) : ToolChatSession {

    /** input 配列（メッセージ・function_call・function_call_output の生JSON）。store: false のため全履歴を毎回送る。 */
    private val input = mutableListOf<JsonElement>()

    override fun addUserText(text: String) {
        input += buildJsonObject { put("role", "user"); put("content", text) }
    }

    override fun addToolResults(results: List<ToolResult>) {
        results.forEach { r ->
            input += buildJsonObject {
                put("type", "function_call_output")
                put("call_id", r.callId)
                put("output", r.content)
            }
        }
    }

    override suspend fun send(): ChatTurn {
        val body = buildJsonObject {
            put("model", model)
            put("store", false)
            put("instructions", system)
            putJsonArray("tools") {
                tools.forEach { t ->
                    addJsonObject {
                        put("type", "function")
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", t.parameters)
                    }
                }
            }
            putJsonArray("input") { input.forEach(::add) }
        }
        val (status, responseBody) = post(
            "https://api.openai.com/v1/responses",
            mapOf("Authorization" to "Bearer ${apiKey.trim()}"),
            body,
        )
        return parseOpenAiToolResponse(status, responseBody)
    }

    internal fun parseOpenAiToolResponse(statusCode: Int, body: String): ChatTurn {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { error("OpenAI APIからJSONではない応答が返されました（HTTP $statusCode）") }
        if (statusCode !in 200..299) error(errorMessageOf("OpenAI", statusCode, body))

        val output = root["output"]?.jsonArray ?: JsonArray(emptyList())
        // 応答の output 項目をそのまま input へ積む（function_call を保持するため）
        output.forEach { input += it }

        val text = output.asSequence()
            .filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "message" }
            .flatMap { it.jsonObject["content"]?.jsonArray.orEmpty().asSequence() }
            .filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "output_text" }
            .joinToString("\n") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            .ifBlank { null }
        val calls = output.filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "function_call" }.map { item ->
            val obj = item.jsonObject
            val argsText = obj["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
            ToolCall(
                id = obj["call_id"]?.jsonPrimitive?.contentOrNull ?: error("function_call に call_id がありません"),
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: error("function_call に name がありません"),
                arguments = runCatching { json.parseToJsonElement(argsText).jsonObject }
                    .getOrElse { error("ツール引数のJSONを解析できません: $argsText") },
            )
        }
        val usage = root["usage"]?.jsonObject
        return ChatTurn(
            assistantText = text,
            toolCalls = calls,
            usage = ChatUsage(
                inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.longOrNull ?: 0,
                outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.longOrNull ?: 0,
            ),
            stopReason = root["status"]?.jsonPrimitive?.contentOrNull,
        )
    }
}
