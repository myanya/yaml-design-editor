package dev.toros.yde.app

import java.nio.file.Files
import dev.toros.yde.app.ui.DesktopUiDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.*

class AppStateFileArrangementTest {
    private val root = Files.createTempDirectory("app-arrangement")
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }
    private fun state(): AppState {
        Files.writeString(root.resolve("a.yaml"), "name: A\n")
        return AppState().also { it.openWorkspace(root); it.openFile(root.resolve("a.yaml")) }
    }
    @Test fun `壊れた未保存ソースがあるファイルを削除改名しない`() {
        val state = state()
        val doc = state.openDoc!!
        doc.editSource { replace(0, length, "broken: [") }
        doc.flushDraft()
        assertTrue(doc.draftDirty)
        assertFalse(state.deletePath(doc.file).isSuccess)
        assertFalse(state.renamePath(doc.file, "other.yaml").isSuccess)
        assertTrue(Files.exists(doc.file))
        assertEquals("broken: [", doc.source.text.toString())
    }
    @Test fun `改名後も文書と展開が追随しUndoでファイル名を戻せる`() {
        val state = state()
        state.memory.treeSelection = root.resolve("a.yaml")
        val result = state.renamePath(root.resolve("a.yaml"), "b.yaml")
        assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
        assertEquals(root.resolve("b.yaml"), state.openDoc?.file)
        assertTrue(state.canUndo())
        state.undo()
        assertEquals(root.resolve("a.yaml"), state.openDoc?.file)
        assertTrue(Files.exists(root.resolve("a.yaml")))
        state.redo()
        assertTrue(Files.exists(root.resolve("b.yaml")))
    }
    @Test fun `フォルダー作成で以前の編集履歴を失わない`() {
        val state = state()
        val doc = state.openDoc!!
        doc.editSource { replace(0, length, "name: B\n") }; doc.flushDraft()
        assertTrue(state.createDirectory(root, "new").isSuccess)
        state.undo()
        assertFalse(Files.exists(root.resolve("new")))
        assertTrue(state.canUndo())
    }

    @Test fun `外部変更で非同期Undoを拒否しても文書と履歴を保ち処理中表示を解除する`() = runBlocking {
        val state = state()
        val original = root.resolve("a.yaml")
        val moved = root.resolve("b.yaml")
        state.applyFileArrangementFromUi(state.planFileRename(original, "b.yaml")).getOrThrow()
        val doc = assertNotNull(state.openDoc)
        Files.writeString(moved, "name: external\n")

        val job = withContext(DesktopUiDispatcher) { state.historyFromUi(true) }
        job?.join()
        assertContains(state.status, "外部変更と競合")

        assertFalse(state.fileOperationBusy)
        assertFalse(state.fileHistoryBusy)
        assertFalse(Files.exists(original))
        assertEquals("name: external\n", Files.readString(moved))
        assertSame(doc, state.openDoc)
        assertSame(doc, state.docFor(moved))
        assertTrue(state.canUndo())
        assertFalse(state.canRedo())
        assertFalse(state.hasDirtyDocs())
    }

    @Test fun `複数削除は一回で戻せて参考資料の構造保存は原文を変えない`() {
        val state = state()
        val source = root.resolve("Example.kt")
        val text = "class Example {\n fun work() {}\n}\n"
        Files.writeString(source, text)
        val outline = dev.toros.yde.app.core.SourceOutline.extract("Example.kt", text)
        val exported = state.saveSourceOutline(source, outline, "references/example.yaml").getOrThrow()
        assertEquals(text, Files.readString(source))
        assertContains(Files.readString(exported), "work")
        assertFalse(state.saveSourceOutline(source, outline, "references/example.yaml").isSuccess)
        assertTrue(state.deletePaths(listOf(source, exported)).isSuccess)
        assertFalse(Files.exists(source))
        state.undo()
        assertEquals(text, Files.readString(source))
        assertTrue(Files.exists(exported))
        state.openReference(source, 2)
        state.openFile(root.resolve("a.yaml"))
        state.goBack()
        assertEquals(source, state.openPreview)
        assertEquals(2, state.previewLine)
    }

    @Test fun `再読込とスキーマ更新後も一般ファイルを含むフォルダー移動をUndoとRedoできる`() {
        val state = state()
        val folder = Files.createDirectory(root.resolve("docs"))
        Files.writeString(folder.resolve("item.yaml"), "title: A\n")
        Files.writeString(folder.resolve("notes.txt"), "note")
        state.reload()
        state.openFile(folder.resolve("item.yaml"))
        state.applyFileArrangement(state.planFileRename(folder, "moved")).getOrThrow()
        val schemas = Files.createDirectory(root.resolve("_schemas"))
        Files.writeString(schemas.resolve("task.yaml"), "id: task\nmatch: '**/*.yaml'\nroot: {type: object}\n")
        state.reload()
        state.undo()
        assertTrue(Files.exists(folder.resolve("notes.txt")))
        assertEquals(folder.resolve("item.yaml"), state.openDoc?.file)
        state.reload()
        state.redo()
        assertEquals("note", Files.readString(root.resolve("moved/notes.txt")))
        assertEquals(root.resolve("moved/item.yaml"), state.openDoc?.file)
        Files.writeString(root.resolve("moved/notes.txt"), "external note")
        state.reload()
        state.undo()
        assertEquals("external note", Files.readString(root.resolve("moved/notes.txt")))
        assertFalse(Files.exists(folder))
        assertTrue(state.canUndo())
    }

    @Test fun `台帳外で開いているYAMLも外部変更通知で更新する`() {
        Files.createDirectories(root.resolve(".yde"))
        Files.writeString(root.resolve(".yde/workspace.yaml"), "ledger: [docs]\n")
        val state = state()
        val file = root.resolve("a.yaml")
        Files.writeString(file, "name: outside\n")
        state.noteFileEvents(setOf(file))
        assertContains(state.openDoc!!.source.text.toString(), "outside")
    }
}
