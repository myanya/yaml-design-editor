package dev.toros.yde.desktop.ui.view

import dev.toros.yde.app.view.flow.*
import dev.toros.yde.desktop.DiagramContext
import dev.toros.yde.desktop.DiagramNodeId
import dev.toros.yde.desktop.DiagramPosition
import dev.toros.yde.desktop.DiagramPositionCodec
import dev.toros.yde.semantic.*
import kotlin.test.*

/** フロー図の線の経由点（ADR-0028）: 記録の識別・記録からの読み出し・経由点を通る経路・記録の形式。 */
class FlowEdgeViasTest {
    private fun node(i: Int, key: String) = FlowNode(listOf("nodes", i), key, key, "box", null, FlowDetail(null, emptyList(), false))
    private fun edge(i: Int, key: String?, from: Int, to: Int) = FlowEdge(listOf("edges", i), key, "線$i",
        FlowConnection("n$from", FlowConnectionState.RESOLVED, listOf("nodes", from)),
        FlowConnection("n$to", FlowConnectionState.RESOLVED, listOf("nodes", to)), FlowDetail(null, emptyList(), false))
    private val model = FlowModel(listOf(node(0, "A"), node(1, "B")),
        listOf(edge(0, "e1", 0, 1), edge(1, null, 0, 1), edge(2, "dup", 1, 0), edge(3, "dup", 0, 1)), emptyList())

    @Test
    fun `経由点を持てるのは一意なkeyを持つ線だけで記録の図の原点の項目だけを読む`() {
        val ids = flowEdgeIds(model)
        assertEquals(mapOf<List<Any>, DiagramNodeId>(listOf("edges", 0) to DiagramNodeId(FLOW_EDGE_KIND, "e1")), ids)
        val id = DiagramNodeId(FLOW_EDGE_KIND, "e1")
        val positions = mapOf(
            id to flowEdgeVia(id, FlowPoint(300f, 150f), 1.5f),
            DiagramNodeId("flow", "A") to DiagramPosition(DiagramNodeId("flow", "A"), null, 0f, 0f, false),
        )
        assertEquals(DiagramPosition(id, null, 200f, 100f, true), positions[id])
        assertEquals(mapOf<List<Any>, FlowPoint>(listOf("edges", 0) to FlowPoint(300f, 150f)), flowEdgeVias(model, positions, 1.5f))
    }

    @Test
    fun `線は元の箱から経由点を通って先の箱へ直角に引きラベルは経由点を中心に置く`() {
        val a = FlowRect(0f, 0f, 100f, 50f)
        val b = FlowRect(400f, 0f, 100f, 50f)
        val straight = FlowRoute(listOf("edges", 0), listOf(FlowPoint(100f, 25f), FlowPoint(400f, 25f)), FlowRect(200f, 10f, 60f, 30f))
        val other = FlowRoute(listOf("edges", 1), listOf(FlowPoint(100f, 30f), FlowPoint(400f, 30f)), FlowRect(200f, 60f, 60f, 30f))
        val base = FlowArrangement(mapOf(listOf<Any>("nodes", 0) to a, listOf<Any>("nodes", 1) to b), emptyMap(), listOf(straight, other), FlowSize(520f, 100f))
        val via = FlowPoint(250f, 220f)
        val out = withFlowVias(base, model, mapOf(listOf<Any>("edges", 0) to via), mapOf(listOf<Any>("edges", 0) to FlowSize(48f, 18f)), "right")
        val route = out.edges.first()
        assertTrue(FlowPoint(250f, 220f) in route.points, route.points.toString())
        // 両端は箱の縁、線分はすべて水平か垂直
        assertTrue(route.points.first().let { it.x in a.x..a.right && it.y in a.y..a.bottom })
        assertTrue(route.points.last().let { it.x in b.x..b.right && it.y in b.y..b.bottom })
        assertTrue(route.points.zipWithNext().all { (p, q) -> p.x == q.x || p.y == q.y }, route.points.toString())
        assertEquals(FlowPoint(250f, 220f), route.label.center)
        assertEquals(60f, route.label.width)
        // 経由点の無い線はそのまま。図の大きさは経由点のラベルまで広がる
        assertEquals(other, out.edges[1])
        assertTrue(out.size.height >= route.label.bottom)
        assertSame(base, withFlowVias(base, model, emptyMap(), emptyMap(), "right"))
    }

    @Test
    fun `経由点は箱と同じ記録の形式で読み書きでき種類で見分けられる`() {
        val context = DiagramContext(mapOf("kind" to "flow", "view" to "v", "document" to "d.yaml"), emptyMap())
        val id = DiagramNodeId(FLOW_EDGE_KIND, "e1")
        val box = DiagramNodeId("flow", "A")
        val positions = mapOf(id to DiagramPosition(id, null, 12.5f, 40f, true), box to DiagramPosition(box, null, 0f, 8f, false))
        val read = DiagramPositionCodec.decode(DiagramPositionCodec.encode(context, positions), context)
        assertEquals(positions, read.positions)
    }
}
