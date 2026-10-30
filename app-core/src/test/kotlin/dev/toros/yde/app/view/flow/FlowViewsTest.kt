package dev.toros.yde.app.view.flow

import dev.toros.yde.semantic.*
import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlowViewsTest {
    private val document = DocumentId("flows/one.yaml")
    private val source = DocumentId(".yde/views/flow.yaml")
    private fun view(build: String, rules: String = "") = VirtualFlowDef(
        "flow", "フロー", source = source, match = "flows/*.yaml", schemaId = "flow",
        projection = TransformDef.parse(YamlIO.parseToRaw("id: projection\nbuild:\n" + build.trimIndent().prependIndent("  ") + rules)),
    )
    private fun resolve(build: String, input: String = "{}") =
        FlowViews.resolve(view(build), document, "flow", YamlIO.parse(input.trimIndent()))

    @Test
    fun `任意活動を結合しても各要素の原本位置と直接編集の適格性を保持する`() {
        val input = YamlIO.parse("main: [{id: A, title: 主活動}]\noptional: [{id: B, title: 任意活動}]\nlinks: [{id: L, from: A, to: B}]")
        val before = YamlIO.dumpPlain(input)
        val definition = view("""
            nodes:
              ${'$'}concat:
                - ${'$'}each: main
                  ${'$'}build:
                    key: {${'$'}copy: id}
                    label: {${'$'}copy: title}
                    detail: {${'$'}copy: .}
                - ${'$'}each: optional
                  ${'$'}build:
                    key: {${'$'}copy: id}
                    label: {${'$'}copy: title}
                    detail: {${'$'}copy: .}
            edges:
              ${'$'}each: links
              ${'$'}build:
                key: {${'$'}copy: id}
                from: {${'$'}copy: from}
                to: {${'$'}copy: to}
                detail: {${'$'}copy: .}
        """)
        val result = FlowViews.resolve(definition, document, "flow", input)
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val model = assertNotNull(result.model)
        assertEquals(listOf("A", "B"), model.nodes.map { it.key })
        assertEquals(listOf(FlowLocation(document, listOf("optional", 0))), model.nodes[1].detail.origins)
        assertTrue(model.nodes.all { it.detail.editable })
        assertTrue(model.edges.single().detail.editable)
        assertEquals(listOf("nodes", 1), model.edges.single().to.target)
        assertEquals(before, YamlIO.dumpPlain(input))
    }

    @Test
    fun `重複と不足を診断し不完全な要素を間引かない`() {
        val result = resolve("""
            nodes:
              ${'$'}each: items
              ${'$'}build:
                key: {${'$'}copy: id}
                label: {${'$'}copy: title}
                shape: {${'$'}copy: shape}
                lane: {${'$'}copy: lane}
                detail: {${'$'}copy: .}
            edges: [{from: A, to: MISSING, detail: {}}]
            lanes: [{key: L, label: 役割}, {key: L, label: 重複}]
        """, """
            items:
              - {id: A, title: 一つ目, shape: triangle, lane: L}
              - {id: A, title: 二つ目, lane: UNKNOWN}
              - {title: ID不足}
        """)
        val model = assertNotNull(result.model)
        assertEquals(3, model.nodes.size)
        assertEquals("box", model.nodes.first().shape)
        assertEquals(FlowConnectionState.AMBIGUOUS, model.edges.single().from.state)
        assertNull(model.edges.single().from.target)
        assertEquals(FlowConnectionState.UNKNOWN, model.edges.single().to.state)
        assertEquals(FlowConnectionState.AMBIGUOUS, model.nodes.first().lane?.state)
        assertEquals(FlowConnectionState.UNKNOWN, model.nodes[1].lane?.state)
        val missingKey = result.diagnostics.single { it.output == listOf("nodes", 2, "key") }
        assertEquals(listOf(FlowLocation(document, listOf("items", 2))), missingKey.locations)
        assertTrue(result.diagnostics.any { it.output == listOf("nodes", 0, "shape") })
        assertEquals(4, result.diagnostics.count { it.code == "duplicate-key" })
    }

    @Test
    fun `直接コピー以外は表示できても詳細欄から編集できない`() {
        for (detail in listOf(
            "{${'$'}copy: child}",
            "{${'$'}copy: ., ${'$'}in: {schemaId: flow}}",
            "{${'$'}when: {path: id, exists: true}, ${'$'}then: {${'$'}copy: .}}",
            "{value: {${'$'}copy: .}}",
        )) {
            val result = resolve("""
                nodes:
                  ${'$'}each: items
                  ${'$'}build:
                    key: {${'$'}copy: id}
                    label: node
                    detail: $detail
                edges: []
            """, "items: [{id: A, child: {name: 子}}]")
            assertFalse(assertNotNull(result.model).nodes.single().detail.editable, detail)
        }
    }

    @Test
    fun `規則経由や条件付きのeachは原本座標が同じでも編集対象にしない`() {
        val rule = "\nrules:\n  - id: item\n    build: {key: {${'$'}copy: id}, label: node, detail: {${'$'}copy: .}}\n"
        val definition = view("""
            nodes: {${'$'}each: items, ${'$'}apply: item}
            edges: []
        """, rule)
        val input = YamlIO.parse("items: [{id: A}]")
        val result = FlowViews.resolve(definition, document, "flow", input)
        assertFalse(assertNotNull(result.model).nodes.single().detail.editable)
        assertEquals(listOf(FlowLocation(document, listOf("items", 0))), result.model!!.nodes.single().detail.origins)
        val guarded = resolve("""
            nodes:
              ${'$'}when: {path: items, exists: true}
              ${'$'}then:
                ${'$'}each: items
                ${'$'}build: {key: {${'$'}copy: id}, label: node, detail: {${'$'}copy: .}}
            edges: []
        """, "items: [{id: A}]")
        assertFalse(guarded.model!!.nodes.single().detail.editable)
    }

    @Test
    fun `表示対象外と未解決は原本の参照位置を使って区別する`() {
        val definition = view("""
            nodes: []
            edges:
              ${'$'}each: links
              ${'$'}build:
                from: {${'$'}copy: from}
                to: {${'$'}copy: to}
                detail: {${'$'}copy: .}
        """)
        val seen = mutableListOf<FlowLocation>()
        val result = FlowViews.resolve(definition, document, "flow", YamlIO.parse("links: [{from: OUTSIDE, to: UNKNOWN}]")) { at, key ->
            seen += at
            if (key == "OUTSIDE") FlowConnectionState.OUTSIDE else FlowConnectionState.UNRESOLVED
        }
        assertEquals(listOf(FlowLocation(document, listOf("links", 0, "from")), FlowLocation(document, listOf("links", 0, "to"))), seen)
        assertEquals(listOf("endpoint-outside", "endpoint-unresolved"), result.diagnostics.map { it.code })
    }

    @Test
    fun `不適用や入力欠落や変換不能では全体診断を返す`() {
        val definition = view("nodes: []\nedges: []")
        assertEquals("not-applicable", FlowViews.resolve(definition, document, "other", YamlIO.parse("{}")).diagnostics.single().code)
        assertEquals("input-missing", FlowViews.resolve(definition, document, "flow", null).diagnostics.single().code)
        val empty = resolve("{${'$'}copy: missing}")
        assertNull(empty.model)
        assertEquals("transform-empty", empty.diagnostics.single().code)
        val malformed = resolve("{nodes: {${'$'}each: items, ${'$'}build: {}}, edges: []}", "items: wrong")
        assertNotNull(malformed.model)
        assertTrue(malformed.diagnostics.any { it.output == listOf("nodes") })
        val failed = resolve("{nodes: {${'$'}concat: [{invalid: value}]}, edges: []}")
        assertNull(failed.model)
        assertEquals("transform-failed", failed.diagnostics.single().code)
        val scalar = resolve("42")
        assertNull(scalar.model)
        assertTrue(scalar.diagnostics.isNotEmpty())
    }
}
