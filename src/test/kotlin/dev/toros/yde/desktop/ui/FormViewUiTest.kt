package dev.toros.yde.desktop.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.OpenDocument
import dev.toros.yde.yaml.model.YNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * フォームを画面無しで描いて確かめる（R47-50 の試行。Compose の UI テスト runComposeUiTest）。
 * 描画の結果は semantics（画面の部品が持つ文字・操作の木）で見る。見た目（色・配置）は見ない。
 * - 文書を開くと、スキーマの項目の名前と値がフォームに出る
 * - 閲覧専用では編集できる欄が 1 つも出ない（FormCtx.readOnly の分岐が画面まで届く）
 * - 欄に入力すると文書の木が変わり、未保存（dirty）になる
 * fixture は test-fixtures/workspaces/todos を一時ディレクトリへ写して使う（読み取り専用の決まり）。
 */
// 順を名前で固定する: 入力のテストが最初に走ると、描画と入力の初回の準備が重なって 10〜16 秒かかった（名前順なら 3 本で約 7.5 秒）
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
@OptIn(ExperimentalTestApi::class)
class FormViewUiTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-form-ui")
        val source = Path.of(System.getProperty("fixtures.root") ?: "test-fixtures").resolve("workspaces/todos")
        Files.walk(source).use { paths ->
            paths.forEach { p ->
                val target = dir.resolve(source.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target)
            }
        }
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    /** 個票 TODO-001 を開いた状態。 */
    private fun openTodo(readOnly: Boolean = false): Pair<AppState, OpenDocument> {
        val state = AppState()
        state.openWorkspace(dir)
        state.readOnlyMode = readOnly
        state.openFile(dir.resolve("todos/details/TODO-001.yaml"))
        return state to state.openDoc!!
    }

    private fun SemanticsNodeInteractionsProvider.count(text: String): Int =
        onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun SemanticsNodeInteractionsProvider.editableCount(): Int =
        onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun `文書を開くとスキーマの項目の名前と値がフォームに出る`() = runComposeUiTest {
        val (state, doc) = openTodo()
        setContent { FormView(state, doc) }
        for (label in listOf("ステータス", "期限", "完了日", "結果", "コメント一覧")) {
            assertTrue(count(label) > 0, "項目の名前「$label」が出ていません")
        }
        val result = "３つのスキーマに関して定義を完了させた。"
        assertTrue(onAllNodes(hasText(result), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "結果の値が出ていません")
    }

    @Test
    fun `閲覧専用では編集できる欄が出ず 通常は出る`() {
        runComposeUiTest {
            val (state, doc) = openTodo(readOnly = true)
            setContent { FormView(state, doc) }
            assertTrue(count("結果") > 0, "閲覧専用でも項目は出る")
            assertEquals(0, editableCount(), "閲覧専用で編集できる欄がある")
        }
        runComposeUiTest {
            val (state, doc) = openTodo(readOnly = false)
            setContent { FormView(state, doc) }
            assertTrue(editableCount() > 0, "通常のモードで編集できる欄が無い")
        }
    }

    @Test
    fun `欄に入力すると文書の木が変わり未保存になる`() = runComposeUiTest {
        val (state, doc) = openTodo()
        setContent { FormView(state, doc) }
        assertFalse(doc.dirty)
        onNode(hasSetTextAction() and hasText("３つのスキーマに関して定義を完了させた。"), useUnmergedTree = true)
            .performTextReplacement("画面のテストで書き換えた")
        waitForIdle()
        val value = ((doc.root as YNode.Map).entries["result"] as YNode.Scalar).value
        assertEquals("画面のテストで書き換えた", value)
        assertTrue(doc.dirty, "入力したのに未保存になっていない")
    }
}
