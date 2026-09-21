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
    fun `dirty は 保存した内容との比較で決まる`() {
        // 履歴（Undo / Redo）は持たない——履歴の正は、カーネルのセッション（P27 段D の D-d・意図的差分 D-37。
        // 連続入力の合体と Undo は workspace-core の WorkspaceSessionTest が保証する）
        val file = dir.resolve("docs/a.yaml")
        val buffer = EditBuffer.load(file)
        assertFalse(buffer.dirty)

        (buffer.root as YNode.Map).entries["title"] = YNode.Scalar("編集1")
        assertTrue(buffer.dirty)
        // 元の内容へ戻せば、dirty ではない
        (buffer.root as YNode.Map).entries["title"] = YNode.Scalar("文書A")
        assertFalse(buffer.dirty)

        (buffer.root as YNode.Map).entries["title"] = YNode.Scalar("編集2")
        buffer.markSaved()
        assertFalse(buffer.dirty)
    }

    @Test
    fun `multi document text keeps trailing documents through source edit`() {
        val file = dir.resolve("docs/multi.yaml")
        Files.writeString(file, "id: DOC-M\ntitle: 先頭\n---\nsecond: true\n")
        val buffer = EditBuffer.load(file)
        assertEquals(1, buffer.extraDocs.size)
        buffer.applySourceText("id: DOC-M\ntitle: 編集済み\n---\nsecond: true\n")
        assertTrue(buffer.dirty)
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
