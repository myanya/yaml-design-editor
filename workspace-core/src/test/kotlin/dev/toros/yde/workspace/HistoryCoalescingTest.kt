package dev.toros.yde.workspace

import dev.toros.yde.yaml.DefaultYamlCodec
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import kotlin.test.*

class HistoryCoalescingTest {
    private val id = DocumentId("a.yaml")
    private fun store() = InMemoryDocumentStore(mapOf(id.value to "title: 最初\n"))
    private fun edit(s: WorkspaceSession, value: String, time: Long = 0) {
        assertTrue(s.recordEdit(id, DefaultYamlCodec.parse(id, "title: $value\n"), "title", time).ok)
    }
    private fun title(s: WorkspaceSession) =
        ((s.snapshot.documents.getValue(id).document.documents.first() as YNode.Map).entries["title"] as YNode.Scalar).value

    @Test
    fun `明示的な区切りは履歴を増やさず同じキーの入力を分ける`() {
        val s = WorkspaceSession(store())
        edit(s, "途中")
        edit(s, "区切り前", 100)
        val version = s.version
        s.endEditGroup()
        s.endEditGroup()
        assertEquals(version, s.version)
        assertEquals(1, s.history.size)
        edit(s, "区切り後", 200)
        assertTrue(s.undo().ok)
        assertEquals("区切り前", title(s))
        assertTrue(s.undo().ok)
        assertEquals("最初", title(s))
        s.endEditGroup()
        assertFalse(s.isDirty(id))
        assertTrue(s.redo().ok, "区切りだけでは Redo を失わない")
        assertEquals("区切り前", title(s))
    }

    @Test
    fun `保存を挟むと同じキーの入力でも保存した状態まで戻せる`() {
        val store = store()
        val s = WorkspaceSession(store)
        edit(s, "保存")
        assertTrue(s.save().ok)
        edit(s, "保存後", 100)
        assertEquals(2, s.history.size)
        assertTrue(s.undo().ok)
        assertEquals("保存", title(s))
        assertFalse(s.isDirty(id))
        assertTrue(s.undo().ok)
        assertEquals("最初", title(s))
        assertTrue(s.isDirty(id))
        assertTrue(store.read(id)!!.text.contains("保存"), "Undo は保存済み内容を変更しない")
    }

    @Test
    fun `保存が競合した場合も入力を区切り未保存の履歴を保つ`() {
        val store = store()
        val s = WorkspaceSession(store)
        edit(s, "保存前")
        store.write(id, "title: 外部変更\n")
        assertFalse(s.save().ok)
        edit(s, "保存失敗後", 100)
        assertTrue(s.undo().ok)
        assertEquals("保存前", title(s))
        assertTrue(s.isDirty(id))
        assertEquals("title: 外部変更\n", store.read(id)!!.text)
    }
}
