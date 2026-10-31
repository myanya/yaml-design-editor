package dev.toros.yde.desktop.ui.shell

import androidx.compose.ui.test.*
import dev.toros.yde.app.service.ConnectionPermissions
import dev.toros.yde.desktop.connections.ConnectionSettingsFixture
import dev.toros.yde.server.*
import dev.toros.yde.yaml.model.DocumentId
import kotlinx.coroutines.runBlocking
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class LocalConnectionSettingsPageTest {
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
            onNodeWithContentDescription("閲覧する文書").performScrollTo().performTextInput("a.yaml")
            onNodeWithText("登録権限を保存").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { f.controller.state.value.registrations.size == 2 && !f.controller.state.value.busy }
            val client = f.server.access.clients().single { it.name == "自作ツール" }
            assertEquals(LocalFeature.API, client.feature)
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
