package dev.toros.yde.desktop.ui.view

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import dev.toros.yde.app.view.flow.*
import dev.toros.yde.desktop.ui.common.*
import dev.toros.yde.semantic.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class FlowPortLayoutTest {
    @Test fun `全配置と移動の後に同じ名前の行へ接続し既存の箱の位置は変えない`() = runComposeUiTest {
        lateinit var measurer: TextMeasurer
        setContent { measurer = rememberTextMeasurer() }
        waitForIdle()
        val body = portTestBody(measurer)
        val detail = FlowDetail(null, emptyList(), false)
        val lanes = (0..1).map { FlowLane(listOf("lanes", it), "L$it", "担当$it") }
        val nodes = (0..1).map { FlowNode(listOf("nodes", it), "N$it", "テーブル$it", "box",
            FlowConnection("L$it", FlowConnectionState.RESOLVED, lanes[it].output), detail, body.rendering) }
        val edge = FlowEdge(listOf("edges", 0), "fk", null,
            FlowConnection("N0", FlowConnectionState.RESOLVED, nodes[0].output), FlowConnection("N1", FlowConnectionState.RESOLVED, nodes[1].output),
            detail, DiagramRendering(edge = DiagramEdgeStyle(sourcePort = "parent_id", targetPort = "id")))
        val model = FlowModel(nodes, listOf(edge), lanes)
        val bodies = nodes.associate { it.output to body }
        val sizes = nodes.associate { it.output to FlowSize(body.size.width, body.size.height) }
        val ports = body.portPositions()
        for (mode in FlowLayoutMode.entries) for (direction in listOf("right", "down")) {
            val base = GraphLayouts.arrangeFlow(model, sizes, direction, mode)
            val moved = flowDragPreview(base, nodes[0].output, FlowPoint(-250f, -90f), model, direction)
            for (arrangement in listOf(base, moved)) {
                val result = withFlowPorts(arrangement, model, bodies)
                assertEquals(arrangement.nodes, result.nodes)
                val route = result.edges.single().points
                assertTrue(route.isNotEmpty(), "$mode $direction")
                assertEquals(result.nodes.getValue(nodes[0].output).y + ports.getValue("parent_id").y, route.first().y, .01f)
                assertEquals(result.nodes.getValue(nodes[1].output).y + ports.getValue("id").y, route.last().y, .01f)
            }
            val legacy = model.copy(edges = listOf(edge.copy(rendering = null)))
            assertSame(base, withFlowPorts(base, legacy, bodies))
        }
    }
}
