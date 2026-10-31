package dev.toros.yde.workspace

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.DefaultYamlCodec
import java.nio.file.Files
import kotlin.test.*

class BackgroundFileOperationTest {
    private val root = Files.createTempDirectory("background-arrangement")
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    @Test fun `背景で移動しても採用前の本文と履歴は変わらずUndoとRedoの印も独立する`() {
        Files.writeString(root.resolve("a.yaml"), "name: A\n")
        Files.writeString(root.resolve("keep.yaml"), "name: keep\n")
        val s = WorkspaceSession(FileSystemDocumentStore(root))
        val keep = DocumentId("keep.yaml")
        val dirty = DefaultYamlCodec.parse(keep, "name: edited\n")
        assertTrue(s.recordEdit(keep, dirty).ok)
        val before = s.history.toList()
        val plan = FileArrangementPlanner.plan(s, root, listOf(PathMove("a.yaml", "b.yaml")))
        val work = s.prepareFileArrangement(plan)
        work.execute()
        assertEquals(before, s.history)
        assertTrue(s.hasDocument(DocumentId("a.yaml")))
        assertFalse(s.hasDocument(DocumentId("b.yaml")))
        assertTrue(Files.exists(root.resolve("b.yaml")))
        assertTrue(work.finish().ok)
        assertTrue(s.isDirty(keep))
        assertEquals(before.size + 1, s.history.size)
        assertFailsWith<IllegalStateException> { work.finish() }

        val undo = s.prepareFileHistory(true)
        undo.execute()
        assertTrue(s.hasDocument(DocumentId("b.yaml")))
        assertTrue(undo.finish().ok)
        assertEquals(before, s.history)
        val redo = s.prepareFileHistory(false)
        redo.execute()
        assertTrue(s.hasDocument(DocumentId("a.yaml")))
        assertTrue(redo.finish().ok)
        assertTrue(s.hasDocument(DocumentId("b.yaml")))
        assertTrue(s.isDirty(keep))
    }

    @Test fun `外部競合で失敗した作業は元の状態と履歴を変えない`() {
        val file = root.resolve("a.yaml")
        Files.writeString(file, "name: A\n")
        val s = WorkspaceSession(FileSystemDocumentStore(root))
        val plan = FileArrangementPlanner.plan(s, root, listOf(PathMove("a.yaml", "b.yaml")))
        val work = s.prepareFileArrangement(plan)
        Files.writeString(file, "name: outside\n")
        val before = s.snapshot
        work.execute()
        assertFalse(work.finish().ok)
        assertEquals(before.version, s.version)
        assertEquals(before.documents, s.snapshot.documents)
        assertFalse(s.canUndo())
        assertEquals("name: outside\n", Files.readString(file))
    }
}
