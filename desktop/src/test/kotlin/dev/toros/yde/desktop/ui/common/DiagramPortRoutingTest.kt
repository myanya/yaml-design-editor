package dev.toros.yde.desktop.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import dev.toros.yde.semantic.*
import kotlin.test.*

internal val portTestColors = DiagramColors(Color.Black, Color.Gray, Color.Blue, Color.Yellow, Color.Red, Color.White, Color.Black)

internal fun portTestBody(measurer: TextMeasurer, names: List<String> = listOf("id", "parent_id"), width: Float = 180f): DiagramBody {
    val part = DiagramPart(emptyList(), "box", width = width, children = names.mapIndexed { i, name ->
        DiagramPart(listOf("children", i), "group", port = name, padding = 3f, children = listOf(
            DiagramPart(listOf("children", i, "children", 0), "text", text = name)))
    })
    return DiagramBody(DiagramRendering(node = part), measureDiagram(part, measurer, portTestColors), emptyMap(), portTestColors)
}

@OptIn(ExperimentalTestApi::class)
class DiagramPortRoutingTest {
    @Test fun `行の順序と折り返しを再計測してポート位置へ反映する`() = runComposeUiTest {
        lateinit var measurer: TextMeasurer
        setContent { measurer = rememberTextMeasurer() }
        waitForIdle()
        val longName = "長いカラム名を何行にも折り返して表示するための値"
        val first = portTestBody(measurer, listOf("id", longName, "parent_id"), 100f)
        val ports = first.portPositions()
        val row = first.measured.children[2]
        assertEquals(row.first.y + row.second.size.height / 2, ports.getValue("parent_id").y)
        assertTrue(first.measured.children[1].second.size.height > first.measured.children[0].second.size.height)
        val reordered = portTestBody(measurer, listOf("parent_id", longName, "id"), 100f)
        assertTrue(reordered.portPositions().getValue("parent_id").y < ports.getValue("parent_id").y)
        val duplicates = portTestBody(measurer, listOf("id", "id"))
        assertTrue(duplicates.portPositions().isEmpty())
        val clippedPart = first.rendering.node!!.copy(height = row.first.y - 1f)
        val clipped = DiagramBody(DiagramRendering(node = clippedPart), measureDiagram(clippedPart, measurer, portTestColors), emptyMap(), portTestColors)
        assertFalse("parent_id" in clipped.portPositions(), "見えない行には配線しない")
    }

    @Test fun `近い行でも端点を平均せず指定した行の左右へ接続する`() {
        val a = LensBox(0f, 0f, 100f, 100f); val b = LensBox(240f, 0f, 100f, 100f)
        val style = DiagramEdgeStyle(sourcePort = "fk", targetPort = "pk")
        val source = mapOf("fk" to DiagramPortPosition(50f, 40f, "auto"))
        val target = mapOf("pk" to DiagramPortPosition(50f, 43f, "auto"))
        val path = routeDiagramPorts(a, b, style, source, target, listOf(a, b))
        assertEquals(LensPoint(100f, 40f), path.first())
        assertEquals(LensPoint(240f, 43f), path.last())
        val reverse = routeDiagramPorts(b, a, style.reversedEndpoints(), target, source, listOf(a, b))
        assertEquals(path.last(), reverse.first()); assertEquals(path.first(), reverse.last())
        assertTrue(routeDiagramPorts(a, b, style.copy(targetPort = "missing"), source, target, listOf(a, b)).isEmpty())
        assertTrue(routeDiagramPorts(a, b, DiagramEdgeStyle(portsValid = false), source, target, listOf(a, b)).isEmpty())
    }

    @Test fun `明示した四面を変更せず障害物を避け自己参照も同じ枠へ戻る`() {
        val a = LensBox(0f, 0f, 100f, 100f); val b = LensBox(300f, 0f, 100f, 100f)
        val obstacle = LensBox(150f, -20f, 80f, 150f)
        val expected = mapOf("left" to LensPoint(0f, 30f), "right" to LensPoint(100f, 30f),
            "top" to LensPoint(40f, 0f), "bottom" to LensPoint(40f, 100f))
        for ((side, point) in expected) {
            val path = routeDiagramPorts(a, b, DiagramEdgeStyle(sourcePort = "p", targetPort = "q"),
                mapOf("p" to DiagramPortPosition(40f, 30f, side)), mapOf("q" to DiagramPortPosition(40f, 70f, "left")), listOf(a, b, obstacle))
            assertTrue(path.isNotEmpty(), side)
            assertEquals(point.x, path.first().x, .001f, side)
            assertEquals(point.y, path.first().y, .001f, side)
            assertEquals(300f, path.last().x, .001f)
            assertEquals(70f, path.last().y, .001f)
            assertTrue(path.zipWithNext().none { (s, t) -> OrthogonalRouting.intersects(s, t, obstacle) })
        }
        val ports = mapOf("p" to DiagramPortPosition(40f, 30f, "auto"), "q" to DiagramPortPosition(40f, 70f, "auto"))
        val loop = routeDiagramPorts(a, a, DiagramEdgeStyle(sourcePort = "p", targetPort = "q"), ports, ports, listOf(a))
        assertEquals(100f, loop.first().x, .001f); assertEquals(30f, loop.first().y, .001f)
        assertEquals(100f, loop.last().x, .001f); assertEquals(70f, loop.last().y, .001f)
        assertTrue(loop.any { it.x > 100f })
    }
}
