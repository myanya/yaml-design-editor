package dev.toros.yde.app

import dev.toros.yde.app.core.EditBuffer
import dev.toros.yde.yaml.model.YNode
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class HistoryCoalescingTest {
    private val dir = createTempDirectory("yde-history-coalescing")
    private val a = dir.resolve("a.yaml")
    private val b = dir.resolve("b.yaml")
    private var now = 0L

    @BeforeTest
    fun setup() {
        Files.writeString(a, "id: A\ntitle: 最初\n")
        Files.writeString(b, "id: B\ntitle: 別文書\n")
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun state() = AppState { now }.also { it.openWorkspace(dir); it.openFile(a); it.editorTab = 0 }
    private fun edit(doc: OpenDocument, text: String) {
        val node = (doc.root as YNode.Map).entries["title"] as YNode.Scalar
        node.value = text
        doc.markEdited(node)
    }
    private fun source(doc: OpenDocument, text: String) = doc.editSource { replace(0, length, "id: A\ntitle: $text\n") }
    private fun title(doc: OpenDocument) = ((doc.root as YNode.Map).entries["title"] as YNode.Scalar).value

    @Test
    fun `同じ画面の連続入力はまとめ同じタブや文書の再選択では区切らない`() {
        val s = state()
        val doc = s.openDoc!!
        edit(doc, "途中")
        now = 500
        s.editorTab = 0
        s.openFile(a)
        edit(doc, "最後")
        s.undo()
        assertEquals("最初", title(doc))
        s.redo()
        assertEquals("最後", title(doc))
    }

    @Test
    fun `入力を一秒より長く空けると別のまとまりになる`() {
        val s = state()
        val doc = s.openDoc!!
        edit(doc, "途中")
        now = 1001
        edit(doc, "最後")
        s.undo()
        assertEquals("途中", title(doc))
    }

    @Test
    fun `別のファイルを往復した後の入力だけを戻す`() {
        val s = state()
        val doc = s.openDoc!!
        edit(doc, "移動前")
        s.openFile(b)
        s.openFile(a)
        edit(doc, "移動後")
        s.undo()
        assertEquals("移動前", title(doc))
        s.undo()
        assertEquals("最初", title(doc))
    }

    @Test
    fun `差分タブを往復した後の入力だけを戻す`() {
        val s = state()
        val doc = s.openDoc!!
        edit(doc, "移動前")
        s.editorTab = 2
        s.editorTab = 0
        edit(doc, "移動後")
        s.undo()
        assertEquals("移動前", title(doc))
    }

    @Test
    fun `スペースを往復した後の入力だけを戻す`() {
        val s = state()
        val doc = s.openDoc!!
        edit(doc, "移動前")
        s.showSpace(UiSpace.RUNS)
        s.showSpace(UiSpace.EDIT)
        edit(doc, "移動後")
        s.undo()
        assertEquals("移動前", title(doc))
    }

    @Test
    fun `ビューの往復も同じセルへの連続入力を区切る`() {
        val s = state()
        val doc = s.openDoc!!
        s.showWorkspaceView(1)
        edit(doc, "移動前")
        s.showWorkspaceView(2)
        s.showWorkspaceView(1)
        edit(doc, "移動後")
        s.undo()
        assertEquals("移動前", title(doc))
        assertEquals(UiSpace.LEDGER, s.uiSpace)
        assertEquals(1, s.workspaceView)
    }

    @Test
    fun `戻ると進むによる往復も入力を区切り移動だけではRedoを失わない`() {
        val s = state()
        val doc = s.openDoc!!
        s.openFile(b)
        s.goBack()
        edit(doc, "移動前")
        s.goForward()
        s.goBack()
        edit(doc, "移動後")
        s.undo()
        assertEquals("移動前", title(doc))
        s.goForward()
        s.goBack()
        s.redo()
        assertEquals("移動後", title(doc))
    }

    @Test
    fun `解析待ちの入力は移動前に含め復帰後の入力とは分ける`() {
        val s = state()
        val doc = s.openDoc!!
        s.editorTab = 1
        source(doc, "途中")
        doc.flushDraft()
        source(doc, "移動前")
        val late = doc.sourceParseRequest()!!
        val parsed = EditBuffer.parse(late.text)
        s.openFile(b)
        assertEquals("移動前", title(doc))
        assertFalse(doc.applyParsed(late, parsed), "移動前に取り込んだ背景解析は二重反映しない")
        s.goBack()
        source(doc, "移動後")
        s.undo()
        assertEquals("移動前", title(doc))
        s.undo()
        assertEquals("最初", title(doc), "移動前の連続入力は一つにまとまる")
    }

    @Test
    fun `ソースのタブ切替は解析待ちを取り込んでから区切る`() {
        val s = state()
        val doc = s.openDoc!!
        s.editorTab = 1
        source(doc, "移動前")
        s.editorTab = 2
        assertEquals("移動前", title(doc))
        s.editorTab = 1
        source(doc, "移動後")
        s.undo()
        assertEquals("移動前", title(doc))
    }

    @Test
    fun `保存直後の入力を戻すと保存した値で未保存印が消える`() {
        val s = state()
        val doc = s.openDoc!!
        edit(doc, "保存する値")
        s.saveCurrent()
        edit(doc, "保存後")
        s.undo()
        assertEquals("保存する値", title(doc))
        assertFalse(doc.dirty)
        s.undo()
        assertEquals("最初", title(doc))
        assertTrue(doc.dirty, "保存より前の履歴も残る")
    }

    @Test
    fun `すべて保存は解析待ちを保存して次の入力と区切る`() {
        val s = state()
        val doc = s.openDoc!!
        s.editorTab = 1
        source(doc, "保存する値")
        s.saveAll()
        source(doc, "保存後")
        s.undo()
        assertEquals("保存する値", title(doc))
        assertFalse(doc.dirty)
        assertFalse(doc.draftDirty)
    }

    @Test
    fun `競合で保存を止めた場合もその後の入力は別に戻す`() {
        val s = state()
        val doc = s.openDoc!!
        edit(doc, "保存前")
        Files.writeString(a, "id: A\ntitle: 外部変更\n")
        s.saveCurrent()
        assertTrue(doc.externalConflict)
        edit(doc, "保存失敗後")
        s.undo()
        assertEquals("保存前", title(doc))
        assertTrue(doc.dirty)
        assertTrue(Files.readString(a).contains("外部変更"))
    }
}
