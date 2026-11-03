package dev.toros.yde.desktop.ui.common

import dev.toros.yde.desktop.DiagramGrid
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.*

class DiagramGridDrawingTest {
    @Test fun `点はパンとズームと親原点に追従し箱と見出しへは重ならない`() {
        val image = ImageBitmap(160, 120)
        val container = LensBox(24f, 8f, 80f, 80f)
        val content = LensBox(24f, 24f, 80f, 64f)
        val child = LensBox(40f, 40f, 20f, 20f)
        val regions = listOf(DiagramGridRegion(LensPoint(0f, 0f), excluded = listOf(container)),
            DiagramGridRegion(LensPoint(24f, 24f), content, listOf(child)))
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(160f, 120f)) {
            drawRect(Color.White)
            drawDiagramGrid(DiagramGrid(true), Offset(5f, 7f), 1.5f, 1f, regions)
        }
        val pixels = image.toPixelMap()
        assertNotEquals(Color.White, pixels[5, 7], "最外層の原点")
        assertNotEquals(Color.White, pixels[89, 43], "親の内容原点からの格子")
        assertEquals(Color.White, pixels[53, 31], "親の見出しでは最外層の格子を除く")
        assertEquals(Color.White, pixels[53, 55], "親の内側で異なる原点の格子を二重に描かない")
        assertEquals(Color.White, pixels[65, 67], "子の箱には点を描かない")
    }

    @Test fun `縮小時に点を間引いても元の格子の位相を保ちOFFでは描かない`() {
        for (enabled in listOf(false, true)) {
            val image = ImageBitmap(32, 32)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(32f, 32f)) {
                drawRect(Color.White)
                drawDiagramGrid(DiagramGrid(enabled), Offset(3f, 3f), .25f, 1f,
                    listOf(DiagramGridRegion(LensPoint(0f, 0f))))
            }
            val pixels = image.toPixelMap()
            assertEquals(enabled, pixels[11, 11] != Color.White)
            assertEquals(Color.White, pixels[7, 7])
        }
    }
}
