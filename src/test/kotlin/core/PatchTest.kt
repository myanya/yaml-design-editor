package core

import model.YNode
import model.parseYPath
import yaml.YamlIO
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

    // ---------------------------------------------------------------- 変更セットの解析

    @Test
    fun `parses changeset yaml`() {
        val cs = ChangeSets.parse(
            """
            changes:
              - file: a.yaml
                base: abc123
                ops:
                  - { op: set, path: title, value: 新タイトル }
                  - { op: insert, path: "tags[0]", value: 重要 }
                  - { op: delete, path: obsolete }
                  - { op: move, from: "items[2]", to: "items[0]" }
            """.trimIndent()
        )
        assertEquals(1, cs.changes.size)
        val change = cs.changes.single()
        assertEquals("a.yaml", change.file)
        assertEquals("abc123", change.baseHash)
        assertEquals(4, change.ops.size)
        assertTrue(change.ops[0] is PatchOp.Set)
        assertTrue(change.ops[1] is PatchOp.Insert)
        assertTrue(change.ops[2] is PatchOp.Delete)
        assertTrue(change.ops[3] is PatchOp.Move)
        assertEquals(listOf<Any>("items", 2), (change.ops[3] as PatchOp.Move).from)
    }

    @Test
    fun `changeset with unknown op fails`() {
        assertFailsWith<IllegalStateException> {
            ChangeSets.parse("changes:\n  - file: a.yaml\n    ops:\n      - { op: rename, path: a }")
        }
    }

    @Test
    fun `structured values are converted to nodes`() {
        val cs = ChangeSets.parse(
            """
            changes:
              - file: a.yaml
                ops:
                  - op: set
                    path: item
                    value:
                      id: X-1
                      tags: [a, b]
            """.trimIndent()
        )
        val value = (cs.changes.single().ops.single() as PatchOp.Set).value
        assertEquals("id: X-1\ntags:\n- a\n- b\n", YamlIO.dump(value))
    }
}
