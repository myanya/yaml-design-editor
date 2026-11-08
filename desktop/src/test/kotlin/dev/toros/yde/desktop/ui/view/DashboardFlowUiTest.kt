package dev.toros.yde.desktop.ui.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.UiSpace
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/**
 * 業務の流れ（注文サンプルのダッシュボード）: 流れ図の工程から作業一覧へ進み、行から操作して次の段へ流れる。
 * 業務詳細の「流れの中の位置」は、いる段・次にできること・履歴を出し、押すと操作のパネルが開く。
 */
@OptIn(ExperimentalTestApi::class)
class DashboardFlowUiTest {
    private fun copyOrders(): Path {
        val root = createTempDirectory("dashboard-flow-ui")
        val sample = Path.of("samples/orders")
        Files.walk(sample).use { paths -> paths.forEach { source ->
            val relative = sample.relativize(source)
            if (!relative.startsWith(Path.of(".yde/state"))) {
                val target = root.resolve(relative)
                if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
            }
        } }
        return root
    }

    private fun cleanup(root: Path) { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }

    @Test fun `工程を押すと作業一覧に分かれ行の操作を適用すると次の段へ流れる`() = runComposeUiTest {
        val root = copyOrders()
        val state = AppState()
        try {
            state.openWorkspace(root)
            assertEquals(UiSpace.DASHBOARD, state.uiSpace)
            state.memory.dashboardDate["order-dashboard"] = LocalDate.parse("2026-11-08")
            setContent { Box(Modifier.size(1400.dp, 900.dp)) { DashboardPane(state) { id -> Text("詳細 $id") } } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("flow-step-pending").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("flow-step-pending").assertTextContains("4 件", substring = true)
            onNodeWithTag("flow-step-confirmed").assertTextContains("3 件", substring = true)
            onNodeWithTag("flow-move-pending|confirm-order|confirmed").assertTextContains("4/4", substring = true).performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("flow-run-ORD-106").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("いま操作できる（4）").assertExists()
            onNodeWithTag("flow-run-ORD-106").performClick()
            runOnIdle {
                val work = assertNotNull(state.actionWork)
                assertEquals("confirm-order", work.entry.name)
                assertEquals("ORD-106", work.target)
                state.planAction()
                assertNotNull(work.plan, work.error)
                val move = assertNotNull(work.transition)
                assertEquals(listOf("未処理"), move.before)
                assertEquals(listOf("受注確定"), move.after)
                assertFalse(move.undeclared)
                val message = state.applyAction().getOrThrow()
                assertTrue(message.contains("「未処理」から「受注確定」へ移りました"), message)
            }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("flow-run-ORD-106").fetchSemanticsNodes().isEmpty() }
            onNodeWithTag("flow-step-confirmed").assertTextContains("4 件", substring = true)
            assertTrue(Files.readString(root.resolve("orders/ORD-106.yaml")).contains("status: confirmed"))
            // 段を押すと工程の選択が外れ、その区画の一覧に戻る
            onNodeWithTag("flow-step-confirmed").performClick()
            runOnIdle { assertNull(state.memory.dashboardMove["order-dashboard"]) }
            runOnIdle { state.undo() }
            waitUntil(timeoutMillis = 10_000) {
                runCatching { onNodeWithTag("flow-step-pending").assertTextContains("4 件", substring = true) }.isSuccess
            }
        } finally { cleanup(root) }
    }

    @Test fun `業務詳細の位置はいる段と次にできることと履歴を出し押すと操作が始まる`() = runComposeUiTest {
        val root = copyOrders()
        val state = AppState()
        try {
            state.openWorkspace(root)
            setContent { Box(Modifier.size(1200.dp, 700.dp)) { DashboardPositionPanel(state, "ORD-105") } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("flow-position").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("いま「受注確定」にいます").assertExists()
            onNodeWithTag("flow-step-confirmed").assertTextContains("いまここ", substring = true)
            onNodeWithText("2026-10-21  受注を確定する  （未処理 → 受注確定）").assertExists()
            onNodeWithTag("flow-next-ship-order").assertIsEnabled().performClick()
            runOnIdle { assertEquals("ship-order", state.actionWork?.entry?.name); assertEquals("ORD-105", state.actionWork?.target) }
        } finally { cleanup(root) }
    }

    @Test fun `終点の注文には次の工程が無く 流れの無い対象には位置を出さない`() = runComposeUiTest {
        val root = copyOrders()
        val state = AppState()
        try {
            state.openWorkspace(root)
            val id = androidx.compose.runtime.mutableStateOf("ORD-001")
            setContent { Box(Modifier.size(1200.dp, 700.dp)) { DashboardPositionPanel(state, id.value) } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("flow-position").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("この段から先の工程はありません（終点）").assertExists()
            id.value = "CUS-001"
            waitForIdle()
            onAllNodesWithTag("flow-position").assertCountEquals(0)
        } finally { cleanup(root) }
    }
}
