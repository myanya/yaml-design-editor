package dev.toros.yde.app.text

import kotlin.test.Test
import kotlin.test.assertEquals

class LineMergeTest {
    private fun lines(vararg s: String) = s.toList()

    @Test
    fun `diff finds a single changed line`() {
        val a = lines("a", "b", "c", "d")
        val b = lines("a", "B", "c", "d")
        assertEquals(listOf(LineMerge.Hunk(1, 2, listOf("B"))), LineMerge.diff(a, b))
    }

    @Test
    fun `diff finds insertions and deletions`() {
        val a = lines("a", "b", "c")
        assertEquals(listOf(LineMerge.Hunk(1, 1, listOf("x", "y"))), LineMerge.diff(a, lines("a", "x", "y", "b", "c")))
        assertEquals(listOf(LineMerge.Hunk(1, 2, emptyList())), LineMerge.diff(a, lines("a", "c")))
        assertEquals(emptyList(), LineMerge.diff(a, a))
    }

    @Test
    fun `diff of a large document with one change is one hunk and fast`() {
        val a = (1..10_000).map { "key-$it: value" }
        val b = a.toMutableList().also { it[5_000] = "key-5001: changed" }
        val t = System.nanoTime()
        val hunks = LineMerge.diff(a, b)
        val ms = (System.nanoTime() - t) / 1_000_000
        assertEquals(listOf(LineMerge.Hunk(5_000, 5_001, listOf("key-5001: changed"))), hunks)
        assert(ms < 200) { "1 万行の差分に ${ms}ms かかった" }
    }

    @Test
    fun `merge applies their change while keeping our unfinished edit elsewhere`() {
        val base = lines("name: a", "status: draft", "note: x")
        val ours = lines("name: a", "status: draft", "note: x", "tags: [one, two")   // 下書き（閉じ括弧がまだ無い）
        val theirs = lines("name: a", "status: review", "note: x")                   // フォームで status を変えた
        val merged = LineMerge.merge3(base, ours, theirs)
        assertEquals(lines("name: a", "status: review", "note: x", "tags: [one, two"), merged.lines)
        assertEquals(0, merged.conflicts)
    }

    @Test
    fun `merge shifts their hunk when our edit inserted lines before it`() {
        val base = lines("a", "b", "c", "d")
        val ours = lines("a", "x", "y", "b", "c", "d")
        val theirs = lines("a", "b", "c", "D")
        val merged = LineMerge.merge3(base, ours, theirs)
        assertEquals(lines("a", "x", "y", "b", "c", "D"), merged.lines)
        assertEquals(0, merged.conflicts)
    }

    @Test
    fun `merge keeps ours and counts a conflict when both changed the same line`() {
        val base = lines("a", "b", "c")
        val ours = lines("a", "b-ours", "c")
        val theirs = lines("a", "b-theirs", "c")
        val merged = LineMerge.merge3(base, ours, theirs)
        assertEquals(ours, merged.lines)
        assertEquals(1, merged.conflicts)
    }

    @Test
    fun `apply to text replaces lines and keeps the caret on the same spot`() {
        val text = "a\nb\nc\nd"
        // カーソルは "c" の先頭（位置 4）。先頭行を 2 行に増やすと 2 文字ぶん後ろへずれる
        val r = LineMerge.applyToText(text, listOf(LineMerge.Hunk(0, 1, listOf("a", "a2"))), caret = 4)
        assertEquals("a\na2\nb\nc\nd", r.text)
        assertEquals(7, r.caret)
        // 末尾の行を置き換えても末尾に改行を足さない
        val last = LineMerge.applyToText(text, listOf(LineMerge.Hunk(3, 4, listOf("D"))), caret = 1)
        assertEquals("a\nb\nc\nD", last.text)
        assertEquals(1, last.caret)
        // カーソルが置き換えた範囲の中なら範囲の先頭へ寄る
        val inside = LineMerge.applyToText(text, listOf(LineMerge.Hunk(1, 3, listOf("X"))), caret = 5)
        assertEquals("a\nX\nd", inside.text)
        assertEquals(2, inside.caret)
    }
}
