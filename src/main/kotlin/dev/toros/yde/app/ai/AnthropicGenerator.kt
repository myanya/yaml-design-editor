package dev.toros.yde.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

object AnthropicGenerator {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generate(input: AiGenerationInput): AiGenerated {
        require(input.apiKey.isNotBlank()) { "Anthropic APIキーを入力してください" }
        require(input.model.isNotBlank()) { "モデルを入力してください" }

        val targetSchema = fieldToJsonSchema(input.field, input.schema, candidates = input.idCandidates)
        val responseSchema = responseSchemaOf(targetSchema)
        // Claude の structured outputs は additionalProperties にスキーマを取る map 型を許容しないため、
        // その場合はプロンプト指示によるJSON生成にフォールバックする。
        val structured = !containsMap(input.field, input.schema)
        val userContent =
            if (structured) buildUserInput(input)
            else buildUserInput(input) + buildString {
                appendLine()
                appendLine("次のJSONスキーマに従うJSONオブジェクトのみを出力してください（コードフェンスや説明は不要）:")
                appendLine(responseSchema.toString())
            }
        val body = buildJsonObject {
            put("model", input.model)
            put("max_tokens", 16_000)
            putJsonObject("thinking") { put("type", "adaptive") }
            put("system", SYSTEM_INSTRUCTIONS)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", userContent)
                }
            }
            if (structured) {
                putJsonObject("output_config") {
                    putJsonObject("format") {
                        put("type", "json_schema")
                        put("schema", responseSchema)
                    }
                }
            }
        }
        // フォールバック時のスキーマはプロンプト内（input）に含まれるため、responseSchema は構造化出力時のみ記録する
        val exchange = AiExchange(
            system = SYSTEM_INSTRUCTIONS,
            input = userContent,
            responseSchema = if (structured) responseSchema.toString() else null,
        )
        val (status, responseBody) = try {
            AiHttp.post(
                "https://api.anthropic.com/v1/messages",
                mapOf("x-api-key" to input.apiKey.trim(), "anthropic-version" to "2023-06-01"),
                body.toString(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AiCallException("Anthropic APIへの接続に失敗しました: ${e.message ?: e}", exchange, e)
        }
        val (inputTokens, outputTokens) = parseUsageTokens(responseBody, json)
        val logged = exchange.copy(inputTokens = inputTokens, outputTokens = outputTokens)
        return try {
            val outputText = extractOutputText(status, responseBody)
            AiGenerated(parseGeneratedValue(outputText, json), logged.copy(output = outputText))
        } catch (e: Exception) {
            // 解析できない応答は生の本文ごと実行記録へ残す
            throw AiCallException(e.message ?: e.toString(), logged.copy(output = responseBody), e)
        }
    }

    internal fun parseResponse(statusCode: Int, body: String) =
        parseGeneratedValue(extractOutputText(statusCode, body), json)

    internal fun extractOutputText(statusCode: Int, body: String): String {
        val responseJson = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { error("Anthropic APIからJSONではない応答が返されました（HTTP $statusCode）") }
        if (statusCode !in 200..299) {
            val message = responseJson["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            error(message ?: "Anthropic APIエラー（HTTP $statusCode）")
        }
        when (responseJson["stop_reason"]?.jsonPrimitive?.contentOrNull) {
            "refusal" -> {
                val explanation = responseJson["stop_details"]?.jsonObject?.get("explanation")?.jsonPrimitive?.contentOrNull
                error("モデルが生成を拒否しました${explanation?.let { ": $it" }.orEmpty()}")
            }
            "max_tokens" -> error("出力がトークン上限に達しました。対象を小さくして再試行してください")
        }
        return responseJson["content"]?.jsonArray
            ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
            ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            ?: error("Anthropic APIの応答に生成結果がありません")
    }
}
