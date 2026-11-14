package dev.toros.yde.desktop.ui.form

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.toros.yde.desktop.AppState
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.scalarText
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 要素が重ならない配列（`unique: true`。UX-316）の入力の手伝い: 参照と選択肢の候補で、ほかの要素が使っている値を
 * 「この一覧で選択済み」として選べなくする（重なりの検査は構造の検査。これは候補の手伝いだけ）。
 */
@OptIn(ExperimentalTestApi::class)
class UniqueChoicesUiTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-unique-ui")
        fun write(rel: String, text: String) {
            val target = dir.resolve(rel)
            Files.createDirectories(target.parent)
            Files.writeString(target, text.trimIndent() + "\n")
        }
        write("_schemas/note.yaml", """
            id: note
            match: "notes/*.yaml"
            root:
              type: object
              fields:
                - key: tags
                  label: タグ
                  type: array
                  unique: true
                  item: { type: ref, idPattern: "TAG-*" }
                - key: colors
                  label: 色
                  type: array
                  unique: true
                  item: { type: enum, values: [red, green, blue] }
        """)
        write("_schemas/tag-list.yaml", """
            id: tag-list
            match: tag-list.yaml
            root:
              type: object
              fields:
                - key: tags
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: name, type: string, role: title }
        """)
        write("tag-list.yaml", "tags:\n  - { id: TAG-001, name: 急ぎ }\n  - { id: TAG-002, name: 確認 }")
        write("notes/a.yaml", "tags: [TAG-001, '']\ncolors: [red, blue]")
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test
    fun `参照と選択肢の候補はほかの要素が使っている値を選択済みとして選べなくする`() = runComposeUiTest {
        val state = AppState()
        state.openWorkspace(dir)
        state.openFile(dir.resolve("notes/a.yaml"))
        val doc = state.openDoc!!
        setContent { FormView(state, doc) }
        // タグの 2 つ目の要素の候補: TAG-001 は 1 つ目が使っているので選べない。TAG-002 は選べる
        onAllNodesWithContentDescription("候補を表示")[1].performClick()
        onNode(hasText("この一覧で選択済み", substring = true)).assertIsNotEnabled()
        onAllNodesWithText("TAG-002")[0].assertIsEnabled().performClick()
        waitForIdle()
        assertEquals("TAG-002", scalarText(getAt(doc.root!!, parseYPath("tags[1]")) as YNode.Scalar))
        // 選択肢の配列も同じ: 2 つ目の要素で red は選べない
        onNode(hasText("blue") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        onNode(hasText("この一覧で選択済み", substring = true)).assertIsNotEnabled()
        onNode(hasText("green")).assertIsEnabled()
    }
}
