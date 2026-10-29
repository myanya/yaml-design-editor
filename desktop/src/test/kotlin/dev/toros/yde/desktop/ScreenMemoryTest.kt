package dev.toros.yde.desktop

import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.toros.yde.desktop.ui.form.FormCtx

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
    fun `フォーム方式と選択とツリーの開閉は文書ごとに保持し改名と削除に追随する`() {
        val memory = ScreenMemory()
        val old = DocumentId("sub/a.yaml")
        val moved = DocumentId("moved/a.yaml")
        val form = memory.navigation(old, false)
        assertEquals(FormDisplayMode.SELECTED, memory.formDisplay.modeFor(old))
        memory.formDisplay.choose(old, FormDisplayMode.LINKED)
        form.selected = listOf("rows", 1)
        form.expanded[listOf("rows")] = false
        form.navigationOpen = true
        assertNull(memory.navigation(old, true).selected)
        assertNull(memory.fold(old, listOf("rows")))
        memory.renameDocument(DocumentId("sub"), DocumentId("moved"))
        assertTrue(memory.formNavigation.keys.none { it.first == old })
        assertNull(memory.formDisplay.specified(old))
        val restored = memory.navigation(moved, false)
        assertEquals(FormDisplayMode.LINKED, memory.formDisplay.specified(moved))
        assertEquals(listOf("rows", 1), restored.selected)
        assertEquals(false, restored.expanded[listOf("rows")])
        assertTrue(restored.navigationOpen)
        memory.forgetDocument(DocumentId("moved"))
        assertTrue(memory.formNavigation.isEmpty())
        assertNull(memory.formDisplay.specified(moved))
        memory.navigation(old, false)
        memory.clear()
        assertTrue(memory.formNavigation.isEmpty())
    }

    @Test
    fun `スキーマの区画と選択は文書の改名と削除と切替に追随する`() {
        val memory = ScreenMemory()
        val old = DocumentId("sub/a.yaml")
        val moved = DocumentId("moved/a.yaml")
        val root = listOf("root")
        val selection = listOf("root", "fields", 1)
        memory.schemaSection[old] = 1
        memory.schemaDefinition[old] = "Task"
        memory.schemaSelection[old to root] = selection
        memory.renameDocument(DocumentId("sub"), DocumentId("moved"))
        assertNull(memory.schemaSection[old])
        assertEquals(1, memory.schemaSection[moved])
        assertEquals("Task", memory.schemaDefinition[moved])
        assertEquals(selection, memory.schemaSelection[moved to root])
        memory.forgetDocument(DocumentId("moved"))
        assertTrue(memory.schemaSection.isEmpty())
        assertTrue(memory.schemaDefinition.isEmpty())
        assertTrue(memory.schemaSelection.isEmpty())
        memory.schemaSection[old] = 2
        memory.schemaDefinition[old] = "Task"
        memory.schemaSelection[old to root] = selection
        memory.clear()
        assertTrue(memory.schemaSection.isEmpty())
        assertTrue(memory.schemaDefinition.isEmpty())
        assertTrue(memory.schemaSelection.isEmpty())
    }

    @Test
    fun `他文書の定義の開閉は文書ごとに覚え改名と削除と切替に追随する`() {
        val memory = ScreenMemory()
        val a = DocumentId("sub/a.yaml")
        val b = DocumentId("other/b.yaml")
        assertNull(memory.documentOccurrencesOpen[a])
        memory.documentOccurrencesOpen[a] = true
        memory.documentOccurrencesOpen[b] = false

        memory.renameDocument(DocumentId("sub"), DocumentId("moved"))
        assertNull(memory.documentOccurrencesOpen[a])
        assertEquals(true, memory.documentOccurrencesOpen[DocumentId("moved/a.yaml")])
        assertEquals(false, memory.documentOccurrencesOpen[b])

        memory.forgetDocument(DocumentId("moved"))
        assertEquals(mapOf(b to false), memory.documentOccurrencesOpen.toMap())
        memory.clear()
        assertTrue(memory.documentOccurrencesOpen.isEmpty())
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

        // ディレクトリの改名: 下の文書とツリーの展開を新しい場所へ移す
        assertTrue(state.renamePath(dir.resolve("sub"), "moved").isSuccess)
        assertNull(state.memory.fold(DocumentId("sub/b.yaml"), listOf("items")))
        assertEquals(false, state.memory.fold(DocumentId("moved/b.yaml"), listOf("items")))
        assertEquals(false, state.memory.treeExpanded[dir.resolve("moved")])
    }
}
