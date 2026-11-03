package dev.toros.yde.app.core

import dev.toros.yde.app.session.DocumentSession
import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkspaceLoadingTest {
    @TempDir lateinit var dir: Path

    private fun write(name: String, text: String): Path = dir.resolve(name).also {
        Files.createDirectories(it.parent)
        Files.writeString(it, text)
    }

    @Test
    fun `一回の読込から作る一覧は台帳とエディタとMarkdownと時刻の従来の分類を保つ`() {
        write(".yde/workspace.yaml", "ledger: [docs]\n")
        write(".yde/schemas/doc.yaml", "id: doc\nmatch: 'docs/*.yaml'\nroot: {type: object, fields: [{key: id, type: id}]}\n")
        write("docs/a.yaml", "id: A\n")
        write("other/b.yml", "id: B\n")
        write("other/b0.yml", "name: before-folder\n")
        write("other/b/a.yml", "name: in-folder\n")
        write(".hidden/secret.yaml", "id: secret\n")
        write("README.MD", "メモ")
        write(".yde/internal.md", "構成領域")
        val session = DocumentSession()
        val candidate = session.prepareReload(dir)
        val ws = candidate.workspace
        assertEquals(WorkspaceScan.yamlFiles(dir, ws.layout), ws.files)
        assertEquals(WorkspaceScan.markdownFiles(dir), candidate.markdown)
        assertEquals(session.editorOnlyFilesOf(ws), candidate.editorOnly)
        assertEquals(WorkspaceScan.stamps(dir, ws.layout), candidate.stamps)
        assertEquals(3, candidate.editorOnly.size)
        assertEquals(setOf("A"), ws.index.entries.keys)
    }

    @Test
    fun `背景の照合は同時刻の変更と作成削除を検出しセッションを書き換えない`() {
        val file = write("a.yaml", "name: before\n")
        val removed = write("removed.yaml", "name: remove\n")
        val session = DocumentSession()
        session.openWorkspace(dir)
        val check = assertNotNull(session.prepareDiskCheck())
        val stamp = Files.getLastModifiedTime(file)
        Files.writeString(file, "name: after\n")
        Files.setLastModifiedTime(file, stamp)
        Files.delete(removed)
        val added = write("new.yaml", "name: new\n")
        assertEquals(setOf(file, removed, added), check.changedFiles())
        assertEquals("name: before\n", session.kernel!!.expectedStoreText(DocumentId("a.yaml")))
        assertFalse(session.hasRetainedEdits())
        session.openWorkspace(dir)
        assertTrue(assertNotNull(session.prepareDiskCheck()).changedFiles().isEmpty())
        assertEquals(setOf(file, removed, added), check.changedFiles(), "作成済みの照合の基準は後から変わらない")
    }
}
