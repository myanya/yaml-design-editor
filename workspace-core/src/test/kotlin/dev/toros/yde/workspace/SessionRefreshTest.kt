package dev.toros.yde.workspace

import dev.toros.yde.yaml.DefaultYamlCodec
import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.*

class SessionRefreshTest {
    private val a = DocumentId("a.yaml")
    private val b = DocumentId("b.yaml")
    private fun store() = InMemoryDocumentStore(mapOf("a.yaml" to "title: A\n", "b.yaml" to "title: B\n"))
    private fun edit(s: WorkspaceSession, id: DocumentId, title: String) {
        assertTrue(s.apply(WorkspaceTransaction(s.version, ChangeIntent("変更"), listOf(
            WorkspaceOperation.ReplaceDocument(id, DefaultYamlCodec.parse(id, "title: $title\n"))
        ))).ok)
    }
    private fun text(s: WorkspaceSession, id: DocumentId) = DefaultYamlCodec.dump(s.copyDocument(id)!!)

    @Test fun `変更のない再読込でRedoと保存基準を保つ`() {
        val store = store()
        val s = WorkspaceSession(store)
        edit(s, a, "edited")
        assertTrue(s.save(setOf(a)).ok)
        assertTrue(s.undo().ok)
        val redo = s.peekRedo()
        val version = s.version
        assertTrue(s.refreshFrom(WorkspaceSession(store)).ok)
        assertEquals(version, s.version)
        assertEquals(redo, s.peekRedo())
        assertTrue(s.isDirty(a))
        assertContains(text(s, a), "A")
        assertTrue(s.redo().ok)
        assertFalse(s.isDirty(a))
    }

    @Test fun `無関係な変更と追加と削除を取り込み未保存編集とUndoを保つ`() {
        val store = store()
        val s = WorkspaceSession(store)
        edit(s, a, "local")
        val history = s.history
        store.write(b, "title: external\n")
        val c = DocumentId("c.yaml")
        store.write(c, "title: new\n")
        val writes = store.writeCount
        assertTrue(s.refreshFrom(WorkspaceSession(store)).ok)
        assertEquals(writes, store.writeCount)
        assertEquals(history, s.history)
        assertTrue(s.isDirty(a))
        assertContains(text(s, b), "external")
        assertTrue(s.hasDocument(c))
        store.delete(b)
        assertTrue(s.refreshFrom(WorkspaceSession(store)).ok)
        assertFalse(s.hasDocument(b))
        assertTrue(s.undo().ok)
        assertContains(text(s, a), "A")
        assertFalse(s.hasDocument(b))
    }

    @Test fun `保存済み対象の外部変更を取り込み無関係な履歴だけ戻せる`() {
        val store = store()
        val s = WorkspaceSession(store)
        edit(s, a, "saved")
        assertTrue(s.save(setOf(a)).ok)
        edit(s, b, "later")
        val version = s.version
        val history = s.history
        store.write(a, "title: external\n")
        store.write(DocumentId("new.yaml"), "title: new\n")
        val result = s.refreshFrom(WorkspaceSession(store))
        assertTrue(result.ok)
        assertContains(result.message.orEmpty(), "a.yaml")
        assertTrue(s.version > version)
        assertEquals(history, s.history)
        assertTrue(s.hasDocument(DocumentId("new.yaml")))
        assertContains(text(s, a), "external")
        assertTrue(s.undo().ok, "無関係な B の編集は戻せる")
        assertFalse(s.undo().ok, "取り込んだ A を古い履歴で上書きしない")
        assertContains(text(s, a), "external")
    }

    @Test fun `Redo対象や下書きだけの文書も外部変更から保護する`() {
        val store = store()
        val s = WorkspaceSession(store)
        edit(s, a, "edited")
        assertTrue(s.undo().ok)
        val redo = s.peekRedo()
        store.write(a, "title: external\n")
        assertTrue(s.refreshFrom(WorkspaceSession(store)).ok)
        assertEquals(redo, s.peekRedo())
        assertFalse(s.redo().ok)
        val clean = WorkspaceSession(store)
        store.delete(b)
        assertTrue(clean.refreshFrom(WorkspaceSession(store), setOf(b)).externalConflict)
        assertTrue(clean.hasDocument(b))
    }

    @Test fun `構成変更後も台帳外の編集と履歴を保ちUndoできる`() {
        val store = store()
        val s = WorkspaceSession(store)
        edit(s, a, "local")
        store.write(DocumentId(".yde/workspace.yaml"), "ledger: [tasks]\n")
        val disk = WorkspaceSession(store)
        assertTrue(s.refreshFrom(disk).ok)
        assertEquals(".yde", s.layout.configDir)
        assertTrue(s.isDirty(a))
        assertFalse(s.snapshot.documents.getValue(a).inLedger)
        assertTrue(s.undo().ok)
        assertContains(text(s, a), "A")
        val cleanStore = store()
        val clean = WorkspaceSession(cleanStore)
        cleanStore.write(DocumentId(".yde/workspace.yaml"), "ledger: [tasks]\n")
        assertTrue(clean.refreshFrom(WorkspaceSession(cleanStore)).ok)
        assertEquals(listOf("tasks"), clean.layout.ledger)
    }

    @Test fun `削除予約と未保存新規文書は変更のない再読込で失われない`() {
        val store = store()
        val s = WorkspaceSession(store)
        val c = DocumentId("c.yaml")
        assertTrue(s.apply(WorkspaceTransaction(s.version, ChangeIntent("作成と削除"), listOf(
            WorkspaceOperation.DeleteDocument(a),
            WorkspaceOperation.CreateDocument(c, DefaultYamlCodec.parse(c, "title: new\n")),
        ))).ok)
        assertTrue(s.refreshFrom(WorkspaceSession(store)).ok)
        assertFalse(s.hasDocument(a))
        assertTrue(s.isDirty(c))
        assertTrue(s.save(setOf(a, c)).ok)
        assertNull(store.read(a))
        assertNotNull(store.read(c))
        assertTrue(s.undo().ok)
        assertTrue(s.hasDocument(a))
    }

    @Test fun `一括履歴の一文書だけが競合しても全体を変更しない`() {
        val store = store()
        val s = WorkspaceSession(store)
        assertTrue(s.apply(WorkspaceTransaction(s.version, ChangeIntent("一括"), listOf(a, b).map {
            WorkspaceOperation.ReplaceDocument(it, DefaultYamlCodec.parse(it, "title: batch\n"))
        })).ok)
        assertTrue(s.save().ok)
        store.write(a, "title: external\n")
        assertFalse(s.undo().ok, "監視前も競合を検出する")
        assertTrue(s.refreshFrom(WorkspaceSession(store)).ok)
        val version = s.version
        assertFalse(s.undo().ok)
        assertEquals(version, s.version)
        assertContains(text(s, a), "external")
        assertContains(text(s, b), "batch")
        edit(s, b, "new")
        assertTrue(s.undo().ok, "新しい編集の履歴は使える")
        assertFalse(s.undo().ok)
    }

    @Test fun `未保存競合は上書き許可までUndoと保存を止めディスク採用後も古い履歴を止める`() {
        val store = store()
        val s = WorkspaceSession(store)
        edit(s, a, "local")
        store.write(a, "title: external\n")
        store.write(b, "title: external B\n")
        val result = s.refreshFrom(WorkspaceSession(store))
        assertTrue(result.ok)
        assertTrue(result.externalConflict)
        assertContains(text(s, a), "local")
        assertContains(text(s, b), "external B")
        assertFalse(s.undo().ok)
        assertFalse(s.save().ok)
        s.acknowledgeStoreText(a, store.read(a)?.text)
        assertTrue(s.undo().ok)
        assertTrue(s.redo().ok)
        s.acceptStoreText(a, store.read(a)?.text)
        assertFalse(s.undo().ok)
    }

    @Test fun `不正なスキーマを候補へ混ぜても現在値と履歴と保存基準は変わらない`() {
        val store = store()
        val s = WorkspaceSession(store)
        edit(s, a, "local")
        val version = s.version
        val derived = s.derived
        val history = s.history
        store.write(DocumentId("_schemas/task.yaml"), "root: [")
        store.write(b, "title: external\n")
        assertFalse(s.refreshFrom(WorkspaceSession(store)).ok)
        assertEquals(version, s.version)
        assertSame(derived, s.derived)
        assertEquals(history, s.history)
        assertContains(text(s, b), "B")
        assertEquals("title: B\n", s.expectedStoreText(b))
    }

    @Test fun `スキーマの追加と更新後も現在値を新しい型で検証し履歴を保持する`() {
        val store = store()
        val s = WorkspaceSession(store)
        edit(s, a, "local")
        val schema = DocumentId("_schemas/task.yaml")
        store.write(schema, "id: task\nmatch: '*.yaml'\nroot:\n  type: object\n  fields:\n    - {key: title, type: int}\n")
        assertTrue(s.refreshFrom(WorkspaceSession(store)).ok)
        assertEquals("task", s.snapshot.documents.getValue(a).schemaId)
        assertTrue(s.derived.structuralIssues.getValue(a).isNotEmpty())
        assertTrue(s.undo().ok)
        assertEquals("task", s.snapshot.documents.getValue(a).schemaId)
        assertTrue(s.redo().ok)
        store.write(schema, "id: task\nmatch: '*.yaml'\nroot:\n  type: object\n  fields:\n    - {key: title, type: string}\n")
        assertTrue(s.refreshFrom(WorkspaceSession(store)).ok)
        assertTrue(s.derived.structuralIssues[a].orEmpty().isEmpty())
    }

    @Test fun `削除予約の外部変更を取り込まず破棄まで復活や上書きを防ぐ`() {
        val store = store()
        val s = WorkspaceSession(store)
        assertTrue(s.apply(WorkspaceTransaction(s.version, ChangeIntent("削除"), listOf(WorkspaceOperation.DeleteDocument(a)))).ok)
        store.write(a, "title: external\n")
        assertTrue(s.refreshFrom(WorkspaceSession(store)).externalConflict)
        assertFalse(s.hasDocument(a))
        assertFalse(s.undo().ok)
        assertFalse(s.save().ok)
        assertEquals("title: external\n", store.read(a)?.text)
    }

    @Test fun `新構成が履歴の対象を扱えないときは取り込み全体を拒否する`() {
        val schema = DocumentId(".yde/schemas/task.yaml")
        val store = store().also { it.write(schema, "id: task\nmatch: '*.yaml'\nroot: {type: object}\n") }
        val s = WorkspaceSession(store)
        assertTrue(s.apply(WorkspaceTransaction(s.version, ChangeIntent("定義変更"), listOf(
            WorkspaceOperation.ReplaceDocument(schema, DefaultYamlCodec.parse(schema,
                "id: task\nmatch: '*.yaml'\nroot: {type: string}\n"))
        ))).ok)
        assertTrue(s.save().ok)
        val version = s.version
        val history = s.history
        val derived = s.derived
        store.delete(schema)
        store.write(b, "title: external\n")
        val result = s.refreshFrom(WorkspaceSession(store))
        assertFalse(result.ok)
        assertContains(result.message.orEmpty(), schema.value)
        assertEquals(version, s.version)
        assertEquals(history, s.history)
        assertSame(derived, s.derived)
        assertContains(text(s, b), "B")
        assertTrue(s.hasDocument(schema))
    }
}
