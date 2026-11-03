package dev.toros.yde.desktop.ui.graph

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import dev.toros.yde.desktop.AppState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

/** 実サンプルを写し、記法の入力・計測・配線・画面の診断までを一緒に確かめる。 */
@OptIn(ExperimentalTestApi::class)
class LensPortsUiTest {
    @Test fun `ERサンプルをポート付きレンズで開き全ての外部キーを描画できる`() = runComposeUiTest {
        val root = createTempDirectory("lens-ports-ui")
        val state = AppState()
        try {
            val sample = Path.of("samples/diagrams")
            Files.walk(sample).use { paths -> paths.forEach { source ->
                val relative = sample.relativize(source)
                if (!relative.startsWith(Path.of(".yde/state"))) {
                    val target = root.resolve(relative)
                    if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
                }
            } }
            state.openWorkspace(root)
            state.memory.lensAppearanceId = "er-lens"
            setContent { Box(Modifier.size(1200.dp, 800.dp)) { GraphLensView(state) } }
            waitUntil(timeoutMillis = 20_000) { state.overlayIndex().graph.entities.values.any { it.entityType == "er-table" } }
            runOnIdle {
                state.overlayIndex().graph.entities.values.map { it.entityType ?: "（型なし）" }.distinct()
                    .filter { it != "er-table" }.forEach { state.memory.lensHiddenTypes[it] = true }
            }
            onNodeWithText("階層").performScrollTo().performClick()
            try {
                waitUntil(timeoutMillis = 20_000) {
                    state.memory.diagramPositions.values.any { it.ready && it.positions.size == 4 && !it.saving } &&
                        onAllNodesWithText("配置を計算中…").fetchSemanticsNodes().isEmpty()
                }
            } catch (e: ComposeTimeoutException) {
                throw AssertionError("配置状態: ${state.memory.diagramPositions.values.map { Triple(it.ready, it.positions.size, it.saving) }}\n" +
                    onRoot().printToString(), e)
            }
            onNodeWithTag("lens-canvas").assertExists()
            onNodeWithText("4 箱 / 3 辺").assertExists()
            onAllNodes(hasText("ポート", substring = true)).assertCountEquals(0)
            onAllNodes(hasText("解決できません", substring = true)).assertCountEquals(0)
        } finally {
            state.close()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
