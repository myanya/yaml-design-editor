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

    /**
     * 画面系（画面遷移定義書・画面定義書・Controller・セッション情報設計）は、ダッシュボードを起点に業務モデルから計算で作る:
     * 一覧画面 1・詳細画面 3（入れ子の作業項目は画面にしない）・入力画面 10・確認画面 12 の 26 画面。ビューは定義の索引の yde-view。
     */
    @Test
    fun `画面系の設計書はダッシュボードを起点に計算で作る`() {
        fun doc(path: String) = dev.toros.yde.yaml.YamlIO.toAny(dev.toros.yde.yaml.YamlIO.parseFirst(java.nio.file.Files.readString(root.resolve(path)))!!) as Map<*, *>
        fun list(m: Map<*, *>, key: String) = (m[key] as List<*>).map { it as Map<*, *> }
        val transitions = doc("design/generated/screens/transitions/todo-dashboard.yaml")
        val kinds = list(transitions, "screens").groupingBy { it["kind"].toString() }.eachCount()
        assertEquals(mapOf("list" to 1, "detail" to 3, "input" to 10, "confirm" to 12), kinds)
        assertTrue(java.nio.file.Files.notExists(root.resolve("design/generated/screens/details/todo-task.yaml")), "入れ子の型は詳細画面にしない")
        val start = doc("design/generated/screens/operations/start-todo.yaml")
        kotlin.test.assertNull(start["input"], "受け取る値が無い操作は、入力画面を作らない")
        assertEquals("GET /todo/{id}/start-todo/confirm", (start["confirm"] as Map<*, *>)["request"])
        assertEquals(listOf(mapOf("value" to "OPEN", "label" to "未着手")), start["shownWhen"], "ボタンは Lifecycle の状態で出す")
        val list = doc("design/generated/screens/lists/todo-dashboard.yaml")
        assertEquals(listOf("register-todo", "add-category", "add-tag"), list(list, "buttons").map { it["operation"] }, "上部のボタンはダッシュボードに書いた順")
        val session = doc("design/generated/session.yaml")
        assertEquals(listOf("csrf", "token", "notice"), list(session, "attributes").map { it["key"] })
        val index = dev.toros.yde.app.core.Workspace.open(root).index
        assertEquals("yde-view", index.entries["todo-dashboard"]?.firstOrNull { it.role == dev.toros.yde.semantic.schema.EntityRole.DEFINING }?.entityType, "ビューの定義は定義の索引の yde-view")
    }

    @Test
    fun `検証にエラーが無い`() {
        val ws = WorkspaceReader.load(FileSystemDocumentStore(root), builtins = dev.toros.yde.app.schema.BuiltinSchemas.kernelSchemas(dev.toros.yde.workspace.layout.WorkspaceLayout.of(root)))
        assertEquals(emptyList(), ws.schemaErrors)
        val errors = WorkspaceCheckRunner.validateAll(ws).flatMap { (doc, v) -> v.issues.filter { it.severity.name == "ERROR" }.map { "${doc.value}: ${it.message}" } }
        assertEquals(emptyList(), errors)
    }
}
