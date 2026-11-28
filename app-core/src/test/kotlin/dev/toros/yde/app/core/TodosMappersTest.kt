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

/** Mapper の材料は確定設計から作り、補正を反映し、未解決の条件を SQL にしない。 */
class TodosMappersTest {
    private val sample = Path.of("samples/todos-sysdev")
    private fun raw(text: String) = YamlIO.parseToRaw(text) as Map<*, *>
    private fun rows(value: Map<*, *>, key: String) = (value[key] as List<*>).map { it as Map<*, *> }
    private fun store() = InMemoryDocumentStore(TodosSysdevFiles.inputs(sample).filter { it.toString().endsWith(".yaml") }
        .associate { sample.relativize(it).toString().replace('\\', '/') to Files.readString(it) })
    private fun write(store: InMemoryDocumentStore, file: String, text: String) = store.write(DocumentId(file), text.trimIndent())

    @Test
    fun `全メソッドを網羅し 一覧と件数は同じ検索条件と型を持つ`() {
        val access = raw(Files.readString(sample.resolve("design/detail/final/application-access.yaml")))
        val mappers = rows(access, "mappers")
        val expected = listOf("category", "tag", "todo", "todo-task").flatMap { id ->
            val source = raw(Files.readString(sample.resolve("design/detail/final/table-access/$id.yaml")))
            rows(source, "methods").map { source["mapper"].toString() + "." + it["name"] }
        }.toSet()
        assertEquals(expected, mappers.flatMap { m -> rows(m, "methods").map { m["name"].toString() + "." + it["name"] } }.toSet())
        assertEquals(53, expected.size)
        // 並びの番号で指す操作は、明細テーブルの 1 行を、親のキーと並び順（seq）で指す update・delete になる
        val table = rows(raw(Files.readString(sample.resolve("design/detail/final/table-access/todo.yaml"))), "methods").associateBy { it["name"] }
        val editRow = table.getValue("editTodoCommentCommentsRow")
        assertEquals(listOf("update", "TBL-todo_comments"), listOf(editRow["kind"], editRow["table"]))
        assertEquals(listOf("date", "body"), rows(editRow, "set").map { it["item"] })
        assertEquals(listOf("todo_id" to "id", "seq" to "row"), rows(editRow, "where").map { it["item"] to it["param"] })
        val deleteRow = table.getValue("deleteTodoCommentCommentsRow")
        assertEquals(listOf("delete", "TBL-todo_comments"), listOf(deleteRow["kind"], deleteRow["table"]))
        assertEquals(listOf("todo_id" to "id", "seq" to "row"), rows(deleteRow, "where").map { it["item"] to it["param"] })
        // 番号の無い入れ替え（タグ）の全件削除は、親のキーだけで消す（番号つきの効果では作らない）
        assertEquals(listOf("todo_id" to "id"), rows(table.getValue("deleteTodoTags"), "where").map { it["item"] to it["param"] })
        assertTrue("deleteTodoComments" !in table, "コメントの丸ごとの入れ替えは無くなった")
        val methods = rows(mappers.single { it["name"] == "TodoMapper" }, "methods").associateBy { it["name"] }
        for (suffix in listOf("All", "Overdue")) {
            val select = methods.getValue("selectTodoDashboard$suffix")
            val count = methods.getValue("countTodoDashboard$suffix")
            assertEquals(select["conditions"], count["conditions"])
            assertEquals(select["parameters"], count["parameters"])
            assertEquals(true, count["conditionReferenceResolved"])
        }
    }

    @Test
    fun `物理名とメソッド名の補正を確定したMapperへ伝える`() {
        val input = store()
        write(input, "design/detail/inputs/tables/corrections.yaml", """
            corrections:
              - {id: DCOR-mapper-title, target: COL-todo.title, set: {name: subject}, reason: 検証}
        """)
        // 名前の判断は、テーブルへの読み書きの項目（まとまり ACC-・メソッド MTH-）を ID で指す
        write(input, "design/detail/inputs/decisions.yaml", """
            inputId: INPUT-detail-decisions
            decisions:
              - {id: DDEC-mapper, target: ACC-todo, set: {mapper: TodoStore}, reason: 検証}
              - {id: DDEC-select, target: MTH-todo.selectTodoDashboardAll, set: {name: readAll}, reason: 検証}
        """)
        val plan = Generators.plan(WorkspaceSession(input), setOf("application-access"))
        assertEquals(emptyList(), plan.errors)
        val mapper = rows(raw(plan.outputs.single { it.generator == "application-access" }.text), "mappers").single { it["name"] == "TodoStore" }
        val methods = rows(mapper, "methods")
        val select = methods.single { it["name"] == "readAll" }
        val choices = methods.single { it["name"] == "selectAll" }
        assertEquals("subject", rows(choices, "properties").single { it["name"] == "column1" }["column"])
        assertEquals(select["conditions"], methods.single { it["name"] == "countTodoDashboardAll" }["conditions"])
        assertEquals("subject", rows(choices, "baseBindings").single { it["source"] == "COL-todo.title" }["name"])
    }

    @Test
    fun `無い対象と置き換えられない値への判断は確定前に拒否して元の出力を保つ`() {
        val original = Files.readString(sample.resolve("design/detail/final/application-access.yaml"))
        val cases = listOf(
            "{id: DDEC-x, target: MTH-todo.missingMethod, set: {name: other}, reason: 検証}",
            "{id: DDEC-x, target: ACC-missing, set: {mapper: OtherMapper}, reason: 検証}",
            "{id: DDEC-x, target: MTH-todo.findById, set: {mapper: OtherMapper}, reason: 検証}",
            "{id: DDEC-x, target: ACC-todo, set: {name: other}, reason: 検証}",
        )
        for (case in cases) {
            val input = store()
            write(input, "design/detail/inputs/decisions.yaml", "inputId: INPUT-detail-decisions\ndecisions:\n  - $case\n")
            val plan = Generators.plan(WorkspaceSession(input), setOf("application-access"))
            assertTrue(plan.errors.any { "detail-table-access" in it && "DDEC-x" in it }, "$case: ${plan.errors}")
            assertTrue(plan.transaction.operations.isEmpty(), case)
            assertEquals(original, input.read(DocumentId("design/detail/final/application-access.yaml"))!!.text)
        }
    }

    @Test
    fun `論理削除は方式の指定で切り替わり 指定を外すと行を消し 無い対象は止まる`() {
        fun plan(specifications: String): dev.toros.yde.workspace.GenerationPlan {
            val input = store()
            write(input, "design/detail/inputs/specifications.yaml", "inputId: INPUT-detail-specifications\nspecifications: $specifications\n")
            return Generators.plan(WorkspaceSession(input), setOf("application-access"))
        }
        fun doc(plan: dev.toros.yde.workspace.GenerationPlan, path: String) = raw(plan.outputs.single { it.document.value == path }.text)
        fun taskColumns(plan: dev.toros.yde.workspace.GenerationPlan) = rows(rows(doc(plan, "design/detail/final/tables.yaml"), "tables").single { it["id"] == "TBL-todo_task" }, "columns")
        fun taskMethods(plan: dev.toros.yde.workspace.GenerationPlan) = rows(doc(plan, "design/detail/final/table-access/todo-task.yaml"), "methods").associateBy { it["id"].toString().substringAfter(".") }
        fun flagged(method: Map<*, *>) = (method["where"] as? List<*>).orEmpty().any { (it as Map<*, *>)["column"] == "COL-todo_task.deleted" && it["equals"] == false }
        // 指定あり（サンプルの状態）: 印のカラムが足され、消すメソッドは印を立て、読む・数えるメソッドは印の無い行だけを見る
        val on = plan("[{id: DSPC-001, rule: soft-delete, target: ENT-todo-task, reason: 検証}]")
        assertEquals(emptyList(), on.errors)
        val flag = taskColumns(on).last()
        assertEquals(listOf("deleted", "deleted", "DSPC-001", false, true), listOf(flag["name"], flag["role"], flag["addedBy"], flag["default"], flag["notNull"]))
        val soft = taskMethods(on)
        val softDelete = soft.getValue("deleteTodoTaskTasksRow")
        assertEquals(listOf("update", true), listOf(softDelete["kind"], softDelete["softDelete"]))
        assertEquals(listOf("COL-todo_task.deleted" to true), rows(softDelete, "set").map { it["column"] to it["value"] })
        assertEquals(listOf("ACT-delete-todo-task"), softDelete["sources"], "操作の項目（効果は消す）は変わらない")
        assertTrue(listOf("findById", "countCompleteTodoNoOpenTask", "countClosedHasNoOpenTask", "deleteTodoTaskTasksRow").all { flagged(soft.getValue(it)) })
        assertTrue(listOf("maxId", "maxSeq", "insert", "todoTaskFinish").none { flagged(soft.getValue(it)) }, "採番は消した行も数える（番号を使い回さない）")
        val detail = rows(doc(on, "design/detail/final/table-access/todo.yaml"), "methods").single { it["name"] == "findDetail" }
        assertEquals(listOf(mapOf("item" to "deleted", "equals" to false, "column" to "COL-todo_task.deleted")), rows(detail, "includes").single { it["item"] == "tasks" }["where"], "一緒に読む明細も、消していない行だけ")
        // 指定を外す: カラムも条件も無くなり、行を消す
        val off = plan("[]")
        assertEquals(emptyList(), off.errors)
        assertTrue(taskColumns(off).none { it["name"] == "deleted" })
        val hard = taskMethods(off)
        assertEquals(listOf("delete", null), listOf(hard.getValue("deleteTodoTaskTasksRow")["kind"], hard.getValue("deleteTodoTaskTasksRow")["softDelete"]))
        assertTrue(hard.values.none { flagged(it) })
        assertTrue("deleted" !in off.outputs.single { it.generator == "application-access" }.text)
        // 対象が、基本設計のエンティティにも区分にも無い指定は、何も書かずに止める
        val missing = plan("[{id: DSPC-x, rule: soft-delete, target: ENT-missing, reason: 検証}]")
        assertTrue(missing.errors.any { "detail-tables" in it && "DSPC-x" in it }, missing.errors.toString())
        assertTrue(missing.transaction.operations.isEmpty())
    }

    @Test
    fun `確定の検査は列と演算子とSQLに埋められない定数を拒否する`() {
        val input = store()
        val file = "design/detail/final/application-access.yaml"
        val access = raw(input.read(DocumentId(file))!!.text).toMutableMap()
        val bad = listOf(
            mapOf("operator" to "equals", "table" to "category", "param" to "id"),
            mapOf("operator" to "executeSql", "table" to "category", "column" to "id"),
            mapOf("operator" to "equals", "table" to "category", "column" to "id", "type" to "java.lang.String", "literal" to true, "compare" to true, "constant" to "\${unexpected}"),
        )
        access["mappers"] = rows(access, "mappers").mapIndexed { index, mapper ->
            if (index != 0) mapper else mapper.toMutableMap().also { changed ->
                changed["methods"] = rows(mapper, "methods").mapIndexed { n, method ->
                    if (n >= bad.size) method else method.toMutableMap().also { it["conditions"] = listOf(bad[n]) }
                }
            }
        }
        input.write(DocumentId(file), YamlIO.dumpPlain(YamlIO.fromAny(access)))
        val definition = TodosSysdevFiles.definition(sample, "transforms", "application-access")
        val probe = raw(Files.readString(sample.resolve(definition))).toMutableMap()
        probe["id"] = "mapper-contract-probe"
        probe["dependsOn"] = emptyList<String>()
        probe["output"] = "probe/access.yaml"
        val values = (probe["input"] as Map<*, *>).toMutableMap()
        values["mappers"] = mapOf("\$each" to mapOf("\$view" to "application-access-input"), "\$build" to mapOf("\$copy" to "values.record"))
        probe["input"] = values
        input.write(DocumentId(".yde/transforms/design/detail/mapper-contract-probe.yaml"), YamlIO.dumpPlain(YamlIO.fromAny(probe)))
        val plan = Generators.plan(WorkspaceSession(input), setOf("mapper-contract-probe"))
        assertTrue(plan.errors.any { "列を確定" in it }, plan.errors.toString())
        assertTrue(plan.errors.any { "operator" in it }, plan.errors.toString())
        assertTrue(plan.errors.any { "定数を対象" in it }, plan.errors.toString())
        assertTrue(plan.transaction.operations.isEmpty())
    }
}
