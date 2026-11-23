package dev.toros.yde.desktop.ui.form

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.toros.yde.desktop.AppState
import dev.toros.yde.yaml.model.YNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * ID の無い行の操作メニュー（⋯。UX-381）: 手のパスに `並び[$名前]` を書いたアクションは、行の ⋯ から、その行の番号を入れて始められる。
 * - メニューは、行を番号で指すアクションがある並びの行にだけ出る
 * - 選ぶと、行を持つ ID の要素を対象に、行の番号（0 から数える）が入ったパネルが開く
 */
@OptIn(ExperimentalTestApi::class)
class RowIndexOpsUiTest {
    private val d = "$"

    private fun workspace(): Path {
        val root = createTempDirectory("row-index-ops-ui")
        fun write(rel: String, text: String) {
            val file = root.resolve(rel)
            Files.createDirectories(file.parent)
            Files.writeString(file, text.trimIndent() + "\n")
        }
        write("_schemas/memo.yaml", """
            id: memo
            match: "memos/*.yaml"
            entity: { type: memo, idPattern: "M-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - key: comments
                  label: コメント
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: body, label: 本文, type: string }
                - key: notes
                  label: 覚え書き
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: text, label: 文, type: string }
            actions:
              - id: delete-comment
                label: コメントを削除する
                target: { entity: memo }
                params:
                  - { key: row, label: 番号, type: int, required: true }
                do:
                  - delete: { path: "comments[${d}row]" }
        """)
        write("memos/M-1.yaml", """
            id: M-1
            comments:
              - { body: 一つ目 }
              - { body: 二つ目 }
            notes:
              - { text: 覚え }
        """)
        return root
    }

    @Test
    fun `行を番号で指す操作は 行のメニューから その行の番号を入れて始まる`() = runComposeUiTest {
        val root = workspace()
        try {
            val state = AppState()
            state.openWorkspace(root)
            state.openFile(root.resolve("memos/M-1.yaml"))
            val doc = assertNotNull(state.openDoc)
            // 入口の検索: コメントの並びには操作があり、番号で指す操作の無い並び（覚え書き）には無い
            assertEquals(listOf("delete-comment" to "row"), state.rowActions("memo", "memo", listOf("comments")).map { it.entry.name to it.param })
            assertEquals(emptyList(), state.rowActions("memo", "memo", listOf("notes")))
            setContent { FormView(state, doc) }
            waitForIdle()
            // ⋯ が出るのはコメントの 2 行だけ
            val menus = onAllNodesWithContentDescription("行の操作", useUnmergedTree = true)
            assertEquals(2, menus.fetchSemanticsNodes().size)
            menus[1].performClick()
            waitForIdle()
            onNodeWithText("M-1 の 2 行目の操作").assertExists()
            onNodeWithText("コメントを削除する").performClick()
            waitForIdle()
            // 対象は行を持つ要素（M-1）、番号は 0 から数えた行（2 行目＝1）
            val work = assertNotNull(state.actionWork)
            assertEquals("delete-comment", work.entry.name)
            assertEquals("M-1", work.target)
            assertEquals("1", (work.values.entries["row"] as? YNode.Scalar)?.value?.toString())
        } finally {
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
