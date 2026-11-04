package dev.toros.yde.desktop.ui.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import dev.toros.yde.app.view.flow.FlowLayoutOption
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.DiagramPositionMemory
import dev.toros.yde.desktop.ui.graph.GraphLensView
import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/** 図の「配置の調整」と「線のラベル」: 押しても閉じないメニュー・選んだ組み合わせごとの配置の記録・効かない調整の案内・ラベルを隠しても箱を動かさない。 */
@OptIn(ExperimentalTestApi::class)
class DiagramLayoutOptionsUiTest {
    private fun copyDiagrams(): Path {
        val root = createTempDirectory("layout-options-ui")
        val sample = Path.of("samples/diagrams")
        Files.walk(sample).use { paths -> paths.forEach { source ->
            val relative = sample.relativize(source)
            if (!relative.startsWith(Path.of(".yde/state"))) {
                val target = root.resolve(relative.toString())
                if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
            }
        } }
        return root
    }

    private fun AppState.saved(kind: String, options: List<String>?): DiagramPositionMemory? =
        memory.diagramPositions.entries.firstOrNull { (context, m) ->
            context.diagram["kind"] == kind && context.selection["options"] == options && m.ready && !m.saving && m.positions.isNotEmpty()
        }?.value

    @Test fun `フローの配置の調整は組み合わせごとに位置を覚え 外すと前の並びに戻る`() = runComposeUiTest {
        val root = copyDiagrams()
        val state = AppState()
        try {
            state.openWorkspace(root)
            val document = DocumentId("data/people.yaml")
            state.openFlowView("people-flow", document)
            setContent { Box(Modifier.size(1400.dp, 900.dp)) { FlowViewPane(state) } }
            waitUntil(timeoutMillis = 20_000) { state.saved("flow", null) != null }
            val plain = state.saved("flow", null)!!.positions

            // 線のラベルを隠しても箱は動かさず、位置の記録も分けない
            onNodeWithTag("diagram-label-toggle").performClick()
            assertEquals(true, state.flowDisplayOptions["people-flow" to document]?.hideLabels)
            waitForIdle()
            assertEquals(plain, state.saved("flow", null)!!.positions)
            assertEquals(1, state.memory.diagramPositions.keys.count { it.diagram["kind"] == "flow" })

            onNodeWithTag("diagram-layout-options").performClick()
            onNodeWithText("ラベルの席を取らない").performClick()
            // 押してもメニューは閉じず、続けて選べる
            onNodeWithText("間隔を詰める").performClick()
            assertEquals(setOf(FlowLayoutOption.NO_LABEL_SEATS, FlowLayoutOption.COMPACT), state.flowDisplayOptions["people-flow" to document]?.options)
            waitUntil(timeoutMillis = 20_000) { state.saved("flow", listOf("compact", "no-label-seats")) != null }
            assertNotEquals(plain, state.saved("flow", listOf("compact", "no-label-seats"))!!.positions, "調整した配置は別に覚える")

            onNodeWithText("ラベルの席を取らない").performClick()
            onNodeWithText("間隔を詰める").performClick()
            waitForIdle()
            assertEquals(emptySet(), state.flowDisplayOptions["people-flow" to document]?.options)
            assertEquals(plain, state.saved("flow", null)!!.positions, "外すと前の並びに戻る")
        } finally {
            state.close()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun `レンズの配置の調整は選んだ配置の種類に効くものだけを選べる`() = runComposeUiTest {
        val root = copyDiagrams()
        val state = AppState()
        try {
            state.openWorkspace(root)
            setContent { Box(Modifier.size(1400.dp, 900.dp)) { GraphLensView(state) } }
            // 起点が無いときの既定は応力配置
            waitUntil(timeoutMillis = 20_000) { state.saved("lens", null) != null }
            val plain = state.saved("lens", null)!!.positions
            onNodeWithTag("diagram-label-toggle").performScrollTo().performClick()
            assertEquals(true, state.memory.lensHideLabels)
            waitForIdle()
            assertEquals(plain, state.saved("lens", null)!!.positions, "線のラベルを隠しても箱は動かさない")
            onNodeWithTag("diagram-layout-options").performScrollTo().performClick()
            onNode(hasText("ラベルの席を取らない", substring = true) and hasClickAction()).assertIsNotEnabled()
            onNodeWithText("階層配置で効きます", substring = true).assertExists()
            onNode(hasText("線を短めに", substring = true) and hasClickAction()).assertIsEnabled().performClick()
            assertEquals(true, state.memory.lensLayoutOptions["SHORT_EDGES"])
            waitUntil(timeoutMillis = 20_000) { state.saved("lens", listOf("short-edges")) != null }
            onNodeWithText("配置の調整（1）").assertExists()
        } finally {
            state.close()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
