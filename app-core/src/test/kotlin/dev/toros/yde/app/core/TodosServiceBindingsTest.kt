package dev.toros.yde.app.core

import dev.toros.yde.workspace.Generators
import dev.toros.yde.workspace.InMemoryDocumentStore
import dev.toros.yde.workspace.WorkspaceSession
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 確定した入力・状態・処理と物理列を結び、推測で未解決の引数を埋めない。 */
class TodosServiceBindingsTest {
    private val sample = Path.of("samples/todos-sysdev")
    private fun raw(text: String) = YamlIO.parseToRaw(text) as Map<*, *>
    private fun rows(value: Map<*, *>, key: String) = (value[key] as List<*>).map { it as Map<*, *> }
    private fun document(file: String) = raw(Files.readString(sample.resolve(file)))
    private fun store() = InMemoryDocumentStore(TodosSysdevFiles.inputs(sample).filter { it.toString().endsWith(".yaml") }
        .associate { sample.relativize(it).toString().replace('\\', '/') to Files.readString(it) })
    private fun put(input: InMemoryDocumentStore, file: String, value: Any) = input.write(DocumentId(file), YamlIO.dumpPlain(YamlIO.fromAny(value)))

    @Test
    fun `全操作の引数と入れ子の型と親のキーを保持する`() {
        val inputs = rows(document("design/detail/final/application-service-inputs.yaml"), "operations")
        assertEquals(14, inputs.size)
        val edit = inputs.single { it["action"] == "edit-todo" }
        assertEquals("java.time.LocalDate", rows(edit, "arguments").single { it["name"] == "createdOn" }["type"])
        assertTrue(rows(edit, "arguments").none { it["name"] == "comments" }, "コメントは編集の入力に無い")
        // 並びの番号（row）は整数の入力で、明細テーブルの並び順（seq）の引数へ渡る
        val editComment = inputs.single { it["action"] == "edit-todo-comment" }
        assertEquals("java.lang.Long", rows(editComment, "arguments").single { it["name"] == "row" }["type"])
        val clear = rows(edit, "classes").single { it["name"] == "EditTodoClear" }
        assertTrue(rows(clear, "fields").all { it["type"] == "java.lang.Boolean" })
        val bindings = rows(document("design/detail/final/application-service-bindings.yaml"), "operations")
        assertEquals(inputs.size, bindings.size)
        assertTrue(bindings.flatMap { rows(it, "steps") }.flatMap { rows(it, "parameters") }.all { it["typeMatches"] == true })
        val rowStep = rows(bindings.single { it["id"] == "SPROG-delete-todo-comment" }, "steps").single { it["call"] == "TodoMapper.deleteTodoCommentCommentsRow" }
        assertEquals(listOf("id" to "input.getId()", "row" to "input.getRow()"), rows(rowStep, "parameters").map { it["name"] to (it["binding"] as Map<*, *>)["java"] })
        val child = bindings.single { it["id"] == "SPROG-todo-task.finish" }
        val parent = rows(child, "steps").single { it["call"] == "TodoMapper.todoTaskFinish" }
        val key = rows(parent, "parameters").single { it["name"] == "todoId" }["binding"] as Map<*, *>
        assertEquals("self", key["place"])
        assertEquals("original.getTodoId()", key["java"])
        val register = document("design/detail/final/processes/register-todo.yaml")
        assertEquals(mapOf("generated" to "numberedId"), rows(register, "steps").single { it["each"] != null }["ownerKey"])
    }

    @Test
    fun `物理名と呼出名の補正がServiceの本体まで届き 状態は状態の型の項目から読む`() {
        val input = store()
        put(input, "design/detail/inputs/tables/corrections.yaml", raw("""
            corrections:
              - {id: DCOR-service-title, target: COL-todo.title, set: {name: subject}, reason: 検証}
        """.trimIndent()))
        // 状態の遷移（どの状態のときに操作できるか）は、詳細設計では変えられない（業務の事実。基本設計の「状態と遷移」から運ぶ）
        put(input, "design/detail/inputs/documents/corrections.yaml", mapOf(
            "inputId" to "INPUT-detail-document-corrections", "corrections" to listOf(
                mapOf("id" to "SCOR-store", "kind" to "table-access", "target" to "DOC-table-access.todo",
                    "set" to mapOf("mapper" to "TodoStore"), "reason" to "検証"),
            )))
        val plan = Generators.plan(WorkspaceSession(input), setOf("application-service-programs"))
        assertEquals(emptyList(), plan.errors)
        val bindings = rows(raw(plan.outputs.single { it.generator == "application-service-bindings" }.text), "operations")
        val start = bindings.single { it["id"] == "SPROG-start-todo" }
        val title = rows(start, "symbols").single { it["place"] == "self" && it["path"] == "title" }
        assertEquals("original.getSubject()", title["java"])
        assertTrue(rows(start, "steps").any { it["call"] == "TodoStore.startTodo" })
        val process = raw(plan.outputs.single { it.document.value == "design/detail/final/processes/start-todo.yaml" }.text)
        val check = rows(process, "steps").single { it["checkMode"] == "state" }
        assertEquals(listOf("OPEN"), check["allowed"], "できる状態は、状態の型の項目（定数の ID）から定数の名前に解いて読む")
        assertEquals("status", check["stateField"])
        val program = plan.outputs.single { it.generator == "application-service-programs" }.text
        assertTrue("getSubject()" in program)
        assertTrue("TodoStore.StartTodoInput" in program)
    }

    private fun probe(input: InMemoryDocumentStore, generator: String) {
        // 依存生成を省く検査では、確定値を生成物の印のない入力として用意する（操作の入力は、基本設計の操作の項目を解いた形から読む）。
        TodosSysdevFiles.inputs(sample).filter {
            val path = it.toString().replace('\\', '/')
            path.endsWith(".yaml") && ("/final/" in path || path.endsWith("/composed/operations-resolved.yaml"))
        }.forEach {
            val path = sample.relativize(it).toString().replace('\\', '/')
            put(input, path, raw(input.read(DocumentId(path))!!.text))
        }
        val definition = raw(Files.readString(sample.resolve(TodosSysdevFiles.definition(sample, "transforms", generator)))).toMutableMap()
        definition["id"] = "service-binding-probe"
        definition["dependsOn"] = emptyList<String>()
        definition["output"] = "probe/bindings.yaml"
        put(input, ".yde/transforms/design/detail/service-binding-probe.yaml", definition)
    }

    @Test
    fun `存在しない入力と受け手を勝手に追加せず拒否する`() {
        for (argument in listOf("missing", "id", "class")) {
            val input = store()
            probe(input, "application-service-inputs")
            assertEquals(emptyList(), Generators.plan(WorkspaceSession(input), setOf("service-binding-probe")).errors)
            val file = "design/detail/final/processes/add-category.yaml"
            val process = raw(input.read(DocumentId(file))!!.text).toMutableMap()
            process["service"] = (process["service"] as Map<*, *>).toMutableMap().also { it["arguments"] = listOf(argument) }
            put(input, file, process)
            val plan = Generators.plan(WorkspaceSession(input), setOf("service-binding-probe"))
            assertTrue(plan.errors.any { "arguments" in it }, plan.errors.toString())
            assertTrue(plan.transaction.operations.isEmpty())
        }
    }

    @Test
    fun `更新値や条件の食い違いと解決できないキーを生成前に拒否する`() {
        for (change in listOf("value", "when", "where", "key", "state")) {
            val input = store()
            probe(input, "application-service-bindings")
            assertEquals(emptyList(), Generators.plan(WorkspaceSession(input), setOf("service-binding-probe")).errors)
            val file = "design/detail/final/processes/start-todo.yaml"
            val process = raw(input.read(DocumentId(file))!!.text).toMutableMap()
            process["steps"] = rows(process, "steps").map { step -> step.toMutableMap().also {
                if (change == "key" && it["step"] == "read") it["key"] = mapOf("param" to "missing")
                if (change == "state" && it["checkMode"] == "state") it["stateField"] = "missing"
                if (it["kind"] == "update") when (change) {
                    "value", "when" -> it["set"] = rows(it, "set").mapIndexed { index, value -> value.toMutableMap().also { assignment ->
                        if (index == 0) assignment[if (change == "value") "value" else "when"] =
                            if (change == "value") "CLOSED" else listOf(mapOf("path" to "id", "exists" to true))
                    } }
                    "where" -> it["where"] = listOf(mapOf("item" to "title", "param" to "id", "value" to mapOf("param" to "id")))
                }
            } }
            put(input, file, process)
            val plan = Generators.plan(WorkspaceSession(input), setOf("service-binding-probe"))
            val expected = when (change) { "where" -> "更新対象"; "key" -> "binding"; "state" -> "状態を読む項目"; else -> "更新値" }
            assertTrue(plan.errors.any { expected in it }, "$change: ${plan.errors}")
            assertTrue(plan.transaction.operations.isEmpty())
        }
    }
}
