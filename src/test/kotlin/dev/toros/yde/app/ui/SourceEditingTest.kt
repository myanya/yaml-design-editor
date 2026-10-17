package dev.toros.yde.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceEditingTest {
    private val sample = "a:\n  b: 1\n  c: 2\nd: 3"   // 行 0〜3。位置: a:=0, "  b: 1"=3, "  c: 2"=10, "d: 3"=17

    @Test
    fun `touched lines exclude a line whose start is the selection end`() {
        assertEquals(1..1, SourceEditing.touchedLines(sample, 3, 9))
        assertEquals(1..1, SourceEditing.touchedLines(sample, 3, 10))   // 10 は 2 行目の先頭＝含めない
        assertEquals(1..2, SourceEditing.touchedLines(sample, 5, 12))
        assertEquals(0..0, SourceEditing.touchedLines(sample, 0, 0))
    }

    @Test
    fun `enter keeps the indent and goes deeper after a colon or a lone dash`() {
        assertEquals("\n  ", SourceEditing.newlineInsertion(sample, 9))          // "  b: 1" の末尾 → 同じ深さ
        assertEquals("\n  ", SourceEditing.newlineInsertion(sample, 2))          // "a:" の末尾 → 1 段深く
        assertEquals("\n    ", SourceEditing.newlineInsertion("  - \n", 4))      // "- " だけ → 1 段深く
        assertEquals("\n  ", SourceEditing.newlineInsertion("  key: v  # c:\n", 14))   // コメントの `:` は数えない
        assertEquals("\n ", SourceEditing.newlineInsertion("    x: 1", 1))       // 行頭の空白の中 → そこまで
    }

    @Test
    fun `indent and outdent touched lines and shift the selection`() {
        val r = SourceEditing.indent(sample, 5, 12, outdent = false)
        assertEquals("a:\n    b: 1\n    c: 2\nd: 3", r.text)
        assertEquals(7, r.start)
        assertEquals(16, r.end)
        val back = SourceEditing.indent(r.text, r.start, r.end, outdent = true)
        assertEquals(sample, back.text)
        assertEquals(5, back.start)
        assertEquals(12, back.end)
        // 行頭で戻すとき、行頭より前へは動かない
        val edge = SourceEditing.indent("  x\n", 0, 0, outdent = true)
        assertEquals("x\n", edge.text)
        assertEquals(0, edge.start)
    }

    @Test
    fun `toggle comment adds at the shallowest indent and removes when all commented`() {
        val on = SourceEditing.toggleComment(sample, 5, 12)
        assertEquals("a:\n  # b: 1\n  # c: 2\nd: 3", on.text)
        assertEquals(7, on.start)
        val off = SourceEditing.toggleComment(on.text, on.start, on.end)
        assertEquals(sample, off.text)
        assertEquals(5, off.start)
        // 混在（片方だけコメント）は「全部コメントにする」
        val mixed = SourceEditing.toggleComment("# a\nb\n", 0, 5)
        assertEquals("# # a\n# b\n", mixed.text)
    }

    @Test
    fun `move lines swaps with the neighbor and stops at the edges`() {
        val down = SourceEditing.moveLines(sample, 3, 3, up = false)
        assertEquals("a:\n  c: 2\n  b: 1\nd: 3", down.text)
        assertEquals(10, down.start)
        val up = SourceEditing.moveLines(down.text, down.start, down.start, up = true)
        assertEquals(sample, up.text)
        assertEquals(3, up.start)
        assertEquals(sample, SourceEditing.moveLines(sample, 0, 0, up = true).text)
        assertEquals(sample, SourceEditing.moveLines(sample, 18, 18, up = false).text)
    }

    @Test
    fun `duplicate lines copies the block below and moves the selection to the copy`() {
        val r = SourceEditing.duplicateLines(sample, 5, 5)
        assertEquals("a:\n  b: 1\n  b: 1\n  c: 2\nd: 3", r.text)
        assertEquals(12, r.start)
        val two = SourceEditing.duplicateLines(sample, 4, 11)
        assertEquals("a:\n  b: 1\n  c: 2\n  b: 1\n  c: 2\nd: 3", two.text)
    }

    @Test
    fun `search matches literal and regex and navigates with wrap around`() {
        val text = "Id: A-1\nid: a-2\nname: x"
        assertEquals(2, SourceEditing.matches(text, "id", regex = false, caseSensitive = false)!!.size)
        assertEquals(1, SourceEditing.matches(text, "id", regex = false, caseSensitive = true)!!.size)
        assertEquals(2, SourceEditing.matches(text, "[a-z]-\\d", regex = true, caseSensitive = false)!!.size)
        assertNull(SourceEditing.matches(text, "[", regex = true, caseSensitive = false))
        val m = SourceEditing.matches(text, "id", regex = false, caseSensitive = false)!!
        assertEquals(0, SourceEditing.nextMatch(m, 0))
        assertEquals(1, SourceEditing.nextMatch(m, 1))
        assertEquals(0, SourceEditing.nextMatch(m, 100))      // 先頭へ戻る
        assertEquals(1, SourceEditing.prevMatch(m, 0))        // 末尾へ回る
        assertEquals(0, SourceEditing.prevMatch(m, 8))
    }

    @Test
    fun `replace expands regex groups and replace all counts`() {
        val text = "key: A-1\nkey: B-2"
        val m = SourceEditing.matches(text, "([A-Z])-(\\d)", regex = true, caseSensitive = true)!!
        val one = SourceEditing.replaceMatch(text, m[0], "([A-Z])-(\\d)", "$2-$1", regex = true, caseSensitive = true)
        assertEquals("key: 1-A\nkey: B-2", one.text)
        assertEquals(5, one.start)
        assertEquals(8, one.end)
        val literal = SourceEditing.replaceMatch(text, 5..7, "A-1", "\$x", regex = false, caseSensitive = true)
        assertEquals("key: \$x\nkey: B-2", literal.text)
        assertEquals("key: 1-A\nkey: 2-B" to 2, SourceEditing.replaceAll(text, "([A-Z])-(\\d)", "$2-$1", regex = true, caseSensitive = true))
        assertEquals("key: \$x\nkey: B-2" to 1, SourceEditing.replaceAll(text, "a-1", "\$x", regex = false, caseSensitive = false))
    }
}
