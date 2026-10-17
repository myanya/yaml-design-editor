package dev.toros.yde.yaml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YamlPositionsTest {
    private val text = """
        |id: A-1
        |items:
        |  - name: x   # c
        |    tags: [a, b]
        |  - name: y
        |note: |
        |  two
        |  lines
    """.trimMargin()

    @Test
    fun `keys and values get lines and the deepest path wins at a position`() {
        val p = YamlPositions.of(text)
        assertEquals(0, p.lineOf(listOf("id")))
        assertEquals(1, p.lineOf(listOf("items")))
        assertEquals(2, p.lineOf(listOf("items", 0)))
        assertEquals(2, p.lineOf(listOf("items", 0, "name")))
        assertEquals(3, p.lineOf(listOf("items", 0, "tags", 1)))
        assertEquals(4, p.lineOf(listOf("items", 1, "name")))
        assertEquals(5, p.lineOf(listOf("note")))
        assertNull(p.lineOf(listOf("missing")))

        assertEquals(listOf("items", 0, "name"), p.pathAt(2, 4))      // キーの上
        assertEquals(listOf("items", 0, "name"), p.pathAt(2, 10))     // 値の上（終わりの列は排他）
        assertEquals(listOf("items", 0, "tags", 1), p.pathAt(3, 14))  // フロー配列の要素
        assertEquals(listOf("note"), p.pathAt(6, 3))                  // ブロックスカラーの中
        assertNull(p.pathAt(20, 0))
    }

    @Test
    fun `broken or empty text gives an empty table`() {
        assertTrue(YamlPositions.of("a: [b").isEmpty)
        assertTrue(YamlPositions.of("").isEmpty)
    }

    @Test
    fun `aliases do not loop`() {
        val p = YamlPositions.of("base: &b {x: 1}\nuse: *b\n")
        assertEquals(1, p.lineOf(listOf("use")))
        assertEquals(0, p.lineOf(listOf("base", "x")))
    }
}
