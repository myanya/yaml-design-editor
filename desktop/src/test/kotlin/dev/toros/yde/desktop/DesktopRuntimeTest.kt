package dev.toros.yde.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import dev.toros.yde.app.service.ConnectionPermissions
import dev.toros.yde.app.service.WorkspaceOwnership
import dev.toros.yde.app.service.WorkspaceInUseException
import dev.toros.yde.server.LocalFeature
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.desktop.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopRuntimeTest {
    private val root = Files.createTempDirectory("desktop-runtime")
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    @Test fun `競合と読込失敗で旧所有権を保ち切替と完全終了で解放する`(): Unit = runBlocking {
        val locks = WorkspaceOwnership(root.resolve("locks"))
        val first = Files.createDirectory(root.resolve("first"))
        val second = Files.createDirectory(root.resolve("second"))
        withContext(DesktopUiDispatcher) {
            val a = DesktopRuntime(monitorFactory = { null }, ownership = locks)
            val b = DesktopRuntime(monitorFactory = { null }, ownership = locks)
            try {
                assertTrue(a.openWorkspace(first))
                assertTrue(a.openWorkspace(first.resolve(".")), "自分の場所は開き直せる")
                assertFalse(b.openWorkspace(first))
                assertContains(b.state.status, "使用中")
                assertTrue(b.connections.status.value.stopped)
                assertTrue(b.openWorkspace(second))
                assertContains(b.state.status, "「second」を開きました", message = "開いたら初期の案内や前の失敗の文言を残さない")
                assertFalse(a.openWorkspace(second))
                assertEquals(first, a.state.workspace)
                assertFalse(a.openWorkspace(root.resolve("missing")))
                assertFailsWith<WorkspaceInUseException> { locks.acquire(first) }
                locks.acquire(root.resolve("missing")).close()
                assertFailsWith<WorkspaceInUseException> {
                    b.state.initializeWorkspace(first, dev.toros.yde.workspace.InitPlan(emptyList()))
                }
                assertFalse(Files.exists(first.resolve(".yde")))
                b.shutdown()
                assertTrue(a.openWorkspace(second))
                locks.acquire(first).close()
                assertFailsWith<WorkspaceInUseException> { locks.acquire(second) }
            } finally { a.shutdown(); b.shutdown() }
            locks.acquire(second).close()
        }
    }

    @Test fun `破棄時も実行中の処理が終わるまで所有権を解放しない`(): Unit = runBlocking {
        val locks = WorkspaceOwnership(root.resolve("locks"))
        val ws = Files.createDirectory(root.resolve("workspace"))
        withContext(DesktopUiDispatcher) {
            val runtime = DesktopRuntime(monitorFactory = { null }, ownership = locks)
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            try {
                assertTrue(runtime.openWorkspace(ws))
                val saving = launch { runtime.host.exclusive("保存の試験") { entered.complete(Unit); finish.await() } }
                entered.await()
                runtime.cancel()
                yield()
                assertFailsWith<WorkspaceInUseException> { locks.acquire(ws) }
                finish.complete(Unit)
                saving.join()
                withTimeout(5_000) { runtime.host.status.first { it.closed } }
                locks.acquire(ws).close()
            } finally { finish.complete(Unit); runtime.shutdown() }
        }
    }

    @Test fun `隠しても編集中の文書と実行を保ち受信箱から同じ状態へ戻れる`(): Unit = runBlocking {
        val file = root.resolve("a.yaml")
        Files.writeString(file, "name: A\n")
        withContext(DesktopUiDispatcher) {
            val runtime = DesktopRuntime(monitorFactory = { null })
            var last: DesktopTrayStatus? = null
            val presence = DesktopPresence({ object : DesktopTray {
                override fun update(status: DesktopTrayStatus) { last = status }
                override fun close() {}
            } }, {})
            try {
                assertTrue(runtime.openWorkspace(root))
                presence.start(ResidentSettings(true), StartupVisibility.DEFAULT)
                val state = runtime.state
                state.openFile(file)
                val doc = assertNotNull(state.openDoc)
                val runs = assertNotNull(state.runController)
                doc.editSource { replace(0, length, "name: edited\n") }
                assertTrue(presence.hide())
                withTimeout(5_000) { runtime.host.status.first { !doc.hasPendingSourceInput && doc.dirty } }
                Files.writeString(file, "name: external\n")
                state.noteFileEvents(setOf(file))
                presence.updateInbox(true, state.pendingInboxCount())
                assertEquals(1, last?.inboxCount)
                assertFalse(presence.status.value.visible)
                state.memory.runsTab = 3
                state.memory.externalWorkReview = "old"
                state.openInbox(); presence.show()
                assertSame(doc, state.openDoc)
                assertSame(runs, state.runController)
                assertEquals(UiSpace.RUNS, state.uiSpace)
                assertEquals(0, state.memory.runsTab)
                assertNull(state.memory.externalWorkReview)
                assertTrue(state.hasDirtyDocs())
                assertFalse(presence.requestExit(state.hasDirtyDocs()))
                presence.cancelExit()
                assertTrue(runtime.connections.status.value.stopped)
                assertEquals("name: external\n", Files.readString(file), "隠す・戻す・終了中断は保存しない")
            } finally { presence.close(); runtime.shutdown() }
        }
    }

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
                assertFailsWith<IllegalArgumentException> { runtime.access.register(LocalFeature.MCP, "試験", ConnectionPermissions()) }
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
                val client = runtime.access.register(LocalFeature.MCP, "試験", ConnectionPermissions(readable = setOf(DocumentId("a.yaml"))))
                assertEquals(runtime.operations.currentWorkspaceId(), client.caller.workspaceId)
                assertFailsWith<IllegalStateException> { runtime.access.issueBearer(client.id) }
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
                assertEquals(listOf(client), runtime.access.clients())
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

    @Test fun `監視登録を待つ間の未保存編集を初回の背景照合で失わない`() = runBlocking {
        val file = root.resolve("a.yaml")
        Files.writeString(file, "name: before\n")
        val stamp = Files.getLastModifiedTime(file)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withContext(DesktopUiDispatcher) {
            val runtime = DesktopRuntime(monitorFactory = {
                entered.complete(Unit)
                runBlocking { release.await() }
                Files.writeString(file, "name: external\n")
                Files.setLastModifiedTime(file, stamp)
                object : WorkspaceMonitor {
                    override fun consumePaths(): Set<Path> = emptySet()
                    override fun close() {}
                }
            })
            try {
                assertTrue(runtime.openWorkspace(root))
                withTimeout(5_000) { entered.await() }
                runtime.state.openFile(file)
                val doc = assertNotNull(runtime.state.openDoc)
                doc.editSource { replace(0, length, "name: edited\n") }
                withTimeout(5_000) { runtime.host.status.first { doc.dirty && !doc.hasPendingSourceInput } }
                release.complete(Unit)
                withTimeout(5_000) { runtime.host.status.first { doc.externalConflict } }
                assertContains(doc.canonicalText(), "edited")
                assertEquals("name: external\n", Files.readString(file))
                assertTrue(runtime.state.hasDirtyDocs())
            } finally { release.complete(Unit); runtime.shutdown() }
        }
    }
}
