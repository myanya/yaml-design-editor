package dev.toros.yde.workspace

import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 永続化ポート（DocumentStore）の契約テスト（Phase 7・WS-001）。メモリ実装とファイル実装で同じ契約を固定する。 */
class DocumentStoreTest {

    @Test
    fun `外部へのディレクトリリンクは読取と新規書込と移動を拒否する`() {
        val root = Files.createDirectory(dir.resolve("workspace"))
        val outside = Files.createDirectory(dir.resolve("outside"))
        val marker = Files.writeString(outside.resolve("marker.yaml"), "original")
        val link = root.resolve("linked")
        if (System.getProperty("os.name").startsWith("Windows")) {
            val process = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), outside.toString())
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
        } else Files.createSymbolicLink(link, outside)
        try {
            val store = FileSystemDocumentStore(root)
            assertFailsWith<IllegalArgumentException> { store.read(DocumentId("linked/marker.yaml")) }
            assertFailsWith<IllegalArgumentException> { store.write(DocumentId("linked/new/deep.yaml"), "changed") }
            assertFailsWith<IllegalArgumentException> { store.delete(DocumentId("linked/marker.yaml")) }
            store.write(DocumentId("inside.yaml"), "inside")
            assertEquals(mapOf(DocumentId("inside.yaml") to StoredContent("inside"), DocumentId("none.yaml") to null),
                store.readAll(listOf(DocumentId("inside.yaml"), DocumentId("none.yaml"))))
            assertFailsWith<IllegalArgumentException>("まとめて読んでも文書ごとに実パスを確かめる") {
                store.readAll(listOf(DocumentId("inside.yaml"), DocumentId("linked/marker.yaml")))
            }
            assertFailsWith<IllegalArgumentException> { store.move(DocumentId("inside.yaml"), DocumentId("linked/moved.yaml")) }
            assertEquals("original", Files.readString(marker))
            assertEquals(listOf("inside.yaml"), store.list().map { it.id.value })
            assertEquals(setOf(DocumentId("inside.yaml")), store.capture().texts.keys)
        } finally {
            Files.delete(link)
        }
    }

    private fun linkDirectory(link: Path, target: Path) {
        if (System.getProperty("os.name").startsWith("Windows")) {
            val process = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
        } else Files.createSymbolicLink(link, target)
    }

    @Test
    fun `読込の境界は親リンクの差替えを毎回検査する`() {
        val root = Files.createDirectory(dir.resolve("workspace"))
        val inside = Files.createDirectory(root.resolve("inside"))
        val outside = Files.createDirectory(dir.resolve("outside"))
        Files.writeString(inside.resolve("a.yaml"), "inside")
        val marker = Files.writeString(outside.resolve("a.yaml"), "outside")
        val link = root.resolve("linked")
        linkDirectory(link, inside)
        try {
            val boundary = WorkspacePaths.ReadBoundary(root)
            assertEquals("inside", Files.readString(boundary.resolve("linked/a.yaml")))
            Files.delete(link)
            linkDirectory(link, outside)
            assertFailsWith<IllegalArgumentException> { boundary.resolve("linked/a.yaml") }
            assertFailsWith<IllegalArgumentException> { boundary.resolve("linked/new/deep.yaml") }
            val store = FileSystemDocumentStore(root)
            assertFailsWith<IllegalArgumentException> { store.write(DocumentId("linked/a.yaml"), "changed") }
            assertEquals("outside", Files.readString(marker))
            assertTrue(store.capture().texts.keys.none { it.value.startsWith("linked/") })
        } finally { Files.delete(link) }
    }

    @Test
    fun `読込中のルート差替えも開始時の境界の外なら拒否する`() {
        val root = Files.createDirectory(dir.resolve("workspace"))
        val outside = Files.createDirectory(dir.resolve("outside"))
        Files.writeString(root.resolve("a.yaml"), "inside")
        Files.writeString(outside.resolve("a.yaml"), "outside")
        val boundary = WorkspacePaths.ReadBoundary(root)
        val moved = dir.resolve("original")
        Files.move(root, moved)
        linkDirectory(root, outside)
        try {
            assertFailsWith<IllegalArgumentException> { boundary.resolve("a.yaml") }
        } finally { Files.delete(root) }
    }

    @Test
    fun `全読込の一覧は構成YAMLを含め隠しMarkdownと領域外を含めない`() {
        Files.createDirectories(dir.resolve(".yde/schemas"))
        Files.createDirectories(dir.resolve(".hidden"))
        Files.writeString(dir.resolve("a.yaml"), "a: 1")
        Files.writeString(dir.resolve(".yde/schemas/a.yaml"), "id: a")
        Files.writeString(dir.resolve(".hidden/private.yaml"), "private")
        Files.writeString(dir.resolve("README.MD"), "visible")
        Files.writeString(dir.resolve(".yde/private.md"), "hidden")
        val captured = FileSystemDocumentStore(dir).capture()
        assertEquals(setOf(DocumentId("a.yaml"), DocumentId(".yde/schemas/a.yaml")), captured.texts.keys)
        assertEquals(captured.texts.keys, captured.listing.yamlTimes.keys)
        assertEquals(listOf(DocumentId("README.MD")), captured.listing.markdown)
        assertEquals(Files.getLastModifiedTime(dir.resolve("a.yaml")).toMillis(), captured.listing.yamlTimes[DocumentId("a.yaml")])
    }

    @Test
    fun `全読込は外を指すYAMLのリンクを読まずに外し、開く処理を失敗にしない`() {
        val root = Files.createDirectory(dir.resolve("workspace"))
        val outside = Files.createDirectory(dir.resolve("outside"))
        Files.writeString(root.resolve("a.yaml"), "inside")
        Files.writeString(outside.resolve("secret.yaml"), "outside")
        // ファイルのリンクは Windows では権限が要るので、作れない環境では確かめない
        val linked = runCatching {
            Files.createSymbolicLink(root.resolve("out.yaml"), outside.resolve("secret.yaml"))
            Files.createSymbolicLink(root.resolve("in.yaml"), root.resolve("a.yaml"))
        }.isSuccess
        if (!linked) return
        val t = Timings("全読込")
        val captured = Timings.within(t) { FileSystemDocumentStore(root).capture() }
        assertEquals(setOf(DocumentId("a.yaml"), DocumentId("in.yaml")), captured.texts.keys)
        assertEquals(captured.texts.keys, captured.listing.yamlTimes.keys)
        assertEquals("inside", captured.texts.getValue(DocumentId("in.yaml")))
        // リンクでない YAML は一覧で確かめず、読む直前の 1 回だけ。リンクのファイルは一覧でも確かめ、外を指すものはそこで外れる
        val names = t.entries().map { it.name }
        assertTrue("一覧の実パスの確認（ファイル）（2 回の合計）" in names, names.toString())
        assertTrue("読む直前の実パスの確認（2 回の合計）" in names, names.toString())
    }

    @Test
    fun `全読込は1本で読んでも並列に読んでも同じ本文と一覧になる`() {
        repeat(40) { i ->
            val folder = Files.createDirectories(dir.resolve("items/g${i % 4}"))
            Files.writeString(folder.resolve("i$i.yaml"), "id: I-$i\n")
        }
        Files.writeString(dir.resolve("README.md"), "説明")
        val sequential = FileSystemDocumentStore(dir, readThreads = 1).capture()
        val parallel = FileSystemDocumentStore(dir, readThreads = 8).capture()
        assertEquals(40, sequential.texts.size)
        assertEquals(sequential.texts.toList(), parallel.texts.toList(), "本文と、その並び（一覧の順）が同じ")
        assertEquals(sequential.listing.yamlTimes.toList(), parallel.listing.yamlTimes.toList())
        assertEquals(sequential.listing.markdown, parallel.listing.markdown)
    }

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
