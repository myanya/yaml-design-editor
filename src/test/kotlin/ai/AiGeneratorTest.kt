package ai

import kotlinx.serialization.json.*
import model.YNode
import schema.FieldDef
import schema.FieldType
import schema.RefScope
import schema.SchemaDef
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiGeneratorTest {

    private fun schemaOf(root: FieldDef) =
        SchemaDef(id = "test", name = "テスト", match = "*.yaml", root = root, definitions = emptyMap(), source = Path.of("_schemas/test.yaml"))

    private val candidates = listOf(
        RefCandidate("REQ-001", "requirement", "doc-req"),
        RefCandidate("REQ-002", "requirement", "doc-req"),
        RefCandidate("DES-001", "design", "doc-des"),
        RefCandidate("REQ-001", "requirement", "doc-req-2"), // 重複ID（別文書）
    )

    // ---------------------------------------------------------------- refCandidateIds

    @Test
    fun `unrestricted ref accepts all ids deduplicated and sorted`() {
        val field = FieldDef(key = "r", type = FieldType.REF)
        assertEquals(listOf("DES-001", "REQ-001", "REQ-002"), refCandidateIds(field, candidates))
    }

    @Test
    fun `idPatterns filter candidates`() {
        val field = FieldDef(key = "r", type = FieldType.REF, idPatterns = listOf("REQ-*"))
        assertEquals(listOf("REQ-001", "REQ-002"), refCandidateIds(field, candidates))
    }

    @Test
    fun `schema scope filters candidates`() {
        val field = FieldDef(key = "r", type = FieldType.REF, scope = RefScope(schemaIds = listOf("design")))
        assertEquals(listOf("DES-001"), refCandidateIds(field, candidates))
    }

    @Test
    fun `document scope filters candidates`() {
        val field = FieldDef(key = "r", type = FieldType.REF, scope = RefScope(documentIds = listOf("doc-req-2")))
        assertEquals(listOf("REQ-001"), refCandidateIds(field, candidates))
    }

    // ---------------------------------------------------------------- REF enum in JSON Schema

    @Test
    fun `ref field with candidates becomes enum`() {
        val field = FieldDef(key = "relates", type = FieldType.REF, idPatterns = listOf("REQ-*"))
        val json = fieldToJsonSchema(field, schemaOf(field), candidates = candidates)
        assertEquals("string", json["type"]?.jsonPrimitive?.content)
        assertEquals(listOf("REQ-001", "REQ-002"), json["enum"]?.jsonArray?.map { it.jsonPrimitive.content })
    }

    @Test
    fun `ref field without candidates stays plain string`() {
        val field = FieldDef(key = "relates", type = FieldType.REF)
        val json = fieldToJsonSchema(field, schemaOf(field))
        assertEquals("string", json["type"]?.jsonPrimitive?.content)
        assertNull(json["enum"])
    }

    @Test
    fun `ref enum is omitted when candidates exceed the limit`() {
        val many = (1..REF_ENUM_LIMIT + 1).map { RefCandidate("ID-%04d".format(it), null, null) }
        val field = FieldDef(key = "relates", type = FieldType.REF)
        val json = fieldToJsonSchema(field, schemaOf(field), candidates = many)
        assertNull(json["enum"])
    }

    @Test
    fun `ref enum applies inside arrays`() {
        val field = FieldDef(
            key = "relates",
            type = FieldType.ARRAY,
            item = FieldDef(type = FieldType.REF, idPatterns = listOf("DES-*")),
        )
        val json = fieldToJsonSchema(field, schemaOf(field), candidates = candidates)
        assertEquals(listOf("DES-001"), json["items"]?.jsonObject?.get("enum")?.jsonArray?.map { it.jsonPrimitive.content })
    }

    // ---------------------------------------------------------------- enum cases

    private fun inputObject() = FieldDef(
        key = "input",
        type = FieldType.OBJECT,
        fields = listOf(
            FieldDef(key = "name", type = FieldType.STRING),
            FieldDef(
                key = "type", type = FieldType.ENUM, values = listOf("text", "radio"),
                cases = mapOf(
                    "radio" to listOf(
                        FieldDef(key = "options", type = FieldType.ARRAY, item = FieldDef(type = FieldType.STRING)),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `object with single discriminator becomes anyOf branches`() {
        val field = inputObject()
        val json = fieldToJsonSchema(field, schemaOf(field))
        val branches = json["anyOf"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, branches.size)
        fun branchOf(value: String) = branches.single {
            it["properties"]!!.jsonObject["type"]!!.jsonObject["enum"]!!.jsonArray.map { e -> e.jsonPrimitive.content } == listOf(value)
        }
        assertTrue("options" in branchOf("radio")["properties"]!!.jsonObject)
        assertTrue("options" !in branchOf("text")["properties"]!!.jsonObject)
        assertTrue("options" in branchOf("radio")["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `object with multiple discriminators falls back to union of case fields`() {
        val field = FieldDef(
            key = "o", type = FieldType.OBJECT,
            fields = listOf(
                FieldDef(key = "kind", type = FieldType.ENUM, values = listOf("a"),
                    cases = mapOf("a" to listOf(FieldDef(key = "x", type = FieldType.STRING)))),
                FieldDef(key = "type", type = FieldType.ENUM, values = listOf("b"),
                    cases = mapOf("b" to listOf(FieldDef(key = "y", type = FieldType.STRING)))),
            ),
        )
        val json = fieldToJsonSchema(field, schemaOf(field))
        assertNull(json["anyOf"])
        val properties = json["properties"]!!.jsonObject
        assertTrue("x" in properties && "y" in properties)
    }

    @Test
    fun `containsMap looks into enum cases`() {
        val field = FieldDef(
            key = "o", type = FieldType.OBJECT,
            fields = listOf(
                FieldDef(key = "type", type = FieldType.ENUM, values = listOf("a"),
                    cases = mapOf("a" to listOf(
                        FieldDef(key = "extra", type = FieldType.MAP, value = FieldDef(type = FieldType.STRING)),
                    ))),
            ),
        )
        assertTrue(containsMap(field, schemaOf(field)))
    }

    // ---------------------------------------------------------------- parseGeneratedValue

    @Test
    fun `code fences are stripped before parsing`() {
        val node = parseGeneratedValue("```json\n{\"value\": \"x\"}\n```", Json { ignoreUnknownKeys = true })
        assertEquals("x", (node as YNode.Scalar).value)
    }
}

class AnthropicGeneratorTest {

    private fun body(vararg blocks: JsonObject, stopReason: String = "end_turn", stopDetails: JsonObject? = null): String =
        buildJsonObject {
            put("stop_reason", stopReason)
            stopDetails?.let { put("stop_details", it) }
            putJsonArray("content") { blocks.forEach(::add) }
        }.toString()

    private fun textBlock(text: String) = buildJsonObject { put("type", "text"); put("text", text) }
    private fun thinkingBlock() = buildJsonObject { put("type", "thinking"); put("thinking", "") }

    @Test
    fun `parses value from text block`() {
        val node = AnthropicGenerator.parseResponse(200, body(textBlock("""{"value":{"title":"概要"}}""")))
        assertEquals("概要", ((node as YNode.Map).entries["title"] as YNode.Scalar).value)
    }

    @Test
    fun `skips thinking block before text`() {
        val node = AnthropicGenerator.parseResponse(200, body(thinkingBlock(), textBlock("""{"value": 42}""")))
        assertEquals(42L, (node as YNode.Scalar).value)
    }

    @Test
    fun `api error message is surfaced`() {
        val e = assertFailsWith<IllegalStateException> {
            AnthropicGenerator.parseResponse(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        }
        assertEquals("invalid x-api-key", e.message)
    }

    @Test
    fun `refusal stop reason is surfaced`() {
        val e = assertFailsWith<IllegalStateException> {
            AnthropicGenerator.parseResponse(
                200,
                body(stopReason = "refusal", stopDetails = buildJsonObject { put("explanation", "policy") }),
            )
        }
        assertTrue(e.message.orEmpty().contains("拒否"))
    }

    @Test
    fun `max_tokens stop reason is surfaced`() {
        val e = assertFailsWith<IllegalStateException> {
            AnthropicGenerator.parseResponse(200, body(textBlock("{"), stopReason = "max_tokens"))
        }
        assertTrue(e.message.orEmpty().contains("トークン上限"))
    }

    @Test
    fun `missing text block fails`() {
        assertFailsWith<IllegalStateException> {
            AnthropicGenerator.parseResponse(200, body(thinkingBlock()))
        }
    }

    @Test
    fun `non json body fails with http status in message`() {
        val e = assertFailsWith<IllegalStateException> { AnthropicGenerator.parseResponse(529, "overloaded") }
        assertTrue(e.message.orEmpty().contains("529"))
    }
}
