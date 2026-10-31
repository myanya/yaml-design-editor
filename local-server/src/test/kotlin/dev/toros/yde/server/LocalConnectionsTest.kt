package dev.toros.yde.server

import dev.toros.yde.server.transport.ConnectionListener
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class LocalConnectionsTest {
    private class Listener : ConnectionListener {
        val starts = AtomicInteger()
        val closes = AtomicInteger()
        var onStart: () -> Unit = {}
        var onClose: () -> Unit = {}
        override fun start(): Int { starts.incrementAndGet(); onStart(); return 18765 }
        override fun close() { closes.incrementAndGet(); onClose() }
    }
    private suspend fun LocalConnections.set(settings: LocalConnectionSettings) = withTimeout(5_000) { awaitSettled(configure(settings)) }
    private fun settings(mask: Int) = LocalConnectionSettings(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0)
    private fun runCase(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking { withTimeout(20_000) { block() } }

    @Test fun `全八組合せで待受を共有し全OFFでは構築も開始もしない`() = runCase {
        val instances = mutableListOf<Listener>()
        val connections = LocalConnections(this, { _, _ -> Listener().also { instances += it } })
        try {
            assertTrue(connections.status.value.stopped)
            assertTrue(instances.isEmpty())
            for (mask in 0..7) {
                val state = connections.set(settings(mask))
                assertEquals(mask != 0, state.boundPort != null)
                LocalFeature.entries.forEachIndexed { index, feature ->
                    val enabled = mask and (1 shl index) != 0
                    assertEquals(enabled, state.features.getValue(feature).phase == ConnectionPhase.RUNNING)
                    var entered = false
                    assertEquals(enabled, connections.withRequest(feature) { entered = true })
                    assertEquals(enabled, entered)
                }
            }
            assertEquals(1, instances.size)
            assertEquals(1, instances.single().starts.get())
            assertTrue(connections.set(settings(0)).stopped)
            assertEquals(1, instances.single().closes.get())
        } finally { connections.shutdown() }
    }

    @Test fun `起動中にOFFと別機能のONを受けても二重起動せず最新の設定へ収束する`() = runCase {
        val entered = CompletableDeferred<Unit>()
        val release = Semaphore(0)
        val listener = Listener().apply { onStart = { entered.complete(Unit); check(release.tryAcquire(10, TimeUnit.SECONDS)) } }
        val connections = LocalConnections(this, { _, _ -> listener })
        try {
            connections.configure(settings(1))
            entered.await()
            connections.configure(settings(0))
            assertFalse(connections.withRequest(LocalFeature.API) { fail("起動中は受付しない") })
            val requested = connections.configure(settings(2))
            assertEquals(ConnectionPhase.STARTING, connections.status.value.listener)
            release.release()
            val state = connections.awaitSettled(requested)
            assertEquals(ConnectionPhase.STOPPED, state.features.getValue(LocalFeature.API).phase)
            assertEquals(ConnectionPhase.RUNNING, state.features.getValue(LocalFeature.MCP).phase)
            assertEquals(1, listener.starts.get())
            assertEquals(0, listener.closes.get())
        } finally { release.release(); connections.shutdown() }
    }

    @Test fun `機能OFFは既存の読取を取消し他機能を維持して古い世代を失効させる`() = runCase {
        val draining = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val listener = Listener()
        val connections = LocalConnections(this, { _, _ -> listener }, onDisabled = {
            if (it == LocalFeature.API) { draining.complete(Unit); release.await() }
        })
        try {
            connections.set(settings(3))
            val apiGeneration = connections.status.value.features.getValue(LocalFeature.API).generation
            val mcpGeneration = connections.status.value.features.getValue(LocalFeature.MCP).generation
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val reading = launch {
                connections.withRequest(LocalFeature.API) {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
            }
            entered.await()
            connections.configure(settings(2))
            assertFalse(connections.accepts(LocalFeature.API, apiGeneration))
            cancelled.await(); draining.await()
            assertTrue(connections.accepts(LocalFeature.MCP, mcpGeneration))
            val next = connections.configure(settings(3))
            assertFalse(connections.withRequest(LocalFeature.API) { fail("停止処理が残っている") })
            assertTrue(connections.withRequest(LocalFeature.MCP) {})
            release.complete(Unit)
            connections.awaitSettled(next)
            assertTrue(connections.withRequest(LocalFeature.API) {})
            assertFalse(connections.accepts(LocalFeature.API, apiGeneration))
            assertTrue(connections.accepts(LocalFeature.MCP, mcpGeneration))
            assertEquals(0, listener.closes.get())
            reading.join()
        } finally { release.complete(Unit); connections.shutdown() }
    }

    @Test fun `最後のOFFは読取を止め保存結果の確定を待ってから待受を閉じる`() = runCase {
        val listener = Listener()
        val connections = LocalConnections(this, { _, _ -> listener })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var saved = false
        try {
            connections.set(settings(1))
            val writing = launch {
                connections.withRequest(LocalFeature.API) { request ->
                    request.finishWrite { entered.complete(Unit); release.await(); saved = true }
                }
            }
            entered.await()
            val requested = connections.configure(settings(0))
            assertFalse(connections.withRequest(LocalFeature.API) { fail("OFF 後は新しい要求を受けない") })
            assertEquals(ConnectionPhase.STOPPING, connections.status.value.features.getValue(LocalFeature.API).phase)
            assertEquals(0, listener.closes.get())
            assertTrue(connections.status.value.settledRevision < requested)
            release.complete(Unit)
            writing.join()
            assertTrue(connections.awaitSettled(requested).stopped)
            assertTrue(saved)
            assertEquals(1, listener.closes.get())
        } finally { release.complete(Unit); connections.shutdown() }
    }

    @Test fun `OFF後に保持していた要求から保存を開始できない`() = runCase {
        val connections = LocalConnections(this, { _, _ -> Listener() })
        val acquired = CompletableDeferred<LocalRequest>()
        try {
            connections.set(settings(1))
            val reading = launch { connections.withRequest(LocalFeature.API) { acquired.complete(it); awaitCancellation() } }
            val request = acquired.await()
            connections.set(settings(0)); reading.join()
            assertFailsWith<IllegalStateException> { request.finishWrite { fail("失効後の書込") } }
        } finally { connections.shutdown() }
    }

    @Test fun `要求本体が返った後の子ストリームも停止対象として保持する`() = runCase {
        val connections = LocalConnections(this, { _, _ -> Listener() })
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        try {
            connections.set(settings(2))
            val call = launch {
                connections.withRequest(LocalFeature.MCP) {
                    CoroutineScope(currentCoroutineContext()).launch {
                        entered.complete(Unit)
                        try { awaitCancellation() } finally { cancelled.complete(Unit) }
                    }
                }
            }
            entered.await()
            assertTrue(connections.set(settings(0)).stopped)
            cancelled.await(); call.join()
        } finally { connections.shutdown() }
    }

    @Test fun `起動失敗は希望値を保持してエラーを表示し明示操作だけで再試行する`() = runCase {
        var failStart = true
        val created = mutableListOf<Listener>()
        val connections = LocalConnections(this, { _, _ -> Listener().also {
            it.onStart = { check(!failStart) { "ポート占有" } }; created += it
        } })
        try {
            val failed = connections.set(settings(1))
            assertTrue(failed.desired.apiEnabled)
            assertEquals(ConnectionPhase.ERROR, failed.listener)
            assertNull(failed.boundPort)
            assertContains(failed.error.orEmpty(), "ポート占有")
            assertEquals(1, created.size)
            assertEquals(1, created.single().closes.get())
            assertFalse(connections.withRequest(LocalFeature.API) {})
            failStart = false
            assertEquals(ConnectionPhase.RUNNING, connections.set(settings(1)).listener)
            assertEquals(2, created.size)
        } finally { connections.shutdown() }
    }

    @Test fun `停止失敗でOFF完了を偽らず同じ待受の停止を再試行する`() = runCase {
        var failClose = true
        val listener = Listener().apply { onClose = { check(!failClose) { "停止失敗" } } }
        val connections = LocalConnections(this, { _, _ -> listener })
        try {
            connections.set(settings(1))
            assertFailsWith<IllegalArgumentException> { connections.configure(settings(1).copy(port = 23456)) }
            val failed = connections.set(settings(0))
            assertEquals(ConnectionPhase.ERROR, failed.listener)
            assertFalse(failed.stopped)
            assertEquals(18765, failed.boundPort)
            assertFailsWith<IllegalArgumentException> { connections.configure(settings(0).copy(port = 23456)) }
            failClose = false
            assertTrue(connections.set(settings(0)).stopped)
            assertEquals(1, listener.starts.get())
            assertEquals(2, listener.closes.get())
            assertEquals(23456, connections.set(settings(0).copy(port = 23456)).desired.port)
        } finally { failClose = false; connections.shutdown() }
    }

    @Test fun `一機能のセッション終了失敗でも他機能を受付し再試行まで復帰させない`() = runCase {
        var failMcp = true
        val connections = LocalConnections(this, { _, _ -> Listener() }, onDisabled = {
            if (it == LocalFeature.MCP) check(!failMcp) { "セッション終了失敗" }
        })
        try {
            connections.set(settings(3))
            val failed = connections.set(settings(1))
            assertEquals(ConnectionPhase.ERROR, failed.features.getValue(LocalFeature.MCP).phase)
            assertTrue(connections.withRequest(LocalFeature.API) {})
            assertFalse(connections.withRequest(LocalFeature.MCP) {})
            failMcp = false
            assertEquals(ConnectionPhase.STOPPED, connections.set(settings(1)).features.getValue(LocalFeature.MCP).phase)
        } finally { failMcp = false; connections.shutdown() }
    }

    @Test fun `起動中の完全終了と終了後の再設定を扱う`() = runCase {
        val entered = CompletableDeferred<Unit>()
        val release = Semaphore(0)
        val listener = Listener().apply { onStart = { entered.complete(Unit); check(release.tryAcquire(10, TimeUnit.SECONDS)) } }
        val connections = LocalConnections(this, { _, _ -> listener })
        try {
            connections.configure(settings(7)); entered.await()
            val shutdown = launch { connections.shutdown() }
            connections.status.first { !it.desired.requiresListener }
            assertFailsWith<IllegalStateException> { connections.configure(settings(1)) }
            release.release()
            shutdown.join()
            assertTrue(connections.status.value.stopped)
            assertEquals(1, listener.closes.get())
            connections.shutdown()
        } finally { release.release(); connections.shutdown() }
    }

    @Test fun `アプリの寿命が取消されても待受を解放する`() = runCase {
        val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val listener = Listener()
        val connections = LocalConnections(lifetime, { _, _ -> listener })
        try {
            connections.set(settings(7))
            lifetime.cancel()
            connections.status.first { it.stopped }
            assertEquals(1, listener.closes.get())
        } finally { lifetime.cancel(); connections.shutdown() }
    }

    @Test fun `既に終了した寿命へ構築しても待受せず終了を待ち続けない`() = runCase {
        val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        lifetime.cancel()
        val connections = LocalConnections(lifetime, { _, _ -> error("構築してはいけない") })
        connections.shutdown()
        assertTrue(connections.status.value.stopped)
        assertFailsWith<IllegalStateException> { connections.configure(settings(1)) }
    }
}
