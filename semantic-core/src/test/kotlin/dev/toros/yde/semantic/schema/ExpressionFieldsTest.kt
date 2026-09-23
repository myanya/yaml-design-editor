package dev.toros.yde.semantic.schema

import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.pretty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 式を受け取る項目（`expression: true` の印。P31・R31-05）の共通の読み取り: どの項目が式かは項目の定義だけが知り、
 * 読み取りはスキーマの木に沿って文書を辿る1実装（`$ref` で合成した印・入れ子の中も同じ）。読めない値は共通の文面。
 */
class ExpressionFieldsTest {

    private fun tree(): SchemaTree {
        val root = SchemaTreeParser.parseRoot(
            mapOf(
                "type" to "object",
                "fields" to listOf(
                    mapOf("key" to "filePattern", "label" to "置き場所", "type" to "string", "expression" to true),
                    mapOf("key" to "plain", "type" to "string"),
                    mapOf(
                        "key" to "items", "type" to "array",
                        "item" to mapOf("type" to "object", "fields" to listOf(mapOf("key" to "path", "\$ref" to "pathType"))),
                    ),
                ),
            ),
            SchemaKind.DOCUMENT, "t", false,
        )
        val definitions = SchemaTreeParser.parseDefinitions(
            mapOf("pathType" to mapOf("type" to "string", "expression" to true, "label" to "パス")),
            "t",
        )
        return SchemaTree("t", root, definitions, null)
    }

    @Test
    fun `印のある項目の値だけを集め 入れ子と ref で合成した印も同じ`() {
        val doc = YamlIO.parse(
            """
            filePattern: "x/{id}.yaml"
            plain: "{a}"
            items:
              - path: "{id}/{name}"
              - path: { format: "{id}", with: { n: { path: name } } }
            """.trimIndent()
        )
        val values = ExpressionFields.read(doc, tree())
        assertEquals(listOf("filePattern", "items[0].path", "items[1].path"), values.keys.map { it.pretty() })
        val top = values.getValue(parseYPath("filePattern"))
        assertEquals("置き場所", top.label)
        assertEquals(listOf("id"), top.reads)
        assertTrue(top.isString)
        // $ref の定義の側の印とラベルが効く
        val nested = values.getValue(parseYPath("items[0].path"))
        assertEquals("パス", nested.label)
        assertEquals(listOf("id", "name"), nested.reads)
        val full = values.getValue(parseYPath("items[1].path"))
        assertFalse(full.isString)
        assertEquals(listOf("id", "name"), full.reads)
        assertTrue(ExpressionFields.declares(tree()))
    }

    @Test
    fun `式として読めない値は 項目の名前つきの共通の文面になる`() {
        val doc = YamlIO.parse("filePattern: { format: \"{id}.yaml\", bogus: 1 }\nplain: x\n")
        val result = ExpressionFields.readAll(doc, tree())
        assertTrue(result.values.isEmpty())
        val (path, message) = result.errors.single()
        assertEquals("filePattern", path.pretty())
        assertTrue(message.startsWith("「置き場所」（filePattern）の式が読めません"), message)
        val e = assertFailsWith<IllegalArgumentException> { ExpressionFields.read(doc, tree()) }
        assertEquals(message, e.message)
    }

    @Test
    fun `印の無いスキーマでは文書を辿らない`() {
        val root = SchemaTreeParser.parseRoot(mapOf("type" to "object", "fields" to listOf(mapOf("key" to "a", "type" to "string"))), SchemaKind.DOCUMENT, "u", false)
        val plain = SchemaTree("u", root, emptyMap(), null)
        assertFalse(ExpressionFields.declares(plain))
        assertTrue(ExpressionFields.readAll(YamlIO.parse("a: \"{x}\""), plain).values.isEmpty())
    }
}
