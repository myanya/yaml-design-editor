package dev.toros.yde.app.ui

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AiProposalTest {

    private fun yaml(text: String): YNode = YamlIO.parse(text.trimIndent())
    private fun dump(node: YNode): String = YamlIO.dump(node)

    // ---------------------------------------------------------------- combineProposal

    @Test
    fun `append keeps existing array items before generated ones`() {
        val current = yaml("- a\n- b")
        val generated = yaml("- c")
        val combined = combineProposal(current, generated, append = true, wholeDocument = false)
        assertEquals(dump(yaml("- a\n- b\n- c")), dump(combined))
    }

    @Test
    fun `replace mode discards existing array items`() {
        val current = yaml("- a\n- b")
        val generated = yaml("- c")
        val combined = combineProposal(current, generated, append = false, wholeDocument = false)
        assertEquals(dump(generated), dump(combined))
    }

    @Test
    fun `whole document proposal ignores append flag`() {
        val current = yaml("- a")
        val generated = yaml("title: x")
        val combined = combineProposal(current, generated, append = true, wholeDocument = true)
        assertEquals(dump(generated), dump(combined))
    }

    @Test
    fun `append against missing current value returns generated`() {
        val generated = yaml("- c")
        val combined = combineProposal(null, generated, append = true, wholeDocument = false)
        assertEquals(dump(generated), dump(combined))
    }

    // ---------------------------------------------------------------- combineProposal（キー単位マージ。R24-02）

    @Test
    fun `merge keeps non-generated keys and key order`() {
        val current = yaml("id: REQ-1\ntitle: 旧\nowner: 山田")
        val generated = yaml("title: 新\nsummary: 追加")
        val combined = combineProposal(current, generated, append = false, wholeDocument = false, mergeObjectKeys = true)
        // 非生成キー（id・owner）とキー順を保持し、生成キーだけ上書き・新キー（summary）は末尾
        assertEquals(dump(yaml("id: REQ-1\ntitle: 新\nowner: 山田\nsummary: 追加")), dump(combined))
    }

    @Test
    fun `merge replaces when current is not a map`() {
        val generated = yaml("title: 新")
        val combined = combineProposal(YNode.Scalar("文字列"), generated, append = false, wholeDocument = false, mergeObjectKeys = true)
        assertEquals(dump(generated), dump(combined))
    }

    @Test
    fun `merge with missing current returns generated`() {
        val generated = yaml("title: 新")
        val combined = combineProposal(null, generated, append = false, wholeDocument = false, mergeObjectKeys = true)
        assertEquals(dump(generated), dump(combined))
    }

    // ---------------------------------------------------------------- replaceInClone

    @Test
    fun `replaces nested map value without mutating original`() {
        val root = yaml(
            """
            meta:
              title: old
            body: text
            """
        )
        val result = replaceInClone(root, listOf("meta", "title"), YNode.Scalar("new"), preserveDocumentMetadata = false)
        assertEquals(dump(yaml("meta:\n  title: new\nbody: text")), dump(result))
        assertEquals(dump(yaml("meta:\n  title: old\nbody: text")), dump(root))
    }

    @Test
    fun `replaces sequence element by index`() {
        val root = yaml("items:\n- a\n- b")
        val result = replaceInClone(root, listOf("items", 1), YNode.Scalar("z"), preserveDocumentMetadata = false)
        assertEquals(dump(yaml("items:\n- a\n- z")), dump(result))
    }

    @Test
    fun `out of range index fails`() {
        val root = yaml("items:\n- a")
        assertFailsWith<IllegalStateException> {
            replaceInClone(root, listOf("items", 5), YNode.Scalar("z"), preserveDocumentMetadata = false)
        }
    }

    @Test
    fun `missing parent fails`() {
        val root = yaml("a: 1")
        assertFailsWith<IllegalStateException> {
            replaceInClone(root, listOf("missing", "key"), YNode.Scalar("z"), preserveDocumentMetadata = false)
        }
    }

    @Test
    fun `whole document replacement preserves existing _document metadata`() {
        val root = yaml(
            """
            _document:
              owner: yamada
            title: old
            """
        )
        val generated = yaml(
            """
            _document:
              owner: ai-fabricated
            title: new
            body: generated
            """
        )
        val result = replaceInClone(root, emptyList(), generated, preserveDocumentMetadata = true)
        val expected = yaml(
            """
            _document:
              owner: yamada
            title: new
            body: generated
            """
        )
        assertEquals(dump(expected), dump(result))
    }

    @Test
    fun `whole document replacement without existing metadata keeps generated as is`() {
        val root = yaml("title: old")
        val generated = yaml("title: new")
        val result = replaceInClone(root, emptyList(), generated, preserveDocumentMetadata = true)
        assertEquals(dump(generated), dump(result))
    }
}
