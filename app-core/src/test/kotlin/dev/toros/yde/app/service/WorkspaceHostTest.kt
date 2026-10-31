package dev.toros.yde.app.service

import dev.toros.yde.app.core.EditBuffer
import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class WorkspaceHostTest {
    private val root = Files.createTempDirectory("workspace-host")
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }
    private fun file() = root.resolve("a.yaml").also { Files.writeString(it, "name: A\n") }

    @Test fun `購読を外しても共有の文書と履歴は残り再購読できる`() {
        val file = file()
        val host = WorkspaceHost()
        host.openWorkspace(root)
        var first = 0; var second = 0
        val subscription = host.observe { first++ }
        val another = host.observe { second++ }
        val doc = host.documents.ensureDocument(file)
        doc.applySourceText("name: B\n")
        subscription.close()
        val oldCount = first
        doc.applySourceText("name: C\n")
        assertEquals(oldCount, first)
        assertTrue(second > first)
        assertSame(doc, host.documents.document(file))
        assertTrue(host.documents.kernel!!.canUndo())
        assertTrue(DocumentId("a.yaml") in host.status.value.dirty)
        another.close()
        host.close()
    }

    @Test fun `開く候補の失敗では旧世代と編集を保持する`() {
        val file = file()
        val host = WorkspaceHost()
        host.openWorkspace(root)
        val doc = host.documents.ensureDocument(file)
        doc.noteSourceInput("name: [")
        val epoch = host.status.value.epoch
        assertFails { host.openWorkspace(root.resolve("missing")) }
        assertEquals(epoch, host.status.value.epoch)
        assertSame(doc, host.documents.document(file))
        assertEquals("name: [", doc.draft)
        host.openWorkspace(root)
        assertNotEquals(epoch, host.status.value.epoch)
        assertFailsWith<IllegalStateException> { host.assertAvailable(epoch) }
        host.close()
    }

    @Test fun `画面なしで解析し古い結果と閉じた文書を採用しない`() = runBlocking {
        val file = file()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try { withContext(dispatcher) {
            val host = WorkspaceHost(owner = dispatcher)
            host.openWorkspace(root)
            val doc = host.documents.ensureDocument(file)
            val parseEntered = CompletableDeferred<Unit>()
            val oldResult = CompletableDeferred<Unit>()
            val workerScope = CoroutineScope(SupervisorJob() + dispatcher)
            val worker = SourceDraftWorker(host, workerScope, delayMillis = 0) { text ->
                if (text.contains("old")) withContext(NonCancellable) { parseEntered.complete(Unit); oldResult.await() }
                EditBuffer.parse(text)
            }
            try {
                doc.noteSourceInput("name: old\n")
                withTimeout(5_000) { parseEntered.await() }
                doc.noteSourceInput("name: new\n")
                withTimeout(5_000) { host.status.first { doc.sourceApplied == "name: new\n" } }
                oldResult.complete(Unit)
                workerScope.coroutineContext.job.children.toList().forEach { it.join() }
                assertContains(doc.canonicalText(), "new")
                doc.noteSourceInput("broken: [")
                withTimeout(5_000) { host.status.first { doc.draftBroken } }
                assertEquals("broken: [", doc.draft)
                assertTrue(host.documents.kernel!!.canUndo())
            } finally { oldResult.complete(Unit); worker.close(); workerScope.cancel(); host.close() }
        } } finally { dispatcher.close() }
    }

    @Test fun `排他IO中の要求を拒否し呼出しの取消後も反映まで終える`() = runBlocking {
        file()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val release = CountDownLatch(1)
        try { withContext(dispatcher) {
            val host = WorkspaceHost(owner = dispatcher)
            host.openWorkspace(root)
            val entered = CompletableDeferred<Unit>()
            var applied = false
            val before = host.currentStatus().revision
            val operation = launch {
                host.exclusiveIo("試験", {
                    entered.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS))
                    "完了"
                }) { applied = it == "完了" }
            }
            entered.await()
            assertEquals("試験", host.status.value.busy)
            assertEquals(before, host.currentStatus().revision, "受付状態だけでデータの版を進めない")
            assertFailsWith<IllegalStateException> { host.access { it.workspace } }
            operation.cancel()
            assertFalse(applied)
            release.countDown()
            operation.join()
            assertTrue(applied)
            assertNull(host.status.value.busy)
            host.close()
        } } finally { release.countDown(); dispatcher.close() }
    }

    @Test fun `別文書の解析完了順が逆でも入力順の単一履歴を保つ`() = runBlocking {
        val first = file()
        val second = root.resolve("b.yaml").also { Files.writeString(it, "name: B\n") }
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try { withContext(dispatcher) {
            val host = WorkspaceHost(owner = dispatcher)
            host.openWorkspace(root)
            val a = host.documents.ensureDocument(first)
            val b = host.documents.ensureDocument(second)
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val secondParsed = CompletableDeferred<Unit>()
            val workerScope = CoroutineScope(SupervisorJob() + dispatcher)
            val worker = SourceDraftWorker(host, workerScope, 0) { text ->
                if (text.contains("first")) { firstEntered.complete(Unit); releaseFirst.await() }
                else secondParsed.complete(Unit)
                EditBuffer.parse(text)
            }
            try {
                a.noteSourceInput("name: first\n")
                withTimeout(5_000) { firstEntered.await() }
                b.noteSourceInput("name: second\n")
                withTimeout(5_000) { secondParsed.await() }
                assertEquals(0, host.documents.kernel!!.historySize)
                releaseFirst.complete(Unit)
                withTimeout(5_000) { host.status.first { !a.hasPendingSourceInput && !b.hasPendingSourceInput } }
                val kernel = host.documents.kernel!!
                assertEquals(2, kernel.historySize)
                assertTrue(kernel.peekUndo()!!.forward.operations.any {
                    it is dev.toros.yde.workspace.WorkspaceOperation.ReplaceDocument && it.documentId == DocumentId("b.yaml")
                })
            } finally { releaseFirst.complete(Unit); worker.close(); workerScope.cancel(); host.close() }
        } } finally { dispatcher.close() }
    }
}
