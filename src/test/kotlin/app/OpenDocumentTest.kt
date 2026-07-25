package app

import model.YNode
import yaml.YamlIO
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R8-05: 複数ドキュメント（`---` 区切り）YAML の編集・保持（L-02）。 */
class OpenDocumentTest {

    private fun openMultiDoc(text: String): OpenDocument {
        val docs = YamlIO.parseAll(text)
        return OpenDocument(
            Path.of("multi.yaml"), docs.firstOrNull() ?: YNode.Map(), null, text, null,
            extraDocs = docs.drop(1),
        )
    }

    @Test
    fun `first document is edited and following documents are preserved`() {
        val doc = openMultiDoc("a: 1\n---\nb: 2\n---\nc: 3\n")
        assertFalse(doc.dirty, "開いた直後は dirty でない")
        // 先頭ドキュメントを編集しても後続は保存テキストに残る
        ((doc.root as YNode.Map).entries["a"] as YNode.Scalar).value = 99
        doc.markEdited()
        assertTrue(doc.dirty)
        assertEquals("a: 99\n---\nb: 2\n---\nc: 3\n", doc.canonicalText())
    }

    @Test
    fun `applySourceText reparses all documents`() {
        val doc = openMultiDoc("a: 1\n---\nb: 2\n")
        doc.applySourceText("a: 10\n---\nb: 20\n---\nadded: true\n")
        assertEquals(2, doc.extraDocs.size)
        assertEquals("a: 10\n---\nb: 20\n---\nadded: true\n", doc.canonicalText())
        // 単一ドキュメントへ戻すと後続は消える（ソース編集による明示的な削除）
        doc.applySourceText("only: 1\n")
        assertEquals(0, doc.extraDocs.size)
        assertEquals("only: 1\n", doc.canonicalText())
    }

    @Test
    fun `undo restores following documents too`() {
        val doc = openMultiDoc("a: 1\n---\nb: 2\n")
        doc.applySourceText("a: 1\n")
        assertEquals("a: 1\n", doc.canonicalText())
        doc.undo()
        assertEquals("a: 1\n---\nb: 2\n", doc.canonicalText())
    }
}
