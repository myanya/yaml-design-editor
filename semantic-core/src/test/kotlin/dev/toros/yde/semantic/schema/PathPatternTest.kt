package dev.toros.yde.semantic.schema

import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.parseYPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `[*]`（任意の番号）を含むパスのパターン（PathPattern）の読み取り・照合・展開のテスト。 */
class PathPatternTest {

    @Test
    fun `任意の番号を含むパスを読み書きし具体的なパスと照合できる`() {
        val pattern = PathPattern.parse("comments[*].date")
        assertEquals(listOf("comments", AnyIndex, "date"), pattern)
        assertEquals("comments[*].date", PathPattern.pretty(pattern))
        assertTrue(PathPattern.hasWildcard(pattern))
        // 番号を書いたパスはそのまま（`[*]` が無ければ 1 つの場所）
        assertEquals(listOf("a", 0, "b"), PathPattern.parse("a[0].b"))
        assertFalse(PathPattern.hasWildcard(PathPattern.parse("a[0].b")))
        assertEquals("a[0].b", PathPattern.pretty(PathPattern.parse("a[0].b")))
        assertTrue(PathPattern.matches(pattern, parseYPath("comments[3].date")))
        assertFalse(PathPattern.matches(pattern, parseYPath("comments.date")))
        assertFalse(PathPattern.matches(pattern, parseYPath("comments[3]")))
    }

    @Test
    fun `展開は配列の全要素に広がり固定の区切りは木に無くても場所になる`() {
        val tree = YamlIO.parse("comments:\n  - { date: a }\n  - { body: b }\nnote: x")
        assertEquals(
            listOf(parseYPath("comments[0].date"), parseYPath("comments[1].date")),
            PathPattern.expand(tree, PathPattern.parse("comments[*].date")),
        )
        // `[*]` の場所が配列でなければ 0 件（書かれていない・スカラー）
        assertEquals(emptyList(), PathPattern.expand(tree, PathPattern.parse("missing[*].date")))
        assertEquals(emptyList(), PathPattern.expand(tree, PathPattern.parse("note[*]")))
        // 固定の区切りは、木に無くても場所として返す（値が「書かれていない」ことを呼び出し側が場所ごとに扱う）
        assertEquals(listOf(parseYPath("missing.date")), PathPattern.expand(tree, PathPattern.parse("missing.date")))
        assertEquals(listOf(parseYPath("comments[5].date")), PathPattern.expand(tree, PathPattern.parse("comments[5].date")))
        // 入れ子の配列にも広がる（木の順）
        val nested = YamlIO.parse("a:\n  - b: [{ c: 1 }, { c: 2 }]\n  - b: [{ c: 3 }]")
        assertEquals(
            listOf(parseYPath("a[0].b[0].c"), parseYPath("a[0].b[1].c"), parseYPath("a[1].b[0].c")),
            PathPattern.expand(nested, PathPattern.parse("a[*].b[*].c")),
        )
    }
}
