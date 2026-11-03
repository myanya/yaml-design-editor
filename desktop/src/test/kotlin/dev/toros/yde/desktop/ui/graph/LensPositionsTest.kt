package dev.toros.yde.desktop.ui.graph

import dev.toros.yde.desktop.*
import dev.toros.yde.desktop.ui.common.*
import kotlin.test.*

class LensPositionsTest {
    @Test fun `親の移動後に全位置と固定の区別を復元し追加と開閉では周囲を動かさない`() {
        val nodes = listOf(LensNestedNode("P", 100f, 40f), LensNestedNode("C", 80f, 50f, 0),
            LensNestedNode("D", 80f, 50f, 0), LensNestedNode("OUT", 80f, 50f))
        val ids = nodes.map { DiagramNodeId("entity", it.id) }
        val edges = listOf(LensEdgeSpec(1, 2), LensEdgeSpec(0, 3))
        for (kind in LensLayoutKind.entries) {
            val first = GraphLayouts.arrangeNested(nodes, edges, kind, 0)
            val moved = GraphLayouts.withMovedNested(first, nodes, edges, mapOf(0 to LensPoint(-500f, -100f)))
            assertEquals(first.boxes[3], moved.boxes[3])
            val saved = lensPositionSnapshot(nodes, ids, moved, 2f, emptyMap(), 0)
            assertEquals(listOf(ids[0]), saved.values.filter { it.pinned }.map { it.node })
            val context = DiagramContext(mapOf("kind" to "lens"), emptyMap())
            val read = DiagramPositionCodec.decode(DiagramPositionCodec.encode(context, saved), context).positions
            val retained = read.values.associate { it.node.key to LensPoint(it.x * 2, it.y * 2) }
            for (next in listOf(nodes, nodes + LensNestedNode("NEW", 80f, 50f, 0))) {
                val restored = GraphLayouts.arrangeNested(next, edges, kind, 0, retained)
                moved.boxes.forEachIndexed { i, b ->
                    assertEquals(b.x, restored.boxes[i].x, .001f)
                    assertEquals(b.y, restored.boxes[i].y, .001f)
                }
            }
            val closed = GraphLayouts.arrangeNested(listOf(nodes[0], nodes[3]), emptyList(), kind, 0, retained)
            assertEquals(moved.boxes[3], closed.boxes[1])
            val released = read.mapValues { it.value.copy(pinned = false) }
            assertTrue(sameDiagramPositions(saved, released), "固定解除は座標を変えない")
        }
    }
}
