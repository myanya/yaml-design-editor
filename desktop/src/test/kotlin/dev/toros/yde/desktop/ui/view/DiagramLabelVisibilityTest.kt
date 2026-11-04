package dev.toros.yde.desktop.ui.view

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.toros.yde.app.view.flow.*
import dev.toros.yde.desktop.ui.common.*
import dev.toros.yde.semantic.*
import kotlin.test.*

/** 「線のラベル」を隠す: 中央のラベルだけを外し、端のラベルと選んだ線のラベルは残す。全体の範囲（全体表示・PNG）からも外す。 */
class DiagramLabelVisibilityTest {
    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)

    @Test fun `記法の線は中央のラベルだけを外し端の多重度は残す`() {
        val style = DiagramEdgeStyle(label = "注文する", sourceLabel = "1", targetLabel = "0..*")
        val visual = DiagramEdgeVisual(style, listOf("注文する", "1", "0..*").associateWith { measurer.measure(it) }, diagramColors())
        val points = listOf(Offset(0f, 0f), Offset(300f, 0f))
        assertEquals(listOf("注文する", "1", "0..*"), diagramEdgeLabels(visual, points).map { it.text.layoutInput.text.text })
        assertEquals(listOf("1", "0..*"), diagramEdgeLabels(visual.withoutCenterLabel(), points).map { it.text.layoutInput.text.text })
    }

    @Test fun `フローで隠すと全体の範囲から外し 選んだ線のラベルは出す`() {
        val detail = FlowDetail(null, emptyList(), false)
        val a = FlowNode(listOf("nodes", 0), "A", "A", "box", null, detail)
        val b = a.copy(output = listOf("nodes", 1), key = "B")
        val edge = FlowEdge(listOf("edges", 0), "E", "とても長い条件のラベル",
            FlowConnection("A", FlowConnectionState.RESOLVED, a.output), FlowConnection("B", FlowConnectionState.RESOLVED, b.output), detail)
        val arrangement = FlowArrangement(mapOf(a.output to FlowRect(0f, 0f, 100f, 40f), b.output to FlowRect(300f, 0f, 100f, 40f)), emptyMap(),
            listOf(FlowRoute(edge.output, listOf(FlowPoint(100f, 20f), FlowPoint(300f, 20f)), FlowRect(150f, 200f, 160f, 30f))), FlowSize(400f, 240f))
        val scene = FlowScene(FlowModel(listOf(a, b), listOf(edge), emptyList()), arrangement,
            mapOf(edge.output to measurer.measure("とても長い条件のラベル")), emptyMap(), emptyMap(), diagramColors(), Color.Gray)
        val hidden = scene.copy(hideLabels = true)
        assertTrue(scene.showsLabel(edge.output))
        assertFalse(hidden.showsLabel(edge.output))
        assertTrue(hidden.showsLabel(edge.output, selected = edge.output), "選んだ線のラベルは出す")
        assertTrue(flowBounds(hidden).bottom < flowBounds(scene).bottom, "隠したラベルは全体の範囲に入れない")
        assertEquals(scene.arrangement, hidden.arrangement, "描き方だけを変え、配置は変えない")
    }
}
