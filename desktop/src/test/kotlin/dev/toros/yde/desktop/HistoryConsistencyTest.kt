package dev.toros.yde.desktop

import dev.toros.yde.desktop.ui.SourceStructure
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class HistoryConsistencyTest {
    private val dir = createTempDirectory("yde-history-consistency")
    private val first = dir.resolve("tasks/a.yaml")
    private val second = dir.resolve("tasks/b.yaml")

    @BeforeTest
    fun setup() {
        Files.createDirectories(first.parent)
        Files.writeString(first, "title: 最初\n")
        Files.writeString(second, "title: 二番目\n---\nnote: 後続文書\n")
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun state() = AppState().also { it.openWorkspace(dir) }
    private fun title(node: YNode?) = ((node as YNode.Map).entries["title"] as YNode.Scalar).value
    private fun changeBoth(state: AppState) {
        assertEquals(2, state.bulkUpdate(setOf(first, second), "title", "変更後").getOrThrow())
    }

    @Test
    fun `未表示文書のUndoを一覧と検索が読み初めて開くフォームとソースも一致する`() {
        val state = state()
        changeBoth(state)
        state.undo()
        assertNull(state.docFor(second))
        assertEquals("二番目", title(state.unsavedFirstLoader()(second)))
        assertEquals("二番目", state.workspaceRecords().single { it.file == second }.title)
        assertTrue(state.searchContent("二番目").isNotEmpty())
        val preview = state.redoPreview()
        val doc = state.ensureDoc(second)
        assertEquals("二番目", title(doc.root))
        assertEquals("二番目", title(YamlIO.parseFirst(doc.source.text.toString())))
        assertEquals(1, doc.extraDocs.size)
        assertTrue(doc.dirty)
        assertEquals(Files.readString(second), doc.rawText)
        assertEquals(preview, state.redoPreview(), "開くことや読むことは履歴を増やさない")
        state.redo()
        assertEquals("変更後", title(doc.root))
        assertFalse(doc.dirty)
    }

    @Test
    fun `未表示文書だけがUndoで未保存になっても終了確認と件数に含まれる`() {
        val state = state()
        changeBoth(state)
        assertFalse(state.hasDirtyDocs())
        state.undo()
        assertTrue(state.hasDirtyDocs())
        assertEquals(2, state.dirtyDocCount())
        state.ensureDoc(second)
        assertEquals(2, state.dirtyDocCount(), "キャッシュとの重複を数えない")
        state.redo()
        assertFalse(state.hasDirtyDocs())
        assertEquals(0, state.dirtyDocCount())
    }

    @Test
    fun `未表示文書をUndoした後のすべて保存は戻した内容を書き出す`() {
        val state = state()
        changeBoth(state)
        state.undo()
        assertNull(state.docFor(second))
        state.saveAll()
        assertEquals("最初", title(YamlIO.parseFirst(Files.readString(first))))
        assertEquals("二番目", title(YamlIO.parseFirst(Files.readString(second))))
        assertEquals(2, YamlIO.parseAll(Files.readString(second)).size)
        assertFalse(state.hasDirtyDocs())
        assertTrue(state.canRedo(), "保存はRedoを失わせない")
    }

    @Test
    fun `内容のUndoは保存済みテキストを変えずソースの差分印が残る`() {
        val state = state()
        val doc = state.ensureDoc(first)
        changeBoth(state)
        doc.syncSourceFromTree()
        val saved = Files.readString(first)
        assertEquals(saved, doc.rawText)
        state.undo()
        doc.syncSourceFromTree()
        assertEquals(saved, Files.readString(first))
        assertEquals(saved, doc.rawText)
        assertTrue(doc.dirty)
        assertTrue(SourceStructure.diffMarks(doc.rawText, doc.source.text.toString()).isNotEmpty())
        state.redo()
        doc.syncSourceFromTree()
        assertFalse(doc.dirty)
        assertTrue(SourceStructure.diffMarks(doc.rawText, doc.source.text.toString()).isEmpty())
    }

    @Test
    fun `読み出しと画面の木を変更してもセッションの履歴は汚れない`() {
        val state = state()
        changeBoth(state)
        state.undo()
        val read = state.unsavedFirstLoader()(second) as YNode.Map
        read.entries["title"] = YNode.Scalar("読み出したコピー")
        assertEquals("二番目", title(state.unsavedFirstLoader()(second)))
        val doc = state.ensureDoc(second)
        (doc.root as YNode.Map).entries["title"] = YNode.Scalar("画面のコピー")
        state.redo()
        state.undo()
        assertEquals("二番目", title(doc.root))
    }

    @Test
    fun `壊れた下書きも未保存件数に含み未表示文書と一緒に保存できる`() {
        val state = state()
        changeBoth(state)
        state.undo()
        val doc = state.ensureDoc(first)
        doc.editSource { replace(0, length, "title: [\n") }
        doc.flushDraft()
        assertTrue(doc.draftBroken)
        assertEquals(2, state.dirtyDocCount())
        state.saveAll()
        assertEquals("title: [\n", Files.readString(first))
        assertEquals("二番目", title(YamlIO.parseFirst(Files.readString(second))))
        assertFalse(state.hasDirtyDocs())
    }
}
