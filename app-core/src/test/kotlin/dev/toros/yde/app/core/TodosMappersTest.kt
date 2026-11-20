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
        assertEquals(48, expected.size)
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
        write(input, "design/detail/inputs/documents/corrections.yaml", """
            inputId: INPUT-detail-document-corrections
            corrections:
              - {id: SCOR-mapper, kind: table-access, target: DOC-table-access.todo, set: {mapper: TodoStore}, reason: 検証}
              - {id: SCOR-select, kind: table-access, target: DOC-table-access.todo, select: {collection: methods, key: methodId, equals: selectTodoDashboardAll}, set: {name: readAll}, reason: 検証}
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
    fun `不明な条件参照は確定前に拒否して元の出力を保つ`() {
        val input = store()
        val original = input.read(DocumentId("design/detail/final/application-access.yaml"))!!.text
        write(input, "design/detail/inputs/documents/corrections.yaml", """
            inputId: INPUT-detail-document-corrections
            corrections:
              - {id: SCOR-source, kind: table-access, target: DOC-table-access.todo, select: {collection: methods, key: methodId, equals: countTodoDashboardAll}, set: {whereFrom: missingMethod}, reason: 検証}
        """)
        val plan = Generators.plan(WorkspaceSession(input), setOf("application-access"))
        assertTrue(plan.errors.isNotEmpty())
        assertTrue(plan.errors.any { "同じ条件" in it }, plan.errors.toString())
        assertTrue(plan.transaction.operations.isEmpty())
        assertEquals(original, input.read(DocumentId("design/detail/final/application-access.yaml"))!!.text)
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
