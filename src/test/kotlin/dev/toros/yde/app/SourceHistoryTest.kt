package dev.toros.yde.app

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.app.core.EditBuffer
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class SourceHistoryTest {
    private val dir = createTempDirectory("yde-source-history")
    private val first = dir.resolve("a.yaml")
    private val second = dir.resolve("b.yaml")

    @BeforeTest
    fun setup() {
        Files.writeString(first, "title: A\n")
        Files.writeString(second, "title: B\n")
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun state() = AppState().also { it.openWorkspace(dir); it.openFile(first) }
    private fun title(doc: OpenDocument) = ((doc.root as YNode.Map).entries["title"] as YNode.Scalar).value
    private fun input(doc: OpenDocument, text: String) {
        doc.editSource { replace(0, length, text) }
    }
    private fun edit(doc: OpenDocument, value: String) {
        (doc.root as YNode.Map).entries["title"] = YNode.Scalar(value)
        doc.markEdited()
    }

    @Test
    fun `最初の入力直後もUndoでき入力と木が戻りRedoで復元する`() {
        val state = state()
        val doc = state.openDoc!!
        input(doc, "title: 入力直後\n")
        assertTrue(state.canUndo())
        state.undoFromUi()
        assertEquals("A", title(doc))
        assertEquals("title: A\n", doc.source.text.toString())
        assertFalse(state.canUndo())
        state.redoFromUi()
        assertEquals("入力直後", title(doc))
        assertEquals("title: 入力直後\n", doc.source.text.toString())
    }

    @Test
    fun `同じ行の空白と値を変更してもUndoとRedoでソースの値が一致する`() {
        val state = state()
        val doc = state.openDoc!!
        input(doc, "title:    入力\n")
        state.undo()
        assertEquals("A", title(doc))
        assertEquals("A", (YamlIO.parseFirst(doc.source.text.toString()) as YNode.Map)
            .entries["title"].let { (it as YNode.Scalar).value })
        assertTrue(state.canRedo())
        repeat(3) {
            state.redo()
            assertSourceMatchesTree(doc)
            assertEquals("入力", title(doc))
            state.undo()
            assertSourceMatchesTree(doc)
            assertEquals("A", title(doc))
        }
    }

    private fun assertSourceMatchesTree(doc: OpenDocument) {
        assertEquals(YamlIO.parseAll(doc.canonicalText()).map(YamlIO::dumpPlain),
            YamlIO.parseAll(doc.source.text.toString()).map(YamlIO::dumpPlain))
        assertEquals(doc.source.text.toString(), doc.sourceApplied)
        assertEquals(doc.source.text.toString(), doc.sourceParsed)
        assertFalse(doc.hasPendingSourceInput)
    }

    @Test
    fun `複数行の意味を守るために書式を揃えた場合は通知する`() {
        Files.writeString(first, "title: |\n  C\n  B\n")
        val state = state()
        val doc = state.openDoc!!
        input(doc, "title: |\n    A\n    B\n")
        state.undo()
        assertSourceMatchesTree(doc)
        assertEquals("C\nB\n", title(doc))
        assertTrue(state.status.contains("書式調整"))
        assertTrue(state.status.contains("a.yaml"))
        assertTrue(state.canRedo())
    }

    @Test
    fun `履歴の準備後に増えた入力や壊れた下書きを書式として捨てない`() {
        val state = state()
        val doc = state.openDoc!!
        input(doc, "title: 入力中\n")
        edit(doc, "木の変更")
        assertEquals(1, doc.syncSourceFromTree(preferHistory = true).conflicts)
        assertEquals("title: 入力中\n", doc.source.text.toString())
        input(doc, "title: [\n")
        doc.flushDraft()
        edit(doc, "別の変更")
        assertEquals(1, doc.syncSourceFromTree(preferHistory = true).conflicts)
        assertTrue(doc.draftBroken)
        assertEquals("title: [\n", doc.source.text.toString())
    }

    @Test
    fun `Undoは隣接する変更のない行の空白とコメントとカーソルを保つ`() {
        Files.writeString(first, "title: A\nnote: 維持 # コメント\n")
        val state = state()
        val doc = state.openDoc!!
        input(doc, "title:    入力\nnote:    維持 # コメント\n")
        val caret = doc.source.text.toString().indexOf("維持")
        doc.editSource { placeCursorBeforeCharAt(caret) }
        state.undo()
        assertSourceMatchesTree(doc)
        assertTrue(doc.source.text.toString().contains("note:    維持 # コメント"))
        assertEquals(doc.source.text.toString().indexOf("維持"), doc.source.selection.start)
        state.redo()
        assertSourceMatchesTree(doc)
        assertTrue(doc.source.text.toString().contains("note:    維持 # コメント"))
    }

    @Test
    fun `引用符と複数行と複数文書でも繰り返すUndoとRedoの値が一致する`() {
        val state = state()
        val doc = state.openDoc!!
        val inputs = listOf(
            "title:    '入力' # 引用符\n",
            "title: |-\n    一行目\n    二行目\n",
            "title:    入力\n---\nitems: [1, 2, 3]\n",
            "title:    入力",
        )
        for (text in inputs) {
            input(doc, text)
            doc.flushDraft()
            val expected = doc.canonicalText()
            repeat(2) {
                state.undo()
                assertSourceMatchesTree(doc)
                assertEquals("A", title(doc))
                state.redo()
                assertSourceMatchesTree(doc)
                assertEquals(expected, doc.canonicalText())
            }
            state.undo()
        }
    }

    @Test
    fun `別文書の履歴があっても直前のソース入力を先に戻し通知も一致する`() {
        val state = state()
        val a = state.openDoc!!
        val b = state.ensureDoc(second)
        edit(b, "Bの編集")
        input(a, "title: Aの入力\n")
        state.undo()
        assertEquals("Bの編集", title(b))
        assertEquals("A", title(a))
        assertEquals(listOf(first), state.historyNotice!!.files)
        state.undo()
        assertEquals("B", title(b))
    }

    @Test
    fun `Redo前に新しいソース入力があれば以前のRedoは破棄される`() {
        val state = state()
        val doc = state.openDoc!!
        edit(doc, "過去の編集")
        state.undo()
        input(doc, "title: 新しい入力\n")
        assertFalse(state.canRedo())
        state.redo()
        assertEquals("新しい入力", title(doc))
        assertFalse(state.canRedo())
        state.undo()
        assertEquals("A", title(doc))
    }

    @Test
    fun `ファイル配置の履歴よりも新しい入力を戻しフォルダーは残す`() {
        val state = state()
        val doc = state.openDoc!!
        state.createDirectory(dir, "new").getOrThrow()
        input(doc, "title: フォルダー作成後\n")
        state.undoFromUi()
        assertFalse(state.fileHistoryBusy)
        assertTrue(Files.isDirectory(dir.resolve("new")))
        assertEquals("A", title(doc))
    }

    @Test
    fun `確認後に別の編集が入ったら古い確認で違う履歴を戻さない`() {
        val state = state()
        val a = state.openDoc!!
        val b = state.ensureDoc(second)
        edit(a, "確認前")
        input(a, "title: [\n")
        state.undo()
        assertNotNull(state.pendingHistory)
        edit(b, "確認中に別の変更")
        state.confirmPendingHistory()
        assertEquals("確認中に別の変更", title(b))
        assertEquals("title: [\n", a.source.text.toString())
        assertEquals("確認前", title(a))
    }

    @Test
    fun `壊れた下書きを確認して戻した後もRedoが使える`() {
        val state = state()
        val doc = state.openDoc!!
        edit(doc, "有効な編集")
        input(doc, "title: [\n")
        state.undo()
        assertNotNull(state.pendingHistory)
        assertEquals("title: [\n", doc.source.text.toString())
        state.confirmPendingHistory()
        assertEquals("A", title(doc))
        assertEquals("title: A\n", doc.source.text.toString())
        assertTrue(state.canRedo())
        state.redo()
        assertEquals("有効な編集", title(doc))
    }

    @Test
    fun `複数文書の入力待ちはキャッシュ順ではなく入力した順に履歴へ入る`() {
        val state = state()
        val a = state.openDoc!!
        val b = state.ensureDoc(second)
        input(b, "title: Bの入力\n")
        input(a, "title: Aの入力\n")
        assertTrue(state.undoPreview()!!.contains("a.yaml"))
        state.undo()
        assertEquals("A", title(a))
        assertEquals("Bの入力", title(b))
        state.undo()
        assertEquals("B", title(b))
        state.redo()
        assertEquals("Bの入力", title(b))
        state.redo()
        assertEquals("Aの入力", title(a))
    }

    @Test
    fun `Undo前に開始した解析が遅れて完了しても内容とRedoを壊さない`() {
        val state = state()
        val doc = state.openDoc!!
        input(doc, "title: 入力\n")
        val request = doc.sourceParseRequest()!!
        val parsed = EditBuffer.parse(request.text)
        state.undo()
        assertFalse(doc.applyParsed(request, parsed))
        assertEquals("A", title(doc))
        assertEquals("title: A\n", doc.source.text.toString())
        assertTrue(state.canRedo())
        state.redo()
        assertEquals("入力", title(doc))
    }

    @Test
    fun `同じ文字列に入力し直しても途中の入力より前の解析結果は採用しない`() {
        val state = state()
        val doc = state.openDoc!!
        input(doc, "title: X\n")
        val old = doc.sourceParseRequest()!!
        input(doc, "title: Y\n")
        input(doc, old.text)
        assertFalse(doc.applyParsed(old, EditBuffer.parse(old.text)))
        assertEquals("A", title(doc))
        doc.flushDraft()
        assertEquals("X", title(doc))
    }

    @Test
    fun `別文書や古い木や破棄前の解析結果を採用しない`() {
        val state = state()
        val doc = state.openDoc!!
        val other = state.ensureDoc(second)
        input(doc, "title: 入力\n")
        val request = doc.sourceParseRequest()!!
        assertFalse(other.applyParsed(request, EditBuffer.parse(request.text)))
        edit(doc, "木の変更")
        assertFalse(doc.applyParsed(request, EditBuffer.parse(request.text)))
        val next = doc.sourceParseRequest()!!
        doc.discardDraft()
        assertFalse(doc.applyParsed(next, EditBuffer.parse(next.text)))
        assertEquals("木の変更", title(doc))
    }

    @Test
    fun `同じ入力の解析は一度だけ採用し古いエラーも反映しない`() {
        val state = state()
        val doc = state.openDoc!!
        input(doc, "title: [\n")
        val broken = doc.sourceParseRequest()!!
        input(doc, "title: 修復\n")
        val valid = doc.sourceParseRequest()!!
        assertTrue(doc.applyParsed(valid, EditBuffer.parse(valid.text)))
        assertFalse(doc.applyParsed(valid, EditBuffer.parse(valid.text)))
        assertFalse(doc.applyParsed(broken, EditBuffer.parse(broken.text)))
        assertFalse(doc.draftBroken)
        state.undo()
        assertEquals("A", title(doc))
        assertFalse(state.canUndo())
    }

    @Test
    fun `確認後に下書きが変わったら同じ履歴でも捨てずに中止する`() {
        val state = state()
        val doc = state.openDoc!!
        edit(doc, "確認前")
        input(doc, "title: [\n")
        state.undo()
        assertNotNull(state.pendingHistory)
        input(doc, "title: [さらに入力\n")
        state.confirmPendingHistory()
        assertEquals("確認前", title(doc))
        assertEquals("title: [さらに入力\n", doc.source.text.toString())
        assertTrue(state.status.contains("中止"))
        state.undo()
        assertNotNull(state.pendingHistory)
        state.confirmPendingHistory()
        assertEquals("A", title(doc))
    }

    @Test
    fun `構文エラーだけの初回入力には履歴を作らず下書きを残す`() {
        val state = state()
        val doc = state.openDoc!!
        input(doc, "title: [\n")
        state.undo()
        assertEquals("A", title(doc))
        assertEquals("title: [\n", doc.source.text.toString())
        assertTrue(doc.draftBroken)
        assertFalse(state.canUndo())
        assertFalse(state.canRedo())
    }
}
