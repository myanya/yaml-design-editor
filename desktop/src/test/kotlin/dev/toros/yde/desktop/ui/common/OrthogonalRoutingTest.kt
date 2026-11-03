package dev.toros.yde.desktop.ui.common

import kotlin.test.*

class OrthogonalRoutingTest {
    @Test fun `狭い経路の退避先は前のラベル位置に引きずられず線との距離で決まる`() {
        val boxes = listOf(LensBox(520f, 25f, 300f, 238f), LensBox(965f, 96f, 300f, 137f))
        val points = listOf(LensPoint(820f, 142f), LensPoint(892.5f, 142f), LensPoint(892.5f, 185f), LensPoint(965f, 185f))
        val size = LensNodeSize("", 231f, 36f)
        assertNull(OrthogonalRouting.labelNear(points, size, boxes))
        val label = OrthogonalRouting.label(points, size, boxes, LensPoint(1123f, 301f))
        assertTrue(boxes.none { PinnedLayout.overlap(label, it) })
        assertTrue(label.y + label.height / 2 < 290f, "以前の遠い位置へ退避しない: $label")
        assertEquals(label, OrthogonalRouting.label(points, size, boxes, LensPoint(label.centerX, label.centerY)))
        val empty = OrthogonalRouting.label(emptyList(), size, emptyList(), LensPoint(400f, 200f))
        assertEquals(400f, empty.centerX)
        assertEquals(200f, empty.centerY)
    }

    private fun clear(points: List<LensPoint>, boxes: List<LensBox>) {
        assertTrue(points.size >= 2)
        points.zipWithNext().forEach { (a, b) ->
            assertTrue(a.x == b.x || a.y == b.y, "斜線を作らない: $a → $b")
            assertTrue(boxes.none { OrthogonalRouting.intersects(a, b, it) }, "箱の内側を通らない: $a → $b")
        }
    }

    @Test fun `横方向では箱の側面から出入りして中央で折れる`() {
        val a = LensBox(0f, 0f, 160f, 124f); val b = LensBox(340f, 190f, 160f, 152f)
        val points = OrthogonalRouting.route(a, b, listOf(a, b), right = true)
        clear(points, listOf(a, b))
        assertEquals(4, points.size)
        assertEquals(a.x + a.width, points.first().x)
        assertEquals(points.first().y, points[1].y)
        assertEquals(b.x, points.last().x)
        assertEquals(points.last().y, points[points.lastIndex - 1].y)
        assertEquals(250f, points[1].x, .01f, "両方の箱から離した位置で折る")
    }

    @Test fun `戻り線は向かい合う面を選び障害物が無ければ直線にする`() {
        val a = LensBox(0f, 0f, 160f, 124f); val b = LensBox(680f, 0f, 160f, 124f)
        val below = LensBox(340f, 190f, 160f, 152f)
        val points = OrthogonalRouting.route(b, a, listOf(a, b, below), right = true)
        assertEquals(listOf(LensPoint(680f, 62f), LensPoint(160f, 62f)), points)
        val block = below.copy(y = 0f)
        val detour = OrthogonalRouting.route(b, a, listOf(a, b, block), right = true)
        clear(detour, listOf(a, b, block))
        assertTrue(detour.size <= 4, "戻り線を箱の背面から大回りさせない: $detour")
        assertTrue(detour.all { it.x in a.x..(b.x + b.width) })
        assertEquals(-20f, detour.minOf { it.y })
    }

    @Test fun `狭い隙間と複数障害物と自己ループも逆走せず通す`() {
        val a = LensBox(0f, 0f, 100f, 60f); val near = a.copy(x = 110f)
        assertEquals(2, OrthogonalRouting.route(a, near, listOf(a, near), right = true).size)
        val taller = near.copy(height = 62f)
        val aligned = OrthogonalRouting.route(a, taller, listOf(a, taller), right = true)
        assertEquals(2, aligned.size, "寸法の小さな違いで段差を作らない")
        assertEquals(aligned.first().y, aligned.last().y)
        val b = a.copy(y = 400f); val obstacle = LensBox(-30f, 160f, 160f, 100f)
        for ((from, to) in listOf(a to b, b to a, a to a)) clear(OrthogonalRouting.route(from, to, listOf(a, b, obstacle)), listOf(a, b, obstacle))
    }

    @Test fun `直交線を持つレンズの移動でも経路とラベルを追従させる`() {
        val nodes = listOf(LensNestedNode("A", 100f, 60f), LensNestedNode("B", 100f, 60f), LensNestedNode("C", 100f, 60f))
        val edges = listOf(LensEdgeSpec(0, 1, 50f, 20f), LensEdgeSpec(1, 2, 50f, 20f))
        val first = GraphLayouts.arrangeNested(nodes, edges, LensLayoutKind.LAYERED,
            pins = mapOf("A" to LensPoint(0f, 0f), "B" to LensPoint(0f, 200f), "C" to LensPoint(0f, 400f)))
        val moved = GraphLayouts.withMovedNested(first, nodes, edges, mapOf(0 to LensPoint(-200f, 0f)))
        assertEquals(first.boxes.drop(1), moved.boxes.drop(1))
        assertEquals(first.routes[1], moved.routes[1])
        clear(moved.routes[0].points, moved.boxes)
        assertNotEquals(first.routes[0].label, moved.routes[0].label)
    }
}
