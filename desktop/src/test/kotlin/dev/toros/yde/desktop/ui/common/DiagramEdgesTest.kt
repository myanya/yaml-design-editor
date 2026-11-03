package dev.toros.yde.desktop.ui.common

import androidx.compose.ui.geometry.Offset
import dev.toros.yde.semantic.DiagramEdgeStyle
import kotlin.test.*

class DiagramEdgesTest {
    @Test fun `端の交換は記号と多重度だけを交換し二度で元に戻る`() {
        val style = DiagramEdgeStyle(stroke = "dashed", sourceMarker = "diamond", targetMarker = "triangle",
            label = "関係", sourceLabel = "1", targetLabel = "0..*")
        val reversed = style.reversedEndpoints()
        assertEquals("triangle", reversed.sourceMarker)
        assertEquals("diamond", reversed.targetMarker)
        assertEquals("0..*", reversed.sourceLabel)
        assertEquals("1", reversed.targetLabel)
        assertEquals("関係", reversed.label)
        assertEquals(style, reversed.reversedEndpoints())
    }

    @Test fun `端点記号は向きによらず折れ線の内側に収まり長さを共有する`() {
        val tip = Offset(30f, 40f)
        for (name in listOf("arrow", "triangle", "diamond", "filled-diamond")) {
            val right = diagramMarkerPoints(name, tip, tip + Offset(100f, 0f))
            val down = diagramMarkerPoints(name, tip, tip + Offset(0f, 100f))
            assertTrue(right.all { it.x >= tip.x })
            assertTrue(down.all { it.y >= tip.y })
            assertEquals(diagramMarkerLength(name), right.maxOf { it.x } - tip.x)
            assertEquals(diagramMarkerLength(name), down.maxOf { it.y } - tip.y)
            assertEquals(right.size, down.size)
        }
        assertTrue(diagramMarkerPoints("none", tip, tip + Offset(1f, 0f)).isEmpty())
        assertTrue(diagramMarkerPoints("triangle", tip, tip).isEmpty())
    }
}
