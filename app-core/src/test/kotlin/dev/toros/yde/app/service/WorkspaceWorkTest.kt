package dev.toros.yde.app.service

import dev.toros.yde.workspace.changeset.ChangeSet
import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.workspace.changeset.FileChange
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.patch.PatchOp
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlin.test.*

class WorkspaceWorkTest {
    private class Fixture(val root: Path, val host: WorkspaceHost) {
        val caller = OperationCaller("workspace", "external")
        var grant: WorkGrant? = WorkGrant(1, setOf("a.yaml", "b.yaml", "new.yaml").map(::DocumentId).toSet())
        val journal get() = FileWorkJournal(root, host.documents.core!!.layout)
        val operations get() = FileOperationJournal(root, host.documents.core!!.layout)
        fun service(log: WorkJournal = journal, operationLog: OperationJournal = operations) = WorkspaceWork(host,
            grants = { if (it == caller) grant else null }, workspaceId = { "workspace" }, journals = { _, _ -> log },
            operationJournals = { _, _ -> operationLog })
        fun changes(value: String = "external", file: String = "a.yaml") = ChangeSets.parse(
            "changes:\n  - file: $file\n    ops:\n      - {op: set, path: title, value: $value}\n")
        suspend fun submit(service: WorkspaceWork): WorkRecord {
            val work = service.begin(caller, "タイトルを更新")
            service.stage(caller, work.id, changes())
            return service.submit(caller, work.id)
        }
    }
    private fun runCase(block: suspend Fixture.() -> Unit): Unit = runBlocking {
        val root = Files.createTempDirectory("external-work")
        val owner = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            Files.writeString(root.resolve("a.yaml"), "# 元の注釈\ntitle: original\n")
            Files.writeString(root.resolve("b.yaml"), "title: b\n")
            withContext(owner) {
                val host = WorkspaceHost(owner = owner)
                host.openWorkspace(root)
                try { Fixture(root, host).block() } finally { host.close() }
            }
        } finally { owner.close(); root.toFile().deleteRecursively() }
    }

    @Test fun `提出を再起動後に復元し確認した内容だけを一度保存する`() = runCase {
        val service = service()
        val work = submit(service)
        assertEquals(WorkState.AWAITING_APPROVAL, work.state)
        assertEquals(work, service.submit(caller, work.id))
        assertContains(Files.readString(root.resolve("a.yaml")), "original")
        val restarted = service()
        assertEquals(listOf(work), restarted.refresh())
        assertFailsWith<IllegalStateException> { restarted.approve(work.id, "未確認") }
        val plan = restarted.review(work.id)
        assertContains(plan.documents.single().after, "external")
        assertEquals(WorkState.APPLIED, restarted.approve(work.id, plan.id).state)
        assertContains(Files.readString(root.resolve("a.yaml")), "external")
        assertEquals(0, host.documents.kernel!!.historySize)
        assertEquals(WorkState.APPLIED, service().result(caller, work.id).state)
        assertFailsWith<IllegalStateException> { restarted.approve(work.id, plan.id) }
        assertEquals(1, operations.readAll().size)
    }

    @Test fun `結果照会で編集を待たせず読み取り中の権限失効を再確認する`() = runCase {
        val original = service()
        val work = submit(original)
        val busy = mutableListOf<String>()
        val subscription = host.observe { host.status.value.busy?.let(busy::add) }
        try {
            original.refresh()
            original.result(caller, work.id)
            assertTrue(busy.isEmpty())
            val disk = journal
            val revoked = service(object : WorkJournal {
                override fun readAll(): List<WorkRecord> = disk.readAll().also { grant = null }
                override fun write(record: WorkRecord) = disk.write(record)
            })
            assertFailsWith<IllegalArgumentException> { revoked.result(caller, work.id) }
        } finally { subscription.close() }
    }

    @Test fun `挿入した配列への追記で過去のパッチ値を変更せず検証を繰り返せる`() = runCase {
        val service = service()
        val work = service.begin(caller, "入れ子の追記")
        service.stage(caller, work.id, ChangeSet(listOf(FileChange("a.yaml", null,
            listOf(PatchOp.Insert(listOf("items"), YamlIO.parse("[]")))))))
        service.stage(caller, work.id, ChangeSet(listOf(FileChange("a.yaml", null,
            listOf(PatchOp.Insert(listOf("items", 0), YNode.Scalar("one")))))))
        repeat(2) { assertTrue(service.validate(caller, work.id).valid) }
        service.stage(caller, work.id, ChangeSet(listOf(FileChange("a.yaml", null,
            listOf(PatchOp.Insert(listOf("items", 1), YNode.Scalar("two")))))))
        service.submit(caller, work.id)
        val restarted = service()
        val plan = restarted.review(work.id)
        assertEquals(listOf("one", "two"), (YamlIO.parseToRaw(plan.documents.single().after) as Map<*, *>)["items"])
        assertEquals(WorkState.APPLIED, restarted.approve(work.id, plan.id).state)
        assertEquals(listOf("one", "two"), (YamlIO.parseToRaw(Files.readString(root.resolve("a.yaml"))) as Map<*, *>)["items"])
    }

    @Test fun `未提出はメモリだけで提出後の追記と却下後の承認を拒否する`() = runCase {
        val service = service()
        val unfinished = service.begin(caller, "未提出")
        service.stage(caller, unfinished.id, changes())
        assertTrue(journal.readAll().isEmpty())
        assertFailsWith<IllegalStateException> { service().result(caller, unfinished.id) }
        val work = submit(service)
        assertFailsWith<IllegalStateException> { service.stage(caller, work.id, changes("overwrite")) }
        service.review(work.id)
        assertEquals(WorkState.REJECTED, service.reject(work.id).state)
        assertEquals(WorkState.REJECTED, service().result(caller, work.id).state)
        assertFailsWith<IllegalArgumentException> { service.review(work.id) }
        assertContains(Files.readString(root.resolve("a.yaml")), "original")
    }

    @Test fun `所有者と文書範囲と既定拒否を強制し権限変更後は承認できない`() = runCase {
        val service = service()
        val work = submit(service)
        assertFailsWith<IllegalArgumentException> { service.result(caller.copy(callerId = "other"), work.id) }
        assertFailsWith<IllegalArgumentException> { service.result(caller.copy(workspaceId = "other"), work.id) }
        val plan = service.review(work.id)
        grant = grant!!.copy(revision = 2)
        assertFailsWith<IllegalArgumentException> { service.approve(work.id, plan.id) }
        grant = grant!!.copy(documents = emptySet())
        assertFailsWith<IllegalArgumentException> { service.result(caller, work.id) }
        assertFailsWith<IllegalArgumentException> { service.review(work.id) }
        grant = null
        assertFailsWith<IllegalArgumentException> { service.begin(caller, "無許可") }
        assertEquals(WorkState.REJECTED, service.reject(work.id).state, "人間は失効済み接続の提案も却下できる")
        assertFailsWith<IllegalArgumentException> { WorkspaceWork(host).begin(caller, "既定拒否") }
        assertTrue(operations.readAll().isEmpty())
    }

    @Test fun `不許可の対象と定義とパスの別表記を編集前に拒否する`() = runCase {
        val service = service()
        val work = service.begin(caller, "範囲")
        for (file in listOf("forbidden.yaml", "../a.yaml", "./a.yaml", "_schemas/test.yaml")) {
            assertFailsWith<IllegalArgumentException> { service.stage(caller, work.id, changes(file = file)) }
        }
        assertTrue(journal.readAll().isEmpty())
        assertContains(Files.readString(root.resolve("a.yaml")), "original")
    }

    @Test fun `編集要求の途中失敗を次の提出へ持ち越さない`() = runCase {
        val service = service()
        val work = service.begin(caller, "原子的な編集")
        service.stage(caller, work.id, changes("first"))
        val broken = ChangeSet(listOf(FileChange("a.yaml", null, listOf(
            PatchOp.Set(listOf("title"), YNode.Scalar("accidental")), PatchOp.Delete(listOf("missing"))))))
        assertFails { service.stage(caller, work.id, broken) }
        assertEquals(WorkState.AWAITING_APPROVAL, service.submit(caller, work.id).state)
        assertContains(service.review(work.id).documents.single().after, "first")
    }

    @Test fun `作業中のディスク変更と承認時の未保存編集を拒否する`() = runCase {
        val service = service()
        val unfinished = service.begin(caller, "古い作業")
        Files.writeString(root.resolve("b.yaml"), "title: changed\n")
        assertFailsWith<IllegalArgumentException> { service.stage(caller, unfinished.id, changes()) }
        val work = submit(service)
        val doc = host.documents.ensureDocument(root.resolve("a.yaml"))
        doc.noteSourceInput("title: [")
        assertFailsWith<IllegalArgumentException> { service.review(work.id) }
        assertEquals(WorkState.AWAITING_APPROVAL, service().result(caller, work.id).state)
    }

    @Test fun `確認後の変更は確定失敗として残り二度目の承認で再実行しない`() = runCase {
        val service = service()
        val work = submit(service)
        val preview = service.review(work.id)
        Files.writeString(root.resolve("b.yaml"), "title: after-preview\n")
        val result = service.approve(work.id, preview.id)
        assertEquals(WorkState.FAILED, result.state)
        assertEquals(WorkState.FAILED, service().result(caller, work.id).state)
        assertFailsWith<IllegalArgumentException> { service.review(work.id) }
        assertContains(Files.readString(root.resolve("a.yaml")), "original")
    }

    @Test fun `新規文書のコメントとパッチの特殊なキーと値を復元する`() = runCase {
        val service = service()
        val work = service.begin(caller, "注釈付きの新規作成")
        val node = YamlIO.parse("# 注釈を残す\ntitle: added\n'a.b': before\n")
        service.stage(caller, work.id, ChangeSet(listOf(FileChange("new.yaml", null, emptyList(), create = true, content = node))))
        service.stage(caller, work.id, ChangeSet(listOf(FileChange("new.yaml", null, listOf(PatchOp.Set(listOf("a.b"), YNode.Scalar("after")))))))
        service.submit(caller, work.id)
        val restarted = service()
        val plan = restarted.review(work.id)
        assertContains(plan.documents.single().after, "注釈を残す")
        assertEquals(WorkState.APPLIED, restarted.approve(work.id, plan.id).state)
        assertContains(Files.readString(root.resolve("new.yaml")), "注釈を残す")
        assertContains(Files.readString(root.resolve("new.yaml")), "after")
        val complex = ChangeSet(listOf(FileChange("a.yaml", "hash", listOf(
            PatchOp.Set(listOf("a.b", 0, "x[y]"), YamlIO.parse("# コメント\na: 1\n")),
            PatchOp.Insert(listOf("0"), YNode.Scalar(null)), PatchOp.Delete(listOf(1)),
            PatchOp.Move(listOf("a", 1), listOf("b", 2)), PatchOp.RenameKey(listOf("a"), "renamed"),
            PatchOp.ReorderKeys(emptyList(), listOf("b", "a"))))))
        assertEquals(WorkChanges.encode(complex), WorkChanges.encode(WorkChanges.decode(WorkChanges.encode(complex))))
    }

    @Test fun `破棄した作業は再開せず提出済みの破棄だけを永続化する`() = runCase {
        val service = service()
        val unfinished = service.begin(caller, "破棄する下書き")
        assertEquals(WorkState.DISCARDED, service.discard(caller, unfinished.id).state)
        assertFailsWith<IllegalArgumentException> { service.stage(caller, unfinished.id, changes()) }
        assertTrue(journal.readAll().isEmpty())
        val work = submit(service)
        service.review(work.id)
        service.discard(caller, work.id)
        assertEquals(WorkState.DISCARDED, service().result(caller, work.id).state)
        assertFails { service.approve(work.id, "old") }
        assertContains(Files.readString(root.resolve("a.yaml")), "original")
    }

    @Test fun `検証エラーを残して提出できず修正後に提出できる`() = runCase {
        Files.createDirectories(root.resolve("_schemas"))
        Files.writeString(root.resolve("_schemas/test.yaml"), "id: test\nmatch: '*.yaml'\nroot:\n  type: object\n  fields:\n    - {key: title, type: string, required: true}\n")
        val service = service()
        val work = service.begin(caller, "検証")
        service.stage(caller, work.id, changes("null"))
        assertFalse(service.validate(caller, work.id).valid)
        assertFailsWith<IllegalStateException> { service.submit(caller, work.id) }
        assertTrue(journal.readAll().isEmpty())
        service.stage(caller, work.id, changes("fixed"))
        assertTrue(service.validate(caller, work.id).valid)
        assertEquals(WorkState.VALIDATED, service.result(caller, work.id).state)
        assertEquals(WorkState.AWAITING_APPROVAL, service.submit(caller, work.id).state)
    }

    @Test fun `提出記録の保存失敗では作業コピーを失わず原本にも書かない`() = runCase {
        var failing = true
        val disk = journal
        val service = service(object : WorkJournal {
            override fun readAll() = disk.readAll()
            override fun write(record: WorkRecord) { if (failing) error("保存失敗"); disk.write(record) }
        })
        val work = service.begin(caller, "失敗からの再提出")
        service.stage(caller, work.id, changes())
        assertFailsWith<IllegalStateException> { service.submit(caller, work.id) }
        failing = false
        assertEquals(WorkState.AWAITING_APPROVAL, service.submit(caller, work.id).state)
        assertContains(Files.readString(root.resolve("a.yaml")), "original")
    }

    @Test fun `結果記録だけ失敗しても要求記録から保存結果を復元する`() = runCase {
        val disk = journal
        val service = service(object : WorkJournal {
            override fun readAll() = disk.readAll()
            override fun write(record: WorkRecord) { if (record.state == WorkState.APPLIED) error("最終記録失敗"); disk.write(record) }
        })
        val work = submit(service)
        val plan = service.review(work.id)
        assertEquals(WorkState.UNKNOWN, service.approve(work.id, plan.id).state)
        assertEquals(WorkState.APPLIED, service().result(caller, work.id).state)
        assertEquals(1, operations.readAll().size)
        assertContains(Files.readString(root.resolve("a.yaml")), "external")
    }

    @Test fun `承認開始の記録中に権限を失った場合も原本へ保存しない`() = runCase {
        val disk = operations
        val service = service(operationLog = object : OperationJournal {
            override fun readAll() = disk.readAll()
            override fun write(record: OperationRecord) {
                disk.write(record)
                if (record.state == OperationState.APPLYING) grant = null
            }
        })
        val work = submit(service)
        val plan = service.review(work.id)
        assertEquals(WorkState.FAILED, service.approve(work.id, plan.id).state)
        assertEquals(OperationState.REJECTED, operations.readAll().single().state)
        assertContains(Files.readString(root.resolve("a.yaml")), "original")
    }

    @Test fun `共通確定窓口へ渡す前に失敗しても確定中表示を残さない`() = runCase {
        val disk = journal
        val service = service(object : WorkJournal {
            override fun readAll() = disk.readAll()
            override fun write(record: WorkRecord) {
                disk.write(record)
                if (record.state == WorkState.APPLYING) grant = null
            }
        })
        val work = submit(service)
        val plan = service.review(work.id)
        assertEquals(WorkState.UNKNOWN, service.approve(work.id, plan.id).state)
        assertEquals(WorkState.UNKNOWN, service().refresh().single().state)
        assertTrue(operations.readAll().isEmpty())
        assertContains(Files.readString(root.resolve("a.yaml")), "original")
    }

    @Test fun `中断した確定は結果不明で復元し自動適用と再承認を止める`() = runCase {
        val service = service()
        val work = submit(service)
        val intent = work.copy(state = WorkState.APPLYING, requestId = "work/${work.id}", planId = "crashed")
        journal.write(intent)
        operations.write(OperationRecord(caller.workspaceId, caller.callerId, intent.requestId!!, "crashed", "hash", OperationState.APPLYING,
            mapOf("a.yaml" to "old"), mapOf("a.yaml" to "new")))
        val restored = service().refresh().single()
        assertEquals(WorkState.UNKNOWN, restored.state)
        assertFailsWith<IllegalArgumentException> { service().review(work.id) }
        assertContains(Files.readString(root.resolve("a.yaml")), "original")
    }

    @Test fun `提出ファイルの上限と壊れた記録を無視しない`() = runCase {
        val service = service(FileWorkJournal(root, host.documents.core!!.layout, maxRecords = 1))
        submit(service)
        assertFailsWith<IllegalArgumentException> { submit(service) }
        val file = Files.list(root.resolve(host.documents.core!!.layout.runsDir).resolve("local-work")).use { it.findFirst().orElseThrow() }
        Files.writeString(file, "broken")
        assertFails { service().refresh() }
        assertContains(Files.readString(root.resolve("a.yaml")), "original")
    }

    @Test fun `承認途中の取消でも要求と提出の最終結果まで記録する`() = runCase {
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.Semaphore(0)
        val disk = operations
        val service = service(operationLog = object : OperationJournal {
            override fun readAll() = disk.readAll()
            override fun write(record: OperationRecord) {
                disk.write(record)
                if (record.state == OperationState.APPLYING) { entered.complete(Unit); check(release.tryAcquire(15, java.util.concurrent.TimeUnit.SECONDS)) }
            }
        })
        val work = submit(service)
        val preview = service.review(work.id)
        coroutineScope {
            val job = launch { service.approve(work.id, preview.id) }
            try {
                withTimeout(15_000) { entered.await() }
                job.cancel()
            } finally { release.release() }
            job.join()
        }
        assertEquals(WorkState.APPLIED, service().result(caller, work.id).state)
        assertEquals(WorkState.APPLIED, journal.readAll().single().state)
    }

    @Test fun `部分保存と照合済みの結果はファイル別の保存状態とともに復元する`() = runCase {
        val service = service()
        val work = submit(service)
        val intent = work.copy(state = WorkState.APPLYING, requestId = "work/${work.id}", planId = "partial")
        journal.write(intent)
        val result = OperationRecord(caller.workspaceId, caller.callerId, intent.requestId!!, "partial", "hash", OperationState.PARTIAL,
            mapOf("a.yaml" to "old", "b.yaml" to "old-b"), mapOf("a.yaml" to "new", "b.yaml" to "new-b"),
            mapOf("a.yaml" to "WRITTEN", "b.yaml" to "NOT_WRITTEN"), "一部の保存に失敗")
        operations.write(result)
        val partial = service().refresh().single()
        assertEquals(WorkState.PARTIAL, partial.state)
        assertEquals(result.persistence, partial.persistence)
        operations.write(result.copy(state = OperationState.RECONCILED))
        assertEquals(WorkState.RECONCILED, service().refresh().single().state)
    }

    @Test fun `別ワークスペースへの切替で古い作業と承認計画を引き継がない`() = runCase {
        val service = WorkspaceWork(host, grants = { grant })
        val current = caller.copy(workspaceId = ChangeSets.contentHash(root.toRealPath().toString()))
        val work = service.begin(current, "切替前")
        service.stage(current, work.id, changes())
        service.submit(current, work.id)
        service.review(work.id)
        val other = Files.createTempDirectory("other-workspace")
        try {
            Files.writeString(other.resolve("a.yaml"), "title: other\n")
            host.openWorkspace(other)
            assertTrue(service.refresh().isEmpty())
            assertFailsWith<IllegalArgumentException> { service.result(current, work.id) }
            assertFailsWith<IllegalStateException> { service.approve(work.id, "old") }
            host.openWorkspace(root)
            assertEquals(WorkState.AWAITING_APPROVAL, service.refresh().single().state)
        } finally { other.toFile().deleteRecursively() }
    }
}
