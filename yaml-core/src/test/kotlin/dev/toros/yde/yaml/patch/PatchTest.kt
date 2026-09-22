package dev.toros.yde.yaml.patch

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PatchTest {

    private fun tree(text: String): YNode = YamlIO.parse(text.trimIndent())
    private fun dump(node: YNode): String = YamlIO.dump(node)
    private fun scalar(v: Any?): YNode = YNode.Scalar(v)

    // ---------------------------------------------------------------- set

    @Test
    fun `set replaces existing scalar`() {
        val root = tree("title: old\nitems:\n- a")
        Patcher.apply(root, PatchOp.Set(parseYPath("title"), scalar("new")))
        assertEquals(dump(tree("title: new\nitems:\n- a")), dump(root))
    }

    @Test
    fun `set creates missing map key`() {
        val root = tree("title: x")
        Patcher.apply(root, PatchOp.Set(parseYPath("owner"), scalar("yamada")))
        assertEquals(dump(tree("title: x\nowner: yamada")), dump(root))
    }

    @Test
    fun `set on out of range index fails`() {
        val root = tree("items:\n- a")
        assertFailsWith<IllegalStateException> {
            Patcher.apply(root, PatchOp.Set(parseYPath("items[3]"), scalar("x")))
        }
    }

    // ---------------------------------------------------------------- insert

    @Test
    fun `insert shifts sequence items`() {
        val root = tree("items:\n- a\n- c")
        Patcher.apply(root, PatchOp.Insert(parseYPath("items[1]"), scalar("b")))
        assertEquals(dump(tree("items:\n- a\n- b\n- c")), dump(root))
    }

    @Test
    fun `insert at end appends`() {
        val root = tree("items:\n- a")
        Patcher.apply(root, PatchOp.Insert(parseYPath("items[1]"), scalar("b")))
        assertEquals(dump(tree("items:\n- a\n- b")), dump(root))
    }

    @Test
    fun `insert into map rejects existing key`() {
        val root = tree("title: x")
        assertFailsWith<IllegalStateException> {
            Patcher.apply(root, PatchOp.Insert(parseYPath("title"), scalar("y")))
        }
    }

    // ---------------------------------------------------------------- delete / move

    @Test
    fun `delete removes key and index`() {
        val root = tree("title: x\nitems:\n- a\n- b")
        Patcher.apply(root, PatchOp.Delete(parseYPath("items[0]")))
        Patcher.apply(root, PatchOp.Delete(parseYPath("title")))
        assertEquals(dump(tree("items:\n- b")), dump(root))
    }

    @Test
    fun `delete missing path fails`() {
        val root = tree("title: x")
        assertFailsWith<IllegalStateException> { Patcher.apply(root, PatchOp.Delete(parseYPath("missing"))) }
    }

    @Test
    fun `move reorders within a sequence`() {
        val root = tree("items:\n- a\n- b\n- c")
        Patcher.apply(root, PatchOp.Move(parseYPath("items[2]"), parseYPath("items[0]")))
        assertEquals(dump(tree("items:\n- c\n- a\n- b")), dump(root))
    }

    @Test
    fun `move relocates between containers`() {
        val root = tree("draft:\n- a\ndone: []")
        Patcher.apply(root, PatchOp.Move(parseYPath("draft[0]"), parseYPath("done[0]")))
        assertEquals(dump(tree("draft: []\ndone:\n- a")), dump(root))
    }

    @Test
    fun `root patch is rejected`() {
        val root = tree("a: 1")
        assertFailsWith<IllegalArgumentException> {
            Patcher.apply(root, PatchOp.Set(emptyList(), scalar("x")))
        }
    }

    // ---------------------------------------------------------------- rename（R25-01）

    @Test
    fun `rename keeps key order and comments`() {
        val root = tree("# 先頭コメント\ntitle: x  # 行内コメント\nowner: yamada")
        Patcher.apply(root, PatchOp.RenameKey(parseYPath("title"), "name"))
        val text = dump(root)
        assertTrue(text.indexOf("name:") < text.indexOf("owner:"), "改名後もキー順が保たれる: $text")
        assertTrue("先頭コメント" in text && "行内コメント" in text, "コメントが保持される: $text")
        assertTrue("title" !in text)
    }

    @Test
    fun `rename works on nested keys`() {
        val root = tree("details:\n  priority: high\n  memo: m")
        Patcher.apply(root, PatchOp.RenameKey(parseYPath("details.priority"), "urgency"))
        assertEquals(dump(tree("details:\n  urgency: high\n  memo: m")), dump(root))
    }

    @Test
    fun `rename rejects missing and colliding keys`() {
        val root = tree("title: x\nname: y")
        assertFailsWith<IllegalStateException> { Patcher.apply(root, PatchOp.RenameKey(parseYPath("missing"), "z")) }
        assertFailsWith<IllegalStateException> { Patcher.apply(root, PatchOp.RenameKey(parseYPath("title"), "name")) }
        assertFailsWith<IllegalStateException> { Patcher.apply(root, PatchOp.RenameKey(parseYPath("items[0]"), "z")) }
    }

    // ---------------------------------------------------------------- reorder（R25-02）

    @Test
    fun `reorder sorts listed keys first and keeps the rest`() {
        val root = tree("c: 3\na: 1  # コメント\nz: 26\nb: 2")
        Patcher.apply(root, PatchOp.ReorderKeys(emptyList(), listOf("a", "b", "c")))
        val text = dump(root)
        val order = listOf("a:", "b:", "c:", "z:").map { text.indexOf(it) }
        assertEquals(order, order.sorted(), "指定キーが先頭・残りは相対順を保つ: $text")
        assertTrue("コメント" in text)
    }

    @Test
    fun `reorder works on nested maps`() {
        val root = tree("details:\n  b: 2\n  a: 1")
        Patcher.apply(root, PatchOp.ReorderKeys(parseYPath("details"), listOf("a", "b")))
        assertEquals(dump(tree("details:\n  a: 1\n  b: 2")), dump(root))
    }
}
