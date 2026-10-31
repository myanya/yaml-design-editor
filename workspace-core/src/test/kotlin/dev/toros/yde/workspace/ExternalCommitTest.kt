package dev.toros.yde.workspace

import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.yaml.edit.YamlOperation
import dev.toros.yde.yaml.model.DocumentId
import java.time.LocalDateTime
import kotlin.test.*

class ExternalCommitTest {
    private val a = DocumentId("a.yaml")
    private val b = DocumentId("b.yaml")
    private val c = DocumentId("c.yaml")
    private fun store() = InMemoryDocumentStore(mapOf("a.yaml" to "title: A\n", "b.yaml" to "title: B\n", "c.yaml" to "title: C\n"))
    private fun changes(vararg files: String) = ChangeSets.parse("changes:\n" + files.joinToString("") {
        "  - file: $it\n    ops:\n      - {op: set, path: title, value: changed}\n"
    })
    private fun edit(s: WorkspaceSession, vararg ids: DocumentId) {
        assertTrue(s.apply(WorkspaceTransaction(s.version, ChangeIntent("編集"), ids.map {
            WorkspaceOperation.EditDocument(it, listOf(YamlOperation.SetScalar(listOf("title"), "edited")))
        })).ok)
        assertTrue(s.save(ids.toSet()).ok)
    }
    private fun external(store: DocumentStore, editor: WorkspaceSession, vararg ids: DocumentId) {
        val disk = WorkspaceSession(store)
        val result = ChangeSetApplier.applyPrepared(disk, ChangeSetApplier.prepare(disk, changes(*ids.map { it.value }.toTypedArray())))
        assertTrue(result.ok, result.toString())
        assertTrue(editor.refreshFrom(disk).ok)
        editor.invalidateExternalHistory(ids.toSet())
    }

    @Test fun `外部B確定の前のCは戻せるがBを越えてAへ飛ばない`() {
        val store = store(); val editor = WorkspaceSession(store)
        edit(editor, a); edit(editor, b); edit(editor, c)
        external(store, editor, b)
        assertEquals(3, editor.historySize)
        assertTrue(editor.undo().ok)
        assertFalse(editor.undo().ok)
        assertEquals("edited", (editor.copyDocument(a)!!.documents.first() as dev.toros.yde.yaml.model.YNode.Map).entries["title"]!!.let { (it as dev.toros.yde.yaml.model.YNode.Scalar).value })
        assertTrue(editor.redo().ok)
        assertFalse(editor.checkHistory(true).externalConflict)
    }

    @Test fun `無関係なRedoと新しい編集のUndoを保持する`() {
        val store = store(); val editor = WorkspaceSession(store)
        edit(editor, a); assertTrue(editor.undo().ok)
        external(store, editor, b)
        assertTrue(editor.redo().ok)
        edit(editor, c)
        assertTrue(editor.undo().ok)
    }

    @Test fun `複数文書の一手は分割せず定義変更では全履歴を止める`() {
        val store = store(); val editor = WorkspaceSession(store)
        edit(editor, a, b)
        external(store, editor, b)
        assertFalse(editor.undo().ok)
        edit(editor, c)
        editor.invalidateExternalHistory(setOf(DocumentId("_schemas/item.yaml")))
        assertFalse(editor.undo().ok)
        assertTrue(editor.apply(WorkspaceTransaction(editor.version, ChangeIntent("新しい編集"), listOf(
            WorkspaceOperation.EditDocument(a, listOf(YamlOperation.SetScalar(listOf("title"), "fresh")))
        ))).ok)
        assertTrue(editor.undo().ok)
    }

    @Test fun `部分保存と書いた後に失敗した不明を分ける`() {
        for (afterWrite in listOf(false, true)) {
            val memory = store()
            val failing = object : DocumentStore by memory {
                override fun write(id: DocumentId, text: String) {
                    if (id == b && !afterWrite) error("保存前の失敗")
                    memory.write(id, text)
                    if (id == b) error("保存後の失敗")
                }
            }
            val session = WorkspaceSession(failing)
            val result = ChangeSetApplier.applyPrepared(session, ChangeSetApplier.prepare(session, changes("a.yaml", "b.yaml")))
            assertFalse(result.ok)
            assertEquals(ChangePersistence.WRITTEN, result.persistence[a])
            assertEquals(if (afterWrite) ChangePersistence.UNKNOWN else ChangePersistence.NOT_WRITTEN, result.persistence[b])
            assertTrue(result.results.single { it.file == "a.yaml" }.applied)
        }
    }

    @Test fun `確認後の競合は最初の保存より前に拒否する`() {
        val store = store(); val session = WorkspaceSession(store)
        val prepared = ChangeSetApplier.prepare(session, changes("a.yaml", "b.yaml"))
        store.write(b, "title: outside\n")
        val result = ChangeSetApplier.applyPrepared(session, prepared)
        assertFalse(result.ok)
        assertEquals(1, store.writeCount)
        assertEquals(0, session.historySize)
    }

    @Test fun `変更していない参照元のエラーも外部確定を止める`() {
        val store = InMemoryDocumentStore(mapOf(
            "_schemas/item.yaml" to """
                id: item
                match: '*.yaml'
                entity: {type: item, idPattern: 'IT-*', defines: true}
                root:
                  type: object
                  fields:
                    - {key: id, type: id}
                    - {key: next, type: ref}
            """.trimIndent(),
            "a.yaml" to "id: IT-1\nnext: IT-2\n", "b.yaml" to "id: IT-2\n",
        ))
        val session = WorkspaceSession(store)
        val prepared = ChangeSetApplier.prepare(session, ChangeSets.parse("changes: [{file: b.yaml, ops: [{op: set, path: id, value: IT-3}]}]"))
        assertFalse(prepared.valid)
        assertTrue(prepared.issues[a].orEmpty().any { it.source.kind == "unresolved" })
        assertFalse(ChangeSetApplier.applyPrepared(session, prepared).ok)
        assertEquals(0, store.writeCount)
    }

    @Test fun `自動値を確認時刻に固定し確定で増やし直さない`() {
        val store = InMemoryDocumentStore(mapOf(
            "_schemas/item.yaml" to """
                id: item
                match: '*.yaml'
                root:
                  type: object
                  fields:
                    - {key: title, type: string}
                    - {key: updated, type: date, auto: updated}
                    - {key: revision, type: int, auto: revision}
            """.trimIndent(), "a.yaml" to "title: A\nupdated: 2026-01-01\nrevision: 3\n",
        ))
        val session = WorkspaceSession(store)
        val prepared = ChangeSetApplier.prepare(session, changes("a.yaml"), LocalDateTime.of(2026, 10, 31, 12, 0))
        assertContains(prepared.documents.single().after, "2026-10-31")
        assertContains(prepared.documents.single().after, "revision: 4")
        assertTrue(ChangeSetApplier.applyPrepared(session, prepared).ok)
        assertEquals(prepared.documents.single().after, store.read(a)!!.text)
    }
}
