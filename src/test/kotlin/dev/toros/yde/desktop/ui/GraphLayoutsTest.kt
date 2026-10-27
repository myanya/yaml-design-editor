package dev.toros.yde.desktop.ui

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** グラフレンズの配置（ELK を包む `GraphLayouts`）。 */
class GraphLayoutsTest {

    private fun nodes(n: Int) = (0 until n).map { LensNodeSize("N-$it", 120f, 40f) }
    private fun chain(n: Int) = (0 until n - 1).map { LensEdgeSpec(it, it + 1, 40f, 12f) }
    private fun star(n: Int) = (1 until n).map { LensEdgeSpec(0, it, 40f, 12f) }

    private fun assertWellFormed(result: LensArrangement, nodeCount: Int, edgeCount: Int) {
        assertEquals(nodeCount, result.boxes.size, "節点ごとに箱が 1 つ")
        assertEquals(edgeCount, result.routes.size, "辺ごとに経路が 1 つ")
        result.boxes.forEach { b ->
            assertTrue(b.x >= 0f && b.y >= 0f, "箱は 0 以上: $b")
            assertTrue(b.x + b.width <= result.width + 0.5f && b.y + b.height <= result.height + 0.5f, "箱は全体に収まる: $b / ${result.width}x${result.height}")
            assertTrue(b.x.isFinite() && b.y.isFinite(), "座標が数: $b")
        }
        result.routes.forEach { r -> r.points.forEach { p -> assertTrue(p.x.isFinite() && p.y.isFinite(), "経路の点が数: $p") } }
    }

    @Test
    fun `every kind returns a box per node and a route per edge`() {
        LensLayoutKind.entries.forEach { kind ->
            val result = GraphLayouts.arrange(nodes(12), chain(12) + star(6), kind, origin = 0)
            assertWellFormed(result, 12, 11 + 5)
            assertEquals(kind, result.kind, "指定した種類で配置できる（代替しない）")
            assertNull(result.note)
            assertTrue(result.routes.all { it.points.size >= 2 }, "有効な辺はすべて経路を持つ")
        }
    }

    @Test
    fun `layout is deterministic`() {
        LensLayoutKind.entries.forEach { kind ->
            val a = GraphLayouts.arrange(nodes(15), chain(15) + star(8), kind, origin = 0)
            val b = GraphLayouts.arrange(nodes(15), chain(15) + star(8), kind, origin = 0)
            assertEquals(a, b, "$kind は同じ入力で同じ結果")
        }
    }

    @Test
    fun `layered places a chain from top to bottom`() {
        val result = GraphLayouts.arrange(nodes(4), chain(4), LensLayoutKind.LAYERED)
        val ys = result.boxes.map { it.y }
        assertEquals(ys, ys.sorted(), "参照の向きに沿って上から下へ")
        assertTrue(ys.zipWithNext().all { (a, b) -> b - a >= 40f }, "層の間に隙間がある")
        result.routes.forEach { assertTrue(it.label != null, "ラベルの位置がある") }
    }

    @Test
    fun `radial puts the origin at the center`() {
        val result = GraphLayouts.arrange(nodes(9), star(9), LensLayoutKind.RADIAL, origin = 0)
        val root = result.boxes[0]
        val d = hypot(root.centerX - result.width / 2, root.centerY - result.height / 2)
        assertTrue(d < maxOf(result.width, result.height) * 0.15f, "起点が中央付近にある（ずれ $d）")
        val radii = result.boxes.drop(1).map { hypot(it.centerX - root.centerX, it.centerY - root.centerY) }
        assertTrue(radii.max() - radii.min() < 2f, "同じ深さの節点は同じ半径: $radii")
    }

    @Test
    fun `radial keeps unreachable nodes below the tree`() {
        // 節点 5 はどこにもつながらない
        val result = GraphLayouts.arrange(nodes(6), star(5), LensLayoutKind.RADIAL, origin = 0)
        assertWellFormed(result, 6, 4)
        val bottomOfTree = result.boxes.take(5).maxOf { it.y + it.height }
        assertTrue(result.boxes[5].y >= bottomOfTree, "辿れない節点は木の下に置く")
    }

    @Test
    fun `radial without origin falls back to stress and stress over the limit falls back to layered`() {
        val noOrigin = GraphLayouts.arrange(nodes(5), chain(5), LensLayoutKind.RADIAL)
        assertEquals(LensLayoutKind.STRESS, noOrigin.kind)
        assertTrue(noOrigin.note!!.contains("起点"))
        val big = GraphLayouts.arrange(nodes(GraphLayouts.STRESS_MAX_NODES + 1), chain(GraphLayouts.STRESS_MAX_NODES + 1), LensLayoutKind.STRESS)
        assertEquals(LensLayoutKind.LAYERED, big.kind)
        assertTrue(big.note!!.contains("階層"))
        assertWellFormed(big, GraphLayouts.STRESS_MAX_NODES + 1, GraphLayouts.STRESS_MAX_NODES)
    }

    private fun assertApart(result: LensArrangement, label: String) {
        val boxes = result.boxes
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            val a = boxes[i]; val b = boxes[j]
            val overlap = a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height
            assertTrue(!overlap, "$label: 箱が重ならない: $a / $b")
        }
    }

    private fun inside(outer: LensBox, inner: LensBox): Boolean =
        inner.x >= outer.x - 0.5f && inner.y >= outer.y - 0.5f &&
            inner.x + inner.width <= outer.x + outer.width + 0.5f && inner.y + inner.height <= outer.y + outer.height + 0.5f

    @Test
    fun `nested boxes contain their children and the layout is deterministic`() {
        // 0 の中に 1・2、2 の中に 3。4 と 5 は外側。辺: 1→4（段をまたぐ）・2→1（同じ段）・4→5・3→5
        val nodes = listOf(
            LensNestedNode("P", 140f, 30f),
            LensNestedNode("C1", 100f, 40f, parent = 0),
            LensNestedNode("C2", 100f, 30f, parent = 0),
            LensNestedNode("G", 90f, 40f, parent = 2),
            LensNestedNode("X", 120f, 40f),
            LensNestedNode("Y", 120f, 40f),
        )
        val edges = listOf(LensEdgeSpec(1, 4, 30f, 12f), LensEdgeSpec(2, 1), LensEdgeSpec(4, 5), LensEdgeSpec(3, 5))
        LensLayoutKind.entries.forEach { kind ->
            val a = GraphLayouts.arrangeNested(nodes, edges, kind, origin = 4)
            assertWellFormed(a, 6, 4)
            assertTrue(inside(a.boxes[0], a.boxes[1]) && inside(a.boxes[0], a.boxes[2]), "$kind: 子は親の箱の中: ${a.boxes}")
            assertTrue(inside(a.boxes[2], a.boxes[3]), "$kind: 孫は子の箱の中")
            assertTrue(a.boxes[1].y >= a.boxes[0].y + 30f - 0.5f, "$kind: 子は親の見出しの下")
            assertTrue(a.boxes[0].height > 30f + 40f, "$kind: 親の箱は見出しと子を収める")
            assertTrue(a.routes.all { it.points.size >= 2 }, "$kind: 辺はすべて経路を持つ")
            assertEquals(a, GraphLayouts.arrangeNested(nodes, edges, kind, origin = 4), "$kind は同じ入力で同じ結果")
        }
    }

    @Test
    fun `moving a parent box moves the boxes inside it`() {
        val nodes = listOf(LensNestedNode("P", 140f, 30f), LensNestedNode("C", 100f, 40f, parent = 0), LensNestedNode("X", 120f, 40f))
        val edges = listOf(LensEdgeSpec(1, 2))
        val a = GraphLayouts.arrangeNested(nodes, edges, LensLayoutKind.LAYERED)
        val moved = GraphLayouts.withMovedNested(a, nodes, edges, mapOf(0 to LensPoint(a.boxes[0].x + 300f, a.boxes[0].y + 50f)))
        assertEquals(a.boxes[1].x + 300f, moved.boxes[1].x, 0.01f, "中の箱も同じだけ動く")
        assertEquals(a.boxes[1].y + 50f, moved.boxes[1].y, 0.01f)
        assertEquals(a.boxes[2], moved.boxes[2], "外の箱は動かない")
        assertEquals(2, moved.routes[0].points.size, "動いた箱につながる辺は直線")
        assertTrue(moved.width >= moved.boxes[0].x + moved.boxes[0].width, "全体の大きさが広がる")
    }

    @Test
    fun `stress keeps boxes apart`() {
        assertApart(GraphLayouts.arrange(nodes(10), chain(10) + star(10), LensLayoutKind.STRESS), "応力")
    }

    @Test
    fun `isolated nodes are placed apart in every kind`() {
        // 型だけで絞ると、参照を持たない節点だけ（辺なし）や、つながりと孤立の混在になる
        LensLayoutKind.entries.forEach { kind ->
            val alone = GraphLayouts.arrange(nodes(8), emptyList(), kind, origin = 0)
            assertWellFormed(alone, 8, 0)
            assertApart(alone, "$kind（辺なし）")
            val mixed = GraphLayouts.arrange(nodes(9), chain(4), kind, origin = 0)
            assertWellFormed(mixed, 9, 3)
            assertApart(mixed, "$kind（孤立の混在）")
        }
    }

    @Test
    fun `parallel edges between the same pair are drawn apart`() {
        val edges = listOf(LensEdgeSpec(0, 1), LensEdgeSpec(0, 1), LensEdgeSpec(1, 0))
        val result = GraphLayouts.arrange(nodes(2), edges, LensLayoutKind.STRESS)
        val labels = result.routes.map { it.label!! }
        assertEquals(3, labels.distinct().size, "同じ 2 点間の辺はずれて並ぶ: $labels")
        assertNotEquals(result.routes[0].points, result.routes[1].points)
    }

    @Test
    fun `degenerate inputs do not crash`() {
        assertEquals(0, GraphLayouts.arrange(emptyList(), emptyList(), LensLayoutKind.LAYERED).boxes.size)
        LensLayoutKind.entries.forEach { kind ->
            val single = GraphLayouts.arrange(nodes(1), emptyList(), kind, origin = 0)
            assertEquals(1, single.boxes.size, "$kind: 節点 1 つ")
            // 自己参照・範囲外の辺は経路が空になる
            val odd = GraphLayouts.arrange(nodes(2), listOf(LensEdgeSpec(0, 0), LensEdgeSpec(5, 9), LensEdgeSpec(0, 1)), kind, origin = 0)
            assertEquals(2, odd.boxes.size)
            assertTrue(odd.routes[0].points.isEmpty() && odd.routes[1].points.isEmpty(), "$kind: 無効な辺の経路は空")
            assertTrue(odd.routes[2].points.size >= 2, "$kind: 有効な辺は描ける")
        }
    }

    @Test
    fun `moved boxes reroute only the touched edges and keep the rest`() {
        val edges = chain(4)
        val base = GraphLayouts.arrange(nodes(4), edges, LensLayoutKind.LAYERED)
        val moved = GraphLayouts.withMovedBoxes(base, edges, mapOf(3 to LensPoint(900f, 10f)))
        assertEquals(LensBox(900f, 10f, 120f, 40f), moved.boxes[3], "動かした節点は新しい位置")
        assertEquals(base.boxes.take(3), moved.boxes.take(3), "他の節点は動かない")
        assertEquals(base.routes[0], moved.routes[0], "触れない辺は元の経路")
        assertEquals(base.routes[1], moved.routes[1])
        assertEquals(2, moved.routes[2].points.size, "動かした節点につながる辺は直線に引き直す")
        assertTrue(moved.routes[2].points.last().x > 800f, "直線の終点は新しい箱の縁: ${moved.routes[2]}")
        assertTrue(moved.width >= 1020f, "全体の大きさは広がる: ${moved.width}")
        assertEquals(base, GraphLayouts.withMovedBoxes(base, edges, emptyMap()), "動かした節点が無ければそのまま")
    }

    @Test
    fun `fitViewport fits the content into the view without enlarging`() {
        val fit = fitViewport(2000f, 1000f, 1000f, 1000f)
        assertEquals(0.5f, fit.zoom)
        assertEquals(0f, fit.panX)
        assertEquals(250f, fit.panY)
        val small = fitViewport(200f, 100f, 1000f, 1000f)
        assertEquals(1f, small.zoom, "小さい絵は拡大しない")
        assertEquals(400f, small.panX)
        assertEquals(LensViewport(1f, 0f, 0f), fitViewport(0f, 0f, 100f, 100f))
    }
}
