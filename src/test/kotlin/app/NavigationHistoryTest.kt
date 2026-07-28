package app

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
        state.navigateToOccurrence(index.IdEntry("x", a, emptyList(), null, null, "a"))
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
