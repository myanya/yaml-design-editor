package dev.toros.yde.app.session

import dev.toros.yde.app.core.EditBuffer
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 文書の論理（Compose 非依存）の契約: 下書きの文字列は殻から引数で受け取り、状態の変化は onChanged で知らせる。
 * 殻（app.OpenDocument）を通した振る舞いは OpenDocumentTest・SourceHistoryTest が見る。
 */
class DocumentStateTest {
    private fun open(text: String): DocumentState {
        val parsed = EditBuffer.parse(text)
        return DocumentState(Path.of("doc.yaml"), parsed.root, null, text, parsed.error, parsed.extraDocs)
    }

    private fun scalar(state: DocumentState, key: String): YNode.Scalar = (state.root as YNode.Map).entries[key] as YNode.Scalar

    @Test
    fun `下書きの反映は渡した文字列で判定する`() {
        val state = open("a: 1\n")
        assertFalse(state.hasPendingSourceInput("a: 1\n"))
        assertTrue(state.hasPendingSourceInput("a: 2\n"))
        val request = state.sourceParseRequest("a: 2\n") ?: error("依頼が無い")
        assertTrue(state.applyParsed(request, EditBuffer.parse(request.text), "a: 2\n"))
        assertEquals("2", scalar(state, "a").value.toString())
        assertEquals("a: 2\n", state.sourceApplied)
        assertEquals("a: 2\n", state.sourceParsed)
        assertEquals(1, state.version)
        assertFalse(state.hasPendingSourceInput("a: 2\n"))
        assertNull(state.sourceError)
    }

    @Test
    fun `依頼の後に下書きが変わっていたら解析結果を採用しない`() {
        val state = open("a: 1\n")
        val request = state.sourceParseRequest("a: 2\n") ?: error("依頼が無い")
        assertFalse(state.applyParsed(request, EditBuffer.parse(request.text), "a: 3\n"))
        assertEquals("1", scalar(state, "a").value.toString())
        assertEquals(0, state.version)
    }

    @Test
    fun `解析できない下書きは文面だけ残して木を触らない`() {
        val state = open("a: 1\n")
        val broken = "a: [1\n"
        val request = state.sourceParseRequest(broken) ?: error("依頼が無い")
        assertTrue(state.applyParsed(request, EditBuffer.parse(broken), broken))
        assertTrue(state.draftBroken)
        assertTrue(state.draftDirty(broken))
        assertEquals("1", scalar(state, "a").value.toString())
        assertEquals(0, state.version)
    }

    @Test
    fun `木が変わったら下書きへ差分だけを差し込む`() {
        val state = open("a: 1\nb: 2\n")
        val draft = "a: 1\nb: 3\n"
        scalar(state, "a").value = 9
        state.markEdited()
        val outcome = state.syncSourceFromTree(draft, caret = 0, preferHistory = false)
        assertEquals("a: 9\nb: 3\n", outcome.text)
        assertEquals(0, outcome.sync.conflicts)
        assertEquals(state.version, state.sourceSyncedVersion)
        assertEquals("a: 9\nb: 2\n", state.sourceBase)
    }

    @Test
    fun `同期済みなら差し込みは無い`() {
        val state = open("a: 1\n")
        val outcome = state.syncSourceFromTree("a: 1\n", caret = 0, preferHistory = false)
        assertNull(outcome.text)
    }

    @Test
    fun `下書きを捨てると木の正規化テキストに揃う`() {
        val state = open("a: 1\n")
        state.sourceParseRequest("a: 2 broken: [\n")
        val (text, caret) = state.discardDraft(caret = 100)
        assertEquals("a: 1\n", text)
        assertEquals(text.length, caret)
        assertFalse(state.hasPendingSourceInput(text))
        assertNull(state.sourceError)
    }

    @Test
    fun `変化は聞き手に伝わり dirty は保存した内容との差で決まる`() {
        val state = open("a: 1\n")
        var notified = 0
        state.onChanged = { notified++ }
        assertFalse(state.dirty)
        scalar(state, "a").value = 2
        state.markEdited()
        assertTrue(notified > 0)
        assertTrue(state.dirty)
        state.markSaved()
        assertFalse(state.dirty)
        assertEquals("a: 2\n", YamlIO.dumpAll(listOf(state.root!!)))
    }

    @Test
    fun `壊れたまま保存するとテキスト文書になり最後に解析できた木へ戻せる`() {
        val state = open("a: 1\n")
        state.enterTextOnly("a: [1\n")
        assertTrue(state.textOnly)
        assertNull(state.root)
        assertTrue(state.canRestoreLastParsed)
        assertTrue(state.restoreLastParsed())
        assertFalse(state.textOnly)
        assertEquals("1", scalar(state, "a").value.toString())
    }
}
