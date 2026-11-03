package dev.toros.yde.desktop.ui.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import dev.toros.yde.app.view.flow.*
import dev.toros.yde.desktop.*
import dev.toros.yde.desktop.ui.common.GraphLayouts
import dev.toros.yde.desktop.ui.graph.GraphLensView
import dev.toros.yde.semantic.*
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/** OS のポインタを動かさず、実際の Canvas のジェスチャーと保存を検査する。 */
@OptIn(ExperimentalTestApi::class)
class DiagramPositionUiTest {
    @Test fun `フローの移動を保存し連続ドラッグのキャンセルと解除と背景パンを区別する`() = runComposeUiTest {
        val root = createTempDirectory("flow-drag-ui")
        val state = AppState()
        try {
            state.openWorkspace(root)
            val context = DiagramContext(mapOf("kind" to "flow", "view" to "test", "document" to "doc.yaml"),
                mapOf("mode" to listOf("plain"), "direction" to listOf("down")))
            val node = FlowNode(listOf("nodes", 0), "N", "工程", "box", null, FlowDetail(null, emptyList(), false))
            val other = node.copy(output = listOf("nodes", 1), key = "B", label = "次工程")
            val model = FlowModel(listOf(node, other), emptyList(), emptyList())
            var visible by mutableStateOf(true)
            setContent { Box(Modifier.size(1000.dp, 700.dp)) { if (visible) FlowCanvas(state, model, "down", FlowLayoutMode.PLAIN,
                node.output, Modifier.size(1000.dp, 700.dp), context) { _, _ -> } } }
            waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("flow-canvas").fetchSemanticsNodes().isNotEmpty() }
            val memory = state.diagramPositions(context)
            waitUntil(timeoutMillis = 10_000) { memory.positions.size == 2 && !memory.saving }
            val otherBefore = memory.positions.getValue(DiagramNodeId("flow", "B"))
            onNodeWithText("表示をリセット").performClick()
            val initial = GraphLayouts.arrangeFlow(model, emptyMap(), mode = FlowLayoutMode.PLAIN).nodes.getValue(node.output)
            val scale = density.density
            val canvas = onNodeWithTag("flow-canvas")
            canvas.performMouseInput {
                moveTo(Offset(initial.center.x, initial.center.y)); press()
                repeat(5) { moveBy(Offset(20f, 10f), 40) }; release()
            }
            waitUntil(timeoutMillis = 10_000) { memory.positions.values.any { it.pinned } && !memory.saving }
            val first = memory.positions.getValue(DiagramNodeId("flow", "N"))
            assertEquals(otherBefore, memory.positions.getValue(otherBefore.node), "移動していないノードは未固定のまま同じ位置")
            assertTrue(first.x * scale > initial.x + 60f, "同一フレームの移動量も累積する: $first")
            val store = DiagramPositionStore(root, state.layout)
            assertEquals(memory.positions, store.load(context).positions)
            runOnIdle { state.openFile(root.resolve(store.relative(context))) }
            assertEquals(root.resolve(store.relative(context)), state.openPreview, "配置記録は原本文書の編集に入れない")
            waitForIdle()
            canvas.performMouseInput {
                moveTo(Offset(first.x * scale + initial.width / 2, first.y * scale + initial.height / 2))
                press(); moveBy(Offset(100f, 80f), 100)
            }
            canvas.performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
            canvas.performMouseInput { release() }
            waitForIdle()
            assertEquals(first, memory.positions[first.node], "Esc では記録を変えない")
            canvas.performMouseInput { moveTo(Offset(700f, 500f)); press(); moveBy(Offset(40f, 40f), 100); release() }
            waitForIdle()
            assertEquals(first, memory.positions[first.node], "背景パンは記録しない")
            runOnIdle { visible = false }; waitForIdle()
            runOnIdle { visible = true }; waitForIdle()
            onNodeWithText("選択ノードの固定を解除").assertExists().performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { memory.positions.values.none { it.pinned } && !memory.saving }
            assertEquals(first.copy(pinned = false), memory.positions[first.node], "解除だけでは移動しない")
            assertEquals(otherBefore, memory.positions[otherBefore.node])
            onNodeWithText("再配置").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { memory.positions[first.node] != first.copy(pinned = false) && !memory.saving }
            assertEquals(memory.positions, store.load(context).positions)
        } finally {
            state.close()
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun `レンズのドラッグを保存し画面復帰後も解除できる`() = runComposeUiTest {
        val root = createTempDirectory("lens-drag-ui")
        val state = AppState()
        try {
            Files.createDirectories(root.resolve("_schemas"))
            Files.writeString(root.resolve("_schemas/node.yaml"), """
                id: node
                match: doc.yaml
                root:
                  type: object
                  fields:
                    - { key: id, type: id }
            """.trimIndent())
            Files.writeString(root.resolve("doc.yaml"), "id: N-001\n")
            state.openWorkspace(root)
            var visible by mutableStateOf(true)
            setContent { Box(Modifier.size(1000.dp, 700.dp)) { if (visible) GraphLensView(state) } }
            waitUntil(timeoutMillis = 15_000) { state.memory.diagramPositions.values.any { it.ready && it.positions.isNotEmpty() && !it.saving } &&
                onAllNodesWithText("配置を計算中…").fetchSemanticsNodes().isEmpty() }
            onNodeWithText("全体").performScrollTo().performClick()
            onNodeWithTag("lens-canvas").performMouseInput {
                moveTo(center); press(); repeat(5) { moveBy(Offset(20f, 10f), 40) }; release()
            }
            val memory = state.memory.diagramPositions.values.single()
            waitUntil(timeoutMillis = 10_000) { memory.positions.values.any { it.pinned } && !memory.saving }
            assertEquals("N-001", memory.positions.values.single().node.key)
            val context = state.memory.diagramPositions.keys.single()
            assertEquals(memory.positions, DiagramPositionStore(root, state.layout).load(context).positions)
            val saved = memory.positions
            onNodeWithText("全体").performScrollTo().performClick()
            onNodeWithTag("lens-canvas").performMouseInput { moveTo(center); press(); moveBy(Offset(-70f, -60f), 100) }
            onNodeWithTag("lens-canvas").performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
            onNodeWithTag("lens-canvas").performMouseInput { release() }
            waitForIdle()
            assertEquals(saved, memory.positions)
            runOnIdle { visible = false }; waitForIdle()
            runOnIdle { visible = true }; waitForIdle()
            onNodeWithText("再配置").performScrollTo().performClick()
            waitForIdle()
            waitUntil(timeoutMillis = 10_000) { !memory.saving }
            assertEquals(saved, memory.positions, "再配置しても手動固定は維持する")
            onNodeWithText("配置を初期化").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { memory.positions.isNotEmpty() && memory.positions.values.none { it.pinned } && !memory.saving }
            assertEquals(memory.positions, DiagramPositionStore(root, state.layout).load(context).positions)
        } finally {
            state.close()
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
