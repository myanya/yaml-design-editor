package dev.toros.yde.desktop

import dev.toros.yde.yaml.model.YNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * ビュー定義の一式の共有（UX-299）: 同じ版（索引の版・編集の版）の間は 1 回だけ読み、画面の部品が同じものを受け取る。
 * 未保存の編集で読み直し、ディスクだけを読む一式は編集では読み直さない。
 */
class ViewCatalogSharingTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-viewcatalog")
        write("_schemas/task.yaml", """
            id: task-schema
            match: "tasks/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id }
                - { key: title, type: string }
        """)
        write("_views/tasks.yaml", """
            id: tasks
            name: タスク一覧
            rows: { idPattern: "TASK-*" }
            columns:
              - { label: 件名, schemaId: task-schema, path: title }
        """)
        write("tasks/t1.yaml", """
            id: TASK-001
            title: 最初のタスク
        """)
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun write(rel: String, content: String) {
        val file = dir.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent() + "\n")
    }

    private fun nameOf(state: AppState, unsavedFirst: Boolean = true) =
        assertNotNull(state.viewCatalog(unsavedFirst)).views.single { it.id == "tasks" }.name

    @Test
    fun `同じ版の間は 1 回だけ読み 未保存の編集と保存で読み直す`() {
        val state = AppState().also { it.openWorkspace(dir) }
        val first = assertNotNull(state.viewCatalog())
        assertSame(first, state.viewCatalog(), "同じ版では読み直さない")
        val stored = assertNotNull(state.viewCatalog(unsavedFirst = false))
        assertEquals("タスク一覧", nameOf(state))

        // 業務の文書の未保存の編集: 未保存を優先する一式は読み直し（編集の版が進む）、ディスクだけの一式はそのまま
        val task = state.ensureDoc(dir.resolve("tasks/t1.yaml"))
        (task.root as YNode.Map).entries["title"] = YNode.Scalar("直したタスク")
        task.markEdited()
        val afterEdit = assertNotNull(state.viewCatalog())
        assertNotSame(first, afterEdit)
        assertSame(afterEdit, state.viewCatalog())
        assertSame(stored, state.viewCatalog(unsavedFirst = false), "ディスクだけの一式は、編集では読み直さない")

        // ビュー定義の未保存の編集は、未保存を優先する一式にだけ見える
        val definition = state.ensureDoc(dir.resolve("_views/tasks.yaml"))
        (definition.root as YNode.Map).entries["name"] = YNode.Scalar("やること")
        definition.markEdited()
        assertEquals("やること", nameOf(state))
        assertEquals("タスク一覧", nameOf(state, unsavedFirst = false))

        // 保存すると索引の版が進み、ディスクだけの一式も読み直す
        state.saveAll()
        assertEquals("やること", nameOf(state, unsavedFirst = false))
        assertEquals("やること", nameOf(state))
    }

    @Test
    fun `ワークスペースを開いていなければ無い`() {
        assertEquals(null, AppState().viewCatalog())
    }
}
