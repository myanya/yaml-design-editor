package ai

import kotlinx.serialization.json.*
import model.YNode

object AnthropicGenerator {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generate(input: AiGenerationInput): YNode {
        require(input.apiKey.isNotBlank()) { "Anthropic APIキーを入力してください" }
        require(input.model.isNotBlank()) { "モデルを入力してください" }

        val targetSchema = fieldToJsonSchema(input.field, input.schema, candidates = input.idCandidates)
        val responseSchema = responseSchemaOf(targetSchema)
        // Claude の structured outputs は additionalProperties にスキーマを取る map 型を許容しないため、
        // その場合はプロンプト指示によるJSON生成にフォールバックする。
        val structured = !containsMap(input.field, input.schema)
        val body = buildJsonObject {
            put("model", input.model)
            put("max_tokens", 16_000)
            putJsonObject("thinking") { put("type", "adaptive") }
            put("system", SYSTEM_INSTRUCTIONS)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put(
                        "content",
                        if (structured) buildUserInput(input)
                        else buildUserInput(input) + buildString {
                            appendLine()
                            appendLine("次のJSONスキーマに従うJSONオブジェクトのみを出力してください（コードフェンスや説明は不要）:")
                            appendLine(responseSchema.toString())
                        },
                    )
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
        val (status, responseBody) = AiHttp.post(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to input.apiKey.trim(), "anthropic-version" to "2023-06-01"),
            body.toString(),
        )
        return parseResponse(status, responseBody)
    }

    internal fun parseResponse(statusCode: Int, body: String): YNode {
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
        val outputText = responseJson["content"]?.jsonArray
            ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
            ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            ?: error("Anthropic APIの応答に生成結果がありません")
        return parseGeneratedValue(outputText, json)
    }
}
