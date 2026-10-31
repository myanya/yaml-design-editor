package dev.toros.yde.desktop.connections

import dev.toros.yde.app.service.ConnectionPermissions
import dev.toros.yde.server.*
import dev.toros.yde.yaml.model.DocumentId
import kotlinx.coroutines.*
import kotlin.test.*

class LocalConnectionControllerTest {
    @Test fun `初期値は全停止で不正な設定の復元も接続を開始しない`() = runBlocking {
        val f = ConnectionSettingsFixture()
        try {
            f.store.failRead = true
            f.controller.initialize()
            assertTrue(f.controller.state.value.blocked)
            assertTrue(f.server.connections.status.value.stopped)
            f.controller.setEnabled(LocalFeature.API, true)
            assertTrue(f.server.connections.status.value.stopped)
            f.store.failRead = false
            f.controller.reset()
            assertFalse(f.controller.state.value.blocked)
            assertEquals(SavedConnections(), f.store.saved)
        } finally { f.close() }
    }

    @Test fun `機能別の指定を復元し停止と再起動で資格情報を復活させない`() = runBlocking {
        val f = ConnectionSettingsFixture()
        val p = ConnectionPermissions(readable = setOf(DocumentId("a.yaml")))
        try {
            f.controller.initialize()
            f.controller.setPort(f.port().toString())
            f.controller.setEnabled(LocalFeature.API, true)
            assertEquals(ConnectionPhase.RUNNING, f.settle().listener)
            f.controller.setEnabled(LocalFeature.WEB, true); f.settle()
            val client = assertNotNull(f.controller.register(LocalFeature.API, "自作ツール", p))
            assertNotNull(f.controller.issue(client.id))
            assertNotNull(f.server.access.credentialExpiry(client.id))
            f.controller.setEnabled(LocalFeature.API, false)
            assertNull(f.server.access.credentialExpiry(client.id))
            assertTrue(f.settle().desired.webEnabled)
            f.controller.setEnabled(LocalFeature.API, true); f.settle()
            assertNull(f.server.access.credentialExpiry(client.id))
            assertNotNull(f.controller.issue(client.id))
            val originalPort = f.store.saved.settings.port
            f.controller.setPort("23456")
            assertNotNull(f.controller.state.value.error)
            assertEquals(originalPort, f.store.saved.settings.port)
        } finally { f.close() }
        val restored = ConnectionSettingsFixture(f.store)
        try {
            restored.controller.initialize()
            assertEquals(ConnectionPhase.RUNNING, restored.settle().listener)
            assertTrue(restored.server.connections.status.value.desired.apiEnabled)
            assertTrue(restored.server.connections.status.value.desired.webEnabled)
            assertFalse(restored.server.connections.status.value.desired.mcpEnabled)
            val client = restored.server.access.clients().single()
            assertEquals(f.store.saved.registrations.single().client.id, client.id)
            assertEquals(p, restored.server.access.grant(client.id)?.permissions)
            assertNull(restored.server.access.credentialExpiry(client.id))
        } finally { restored.close() }
    }

    @Test fun `保存できなければ有効化せず停止だけは実行し復元値の相違を通知する`() = runBlocking {
        val f = ConnectionSettingsFixture()
        try {
            f.controller.initialize(); f.controller.setPort(f.port().toString())
            f.store.failWrite = true
            f.controller.setEnabled(LocalFeature.MCP, true)
            assertTrue(f.server.connections.status.value.stopped)
            assertFalse(f.store.saved.settings.mcpEnabled)
            f.store.failWrite = false
            f.controller.setEnabled(LocalFeature.MCP, true); f.settle()
            f.store.failWrite = true
            f.controller.setEnabled(LocalFeature.MCP, false)
            assertTrue(f.settle().stopped)
            assertTrue(f.store.saved.settings.mcpEnabled)
            assertContains(f.controller.state.value.error.orEmpty(), "次回起動時")
            f.store.failWrite = false
            f.controller.retry()
            assertFalse(f.store.saved.settings.mcpEnabled)
        } finally { f.close() }
    }

    @Test fun `登録と権限を保存し保存失敗時には新しい権限を採用しない`() = runBlocking {
        val f = ConnectionSettingsFixture()
        val p = ConnectionPermissions(readable = setOf(DocumentId("a.yaml")))
        try {
            f.controller.initialize()
            f.store.failWrite = true
            assertNull(f.controller.register(LocalFeature.MCP, "失敗", p))
            assertTrue(f.server.access.clients().isEmpty())
            f.store.failWrite = false
            val client = assertNotNull(f.controller.register(LocalFeature.MCP, "提案", p))
            f.store.failWrite = true
            assertNull(f.controller.setPermissions(client.id, p.copy(proposable = p.readable)))
            assertNull(f.grants.workGrant(client.caller))
            f.store.failWrite = false
            f.controller.setPermissions(client.id, p.copy(proposable = p.readable))
            assertNotNull(f.grants.workGrant(client.caller))
            f.controller.remove(client.id)
            assertTrue(f.store.saved.registrations.isEmpty())
            assertNull(f.grants.get(client.caller))
        } finally { f.close() }
    }

    @Test fun `終了処理が始まったら設定操作を拒否し終了を中断したら再開できる`(): Unit = runBlocking {
        val f = ConnectionSettingsFixture()
        try {
            f.controller.initialize()
            f.controller.stopAccepting()
            assertNull(f.controller.setEnabled(LocalFeature.WEB, true))
            assertTrue(f.server.connections.status.value.stopped)
            f.controller.resumeAccepting()
            assertNotNull(f.controller.setPort("21345"))
        } finally { f.close() }
    }

    @Test fun `画面の破棄後も保存と設定反映を終えてからアプリの終了へ進む`() = runBlocking {
        val f = ConnectionSettingsFixture()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            f.controller.initialize()
            f.store.beforeSave = { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            val changing = launch(Dispatchers.Default) { f.controller.setPort("21346") }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            changing.cancel()
            val stopping = async(Dispatchers.Default) { f.controller.stopAccepting() }
            assertFalse(stopping.isCompleted)
            release.countDown()
            withTimeout(5_000) { changing.join(); stopping.await() }
            assertEquals(21346, f.store.saved.settings.port)
            assertEquals(21346, f.server.connections.status.value.desired.port)
            assertFalse(f.controller.state.value.busy)
        } finally { release.countDown(); f.close() }
    }
}
