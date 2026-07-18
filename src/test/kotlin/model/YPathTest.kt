package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class YPathTest {

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
