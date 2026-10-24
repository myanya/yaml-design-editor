package dev.toros.yde.app

import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class SaveConflictTest {
    private val dir = createTempDirectory("yde-save-conflict")
    private val a = dir.resolve("a.yaml")
    private val b = dir.resolve("b.yaml")

    @BeforeTest
    fun setup() {
        Files.writeString(a, "title: A\n")
        Files.writeString(b, "title: B\n")
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun state() = AppState().also { it.openWorkspace(dir) }
    private fun edit(doc: OpenDocument, text: String = "title: local\n") {
        doc.source.edit { replace(0, length, text) }
        doc.flushDraft()
    }
    private fun title(text: String) = ((YamlIO.parseFirst(text) as YNode.Map).entries["title"] as YNode.Scalar).value

    @Test
    fun `監視前で更新時刻が同じ外部変更も保存時に検出しUndoを残す`() {
        val state = state()
        state.openFile(a)
        val doc = state.openDoc!!
        edit(doc)
        val preview = state.undoPreview()
        val modified = Files.getLastModifiedTime(a)
        Files.writeString(a, "title: external\n")
        Files.setLastModifiedTime(a, modified)
        state.saveCurrent()
        assertEquals("external", title(Files.readString(a)))
        assertTrue(doc.dirty)
        assertTrue(doc.externalConflict)
        assertEquals(preview, state.undoPreview())
        state.keepLocalVersion()
        assertTrue(doc.dirty)
        state.saveCurrent()
        assertEquals("local", title(Files.readString(a)))
        assertFalse(doc.dirty)
        assertFalse(doc.externalConflict)
    }

    @Test
    fun `上書き許可の後に再度変更されると再び停止する`() {
        val state = state()
        state.openFile(a)
        val doc = state.openDoc!!
        edit(doc)
        Files.writeString(a, "title: external\n")
        state.saveCurrent()
        state.keepLocalVersion()
        Files.writeString(a, "title: newer\n")
        state.saveCurrent()
        assertEquals("newer", title(Files.readString(a)))
        assertTrue(doc.externalConflict)
        state.acceptDiskVersion()
        assertEquals("newer", title(doc.canonicalText()))
        assertFalse(doc.dirty)
        assertFalse(doc.externalConflict)
        edit(doc, "title: after\n")
        state.saveCurrent()
        assertEquals("after", title(Files.readString(a)))
    }

    @Test
    fun `未表示文書のUndo後の全保存は競合を残し他の文書を保存する`() {
        val state = state()
        state.bulkUpdate(setOf(a, b), "title", "changed").getOrThrow()
        state.undo()
        val redo = state.redoPreview()
        assertNull(state.docFor(a))
        Files.writeString(a, "title: external\n")
        state.saveAll()
        assertEquals("external", title(Files.readString(a)))
        assertEquals("B", title(Files.readString(b)))
        assertTrue(state.ensureDoc(a).externalConflict)
        assertTrue(state.ensureDoc(a).dirty)
        assertFalse(state.ensureDoc(b).dirty)
        assertEquals(1, state.dirtyDocCount())
        assertEquals(redo, state.redoPreview())
        assertTrue("a.yaml" in state.status)
        assertTrue("保存できなかった" in state.status)
    }

    @Test
    fun `壊れた下書きも外部変更を上書きせず許可後に保存できる`() {
        val state = state()
        state.openFile(a)
        val doc = state.openDoc!!
        edit(doc, "title: [local\n")
        Files.writeString(a, "title: external\n")
        state.saveCurrent()
        assertTrue(doc.draftDirty)
        assertTrue(doc.externalConflict)
        assertEquals("external", title(Files.readString(a)))
        state.keepLocalVersion()
        state.saveCurrent()
        assertEquals("title: [local\n", Files.readString(a))
        assertFalse(doc.draftDirty)
    }

    @Test
    fun `初期状態で解析不能な文書を修復しても外部変更を上書きしない`() {
        Files.writeString(a, "title: [\n")
        val state = state()
        state.openFile(a)
        val doc = state.openDoc!!
        Files.writeString(a, "title: external\n")
        edit(doc)
        state.saveCurrent()
        assertTrue(doc.externalConflict)
        assertEquals("external", title(Files.readString(a)))
        state.keepLocalVersion()
        state.saveCurrent()
        assertEquals("local", title(Files.readString(a)))
    }

    @Test
    fun `外部削除を保存で再作成せず許可後に再作成できる`() {
        val state = state()
        state.openFile(a)
        val doc = state.openDoc!!
        edit(doc)
        Files.delete(a)
        state.saveCurrent()
        assertFalse(Files.exists(a))
        assertTrue(doc.externalConflict)
        state.keepLocalVersion()
        state.saveCurrent()
        assertEquals("local", title(Files.readString(a)))
    }

    @Test
    fun `監視が競合を検出した後も保存は停止して履歴を保持する`() {
        val state = state()
        state.openFile(a)
        val doc = state.openDoc!!
        edit(doc)
        val preview = state.undoPreview()
        Files.writeString(a, "title: external\n")
        state.pollExternalChanges(eventHint = true)
        state.saveCurrent()
        assertEquals("external", title(Files.readString(a)))
        assertTrue(doc.externalConflict)
        assertEquals(preview, state.undoPreview())
    }

    @Test
    fun `別文書の通知が混在しても未表示のUndoと保存競合を失わない`() {
        val c = dir.resolve("c.yaml")
        Files.writeString(c, "title: C\n")
        val state = state()
        state.bulkUpdate(setOf(a, b), "title", "changed").getOrThrow()
        state.undo()
        val redo = state.redoPreview()
        Files.writeString(a, "title: external\n")
        Files.writeString(c, "title: external C\n")
        state.pollExternalChanges(eventHint = true)
        assertEquals(redo, state.redoPreview())
        state.saveAll()
        assertEquals("external", title(Files.readString(a)))
        assertEquals("B", title(Files.readString(b)))
        assertTrue(state.ensureDoc(a).externalConflict)
    }
}
