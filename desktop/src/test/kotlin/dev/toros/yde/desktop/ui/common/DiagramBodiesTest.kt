package dev.toros.yde.desktop.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.toros.yde.semantic.*
import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class DiagramBodiesTest {
    @Test fun `画像と複数行の名前を計測し同じ座標で描画と原本への移動を行う`() = runComposeUiTest {
        lateinit var measurer: TextMeasurer
        setContent { measurer = rememberTextMeasurer() }
        waitForIdle()
        val colors = DiagramColors(Color.Black, Color.Gray, Color.Blue, Color.Yellow, Color.Red, Color.White, Color.Black)
        val part = DiagramPart(emptyList(), "box", width = 160f, align = "center", children = listOf(
            DiagramPart(listOf("children", 0), "image", source = "face.png", width = 80f, height = 60f),
            DiagramPart(listOf("children", 1), "text", text = "とても長い登場人物の名前と肩書きを折り返して表示します", maxLines = 3),
        ))
        val measured = measureDiagram(part, measurer, colors)
        assertEquals(160f, measured.size.width)
        val image = measured.children[0]
        val name = measured.children[1]
        assertTrue(name.first.y >= image.first.y + image.second.size.height)
        assertTrue(name.second.text!!.lineCount in 2..3)
        assertTrue(measured.size.height >= name.first.y + name.second.size.height)
        val source = DocumentId("people/a.yaml")
        val rendering = DiagramRendering(node = part, origins = listOf(
            TransformOrigin(listOf("children", 0, "source"), source, listOf("portrait")),
            TransformOrigin(listOf("children", 1, "text"), source, listOf("name")),
        ))
        val bitmap = ImageBitmap(20, 20)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(20f, 20f)) { drawRect(Color.Red) }
        val body = DiagramBody(rendering, measured, mapOf(DiagramImageKey(source, "face.png") to LoadedImage.Raster(bitmap)), colors)
        assertEquals(DiagramLocation(source, listOf("name")), body.locationAt(name.first + Offset(2f, 2f)))
        assertNull(body.locationAt(Offset(-1f, 0f)))
        val exported = ImageBitmap(200, 240)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(exported), Size(200f, 240f)) { drawDiagram(body) }
        val pixels = exported.toAwtImage()
        assertEquals(java.awt.Color.RED.rgb, pixels.getRGB(80, 30), "画像出力でも画像を同じ場所に描く")
        assertEquals(0, pixels.getRGB(190, 230), "部品の外へ描かない")
        val diamond = measureDiagram(DiagramPart(emptyList(), "box", shape = "diamond", width = 240f,
            children = listOf(DiagramPart(listOf("children", 0), "text", text = "判断する項目"))), measurer, colors)
        val child = diamond.children.single()
        assertTrue(child.first.x >= diamond.size.width / 4)
        assertTrue(child.first.y >= diamond.size.height / 4)
        assertNull(diamond.hit(Offset(1f, 1f)))
    }

    @Test fun `菱形の接続点は直交線を保ち輪郭へ延ばす`() {
        val result = LensArrangement(LensLayoutKind.LAYERED, listOf(LensBox(0f, 0f, 100f, 80f), LensBox(200f, 0f, 100f, 80f)),
            listOf(LensRoute(listOf(LensPoint(100f, 60f), LensPoint(200f, 60f)), null)), 324f, 104f)
        val adjusted = GraphLayouts.withDiamondOutlines(result, setOf(0), listOf(LensEdgeSpec(0, 1)))
        assertEquals(LensPoint(75f, 60f), adjusted.routes.single().points.first())
        assertEquals(result.routes.single().points.last(), adjusted.routes.single().points.last())
    }
}
