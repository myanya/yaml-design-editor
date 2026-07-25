package ai

import kotlinx.serialization.json.*
import model.YNode

object OpenAiGenerator {
    private val json = Json { ignoreUnknownKeys = true }

    /** OpenAI 公式および OpenAI 互換プリセット（Azure 等）での生成。接続先は AiConnection が解決する（R9-07）。 */
    suspend fun generate(input: AiGenerationInput, provider: AiProvider = AiProvider.OPENAI): YNode {
        require(input.apiKey.isNotBlank()) { "${provider.label} APIキーを入力してください" }
        require(input.model.isNotBlank()) {
            if (provider == AiProvider.AZURE) "デプロイメント名を入力してください" else "モデルを入力してください"
        }

        val targetSchema = fieldToJsonSchema(input.field, input.schema, candidates = input.idCandidates)
        // strict モードは additionalProperties にスキーマを取る map 型を許容しないため、その場合のみ無効化する。
        val strict = !containsMap(input.field, input.schema)
        val body = buildJsonObject {
            put("model", input.model)
            put("store", false)
            put("instructions", SYSTEM_INSTRUCTIONS)
            put("input", buildUserInput(input))
            putJsonObject("text") {
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("name", "yaml_design_value")
                    put("strict", strict)
                    put("schema", responseSchemaOf(targetSchema))
                }
            }
        }
        val target = AiConnection.responsesTarget(provider, input.apiKey)
        val (status, responseBody) = AiHttp.post(target.uri, target.headers, body.toString(), timeoutSeconds = 120)
        return parseResponse(status, responseBody)
    }

    internal fun parseResponse(statusCode: Int, body: String): YNode {
        val responseJson = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { error("OpenAI APIからJSONではない応答が返されました（HTTP $statusCode）") }
        if (statusCode !in 200..299) {
            val message = responseJson["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            error(message ?: "OpenAI APIエラー（HTTP $statusCode）")
        }
        val outputText = responseJson["output_text"]?.jsonPrimitive?.contentOrNull
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
        return parseGeneratedValue(outputText, json)
    }
}
