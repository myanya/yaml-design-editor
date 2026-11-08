package dev.toros.yde.desktop.ui.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.toros.yde.app.view.flow.*
import dev.toros.yde.desktop.*
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/** 線のラベルを動かすと経由点として記録し、線はそこを通る（ADR-0028）。実際の Canvas のジェスチャーで確かめる。 */
@OptIn(ExperimentalTestApi::class)
class FlowEdgeViaUiTest {
    private val nodeA = FlowNode(listOf("nodes", 0), "A", "工程A", "box", null, FlowDetail(null, emptyList(), false))
    private val nodeB = FlowNode(listOf("nodes", 1), "B", "工程B", "box", null, FlowDetail(null, emptyList(), false))
    private val edge = FlowEdge(listOf("edges", 0), "e1", "進む",
        FlowConnection("A", FlowConnectionState.RESOLVED, nodeA.output), FlowConnection("B", FlowConnectionState.RESOLVED, nodeB.output),
        FlowDetail(null, emptyList(), false))
    private val model = FlowModel(listOf(nodeA, nodeB), listOf(edge), emptyList())
    private val via = DiagramNodeId(FLOW_EDGE_KIND, "e1")

    @Test fun `ラベルをドラッグすると経由点を記録し選んだ線は自動に戻せる`() = runComposeUiTest {
        val root = createTempDirectory("flow-via-ui")
        val state = AppState()
        try {
            state.openWorkspace(root)
            val context = DiagramContext(mapOf("kind" to "flow", "view" to "via", "document" to "doc.yaml"), emptyMap())
            var selected by mutableStateOf<List<Any>?>(null)
            setContent { CompositionLocalProvider(LocalDensity provides Density(1.5f)) {
                Box(Modifier.size(1000.dp, 700.dp)) { FlowCanvas(state, model, "down", FlowLayoutMode.PLAIN,
                    selected, Modifier.size(1000.dp, 700.dp), context) { _, path -> selected = path } }
            } }
            val memory = state.diagramPositions(context)
            waitUntil(timeoutMillis = 15_000) { memory.positions.size == 2 && !memory.saving }
            onNodeWithText("表示をリセット").performScrollTo().performClick()
            // 経由点を置いた線（記録の中心＝ラベルの中心）を、ラベルごとドラッグして動かす
            runOnIdle { state.saveDiagramPositions(context, memory.positions + (via to DiagramPosition(via, null, 300f, 300f, true))) }
            waitUntil(timeoutMillis = 10_000) { memory.positions[via] != null && !memory.saving }
            waitForIdle()
            onNodeWithTag("flow-canvas").performMouseInput {
                moveTo(Offset(300f * 1.5f, 300f * 1.5f)); press()
                repeat(40) { moveBy(Offset(2f, 1f), 16) }; release()
            }
            waitUntil(timeoutMillis = 10_000) { memory.positions[via]?.let { it.x > 330f } == true && !memory.saving }
            val moved = memory.positions.getValue(via)
            assertEquals(300f + 80f / 1.5f, moved.x, .5f)
            assertEquals(300f + 40f / 1.5f, moved.y, .5f)
            assertTrue(moved.pinned)
            assertEquals(2, memory.positions.keys.count { it.kind == "flow" }, "箱の位置は変えない")
            // ラベルを押して線を選ぶと「選択した線を自動に戻す」で記録から外せる
            onNodeWithTag("flow-canvas").performMouseInput { click(Offset(moved.x * 1.5f, moved.y * 1.5f)) }
            runOnIdle { assertEquals(edge.output, selected) }
            onNodeWithText("選択した線を自動に戻す").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { memory.positions[via] == null && !memory.saving }
        } finally {
            state.close()
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun `読むだけの図は同じ経由点で描きラベルのドラッグでは記録を変えない`() = runComposeUiTest {
        val root = createTempDirectory("flow-via-read")
        val state = AppState()
        try {
            state.openWorkspace(root)
            val context = DiagramContext(mapOf("kind" to "flow", "view" to "via", "document" to "doc.yaml"), emptyMap())
            val memory = state.diagramPositions(context)
            waitUntil(timeoutMillis = 15_000) { memory.ready }
            runOnIdle { state.saveDiagramPositions(context, mapOf(via to DiagramPosition(via, null, 300f, 300f, true))) }
            waitUntil(timeoutMillis = 10_000) { memory.positions[via] != null && !memory.saving }
            setContent { CompositionLocalProvider(LocalDensity provides Density(1.5f)) {
                Box(Modifier.size(1000.dp, 700.dp)) { FlowCanvas(state, model, "down", FlowLayoutMode.PLAIN,
                    null, Modifier.size(1000.dp, 700.dp), context, editable = false) { _, _ -> } }
            } }
            waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("flow-canvas").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("表示をリセット").performScrollTo().performClick()
            onAllNodesWithText("選択した線を自動に戻す").assertCountEquals(0)
            val before = memory.positions
            onNodeWithTag("flow-canvas").performMouseInput {
                moveTo(Offset(300f * 1.5f, 300f * 1.5f)); press()
                repeat(20) { moveBy(Offset(2f, 1f), 16) }; release()
            }
            waitForIdle()
            assertEquals(before, memory.positions, "読むだけの図は記録を書かない（初回の自動配置も保存しない）")
        } finally {
            state.close()
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
