package dev.toros.yde.desktop.ui.graph

import androidx.compose.ui.graphics.Color
import dev.toros.yde.desktop.ui.common.*
import kotlin.test.*

class LensBoundsTest {
    @Test fun `負の固定位置を全体とミニマップの表示変換に含める`() {
        val result = GraphLayouts.arrangeNested(listOf(LensNestedNode("A", 120f, 80f)), emptyList(), LensLayoutKind.LAYERED,
            pins = mapOf("A" to LensPoint(-400f, -250f)))
        val scene = LensScene(LensArranged(emptyList(), emptyList(), emptyList(), result), emptySet(), { Color.Black },
            emptyMap(), emptyMap(), emptyMap(), emptyMap(), LensPaint(Color.Black, Color.White, Color.Blue, Color.Gray))
        val b = lensBounds(scene)
        assertTrue(result.width > 0 && result.height > 0)
        assertTrue(b.left < -400f && b.top < -250f)
        assertTrue(b.right > -280f && b.bottom > -170f)
        val fit = fitViewport(b.width, b.height, 200f, 140f)
        val ox = fit.panX - b.left * fit.zoom
        val oy = fit.panY - b.top * fit.zoom
        assertTrue(ox - 400f * fit.zoom >= 0)
        assertTrue(oy - 250f * fit.zoom >= 0)
        assertTrue(ox - 280f * fit.zoom <= 200f)
        assertTrue(oy - 170f * fit.zoom <= 140f)
    }
}
