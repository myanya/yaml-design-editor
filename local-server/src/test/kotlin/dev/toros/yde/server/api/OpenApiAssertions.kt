package dev.toros.yde.server.api

import kotlinx.serialization.json.*
import kotlin.test.*

/** 公開契約で使う JSON Schema の語彙を実応答へ当てる。未知の語彙は黙って無視しない。 */
internal class OpenApiAssertions(private val specification: JsonObject) {
    fun response(path: String, status: Int, value: JsonElement, method: String = "get") {
        val response = specification.getValue("paths").jsonObject.getValue(path).jsonObject.getValue(method).jsonObject
            .getValue("responses").jsonObject.getValue(status.toString())
        val schema = resolve(response).jsonObject.getValue("content").jsonObject
            .getValue("application/json").jsonObject.getValue("schema")
        check(schema, value, path)
    }

    private fun resolve(raw: JsonElement): JsonElement = raw.jsonObject["\$ref"]?.jsonPrimitive?.content?.let { reference ->
        assertTrue(reference.startsWith("#/"))
        reference.removePrefix("#/").split('/').fold(specification as JsonElement) { node, key -> node.jsonObject.getValue(key) }
    } ?: raw

    private fun check(raw: JsonElement, value: JsonElement, path: String) {
        val schema = raw.jsonObject
        val vocabulary = setOf("\$ref", "anyOf", "oneOf", "type", "properties", "required", "additionalProperties", "items",
            "enum", "minimum", "maximum", "minLength", "maxLength", "pattern", "format", "default", "description")
        assertTrue(schema.keys.all { it in vocabulary }, "未検査の語彙: ${schema.keys - vocabulary}")
        schema["\$ref"]?.jsonPrimitive?.content?.let { reference ->
            assertTrue(reference.startsWith("#/"))
            val target = reference.removePrefix("#/").split('/').fold(specification as JsonElement) { node, key -> node.jsonObject.getValue(key) }
            check(target, value, path)
            return
        }
        for (composition in listOf("anyOf", "oneOf")) schema[composition]?.jsonArray?.let { choices ->
            val results = choices.map { runCatching { check(it, value, path) } }
            val successes = results.count { it.isSuccess }
            assertTrue(if (composition == "oneOf") successes == 1 else successes > 0,
                "$path の $composition に一致しません: ${results.mapNotNull { it.exceptionOrNull()?.message }}")
            return
        }
        when (schema["type"]?.jsonPrimitive?.content) {
            "null" -> assertEquals(JsonNull, value, path)
            "object" -> {
                assertTrue(value is JsonObject, path)
                val properties = schema["properties"]?.jsonObject.orEmpty()
                schema["required"]?.jsonArray?.forEach { assertTrue(it.jsonPrimitive.content in value, "$path に $it が必要です") }
                for ((key, item) in value) {
                    val property = properties[key] ?: schema["additionalProperties"]
                    if (property == JsonPrimitive(false)) fail("$path に未定義の $key があります")
                    if (property is JsonObject) check(property, item, "$path.$key")
                }
            }
            "array" -> { assertTrue(value is JsonArray, path); value.forEachIndexed { i, item -> check(schema.getValue("items"), item, "$path[$i]") } }
            "string" -> {
                assertTrue(value is JsonPrimitive && value.isString, path)
                schema["pattern"]?.let { assertTrue(Regex(it.jsonPrimitive.content).containsMatchIn(value.content), path) }
                schema["minLength"]?.let { assertTrue(value.content.length >= it.jsonPrimitive.int, path) }
                schema["maxLength"]?.let { assertTrue(value.content.length <= it.jsonPrimitive.int, path) }
                if (schema["format"]?.jsonPrimitive?.content == "date") java.time.LocalDate.parse(value.content)
            }
            "integer", "number" -> {
                assertTrue(value is JsonPrimitive && !value.isString && value.doubleOrNull != null, path)
                if (schema["type"]?.jsonPrimitive?.content == "integer") assertNotNull(value.longOrNull, path)
                schema["minimum"]?.let { assertTrue(value.double >= it.jsonPrimitive.double, path) }
                schema["maximum"]?.let { assertTrue(value.double <= it.jsonPrimitive.double, path) }
            }
            "boolean" -> assertTrue(value is JsonPrimitive && value.booleanOrNull != null, path)
            else -> fail("$path の型がありません")
        }
        schema["enum"]?.jsonArray?.let { assertTrue(value in it, "$path の値は列挙の範囲外です: $value") }
    }
}
