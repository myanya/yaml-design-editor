package dev.toros.yde.yaml.model

import dev.toros.yde.yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class YPathTest {

    @Test
    fun `path of a node is found by identity`() {
        val root = YamlIO.parse("items:\n  - name: x\n  - name: y\n")
        val second = ((root as YNode.Map).entries["items"] as YNode.Seq).items[1]
        val name = (second as YNode.Map).entries["name"]!!
        assertEquals(listOf("items", 1, "name"), pathOfNode(root, name))
        assertEquals(name, getAt(root, pathOfNode(root, name)!!))
        // 値が等しくても別のノードは見つけない
        assertNull(pathOfNode(root, YNode.Scalar("y")))
    }

    @Test
    fun `parses keys and indexes`() {
        assertEquals(listOf<Any>("items", 2, "title"), parseYPath("items[2].title"))
        assertEquals(listOf<Any>("a", "b", "c"), parseYPath("a.b.c"))
        assertEquals(listOf<Any>(0, "x"), parseYPath("[0].x"))
    }

    @Test
    fun `empty and root markers become empty path`() {
        assertEquals(emptyList(), parseYPath(""))
        assertEquals(emptyList(), parseYPath("  "))
        assertEquals(emptyList(), parseYPath("(root)"))
    }

    @Test
    fun `roundtrips with pretty`() {
        val paths = listOf(
            listOf<Any>("items", 2, "title"),
            listOf<Any>("a"),
            listOf<Any>(0),
            listOf<Any>("map", "key", 10, 3),
        )
        for (p in paths) assertEquals(p, parseYPath(p.pretty()))
    }

    @Test
    fun `invalid index is rejected`() {
        assertFailsWith<IllegalArgumentException> { parseYPath("items[x]") }
        assertFailsWith<IllegalArgumentException> { parseYPath("items[1") }
        assertFailsWith<IllegalArgumentException> { parseYPath("items]") }
    }
}
