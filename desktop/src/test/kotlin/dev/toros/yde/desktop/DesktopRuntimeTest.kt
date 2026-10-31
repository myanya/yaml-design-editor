package dev.toros.yde.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopRuntimeTest {
    private val root = Files.createTempDirectory("desktop-runtime")
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    @Test fun `Windowなしでも下書きと履歴と実行状態を持ち失敗した切替で失わない`(): Unit = runBlocking {
        val file = root.resolve("a.yaml")
        Files.writeString(file, "name: A\n")
        val opened = CompletableDeferred<Unit>()
        val closed = AtomicInteger()
        withContext(DesktopUiDispatcher) {
            val runtime = DesktopRuntime(monitorFactory = {
                opened.complete(Unit)
                object : WorkspaceMonitor {
                    override fun consumePaths(): Set<Path> = emptySet()
                    override fun close() { closed.incrementAndGet() }
                }
            })
            try {
                assertTrue(runtime.openWorkspace(root))
                withTimeout(5_000) { opened.await() }
                val state = runtime.state
                state.openFile(file)
                val doc = assertNotNull(state.openDoc)
                val controller = assertNotNull(state.runController)
                val automation = assertNotNull(state.automation)
                assertFalse(automation.enabled)
                val connections = runtime.connections
                assertTrue(connections.status.value.stopped, "画面とワークスペースを開くだけでは待受しない")
                doc.editSource { replace(0, length, "name: edited\n") }
                assertTrue(state.hasDirtyDocs(), "解析前の入力も終了確認の対象")
                withTimeout(5_000) { runtime.host.status.first { !doc.hasPendingSourceInput && doc.dirty } }
                state.undo()
                assertContains(doc.source.text.toString(), "A")
                state.redo()
                assertContains(doc.source.text.toString(), "edited")
                assertFalse(runtime.openWorkspace(root.resolve("missing")))
                assertSame(doc, state.openDoc)
                assertSame(controller, state.runController)
                assertSame(automation, state.automation)
                assertSame(connections, runtime.connections)
                assertTrue(connections.status.value.stopped)
                assertEquals(0, closed.get())
                doc.editSource { replace(0, length, "name: [") }
                withTimeout(5_000) { runtime.host.status.first { doc.draftBroken } }
                Files.writeString(file, "name: external\n")
                state.noteFileEvents(setOf(file))
                assertEquals("name: [", doc.source.text.toString())
                assertTrue(doc.externalConflict)
            } finally { runtime.shutdown() }
            assertEquals(1, closed.get())
            assertTrue(runtime.host.status.value.closed)
            assertTrue(runtime.connections.status.value.stopped)
            assertFailsWith<IllegalStateException> { runtime.connections.configure(dev.toros.yde.server.LocalConnectionSettings(apiEnabled = true)) }
        }
    }

    @Test fun `切替で旧監視を閉じ新しい世代だけが有効になる`() = runBlocking {
        val next = Files.createDirectory(root.resolve("next"))
        Files.writeString(root.resolve("a.yaml"), "name: A\n")
        Files.writeString(next.resolve("b.yaml"), "name: B\n")
        val opened = kotlinx.coroutines.channels.Channel<Path>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val closed = java.util.concurrent.ConcurrentLinkedQueue<Path>()
        withContext(DesktopUiDispatcher) {
            val runtime = DesktopRuntime(monitorFactory = { path ->
                opened.trySend(path)
                object : WorkspaceMonitor {
                    override fun consumePaths(): Set<Path> = emptySet()
                    override fun close() { closed.add(path) }
                }
            })
            try {
                assertTrue(runtime.openWorkspace(root))
                withTimeout(5_000) { assertEquals(root, opened.receive()) }
                val old = runtime.host.status.value.epoch
                assertTrue(runtime.openWorkspace(next))
                withTimeout(5_000) { assertEquals(next, opened.receive()) }
                assertEquals(listOf(root), closed.toList())
                assertNotEquals(old, runtime.host.status.value.epoch)
                assertFailsWith<IllegalStateException> { runtime.host.access(old) { it.workspace } }
                assertEquals(next, runtime.state.workspace)
            } finally { runtime.shutdown(); opened.close() }
            assertEquals(listOf(root, next), closed.toList())
        }
    }

    @Test fun `読込と監視登録の間の同時刻の上書きも取り込む`() = runBlocking {
        val file = root.resolve("a.yaml")
        Files.writeString(file, "name: before\n")
        val stamp = Files.getLastModifiedTime(file)
        withContext(DesktopUiDispatcher) {
            val runtime = DesktopRuntime(monitorFactory = {
                Files.writeString(file, "name: after\n")
                Files.setLastModifiedTime(file, stamp)
                object : WorkspaceMonitor {
                    override fun consumePaths(): Set<Path> = emptySet()
                    override fun close() {}
                }
            })
            try {
                assertTrue(runtime.openWorkspace(root))
                runtime.state.openFile(file)
                val doc = assertNotNull(runtime.state.openDoc)
                withTimeout(5_000) { runtime.host.status.first { doc.canonicalText().contains("after") } }
                assertFalse(runtime.state.hasDirtyDocs())
            } finally { runtime.shutdown() }
        }
    }
}
