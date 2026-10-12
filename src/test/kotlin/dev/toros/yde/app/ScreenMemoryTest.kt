package dev.toros.yde.app

import dev.toros.yde.app.ui.FormCtx
import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 画面の覚え（ScreenMemory）のテスト。画面の描画は手動確認だが、
 * 「畳みは文書ごと・ジャンプ先は開いて覚える・ワークスペース切替で消える・削除と改名に追随する」は純ロジックとして確かめる。
 */
class ScreenMemoryTest {
    private val dir: Path = Files.createTempDirectory("memory-test")
    private val extraDirs = mutableListOf<Path>()

    private fun newFile(rel: String): Path =
        dir.resolve(rel).also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "id: ${rel.substringAfterLast('/').removeSuffix(".yaml")}\nitems:\n  - a: 1\n")
        }

    @AfterTest
    fun cleanup() {
        (listOf(dir) + extraDirs).forEach { root ->
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun ctxFor(state: AppState, file: Path): FormCtx {
        state.openFile(file)
        return FormCtx(state, state.openDoc!!, emptyList())
    }

    @Test
    fun `畳みは文書とパスごとに覚え、無ければ既定の深さで決める`() {
        val state = AppState().also { it.openWorkspace(dir) }
        val a = newFile("a.yaml")
        val b = newFile("b.yaml")
        state.reload()
        val ctxA = ctxFor(state, a)
        assertTrue(ctxA.expanded(listOf("items")))               // 2 階層までは既定で開く
        assertFalse(ctxA.expanded(listOf("items", 0, "deep")))  // それより深いと畳む

        ctxA.setExpanded(listOf("items"), false)
        ctxA.setExpanded(listOf("items", 0, "deep"), true)
        assertFalse(ctxA.expanded(listOf("items")))
        assertTrue(ctxA.expanded(listOf("items", 0, "deep")))

        // 別の文書には効かない。戻ってきても覚えている
        assertTrue(ctxFor(state, b).expanded(listOf("items")))
        assertFalse(ctxFor(state, a).expanded(listOf("items")))
    }

    @Test
    fun `ジャンプ先が畳みの中なら開いて覚え、ハイライトが消えても開いたまま`() {
        val state = AppState().also { it.openWorkspace(dir) }
        val a = newFile("a.yaml")
        state.reload()
        val ctx = ctxFor(state, a)
        ctx.setExpanded(listOf("items"), false)
        state.highlightPath = listOf("items", 0, "a")
        assertTrue(ctx.expanded(listOf("items")))
        state.highlightPath = null
        assertTrue(ctx.expanded(listOf("items")))
    }

    @Test
    fun `ワークスペースを切り替えると画面の覚えが全部消える`() {
        val state = AppState().also { it.openWorkspace(dir) }
        val a = newFile("a.yaml")
        state.reload()
        ctxFor(state, a).setExpanded(listOf("items"), false)
        state.memory.sidePanelTab = 2
        state.memory.lensHiddenTypes["画面"] = true
        state.memory.lensDepth = 1
        state.memory.treeExpanded[dir] = false

        val other = Files.createTempDirectory("memory-test-other").also(extraDirs::add)
        state.openWorkspace(other)
        assertNull(state.memory.fold(DocumentId("a.yaml"), listOf("items")))
        assertEquals(0, state.memory.sidePanelTab)
        assertTrue(state.memory.lensHiddenTypes.isEmpty())
        assertEquals(2, state.memory.lensDepth)
        assertTrue(state.memory.treeExpanded.isEmpty())
    }

    @Test
    fun `文書の削除と改名で畳みの覚えが追随する`() {
        val state = AppState().also { it.openWorkspace(dir) }
        val a = newFile("a.yaml")
        val b = newFile("sub/b.yaml")
        state.reload()
        ctxFor(state, a).setExpanded(listOf("items"), false)
        ctxFor(state, b).setExpanded(listOf("items"), false)
        state.memory.treeSelection = a
        state.memory.treeExpanded[dir.resolve("sub")] = false

        // 削除: その文書のぶんだけ消え、ツリーの選択も外れる
        assertTrue(state.deletePath(a).isSuccess)
        assertNull(state.memory.fold(DocumentId("a.yaml"), listOf("items")))
        assertEquals(false, state.memory.fold(DocumentId("sub/b.yaml"), listOf("items")))
        assertNull(state.memory.treeSelection)

        // ディレクトリの改名: 下の文書の覚えは新しい場所へ移り、ツリーの展開は忘れる
        assertTrue(state.renamePath(dir.resolve("sub"), "moved").isSuccess)
        assertNull(state.memory.fold(DocumentId("sub/b.yaml"), listOf("items")))
        assertEquals(false, state.memory.fold(DocumentId("moved/b.yaml"), listOf("items")))
        assertTrue(state.memory.treeExpanded.isEmpty())
    }
}
