package dev.toros.yde.desktop

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.scalarText
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * サンプル TODO のコメントの編集と削除（UX-381）: コメントは ID を持たないので、個票への操作として、並びの番号で指す。
 * - コメント一覧の行の ⋯ に「コメントを編集する」「コメントを削除する」が並ぶ（行を番号で指す操作）
 * - 編集は、選んだ行の記録日と本文を初期値に読み、変えた項目だけを書く。削除はその 1 件だけを消す
 * - 「TODOを編集する」はコメントを受け取らない
 */
class TodosCommentOpsTest {
    private lateinit var root: Path

    @BeforeTest
    fun setup() {
        root = createTempDirectory("yde-todos-comment-ops")
        val sample = Path.of("samples/todos")
        Files.walk(sample).use { paths -> paths.forEach { source ->
            val relative = sample.relativize(source)
            if (!relative.startsWith(Path.of(".yde/state"))) {
                val target = root.resolve(relative)
                if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
            }
        } }
    }

    @AfterTest
    fun cleanup() {
        Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun comments(): List<Pair<String?, String?>> {
        val tree = dev.toros.yde.yaml.YamlIO.parse(Files.readString(root.resolve("todos/details/TODO-004.yaml")))
        return (getAt(tree, listOf("comments")) as YNode.Seq).items.map { scalarText(getAt(it, listOf("date"))) to scalarText(getAt(it, listOf("body"))) }
    }

    @Test
    fun `コメントの行から 編集は今の値を読んで変えた項目だけを書き 削除はその 1 件だけを消す`() {
        val state = AppState()
        state.openWorkspace(root)
        val before = comments()
        assertEquals(2, before.size)
        // 行の ⋯ に並ぶ操作（個票のコメント一覧）
        assertEquals(
            listOf("edit-todo-comment" to "row", "delete-todo-comment" to "row"),
            state.rowActions("todo-detail", "todo", listOf("comments")).map { it.entry.name to it.param },
        )
        assertEquals(emptyList(), state.rowActions("todo-detail", "todo", listOf("history")), "状態の履歴には、行を指す操作を定義していない")

        // 編集: 2 行目（番号 1）を選ぶと、その行の記録日と本文が初期値に入る
        state.startAction("edit-todo-comment", "TODO-004", mapOf("row" to 1))
        val edit = assertNotNull(state.actionWork)
        assertEquals(before[1].first, scalarText(edit.values.entries["date"]))
        assertEquals(before[1].second, scalarText(edit.values.entries["body"]))
        edit.values.entries["body"] = YNode.Scalar("候補を整理し直した。")
        edit.form.version++
        state.planAction()
        assertNull(edit.error, edit.error)
        state.applyAction()
        assertNull(edit.error, edit.error)
        val edited = comments()
        assertEquals(before[0], edited[0], "ほかの行は変えない")
        assertEquals(before[1].first to "候補を整理し直した。", edited[1], "変えた本文だけを書き、記録日はそのまま")

        // 削除: 1 行目（番号 0）だけが消える
        state.startAction("delete-todo-comment", "TODO-004", mapOf("row" to 0))
        val delete = assertNotNull(state.actionWork)
        state.planAction()
        assertNull(delete.error, delete.error)
        state.applyAction()
        assertEquals(listOf(edited[1]), comments())

        // 無い番号は、何も書かずに止まる
        state.startAction("delete-todo-comment", "TODO-004", mapOf("row" to 3))
        state.planAction()
        assertTrue("3 番の要素はありません" in state.actionWork?.error.orEmpty(), state.actionWork?.error)
        assertEquals(listOf(edited[1]), comments())

        // 「TODOを編集する」は、コメントを受け取らない
        state.startAction("edit-todo", "TODO-004")
        assertTrue(assertNotNull(state.actionWork).entry.params.none { it.key == "comments" })
    }
}
