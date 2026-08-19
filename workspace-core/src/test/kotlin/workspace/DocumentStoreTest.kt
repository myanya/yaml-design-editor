package workspace

import model.DocumentId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 永続化ポート（DocumentStore）の契約テスト（Phase 7・WS-001）。メモリ実装とファイル実装で同じ契約を固定する。 */
class DocumentStoreTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("ws-store")
    }

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun stores(): List<Pair<String, DocumentStore>> = listOf(
        "InMemory" to InMemoryDocumentStore(),
        "FileSystem" to FileSystemDocumentStore(dir),
    )

    @Test
    fun `書いた文書を読めてlistに載る`() {
        for ((name, store) in stores()) {
            store.write(DocumentId("docs/a.yaml"), "id: A-1\n")
            assertEquals("id: A-1\n", store.read(DocumentId("docs/a.yaml"))?.text, name)
            assertEquals(listOf("docs/a.yaml"), store.list().map { it.id.value }, name)
        }
    }

    @Test
    fun `存在しない文書のreadはnullになる`() {
        for ((name, store) in stores()) {
            assertNull(store.read(DocumentId("missing.yaml")), name)
        }
    }

    @Test
    fun `moveで移動しdeleteで消える`() {
        for ((name, store) in stores()) {
            store.write(DocumentId("a.yaml"), "x: 1\n")
            store.move(DocumentId("a.yaml"), DocumentId("sub/b.yaml"))
            assertNull(store.read(DocumentId("a.yaml")), name)
            assertEquals("x: 1\n", store.read(DocumentId("sub/b.yaml"))?.text, name)
            store.delete(DocumentId("sub/b.yaml"))
            assertEquals(emptyList(), store.list(), name)
        }
    }

    @Test
    fun `存在しない削除・移動元・既存の移動先はエラーになる`() {
        for ((name, store) in stores()) {
            assertFailsWith<IllegalArgumentException>(name) { store.delete(DocumentId("missing.yaml")) }
            assertFailsWith<IllegalArgumentException>(name) { store.move(DocumentId("missing.yaml"), DocumentId("b.yaml")) }
            store.write(DocumentId("a.yaml"), "1")
            store.write(DocumentId("b.yaml"), "2")
            assertFailsWith<IllegalArgumentException>(name) { store.move(DocumentId("a.yaml"), DocumentId("b.yaml")) }
        }
    }

    @Test
    fun `既存文書への上書き書込ができる`() {
        for ((name, store) in stores()) {
            store.write(DocumentId("a.yaml"), "v: 1\n")
            store.write(DocumentId("a.yaml"), "v: 2\n")
            assertEquals("v: 2\n", store.read(DocumentId("a.yaml"))?.text, name)
            assertEquals(1, store.list().size, name)
        }
    }

    @Test
    fun `YAML以外はlistに載らない`() {
        val store = FileSystemDocumentStore(dir)
        store.write(DocumentId("a.yaml"), "1")
        Files.writeString(dir.resolve("note.md"), "メモ")
        assertEquals(listOf("a.yaml"), store.list().map { it.id.value })
    }

    @Test
    fun `ワークスペース外への参照は拒否される`() {
        val store = FileSystemDocumentStore(dir.resolve("ws").also { Files.createDirectories(it) })
        assertFailsWith<IllegalArgumentException> { store.read(DocumentId("../outside.yaml")) }
        assertFailsWith<IllegalArgumentException> { store.write(DocumentId("../outside.yaml"), "x") }
        assertFailsWith<IllegalArgumentException> { store.write(DocumentId("a/../../outside.yaml"), "x") }
    }

    @Test
    fun `InMemoryは書込回数を数える`() {
        val store = InMemoryDocumentStore()
        assertEquals(0, store.writeCount)
        store.write(DocumentId("a.yaml"), "1")
        store.write(DocumentId("a.yaml"), "2")
        store.delete(DocumentId("a.yaml"))
        assertEquals(3, store.writeCount, "applyが書かないことの検証（Phase 8）に使うカウンタ")
    }
}
