package dev.toros.yde.desktop.ui.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.toros.yde.desktop.ui.common.charDiffRanges
import dev.toros.yde.desktop.ui.schema.scalar

/** R8-04: ソースエディタの構文ハイライト・置換の純ロジック。 */
class SourceHighlightTest {

    private fun tokensOf(text: String): List<Pair<String, YamlTokenType>> =
        yamlHighlightTokens(text).map { text.substring(it.start, it.end) to it.type }

    @Test
    fun `keys comments and values are classified`() {
        val text = "title: sample # メモ\ncount: 42\nflag: true\nquoted: \"hello\"\n"
        val tokens = tokensOf(text)
        assertTrue("title:" to YamlTokenType.KEY in tokens)
        assertTrue("# メモ" to YamlTokenType.COMMENT in tokens)
        assertTrue("count:" to YamlTokenType.KEY in tokens)
        assertTrue("42" to YamlTokenType.NUMBER in tokens)
        assertTrue("true" to YamlTokenType.KEYWORD in tokens)
        assertTrue("\"hello\"" to YamlTokenType.STRING in tokens)
    }

    @Test
    fun `list items and document separators are indicators`() {
        val text = "---\nitems:\n  - id: a\n  - plain\n"
        val tokens = tokensOf(text)
        assertTrue("---" to YamlTokenType.INDICATOR in tokens)
        assertTrue("-" to YamlTokenType.INDICATOR in tokens)
        assertTrue("id:" to YamlTokenType.KEY in tokens)
    }

    @Test
    fun `block scalar content is treated as string not keys`() {
        val text = "note: |\n  key: value に見える行\n  2行目\nnext: 1\n"
        val tokens = tokensOf(text)
        // ブロックスカラー内の「key:」はキーとして扱わない
        assertTrue(tokens.none { it.first == "key:" && it.second == YamlTokenType.KEY })
        assertTrue("next:" to YamlTokenType.KEY in tokens)
        assertTrue(tokens.any { it.second == YamlTokenType.STRING && "に見える行" in it.first })
    }

    @Test
    fun `hash inside quotes is not a comment`() {
        assertEquals(-1, findCommentStart("""title: "a # b""""))
        assertEquals(9, findCommentStart("title: x # c"))
        assertEquals(0, findCommentStart("# 行頭"))
        assertEquals(-1, findCommentStart("title: a#b"))  // 空白の直後でない # はコメントではない
    }

    @Test
    fun `char diff ranges isolate the changed middle`() {
        val (left, right) = charDiffRanges("status: open", "status: closed")
        assertEquals("open", "status: open".substring(left.first, left.last + 1))
        assertEquals("closed", "status: closed".substring(right.first, right.last + 1))
        // 挿入だけの場合、削除側は空レンジ
        val (l2, r2) = charDiffRanges("ab", "axb")
        assertTrue(l2.isEmpty())
        assertEquals("x", "axb".substring(r2.first, r2.last + 1))
    }
}
