package dev.toros.yde.desktop.ui.common

import dev.toros.yde.desktop.DiagramGrid
import kotlin.test.*

class DiagramGridTest {
    @Test fun `細かい移動の累積は表示密度とズームによらず同じ保存座標に吸着する`() {
        for (density in listOf(1f, 1.5f, 2f)) for (zoom in listOf(.25f, 1f, 2.5f)) {
            val drag = DiagramDrag(LensPoint(-19f * density, 3f * density))
            var result = LensPoint(0f, 0f)
            repeat(35) { result = drag.move(density * zoom, -density * zoom, zoom, DiagramGrid(true), density, DiagramDragFrame()) }
            assertEquals(16f, result.x / density, .001f)
            assertEquals(-32f, result.y / density, .001f)
        }
    }

    @Test fun `親の内容原点から吸着し端では枠内の格子点を選ぶ`() {
        val frame = DiagramDragFrame(LensPoint(13f, 59f), 13f, 59f, 48f, 88f)
        val grid = DiagramGrid(true)
        assertEquals(LensPoint(45f, 75f), grid.position(LensPoint(999f, 999f), 1f, frame))
        assertEquals(LensPoint(13f, 59f), grid.position(LensPoint(-100f, -100f), 1f, frame))
        val cramped = frame.copy(maxX = 15f, maxY = 60f)
        assertEquals(frame.origin, grid.position(LensPoint(14f, 60f), 1f, cramped))
        val outside = DiagramDragFrame(minY = 103f)
        assertEquals(112f, grid.position(LensPoint(0f, 100f), 1f, outside).y, "未割当ノードはレーン外で吸着")
    }

    @Test fun `吸着を切ると丸めず移動し境界からの戻りにも余分な移動を要求しない`() {
        val frame = DiagramDragFrame(minX = 0f, maxX = 35f)
        val drag = DiagramDrag(LensPoint(0f, 3f))
        assertEquals(LensPoint(32f, 0f), drag.move(200f, 0f, 1f, DiagramGrid(true), 1f, frame))
        assertEquals(LensPoint(25f, 3f), drag.move(-10f, 0f, 1f, DiagramGrid(), 1f, frame))
    }
}
