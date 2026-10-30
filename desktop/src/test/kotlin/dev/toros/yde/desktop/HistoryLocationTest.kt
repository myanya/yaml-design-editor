package dev.toros.yde.desktop

import dev.toros.yde.yaml.model.YNode
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class HistoryLocationTest {
    private val dir = createTempDirectory("yde-history-location")
    private val a = dir.resolve("a.yaml")
    private val b = dir.resolve("b.yaml")

    @BeforeTest
    fun setup() {
        Files.writeString(a, "id: A\ntitle: 最初\n")
        Files.writeString(b, "id: B\ntitle: 二番目\n")
        Files.createDirectories(dir.resolve("_views"))
        Files.writeString(dir.resolve("_views/tasks.yaml"), "id: tasks\nname: 一覧\nrows: {idPattern: '*'}\ncolumns:\n  - {label: タイトル, path: title, editable: true}\n")
        val catalog = dev.toros.yde.app.core.Views.load(dir)
        assertNotNull(catalog.view("tasks"), catalog.errors.joinToString())
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun state() = AppState().also { it.openWorkspace(dir) }
    private fun edit(doc: OpenDocument, text: String, key: Any? = null) {
        (doc.root as YNode.Map).entries["title"] = YNode.Scalar(text)
        doc.markEdited(key)
    }
    private fun title(doc: OpenDocument) = ((doc.root as YNode.Map).entries["title"] as YNode.Scalar).value

    @Test
    fun `UndoとRedoは元のファイルとタブへ戻り移動前の場所へも戻れる`() {
        val s = state()
        s.openFile(a)
        s.editorTab = EditorTab.FORM
        edit(s.openDoc!!, "編集後")
        s.openFile(b)
        s.undo()
        assertEquals(a, s.openDoc!!.file)
        assertEquals(EditorTab.FORM, s.editorTab)
        assertEquals("最初", title(s.openDoc!!))
        s.goBack()
        assertEquals(b, s.openDoc!!.file)
        s.redo()
        assertEquals(a, s.openDoc!!.file)
        assertEquals("編集後", title(s.openDoc!!))
        assertEquals("id: A\ntitle: 最初\n", Files.readString(a), "画面移動では保存しない")
    }

    @Test
    fun `ビューのセル編集は原本を開かず元のビューへ戻る`() {
        val s = state()
        s.openFile(b)
        s.openViewGrid("tasks")
        edit(s.ensureDoc(a), "セルで編集")
        s.openFile(b)
        s.undo()
        assertEquals(UiSpace.LEDGER, s.uiSpace)
        assertEquals(4, s.workspaceView)
        assertEquals("tasks", s.viewGridId)
        assertEquals(b, s.openDoc!!.file, "下に保持する文書を原本へ切り替えない")
        assertEquals("最初", title(s.ensureDoc(a)))
        s.openFile(b)
        s.redo()
        assertEquals(UiSpace.LEDGER, s.uiSpace)
        assertEquals("tasks", s.viewGridId)
        assertEquals("セルで編集", title(s.ensureDoc(a)))
    }

    @Test
    fun `一括変更のUndoは元の一覧へ戻し全対象を戻す`() {
        val s = state()
        s.showWorkspaceView(1)
        s.bulkUpdate(setOf(a, b), "title", "一括変更").getOrThrow()
        s.openFile(b)
        s.undo()
        assertEquals(UiSpace.LEDGER, s.uiSpace)
        assertEquals(1, s.workspaceView)
        assertEquals("最初", title(s.ensureDoc(a)))
        assertEquals("二番目", title(s.ensureDoc(b)))
        assertEquals(2, s.dirtyDocCount())
    }

    @Test
    fun `ソースの反映前に別画面へ移動してもソースタブへ戻る`() {
        val s = state()
        s.openFile(a)
        s.editorTab = EditorTab.SOURCE
        s.openDoc!!.editSource { replace(0, length, "id: A\ntitle: ソース編集\n") }
        s.openViewGrid("tasks")
        s.undo()
        assertEquals(UiSpace.EDIT, s.uiSpace)
        assertEquals(a, s.openDoc!!.file)
        assertEquals(EditorTab.SOURCE, s.editorTab)
        assertEquals("最初", title(s.openDoc!!))
        s.openFile(b)
        s.redo()
        assertEquals(a, s.openDoc!!.file)
        assertEquals(EditorTab.SOURCE, s.editorTab)
        assertEquals("ソース編集", title(s.openDoc!!))
    }

    @Test
    fun `同じ画面のUndoは選択位置や戻る履歴を追加しない`() {
        val s = state()
        s.openFile(a)
        val doc = s.openDoc!!
        edit(doc, "変更")
        assertFalse(s.canGoBack)
        s.undo()
        assertSame(doc, s.openDoc)
        assertFalse(s.canGoBack)
        assertFalse(s.historyNotice!!.returnedToOrigin)
    }

    @Test
    fun `同じ文書と合体キーでも別画面での編集を分ける`() {
        val s = state()
        s.openFile(a)
        val doc = s.openDoc!!
        edit(doc, "フォーム", "same-field")
        s.openViewGrid("tasks")
        edit(doc, "ビュー", "same-field")
        s.openFile(b)
        s.undo()
        assertEquals("フォーム", title(doc))
        assertEquals(UiSpace.LEDGER, s.uiSpace)
        s.undo()
        assertEquals("最初", title(doc))
        assertEquals(UiSpace.EDIT, s.uiSpace)
        assertEquals(a, s.openDoc!!.file)
    }

    @Test
    fun `元のビューが消えたら対象文書を開く`() {
        val s = state()
        s.openViewGrid("tasks")
        edit(s.ensureDoc(a), "変更")
        s.openFile(b)
        Files.delete(dir.resolve("_views/tasks.yaml"))
        s.undo()
        assertEquals(UiSpace.EDIT, s.uiSpace)
        assertEquals(a, s.openDoc!!.file)
    }

    @Test
    fun `履歴が実行されない確認待ちでは画面を移動しない`() {
        val s = state()
        s.openFile(a)
        val doc = s.openDoc!!
        edit(doc, "変更")
        doc.editSource { replace(0, length, "title: [\n") }
        doc.flushDraft()
        s.openFile(b)
        s.undo()
        assertNotNull(s.pendingHistory)
        assertEquals(b, s.openDoc!!.file)
        s.pendingHistory = null
        assertEquals(b, s.openDoc!!.file)
    }

    @Test
    fun `既定で表示されたビューも編集元として記憶する`() {
        val s = state()
        s.openViewGrid()
        edit(s.ensureDoc(a), "既定ビューから編集")
        s.openFile(b)
        s.undo()
        assertEquals(UiSpace.LEDGER, s.uiSpace)
        assertEquals("tasks", s.viewGridId)
    }

    @Test
    fun `セッションを開き直した後の履歴に古い画面情報を使わない`() {
        val s = state()
        s.openViewGrid("tasks")
        edit(s.ensureDoc(a), "古い編集")
        s.openWorkspace(dir)
        s.openFile(a)
        edit(s.openDoc!!, "新しい編集")
        s.openFile(b)
        s.undo()
        assertEquals(UiSpace.EDIT, s.uiSpace)
        assertEquals(a, s.openDoc!!.file)
    }
}
