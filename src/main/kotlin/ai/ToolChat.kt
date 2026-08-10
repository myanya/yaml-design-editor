package ai

import kotlinx.serialization.json.*

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

/**
 * トークン消費。inputTokens は総入力（キャッシュ分を含む）、cachedInputTokens はその内数（キャッシュから読まれた分）。
 * プロバイダ間の報告差はパース側で正規化する（Anthropic の input_tokens はキャッシュ分を含まないため合算する。ARCHITECTURE §4.28）。
 */
data class ChatUsage(val inputTokens: Long = 0, val outputTokens: Long = 0, val cachedInputTokens: Long = 0) {
    operator fun plus(other: ChatUsage) =
        ChatUsage(
            inputTokens + other.inputTokens,
            outputTokens + other.outputTokens,
            cachedInputTokens + other.cachedInputTokens,
        )
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
            // AZURE は OpenAI 互換機構（R9-07）のプリセット。接続先の差は AiConnection が吸収する。
            AiProvider.OPENAI, AiProvider.AZURE -> OpenAiToolSession(apiKey, model, system, tools, provider)
            AiProvider.ANTHROPIC -> AnthropicToolSession(apiKey, model, system, tools)
        }
    }
}

private val json = Json { ignoreUnknownKeys = true }

private suspend fun post(uri: String, headers: Map<String, String>, body: JsonObject): Pair<Int, String> =
    AiHttp.post(uri, headers, body.toString())

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
        val (status, responseBody) = post(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to apiKey.trim(), "anthropic-version" to "2023-06-01"),
            buildRequestBody(),
        )
        val turn = parseAnthropicToolResponse(status, responseBody)
        return turn
    }

    /**
     * リクエスト本文の組み立て（prompt caching 対応。R19-07 / ARCHITECTURE §4.28）。
     * ツールループでは system・ツール定義・会話履歴の前方が毎ターン不変のため、キャッシュ切れ目を
     * ①system ②ツール定義の末尾 ③最新メッセージの末尾ブロック（毎ターン前進する切れ目）に置き、
     * 2ターン目以降の再送プレフィクス全体をキャッシュ読取にする。会計は usage の
     * cache_read / cache_creation 正規化（R19-01）で追跡する。
     */
    internal fun buildRequestBody(): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", 16_000)
        putJsonArray("system") {
            addJsonObject {
                put("type", "text")
                put("text", system)
                putJsonObject("cache_control") { put("type", "ephemeral") }
            }
        }
        putJsonArray("tools") {
            tools.forEachIndexed { i, t ->
                addJsonObject {
                    put("name", t.name)
                    put("description", t.description)
                    put("input_schema", t.parameters)
                    if (i == tools.lastIndex) putJsonObject("cache_control") { put("type", "ephemeral") }
                }
            }
        }
        putJsonArray("messages") {
            messages.forEachIndexed { i, msg ->
                add(if (i == messages.lastIndex) cacheMarked(msg) else msg)
            }
        }
    }

    /** メッセージの末尾コンテンツブロックへ cache_control を付けた複製を返す（保持中の履歴は変更しない）。 */
    private fun cacheMarked(message: JsonObject): JsonObject {
        val mark = buildJsonObject { put("type", "ephemeral") }
        val content = message["content"]
        val marked: JsonElement = when (content) {
            is JsonPrimitive -> buildJsonArray {
                addJsonObject {
                    put("type", "text")
                    put("text", content.content)
                    put("cache_control", mark)
                }
            }
            is JsonArray -> buildJsonArray {
                content.forEachIndexed { i, block ->
                    if (i == content.size - 1 && block is JsonObject) add(JsonObject(block + ("cache_control" to mark)))
                    else add(block)
                }
            }
            else -> return message
        }
        return JsonObject(message + ("content" to marked))
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
        // Anthropic の input_tokens はキャッシュ読取・作成分を含まないため、総入力へ正規化する（§4.28）
        val base = usage?.get("input_tokens")?.jsonPrimitive?.longOrNull ?: 0
        val cacheRead = usage?.get("cache_read_input_tokens")?.jsonPrimitive?.longOrNull ?: 0
        val cacheCreation = usage?.get("cache_creation_input_tokens")?.jsonPrimitive?.longOrNull ?: 0
        return ChatTurn(
            assistantText = text,
            toolCalls = calls,
            usage = ChatUsage(
                inputTokens = base + cacheRead + cacheCreation,
                outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.longOrNull ?: 0,
                cachedInputTokens = cacheRead,
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
    private val provider: AiProvider = AiProvider.OPENAI,
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
        val target = AiConnection.responsesTarget(provider, apiKey)
        val (status, responseBody) = post(target.uri, target.headers, body)
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
                // cached_tokens は input_tokens の内数（OpenAI の自動キャッシュ報告）
                cachedInputTokens = usage?.get("input_tokens_details")?.jsonObject
                    ?.get("cached_tokens")?.jsonPrimitive?.longOrNull ?: 0,
            ),
            stopReason = root["status"]?.jsonPrimitive?.contentOrNull,
        )
    }
}
