package dev.toros.yde.app.session

import dev.toros.yde.app.core.WorkspaceScan
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 外部変更の確認を、所有スレッドの準備・背景の走査と照合・所有スレッドの取り込みに分けたときの決まり。 */
class ExternalCheckTest {
    @TempDir lateinit var dir: Path

    private fun write(name: String, text: String): Path = dir.resolve(name).also {
        Files.createDirectories(it.parent)
        Files.writeString(it, text)
    }

    private fun DocumentSession.edit(file: Path, value: String) {
        val doc = ensureDocument(file)
        (doc.root as YNode.Map).entries["name"] = YNode.Scalar(value)
        doc.markEdited()
    }

    private fun DocumentSession.expected(name: String) = kernel!!.expectedStoreText(DocumentId(name))

    @Test
    fun `背景の確認の間に保存があれば結果を取り込まず次の確認で取り込む`() {
        val a = write("a.yaml", "name: A\n")
        write("b.yaml", "name: B\n")
        val session = DocumentSession().also { it.openWorkspace(dir) }
        val check = assertNotNull(session.prepareExternalCheck(eventHint = true))
        write("b.yaml", "name: external\n")
        val scan = check.scan()
        session.edit(a, "saved")
        assertTrue(session.saveAll().failures.isEmpty())
        assertFalse(session.applyExternalCheck(scan), "保存の前に走った結果で印や本文を戻さない")
        assertEquals("name: B\n", session.expected("b.yaml"))
        session.pollExternalChanges(eventHint = true)
        assertEquals("name: external\n", session.expected("b.yaml"))
        assertEquals("name: saved\n", session.expected("a.yaml"))
        assertTrue(session.kernel!!.canUndo(), "取り込みは保存の履歴を消さない")
    }

    @Test
    fun `履歴があるときは背景で読んだ候補から外部版を取り込み未保存の編集と履歴を保つ`() {
        val a = write("a.yaml", "name: A\n")
        write("b.yaml", "name: B\n")
        val session = DocumentSession().also { it.openWorkspace(dir) }
        session.edit(a, "local")
        write("b.yaml", "name: external\n")
        val scan = assertNotNull(session.prepareExternalCheck(eventHint = true)).scan()
        assertNotNull(scan.candidate, "履歴があり変化もあるので、取り込みの候補は背景で読む")
        assertTrue(session.applyExternalCheck(scan))
        assertEquals("name: external\n", session.expected("b.yaml"))
        assertTrue(session.document(a)!!.dirty, "未保存の編集を保つ")
        assertTrue(session.kernel!!.undo().ok)
    }

    @Test
    fun `履歴がなく変化もなければ候補を読まず何も変えない`() {
        write("a.yaml", "name: A\n")
        val session = DocumentSession().also { it.openWorkspace(dir) }
        val version = session.indexVersion
        val scan = assertNotNull(session.prepareExternalCheck(eventHint = true)).scan()
        assertNull(scan.candidate)
        assertNull(scan.markdown, "一覧が変わらなければ歩き直さない")
        assertTrue(session.applyExternalCheck(scan))
        assertEquals(version, session.indexVersion)
    }

    @Test
    fun `同じ時刻の上書きは背景で見つけ取り込む前にいまの基準で比べ直す`() {
        val a = write("a.yaml", "name: A\n")
        val session = DocumentSession().also { it.openWorkspace(dir) }
        val notices = mutableListOf<String>()
        session.onNotice = { notices += it }
        val time = Files.getLastModifiedTime(a)
        Files.writeString(a, "name: outside\n"); Files.setLastModifiedTime(a, time)
        val reverted = assertNotNull(session.prepareExternalCheck(eventHint = true)).scan()
        assertEquals(setOf(DocumentId("a.yaml")), reverted.contentChanged)
        Files.writeString(a, "name: A\n"); Files.setLastModifiedTime(a, time)
        assertTrue(session.applyExternalCheck(reverted))
        assertTrue(notices.isEmpty(), "取り込む前に元へ戻った文書は外部変更と知らせない: $notices")

        Files.writeString(a, "name: outside\n"); Files.setLastModifiedTime(a, time)
        val changed = assertNotNull(session.prepareExternalCheck(eventHint = true)).scan()
        assertTrue(session.applyExternalCheck(changed))
        assertEquals("name: outside\n", session.expected("a.yaml"))
        assertEquals(1, notices.size)
    }

    @Test
    fun `別のワークスペースへ切り替えた後は古い結果を取り込まない`() {
        write("one/a.yaml", "name: A\n")
        write("two/b.yaml", "name: B\n")
        val session = DocumentSession().also { it.openWorkspace(dir.resolve("one")) }
        val check = assertNotNull(session.prepareExternalCheck(eventHint = true))
        write("one/c.yaml", "name: C\n")
        val scan = check.scan()
        session.openWorkspace(dir.resolve("two"))
        assertFalse(session.applyExternalCheck(scan))
        assertEquals(listOf(dir.resolve("two/b.yaml")), session.yamlFiles)
    }

    @Test
    fun `外部で増えた文書は背景で歩いた一覧で取り込む`() {
        write(".yde/workspace.yaml", "ledger: [docs]\n")
        write("docs/a.yaml", "name: A\n")
        val session = DocumentSession().also { it.openWorkspace(dir) }
        write("docs/b.yaml", "name: B\n")
        write("notes/c.yaml", "name: C\n")
        write("README.md", "メモ\n")
        val scan = assertNotNull(session.prepareExternalCheck(eventHint = true)).scan()
        assertTrue(session.applyExternalCheck(scan))
        assertTrue(dir.resolve("docs/b.yaml") in session.yamlFiles)
        assertEquals(listOf(dir.resolve("notes/c.yaml")), session.editorOnlyFiles)
        assertEquals(listOf(dir.resolve("README.md")), session.markdownFiles)
        assertEquals(WorkspaceScan.yamlFiles(dir), session.core!!.files, "歩き直した一覧と同じ")
    }
}
