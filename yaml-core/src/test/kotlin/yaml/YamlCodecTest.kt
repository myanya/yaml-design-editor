package yaml

import model.DocumentId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** YamlCodec / YDocument / DocumentId の契約（3層コア移行 Phase 2・YM-003）。 */
class YamlCodecTest {

    private fun fixture(name: String): String {
        val root = System.getProperty("fixtures.root")
            ?: error("システムプロパティ fixtures.root が未設定（yaml-core/build.gradle.kts が渡す）")
        return Files.readString(Path.of(root, "yaml", name))
    }

    @Test
    fun `parseはDocumentIdと全ドキュメントを保持する`() {
        val doc = DefaultYamlCodec.parse(DocumentId("fx/multidoc.yaml"), fixture("multidoc.yaml"))
        assertEquals("fx/multidoc.yaml", doc.id.value)
        assertEquals(3, doc.documents.size)
    }

    @Test
    fun `dumpとparseの往復で全ドキュメントのplain treeが一致する`() {
        val id = DocumentId("fx/multidoc.yaml")
        val doc = DefaultYamlCodec.parse(id, fixture("multidoc.yaml"))
        val out = DefaultYamlCodec.dump(doc)
        val again = DefaultYamlCodec.parse(id, out)
        assertEquals(doc.documents.size, again.documents.size)
        doc.documents.zip(again.documents).forEach { (a, b) ->
            assertEquals(YamlIO.dumpPlain(a), YamlIO.dumpPlain(b))
        }
    }

    @Test
    fun `コメントと引用符スタイルはcodec経由でも保持される`() {
        val id = DocumentId("fx/comments.yaml")
        val out = DefaultYamlCodec.dump(DefaultYamlCodec.parse(id, fixture("comments.yaml")))
        assertTrue("ファイル先頭のコメント" in out)
        assertTrue("\"true\"" in out)
        assertTrue("'シングルクォート'" in out)
    }

    @Test
    fun `空テキストは空のドキュメント列になる`() {
        assertEquals(0, DefaultYamlCodec.parse(DocumentId("empty"), "").documents.size)
    }

    @Test
    fun `空白だけのDocumentIdは拒否される`() {
        assertFailsWith<IllegalArgumentException> { DocumentId("  ") }
    }

    @Test
    fun `構文エラーは例外として伝わる`() {
        assertFailsWith<Exception> {
            DefaultYamlCodec.parse(DocumentId("broken"), "a: [unclosed")
        }
    }
}
