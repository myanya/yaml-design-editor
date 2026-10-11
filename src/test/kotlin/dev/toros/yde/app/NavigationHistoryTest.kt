package dev.toros.yde.app

import dev.toros.yde.app.index.IdEntry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ナビゲーション履歴（戻る／進む）のテスト。
 * ツールバー・ショートカットの表示は手動確認（プロジェクト方針）だが、
 * 履歴の積み降ろし・位置復元は AppState の純ロジックとして検証する。
 */
class NavigationHistoryTest {
    @Test
    fun `フローから参照先へ移動して戻ると文書と選択が復元される`() {
        val state = AppState()
        val doc = dev.toros.yde.yaml.model.DocumentId("a.yaml")
        val selection = dev.toros.yde.semantic.FlowSelection("diagram", doc,
            dev.toros.yde.semantic.FlowElementKind.NODE, "A", listOf("nodes", 0), 1, 2)
        state.openFlowView("diagram", doc)
        state.selectFlow("diagram", doc, selection)
        state.openFile(newFile("target.yaml"))
        state.goBack()
        assertEquals(5, state.workspaceView)
        assertEquals("diagram", state.flowViewId)
        assertEquals(doc, state.flowDocument)
        assertEquals(selection, state.flowSelections["diagram" to doc])
        state.openFlowView("diagram", dev.toros.yde.yaml.model.DocumentId("b.yaml"))
        assertEquals(null, state.flowSelections["diagram" to state.flowDocument])
        state.goBack()
        assertEquals(doc, state.flowDocument)
    }


    private val dir: Path = Files.createTempDirectory("nav-test")

    private fun newFile(name: String): Path =
        dir.resolve(name).also { Files.writeString(it, "id: ${name.removeSuffix(".yaml")}\n") }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    @Test
    fun `ジャンプ後に戻る・進むで位置が復元される`() {
        val state = AppState()
        val a = newFile("a.yaml")
        val b = newFile("b.yaml")
        state.openFile(a, listOf("x"))
        assertFalse(state.canGoBack)

        state.openFile(b, listOf("y"))
        assertTrue(state.canGoBack)

        state.goBack()
        assertEquals(a, state.openDoc?.file)
        assertEquals(listOf<Any>("x"), state.highlightPath)
        assertTrue(state.canGoForward)

        state.goForward()
        assertEquals(b, state.openDoc?.file)
        assertEquals(listOf<Any>("y"), state.highlightPath)
        assertFalse(state.canGoForward)
    }

    @Test
    fun `同一ファイルの再オープンは履歴に積まない`() {
        val state = AppState()
        val a = newFile("a.yaml")
        state.openFile(a)
        state.openFile(a)
        assertFalse(state.canGoBack)
        // 同一ファイル内でも位置指定のジャンプは履歴に積む
        state.openFile(a, listOf("x"))
        assertTrue(state.canGoBack)
    }

    @Test
    fun `新しい移動で進む履歴は破棄される`() {
        val state = AppState()
        val a = newFile("a.yaml")
        val b = newFile("b.yaml")
        val c = newFile("c.yaml")
        state.openFile(a)
        state.openFile(b)
        state.goBack()
        assertTrue(state.canGoForward)
        state.openFile(c)
        assertFalse(state.canGoForward)
        state.goBack()
        assertEquals(a, state.openDoc?.file)
    }

    @Test
    fun `ビューグリッドから文書へ飛んでも戻れる`() {
        val state = AppState()
        val a = newFile("a.yaml")
        state.openViewGrid("v1")
        assertFalse(state.canGoBack)  // ビューを開いただけでは履歴なし

        state.openFile(a)
        assertTrue(state.canGoBack)

        state.goBack()
        assertEquals(4, state.workspaceView)
        assertEquals("v1", state.viewGridId)

        state.goForward()
        assertEquals(0, state.workspaceView)
        assertEquals(a, state.openDoc?.file)
    }

    @Test
    fun `文書から横ぐしビューへのジャンプも履歴に積まれる`() {
        val state = AppState()
        val a = newFile("a.yaml")
        state.openFile(a)
        state.openCrossView("some-id")
        assertEquals(3, state.workspaceView)
        assertTrue(state.canGoBack)

        state.goBack()
        assertEquals(0, state.workspaceView)
        assertEquals(a, state.openDoc?.file)

        state.goForward()
        assertEquals(3, state.workspaceView)
        assertEquals("some-id", state.crossViewId)
    }

    @Test
    fun `横ぐしビューから出現・参照先へ飛ぶと戻るが有効になる`() {
        val state = AppState()
        val a = newFile("a.yaml")
        state.openCrossView("x")
        assertFalse(state.canGoBack)

        // 出現カードの→ボタン・参照クリックはいずれも navigateToOccurrence → openFile に到達する
        state.navigateToOccurrence(IdEntry("x", state.documentOf(a), emptyList(), null, null, "a"))
        assertEquals(0, state.workspaceView)
        assertTrue(state.canGoBack)

        state.goBack()
        assertEquals(3, state.workspaceView)
        assertEquals("x", state.crossViewId)
    }

    @Test
    fun `横ぐしビュー内のID切替も履歴に積まれる`() {
        val state = AppState()
        state.openCrossView("x")
        assertFalse(state.canGoBack)  // 空の文書ビューからの遷移は積まない
        state.openCrossView("y")
        assertTrue(state.canGoBack)

        state.goBack()
        assertEquals(3, state.workspaceView)
        assertEquals("x", state.crossViewId)

        state.goForward()
        assertEquals("y", state.crossViewId)
    }

    @Test
    fun `文書内ジャンプ（検証パネル相当）も履歴に積まれる`() {
        val state = AppState()
        val a = newFile("a.yaml")
        state.openFile(a)
        state.navigateToPath(listOf("k"))
        assertEquals(listOf<Any>("k"), state.highlightPath)
        assertTrue(state.canGoBack)

        state.goBack()
        assertEquals(a, state.openDoc?.file)
        assertEquals(null, state.highlightPath)
    }

    @Test
    fun `スペースはナビゲーションで文書世界へ同期される`() {
        // R12-01: 実行・設計スペースからのジャンプは編集／台帳スペースへ着地し、
        // レールで台帳へ戻ると最後の下位ビューが復元される（architecture/ui.md「UI シェル」）
        val state = AppState()
        val a = newFile("a.yaml")
        assertEquals(UiSpace.EDIT, state.uiSpace)

        state.showSpace(UiSpace.DESIGN)
        assertEquals(UiSpace.DESIGN, state.uiSpace)
        state.openFile(a)
        assertEquals(UiSpace.EDIT, state.uiSpace)

        state.openCrossView("x")
        assertEquals(UiSpace.LEDGER, state.uiSpace)
        assertEquals(3, state.workspaceView)

        // 実行スペースは文書位置を保ったまま重なる（workspaceView は変わらない）
        state.showSpace(UiSpace.RUNS)
        assertEquals(UiSpace.RUNS, state.uiSpace)
        assertEquals(3, state.workspaceView)

        // レール切替: 編集 → 台帳 で最後の下位ビュー（横ぐし）を復元する
        state.showSpace(UiSpace.EDIT)
        assertEquals(0, state.workspaceView)
        state.showSpace(UiSpace.LEDGER)
        assertEquals(3, state.workspaceView)

        // 履歴の復元でもスペースが同期される
        state.openFile(a)
        assertEquals(UiSpace.EDIT, state.uiSpace)
        state.goBack()
        assertEquals(UiSpace.LEDGER, state.uiSpace)
    }

    @Test
    fun `スペース切替と台帳タブ切替も戻る・進むの対象になる`() {
        // R12-01: レールでのスペース移動・台帳タブの切替は、ユーザーにとって「画面の遷移」なので履歴に積む
        val state = AppState()
        val a = newFile("a.yaml")
        state.openFile(a)
        assertFalse(state.canGoBack)

        // 編集 → 実行 → 戻ると編集（開いていた文書）へ、進むと実行へ
        state.showSpace(UiSpace.RUNS)
        assertTrue(state.canGoBack)
        state.goBack()
        assertEquals(UiSpace.EDIT, state.uiSpace)
        assertEquals(a, state.openDoc?.file)
        state.goForward()
        assertEquals(UiSpace.RUNS, state.uiSpace)

        // 実行 → 台帳（一覧）→ グラフ。戻るたびに 一覧 → 実行 と1段ずつ戻る
        state.showSpace(UiSpace.LEDGER)
        assertEquals(1, state.workspaceView)
        state.showWorkspaceView(2)
        assertEquals(2, state.workspaceView)
        state.goBack()
        assertEquals(UiSpace.LEDGER, state.uiSpace)
        assertEquals(1, state.workspaceView)
        state.goBack()
        assertEquals(UiSpace.RUNS, state.uiSpace)

        // 同じスペースの再選択・同じタブの再選択は履歴に積まない
        state.showSpace(UiSpace.RUNS)
        state.goForward()
        assertEquals(1, state.workspaceView)
        state.showWorkspaceView(1)
        assertTrue(state.canGoForward)  // 進む履歴が破棄されていない＝何も積まれていない
    }

    @Test
    fun `設計スペースからのジャンプは編集へ着地し戻ると設計へ帰る`() {
        val state = AppState()
        val a = newFile("a.yaml")
        state.showSpace(UiSpace.DESIGN)
        assertFalse(state.canGoBack)  // 空の編集スペースからの遷移は積まない

        state.openFile(a)
        assertEquals(UiSpace.EDIT, state.uiSpace)
        assertTrue(state.canGoBack)
        state.goBack()
        assertEquals(UiSpace.DESIGN, state.uiSpace)
        state.goForward()
        assertEquals(UiSpace.EDIT, state.uiSpace)
        assertEquals(a, state.openDoc?.file)
    }

    @Test
    fun `削除済みファイルは戻る対象から読み飛ばす`() {
        val state = AppState()
        val a = newFile("a.yaml")
        val b = newFile("b.yaml")
        val c = newFile("c.yaml")
        state.openFile(a)
        state.openFile(b)
        state.openFile(c)
        Files.delete(b)
        state.goBack()
        assertEquals(a, state.openDoc?.file)
    }
}
