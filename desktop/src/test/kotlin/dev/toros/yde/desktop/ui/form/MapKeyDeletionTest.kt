package dev.toros.yde.desktop.ui.form

import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.getAt
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.OpenDocument

/** map のキーの削除の確認（UX-189）。配列の行（ArrayDeletionTest）と同じ決まり: 取消は何も変えず、確認中に対象が変わったら消さない。 */
class MapKeyDeletionTest {
    private val text = "labels: {a: {name: A, tags: [x, y]}, b: {name: B}}\n"
    private fun document() = OpenDocument(Path.of("map.yaml"), YamlIO.parse(text), null, text, null)
    private fun map(doc: OpenDocument) = getAt(doc.root!!, listOf("labels")) as YNode.Map

    @Test
    fun `確認を開いて取り消しても木と版と編集通知を変えない`() {
        val doc = document()
        var edits = 0
        doc.onEdited = { edits++ }
        val request = MapKeyDeletion(doc, map(doc), "a")
        assertEquals(listOf("labels"), request.path)
        assertNull(request.problem(doc, false))
        assertEquals(YamlIO.dumpPlain(YamlIO.parse(text)), YamlIO.dumpPlain(doc.root!!))
        assertEquals(0, doc.version)
        assertEquals(0, edits)
        assertFalse(doc.dirty)
    }

    @Test
    fun `確定だけが子を含む一つのキーを削除して編集通知を一回呼ぶ`() {
        val doc = document()
        var edits = 0
        doc.onEdited = { edits++ }
        val request = MapKeyDeletion(doc, map(doc), "a")
        assertTrue(request.confirm(doc, false))
        assertEquals(listOf("b"), map(doc).entries.keys.toList())
        assertEquals(1, edits)
        assertTrue(doc.dirty)
        assertFalse(request.confirm(doc, false), "同じ確認で 2 回は消さない")
        assertEquals(1, edits)
    }

    @Test
    fun `閲覧専用では削除しない`() {
        val doc = document()
        assertFalse(MapKeyDeletion(doc, map(doc), "a").confirm(doc, true, markEdited = { fail("編集通知に到達しない") }))
        assertEquals(2, map(doc).entries.size)
        assertFalse(doc.dirty)
    }

    @Test
    fun `確認中に値が置き換わった・キー名が変わった・内容が変わったキーは削除しない`() {
        // 同じ名前の別の値に置き換わった
        document().let { doc ->
            val request = MapKeyDeletion(doc, map(doc), "a")
            map(doc).entries["a"] = YamlIO.parse("name: C")
            assertFalse(request.confirm(doc, false))
            assertEquals(2, map(doc).entries.size)
        }
        // キー名が変わった（同じ名前のキーはもう無い）
        document().let { doc ->
            val request = MapKeyDeletion(doc, map(doc), "a")
            assertTrue(renameMapKey(map(doc), "a", "c"))
            assertFalse(request.confirm(doc, false))
            assertEquals(listOf("c", "b"), map(doc).entries.keys.toList())
        }
        // 中の値を編集した（版が進んだ）
        document().let { doc ->
            val request = MapKeyDeletion(doc, map(doc), "a")
            (getAt(doc.root!!, listOf("labels", "a", "name")) as YNode.Scalar).value = "A2"
            doc.markEdited()
            assertFalse(request.confirm(doc, false))
            assertEquals(2, map(doc).entries.size)
        }
    }

    @Test
    fun `文書の再読込と別文書と解析待ちのソースと無いキーを拒否する`() {
        val doc = document()
        val request = MapKeyDeletion(doc, map(doc), "a")
        assertFalse(request.confirm(document(), false), "別の文書")
        assertFalse(MapKeyDeletion(doc, map(doc), "zz").confirm(doc, false), "無いキー")
        val pending = MapKeyDeletion(doc, map(doc), "a")
        doc.editSource { replace(0, length, "labels: {変更中: 1}") }
        assertFalse(pending.confirm(doc, false), "解析待ちのソース")
        val reloaded = document()
        val stale = MapKeyDeletion(reloaded, map(reloaded), "a")
        reloaded.root = YamlIO.parse(text)
        assertFalse(stale.confirm(reloaded, false), "再読込で木が入れ替わった")
        assertEquals(2, map(reloaded).entries.size)
    }

    @Test
    fun `空の値とルートのmapのキーも確定で削除できる`() {
        val source = "a: ''\nb: null\nc: {}\n"
        val doc = OpenDocument(Path.of("map.yaml"), YamlIO.parse(source), null, source, null)
        val root = doc.root as YNode.Map
        for (key in listOf("a", "b", "c")) {
            val request = MapKeyDeletion(doc, root, key)
            assertEquals(emptyList(), request.path)
            assertTrue(request.confirm(doc, false), key)
        }
        assertTrue(root.entries.isEmpty())
    }

    @Test
    fun `削除はUndo一回で子ごと戻り 保存は明示操作を待つ`() {
        val dir = createTempDirectory("yde-map-delete")
        try {
            val file = dir.resolve("data.yaml")
            Files.writeString(file, text)
            val state = AppState().also { it.openWorkspace(dir); it.openFile(file) }
            val doc = state.openDoc!!
            val ctx = FormCtx(state, doc, emptyList(), readOnly = false)
            val request = MapKeyDeletion(doc, map(doc), "a")
            assertTrue(request.confirm(doc, ctx.readOnly, markEdited = { ctx.markEdited() }))
            assertTrue(doc.dirty)
            assertEquals(text, Files.readString(file))
            assertEquals(listOf("b"), map(doc).entries.keys.toList())
            state.undo()
            assertEquals(listOf("a", "b"), map(doc).entries.keys.toList())
            assertEquals(2, (getAt(doc.root!!, listOf("labels", "a", "tags")) as YNode.Seq).items.size)
            assertFalse(doc.dirty)
            assertFalse(request.confirm(doc, false), "Undo の後に古い確認では消さない")
            state.redo()
            assertEquals(listOf("b"), map(doc).entries.keys.toList())
        } finally {
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
