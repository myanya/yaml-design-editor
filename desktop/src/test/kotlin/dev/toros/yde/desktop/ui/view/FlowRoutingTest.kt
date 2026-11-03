package dev.toros.yde.desktop.ui.view

import dev.toros.yde.app.view.flow.*
import dev.toros.yde.desktop.ui.common.*
import dev.toros.yde.semantic.*
import kotlin.test.*

class FlowRoutingTest {
    private val detail = FlowDetail(null, emptyList(), false)
    private val nodes = listOf("葵", "蓮", "結衣").mapIndexed { i, name -> FlowNode(listOf("nodes", i), "N$i", name, "box", null, detail) }
    private val edges = listOf("先輩", "同僚", "幼なじみ").mapIndexed { i, name -> FlowEdge(listOf("edges", i), "E$i", name,
        FlowConnection("N$i", FlowConnectionState.RESOLVED, nodes[i].output),
        FlowConnection("N${(i + 1) % 3}", FlowConnectionState.RESOLVED, nodes[(i + 1) % 3].output), detail) }
    private val model = FlowModel(nodes, edges, emptyList())
    private val labels = edges.associate { it.output to FlowSize(64f, 20f) }
    private val sizes = nodes.mapIndexed { i, n -> n.output to FlowSize(160f, if (i == 1) 152f else 124f) }.toMap()

    private fun verify(a: FlowArrangement) {
        val boxes = a.nodes.values.map { LensBox(it.x, it.y, it.width, it.height) }
        a.edges.forEach { route ->
            assertTrue(route.points.size >= 2)
            route.points.zipWithNext().forEach { (p, q) ->
                assertTrue(p.x == q.x || p.y == q.y)
                assertTrue(boxes.none { OrthogonalRouting.intersects(LensPoint(p.x, p.y), LensPoint(q.x, q.y), it) }, "$route")
            }
            val label = route.label.let { LensBox(it.x, it.y, it.width, it.height) }
            assertTrue(boxes.none { PinnedLayout.overlap(label, it) }, "ラベルを箱へ重ねない")
            val distance = route.points.zipWithNext().minOf { (p, q) ->
                val x = label.centerX.coerceIn(minOf(p.x, q.x), maxOf(p.x, q.x))
                val y = label.centerY.coerceIn(minOf(p.y, q.y), maxOf(p.y, q.y))
                kotlin.math.hypot(label.centerX - x, label.centerY - y)
            }
            assertTrue(distance <= maxOf(label.width, label.height) / 2 + 32f, "ラベルを線の近くに保つ: $route")
        }
    }

    @Test fun `人物カードの初期配置と移動と再配置で自然な出入りを保つ`() {
        val first = GraphLayouts.arrangeFlow(model, labels, "right", FlowLayoutMode.PLAIN, sizes)
        val ren = first.nodes.getValue(nodes[1].output)
        val moved = flowDragPreview(first, nodes[1].output, FlowPoint(ren.x, ren.y + 100f), model, "right")
        assertEquals(first.nodes.filterKeys { it != nodes[1].output }, moved.nodes.filterKeys { it != nodes[1].output })
        assertEquals(first.edges[2], moved.edges[2], "遮られていない幼なじみの線はそのまま")
        for (route in moved.edges.take(2)) {
            assertEquals(route.points.first().y, route.points[1].y, "横向きの線は横から出る")
            assertEquals(route.points.last().y, route.points[route.points.lastIndex - 1].y, "横向きの線は横から入る")
        }
        assertNotEquals(first.edges[0].label, moved.edges[0].label)
        val arranged = GraphLayouts.arrangeFlow(model, labels, "right", FlowLayoutMode.PLAIN, sizes,
            pins = mapOf(nodes[1].output to FlowPoint(ren.x, ren.y + 100f)))
        verify(first); verify(moved); verify(arranged)
        val back = arranged.edges[2].points
        assertTrue(back.size <= 4, "再配置でも不要な大回りをしない: $back")
        assertTrue(back.all { it.x >= arranged.nodes.values.minOf { b -> b.x } && it.x <= arranged.nodes.values.maxOf { b -> b.right } })
    }

    @Test fun `関係のない線をノードが遮った場合もその線だけを引き直す`() {
        val first = GraphLayouts.arrangeFlow(model, labels, "right", FlowLayoutMode.PLAIN, sizes)
        val line = first.edges[2].points
        val middle = FlowPoint((line.first().x + line.last().x) / 2 - 80f, line.first().y - 76f)
        val moved = flowDragPreview(first, nodes[1].output, middle, model, "right")
        assertNotEquals(first.edges[2].points, moved.edges[2].points)
        verify(moved)
    }
}
