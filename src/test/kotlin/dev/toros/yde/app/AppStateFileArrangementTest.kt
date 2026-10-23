package dev.toros.yde.app

import java.nio.file.Files
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
        doc.source.edit { replace(0, length, "broken: [") }
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
        doc.source.edit { replace(0, length, "name: B\n") }; doc.flushDraft()
        assertTrue(state.createDirectory(root, "new").isSuccess)
        state.undo()
        assertFalse(Files.exists(root.resolve("new")))
        assertTrue(state.canUndo())
    }
}
