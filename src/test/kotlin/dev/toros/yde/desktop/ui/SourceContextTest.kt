package dev.toros.yde.desktop.ui

import dev.toros.yde.app.text.LineMerge
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceContextTest {
    private val text = """
        |id: A-1
        |items:
        |  - name: x
        |    tags: [a]
        |  - na
        |note: |
        |  two
        |  lines
        |stat
    """.trimMargin()

    private fun caretAtEndOfLine(n: Int): Int {
        val starts = LineMerge.lineStarts(text)
        val next = starts[n + 1]
        return if (next >= text.length) text.length else next - 1   // 最後の行は末尾（番兵）まで
    }

    @Test
    fun `typing a key inside a sequence item gives the item path and the prefix`() {
        val c = SourceContext.at(text, caretAtEndOfLine(4))   // "  - na"
        assertEquals(SourceContext.Mode.KEY, c.mode)
        assertEquals(listOf("items", 1), c.parentPath)
        assertEquals("na", c.prefix)
        assertEquals("na", text.substring(c.replaceStart, c.replaceEnd))
    }

    @Test
    fun `typing a top level key after a block scalar gives the root`() {
        val c = SourceContext.at(text, caretAtEndOfLine(8))   // "stat"
        assertEquals(SourceContext.Mode.KEY, c.mode)
        assertEquals(emptyList<Any>(), c.parentPath)
        assertEquals("stat", c.prefix)
    }

    @Test
    fun `typing a value gives the key and the value prefix`() {
        val c = SourceContext.at(text, caretAtEndOfLine(2))   // "  - name: x"
        assertEquals(SourceContext.Mode.VALUE, c.mode)
        assertEquals(listOf("items", 0), c.parentPath)
        assertEquals("name", c.key)
        assertEquals("x", c.prefix)
        assertEquals("x", text.substring(c.replaceStart, c.replaceEnd))
        val top = SourceContext.at(text, caretAtEndOfLine(0))   // "id: A-1"
        assertEquals(listOf<Any>(), top.parentPath)
        assertEquals("id", top.key)
        assertEquals("A-1", top.prefix)
    }

    @Test
    fun `a nested key under an item uses the item as parent`() {
        val c = SourceContext.at(text, caretAtEndOfLine(3))   // "    tags: [a]"
        assertEquals(SourceContext.Mode.VALUE, c.mode)
        assertEquals(listOf("items", 0), c.parentPath)
        assertEquals("tags", c.key)
    }

    @Test
    fun `inside a block scalar or a comment there is nothing to complete`() {
        assertEquals(SourceContext.Mode.NONE, SourceContext.at(text, caretAtEndOfLine(6)).mode)   // "  two"
        assertEquals(SourceContext.Mode.NONE, SourceContext.at("a: 1\n# com", 9).mode)
    }

    @Test
    fun `an empty new line under an object key is a key position with the key as parent`() {
        val t = "items:\n  - name: x\n    "
        val c = SourceContext.at(t, t.length)
        assertEquals(SourceContext.Mode.KEY, c.mode)
        assertEquals(listOf("items", 0), c.parentPath)
        assertEquals("", c.prefix)
        val t2 = "spec:\n  "
        val c2 = SourceContext.at(t2, t2.length)
        assertEquals(listOf("spec"), c2.parentPath)
    }
}
