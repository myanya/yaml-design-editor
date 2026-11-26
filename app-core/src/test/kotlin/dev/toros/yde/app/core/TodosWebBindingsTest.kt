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

/** 保存した確定値を使い、Form の名前・型・入れ子と処理の呼出先の境界を確かめる。 */
class TodosWebBindingsTest {
    private val sample = Path.of("samples/todos-sysdev")
    private val formsFile = "design/detail/final/application-web-forms.yaml"
    private val bindingsFile = "design/detail/final/application-web-bindings.yaml"
    private fun raw(text: String) = YamlIO.parseToRaw(text) as Map<*, *>
    private fun rows(value: Map<*, *>, key: String) = (value[key] as List<*>).map { it as Map<*, *> }
    private fun dump(value: Any) = YamlIO.dumpPlain(YamlIO.fromAny(value))
    private fun sources() = TodosSysdevFiles.inputs(sample).filter { it.toString().endsWith(".yaml") }.associate {
        val file = sample.relativize(it).toString().replace('\\', '/')
        file to Files.readString(it).let { text -> if ("/final/" in file) dump(raw(text)) else text }
    }
    private fun probe(sources: Map<String, String>, forms: Map<*, *>? = null, changes: Map<String, Map<*, *>> = emptyMap(), expectValid: Boolean = false): String? {
        val store = InMemoryDocumentStore(sources)
        val definition = raw(sources.getValue(TodosSysdevFiles.definition(sample, "transforms", "application-web-bindings"))).toMutableMap()
        definition["id"] = "web-bindings-probe"
        definition["dependsOn"] = emptyList<String>()
        definition["output"] = "probe/web-bindings.yaml"
        store.write(DocumentId(".yde/transforms/design/detail/web-bindings-probe.yaml"), dump(definition))
        if (forms != null) store.write(DocumentId(formsFile), dump(forms))
        changes.forEach { (file, value) -> store.write(DocumentId(file), dump(value)) }
        val plan = Generators.plan(WorkspaceSession(store), setOf("web-bindings-probe"))
        if (expectValid) assertEquals(emptyList(), plan.errors)
        if (plan.errors.isNotEmpty()) {
            assertTrue(plan.transaction.operations.isEmpty())
            return null
        }
        return plan.outputs.single().text
    }
    private fun forms(sources: Map<String, String>, change: (Map<*, *>) -> Map<*, *>) = raw(sources.getValue(formsFile)).toMutableMap().also {
        it["forms"] = rows(it, "forms").map(change)
    }
    private fun fields(record: Map<*, *>, change: (Map<*, *>) -> Map<*, *>?) = record.toMutableMap().also {
        it["fields"] = rows(record, "fields").mapNotNull(change)
    }

    @Test
    fun `全操作の入力と確認用メソッドを同じ確定値から接続する`() {
        val generated = raw(requireNotNull(probe(sources(), expectValid = true)))
        val operations = rows(generated, "operations")
        assertEquals(14, operations.size)
        assertEquals(13, operations.count { it["formClass"] != null }, "入力の無い着手も、読んだときの版を運ぶ Form を持つ（Form が無いのは、作業項目を済にする操作だけ）")
        assertEquals(4, operations.count { rows(it, "confirm").isNotEmpty() })
        assertEquals(raw(Files.readString(sample.resolve(bindingsFile))), generated)
        val edit = operations.single { it["action"] == "edit-todo" }
        val assignments = rows(edit["fields"] as Map<*, *>, "assignments")
        assertTrue(assignments.none { it["item"] == "comments" }, "コメント一覧は編集の入力に無い")
        assertTrue(dump(edit).contains("valueClear.setDueOn(form.getClear().getDueOn())"))
        // 並びの番号（row）は、Form から Service の入力へそのまま渡る
        val editComment = operations.single { it["action"] == "edit-todo-comment" }
        assertTrue(rows(editComment["fields"] as Map<*, *>, "assignments").any { it["item"] == "row" })
    }

    @Test
    fun `入力名と明細クラス名の補正は項目キーで接続し直す`() {
        val sources = sources()
        val renamed = forms(sources) { form ->
            if (form["className"] != "EditTodoForm") form else fields(form) { field -> field.toMutableMap().also {
                if (field["item"] == "created_on") { it["name"] = "creationDate"; it["accessor"] = "CreationDate" }
            } }.toMutableMap().also { result ->
                result["classes"] = rows(form, "classes").map { nested -> fields(nested) { field -> field.toMutableMap().also {
                    if (field["item"] == "due_on") { it["name"] = "deadline"; it["accessor"] = "Deadline" }
                } } }
            }
        }
        val programsFile = "design/detail/final/application-service-programs.yaml"
        val programs = raw(sources.getValue(programsFile)).toMutableMap().also { doc ->
            doc["services"] = rows(doc, "services").map { service -> service.toMutableMap().also { changed ->
                changed["operations"] = rows(service, "operations").map { operation -> operation.toMutableMap().also {
                    if (operation["method"] == "editTodo") {
                        it["method"] = "updateTodo"
                        it["confirm"] = rows(operation, "confirm").map { confirm -> confirm.toMutableMap().also { c -> c["method"] = "checkChange"; c["operationMethod"] = "updateTodo" } }
                    }
                } }
            } }
        }
        val controllerFile = "design/detail/final/controllers/todo.yaml"
        // 名前の前方一致で、コメントの編集（editTodoComment・checkEditTodoComment）まで書き換えない
        val controller = raw(sources.getValue(controllerFile)
            .replace(Regex("""TodoService\.editTodo(?![A-Za-z])"""), "TodoService.updateTodo")
            .replace(Regex("""TodoService\.checkEditTodo(?![A-Za-z])"""), "TodoService.checkChange"))
        val text = requireNotNull(probe(sources, renamed, mapOf(programsFile to programs, controllerFile to controller), expectValid = true))
        val copy = Files.createTempDirectory("sysdev-web-binding")
        try {
            TodosSysdevFiles.copy(sample, copy)
            Files.writeString(copy.resolve(bindingsFile), text)
            val workspace = Workspace.open(copy)
            val reports = Reports.load(copy)
            val views = Views.load(copy, workspace.schemas)
            val result = Reports.run(reports.reports.single { it.def.id == "app-web-operation" }, mapOf("webOperation" to "JWEB-edit-todo"), views, workspace.index, workspace::treeOf, workspace::schemaFor)
            assertEquals(emptyList(), result.missing)
            assertTrue("input.setCreatedOn(form.getCreationDate())" in result.text)
            assertTrue("valueClear.setDueOn(form.getClear().getDeadline())" in result.text)
            assertTrue("service.updateTodo(" in result.text)
            assertTrue("service.checkChange(" in result.text)
        } finally { copy.toFile().deleteRecursively() }
    }

    @Test
    fun `対応のない項目や重複や型の食い違いを保存前に拒否する`() {
        val sources = sources()
        for (change in listOf("missing", "extra", "duplicate", "type", "nested-type", "nested-extra", "controller", "confirm")) {
            val changedForms = forms(sources) { form ->
                if (form["className"] != "EditTodoForm") form else fields(form) { field ->
                    if (change == "missing" && field["item"] == "title") null else field.toMutableMap().also {
                        if (change == "type" && field["item"] == "title") it["type"] = "Long"
                        if (change == "nested-type" && field["item"] == "clear") it["type"] = "UnknownClear"
                    }
                }.also { result ->
                    if (change in listOf("extra", "duplicate")) result["fields"] = rows(result, "fields") + rows(result, "fields").first().toMutableMap().also {
                        it["name"] = "extra"; it["accessor"] = "Extra"
                        if (change == "extra") it["item"] = "extra"
                    }
                    if (change == "nested-extra") result["classes"] = rows(form, "classes").map { nested -> nested.toMutableMap().also {
                        if (nested["name"] == "EditTodoClear") it["fields"] = rows(nested, "fields") + mapOf("name" to "extra", "item" to "extra", "accessor" to "Extra", "type" to "String")
                    } }
                }
            }
            val controllerFile = "design/detail/final/controllers/todo.yaml"
            val controller = raw(sources.getValue(controllerFile)).toMutableMap().also { doc ->
                doc["handlers"] = rows(doc, "handlers").map { handler -> handler.toMutableMap().also { edited ->
                    if (handler["action"] == "edit-todo") {
                        if (change == "controller") edited["service"] = "TodoService.unknown"
                        if (change == "confirm") edited["steps"] = rows(handler, "steps").map { step -> step.toMutableMap().also {
                            if (step["calls"] != null && (step["calls"] as List<*>).isNotEmpty()) it["calls"] = listOf("TodoService.unknownCheck")
                        } }
                    }
                } }
            }
            assertTrue(probe(sources, changedForms, mapOf(controllerFile to controller)) == null, change)
        }
    }
}
