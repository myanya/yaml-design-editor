package dev.toros.yde.app.service

import dev.toros.yde.app.core.Workspace
import dev.toros.yde.workspace.*
import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlin.test.*

class WorkspaceOperationsTest {
    private class MemoryJournal : OperationJournal {
        val records = linkedMapOf<String, OperationRecord>()
        var fail: (OperationRecord) -> Boolean = { false }
        override fun readAll() = records.values.toList()
        override fun write(record: OperationRecord) {
            check(!fail(record)) { "記録の失敗" }
            records[record.requestId] = record
        }
    }
    private class Fixture(val root: Path, val host: WorkspaceHost) {
        val caller = OperationCaller("workspace", "client")
        var grant: OperationGrant? = OperationGrant(1, setOf(DocumentId("a.yaml"), DocumentId("b.yaml"), DocumentId("c.yaml")), applyChanges = true, recover = true)
        var now = Instant.parse("2026-10-31T03:00:00Z")
        val journal = MemoryJournal()
        fun service(loader: (Path) -> WorkspaceSession = { Workspace.open(it).mirror }, log: OperationJournal = journal) = WorkspaceOperations(
            host, grants = { grant }, workspaceId = { "workspace" }, journals = { _, _ -> log }, clock = { now }, load = loader,
        )
        fun changes(vararg files: String) = ChangeSets.parse("changes:\n" + files.joinToString("") {
            "  - file: $it\n    ops:\n      - {op: set, path: title, value: external}\n"
        })
    }
    private fun runCase(block: suspend Fixture.() -> Unit): Unit = runBlocking {
        val root = Files.createTempDirectory("operation-service")
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            listOf("a", "b", "c").forEach { Files.writeString(root.resolve("$it.yaml"), "title: $it\n") }
            withContext(dispatcher) {
                val host = WorkspaceHost(owner = dispatcher)
                host.openWorkspace(root)
                try { Fixture(root, host).block() } finally { host.close() }
            }
        } finally { dispatcher.close(); root.toFile().deleteRecursively() }
    }

    @Test fun `確定はUndoへ載らず同一再送と再起動後の照会は同じ結果`() = runCase {
        val service = service(log = FileOperationJournal(root, host.documents.core!!.layout))
        val doc = host.documents.ensureDocument(root.resolve("a.yaml"))
        doc.applySourceText("title: edited\n")
        assertTrue(host.documents.save(doc).ok)
        val beforeHistory = host.documents.kernel!!.historySize
        val plan = service.planChanges(caller, changes("a.yaml"))
        val result = service.commit(caller, plan.id, "one")
        assertEquals(OperationState.COMMITTED, result.state, result.message)
        assertEquals(beforeHistory, host.documents.kernel!!.historySize)
        assertFalse(host.documents.kernel!!.checkHistory(true).ok)
        assertContains(doc.canonicalText(), "external")
        assertEquals(result, service.commit(caller, plan.id, "one"))
        val restarted = service(log = FileOperationJournal(root, host.documents.core!!.layout))
        val busy = mutableListOf<String>()
        val observer = host.observe { host.status.value.busy?.let(busy::add) }
        try { assertEquals(result, restarted.result(caller, "one")) } finally { observer.close() }
        assertTrue(busy.isEmpty(), "結果照会で画面の編集を待たせない")
        assertEquals(result, restarted.commit(caller, plan.id, "one"))
        assertFailsWith<IllegalArgumentException> { restarted.commit(caller, "different", "one") }
    }

    @Test fun `未反映入力と壊れた下書きと無関係な編集を区別する`() = runCase {
        val service = service()
        val a = host.documents.ensureDocument(root.resolve("a.yaml"))
        a.noteSourceInput("title: [")
        assertFailsWith<IllegalArgumentException> { service.planChanges(caller, changes("a.yaml")) }
        val b = service.planChanges(caller, changes("b.yaml"))
        assertEquals(OperationState.COMMITTED, service.commit(caller, b.id, "b").state)
        assertEquals("title: [", a.draft)
        assertTrue(a.hasPendingSourceInput)
        val request = assertNotNull(a.sourceParseRequest())
        a.applyParsed(request, dev.toros.yde.app.core.EditBuffer.parse(request.text))
        assertTrue(a.draftBroken)
        assertFailsWith<IllegalArgumentException> { service.planChanges(caller, changes("a.yaml")) }
    }

    @Test fun `期限と入力後の版と権限変更と同時刻のディスク変更を拒否する`() = runCase {
        val service = service()
        suspend fun planned() = service.planChanges(caller, changes("a.yaml"))
        val expired = planned(); now = now.plusSeconds(601)
        assertEquals(OperationState.REJECTED, service.commit(caller, expired.id, "expiry").state)
        val revoked = planned(); grant = grant!!.copy(revision = 2)
        assertEquals(OperationState.REJECTED, service.commit(caller, revoked.id, "grant").state)
        val edited = planned()
        host.documents.ensureDocument(root.resolve("c.yaml")).noteSourceInput("title: input")
        assertEquals(OperationState.REJECTED, service.commit(caller, edited.id, "revision").state)
        val stale = planned()
        val path = root.resolve("b.yaml"); val stamp = Files.getLastModifiedTime(path)
        Files.writeString(path, "title: outside\n"); Files.setLastModifiedTime(path, stamp)
        assertEquals(OperationState.REJECTED, service.commit(caller, stale.id, "disk").state)
        assertEquals("title: a\n", Files.readString(root.resolve("a.yaml")))
    }

    @Test fun `未許可と定義文書と別の世代を拒否する`() = runCase {
        assertFailsWith<IllegalArgumentException> { WorkspaceOperations(host, workspaceId = { "workspace" }).planChanges(caller, changes("a.yaml")) }
        val service = service()
        val plan = service.planChanges(caller, changes("a.yaml"))
        host.openWorkspace(root)
        assertEquals(OperationState.REJECTED, service.commit(caller, plan.id, "epoch").state)
        Files.createDirectories(root.resolve("_schemas"))
        Files.writeString(root.resolve("_schemas/test.yaml"), "id: test\nroot: {type: object}\n")
        grant = grant!!.copy(documents = grant!!.documents + DocumentId("_schemas/test.yaml"))
        assertFailsWith<IllegalArgumentException> { service.planChanges(caller, changes("_schemas/test.yaml")) }
        grant = null
        assertFailsWith<IllegalArgumentException> { service.result(caller, "epoch") }
    }

    @Test fun `部分保存は成功扱いせず残りを同じ要求で再実行しない`() = runCase {
        val service = service(loader = { path ->
            val store = FileSystemDocumentStore(path)
            WorkspaceSession(object : DocumentStore by store {
                override fun write(id: DocumentId, text: String) {
                    check(id.value != "b.yaml") { "保存の失敗" }
                    store.write(id, text)
                }
            })
        })
        val plan = service.planChanges(caller, changes("a.yaml", "b.yaml"))
        val result = service.commit(caller, plan.id, "partial")
        assertEquals(OperationState.PARTIAL, result.state, result.message)
        assertEquals("WRITTEN", result.persistence["a.yaml"])
        assertEquals("NOT_WRITTEN", result.persistence["b.yaml"])
        assertEquals(result, service.commit(caller, plan.id, "partial"))
        assertEquals("title: b\n", Files.readString(root.resolve("b.yaml")))
        assertTrue(host.documents.kernel!!.dirtyDocuments().isEmpty())
    }

    @Test fun `結果記録失敗は再起動後も別IDで迂回できず明示照合まで止める`() = runCase {
        val service = service()
        journal.fail = { it.state == OperationState.COMMITTED || it.state == OperationState.UNKNOWN }
        val plan = service.planChanges(caller, changes("a.yaml"))
        assertEquals(OperationState.UNKNOWN, service.commit(caller, plan.id, "unknown").state)
        val restarted = service()
        assertEquals(OperationState.UNKNOWN, restarted.result(caller, "unknown")!!.state)
        val second = restarted.planChanges(caller, changes("a.yaml"))
        assertEquals(OperationState.REJECTED, restarted.commit(caller, second.id, "other-id").state)
        val hash = ChangeSets.contentHash(Files.readString(root.resolve("a.yaml")))
        assertFailsWith<IllegalArgumentException> { restarted.reconcile(caller, "unknown", mapOf("a.yaml" to "bad")) }
        assertEquals(OperationState.RECONCILED, restarted.reconcile(caller, "unknown", mapOf("a.yaml" to hash)).state)
        assertEquals(OperationState.RECONCILED, restarted.commit(caller, plan.id, "unknown").state)
    }

    @Test fun `開始記録の失敗なら文書を書かない`() = runCase {
        journal.fail = { it.state == OperationState.APPLYING }
        val service = service(); val plan = service.planChanges(caller, changes("a.yaml"))
        assertEquals(OperationState.REJECTED, service.commit(caller, plan.id, "failure").state)
        assertEquals("title: a\n", Files.readString(root.resolve("a.yaml")))
    }

    @Test fun `エラーを含む確定は拒否し無関係な既存エラーでは妨げない`() = runCase {
        Files.createDirectories(root.resolve("_schemas"))
        Files.writeString(root.resolve("_schemas/item.yaml"), """
            id: item
            match: '*.yaml'
            root:
              type: object
              fields:
                - {key: title, type: enum, values: [a, b, c], required: true}
        """.trimIndent())
        Files.writeString(root.resolve("c.yaml"), "title: invalid\n")
        val service = service()
        val invalid = service.planChanges(caller, changes("a.yaml"))
        assertFalse(invalid.canCommit)
        assertEquals(OperationState.REJECTED, service.commit(caller, invalid.id, "invalid").state)
        assertEquals("title: a\n", Files.readString(root.resolve("a.yaml")))
        val valid = service.planChanges(caller, ChangeSets.parse("changes: [{file: a.yaml, ops: [{op: set, path: title, value: b}]}]"))
        assertTrue(valid.canCommit, valid.issues.toString())
        assertEquals(OperationState.COMMITTED, service.commit(caller, valid.id, "valid").state)
    }

    @Test fun `許可した業務操作だけ計画し同じ候補を確定する`() = runCase {
        Files.createDirectories(root.resolve("_schemas"))
        Files.writeString(root.resolve("_schemas/item.yaml"), """
            id: item
            match: '*.yaml'
            root:
              type: object
              fields:
                - {key: title, type: string}
            actions:
              - id: rename
                target: {schema: item}
                do:
                  - set: {path: title, value: renamed}
        """.trimIndent())
        grant = grant!!.copy(actions = setOf("rename"), applyChanges = false)
        val service = service()
        assertFailsWith<IllegalArgumentException> { service.planChanges(caller, changes("a.yaml")) }
        val plan = service.planAction(caller, ActionRequest("rename", "a.yaml"))
        assertTrue(plan.canCommit, plan.failures.toString())
        val result = service.commit(caller, plan.id, "../操作\\結果")
        assertEquals(OperationState.COMMITTED, result.state, result.message)
        assertEquals(plan.documents.single().after, Files.readString(root.resolve("a.yaml")))
        grant = grant!!.copy(documents = emptySet())
        assertFailsWith<IllegalArgumentException> { service.result(caller, result.requestId) }
    }

    @Test fun `呼出しの取消後も保存と結果記録を終える`() = runCase {
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val log = object : OperationJournal by journal {
            override fun write(record: OperationRecord) {
                if (record.state == OperationState.APPLYING) {
                    entered.complete(Unit)
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }
                journal.write(record)
            }
        }
        val service = service(log = log)
        val plan = service.planChanges(caller, changes("a.yaml"))
        val job = CoroutineScope(currentCoroutineContext()).launch { service.commit(caller, plan.id, "cancel") }
        try {
            withTimeout(10_000) { entered.await() }
            assertFailsWith<IllegalStateException> { host.access { it.workspace } }
            job.cancel()
        } finally { release.countDown(); job.join() }
        assertEquals(OperationState.COMMITTED, service.result(caller, "cancel")!!.state)
        assertContains(Files.readString(root.resolve("a.yaml")), "external")
    }

    @Test fun `記録の破損と保管上限では新しい書込を始めない`() = runCase {
        val log = FileOperationJournal(root, host.documents.core!!.layout, maxRecords = 1)
        val service = service(log = log)
        val first = service.planChanges(caller, changes("a.yaml"))
        assertEquals(OperationState.COMMITTED, service.commit(caller, first.id, "first").state)
        val second = service.planChanges(caller, changes("b.yaml"))
        assertEquals(OperationState.REJECTED, service.commit(caller, second.id, "limit").state)
        assertEquals("title: b\n", Files.readString(root.resolve("b.yaml")))
        val dir = root.resolve(host.documents.core!!.layout.runsDir).resolve("local-operations")
        val file = Files.list(dir).use { it.findFirst().get() }
        Files.writeString(file, "broken")
        val third = service.planChanges(caller, changes("b.yaml"))
        assertFails { service.commit(caller, third.id, "broken") }
        assertEquals("title: b\n", Files.readString(root.resolve("b.yaml")))
    }

    @Test fun `新規文書のコメントを保ち計画後の入力差替えを採用しない`() = runCase {
        grant = grant!!.copy(documents = grant!!.documents + DocumentId("d.yaml"))
        val changes = dev.toros.yde.workspace.changeset.ChangeSet(listOf(
            dev.toros.yde.workspace.changeset.FileChange("d.yaml", null, emptyList(), create = true,
                content = dev.toros.yde.yaml.YamlIO.parse("title: original # 作成時の説明\n"))
        ))
        val service = service()
        val preview = service.planChanges(caller, changes)
        assertContains(preview.documents.single().after, "作成時の説明")
        (changes.changes.single().content as dev.toros.yde.yaml.model.YNode.Map).entries["title"] =
            dev.toros.yde.yaml.model.YNode.Scalar("差替え")
        assertEquals(OperationState.COMMITTED, service.commit(caller, preview.id, "create").state)
        assertEquals(preview.documents.single().after, Files.readString(root.resolve("d.yaml")))
    }

    @Test fun `保存中に文書が失われた結果不明では古い表示を残さない`() = runCase {
        host.documents.ensureDocument(root.resolve("a.yaml"))
        val service = service(loader = { path ->
            val store = FileSystemDocumentStore(path)
            WorkspaceSession(object : DocumentStore by store {
                override fun write(id: DocumentId, text: String) {
                    store.delete(id)
                    error("保存中に文書が失われました")
                }
            })
        })
        val plan = service.planChanges(caller, changes("a.yaml"))
        assertEquals(OperationState.UNKNOWN, service.commit(caller, plan.id, "lost").state)
        assertNull(host.documents.document(root.resolve("a.yaml")))
        assertFalse(host.documents.kernel!!.hasDocument(DocumentId("a.yaml")))
        assertEquals(OperationState.RECONCILED, service.reconcile(caller, "lost", mapOf("a.yaml" to null)).state)
    }
}
