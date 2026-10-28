package dev.toros.yde.desktop.ui

import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.OpenDocument
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.getAt
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ArrayDeletionTest {
    private val text = "items: [{id: A, children: [one, two]}, {id: B}]\n"
    private fun document() = OpenDocument(Path.of("array.yaml"), YamlIO.parse(text), null, text, null)
    private fun array(doc: OpenDocument) = getAt(doc.root!!, listOf("items")) as YNode.Seq

    @Test
    fun `確認を開いて取り消しても木と版と編集通知を変えない`() {
        val doc = document()
        var edits = 0
        doc.onEdited = { edits++ }
        val request = ArrayDeletion(doc, array(doc), 0)
        assertEquals(listOf("items"), request.path)
        assertNull(request.problem(doc, false))
        assertEquals(YamlIO.dumpPlain(YamlIO.parse(text)), YamlIO.dumpPlain(doc.root!!))
        assertEquals(0, doc.version)
        assertEquals(0, edits)
        assertFalse(doc.dirty)
    }

    @Test
    fun `確定だけが子を含む一要素を削除して一時フォームの編集通知を一回呼ぶ`() {
        val doc = document()
        var edits = 0
        doc.onEdited = { edits++ }
        val request = ArrayDeletion(doc, array(doc), 0)
        assertTrue(request.confirm(doc, false))
        assertEquals("B", (getAt(doc.root!!, listOf("items", 0, "id")) as YNode.Scalar).value)
        assertEquals(1, array(doc).items.size)
        assertEquals(1, edits)
        assertTrue(doc.dirty)
        assertFalse(request.confirm(doc, false))
        assertEquals(1, edits)
    }

    @Test
    fun `閲覧専用への切替では変更と完了通知を行わない`() {
        val doc = document()
        val request = ArrayDeletion(doc, array(doc), 0)
        assertFalse(request.confirm(doc, true, afterDelete = { fail("削除後処理に到達しない") }))
        assertEquals(2, array(doc).items.size)
        assertFalse(doc.dirty)
    }

    @Test
    fun `並べ替えや同じ行番号の要素置換を確認後に削除しない`() {
        for (replace in listOf(false, true)) {
            val doc = document()
            val node = array(doc)
            val request = ArrayDeletion(doc, node, 0)
            if (replace) node.items[0] = YamlIO.parse("id: C") else moveSeqItem(node, 0, 1)
            assertFalse(request.confirm(doc, false))
            assertEquals(2, node.items.size)
            assertEquals(0, doc.version)
        }
    }

    @Test
    fun `同じ要素の内容変更と文書の再読込と別文書への切替を拒否する`() {
        val doc = document()
        val request = ArrayDeletion(doc, array(doc), 0)
        (getAt(doc.root!!, listOf("items", 0, "id")) as YNode.Scalar).value = "C"
        doc.markEdited()
        assertFalse(request.confirm(doc, false))
        val newer = ArrayDeletion(doc, array(doc), 0)
        doc.root = YamlIO.parse(text)
        assertFalse(newer.confirm(doc, false))
        assertFalse(newer.confirm(document(), false))
        assertEquals(2, array(doc).items.size)
    }

    @Test
    fun `解析待ちソースと文書から外れた配列と存在しない行を拒否する`() {
        val doc = document()
        val request = ArrayDeletion(doc, array(doc), 0)
        doc.editSource { replace(0, length, "items: [変更中]") }
        assertFalse(request.confirm(doc, false))
        val other = document()
        assertFalse(ArrayDeletion(other, array(doc), 0).confirm(other, false))
        assertFalse(ArrayDeletion(other, array(other), 2).confirm(other, false))
        assertFalse(other.dirty)
        assertEquals(2, array(doc).items.size)
    }

    @Test
    fun `空の要素とルート配列も確定で削除できる`() {
        val source = "['', null, {}, []]"
        val doc = OpenDocument(Path.of("array.yaml"), YamlIO.parse(source), null, source, null)
        val node = doc.root as YNode.Seq
        repeat(4) {
            val request = ArrayDeletion(doc, node, 0)
            assertEquals(emptyList(), request.path)
            assertTrue(request.confirm(doc, false))
        }
        assertTrue(node.items.isEmpty())
    }

    @Test
    fun `削除とカバレッジ空配列の後処理が同じUndo単位となり保存は明示操作を待つ`() {
        val dir = createTempDirectory("yde-array-delete")
        try {
            val file = dir.resolve("data.yaml")
            val source = "items: [{id: A, children: [one, two]}]\n"
            Files.writeString(file, source)
            val state = AppState().also { it.openWorkspace(dir); it.openFile(file) }
            val doc = state.openDoc!!
            val ctx = FormCtx(state, doc, emptyList(), readOnly = false)
            val request = ArrayDeletion(doc, array(doc), 0)
            assertTrue(request.confirm(doc, ctx.readOnly,
                afterDelete = { (doc.root as YNode.Map).entries.remove("items") },
                markEdited = { ctx.markEdited() }))
            assertTrue(doc.dirty)
            assertEquals(source, Files.readString(file))
            assertNull(getAt(doc.root!!, listOf("items")))
            state.undo()
            assertEquals(1, array(doc).items.size)
            assertEquals(2, (getAt(doc.root!!, listOf("items", 0, "children")) as YNode.Seq).items.size)
            assertFalse(doc.dirty)
            assertFalse(request.confirm(doc, false))
            state.redo()
            assertNull(getAt(doc.root!!, listOf("items")))
            assertTrue(doc.dirty)
            state.saveAll()
            assertFalse(doc.dirty, state.status)
        } finally {
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
