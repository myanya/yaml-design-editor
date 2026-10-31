package dev.toros.yde.desktop.ui.run

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import dev.toros.yde.app.service.*
import dev.toros.yde.desktop.DesktopUiDispatcher
import dev.toros.yde.desktop.ExternalWorkController
import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*

/** 受信箱の外部提出を画面無しで描き、確認から保存・却下まで実際の共通サービスへ接続する。 */
@OptIn(ExperimentalTestApi::class)
class ExternalWorkPaneTest {
    @Test fun `差分確認前は保存できず確認後の承認で保存結果へ切り替わる`() = runComposeUiTest {
        val root = Files.createTempDirectory("external-inbox-ui")
        val host = WorkspaceHost(owner = DesktopUiDispatcher)
        val scope = CoroutineScope(SupervisorJob() + host.owner)
        try {
            Files.writeString(root.resolve("a.yaml"), "title: before\n")
            runBlocking(host.owner) { host.openWorkspace(root) }
            val caller = OperationCaller("workspace", "external-test")
            val service = WorkspaceWork(host, grants = { WorkGrant(1, setOf(DocumentId("a.yaml"))) }, workspaceId = { "workspace" })
            runBlocking {
                val work = service.begin(caller, "テストの提出")
                service.stage(caller, work.id, ChangeSets.parse("changes:\n  - file: a.yaml\n    ops:\n      - {op: set, path: title, value: after}\n"))
                service.submit(caller, work.id)
            }
            val controller = runBlocking(host.owner) { ExternalWorkController(service, scope) }
            setContent {
                var selected by remember { mutableStateOf<String?>(null) }
                val record = controller.records.firstOrNull { it.id == selected }
                if (record == null) ExternalWorkList(controller) { selected = it }
                else ExternalWorkPane(controller, record) { selected = null }
            }
            onNodeWithText("内容と結果").performClick()
            onNodeWithText("承認して保存").assertIsNotEnabled()
            onNodeWithText("差分と検証を再確認").performClick()
            waitUntil(timeoutMillis = 15_000) { controller.preview != null && !controller.busy }
            onNodeWithText("承認して保存").assertIsEnabled().performClick()
            waitUntil(timeoutMillis = 15_000) { controller.records.single().state == WorkState.APPLIED && !controller.busy }
            onNodeWithText("external-test ／ 保存済み").assertExists()
            assertContains(Files.readString(root.resolve("a.yaml")), "after")
            onNodeWithText("承認して保存").assertDoesNotExist()
            onNodeWithText("受信箱へ戻る").performClick()
            onNodeWithText("テストの提出").assertExists()
        } finally {
            scope.cancel()
            runBlocking(host.owner) { host.close() }
            root.toFile().deleteRecursively()
        }
    }

    @Test fun `却下は確認して記録を残し原本を変えない`() = runComposeUiTest {
        val root = Files.createTempDirectory("external-reject-ui")
        val host = WorkspaceHost(owner = DesktopUiDispatcher)
        val scope = CoroutineScope(SupervisorJob() + host.owner)
        try {
            Files.writeString(root.resolve("a.yaml"), "title: before\n")
            runBlocking(host.owner) { host.openWorkspace(root) }
            val caller = OperationCaller("workspace", "external-test")
            val service = WorkspaceWork(host, grants = { WorkGrant(1, setOf(DocumentId("a.yaml"))) }, workspaceId = { "workspace" })
            runBlocking {
                val work = service.begin(caller, "却下する提出")
                service.stage(caller, work.id, ChangeSets.parse("changes:\n  - file: a.yaml\n    ops:\n      - {op: set, path: title, value: after}\n"))
                service.submit(caller, work.id)
            }
            val controller = runBlocking(host.owner) { ExternalWorkController(service, scope) }
            setContent { controller.records.singleOrNull()?.let { ExternalWorkPane(controller, it) {} } }
            onNodeWithText("却下").performClick()
            onNodeWithText("提出を却下しますか").assertExists()
            onNodeWithText("却下する").performClick()
            waitUntil(timeoutMillis = 15_000) { controller.records.single().state == WorkState.REJECTED && !controller.busy }
            onNodeWithText("external-test ／ 却下済み").assertExists()
            assertEquals("title: before\n", Files.readString(root.resolve("a.yaml")))
        } finally {
            scope.cancel()
            runBlocking(host.owner) { host.close() }
            root.toFile().deleteRecursively()
        }
    }
}
