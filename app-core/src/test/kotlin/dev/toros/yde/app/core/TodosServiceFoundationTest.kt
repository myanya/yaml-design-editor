package dev.toros.yde.app.core

import dev.toros.yde.workspace.Generators
import dev.toros.yde.workspace.InMemoryDocumentStore
import dev.toros.yde.workspace.WorkspaceSession
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 処理の呼び先・検査対象・状態型を確定項目から引き、未解決の指定を生成へ通さない。 */
class TodosServiceFoundationTest {
    private val sample = Path.of("samples/todos-sysdev")
    private fun raw(text: String) = YamlIO.parseToRaw(text) as Map<*, *>
    private fun rows(value: Map<*, *>, key: String) = (value[key] as List<*>).map { it as Map<*, *> }
    private fun document(file: String) = raw(Files.readString(sample.resolve(file)))
    private fun store() = InMemoryDocumentStore(TodosSysdevFiles.inputs(sample).filter { it.toString().endsWith(".yaml") }
        .associate { sample.relativize(it).toString().replace('\\', '/') to Files.readString(it) })
    private fun put(store: InMemoryDocumentStore, file: String, value: Any) = store.write(DocumentId(file), YamlIO.dumpPlain(YamlIO.fromAny(value)))

    @Test
    fun `編集の個別条件と明細のクリアと親の検査対象を保持する`() {
        val edit = document("design/detail/final/processes/edit-todo.yaml")
        val steps = rows(edit, "steps")
        val update = steps.single { it["kind"] == "update" }
        assertFalse(update.containsKey("when"), "最初の代入の条件で更新全体を止めない")
        val title = rows(update, "set").single { it["item"] == "title" }
        assertEquals(listOf(mapOf("path" to "title", "exists" to true)), title["when"])
        assertTrue(steps.any { it["kind"] == "delete" && it["call"] == "MTH-todo.deleteTodoTags" &&
            it["when"] == listOf(mapOf("path" to "clear.tags", "equals" to true)) })
        // 並びの番号で指す操作: 明細の 1 行を並び順（seq）で指し、行の鍵と「行が無いとき」を手順に残す。全件の削除や入れ直しにはしない
        val editComment = rows(document("design/detail/final/processes/edit-todo-comment.yaml"), "steps")
        val rowUpdate = editComment.single { it["call"] == "MTH-todo.editTodoCommentCommentsRow" }
        assertEquals("update", rowUpdate["kind"])
        assertEquals(mapOf("item" to "seq", "param" to "row"), rowUpdate["rowKey"])
        assertEquals(listOf("date", "body"), rows(rowUpdate, "set").map { it["item"] })
        assertEquals(listOf(mapOf("path" to "body", "exists" to true)), rows(rowUpdate, "set").single { it["item"] == "body" }["when"])
        assertEquals(listOf("todo_id" to "id", "seq" to "row"), rows(rowUpdate, "where").map { it["item"] to it["param"] })
        assertEquals("todo.comments", rowUpdate["missingEntity"])
        assertTrue(editComment.none { it["kind"] == "insert" || it["kind"] == "delete" }, "1 行の書き換えは、消して入れ直さない")
        val deleteComment = rows(document("design/detail/final/processes/delete-todo-comment.yaml"), "steps")
        val rowDelete = deleteComment.single { it["kind"] == "delete" }
        assertEquals("MTH-todo.deleteTodoCommentCommentsRow", rowDelete["call"], "呼ぶ Mapper のメソッドは ID で参照する")
        assertEquals(mapOf("item" to "seq", "param" to "row"), rowDelete["rowKey"])
        assertFalse(rowDelete.containsKey("key"), "親のキーだけの全件削除にしない")
        val child = document("design/detail/final/processes/todo-task.finish.yaml")
        assertTrue(rows(child, "steps").filter { it["step"] == "check" }.all { it["entity"] == "ENT-todo" })
        val after = rows(child, "steps").single { it["phase"] == "after" }
        assertEquals(mapOf("self" to "todo_id"), after["key"])
        val check = document("design/detail/composed/application/process-contract.yaml")
        assertEquals(15, rows(check, "processes").size)
        assertTrue(rows(check, "processes").all { it["transactionMode"] == "atomic" && rows(it, "calls").all { call -> call["method"] != null } })
    }

    @Test
    fun `状態型と例外と並び順の呼出名への補正が生成値に届く`() {
        val input = store()
        val corrections = raw("""
            inputId: INPUT-detail-document-corrections
            corrections:
              - {id: SCOR-exception, kind: error-handling, target: DOC-error-handling.error-handling, select: {collection: exceptions, key: kind, equals: refused}, set: {class: OperationRefused}, reason: 検証}
        """.trimIndent()).toMutableMap()
        // 状態の型への個別の判断は、項目の ID（型 ENM-・判定 ENG-）を対象にして、項目へ直接当てる
        put(input, "design/detail/inputs/decisions.yaml", raw("""
            inputId: INPUT-detail-decisions
            decisions:
              - {id: DDEC-901, target: ENM-todo-lifecycle, set: {enum: TaskState}, reason: 検証}
              - {id: DDEC-902, target: ENG-todo-ACTIVE, set: {method: isOpenTask}, reason: 検証}
              - {id: DDEC-903, target: MTH-todo-task.maxSeq, set: {name: lastPosition}, reason: 検証}
        """.trimIndent()))
        put(input, "design/detail/inputs/documents/corrections.yaml", corrections)
        val plan = Generators.plan(WorkspaceSession(input), setOf("application-runtime-model", "application-process-gate"))
        assertEquals(emptyList(), plan.errors)
        val runtime = raw(plan.outputs.single { it.generator == "application-runtime-model" }.text)
        assertEquals("TaskState", rows(runtime, "states").single()["name"])
        assertEquals(listOf("isOpenTask"), rows(rows(runtime, "states").single(), "groups").map { it["method"] })
        val statusItem = raw(plan.outputs.single { it.document.value == "design/detail/final/status/todo-lifecycle.yaml" }.text)
        assertEquals(setOf(listOf("DDEC-901"), listOf("DDEC-902")), rows(statusItem, "decisions").map { it["by"] }.toSet(), "当てた判断は項目に残る")
        assertEquals("ENC-todo-CLOSED", rows(statusItem, "transitions").single { it["action"] == "complete-todo" }["to"], "同じ項目の中の定数は ID で指す")
        assertEquals("OperationRefused", rows(runtime, "errors").single { it["kind"] == "refused" }["name"])
        val process = raw(plan.outputs.single { it.document.value == "design/detail/final/processes/add-todo-task.yaml" }.text)
        val insert = rows(process, "steps").single { it["kind"] == "insert" }
        val sequence = (((insert["values"] as Map<*, *>)["seq"] as Map<*, *>)["sequence"] as Map<*, *>)
        assertEquals("MTH-todo-task.maxSeq", sequence["call"], "メソッドの名前を変えても、ステップが参照する ID は変わらない")
        assertTrue("TodoTaskMapper.lastPosition" in plan.outputs.single { it.generator == "application-process-gate" }.text, "変更後の名前は、ID から解決して下流に出る")
    }

    private fun probe(input: InMemoryDocumentStore, generator: String) {
        // 前回の生成物は通常の入力から外れるため、検査用の値を印なしの入力にする。
        val files = if (generator == "application-runtime-model") {
            listOf("design/detail/final/status/todo-lifecycle.yaml", "design/detail/final/error-handling.yaml")
        } else {
            // 処理の手順は、Mapper のメソッド・メッセージ・状態の定数を ID で参照するので、参照先のレコードも入力にする
            listOf("processes", "table-access", "status").flatMap { directory ->
                Files.list(sample.resolve("design/detail/final/$directory")).use { paths ->
                    paths.map { "design/detail/final/$directory/${it.fileName}" }.toList()
                }
            } + "design/detail/final/application-access.yaml" + "design/basic/final/messages.yaml"
        }
        files.forEach { put(input, it, raw(input.read(DocumentId(it))!!.text)) }
        val definition = TodosSysdevFiles.definition(sample, "transforms", generator)
        val copy = raw(Files.readString(sample.resolve(definition))).toMutableMap()
        copy["id"] = "service-foundation-probe"
        copy["dependsOn"] = emptyList<String>()
        copy["output"] = "probe/foundation.yaml"
        put(input, ".yde/transforms/design/detail/service-foundation-probe.yaml", copy)
    }

    @Test
    fun `存在しない状態と重なる遷移と使えない判定名を拒否する`() {
        for (invalid in listOf("state", "duplicate", "clone", "finalize", "byte")) {
            val input = store()
            probe(input, "application-runtime-model")
            assertEquals(emptyList(), Generators.plan(WorkspaceSession(input), setOf("service-foundation-probe")).errors)
            val file = "design/detail/final/status/todo-lifecycle.yaml"
            val source = raw(input.read(DocumentId(file))!!.text).toMutableMap()
            if (invalid !in listOf("state", "duplicate")) {
                source["groups"] = rows(source, "groups").map { it.toMutableMap().also { group -> group["method"] = invalid } }
            } else {
                val transitions = rows(source, "transitions").toMutableList()
                if (invalid == "duplicate") transitions.add(transitions.first())
                else transitions[0] = transitions[0].toMutableMap().also { it["to"] = "MISSING" }
                source["transitions"] = transitions
            }
            put(input, file, source)
            val plan = Generators.plan(WorkspaceSession(input), setOf("service-foundation-probe"))
            val diagnostic = when (invalid) {
                "state" -> "未定義の状態"
                "duplicate" -> "transitionPairs"
                else -> "method"
            }
            assertTrue(plan.errors.any { diagnostic in it }, plan.errors.toString())
            assertTrue(plan.transaction.operations.isEmpty())
        }
    }

    @Test
    fun `標準型と同名に補正しても状態型と業務例外をコンパイルできる`() {
        val copy = Files.createTempDirectory("sysdev-runtime-names")
        try {
            TodosSysdevFiles.copy(sample, copy)
            val runtimeFile = copy.resolve("design/detail/final/application-runtime-model.yaml")
            val runtime = raw(Files.readString(runtimeFile)).toMutableMap()
            runtime["states"] = rows(runtime, "states").map { it.toMutableMap().also { state -> state["name"] = "String" } }
            runtime["errors"] = rows(runtime, "errors").map {
                it.toMutableMap().also { error -> error["name"] = if (error["kind"] == "refused") "RuntimeException" else "String" }
            }
            Files.writeString(runtimeFile, YamlIO.dumpPlain(YamlIO.fromAny(runtime)))
            val workspace = Workspace.open(copy)
            val reports = Reports.load(copy)
            val views = Views.load(copy, workspace.schemas)
            assertEquals(emptyList(), reports.errors)
            assertEquals(emptyList(), views.errors)
            val outputs = listOf(
                "app-status" to "JSTATE-todo-lifecycle",
                "app-exception" to "JERR-refused",
                "app-exception" to "JERR-notFound",
                "app-business-violation" to null,
            ).map { (report, record) ->
                val params = record?.let { mapOf("runtime" to it) } ?: emptyMap()
                val result = Reports.run(reports.reports.single { it.def.id == report }, params, views,
                    workspace.index, workspace::treeOf, workspace::schemaFor)
                assertEquals(emptyList(), result.missing)
                copy.resolve(result.outputRel!!).also { file ->
                    Files.createDirectories(file.parent)
                    Files.writeString(file, result.text)
                }.toString()
            }
            val classes = Files.createDirectories(copy.resolve("compiled"))
            val diagnostics = ByteArrayOutputStream()
            val code = ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
                "-encoding", "UTF-8", "-d", classes.toString(), *outputs.toTypedArray())
            assertEquals(0, code, diagnostics.toString(Charsets.UTF_8))
        } finally {
            copy.toFile().deleteRecursively()
        }
    }

    @Test
    fun `並び順の未解決の呼び先も生成前に拒否する`() {
        val input = store()
        probe(input, "application-process-gate")
        assertEquals(emptyList(), Generators.plan(WorkspaceSession(input), setOf("service-foundation-probe")).errors)
        val file = "design/detail/final/processes/add-todo-task.yaml"
        input.write(DocumentId(file), input.read(DocumentId(file))!!.text.replace("MTH-todo-task.maxSeq", "MTH-todo-task.missing"))
        val plan = Generators.plan(WorkspaceSession(input), setOf("service-foundation-probe"))
        assertTrue(plan.errors.any { "呼び先に対応する" in it }, plan.errors.toString())
        assertTrue(plan.transaction.operations.isEmpty())
    }
}
