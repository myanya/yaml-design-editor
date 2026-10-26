package dev.toros.yde.semantic

import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.semantic.schema.QueryNodeKind
import dev.toros.yde.semantic.schema.SchemaTreeParser
import dev.toros.yde.semantic.schema.ValueExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 値の式の「参照を 1 段辿る」読み出しと入れ物（`via`）のテスト。ビューの派生列（旧 computed の lookup）が
 * 「クエリ＋値の式」の形に落ちることを固定する。参照は線として辿り（検査の edge と同じ突き合わせ）、
 * ちょうど 1 本のときだけ相手が決まる。相手の出現は `in` の指定に合うものを定義出現優先で 1 つ。
 */
class SemanticViaTest {

    private fun schema(id: String, yaml: String) = SchemaTreeParser.parse(yaml.trimIndent(), fallbackId = id)

    private val attr = schema(
        "attr",
        """
        id: attr
        root:
          type: object
          fields:
            - key: attrs
              type: array
              item:
                type: object
                fields:
                  - { key: id, type: id }
                  - { key: vtype, type: ref }
                  - { key: tags, type: array, item: { type: ref } }
        """,
    )
    private val vt = schema(
        "vt",
        """
        id: vt
        root:
          type: object
          fields:
            - key: types
              type: array
              item:
                type: object
                fields:
                  - { key: id, type: id }
                  - { key: name, type: string }
                  - { key: maxLength, type: int }
                  - { key: group, type: ref }
        """,
    )
    private val ddl = schema(
        "vt-ddl",
        """
        id: vt-ddl
        root:
          type: object
          fields:
            - key: mappings
              type: array
              item:
                type: object
                fields:
                  - { key: id, type: id }
                  - { key: to, type: string }
        """,
    )

    private val grp = schema(
        "grp",
        """
        id: grp
        root:
          type: object
          fields:
            - key: groups
              type: array
              item:
                type: object
                fields:
                  - { key: id, type: id }
                  - { key: label, type: string }
        """,
    )

    private val attrsDoc = DocumentId("attrs.yaml")
    private val trees = mapOf(
        "attrs.yaml" to YamlIO.parse(
            "attrs:\n  - { id: FLD-TITLE, vtype: VT-TITLE, tags: [VT-TITLE, VT-NOTE] }\n  - { id: FLD-FREE }\n  - { id: FLD-NOTE, vtype: VT-NOTE }",
        ),
        "types.yaml" to YamlIO.parse("types:\n  - { id: VT-TITLE, name: タイトル, maxLength: 100, group: GRP-TEXT }\n  - { id: VT-NOTE, name: メモ }"),
        "ddl-mapping.yaml" to YamlIO.parse("mappings:\n  - { id: VT-TITLE, to: \"varchar({maxLength})\" }"),
        "groups.yaml" to YamlIO.parse("groups:\n  - { id: GRP-TEXT, label: 文字 }"),
    )
    private val snapshot = SemanticEngine.build(
        SemanticInput(
            1,
            listOf(
                SemanticDocument(attrsDoc, trees.getValue("attrs.yaml"), attr, "attr"),
                SemanticDocument(DocumentId("types.yaml"), trees.getValue("types.yaml"), vt, "vt"),
                SemanticDocument(DocumentId("ddl-mapping.yaml"), trees.getValue("ddl-mapping.yaml"), ddl, "vt-ddl"),
                SemanticDocument(DocumentId("groups.yaml"), trees.getValue("groups.yaml"), grp, "grp"),
            ),
        ),
    )

    private fun env(id: String, index: Int): SemanticValues.Environment {
        val path = parseYPath("attrs[$index]")
        return SemanticValues.Environment(
            tree = { d -> trees[d.value] },
            current = SemanticQueryNodeRef(QueryNodeKind.ENTITY, id, null, null, attrsDoc, path),
            currentOccurrence = SemanticExpression.Occurrence(attrsDoc, path),
            graph = snapshot.graph,
        )
    }

    private val derived: ValueExpr = ValueExpr.parseValue(
        mapOf(
            "format" to mapOf("via" to "vtype", "in" to mapOf("schemaId" to "vt-ddl"), "path" to "to"),
            "scopes" to listOf(
                mapOf("via" to "vtype", "in" to mapOf("schemaId" to "vt-ddl")),
                mapOf("via" to "vtype", "in" to mapOf("schemaId" to "vt")),
            ),
        ),
        "value",
    )

    @Test
    fun `参照を辿った相手のひな型を相手の出現の値で穴埋めし出どころを持つ`() {
        val side = assertNotNull(SemanticValues.evaluate(derived, env("FLD-TITLE", 0)))
        assertEquals("varchar(100)", side.text)
        assertEquals("varchar({maxLength})", side.raw)
        // 座標はひな型の場所（参照先の写像行）。穴の入力値は参照先の定義（types.yaml）
        assertEquals(DocumentId("ddl-mapping.yaml"), side.sourceDocument)
        assertEquals(parseYPath("mappings[0].to"), side.sourcePath)
        val input = side.inputs.single()
        assertEquals("maxLength", input.placeholder)
        assertEquals(DocumentId("types.yaml"), input.document)
        assertEquals(parseYPath("types[0].maxLength"), input.path)
        // 読み出しだけでも辿れる
        val name = SemanticValues.evaluate(ValueExpr.parseValue(mapOf("via" to "vtype", "in" to mapOf("schemaId" to "vt"), "path" to "name"), "x"), env("FLD-TITLE", 0))!!
        assertEquals("タイトル", name.text)
    }

    @Test
    fun `参照が無い複数ある相手の出現が無いときは得られず理由が残る`() {
        val none = SemanticValues.evaluateDetailed(derived, env("FLD-FREE", 1))
        assertNull(none.side)
        assertEquals("vtype に参照が書かれていません", none.reason)
        val many = SemanticValues.evaluateDetailed(
            ValueExpr.parseValue(mapOf("via" to "tags", "in" to mapOf("schemaId" to "vt"), "path" to "name"), "x"),
            env("FLD-TITLE", 0),
        )
        assertNull(many.side)
        assertEquals("tags の参照が 1 つに決まりません（2 本）", many.reason)
        val noTarget = SemanticValues.evaluateDetailed(derived, env("FLD-NOTE", 2))
        assertNull(noTarget.side)
        assertEquals("参照先「VT-NOTE」の出現がありません（in の指定に合う文書）", noTarget.reason)
    }

    @Test
    fun `入れ物を書いてもいまの要素が末尾に続く`() {
        // 穴 {vtype} は写像行にも値型の定義にも無く、行の要素（いまの要素）で埋まる
        val expr = ValueExpr.parseValue(
            mapOf(
                "format" to "{name}/{vtype}",
                "scopes" to listOf(mapOf("via" to "vtype", "in" to mapOf("schemaId" to "vt"))),
            ),
            "x",
        )
        assertEquals("タイトル/VT-TITLE", SemanticValues.evaluate(expr, env("FLD-TITLE", 0))!!.text)
    }

    @Test
    fun `via を並べると段を重ねて辿り in は最後の段に効く`() {
        // 属性 → 値型（vtype）→ 分類（group）と 2 段辿って分類の名前を読む
        val chained = ValueExpr.parseValue(mapOf("via" to listOf("vtype", "group"), "in" to mapOf("schemaId" to "grp"), "path" to "label"), "x")
        val detailed = SemanticValues.evaluateDetailed(chained, env("FLD-TITLE", 0))
        val side = assertNotNull(detailed.side, detailed.reason)
        assertEquals("文字", side.text)
        assertEquals(DocumentId("groups.yaml"), side.sourceDocument)
        // 2 段目の参照が無ければ、その段の理由が残る
        val none = SemanticValues.evaluateDetailed(chained, env("FLD-NOTE", 2))
        assertNull(none.side)
        assertEquals("group に参照が書かれていません", none.reason)
        // in の指定は最後の段の相手に効く（途中の相手は定義出現で選ぶ）
        val wrongIn = SemanticValues.evaluateDetailed(
            ValueExpr.parseValue(mapOf("via" to listOf("vtype", "group"), "in" to mapOf("schemaId" to "vt"), "path" to "label"), "x"),
            env("FLD-TITLE", 0),
        )
        assertNull(wrongIn.side)
        assertEquals("参照先「GRP-TEXT」の出現がありません（in の指定に合う文書）", wrongIn.reason)
        // 配列に空の項目は書けない。1 段の文字はこれまでどおり 1 つの並び
        assertFails { ValueExpr.parseValue(mapOf("via" to listOf("vtype", ""), "path" to "label"), "x") }
        assertEquals(listOf(parseYPath("vtype")), (ValueExpr.parseValue(mapOf("via" to "vtype", "path" to "name"), "x") as ValueExpr.Get).via)
    }

    @Test
    fun `検査の中では via を書けない`() {
        assertFailsWith<IllegalArgumentException> { ValueExpr.parse(mapOf("of" to "a", "via" to "vtype", "path" to "to"), "x") }
        assertFailsWith<IllegalArgumentException> {
            ValueExpr.parse(mapOf("format" to "{x}", "scopes" to listOf(mapOf("of" to "a", "via" to "vtype"))), "x")
        }
        // 文書の木が読めなくても評価器は落ちない
        assertNull(SemanticValues.evaluate(derived, env("FLD-TITLE", 0).let { e -> SemanticValues.Environment(tree = { null }, current = e.current, currentOccurrence = e.currentOccurrence, graph = e.graph) }))
    }
}
