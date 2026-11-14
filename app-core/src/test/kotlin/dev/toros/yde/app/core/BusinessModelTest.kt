package dev.toros.yde.app.core

import dev.toros.yde.workspace.BusinessModel
import dev.toros.yde.workspace.FileSystemDocumentStore
import dev.toros.yde.workspace.WorkspaceReader
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 業務モデルを展開した形（生成の入力）: samples/todos の定義から、操作の効果（do・Lifecycle・自動の値の 3 か所を 1 列に）、
 * 展開した Lifecycle、エンティティの側面（型を解いた項目）を 1 つの項にする。文書（データ）は読まない。
 */
class BusinessModelTest {
    @Suppress("UNCHECKED_CAST")
    private val model: Map<String, Any?> by lazy {
        val ws = WorkspaceReader.load(FileSystemDocumentStore(Path.of("samples/todos")))
        assertEquals(emptyList(), ws.schemaErrors)
        YamlIO.toAny(BusinessModel.of(ws)) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun list(key: String, of: Map<String, Any?> = model) = of[key] as List<Map<String, Any?>>
    private fun byId(key: String, id: String, of: Map<String, Any?> = model) = assertNotNull(list(key, of).firstOrNull { it["id"] == id }, "$key: $id")

    @Test
    fun `操作の効果は do と Lifecycle と自動の値を 1 列にまとめる`() {
        val complete = byId("actions", "complete-todo")
        assertEquals("todo", complete["subject"])
        val lifecycle = complete["lifecycle"] as Map<*, *>
        assertEquals(
            listOf(mapOf("from" to listOf(mapOf("id" to "todo-IN_PROGRESS", "label" to "対応中", "value" to "IN_PROGRESS")), "to" to mapOf("id" to "todo-CLOSED", "label" to "完了", "value" to "CLOSED"))),
            lifecycle["transitions"],
        )
        assertEquals("「{id}」は「{state}」なので、「TODOを完了する」はできません（できるのは「対応中」のとき）", lifecycle["refusal"])
        assertEquals(mapOf("date" to "\$date"), lifecycle["history"])
        assertEquals(listOf("no-open-task"), list("require", complete).map { it["id"] }, "状態の条件は Lifecycle から（前提ではない）")
        val effects = list("effects", complete).map { listOf(it["op"], it["schema"], it["path"], it["source"]) }
        assertEquals(
            listOf(
                listOf("set", "todo-detail", "completed_on", "do"),
                listOf("set", "todo-detail", "result", "do"),
                listOf("set", "todo-detail", "status", "lifecycle"),
                listOf("insert", "todo-detail", "history", "lifecycle"),
                listOf("set", "todo-detail", "updated_on", "auto"),
            ),
            effects,
        )
        // 書き込む項目の事実（どの型の項目か・基底の種類）。列か子の表かは生成の定義が決める
        val history = list("effects", complete).first { it["path"] == "history" }
        assertEquals("todo", history["entity"])
        assertEquals(mapOf("key" to "history", "base" to "array", "item" to mapOf("base" to "object")), history["field"])
        val row = history["value"] as Map<*, *>
        assertEquals("\$date", row["date"], "操作の history の値が既定の今日を上書きする")
        assertEquals(mapOf("\$copy" to "status", "\$of" to "self"), row["from"])
        assertEquals("CLOSED", row["to"])
        // 入力は型を解いた形（完了結果は文章を狭めた型で、1 文字以上・複数行。基底の種類は文字）
        val result = list("params", complete).first { it["key"] == "result" }
        assertEquals("todo-operations#操作の記録", result["type"])
        assertEquals("string", result["base"])
        assertEquals(true, result["multiline"])
        assertEquals(1, result["minLength"])
        assertEquals(true, result["required"])
    }

    @Test
    fun `登録は入口で作り 中止はどの子の状態からでも 型の操作は受け手の中だけ`() {
        val register = byId("actions", "register-todo")
        assertEquals(listOf("todo-OPEN"), ((register["lifecycle"] as Map<*, *>)["entry"] as List<*>).map { (it as Map<*, *>)["id"] })
        assertEquals("todo", register["subject"], "受け手の無い登録は、作る文書の型が主体")
        assertEquals("category", byId("actions", "add-category")["subject"], "一覧へ足す操作は、足す要素の型が主体")
        val effects = list("effects", register)
        assertTrue(effects.any { it["op"] == "create" && it["schema"] == "todo-detail" })
        assertTrue(effects.any { it["path"] == "status" && it["value"] == "OPEN" && it["on"] == "create" })
        assertTrue(effects.any { it["path"] == "created_on" && it["auto"] == "created" }, effects.toString())
        val cancel = byId("actions", "cancel-todo")
        val cancelMove = ((cancel["lifecycle"] as Map<*, *>)["transitions"] as List<*>).single() as Map<*, *>
        assertEquals(listOf("todo-OPEN", "todo-IN_PROGRESS", "todo-SUSPENDED"), (cancelMove["from"] as List<*>).map { (it as Map<*, *>)["id"] })
        assertTrue("できるのは「未完了」のとき" in (cancel["lifecycle"] as Map<*, *>)["refusal"].toString(), "子がそろえば親の名前でまとめる")
        val finish = byId("actions", "todo-task.finish")
        assertEquals("method", finish["kind"])
        assertTrue(list("effects", finish).any { it["path"] == "done" && it["within"] == "todo-task" && it["schema"] == "todo-detail" })
    }

    @Test
    fun `エンティティの側面と State と展開した Lifecycle`() {
        val todo = byId("entities", "todo")
        val aspects = list("aspects", todo)
        assertEquals(listOf("todo-list" to "todos[*]", "todo-detail" to ""), aspects.map { it["schema"] to it["at"] })
        val title = list("fields", aspects[0]).first { it["key"] == "title" }
        assertEquals(100, title["maxLength"])
        assertEquals("common#一行の文", title["type"])
        val status = list("fields", aspects[1]).first { it["key"] == "status" }
        assertEquals(5, (status["values"] as List<*>).size)
        assertEquals("todo-task", (list("fields", aspects[1]).first { it["key"] == "tasks" }["item"] as Map<*, *>)["entity"])
        assertEquals("todo-ACTIVE", byId("states", "todo-OPEN")["parent"])
        assertEquals(listOf("todo-OPEN", "todo-IN_PROGRESS", "todo-SUSPENDED"), byId("states", "todo-ACTIVE")["children"])
        val lifecycle = byId("lifecycles", "todo-lifecycle")
        assertEquals(5, (lifecycle["states"] as List<*>).size)
        assertEquals(listOf("todo-ACTIVE"), list("groups", lifecycle).map { it["id"] })
        assertEquals(listOf("todo-CLOSED", "todo-CANCELED"), list("terminal", lifecycle).map { it["id"] })
    }
}
