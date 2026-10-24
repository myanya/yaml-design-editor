package dev.toros.yde.app

import androidx.compose.ui.text.TextRange
import dev.toros.yde.yaml.model.YNode
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ReloadHistoryTest {
    private val dir = createTempDirectory("yde-reload-history")
    private val a = dir.resolve("a.yaml")
    private val b = dir.resolve("b.yaml")
    @BeforeTest fun setup() {
        Files.writeString(a, "title: A\n")
        Files.writeString(b, "title: B\n")
    }
    @AfterTest fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
    private fun state() = AppState().also { it.openWorkspace(dir); it.openFile(a) }
    private fun edit(doc: OpenDocument, value: String) {
        (doc.root as YNode.Map).entries["title"] = YNode.Scalar(value)
        doc.markEdited()
    }
    private fun title(doc: OpenDocument) = ((doc.root as YNode.Map).entries["title"] as YNode.Scalar).value

    @Test fun `再読込後も保存済み編集を元の文書でUndoしRedoできる`() {
        val s = state()
        edit(s.openDoc!!, "saved")
        s.saveCurrent()
        s.openFile(b)
        val preview = s.undoPreview()
        s.reload()
        assertEquals(preview, s.undoPreview())
        assertTrue(s.canUndo())
        s.undo()
        assertEquals(a, s.openDoc?.file)
        assertEquals("A", title(s.openDoc!!))
        assertTrue(s.openDoc!!.dirty)
        s.reload()
        assertTrue(s.canRedo())
        s.redo()
        assertEquals("saved", title(s.openDoc!!))
        assertFalse(s.hasDirtyDocs())
    }

    @Test fun `反映待ちと壊れたソースとカーソルを再読込で失わない`() {
        val s = state()
        val doc = s.openDoc!!
        doc.source.edit { replace(0, length, "title: pending\n"); selection = TextRange(8) }
        val cursor = doc.source.selection
        s.reload()
        assertEquals("title: pending\n", doc.source.text.toString())
        assertEquals(cursor, doc.source.selection)
        assertTrue(doc.hasPendingSourceInput)
        s.undo()
        assertEquals("A", title(doc))
        doc.source.edit { replace(0, length, "title: [") }
        doc.flushDraft()
        val error = doc.sourceError
        s.reload()
        assertEquals("title: [", doc.source.text.toString())
        assertEquals(error, doc.sourceError)
        assertTrue(doc.draftDirty)
    }

    @Test fun `無関係な文書の外部変更を再読込しても未保存編集と履歴を保持する`() {
        val s = state()
        val doc = s.openDoc!!
        edit(doc, "local")
        s.ensureDoc(b)
        val modified = Files.getLastModifiedTime(b)
        Files.writeString(b, "title: external\n")
        Files.setLastModifiedTime(b, modified)
        s.reload()
        assertEquals("local", title(doc))
        assertEquals("external", title(s.docFor(b)!!))
        assertTrue(doc.dirty)
        assertTrue(s.canUndo())
        s.undo()
        assertEquals("A", title(doc))
        assertEquals("external", title(s.docFor(b)!!))
    }

    @Test fun `自動監視でも保存済みのUndo履歴を無関係な変更で消さない`() {
        val s = state()
        edit(s.openDoc!!, "saved")
        s.saveCurrent()
        Files.writeString(b, "title: external\n")
        s.noteFileEvents(setOf(b))
        assertTrue(s.canUndo())
        s.undo()
        assertEquals("A", title(s.openDoc!!))
        assertEquals("external", title(s.ensureDoc(b)))
    }

    @Test fun `未保存対象の外部変更では編集を保持して競合を示す`() {
        val s = state()
        val doc = s.openDoc!!
        edit(doc, "local")
        val preview = s.undoPreview()
        Files.writeString(a, "title: external\n")
        s.reload()
        assertContains(s.status, "未保存の競合")
        assertEquals(preview, s.undoPreview())
        assertEquals("local", title(doc))
        assertTrue(doc.externalConflict)
        assertTrue(s.canUndo())
        s.saveCurrent()
        assertEquals("title: external\n", Files.readString(a))
        assertTrue(doc.dirty)
        s.undo()
        assertContains(s.status, "外部変更と競合")
        assertEquals("local", title(doc))
        s.keepLocalVersion()
        s.undo()
        assertEquals("A", title(doc))
    }

    @Test fun `未表示文書のUndo状態と一覧への復帰先を再読込で失わない`() {
        val s = AppState().also { it.openWorkspace(dir); it.showWorkspaceView(1) }
        s.bulkUpdate(setOf(a, b), "title", "changed").getOrThrow()
        s.undo()
        assertNull(s.docFor(a))
        assertEquals(2, s.dirtyDocCount())
        s.reload()
        assertNull(s.docFor(a))
        assertEquals(2, s.dirtyDocCount())
        assertTrue(s.canRedo())
        s.openFile(b)
        s.redo()
        assertEquals(UiSpace.LEDGER, s.uiSpace)
        assertEquals(1, s.workspaceView)
        assertFalse(s.hasDirtyDocs())
        s.undo()
        s.saveAll()
        assertEquals("A", title(s.ensureDoc(a)))
        assertContains(Files.readString(a), "A")
    }

    @Test fun `スキーマを追加しても画面と編集とUndoを保つ`() {
        val s = state()
        val doc = s.openDoc!!
        edit(doc, "local")
        val schemaDir = Files.createDirectories(dir.resolve("_schemas"))
        Files.writeString(schemaDir.resolve("task.yaml"), "id: task\nmatch: '*.yaml'\nroot: {type: object}\n")
        s.reload()
        assertSame(doc, s.openDoc)
        assertEquals("local", title(doc))
        assertEquals("task", s.schemaFor(a)?.id)
        assertTrue(s.canUndo())
        s.undo()
        assertEquals("A", title(doc))
    }

    @Test fun `破棄確認のキャンセルと確認中の編集では何も破棄しない`() {
        val s = state()
        val doc = s.openDoc!!
        edit(doc, "local")
        s.requestDiscardAndReload()
        assertEquals(listOf("a.yaml"), s.pendingReload?.files)
        assertEquals(1, s.pendingReload?.historyCount)
        s.cancelDiscardAndReload()
        s.confirmDiscardAndReload()
        assertEquals("local", title(doc))
        assertTrue(s.canUndo())
        s.requestDiscardAndReload()
        doc.source.edit { replace(0, length, "title: [") }
        s.confirmDiscardAndReload()
        assertContains(s.status, "確認中")
        assertEquals("title: [", doc.source.text.toString())
        assertSame(doc, s.openDoc)
    }

    @Test fun `破棄して開き直すと下書きとUndoとRedoを失いディスクは変更しない`() {
        val s = state()
        edit(s.openDoc!!, "saved")
        s.saveCurrent()
        s.undo()
        val doc = s.openDoc!!
        doc.source.edit { replace(0, length, "title: [") }
        s.requestDiscardAndReload()
        assertTrue(s.pendingReload!!.historyCount > 0)
        s.confirmDiscardAndReload()
        assertNotSame(doc, s.openDoc)
        assertEquals("saved", title(s.openDoc!!))
        assertFalse(s.hasDirtyDocs())
        assertFalse(s.canUndo())
        assertFalse(s.canRedo())
        assertContains(Files.readString(a), "saved")
        edit(s.openDoc!!, "after")
        s.undo()
        assertEquals("saved", title(s.openDoc!!))
    }

    @Test fun `開き直しの読込に失敗しても確認済みの編集と履歴を失わない`() {
        val s = state()
        val doc = s.openDoc!!
        edit(doc, "local")
        s.requestDiscardAndReload()
        // 一時ワークスペースのルートを一時的に通常ファイルにし、走査の失敗を再現する。
        val backup = dir.resolveSibling(dir.fileName.toString() + "-backup")
        Files.move(dir, backup)
        try {
            Files.writeString(dir, "workspace unavailable")
            s.confirmDiscardAndReload()
            assertSame(doc, s.openDoc)
            assertEquals("local", title(doc))
            assertTrue(s.canUndo())
            assertContains(s.status, "失敗")
        } finally {
            Files.deleteIfExists(dir)
            Files.move(backup, dir)
        }
    }

    @Test fun `保存済み対象の外部更新後は古い履歴を止め新しい編集を戻せる`() {
        val s = state()
        edit(s.openDoc!!, "saved")
        s.saveCurrent()
        Files.writeString(a, "title: external\n")
        s.reload()
        assertEquals("external", title(s.openDoc!!))
        s.undo()
        assertContains(s.status, "外部変更と競合")
        assertEquals("external", title(s.openDoc!!))
        edit(s.openDoc!!, "new")
        s.undo()
        assertEquals("external", title(s.openDoc!!))
    }

    @Test fun `競合するUndoは壊れた下書きを捨てる確認へ進めない`() {
        val s = state()
        val doc = s.openDoc!!
        edit(doc, "local")
        doc.source.edit { replace(0, length, "title: [") }
        Files.writeString(a, "title: external\n")
        s.undo()
        assertContains(s.status, "外部変更と競合")
        assertNull(s.pendingHistory)
        assertEquals("title: [", doc.source.text.toString())
        assertEquals("local", title(doc))
        assertTrue(s.canUndo())
    }

    @Test fun `解析待ちだけの文書も破棄確認の対象に含める`() {
        val s = state()
        val doc = s.openDoc!!
        doc.source.edit { replace(0, length, "title: 入力直後") }
        s.requestDiscardAndReload()
        assertEquals(listOf("a.yaml"), s.pendingReload?.files)
        assertEquals(0, s.pendingReload?.historyCount)
        s.cancelDiscardAndReload()
        assertEquals("title: 入力直後", doc.source.text.toString())
        assertFalse(s.canRedo())
    }
}
