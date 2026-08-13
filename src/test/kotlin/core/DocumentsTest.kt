package core

import model.YNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 文書キャッシュ層の中核（R9-02）のテスト。
 * app.OpenDocument から core へ抽出した EditBuffer（履歴・dirty・複数ドキュメント）と、
 * WorkspaceRecords（一覧・一括編集）、Workspace.noteDiskChanges（増分／全再構築の振り分け）を検証する。
 */
class DocumentsTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-docs")
        write(
            "_schemas/doc.yaml",
            """
            id: doc
            name: 文書
            match: "docs/**/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: title, type: string }
                - { key: status, type: string }
            """,
        )
        write("docs/a.yaml", "id: DOC-A\ntitle: 文書A\nstatus: open")
        write("docs/b.yaml", "id: DOC-B\nname: 文書B")
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun write(rel: String, content: String) {
        val file = dir.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent() + "\n")
    }

    // ---------------------------------------------------------------- EditBuffer

    @Test
    fun `edit history supports undo redo and dirty tracking`() {
        val file = dir.resolve("docs/a.yaml")
        val buffer = EditBuffer.load(file)
        assertFalse(buffer.dirty)

        (buffer.root as YNode.Map).entries["title"] = YNode.Scalar("編集1")
        buffer.recordEdit()
        assertTrue(buffer.dirty)
        (buffer.root as YNode.Map).entries["title"] = YNode.Scalar("編集2")
        buffer.recordEdit()

        assertTrue(buffer.undo())
        assertEquals("編集1", ((buffer.root as YNode.Map).entries["title"] as YNode.Scalar).value)
        assertTrue(buffer.undo())
        assertEquals("文書A", ((buffer.root as YNode.Map).entries["title"] as YNode.Scalar).value)
        assertFalse(buffer.dirty)   // 初期状態まで戻れば dirty ではない
        assertFalse(buffer.undo())  // それ以上は戻れない
        assertTrue(buffer.redo())
        assertEquals("編集1", ((buffer.root as YNode.Map).entries["title"] as YNode.Scalar).value)
        assertTrue(buffer.dirty)

        buffer.markSaved()
        assertFalse(buffer.dirty)
    }

    @Test
    fun `consecutive edits with same coalesce key merge into one history entry`() {
        // 履歴の合体（UX-11。ARCHITECTURE §4.34）: 同一キー・時間窓内の連続入力は Undo 1回で戻る
        val file = dir.resolve("docs/a.yaml")
        val buffer = EditBuffer.load(file)
        val title = (buffer.root as YNode.Map).entries["title"] as YNode.Scalar

        title.value = "1"
        buffer.recordEdit(coalesceKey = title, nowMillis = 1000)
        title.value = "12"
        buffer.recordEdit(coalesceKey = title, nowMillis = 1300)
        title.value = "120"
        buffer.recordEdit(coalesceKey = title, nowMillis = 1600)

        assertTrue(buffer.undo())
        assertEquals("文書A", ((buffer.root as YNode.Map).entries["title"] as YNode.Scalar).value, "タイプ3回が1エントリに合体")
        assertFalse(buffer.undo())
        // Redo でまとめて先端へ戻る
        assertTrue(buffer.redo())
        assertEquals("120", ((buffer.root as YNode.Map).entries["title"] as YNode.Scalar).value)
    }

    @Test
    fun `coalesce breaks on different key window timeout and after undo`() {
        val file = dir.resolve("docs/a.yaml")
        val buffer = EditBuffer.load(file)
        fun root() = buffer.root as YNode.Map
        fun value(key: String) = (root().entries[key] as YNode.Scalar).value

        // 別フィールド（別キー）へ移ると境界になる
        val title = root().entries["title"] as YNode.Scalar
        val status = root().entries["status"] as YNode.Scalar
        title.value = "T1"
        buffer.recordEdit(coalesceKey = title, nowMillis = 1000)
        status.value = "done"
        buffer.recordEdit(coalesceKey = status, nowMillis = 1200)
        assertTrue(buffer.undo())
        assertEquals("open", value("status"), "キーが変わればフィールド単位で戻る")
        assertEquals("T1", value("title"))

        // 時間窓（COALESCE_WINDOW_MS）を超えると同一キーでも境界になる
        val buffer2 = EditBuffer.load(file)
        val t2 = (buffer2.root as YNode.Map).entries["title"] as YNode.Scalar
        t2.value = "A"
        buffer2.recordEdit(coalesceKey = t2, nowMillis = 1000)
        t2.value = "AB"
        buffer2.recordEdit(coalesceKey = t2, nowMillis = 1000 + EditBuffer.COALESCE_WINDOW_MS + 1)
        assertTrue(buffer2.undo())
        assertEquals("A", ((buffer2.root as YNode.Map).entries["title"] as YNode.Scalar).value)

        // Undo 直後の編集は復元状態に合体しない（キーはリセットされる）
        val buffer3 = EditBuffer.load(file)
        val t3 = (buffer3.root as YNode.Map).entries["title"] as YNode.Scalar
        t3.value = "X"
        buffer3.recordEdit(coalesceKey = t3, nowMillis = 1000)
        assertTrue(buffer3.undo())
        val restored = (buffer3.root as YNode.Map).entries["title"] as YNode.Scalar
        restored.value = "Y"
        buffer3.recordEdit(coalesceKey = restored, nowMillis = 1100)
        assertTrue(buffer3.undo())
        assertEquals("文書A", ((buffer3.root as YNode.Map).entries["title"] as YNode.Scalar).value)
    }

    @Test
    fun `multi document text keeps trailing documents through source edit`() {
        val file = dir.resolve("docs/multi.yaml")
        Files.writeString(file, "id: DOC-M\ntitle: 先頭\n---\nsecond: true\n")
        val buffer = EditBuffer.load(file)
        assertEquals(1, buffer.extraDocs.size)
        buffer.applySourceText("id: DOC-M\ntitle: 編集済み\n---\nsecond: true\n")
        buffer.recordEdit()
        assertTrue("second: true" in buffer.canonicalText())
        assertTrue("編集済み" in buffer.canonicalText())
    }

    // ---------------------------------------------------------------- WorkspaceRecords

    @Test
    fun `records prefer unsaved trees via injected loader`() {
        val ws = Workspace.open(dir)
        val edited = YNode.Map(linkedMapOf<String, YNode>(
            "id" to YNode.Scalar("DOC-A"),
            "title" to YNode.Scalar("未保存のタイトル"),
        ))
        val records = WorkspaceRecords.collect(ws) { f ->
            if (f.fileName.toString() == "a.yaml") edited
            else runCatching { yaml.YamlIO.parseFirst(Files.readString(f)) }.getOrNull()
        }
        // スキーマ文書（_schemas）は一覧に含まれない
        assertEquals(listOf("DOC-A", "DOC-B"), records.map { it.id })
        assertEquals("未保存のタイトル", records.first { it.id == "DOC-A" }.title)
        assertEquals("文書B", records.first { it.id == "DOC-B" }.title)   // name もタイトルとして拾う
        assertEquals("文書", records.first { it.id == "DOC-A" }.kind)     // スキーマ名
    }

    @Test
    fun `bulk key candidates and root scalar update`() {
        val ws = Workspace.open(dir)
        val loader = { f: Path -> runCatching { yaml.YamlIO.parseFirst(Files.readString(f)) }.getOrNull() }
        val keys = WorkspaceRecords.bulkKeyCandidates(ws, loader)
        assertTrue("title" in keys && "status" in keys)
        assertFalse("id" in keys)

        val count = WorkspaceRecords.updateRootScalar(listOf(dir.resolve("docs/a.yaml"), dir.resolve("docs/b.yaml")), "status", "done")
        assertEquals(2, count)
        assertTrue("status: done" in Files.readString(dir.resolve("docs/a.yaml")))
        assertTrue("status: done" in Files.readString(dir.resolve("docs/b.yaml")))
    }

    // ---------------------------------------------------------------- noteDiskChanges（R9-02）

    @Test
    fun `noteDiskChanges updates the index incrementally and rebuilds on schema change`() {
        val ws = Workspace.open(dir)
        assertTrue("DOC-A" in ws.index.entries)

        // 通常文書の変更 → 増分更新でIDが差し替わる
        write("docs/a.yaml", "id: DOC-A2\ntitle: 改名")
        ws.noteDiskChanges(listOf(dir.resolve("docs/a.yaml")))
        assertTrue("DOC-A2" in ws.index.entries)
        assertFalse("DOC-A" in ws.index.entries)

        // 新規ファイル → ファイル一覧にも反映される
        write("docs/c.yaml", "id: DOC-C")
        ws.noteDiskChanges(listOf(dir.resolve("docs/c.yaml")))
        assertTrue(ws.files.any { it.fileName.toString() == "c.yaml" })
        assertTrue("DOC-C" in ws.index.entries)

        // 削除 → 一覧・索引から消える
        Files.delete(dir.resolve("docs/c.yaml"))
        ws.noteDiskChanges(emptyList(), removed = listOf(dir.resolve("docs/c.yaml")))
        assertFalse(ws.files.any { it.fileName.toString() == "c.yaml" })
        assertFalse("DOC-C" in ws.index.entries)

        // スキーマ変更 → 全再構築（スキーマ・走査規則も読み直される）
        write(
            "_schemas/doc.yaml",
            """
            id: doc
            name: 文書（改）
            match: "docs/**/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
            """,
        )
        ws.noteDiskChanges(listOf(dir.resolve("_schemas/doc.yaml")))
        // 組込みスキーマ（builtin-*）も混ざるため、対象スキーマだけを確認する
        assertEquals("文書（改）", ws.schemas.first { it.id == "doc" }.name)
    }
}
