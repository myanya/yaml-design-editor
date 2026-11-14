package dev.toros.yde.app.core

import dev.toros.yde.app.skill.Skills
import dev.toros.yde.workspace.layout.WorkspaceLayout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 定義ゾーンの下のフォルダ: ビュー・レポート・skill の定義を、ゾーンの下のフォルダに置いても読む。
 * フォルダは置き場所の整理だけで、ID はゾーン全体で 1 つ（別のフォルダに同じ ID があれば誤り）。
 */
class DefinitionSubfoldersTest {
    private lateinit var ws: Path

    @BeforeTest
    fun setup() {
        ws = createTempDirectory("yde-subfolders")
        write(".yde/workspace.yaml", "ledger: [docs]")
        write(".yde/schemas/business/task.yaml", """
            id: task
            match: "docs/*.yaml"
            entity: { type: task, idPattern: "T-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: title, type: string }
        """)
        write("docs/T-1.yaml", "id: T-1\ntitle: 一つ目")
        write(".yde/views/top.yaml", "id: top\nrows: { match: [ { node: { entity: task, bind: t } } ] }\ncolumns: [ { label: 題, path: title } ]")
        write(".yde/views/business/tasks.yaml", "id: tasks\nrows: { match: [ { node: { entity: task, bind: t } } ] }\ncolumns: [ { label: 題, path: title } ]")
        write(".yde/views/design/deep/more.yaml", "id: more\nrows: { match: [ { node: { entity: task, bind: t } } ] }\ncolumns: [ { label: 題, path: title } ]")
        // 点で始まるフォルダは読まない
        write(".yde/views/.draft/hidden.yaml", "id: hidden\nrows: { match: [ { node: { entity: task, bind: t } } ] }\ncolumns: [ { label: 題, path: title } ]")
        write(".yde/reports/business/list.yaml", "id: list\nbuild: { rows: { ${'$'}view: tasks } }\ntemplate: list.md")
        write(".yde/reports/business/list.md", "{#each rows}- {title}\n{/each}")
        write(".yde/skills/design/run.yaml", "id: run-it\nkind: run\nrun:\n  command: [echo, x]\n  workdir: .\n  timeoutSeconds: 5\n  writes: [\"docs/**\"]")
    }

    @AfterTest
    fun cleanup() {
        Files.walk(ws).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test
    fun `下のフォルダの定義も読み 並びはゾーンの中の場所の順`() {
        val workspace = Workspace.open(ws)
        val views = Views.load(ws, workspace.schemas)
        assertEquals(emptyList(), views.errors)
        assertEquals(listOf("tasks", "more", "top"), views.views.map { it.id })
        val reports = Reports.load(ws)
        assertEquals(emptyList(), reports.errors)
        assertEquals(listOf("list"), reports.reports.map { it.def.id })
        val skills = Skills.load(ws)
        assertEquals(emptyList(), skills.errors)
        assertEquals(listOf("run-it"), skills.skills.map { it.id })
        // 下のフォルダのスキーマも読む（もとから）
        assertEquals(emptyList(), workspace.schemaErrors)
        assertTrue(workspace.schemas.any { it.id == "task" })
    }

    @Test
    fun `別のフォルダに同じ ID があれば誤りで ゾーンの中の場所を示す`() {
        write(".yde/views/design/tasks.yaml", "id: tasks\nrows: { match: [ { node: { entity: task, bind: t } } ] }\ncolumns: [ { label: 題, path: title } ]")
        write(".yde/reports/design/list.yaml", "id: list\nbuild: { rows: { ${'$'}view: tasks } }\ntemplate: list.md")
        write(".yde/reports/design/list.md", "{#each rows}- {title}\n{/each}")
        val workspace = Workspace.open(ws)
        val views = Views.load(ws, workspace.schemas)
        assertTrue(views.errors.any { "ビューID「tasks」が重複しています" in it && "business/tasks.yaml" in it && "design/tasks.yaml" in it }, views.errors.toString())
        val reports = Reports.load(ws)
        assertTrue(reports.errors.any { "レポートID「list」が重複しています" in it && "design/list.yaml" in it }, reports.errors.toString())
    }

    @Test
    fun `誤りの文の名前はゾーンから見た場所`() {
        assertEquals("business/tasks.yaml", WorkspaceLayout.nameInZone(".yde/views/business/tasks.yaml"))
        assertEquals("top.yaml", WorkspaceLayout.nameInZone(".yde/views/top.yaml"))
        assertEquals("a/b.yaml", WorkspaceLayout.nameInZone("_views/a/b.yaml"))
        assertEquals("T-1.yaml", WorkspaceLayout.nameInZone("docs/T-1.yaml"))
    }

    private fun write(rel: String, content: String) {
        val file = ws.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent())
    }
}
