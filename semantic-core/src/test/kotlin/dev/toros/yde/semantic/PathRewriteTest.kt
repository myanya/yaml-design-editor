package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.globToRegex
import kotlin.test.*

class PathRewriteTest {
    @Test fun `部分移動では元のパターンに残る文書を除外しない`() {
        val pattern = PathRewrite.pattern("docs/**/*.{yaml,yml}", listOf(PathRewrite.Move("docs/a.yaml", "other/a.yaml", false)),
            listOf("docs/a.yaml", "docs/b.yml"))
        val regex = globToRegex(pattern)
        assertTrue(regex.matches("other/a.yaml"))
        assertTrue(regex.matches("docs/b.yml"))
        assertFalse(regex.matches("other/b.yaml"))
    }
    @Test fun `フォルダー全体は接頭辞を境界付きで移す`() {
        val moves = listOf(PathRewrite.Move("docs", "specs", true))
        assertEquals("specs/**/*.yaml", PathRewrite.pattern("docs/**/*.yaml", moves, emptyList()))
        assertEquals("docs-old/a.yaml", PathRewrite.relocated("docs-old/a.yaml", moves))
        assertEquals("../images/a.png", PathRewrite.relative("docs/a.yaml", "images/a.png"))
    }
}
