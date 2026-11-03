package dev.toros.yde.desktop.ui.common

import dev.toros.yde.app.view.flow.*
import dev.toros.yde.semantic.*
import kotlin.test.*

class PinnedLayoutTest {
    @Test fun `全配置モードで固定を動かさず自由な箱だけを空きへ置く`() {
        val nodes = (0..7).map { LensNestedNode("N$it", 100f, 60f) }
        val edges = (0..6).map { LensEdgeSpec(it, it + 1) }
        val pins = mapOf("N0" to LensPoint(-240f, -120f), "N4" to LensPoint(80f, 80f))
        for (kind in LensLayoutKind.entries) {
            val result = GraphLayouts.arrangeNested(nodes, edges, kind, pins = pins)
            assertEquals(-240f, result.boxes[0].x)
            assertEquals(-120f, result.boxes[0].y)
            assertEquals(80f, result.boxes[4].x)
            assertEquals(80f, result.boxes[4].y)
            result.boxes.forEachIndexed { i, a -> result.boxes.drop(i + 1).forEach { b -> assertFalse(PinnedLayout.overlap(a, b)) } }
            assertEquals(result, GraphLayouts.arrangeNested(nodes, edges, kind, pins = pins))
        }
    }

    @Test fun `親の移動と開閉で子の固定位置を失わない`() {
        val nodes = listOf(LensNestedNode("P", 100f, 40f), LensNestedNode("C", 80f, 50f, 0), LensNestedNode("D", 70f, 50f, 0))
        val fixed = mapOf("P" to LensPoint(-300f, -200f), "C" to LensPoint(120f, 80f))
        val first = GraphLayouts.arrangeNested(nodes, emptyList(), LensLayoutKind.LAYERED, pins = fixed)
        assertEquals(-180f, first.boxes[1].x)
        assertEquals(-80f, first.boxes[1].y)
        val moved = GraphLayouts.arrangeNested(nodes, emptyList(), LensLayoutKind.LAYERED, pins = fixed + ("P" to LensPoint(10f, 20f)))
        assertEquals(130f, moved.boxes[1].x)
        assertEquals(140f, moved.boxes[1].y)
        assertFalse(PinnedLayout.overlap(moved.boxes[1], moved.boxes[2]))
        val closed = GraphLayouts.arrangeNested(nodes.take(1), emptyList(), LensLayoutKind.LAYERED, pins = fixed)
        assertEquals(first.boxes[0].x, closed.boxes[0].x)
        assertEquals(first, GraphLayouts.arrangeNested(nodes, emptyList(), LensLayoutKind.LAYERED, pins = fixed))
        val preview = GraphLayouts.withMovedNested(first, nodes, emptyList(), mapOf(0 to LensPoint(10f, 20f)))
        assertEquals(moved.boxes[1], preview.boxes[1])
    }

    @Test fun `重なった固定同士は勝手に動かさず通知する`() {
        val nodes = listOf(LensNestedNode("A", 100f, 60f), LensNestedNode("B", 100f, 60f))
        val result = GraphLayouts.arrangeNested(nodes, listOf(LensEdgeSpec(0, 1)), LensLayoutKind.STRESS,
            pins = mapOf("A" to LensPoint(0f, 0f), "B" to LensPoint(10f, 10f)))
        assertEquals(0f, result.boxes[0].x)
        assertEquals(10f, result.boxes[1].x)
        assertTrue(result.note!!.contains("重なって"))
    }

    @Test fun `経路は障害物を横切らず自己ループと戻り線も残る`() {
        val boxes = listOf(LensBox(0f, 0f, 100f, 60f), LensBox(0f, 400f, 100f, 60f), LensBox(-30f, 160f, 160f, 100f))
        for ((a, b) in listOf(0 to 1, 1 to 0, 0 to 0)) {
            val points = PinnedLayout.route(boxes[a], boxes[b], boxes)
            assertTrue(points.size >= 4)
            for ((p, q) in points.zipWithNext()) {
                assertTrue(p.x == q.x || p.y == q.y)
                for (r in boxes) {
                    val crosses = if (p.x == q.x) p.x > r.x && p.x < r.x + r.width &&
                        maxOf(minOf(p.y, q.y), r.y) < minOf(maxOf(p.y, q.y), r.y + r.height)
                    else p.y > r.y && p.y < r.y + r.height && maxOf(minOf(p.x, q.x), r.x) < minOf(maxOf(p.x, q.x), r.x + r.width)
                    assertFalse(crosses, "$p → $q / $r")
                }
            }
        }
    }

    private val detail = FlowDetail(null, emptyList(), false)
    private fun node(i: Int, lane: Int? = null) = FlowNode(listOf("nodes", i), "N$i", "工程$i", "box",
        lane?.let { FlowConnection("L$it", FlowConnectionState.RESOLVED, listOf("lanes", it)) }, detail)

    @Test fun `フローのレーン内固定はレーンの内容領域から復元し未割当も重ならない`() {
        val lanes = (0..1).map { FlowLane(listOf("lanes", it), "L$it", "担当$it") }
        val model = FlowModel(listOf(node(0, 0), node(1, 0), node(2, 1), node(3), node(4), node(5)), emptyList(), lanes)
        val labels = mapOf(lanes[0].output to FlowSize(80f, 24f), lanes[1].output to FlowSize(200f, 50f))
        val pins = mapOf(model.nodes[0].output to FlowPoint(100f, 120f))
        for (mode in FlowLayoutMode.entries) for (direction in listOf("down", "right")) {
            val result = GraphLayouts.arrangeFlow(model, labels, direction, mode, pins = pins)
            val p = result.nodes.getValue(model.nodes[0].output)
            if (mode == FlowLayoutMode.PLAIN) { assertEquals(100f, p.x); assertEquals(120f, p.y) }
            else {
                val lane = result.lanes.getValue(lanes[0].output)
                val inset = PinnedFlow.inset(labels, lanes[0].output, mode, direction, lanes.map { it.output })
                assertEquals(100f, p.x - lane.x - inset.x)
                assertEquals(120f, p.y - lane.y - inset.y)
                assertTrue(lane.contains(p.center))
            }
            val boxes = result.nodes.values.map { LensBox(it.x, it.y, it.width, it.height) }
            boxes.forEachIndexed { i, a -> boxes.drop(i + 1).forEach { b -> assertFalse(PinnedLayout.overlap(a, b), "$mode $direction $a $b") } }
            assertEquals(result, GraphLayouts.arrangeFlow(model, labels, direction, mode, pins = pins))
        }
    }

    @Test fun `大きな鎖も固定位置を保って配置できる`() {
        val nodes = (0..2000).map { LensNestedNode("N$it", 100f, 60f) }
        val edges = (0..1999).map { LensEdgeSpec(it, it + 1) }
        val result = GraphLayouts.arrangeNested(nodes, edges, LensLayoutKind.LAYERED, pins = mapOf("N0" to LensPoint(-50f, -80f)))
        assertEquals(2001, result.boxes.size)
        assertEquals(-50f, result.boxes.first().x)
        assertEquals(-80f, result.boxes.first().y)
        assertTrue(result.routes.all { it.points.size >= 2 })
    }

    @Test fun `固定した菱形でも並行辺と自己ループとラベルを残す`() {
        fun edge(i: Int, from: Int, to: Int) = FlowEdge(listOf("edges", i), "E$i", "条件$i",
            FlowConnection("N$from", FlowConnectionState.RESOLVED, listOf("nodes", from)),
            FlowConnection("N$to", FlowConnectionState.RESOLVED, listOf("nodes", to)), detail)
        val model = FlowModel(listOf(node(0).copy(shape = "diamond"), node(1)),
            listOf(edge(0, 0, 1), edge(1, 0, 1), edge(2, 1, 0), edge(3, 0, 0)), emptyList())
        for (direction in listOf("down", "right")) {
            val result = GraphLayouts.arrangeFlow(model, emptyMap(), direction, pins = mapOf(model.nodes[0].output to FlowPoint(-80f, -120f)))
            assertEquals(4, result.edges.map { it.points }.distinct().size)
            assertTrue(result.edges.all { it.points.size >= 2 })
            val occupied = (result.nodes.values + result.edges.map { it.label }).map { LensBox(it.x, it.y, it.width, it.height) }
            occupied.forEachIndexed { i, a -> occupied.drop(i + 1).forEach { b -> assertFalse(PinnedLayout.overlap(a, b)) } }
            model.edges.zip(result.edges).forEach { (e, r) ->
                listOf(e.from.target to r.points.first(), e.to.target to r.points.last()).forEach { (id, p) ->
                    if (id == model.nodes[0].output) {
                        val b = result.nodes.getValue(id)
                        assertEquals(1f, kotlin.math.abs(p.x - b.center.x) / (b.width / 2) + kotlin.math.abs(p.y - b.center.y) / (b.height / 2), .001f)
                    }
                }
            }
        }
    }
}
