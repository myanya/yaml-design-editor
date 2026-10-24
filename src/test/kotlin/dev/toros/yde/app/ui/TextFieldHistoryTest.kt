package dev.toros.yde.app.ui

import androidx.compose.ui.input.key.Key
import dev.toros.yde.app.AppState
import dev.toros.yde.app.OpenDocument
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.yaml.model.YNode
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class TextFieldHistoryTest {
    @Test
    fun `取り消しと両方のやり直しキーを判定する`() {
        assertEquals(HistoryShortcut.UNDO, historyShortcut(Key.Z, true, false, false, false))
        assertEquals(HistoryShortcut.REDO, historyShortcut(Key.Z, true, true, false, false))
        assertEquals(HistoryShortcut.REDO, historyShortcut(Key.Y, true, false, false, false))
        assertNull(historyShortcut(Key.Z, false, false, false, false))
        assertNull(historyShortcut(Key.Z, true, false, true, false))
        assertNull(historyShortcut(Key.Z, true, false, false, true))
        assertNull(historyShortcut(Key.S, true, false, false, false))
    }

    @Test
    fun `文書履歴が空でもキーを消費して欄の古い履歴に戻らない`() {
        val handler = TextFieldHistory()
        val actions = mutableListOf<HistoryShortcut>()
        assertTrue(handler.preview(HistoryShortcut.UNDO, true, false) { actions.add(it) })
        assertEquals(listOf(HistoryShortcut.UNDO), actions)
        assertFalse(handler.preview(HistoryShortcut.UNDO, false, false) { actions.add(it) })
        assertEquals(1, actions.size)
    }

    @Test
    fun `検索や追加前の入力は欄の履歴へ渡す`() {
        val handler = TextFieldHistory()
        assertFalse(handler.preview(HistoryShortcut.UNDO, true, false, null))
        assertFalse(handler.blocksFallback)
        assertFalse(handler.preview(HistoryShortcut.REDO, true, false, null))
    }

    @Test
    fun `変換中のキーはIMEへ渡し未消費でもウィンドウの履歴へ伝えない`() {
        val handler = TextFieldHistory()
        var calls = 0
        val action: (HistoryShortcut) -> Unit = { calls++ }
        assertFalse(handler.preview(HistoryShortcut.UNDO, true, true, action))
        assertTrue(handler.blocksFallback)
        // 同じ一押しで変換が終了しても、キーリピートで文書を戻さない。
        assertFalse(handler.preview(HistoryShortcut.UNDO, true, false, action))
        assertEquals(0, calls)
        handler.preview(null, false, false, action)
        assertTrue(handler.preview(HistoryShortcut.UNDO, true, false, action))
        assertEquals(1, calls)
    }

    @Test
    fun `フォーカス離脱で変換中の一押しを解放する`() {
        val handler = TextFieldHistory()
        handler.preview(HistoryShortcut.UNDO, true, true) { fail("変換中は戻さない") }
        handler.reset()
        assertTrue(handler.preview(HistoryShortcut.REDO, true, false) {})
        assertFalse(handler.blocksFallback)
    }

    private fun withWorkspace(block: (AppState, OpenDocument, OpenDocument) -> Unit) {
        val dir = createTempDirectory("yde-form-history")
        try {
            val first = dir.resolve("a.yaml")
            val second = dir.resolve("b.yaml")
            Files.writeString(first, "title: A\n")
            Files.writeString(second, "title: B\n")
            val state = AppState().also { it.openWorkspace(dir); it.openFile(first) }
            block(state, state.openDoc!!, state.ensureDoc(second))
        } finally {
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun title(doc: OpenDocument) = ((doc.root as YNode.Map).entries["title"] as YNode.Scalar).value
    private fun edit(ctx: FormCtx, text: String) =
        ctx.setScalar(MapSlot(ctx.doc.root as YNode.Map, "title"), text, FieldType.STRING)

    @Test
    fun `フォームのキーで別文書の直前の変更を戻しボタンと同じ順序でやり直す`() = withWorkspace { state, a, b ->
        val ctx = FormCtx(state, a, emptyList(), readOnly = false)
        edit(ctx, "A編集")
        edit(FormCtx(state, b, emptyList(), readOnly = false), "B編集")
        val handler = TextFieldHistory()
        assertTrue(handler.preview(HistoryShortcut.UNDO, true, false, ctx.historyHandler))
        assertEquals("A編集", title(a))
        assertEquals("B", title(b))
        assertEquals(listOf(b.file), state.historyNotice!!.files)
        assertTrue(handler.preview(HistoryShortcut.UNDO, true, false, ctx.historyHandler))
        assertEquals("A", title(a))
        assertFalse(state.canUndo())
        state.redoFromUi()
        assertEquals("A編集", title(a))
        assertTrue(handler.preview(HistoryShortcut.REDO, true, false, ctx.historyHandler))
        assertEquals("B編集", title(b))
        assertFalse(state.canRedo())
    }

    @Test
    fun `ファイル操作中はキーを消費してフォームの文字列履歴にも戻らない`() = withWorkspace { state, a, _ ->
        val ctx = FormCtx(state, a, emptyList(), readOnly = false)
        edit(ctx, "編集")
        state.fileOperationBusy = true
        assertTrue(TextFieldHistory().preview(HistoryShortcut.UNDO, true, false, ctx.historyHandler))
        assertEquals("編集", title(a))
        state.fileOperationBusy = false
        state.undoFromUi()
        assertEquals("A", title(a))
    }

    @Test
    fun `同じファイル名の一時フォームを文書履歴へ接続しない`() = withWorkspace { state, a, _ ->
        val draft = OpenDocument(a.file, YNode.Map(), null, "", null)
        assertNotNull(FormCtx(state, a, emptyList()).historyHandler)
        assertNull(FormCtx(state, draft, emptyList(), readOnly = false).historyHandler)
    }
}
