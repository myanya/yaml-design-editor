package dev.toros.yde.app.core

import dev.toros.yde.workspace.FileSystemDocumentStore
import dev.toros.yde.workspace.Generators
import dev.toros.yde.workspace.WorkspaceCheckRunner
import dev.toros.yde.workspace.WorkspaceReader
import dev.toros.yde.workspace.WorkspaceSession
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * samples/todos-sysdev（todos を業務モデルにしたシステム開発の設計書体系）: 設計書の生成物が業務モデルの写しから作り直した
 * 内容と同じ（最新）で、ワークスペース全体の検証にエラーが無い（設計の対応の検査を含む）。業務モデルや生成の定義を直したら、
 * `./gradlew runCli -q --args="generate samples/todos-sysdev --apply"` で作り直す。
 */
class TodosSysdevTest {
    private val root = Path.of("samples/todos-sysdev")

    @Test
    fun `生成物は最新`() {
        val plan = Generators.plan(WorkspaceSession(FileSystemDocumentStore(root)))
        assertEquals(emptyList(), plan.errors)
        assertTrue(plan.upToDate, "生成物が最新ではありません（作る ${plan.created} ・作り直す ${plan.updated} ・消す ${plan.deleted}）。generate samples/todos-sysdev --apply で作り直します")
    }

    /**
     * データ系（エンティティ定義書・テーブル設計書・テーブルアクセス設計書）の決め打ちの規則が、業務モデルから計算で効いていること:
     * 一覧の行と個票は 1 テーブル・明細テーブル・予約語の末尾の _・同じテーブルへの値は 1 つの update・前提の件数・CRUD 表。
     */
    @Test
    fun `データ系の設計書は業務モデルから計算で作る`() {
        fun doc(path: String) = dev.toros.yde.yaml.YamlIO.toAny(dev.toros.yde.yaml.YamlIO.parseFirst(java.nio.file.Files.readString(root.resolve(path)))!!) as Map<*, *>
        fun list(m: Map<*, *>, key: String) = m[key] as List<*>
        val tables = list(doc("design/generated/tables/todo.yaml"), "tables").map { it as Map<*, *> }
        assertEquals(listOf("todo", "todo_tags", "todo_comments", "todo_history"), tables.map { it["name"] })
        val todoColumns = list(tables[0], "columns").map { (it as Map<*, *>)["name"] }
        assertTrue("title" in todoColumns && "status" in todoColumns, "一覧の行（title）と個票（status）の項目が 1 テーブルに: $todoColumns")
        val historyColumns = list(tables[3], "columns").map { (it as Map<*, *>)["name"] }
        assertEquals(listOf("todo_id", "seq", "date", "action", "from_", "to_", "note", "resume_on"), historyColumns, "予約語は末尾に _")
        val taskColumns = list(list(doc("design/generated/tables/todo-task.yaml"), "tables")[0] as Map<*, *>, "columns").map { (it as Map<*, *>)["name"] }
        assertEquals(listOf("id", "todo_id", "seq", "title", "done"), taskColumns, "入れ子の型は親の ID と並び順を足す")
        val methods = list(doc("design/generated/table-access/todo.yaml"), "methods").map { it as Map<*, *> }
        val complete = methods.single { it["name"] == "completeTodo" }
        assertEquals(listOf("completed_on", "result", "status", "updated_on"), list(complete, "set").map { (it as Map<*, *>)["item"] }, "同じテーブルへの値は 1 つの update")
        val count = list(doc("design/generated/table-access/todo-task.yaml"), "methods").map { it as Map<*, *> }.single { it["kind"] == "count" }
        assertEquals("complete-todo.no-open-task", count["precondition"])
        assertEquals(listOf(mapOf("item" to "todo_id", "param" to "id"), mapOf("item" to "done", "equals" to false)), count["where"])
        val crud = list(doc("design/generated/entities/todo.yaml"), "crud").map { it as Map<*, *> }
        val status = crud.single { it["項目"] == "status" }
        assertEquals("C", status["register-todo"])
        assertEquals("U", status["complete-todo"])
        assertEquals("", status["edit-todo"], "編集は状態を変えない")
    }

    @Test
    fun `検証にエラーが無い`() {
        val ws = WorkspaceReader.load(FileSystemDocumentStore(root), builtins = dev.toros.yde.app.schema.BuiltinSchemas.kernelSchemas(dev.toros.yde.workspace.layout.WorkspaceLayout.of(root)))
        assertEquals(emptyList(), ws.schemaErrors)
        val errors = WorkspaceCheckRunner.validateAll(ws).flatMap { (doc, v) -> v.issues.filter { it.severity.name == "ERROR" }.map { "${doc.value}: ${it.message}" } }
        assertEquals(emptyList(), errors)
    }
}
