package dev.toros.yde.agent.llm

import dev.toros.yde.app.tools.fieldToJsonSchema
import dev.toros.yde.semantic.schema.FieldHints
import kotlinx.serialization.json.*
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.app.schema.SchemaDef
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiGeneratorTest {

    private fun schemaOf(root: FieldDef, definitions: Map<String, FieldDef> = emptyMap()) =
        SchemaDef(id = "test", name = "テスト", match = "*.yaml", root = root, definitions = definitions, source = Path.of("_schemas/test.yaml"))

    // ---------------------------------------------------------------- fieldToJsonSchema

    @Test
    fun `string field becomes string schema with description`() {
        val field = FieldDef(key = "title", type = FieldType.STRING, hints = FieldHints(description = "タイトル"))
        val json = fieldToJsonSchema(field, schemaOf(field))
        assertEquals("string", json["type"]?.jsonPrimitive?.content)
        assertEquals("タイトル", json["description"]?.jsonPrimitive?.content)
    }

    @Test
    fun `id ref date and text map to string`() {
        for (type in listOf(FieldType.ID, FieldType.REF)) {
            val field = FieldDef(key = "f", type = type)
            val json = fieldToJsonSchema(field, schemaOf(field))
            assertEquals("string", json["type"]?.jsonPrimitive?.content, "type=$type")
        }
        // 組込みの型 text（型の名前。基底の種類は string）も文字列。意味の基底型 date は文字列＋ format で日付と伝える
        val text = FieldDef(key = "f", typeName = "text", ref = "text")
        assertEquals("string", fieldToJsonSchema(text, schemaOf(text))["type"]?.jsonPrimitive?.content)
        val date = FieldDef(key = "f", type = FieldType.DATE)
        val dateJson = fieldToJsonSchema(date, schemaOf(date))
        assertEquals("string", dateJson["type"]?.jsonPrimitive?.content)
        assertEquals("date", dateJson["format"]?.jsonPrimitive?.content)
        assertEquals("date-time", fieldToJsonSchema(FieldDef(key = "f", type = FieldType.DATETIME), schemaOf(date))["format"]?.jsonPrimitive?.content)
    }

    @Test
    fun `enum field lists its values`() {
        val field = FieldDef(key = "status", type = FieldType.ENUM, values = listOf("open", "closed"))
        val json = fieldToJsonSchema(field, schemaOf(field))
        assertEquals("string", json["type"]?.jsonPrimitive?.content)
        assertEquals(listOf("open", "closed"), json["enum"]?.jsonArray?.map { it.jsonPrimitive.content })
    }

    @Test
    fun `object field requires all keys and forbids extra properties`() {
        val field = FieldDef(
            key = "item",
            type = FieldType.OBJECT,
            fields = listOf(
                FieldDef(key = "id", type = FieldType.ID),
                FieldDef(key = "count", type = FieldType.INT),
            ),
        )
        val json = fieldToJsonSchema(field, schemaOf(field))
        assertEquals("object", json["type"]?.jsonPrimitive?.content)
        assertEquals(setOf("id", "count"), json["properties"]?.jsonObject?.keys)
        assertEquals(listOf("id", "count"), json["required"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(false, json["additionalProperties"]?.jsonPrimitive?.boolean)
        assertEquals("integer", json["properties"]?.jsonObject?.get("count")?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `array field nests its item schema`() {
        val field = FieldDef(
            key = "tags",
            type = FieldType.ARRAY,
            item = FieldDef(type = FieldType.ENUM, values = listOf("a", "b")),
        )
        val json = fieldToJsonSchema(field, schemaOf(field))
        assertEquals("array", json["type"]?.jsonPrimitive?.content)
        assertEquals(listOf("a", "b"), json["items"]?.jsonObject?.get("enum")?.jsonArray?.map { it.jsonPrimitive.content })
    }

    @Test
    fun `map field uses value schema as additionalProperties`() {
        val field = FieldDef(key = "attrs", type = FieldType.MAP, value = FieldDef(type = FieldType.NUMBER))
        val json = fieldToJsonSchema(field, schemaOf(field))
        assertEquals("object", json["type"]?.jsonPrimitive?.content)
        assertEquals("number", json["additionalProperties"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `named definition is resolved through ref`() {
        val defs = mapOf("person" to FieldDef(type = FieldType.OBJECT, fields = listOf(FieldDef(key = "name"))))
        val field = FieldDef(key = "owner", ref = "person")
        val json = fieldToJsonSchema(field, schemaOf(field, defs))
        assertEquals("object", json["type"]?.jsonPrimitive?.content)
        assertEquals(setOf("name"), json["properties"]?.jsonObject?.keys)
    }

    @Test
    fun `recursive definition terminates at depth limit`() {
        val defs = mapOf(
            "node" to FieldDef(
                type = FieldType.OBJECT,
                fields = listOf(FieldDef(key = "child", ref = "node")),
            ),
        )
        val field = FieldDef(key = "tree", ref = "node")
        val json = fieldToJsonSchema(field, schemaOf(field, defs))
        assertEquals("object", json["type"]?.jsonPrimitive?.content)
        // 深さ制限で空オブジェクトに畳まれていれば無限再帰していない
        var current = json
        var depth = 0
        while (true) {
            val child = current["properties"]?.jsonObject?.get("child")?.jsonObject ?: break
            current = child
            depth++
        }
        assertTrue(depth in 1..20)
    }

    // ---------------------------------------------------------------- parseResponse

    private fun outputBody(text: String): String = buildJsonObject {
        putJsonArray("output") {
            addJsonObject {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "output_text")
                        put("text", text)
                    }
                }
            }
        }
    }.toString()

    @Test
    fun `parses value from responses output array`() {
        val node = OpenAiGenerator.parseResponse(200, outputBody("""{"value":{"title":"概要","count":3,"done":true}}"""))
        val map = node as YNode.Map
        assertEquals("概要", (map.entries["title"] as YNode.Scalar).value)
        assertEquals(3L, (map.entries["count"] as YNode.Scalar).value)
        assertEquals(true, (map.entries["done"] as YNode.Scalar).value)
    }

    @Test
    fun `parses arrays and nulls`() {
        val node = OpenAiGenerator.parseResponse(200, outputBody("""{"value":[{"a":null},"b"]}"""))
        val seq = node as YNode.Seq
        assertEquals(2, seq.items.size)
        assertNull(((seq.items[0] as YNode.Map).entries["a"] as YNode.Scalar).value)
        assertEquals("b", (seq.items[1] as YNode.Scalar).value)
    }

    @Test
    fun `parses top-level output_text convenience field`() {
        val body = buildJsonObject { put("output_text", """{"value":"plain"}""") }.toString()
        val node = OpenAiGenerator.parseResponse(200, body)
        assertEquals("plain", (node as YNode.Scalar).value)
    }

    @Test
    fun `api error message is surfaced`() {
        val body = """{"error":{"message":"Invalid API key"}}"""
        val e = assertFailsWith<IllegalStateException> { OpenAiGenerator.parseResponse(401, body) }
        assertEquals("Invalid API key", e.message)
    }

    @Test
    fun `refusal is surfaced as error`() {
        val body = buildJsonObject {
            putJsonArray("output") {
                addJsonObject {
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "refusal")
                            put("refusal", "ポリシー違反")
                        }
                    }
                }
            }
        }.toString()
        val e = assertFailsWith<IllegalStateException> { OpenAiGenerator.parseResponse(200, body) }
        assertTrue(e.message.orEmpty().contains("拒否"))
    }

    @Test
    fun `missing value key fails`() {
        assertFailsWith<IllegalStateException> { OpenAiGenerator.parseResponse(200, outputBody("""{"other":1}""")) }
    }

    @Test
    fun `non json body fails with http status in message`() {
        val e = assertFailsWith<IllegalStateException> { OpenAiGenerator.parseResponse(502, "Bad Gateway") }
        assertTrue(e.message.orEmpty().contains("502"))
    }

    @Test
    fun `empty response without output fails`() {
        assertFailsWith<IllegalStateException> { OpenAiGenerator.parseResponse(200, "{}") }
    }
}
