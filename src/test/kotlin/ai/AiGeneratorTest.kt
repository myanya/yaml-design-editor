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

    // ---------------------------------------------------------------- 属性付き参照（R20-03）

    @Test
    fun `attributed ref becomes object with to enum and attribute fields`() {
        val field = FieldDef(
            key = "owner", type = FieldType.REF, idPatterns = listOf("REQ-*"),
            fields = listOf(FieldDef(key = "role", type = FieldType.STRING)),
        )
        val json = fieldToJsonSchema(field, schemaOf(field), candidates = candidates)
        assertEquals("object", json["type"]?.jsonPrimitive?.content)
        val properties = json["properties"]!!.jsonObject
        assertEquals(
            listOf("REQ-001", "REQ-002"),
            properties["to"]?.jsonObject?.get("enum")?.jsonArray?.map { it.jsonPrimitive.content },
        )
        assertEquals("string", properties["role"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(listOf("to", "role"), json["required"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(false, json["additionalProperties"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `attributed ref inside array items also becomes object`() {
        val field = FieldDef(
            key = "actors", type = FieldType.ARRAY,
            item = FieldDef(
                type = FieldType.REF, idPatterns = listOf("DES-*"),
                fields = listOf(FieldDef(key = "role", type = FieldType.STRING)),
            ),
        )
        val json = fieldToJsonSchema(field, schemaOf(field), candidates = candidates)
        val items = json["items"]!!.jsonObject
        assertEquals("object", items["type"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("DES-001"),
            items["properties"]?.jsonObject?.get("to")?.jsonObject?.get("enum")?.jsonArray?.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `attributed ref with map attribute disables structured output`() {
        val field = FieldDef(
            key = "owner", type = FieldType.REF,
            fields = listOf(FieldDef(key = "extra", type = FieldType.MAP, value = FieldDef(type = FieldType.STRING))),
        )
        assertTrue(containsMap(field, schemaOf(field)))
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

    // ---------------------------------------------------------------- reference docs in prompt

    private fun inputWith(refs: List<ReferenceDoc>, context: List<ReferenceDoc> = emptyList()): AiGenerationInput {
        val field = FieldDef(key = "summary", type = FieldType.STRING)
        return AiGenerationInput(
            apiKey = "k", model = "m", instruction = "作成して", field = field, schema = schemaOf(field),
            targetPath = "summary", currentValueYaml = "（未設定）", currentDocumentYaml = "id: DOC-1",
            idCandidates = emptyList(), referenceDocs = refs, contextDocs = context,
        )
    }

    @Test
    fun `reference docs are embedded with their file names`() {
        val prompt = buildUserInput(inputWith(listOf(
            ReferenceDoc("a/doc1.yaml", "id: A-1"),
            ReferenceDoc("b/doc2.yaml", "id: B-1"),
        )))
        assertTrue("<reference-document file=\"a/doc1.yaml\">" in prompt)
        assertTrue("id: A-1" in prompt)
        assertTrue("<reference-document file=\"b/doc2.yaml\">" in prompt)
        assertTrue("id: B-1" in prompt)
    }

    @Test
    fun `no reference section without reference docs`() {
        assertTrue("reference-document" !in buildUserInput(inputWith(emptyList())))
    }

    @Test
    fun `reference docs beyond the total limit are dropped with a note`() {
        val big = "x".repeat(REF_DOC_CHAR_LIMIT)
        val prompt = buildUserInput(inputWith((1..8).map { ReferenceDoc("doc$it.yaml", big) }))
        // 1件 20_000 文字 × 6件で合計上限 120_000 に達し、7件目以降は省略される
        assertTrue("<reference-document file=\"doc6.yaml\">" in prompt)
        assertTrue("doc7.yaml" !in prompt)
        assertTrue("省略" in prompt)
    }

    // ---------------------------------------------------------------- context docs in prompt（R24-02）

    @Test
    fun `context docs are embedded as related documents before reference docs`() {
        val prompt = buildUserInput(inputWith(
            refs = listOf(ReferenceDoc("ref.yaml", "id: R-1")),
            context = listOf(ReferenceDoc("rel.yaml", "id: C-1")),
        ))
        assertTrue("<related-document file=\"rel.yaml\">" in prompt)
        assertTrue("id: C-1" in prompt)
        assertTrue("<reference-document file=\"ref.yaml\">" in prompt)
        // 関連文書（内容の文脈）は参考文書（書き方の手本）より先に置く
        assertTrue(prompt.indexOf("<related-document") < prompt.indexOf("<reference-document"))
    }

    @Test
    fun `no related section without context docs`() {
        assertTrue("related-document" !in buildUserInput(inputWith(listOf(ReferenceDoc("ref.yaml", "id: R-1")))))
    }

    @Test
    fun `context and reference docs share the total character budget`() {
        val big = "x".repeat(REF_DOC_CHAR_LIMIT)
        val prompt = buildUserInput(inputWith(
            refs = listOf(ReferenceDoc("ref.yaml", big)),
            context = (1..6).map { ReferenceDoc("rel$it.yaml", big) },
        ))
        // 関連文書6件（合計 120_000 文字）で共有予算を使い切り、参考文書は省略される
        assertTrue("<related-document file=\"rel6.yaml\">" in prompt)
        assertTrue("<reference-document" !in prompt)
        assertTrue("省略" in prompt)
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
