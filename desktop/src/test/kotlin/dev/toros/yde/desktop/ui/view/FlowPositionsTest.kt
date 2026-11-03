package dev.toros.yde.desktop.ui.view

import dev.toros.yde.app.view.flow.*
import dev.toros.yde.desktop.*
import dev.toros.yde.desktop.ui.common.GraphLayouts
import dev.toros.yde.semantic.*
import kotlin.test.*

class FlowPositionsTest {
    private val detail = FlowDetail(null, emptyList(), false)
    private fun node(index: Int, key: String?) = FlowNode(listOf("nodes", index), key, key, "box", null, detail)

    @Test fun `出力順が変わってもkeyで識別し重複と欠落は保存しない`() {
        val first = FlowModel(listOf(node(0, "A"), node(1, "B")), emptyList(), emptyList())
        val reordered = first.copy(nodes = listOf(node(0, "B"), node(1, "A")))
        assertEquals(flowPositionIds(first).getValue(listOf("nodes", 0)), flowPositionIds(reordered).getValue(listOf("nodes", 1)))
        val ambiguous = first.copy(nodes = listOf(node(0, "A"), node(1, "A"), node(2, null), node(3, "")))
        assertTrue(flowPositionIds(ambiguous).isEmpty())
    }

    @Test fun `識別できないレーンは最外層と取り違えない`() {
        val lane = FlowLane(listOf("lanes", 0), null, "担当")
        val node = node(0, "A").copy(lane = FlowConnection(null, FlowConnectionState.RESOLVED, lane.output))
        val model = FlowModel(listOf(node), emptyList(), listOf(lane))
        assertFalse(node.output in flowPositionFrames(model, FlowLayoutMode.GROUPED))
        assertTrue(node.output in flowPositionFrames(model, FlowLayoutMode.PLAIN))
        assertNull(flowPositionFrames(model, FlowLayoutMode.PLAIN)[node.output])
    }

    @Test fun `全表示の現在位置を復元しノードを追加しても既存の位置は保つ`() {
        val lanes = (0..1).map { FlowLane(listOf("lanes", it), "L$it", "担当$it") }
        val model = FlowModel((0..4).map { i -> node(i, "N$i").copy(lane = if (i < 4)
            FlowConnection("L${i / 2}", FlowConnectionState.RESOLVED, lanes[i / 2].output) else null) }, emptyList(), lanes)
        for (mode in FlowLayoutMode.entries) for (direction in listOf("down", "right")) {
            val first = GraphLayouts.arrangeFlow(model, emptyMap(), direction, mode)
            val target = model.nodes.first().output
            val box = first.nodes.getValue(target)
            val moved = flowDragPreview(first, target, FlowPoint(box.x + 5f, box.y + 8f), model)
            first.nodes.filterKeys { it != target }.forEach { (path, rect) -> assertEquals(rect, moved.nodes[path]) }
            val saved = flowPositionSnapshot(model, mode, direction, emptyMap(), moved, 1.5f, emptyMap(), target)
            assertEquals(1, saved.values.count { it.pinned })
            val context = DiagramContext(mapOf("kind" to "flow"), emptyMap())
            val restored = DiagramPositionCodec.decode(DiagramPositionCodec.encode(context, saved), context)
            fun arrange(next: FlowModel): FlowArrangement {
                val nodes = flowPositionIds(next).mapNotNull { (path, id) -> restored[id]?.let { path to FlowPoint(it.x * 1.5f, it.y * 1.5f) } }.toMap()
                val roots = flowLaneIds(next).mapNotNull { (path, id) -> restored[id]?.let { path to FlowPoint(it.x * 1.5f, it.y * 1.5f) } }.toMap()
                return GraphLayouts.arrangeFlow(next, emptyMap(), direction, mode, pins = nodes, lanePositions = roots)
            }
            for (next in listOf(model, model.copy(nodes = model.nodes + node(5, "NEW").copy(lane = model.nodes[0].lane)))) {
                val result = arrange(next)
                moved.nodes.forEach { (path, b) ->
                    assertEquals(b.x, result.nodes.getValue(path).x, .001f, "$mode $direction $path x")
                    assertEquals(b.y, result.nodes.getValue(path).y, .001f, "$mode $direction $path y")
                }
            }
        }
    }
}
