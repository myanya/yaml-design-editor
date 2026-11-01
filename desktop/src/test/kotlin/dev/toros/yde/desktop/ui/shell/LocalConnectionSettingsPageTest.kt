package dev.toros.yde.desktop.ui.shell

import androidx.compose.ui.test.*
import dev.toros.yde.app.service.ConnectionPermissions
import dev.toros.yde.app.service.ConnectionReadScope
import dev.toros.yde.app.service.ConnectionWriteScope
import dev.toros.yde.desktop.connections.ConnectionSettingsFixture
import dev.toros.yde.server.*
import dev.toros.yde.yaml.model.DocumentId
import kotlinx.coroutines.runBlocking
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class LocalConnectionSettingsPageTest {
    @Test fun `簡単設定で提案と直接確定を別々に選び業務操作名を選択する`() = runComposeUiTest {
        val f = ConnectionSettingsFixture()
        try {
            f.actions = listOf("rename", "complete")
            runBlocking { f.controller.initialize() }
            setContent { LocalConnectionSettingsPage(f.controller, "試験ワークスペース") {} }
            onNodeWithText("接続者を追加").performScrollTo().performClick()
            onNodeWithContentDescription("接続名").performScrollTo().performTextInput("両ルート")
            onNodeWithContentDescription("変更提案を許可（デスクトップで承認）").performScrollTo().assertIsOff().performClick()
            onNodeWithContentDescription("直接確定を許可（外部ツールで保存）").performScrollTo().assertIsOff().performClick()
            onNodeWithContentDescription("選択した業務操作を許可").performScrollTo().performClick()
            onNodeWithText("業務操作を選択（0 件）").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("操作 rename を許可").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription("操作 rename を許可").performClick()
            onNodeWithContentDescription("操作 complete を許可").assertIsOff()
            onNodeWithText("選択を反映").performClick()
            onNodeWithText("登録権限を保存").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { f.controller.state.value.registrations.size == 1 && !f.controller.state.value.busy }
            val caller = f.server.access.clients().single().caller
            val p = f.grants.get(caller)!!.permissions
            assertEquals(ConnectionWriteScope.LEDGER, p.proposalScope)
            assertEquals(ConnectionWriteScope.LEDGER, p.commitScope)
            assertEquals(setOf("rename"), p.actions)
            assertTrue(f.server.connections.status.value.stopped)
            onNodeWithText("権限を編集").performScrollTo().performClick()
            onNodeWithContentDescription("簡単設定").performScrollTo().assertIsSelected()
            onNodeWithContentDescription("直接確定を許可（外部ツールで保存）").performScrollTo().performClick()
            onNodeWithText("登録権限を保存").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { f.grants.directWorkGrant(caller) == null && !f.controller.state.value.busy }
            assertNotNull(f.grants.workGrant(caller))
        } finally { runBlocking { f.close() } }
    }
    @Test fun `既存登録の簡単設定への切替は確認と保存を経て初めて反映する`() = runComposeUiTest {
        val f = ConnectionSettingsFixture()
        try {
            val previous = ConnectionPermissions(readable = setOf(DocumentId("a.yaml")), proposable = setOf(DocumentId("a.yaml")))
            val client = runBlocking {
                f.controller.initialize()
                assertNotNull(f.controller.register(LocalFeature.API, "既存ツール", previous))
            }
            setContent { LocalConnectionSettingsPage(f.controller, "試験ワークスペース") {} }
            onNodeWithText("権限を編集").performScrollTo().performClick()
            onNodeWithContentDescription("詳細設定").performScrollTo().assertIsSelected()
            onNodeWithContentDescription("簡単設定").performClick()
            onNodeWithText("キャンセル").performClick()
            onNodeWithContentDescription("詳細設定").assertIsSelected()
            assertEquals(previous, f.grants.get(client.caller)?.permissions)
            onNodeWithContentDescription("簡単設定").performClick()
            onNodeWithText("簡単設定に切り替える").performClick()
            onNodeWithContentDescription("簡単設定").assertIsSelected()
            assertEquals(previous, f.grants.get(client.caller)?.permissions)
            onNodeWithText("登録権限を保存").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { f.grants.get(client.caller)?.permissions?.readScope == ConnectionReadScope.WORKSPACE && !f.controller.state.value.busy }
            assertNull(f.grants.workGrant(client.caller))
            assertTrue(f.grants.get(client.caller)!!.permissions.canRead(DocumentId("future.yaml")))
            assertTrue(f.server.connections.status.value.stopped)
            assertFalse(f.store.saved.settings.requiresListener)
        } finally { runBlocking { f.close() } }
    }

    @Test fun `文書ツリーの選択と検索は取消でき保存後も追加文書を自動許可しない`() = runComposeUiTest {
        val f = ConnectionSettingsFixture()
        val a = DocumentId("tasks/a.yaml")
        val b = DocumentId("tasks/b.yaml")
        val c = DocumentId("tasks/c.yaml")
        try {
            f.documents = listOf(a, b, DocumentId("other.yaml"))
            val client = runBlocking {
                f.controller.initialize()
                assertNotNull(f.controller.register(LocalFeature.API, "個別ツール", ConnectionPermissions(readable = setOf(a))))
            }
            setContent { LocalConnectionSettingsPage(f.controller, "試験ワークスペース") {} }
            onNodeWithText("権限を編集").performScrollTo().performClick()
            onNodeWithContentDescription("閲覧する文書 を選択").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("許可フォルダー tasks/").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription("許可フォルダー tasks/").performClick()
            onNodeWithContentDescription("許可文書の検索").performTextInput("b.yaml")
            onNodeWithContentDescription("許可文書 tasks/b.yaml").assertIsOn()
            onNodeWithText("キャンセル").performClick()
            onNodeWithContentDescription("閲覧する文書").assertTextContains("tasks/a.yaml")
            assertEquals(setOf(a), f.grants.get(client.caller)?.permissions?.readable)
            onNodeWithContentDescription("閲覧する文書 を選択").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("許可フォルダー tasks/").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription("許可フォルダー tasks/").performClick()
            onNodeWithText("選択を反映").performClick()
            assertEquals(setOf(a), f.grants.get(client.caller)?.permissions?.readable)
            onNodeWithText("登録権限を保存").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { f.grants.get(client.caller)?.permissions?.readable == setOf(a, b) && !f.controller.state.value.busy }
            f.documents = f.documents + c
            onNodeWithText("権限を編集").performScrollTo().performClick()
            onNodeWithContentDescription("閲覧する文書 を選択").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("許可フォルダー tasks/").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription("許可文書の検索").performTextInput("c.yaml")
            onNodeWithContentDescription("許可文書 tasks/c.yaml").assertIsOff()
            onNodeWithText("キャンセル").performClick()
            assertFalse(f.grants.get(client.caller)!!.permissions.canRead(c))
        } finally { runBlocking { f.close() } }
    }

    @Test fun `APIだけが有効ならMCP登録に発行できない理由を示し追加ではAPIを選ぶ`() = runComposeUiTest {
        val f = ConnectionSettingsFixture()
        try {
            runBlocking {
                f.controller.initialize(); f.controller.setPort(f.port().toString())
                f.controller.setEnabled(LocalFeature.API, true); f.settle()
                assertNotNull(f.controller.register(LocalFeature.MCP, "外部エージェント", ConnectionPermissions(readable = setOf(DocumentId("a.yaml")))))
            }
            setContent { LocalConnectionSettingsPage(f.controller, "試験ワークスペース") {} }
            onNodeWithText("トークンを発行").performScrollTo().assertIsNotEnabled()
            onNodeWithText("この登録は MCP 用です。API を使う場合は、API を選んで接続者を追加してください。").assertExists()
            onNodeWithText("接続者を追加").performScrollTo().performClick()
            onNodeWithContentDescription("登録する機能 API").performScrollTo().assertIsSelected()
            onNodeWithContentDescription("接続名").performScrollTo().performTextInput("自作ツール")
            onNodeWithContentDescription("簡単設定").assertIsSelected()
            onNodeWithText("登録権限を保存").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { f.controller.state.value.registrations.size == 2 && !f.controller.state.value.busy }
            val client = f.server.access.clients().single { it.name == "自作ツール" }
            assertEquals(LocalFeature.API, client.feature)
            assertEquals(ConnectionReadScope.WORKSPACE, f.grants.get(client.caller)?.permissions?.readScope)
            assertNull(f.grants.workGrant(client.caller)); assertNull(f.grants.operationGrant(client.caller))
            onAllNodesWithText("トークンを発行")[1].performScrollTo().assertIsEnabled().performClick()
            waitUntil(timeoutMillis = 10_000) { f.server.access.credentialExpiry(client.id) != null && !f.controller.state.value.busy }
            onNodeWithText("トークンをコピー").assertExists()
            assertEquals(ConnectionPhase.STOPPED, f.server.connections.status.value.features.getValue(LocalFeature.MCP).phase)
        } finally { runBlocking { f.close() } }
    }

    @Test fun `API停止中の理由は画面を開いたまま起動すると消えて発行可能になる`() = runComposeUiTest {
        val f = ConnectionSettingsFixture()
        try {
            runBlocking {
                f.controller.initialize(); f.controller.setPort(f.port().toString())
                assertNotNull(f.controller.register(LocalFeature.API, "自作ツール", ConnectionPermissions(readable = setOf(DocumentId("a.yaml")))))
            }
            setContent { LocalConnectionSettingsPage(f.controller, "試験ワークスペース") {} }
            val reason = "API が OFF のため発行できません。上の API を ON にして、稼働中になるまで待ってください。"
            onNodeWithText(reason).assertExists()
            onNodeWithText("トークンを発行").performScrollTo().assertIsNotEnabled()
            onNodeWithContentDescription("API を有効にする").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { f.server.connections.status.value.features.getValue(LocalFeature.API).phase == ConnectionPhase.RUNNING && !f.controller.state.value.busy }
            onNodeWithText(reason).assertDoesNotExist()
            onNodeWithText("トークンを発行").performScrollTo().assertIsEnabled()
            onNodeWithContentDescription("API を有効にする").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { f.server.connections.status.value.stopped && !f.controller.state.value.busy }
            onNodeWithText(reason).assertExists()
            onNodeWithText("トークンを発行").performScrollTo().assertIsNotEnabled()
        } finally { runBlocking { f.close() } }
    }

    @Test fun `読込エラーの修復後に再読込すると常駐を操作できる`() = runComposeUiTest {
        val f = ConnectionSettingsFixture()
        try {
            f.store.failRead = true
            f.store.readFailure = dev.toros.yde.desktop.connections.ConnectionProtectionException("保存先の権限を修復してください")
            runBlocking { f.controller.initialize() }
            setContent { LocalConnectionSettingsPage(f.controller, null) {} }
            onNodeWithContentDescription("トレイ常駐を有効にする").assertIsNotEnabled()
            f.store.failRead = false
            onNodeWithText("設定を再読込").performClick()
            waitUntil(timeoutMillis = 10_000) { !f.controller.state.value.blocked && !f.controller.state.value.busy }
            onNodeWithText("設定を再読込").assertDoesNotExist()
            onNodeWithContentDescription("トレイ常駐を有効にする").performClick()
            waitUntil(timeoutMillis = 10_000) { f.store.saved.resident.trayEnabled && !f.controller.state.value.busy }
            onNodeWithContentDescription("トレイ常駐を有効にする").assertIsOn()
            assertTrue(f.server.connections.status.value.stopped)
        } finally { runBlocking { f.close() } }
    }

    @Test fun `スイッチの指定と稼働状態を分けて表示しWebだけを残して停止できる`() = runComposeUiTest {
        val f = ConnectionSettingsFixture()
        try {
            runBlocking { f.controller.initialize(); f.controller.setPort(f.port().toString()) }
            setContent { LocalConnectionSettingsPage(f.controller, "試験ワークスペース") {} }
            onNodeWithText("待受なし").assertExists()
            onNodeWithContentDescription("API を有効にする").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { f.server.connections.status.value.features.getValue(LocalFeature.API).phase == ConnectionPhase.RUNNING && !f.controller.state.value.busy }
            onNodeWithContentDescription("Web を有効にする").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { f.server.connections.status.value.features.getValue(LocalFeature.WEB).phase == ConnectionPhase.RUNNING && !f.controller.state.value.busy }
            onNodeWithContentDescription("API を有効にする").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { f.server.connections.status.value.features.getValue(LocalFeature.API).phase == ConnectionPhase.STOPPED && !f.controller.state.value.busy }
            onNodeWithContentDescription("Web を有効にする").assertIsOn()
            onNodeWithContentDescription("API を有効にする").assertIsOff()
            onNodeWithContentDescription("Web を有効にする").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { f.server.connections.status.value.stopped && !f.controller.state.value.busy }
            onNodeWithText("待受なし").assertExists()
        } finally { runBlocking { f.close() } }
    }

    @Test fun `登録権限を入力して保存し削除は確認してから反映する`() = runComposeUiTest {
        val f = ConnectionSettingsFixture()
        try {
            runBlocking { f.controller.initialize() }
            setContent { LocalConnectionSettingsPage(f.controller, "試験ワークスペース") {} }
            onNodeWithText("接続者を追加").performScrollTo().performClick()
            onNodeWithContentDescription("接続名").performScrollTo().performTextInput("外部エージェント")
            onNodeWithContentDescription("詳細設定").performScrollTo().performClick()
            onNodeWithContentDescription("閲覧範囲 指定した文書のみ").performScrollTo().performClick()
            onNodeWithContentDescription("閲覧する文書").performScrollTo().performTextInput("tasks.yaml")
            onNodeWithContentDescription("変更提案する文書").performScrollTo().performTextInput("tasks.yaml")
            onNodeWithText("登録権限を保存").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { f.store.saved.registrations.size == 1 && !f.controller.state.value.busy }
            val client = f.server.access.clients().single()
            assertEquals(setOf(DocumentId("tasks.yaml")), f.grants.workGrant(client.caller)?.documents)
            assertNull(f.grants.operationGrant(client.caller))
            onNodeWithText("登録を削除").performScrollTo().performClick()
            onNodeWithText("キャンセル").performClick()
            assertEquals(1, f.server.access.clients().size)
            onNodeWithText("登録を削除").performClick()
            onNodeWithText("実行").performClick()
            waitUntil(timeoutMillis = 10_000) { f.store.saved.registrations.isEmpty() && !f.controller.state.value.busy }
            onNodeWithText("外部エージェント / MCP").assertDoesNotExist()
        } finally { runBlocking { f.close() } }
    }

    @Test fun `未選択のワークスペースでは登録できず読込エラーではスイッチを操作できない`() = runComposeUiTest {
        val f = ConnectionSettingsFixture()
        f.workspace = null
        f.store.failRead = true
        try {
            runBlocking { f.controller.initialize() }
            setContent { LocalConnectionSettingsPage(f.controller, null) {} }
            onNodeWithContentDescription("API を有効にする").assertIsNotEnabled()
            onNodeWithContentDescription("MCP を有効にする").assertIsNotEnabled()
            onNodeWithContentDescription("Web を有効にする").assertIsNotEnabled()
            onNodeWithText("接続者を追加").performScrollTo().assertIsNotEnabled()
            assertTrue(f.server.connections.status.value.stopped)
        } finally { runBlocking { f.close() } }
    }

    @Test fun `トークンの発行と再発行の確認と失効を画面から行える`() = runComposeUiTest {
        val f = ConnectionSettingsFixture()
        try {
            val client = runBlocking {
                f.controller.initialize(); f.controller.setPort(f.port().toString())
                f.controller.setEnabled(LocalFeature.API, true); f.settle()
                assertNotNull(f.controller.register(LocalFeature.API, "自作ツール", ConnectionPermissions(readable = setOf(DocumentId("a.yaml")))))
            }
            setContent { LocalConnectionSettingsPage(f.controller, "試験ワークスペース") {} }
            onNodeWithText("トークンを発行").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { f.server.access.credentialExpiry(client.id) != null && !f.controller.state.value.busy }
            onNodeWithText("トークンをコピー").assertExists()
            onNodeWithText("閉じる").performClick()
            val originalExpiry = f.server.access.credentialExpiry(client.id)
            onNodeWithText("トークンを再発行").performClick()
            onNodeWithText("キャンセル").performClick()
            assertEquals(originalExpiry, f.server.access.credentialExpiry(client.id))
            onNodeWithText("トークンを再発行").performClick()
            onNodeWithText("実行").performClick()
            waitUntil(timeoutMillis = 10_000) { f.server.access.credentialExpiry(client.id) != originalExpiry && !f.controller.state.value.busy }
            onNodeWithText("トークンをコピー").assertExists()
            onNodeWithText("閉じる").performClick()
            onNodeWithText("失効").performClick()
            onNodeWithText("実行").performClick()
            waitUntil(timeoutMillis = 10_000) { f.server.access.credentialExpiry(client.id) == null && !f.controller.state.value.busy }
            assertEquals(1, f.store.saved.registrations.size)
            onNodeWithText("トークンを発行").assertIsEnabled()
        } finally { runBlocking { f.close() } }
    }
}
