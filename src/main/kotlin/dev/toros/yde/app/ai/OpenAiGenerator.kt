package dev.toros.yde.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

object OpenAiGenerator {
    private val json = Json { ignoreUnknownKeys = true }

    /** OpenAI 公式および OpenAI 互換プリセット（Azure 等）での生成。接続先は AiConnection が解決する（R9-07）。 */
    suspend fun generate(input: AiGenerationInput, provider: AiProvider = AiProvider.OPENAI): AiGenerated {
        require(input.apiKey.isNotBlank()) { "${provider.label} APIキーを入力してください" }
        require(input.model.isNotBlank()) {
            if (provider == AiProvider.AZURE) "デプロイメント名を入力してください" else "モデルを入力してください"
        }

        val targetSchema = fieldToJsonSchema(input.field, input.schema, candidates = input.idCandidates)
        // strict モードは additionalProperties にスキーマを取る map 型を許容しないため、その場合のみ無効化する。
        val strict = !containsMap(input.field, input.schema)
        val userInput = buildUserInput(input)
        val responseSchema = responseSchemaOf(targetSchema)
        val body = buildJsonObject {
            put("model", input.model)
            put("store", false)
            put("instructions", SYSTEM_INSTRUCTIONS)
            put("input", userInput)
            putJsonObject("text") {
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("name", "yaml_design_value")
                    put("strict", strict)
                    put("schema", responseSchema)
                }
            }
        }
        val exchange = AiExchange(system = SYSTEM_INSTRUCTIONS, input = userInput, responseSchema = responseSchema.toString())
        val target = AiConnection.responsesTarget(provider, input.apiKey)
        val (status, responseBody) = try {
            AiHttp.post(target.uri, target.headers, body.toString(), timeoutSeconds = 120)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AiCallException("${provider.label} APIへの接続に失敗しました: ${e.message ?: e}", exchange, e)
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
            .getOrElse { error("OpenAI APIからJSONではない応答が返されました（HTTP $statusCode）") }
        if (statusCode !in 200..299) {
            val message = responseJson["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            error(message ?: "OpenAI APIエラー（HTTP $statusCode）")
        }
        return responseJson["output_text"]?.jsonPrimitive?.contentOrNull
            ?: responseJson["output"]?.jsonArray
                ?.asSequence()
                ?.flatMap { it.jsonObject["content"]?.jsonArray.orEmpty().asSequence() }
                ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "output_text" }
                ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            ?: responseJson["output"]?.jsonArray
                ?.asSequence()
                ?.flatMap { it.jsonObject["content"]?.jsonArray.orEmpty().asSequence() }
                ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "refusal" }
                ?.jsonObject?.get("refusal")?.jsonPrimitive?.contentOrNull
                ?.let { error("モデルが生成を拒否しました: $it") }
            ?: error("OpenAI APIの応答に生成結果がありません")
    }
}
