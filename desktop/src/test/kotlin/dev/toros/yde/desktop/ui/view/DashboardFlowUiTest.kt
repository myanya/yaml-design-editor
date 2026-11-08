package dev.toros.yde.desktop.ui.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.UiSpace
import dev.toros.yde.desktop.diagramPositions
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

    @Test fun `流れ図はフロー図の部品で描き工程を選ぶと作業一覧に分かれ行の操作を適用すると次の段へ流れる`() = runComposeUiTest {
        val root = copyOrders()
        val state = AppState()
        try {
            state.openWorkspace(root)
            assertEquals(UiSpace.DASHBOARD, state.uiSpace)
            state.memory.dashboardDate["order-dashboard"] = LocalDate.parse("2026-11-08")
            setContent { Box(Modifier.size(1400.dp, 900.dp)) { DashboardPane(state) { id -> Text("詳細 $id") } } }
            // 流れ図はフロービューと同じ FlowCanvas（配置・描画・拡大縮小と PNG）。位置を覚えない図なので配置の操作は出ない
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("flow-canvas").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("dashboard-flow").assertExists()
            onNodeWithText("PNG に保存").assertExists()
            onAllNodesWithText("再配置").assertCountEquals(0)
            // 工程の線を押したときと同じ選択（押した要素から工程を引く対応は DashboardFlowDiagramsTest）
            runOnIdle { state.memory.dashboardMove["order-dashboard"] = "pending|confirm-order|confirmed" }
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
            onNodeWithText("いま操作できる（3）").assertExists()
            assertTrue(Files.readString(root.resolve("orders/ORD-106.yaml")).contains("status: confirmed"))
            runOnIdle { state.undo() }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("いま操作できる（4）").fetchSemanticsNodes().isNotEmpty() }
            // 図の位置はワークスペースに書かない
            assertFalse(Files.exists(root.resolve(".yde/state/diagrams")))
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
            // 図はフロービューと同じ FlowCanvas（配置は裏で計算してから描く）
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("flow-canvas").fetchSemanticsNodes().isNotEmpty() }
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

    @Test fun `流れ図は見出しで畳め 畳むと段の区画がタイルに戻り工程の作業一覧も隠れる`() = runComposeUiTest {
        val root = copyOrders()
        val state = AppState()
        try {
            state.openWorkspace(root)
            state.memory.dashboardDate["order-dashboard"] = LocalDate.parse("2026-11-08")
            state.memory.dashboardMove["order-dashboard"] = "pending|confirm-order|confirmed"
            setContent { Box(Modifier.size(1400.dp, 900.dp)) { DashboardPane(state) { id -> Text("詳細 $id") } } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("flow-canvas").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("いま操作できる（4）").assertExists()
            // 段の区画（受注確定など）は流れ図にあり、タイルには出ない
            onAllNodesWithText("受注確定").assertCountEquals(0)
            onNodeWithTag("dashboard-flow-header").performClick()
            waitForIdle()
            assertEquals(true, state.memory.dashboardFlowCollapsed["order-dashboard"])
            onAllNodesWithTag("flow-canvas").assertCountEquals(0)
            onNodeWithText("（4）").assertExists()
            onNodeWithText("受注確定").assertExists()
            onAllNodesWithText("いま操作できる（4）").assertCountEquals(0)
            // 開くと流れ図と、覚えていた工程の作業一覧が戻る
            onNodeWithTag("dashboard-flow-header").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("flow-canvas").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("いま操作できる（4）").assertExists()
            onAllNodesWithText("受注確定").assertCountEquals(0)
        } finally { cleanup(root) }
    }

    @Test fun `流れの中の位置は見出しで畳むと見出しといまいる段の1行だけになる`() = runComposeUiTest {
        val root = copyOrders()
        val state = AppState()
        try {
            state.openWorkspace(root)
            setContent { Box(Modifier.size(1200.dp, 700.dp)) { DashboardPositionPanel(state, "ORD-105") } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("flow-canvas").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("flow-position-header").performClick()
            waitForIdle()
            onNodeWithText("いま「受注確定」にいます").assertExists()
            onAllNodesWithTag("flow-canvas").assertCountEquals(0)
            onAllNodesWithText("次にできること").assertCountEquals(0)
            onNodeWithTag("flow-position-header").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("flow-canvas").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("次にできること").assertExists()
        } finally { cleanup(root) }
    }

    @Test fun `流れの配置は台帳のフロータブで編集して記録しダッシュボードは同じ記録を読むだけ`() = runComposeUiTest {
        val root = copyOrders()
        val state = AppState()
        try {
            state.openWorkspace(root)
            state.memory.dashboardDate["order-dashboard"] = LocalDate.parse("2026-11-08")
            val screen = androidx.compose.runtime.mutableStateOf("dashboard")
            setContent { Box(Modifier.size(1400.dp, 900.dp)) {
                if (screen.value == "dashboard") DashboardPane(state) { id -> Text("詳細 $id") } else FlowViewPane(state)
            } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("flow-canvas").fetchSemanticsNodes().isNotEmpty() }
            // ダッシュボードの図は読むだけ: 配置の道具を出さず、記録を書かない
            onAllNodesWithText("再配置").assertCountEquals(0)
            assertFalse(Files.exists(root.resolve(".yde/state/diagrams")))
            // 拡大表示で台帳のフロータブ（ダッシュボードの流れ）へ
            onNodeWithText("拡大表示（配置の編集）").performClick()
            runOnIdle {
                assertEquals(dev.toros.yde.desktop.UiSpace.LEDGER, state.uiSpace)
                assertEquals("order-dashboard", state.flowViewId)
                screen.value = "ledger"
            }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("dashboard-flow-editor").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("業務の流れ: 注文ダッシュボード").assertExists()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("再配置").fetchSemanticsNodes().isNotEmpty() }
            // 編集する図は初回の自動配置を記録する（入口と 4 つの段）。ダッシュボードと業務詳細は同じ記録を共有する
            val catalog = dev.toros.yde.app.core.Views.load(root, state.schemas)
            val context = dashboardFlowContext(assertNotNull(catalog.dashboard("order-dashboard")))
            val memory = state.diagramPositions(context)
            waitUntil(timeoutMillis = 20_000) { memory.positions.size == 5 && !memory.saving }
            assertEquals(setOf("@start", "pending", "confirmed", "shipped", "cancelled"), memory.positions.keys.map { it.key }.toSet())
            assertTrue(Files.exists(root.resolve(".yde/state/diagrams")))
            // ダッシュボードへ戻っても記録は書き換えない（読むだけ）
            val saved = memory.positions
            runOnIdle { state.goBack(); screen.value = "dashboard" }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("flow-canvas").fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            assertEquals(dev.toros.yde.desktop.UiSpace.DASHBOARD, state.uiSpace)
            assertEquals(saved, state.diagramPositions(context).positions)
        } finally { cleanup(root) }
    }
}
