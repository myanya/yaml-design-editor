package ai

import kotlinx.coroutines.future.await
import kotlinx.serialization.json.*
import model.YNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

object OpenAiGenerator {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()

    suspend fun generate(input: AiGenerationInput): YNode {
        require(input.apiKey.isNotBlank()) { "OpenAI APIキーを入力してください" }
        require(input.model.isNotBlank()) { "モデルを入力してください" }

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
        val request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/responses"))
            .timeout(Duration.ofSeconds(120))
            .header("Authorization", "Bearer ${input.apiKey.trim()}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        return parseResponse(response.statusCode(), response.body())
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
