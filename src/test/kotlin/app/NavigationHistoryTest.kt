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
