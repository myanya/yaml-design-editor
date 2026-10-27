package dev.toros.yde.desktop.ui

import dev.toros.yde.yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceStructureTest {
    private val text = """
        |id: A
        |items:
        |  - name: x
        |    tags:
        |      - a
        |
        |  - name: y
        |note: |
        |  two
        |  lines
        |end: 1
    """.trimMargin()
    private val lines = text.split('\n')

    @Test
    fun `foldable regions follow indentation and stop before a shallower line`() {
        val regions = SourceStructure.foldableRegions(lines)
        assertEquals(listOf(1 to 6, 2 to 4, 3 to 4, 7 to 9), regions.map { it.header to it.end })
        assertNull(SourceStructure.regionAt(lines, 0))   // 次の行が深くない
        assertNull(SourceStructure.regionAt(lines, 5))   // 空行
    }

    @Test
    fun `fold view hides the region and maps display lines back to the original`() {
        val view = SourceStructure.foldView(text, setOf(1, 3))   // 3 は 1 の中なので外側だけ畳む
        assertEquals(1, view.hidden.size)
        val h = view.hidden.single()
        assertEquals("items:".length + "id: A\n".length, h.headerEnd)
        assertEquals(5, h.lines)
        assertEquals(text.substring(h.from, h.to), "  - name: x\n    tags:\n      - a\n\n  - name: y\n")
        assertEquals(listOf(0, 1, 7, 8, 9, 10), view.displayToOriginal.toList())
        assertEquals(7, view.originalLine(2))
        assertEquals(2, view.displayLine(7))
        assertEquals(1, view.displayLine(4))   // 隠れた行は見出しの表示行へ
        assertTrue(SourceStructure.isHidden(view, h.from + 3))
        assertTrue(!SourceStructure.isHidden(view, h.headerEnd))
        assertEquals("id: A\nitems: ⋯ 5 行\nnote: |\n  two\n  lines\nend: 1", view.displayText)
        // 位置の写し: 隠れた範囲の後ろは印の長さと隠れた長さの差だけずれ、中は印の直後へ
        val noteOriginal = text.indexOf("note:")
        val noteDisplay = view.displayText.indexOf("note:")
        assertEquals(noteDisplay, view.displayOffset(noteOriginal))
        assertEquals(noteOriginal, view.originalOffset(noteDisplay))
        assertEquals(view.displayText.indexOf("\nnote"), view.displayOffset(h.from + 3))
        assertEquals(h.headerEnd, view.originalOffset(h.headerEnd + 2))   // 印の上
        assertEquals(3, view.displayOffset(3))
        // 最後の区画を畳んだとき
        val tail = SourceStructure.foldView("a:\n  b: 1\n  c: 2", setOf(0))
        assertEquals("a:".length, tail.hidden.single().headerEnd)
        assertEquals("a:\n  b: 1\n  c: 2".length, tail.hidden.single().to)
        assertEquals("a: ⋯ 2 行", tail.displayText)
    }

    @Test
    fun `diff marks mark added changed and deleted lines in current coordinates`() {
        val saved = "a\nb\nc\nd\n"
        assertEquals(mapOf(1 to SourceStructure.DiffMark.CHANGED), SourceStructure.diffMarks(saved, "a\nB\nc\nd\n"))
        assertEquals(
            mapOf(1 to SourceStructure.DiffMark.ADDED, 2 to SourceStructure.DiffMark.ADDED),
            SourceStructure.diffMarks(saved, "a\nx\ny\nb\nc\nd\n"),
        )
        assertEquals(mapOf(0 to SourceStructure.DiffMark.DELETED_AFTER), SourceStructure.diffMarks(saved, "a\nc\nd\n"))
        assertTrue(SourceStructure.diffMarks(saved, saved).isEmpty())
    }

    @Test
    fun `path of a node is found by identity`() {
        val root = YamlIO.parse("items:\n  - name: x\n  - name: y\n")
        val second = ((root as dev.toros.yde.yaml.model.YNode.Map).entries["items"] as dev.toros.yde.yaml.model.YNode.Seq).items[1]
        val name = (second as dev.toros.yde.yaml.model.YNode.Map).entries["name"]!!
        assertEquals(listOf("items", 1, "name"), SourceStructure.pathOfNode(root, name))
        assertNull(SourceStructure.pathOfNode(root, dev.toros.yde.yaml.model.YNode.Scalar("z")))
    }

    @Test
    fun `format selection normalizes only the selected block and keeps its depth`() {
        val t = "a:\n  b:   1\n  c: {x: 1,   y: 2}\nd: 2"
        val r = SourceStructure.formatSelection(t, 3, 24)   // 2〜3 行目
        assertEquals("a:\n  b: 1\n  c: {x: 1, y: 2}\nd: 2", r!!.text)
        assertEquals("  b: 1\n  c: {x: 1, y: 2}", r.text.substring(r.start, r.end))
        // 塊になっていない（途中で切れている）範囲は整形しない
        assertNull(SourceStructure.formatSelection("a:\n  b: [1,\n  2]\n", 3, 10))
    }
}
