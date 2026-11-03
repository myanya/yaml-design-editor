package dev.toros.yde.desktop.ui.graph

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import dev.toros.yde.desktop.ui.common.*
import dev.toros.yde.semantic.*
import dev.toros.yde.semantic.schema.EntityDecl
import dev.toros.yde.semantic.schema.SchemaTreeParser
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class LensPortsTest {
    @Test fun `同じ相手の複数参照と自己参照をポート表示だけで区別する`() {
        val schema = SchemaTreeParser.parse("""
            id: table
            root:
              type: object
              fields:
                - {key: id, type: id}
                - {key: refs, type: array, item: {type: ref, relation: fk}}
        """.trimIndent(), fallbackId = "table")
        val snapshot = SemanticEngine.build(SemanticInput(1, listOf(
            SemanticDocument(DocumentId("a.yaml"), YamlIO.parse("id: A\nrefs: [B, B, A]"), schema, "table", listOf(EntityDecl(type = "table", defines = true))),
            SemanticDocument(DocumentId("b.yaml"), YamlIO.parse("id: B"), schema, "table", listOf(EntityDecl(type = "table", defines = true))),
        )))
        for (mode in LensMode.entries) for (reverse in listOf(emptySet(), setOf("fk"))) {
            val raw = LensGraphs.build(snapshot.graph, snapshot.refs, mode, reverse, includeSelfReferences = true)
            val legacy = LensGraphs.build(snapshot.graph, snapshot.refs, mode, reverse)
            assertEquals(legacy, LensGraphs.withPortReferences(raw) { _, _ -> false })
            val ports = LensGraphs.withPortReferences(raw) { _, _ -> true }
            assertEquals(3, ports.edges.size)
            assertEquals(1, ports.edges.count { it.from == it.to })
            assertTrue(ports.sources.values.all { it.size == 1 })
            assertEquals(3, ports.edges.map { it.reference!!.reference!!.path }.distinct().size)
            val selection = LensGraphs.select(ports, LensSelectParams({ "table" }))
            assertEquals(ports.edges, selection.edges)
        }
    }

    @Test fun `畳みでは祖先側のポートだけを外し逆向きでも同じ参照属性を使う`() {
        val source = DiagramLinkSource("A", "B")
        val edge = LensGraphEdge("A", "B", "fk", LensEdgeKind.REF, reference = source)
        val self = LensGraphEdge("A", "A", "fk", LensEdgeKind.REF, reference = DiagramLinkSource("A", "A"))
        val graph = LensGraph(LensMode.ENTITY, listOf(LensNode("P", "P", null, null, null),
            LensNode("A", "A", null, "P", "owns"), LensNode("B", "B", null, null, null)), listOf(edge, self),
            mapOf(edge to listOf(source), self to listOf(self.reference!!)))
        val folded = LensGraphs.select(graph, LensSelectParams({ "table" }))
        val shown = folded.edges.single()
        assertEquals("P", shown.from)
        assertEquals(source, shown.reference)
        val style = DiagramEdgeStyle(sourcePort = "parent_id", targetPort = "id")
        val attached = lensEndpointStyle(style, shown, folded.sources.getValue(shown), "P", "B")
        assertNull(attached.sourcePort); assertEquals("id", attached.targetPort)
        val reversed = lensEndpointStyle(style, shown.copy(from = "B", to = "P", reversed = true), listOf(source), "B", "P")
        assertEquals("id", reversed.sourcePort); assertNull(reversed.targetPort)
    }

    @Test fun `全配置と移動で行へ接続し入れ子の親の枠は通り抜けられる`() = runComposeUiTest {
        lateinit var measurer: TextMeasurer
        setContent { measurer = rememberTextMeasurer() }
        waitForIdle()
        val body = portTestBody(measurer)
        val nodes = (0..1).map { LensVisibleNode(LensNode("N$it", "N$it", null, null, null), null, false, 0) }
        val nested = nodes.map { LensNestedNode(it.node.key, body.size.width, body.size.height) }
        val spec = listOf(LensEdgeSpec(0, 1))
        val visual = DiagramEdgeVisual(DiagramEdgeStyle(sourcePort = "parent_id", targetPort = "id"), emptyMap(), portTestColors)
        val edges = listOf(LensArrangedEdge(0, 1, "fk", LensEdgeKind.REF, null, visual))
        val bodies = nodes.associate { it.node.key to body }
        for (kind in LensLayoutKind.entries) {
            val base = GraphLayouts.arrangeNested(nested, spec, kind, 0)
            val moved = GraphLayouts.withMovedNested(base, nested, spec, mapOf(0 to LensPoint(-300f, -100f)))
            for (result in listOf(base, moved)) {
                val a = LensArranged(nodes, nested, edges, result, bodies)
                val connected = withLensPorts(a)
                assertEquals(result.boxes, connected.result.boxes)
                val route = connected.result.routes.single().points
                assertTrue(route.isNotEmpty(), kind.name)
                assertEquals(result.boxes[0].y + body.portPositions().getValue("parent_id").y, route.first().y, .01f)
                assertEquals(result.boxes[1].y + body.portPositions().getValue("id").y, route.last().y, .01f)
                val legacy = a.copy(edges = edges.map { it.copy(visual = null) })
                assertSame(legacy, withLensPorts(legacy))
            }
        }
        val parent = LensVisibleNode(LensNode("P", "P", null, null, null), null, true, 0)
        val inside = nodes[0].copy(node = nodes[0].node.copy(parent = "P"), parent = "P")
        val boxes = listOf(LensBox(0f, 0f, 260f, 220f), LensBox(30f, 90f, body.size.width, body.size.height), LensBox(430f, 80f, body.size.width, body.size.height))
        val arranged = LensArranged(listOf(parent, inside, nodes[1]), listOf(LensNestedNode("P", 260f, 50f), nested[0].copy(parent = 0), nested[1]),
            edges.map { it.copy(from = 1, to = 2) }, LensArrangement(LensLayoutKind.LAYERED, boxes, listOf(LensRoute(emptyList(), null)), 700f, 300f), bodies)
        assertTrue(withLensPorts(arranged).result.routes.single().points.isNotEmpty())
    }
}
