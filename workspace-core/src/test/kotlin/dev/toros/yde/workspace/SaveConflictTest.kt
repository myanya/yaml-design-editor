package dev.toros.yde.workspace

import dev.toros.yde.yaml.DefaultYamlCodec
import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.*

class SaveConflictTest {
    private val a = DocumentId("a.yaml")
    private val b = DocumentId("b.yaml")
    private fun store() = InMemoryDocumentStore(mapOf(a.value to "title: A\n", b.value to "title: B\n"))
    private fun edit(s: WorkspaceSession, id: DocumentId = a) {
        assertTrue(s.recordEdit(id, DefaultYamlCodec.parse(id, "title: local\n")).ok)
    }

    @Test
    fun `保存は外部の書式変更も拒否し履歴と未保存内容を保持する`() {
        val store = store()
        val s = WorkspaceSession(store)
        edit(s)
        val history = s.history
        val version = s.version
        store.write(a, "title: A # external\n")
        val result = s.save()
        assertEquals(setOf(a), result.conflicts)
        assertEquals("title: A # external\n", store.read(a)?.text)
        assertEquals(history, s.history)
        assertEquals(version, s.version)
        assertTrue(s.isDirty(a))
        assertTrue(s.undo().ok)
        assertTrue(s.redo().ok)
        assertFalse(s.save(force = true).ok, "force は競合回避の指定ではない")
    }

    @Test
    fun `全保存は競合文書を残して成功した文書だけを保存済みにする`() {
        val store = store()
        val s = WorkspaceSession(store)
        edit(s); edit(s, b)
        store.write(a, "title: external\n")
        val result = s.save()
        assertEquals(listOf(b), result.written)
        assertEquals(setOf(a), result.conflicts)
        assertTrue(s.isDirty(a))
        assertFalse(s.isDirty(b))
    }

    @Test
    fun `上書き許可は確認した版だけに効きその後の外部変更を拒否する`() {
        val store = store()
        val s = WorkspaceSession(store)
        edit(s)
        store.write(a, "title: external\n")
        s.acknowledgeStoreText(a, store.read(a)?.text)
        assertTrue(s.isDirty(a))
        store.write(a, "title: newer\n")
        assertFalse(s.save().ok)
        s.acknowledgeStoreText(a, store.read(a)?.text)
        assertTrue(s.save().ok)
        assertEquals("title: local\n", store.read(a)?.text)
        assertTrue(s.canUndo())
    }

    @Test
    fun `外部削除と作成先の衝突と削除対象の変更を保護する`() {
        val store = store()
        val s = WorkspaceSession(store)
        edit(s)
        store.delete(a)
        assertEquals(setOf(a), s.save().conflicts)
        assertNull(store.read(a))
        s.acknowledgeStoreText(a, null)
        assertTrue(s.save().ok)
        val c = DocumentId("c.yaml")
        assertTrue(s.apply(WorkspaceTransaction(s.version, ChangeIntent("作成"),
            listOf(WorkspaceOperation.CreateDocument(c, DefaultYamlCodec.parse(c, "title: local\n"))))).ok)
        store.write(c, "title: external\n")
        assertEquals(setOf(c), s.save().conflicts)
        assertTrue(s.apply(WorkspaceTransaction(s.version, ChangeIntent("削除"),
            listOf(WorkspaceOperation.DeleteDocument(b)))).ok)
        store.write(b, "title: external\n")
        assertEquals(setOf(b), s.save(setOf(b)).conflicts)
        assertEquals("title: external\n", store.read(b)?.text)
    }

    @Test
    fun `テキスト保存も解析不能な初期文書の外部変更を拒否する`() {
        val store = InMemoryDocumentStore(mapOf(a.value to "title: [\n"))
        val s = WorkspaceSession(store)
        store.write(a, "title: external\n")
        assertTrue(s.saveAsText(a, "title: [local\n").externalConflict)
        assertEquals("title: external\n", store.read(a)?.text)
        s.acknowledgeStoreText(a, store.read(a)?.text)
        assertTrue(s.saveAsText(a, "title: [local\n").ok)
    }

    @Test
    fun `保存前の読取失敗では書かず未保存のままにする`() {
        val backing = store()
        var unreadable = false
        val store = object : DocumentStore by backing {
            override fun read(id: DocumentId): StoredContent? {
                if (unreadable && id == a) error("読み取りできません")
                return backing.read(id)
            }
        }
        val s = WorkspaceSession(store)
        edit(s)
        unreadable = true
        assertFalse(s.save().ok)
        assertTrue(s.isDirty(a))
        assertEquals("title: A\n", backing.read(a)?.text)
    }

    @Test
    fun `ディスク版の採用は保存基準を揃え履歴を消さない`() {
        val store = store()
        val s = WorkspaceSession(store)
        edit(s)
        store.write(a, "title: external\n")
        s.acceptStoreText(a, store.read(a)?.text)
        assertFalse(s.isDirty(a))
        assertTrue(s.undo().ok)
        assertTrue(s.save().ok)
        assertEquals("title: A\n", store.read(a)?.text)
    }
}
