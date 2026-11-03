package dev.toros.yde.desktop.ui.view

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import dev.toros.yde.app.view.flow.FlowModel
import dev.toros.yde.desktop.ui.common.*
import dev.toros.yde.semantic.*
import dev.toros.yde.yaml.model.YPath
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class FlowDrawingTest {
    @Test fun `PNGの境界は負の位置と端のラベルを含み同じ辺を描く`() = runComposeUiTest {
        lateinit var measurer: TextMeasurer
        setContent { measurer = rememberTextMeasurer() }
        waitForIdle()
        val colors = DiagramColors(Color.Black, Color.Gray, Color.Blue, Color.Yellow, Color.Red, Color.White, Color.Black)
        val style = DiagramEdgeStyle(sourceMarker = "diamond", targetMarker = "triangle", sourceLabel = "1", targetLabel = "0..*")
        val visual = DiagramEdgeVisual(style, listOf("1", "0..*").associateWith { measurer.measure(it) }, colors)
        val output: YPath = listOf("edges", 0)
        val points = listOf(FlowPoint(-40f, -30f), FlowPoint(180f, -30f))
        val arrangement = FlowArrangement(emptyMap(), emptyMap(), listOf(FlowRoute(output, points, FlowRect(40f, -30f, 1f, 1f))), FlowSize(200f, 50f))
        val scene = FlowScene(FlowModel(emptyList(), emptyList(), emptyList()), arrangement, emptyMap(), emptyMap(), mapOf(output to visual), colors, Color.LightGray)
        val bounds = flowBounds(scene)
        val labels = diagramEdgeLabels(visual, points.map { Offset(it.x, it.y) })
        labels.forEach { assertTrue(bounds.contains(it.bounds.topLeft) && bounds.contains(it.bounds.bottomRight)) }
        assertTrue(bounds.left < -40f && bounds.top < -30f)
        val dir = Files.createTempDirectory("diagram-png")
        try {
            val file = dir.resolve("flow.png")
            exportFlowPng(file, scene, Color.White, Density(1f))
            val image = ImageIO.read(file.toFile())
            assertTrue(image.width >= bounds.width.toInt())
            assertTrue(image.height >= bounds.height.toInt())
            val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            assertTrue(pixels.any { it != java.awt.Color.WHITE.rgb }, "白紙でなく辺と記号を書き出す")
        } finally { Files.deleteIfExists(dir.resolve("flow.png")); Files.delete(dir) }
    }
}
