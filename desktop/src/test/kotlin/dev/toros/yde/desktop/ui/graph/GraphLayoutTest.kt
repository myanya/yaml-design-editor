package dev.toros.yde.desktop.ui.graph

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** R8-05: 参照グラフの力学レイアウト（L-06）。 */
class GraphLayoutTest {

    private fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>): Float =
        hypot(a.first - b.first, a.second - b.second)

    @Test
    fun `layout returns positions within bounds for all nodes`() {
        val positions = forceLayout(30, (0 until 29).map { it to it + 1 }, 800f, 600f)
        assertEquals(30, positions.size)
        positions.forEach { (x, y) ->
            assertTrue(x in 10f..790f, "x=$x が枠内にあること")
            assertTrue(y in 10f..590f, "y=$y が枠内にあること")
        }
    }

    @Test
    fun `layout is deterministic for the same seed`() {
        val edges = listOf(0 to 1, 1 to 2, 2 to 0)
        assertEquals(forceLayout(4, edges, 400f, 400f), forceLayout(4, edges, 400f, 400f))
    }

    @Test
    fun `connected nodes end up closer than disconnected ones`() {
        // 2つの独立クラスタ {0,1} と {2,3}
        val positions = forceLayout(4, listOf(0 to 1, 2 to 3), 800f, 600f)
        assertTrue(dist(positions[0], positions[1]) < dist(positions[0], positions[2]))
        assertTrue(dist(positions[2], positions[3]) < dist(positions[1], positions[3]))
    }

    @Test
    fun `degenerate graphs do not crash`() {
        assertEquals(emptyList(), forceLayout(0, emptyList(), 100f, 100f))
        assertEquals(1, forceLayout(1, emptyList(), 100f, 100f).size)
        // 自己参照・範囲外の辺は無視される
        assertEquals(2, forceLayout(2, listOf(0 to 0, 5 to 9), 100f, 100f).size)
    }
}
