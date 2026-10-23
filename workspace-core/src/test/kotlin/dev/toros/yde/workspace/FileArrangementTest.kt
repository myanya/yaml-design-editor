package dev.toros.yde.workspace

import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Files
import kotlin.test.*

class FileArrangementTest {
    private val root = Files.createTempDirectory("arrangement")
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }
    private fun write(path: String, text: String) { val file = root.resolve(path); Files.createDirectories(file.parent); Files.writeString(file, text) }
    private fun read(path: String) = Files.readString(root.resolve(path))
    private fun session() = WorkspaceSession(FileSystemDocumentStore(root))

    @Test fun `フォルダーと一般ファイルと定義を一回で移動し履歴で戻す`() {
        write("_schemas/doc.yaml", "id: doc\nmatch: docs/**/*.yaml\nroot:\n  type: object\n  fields: []\n")
        write("_views/list.yaml", "id: list\nkind: document\nmatch: docs/**/*.yaml\n")
        write("docs/a.yaml", "name: A\n")
        write("docs/code.kt", "class A")
        write("README.md", "[A](docs/a.yaml)\n")
        val s = session()
        val plan = FileArrangementPlanner.plan(s, root, listOf(PathMove("docs", "specs")))
        assertTrue(plan.edits.any { it.after == "specs/**/*.yaml" })
        assertTrue(s.applyFileArrangement(plan).ok)
        assertFalse(Files.exists(root.resolve("docs")))
        assertEquals("class A", read("specs/code.kt"))
        assertContains(read("_schemas/doc.yaml"), "specs/**/*.yaml")
        assertContains(read("README.md"), "specs/a.yaml")
        assertTrue(s.undo().ok)
        assertTrue(Files.exists(root.resolve("docs/a.yaml")))
        assertContains(read("_schemas/doc.yaml"), "docs/**/*.yaml")
        assertContains(read("README.md"), "docs/a.yaml")
        assertTrue(s.redo().ok)
        assertTrue(Files.exists(root.resolve("specs/a.yaml")))
    }

    @Test fun `確認後の外部編集とUndo時の外部編集を上書きしない`() {
        write("a.txt", "old")
        val s = session()
        val plan = FileArrangementPlanner.plan(s, root, listOf(PathMove("a.txt", "b.txt")))
        write("a.txt", "changed")
        assertFalse(s.applyFileArrangement(plan).ok)
        assertEquals("changed", read("a.txt"))
        assertFalse(s.canUndo())
        val fresh = FileArrangementPlanner.plan(s, root, listOf(PathMove("a.txt", "b.txt")))
        assertTrue(s.applyFileArrangement(fresh).ok)
        write("b.txt", "external")
        assertFalse(s.undo().ok)
        assertTrue(s.canUndo())
        assertEquals("external", read("b.txt"))
    }

    @Test fun `削除した一般ファイルと空フォルダーを復元できる`() {
        write("docs/a.yaml", "name: A\n")
        Files.createDirectories(root.resolve("docs/empty"))
        write("docs/image.bin", "opaque")
        val s = session()
        val plan = FileArrangementPlanner.plan(s, root, listOf(PathMove("docs", ".yde-trash/test/docs")), deleting = true)
        assertTrue(s.applyFileArrangement(plan).ok)
        assertFalse(Files.exists(root.resolve("docs")))
        assertTrue(s.undo().ok)
        assertEquals("opaque", read("docs/image.bin"))
        assertTrue(Files.isDirectory(root.resolve("docs/empty")))
        assertTrue(s.redo().ok)
    }

    @Test fun `確認前の外部変更も保存済みの原文と照合して拒否する`() {
        write("_schemas/doc.yaml", "id: doc\nmatch: docs/**/*.yaml\nroot: {type: object}\n")
        write("docs/a.yaml", "name: A\n")
        val s = session()
        write("_schemas/doc.yaml", "id: doc\nmatch: docs/**/*.yaml\nroot: {type: object}\n# 外部編集\n")
        assertFailsWith<IllegalArgumentException> {
            FileArrangementPlanner.plan(s, root, listOf(PathMove("docs", "specs")))
        }
        assertContains(read("_schemas/doc.yaml"), "# 外部編集")
        assertTrue(Files.exists(root.resolve("docs/a.yaml")))
        assertFalse(s.canUndo())
    }

    @Test fun `フォルダー作成も履歴に入り空でなければ取り消さない`() {
        val s = session()
        assertTrue(s.applyFileArrangement(FileArrangementPlanner.plan(s, root, emptyList(), createDirectory = "new")).ok)
        assertTrue(s.undo().ok)
        assertTrue(s.redo().ok)
        write("new/file.txt", "outside")
        assertFalse(s.undo().ok)
        assertEquals("outside", read("new/file.txt"))
    }

    @Test fun `台帳範囲は明示採用した場合だけ追随しUndoで戻る`() {
        write(".yde/workspace.yaml", "ledger: [docs]\n")
        write("docs/a.yaml", "name: A\n")
        val s = session()
        val plan = FileArrangementPlanner.plan(s, root, listOf(PathMove("docs", "specs")), updatePermissions = true)
        assertTrue(plan.texts.isNotEmpty())
        val result = s.applyFileArrangement(plan)
        assertTrue(result.ok, result.message)
        assertTrue(s.layout.inLedger("specs/a.yaml"))
        assertTrue(s.undo().ok)
        assertTrue(s.layout.inLedger("docs/a.yaml"))
    }

    @Test fun `自分の配下や同名やワークスペース外への移動を拒否する`() {
        write("a/x.txt", "x")
        write("b.txt", "b")
        val s = session()
        assertFails { FileArrangementPlanner.plan(s, root, listOf(PathMove("a", "a/nested"))) }
        assertFails { FileArrangementPlanner.plan(s, root, listOf(PathMove("a/x.txt", "b.txt"))) }
        assertFails { FileArrangementPlanner.plan(s, root, listOf(PathMove("a/x.txt", "../outside"))) }
    }

    @Test fun `一般ファイルの部分移動でもfileGlobの元の対象範囲を残して追随する`() {
        write("_views/source.yaml", "id: source\nkind: document\nfileGlob: sources/**/*.kt\n")
        write("sources/part/A.kt", "class A")
        write("sources/keep.kt", "class Keep")
        val s = session()
        val plan = FileArrangementPlanner.plan(s, root, listOf(PathMove("sources/part", "reference")))
        assertTrue(plan.edits.any { it.after == "{sources/**/*.kt,reference/A.kt}" })
        assertTrue(s.applyFileArrangement(plan).ok)
        assertTrue(Files.exists(root.resolve("sources/keep.kt")))
        assertTrue(s.undo().ok)
        assertContains(read("_views/source.yaml"), "sources/**/*.kt")
    }

    @Test fun `物理移動後に保存が失敗しても配置と文面と履歴を復元する`() {
        write("_schemas/doc.yaml", "id: doc\nmatch: docs/**/*.yaml\nroot: {type: object}\n")
        write("docs/a.yaml", "name: A\n")
        val before = read("_schemas/doc.yaml")
        val backing = FileSystemDocumentStore(root)
        val failing = object : DocumentStore by backing {
            override fun write(id: DocumentId, text: String) { error("書込失敗を注入") }
        }
        val s = WorkspaceSession(failing)
        val plan = FileArrangementPlanner.plan(s, root, listOf(PathMove("docs", "specs")))
        assertFalse(s.applyFileArrangement(plan).ok)
        assertEquals(before, read("_schemas/doc.yaml"))
        assertEquals("name: A\n", read("docs/a.yaml"))
        assertFalse(Files.exists(root.resolve("specs")))
        assertFalse(s.canUndo())
        assertTrue(s.hasDocument(DocumentId("docs/a.yaml")))
    }

    @Test fun `文書参照と文書相対file値は移動元と参照元の両方に追随する`() {
        write("_schemas/doc.yaml", """
            id: doc
            match: docs/**/*.yaml
            root:
              type: object
              fields:
                - {key: link, type: ref, target: document}
                - {key: image, type: file}
        """.trimIndent())
        write("docs/a.yaml", "image: ../assets/a.png\n")
        write("docs/b.yaml", "link: docs/a.yaml\n")
        write("assets/a.png", "image")
        val s = session()
        val plan = FileArrangementPlanner.plan(s, root, listOf(PathMove("docs/a.yaml", "deep/nested/a.yaml")))
        val result = s.applyFileArrangement(plan)
        assertTrue(result.ok, result.message)
        assertContains(read("docs/b.yaml"), "deep/nested/a.yaml")
        assertContains(read("deep/nested/a.yaml"), "../../assets/a.png")
        assertTrue(s.undo().ok)
        assertContains(read("docs/b.yaml"), "docs/a.yaml")
        assertContains(read("docs/a.yaml"), "../assets/a.png")
    }
}
