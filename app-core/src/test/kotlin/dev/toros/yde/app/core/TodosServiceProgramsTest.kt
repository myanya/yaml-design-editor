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

/** 業務の手順を生成時に解決し、未対応の式を実行時へ持ち越さない。 */
class TodosServiceProgramsTest {
    private val sample = Path.of("samples/todos-sysdev")
    private val output = "design/detail/final/application-service-programs.yaml"
    private fun raw(text: String) = YamlIO.parseToRaw(text) as Map<*, *>
    private fun rows(value: Map<*, *>, key: String) = (value[key] as List<*>).map { it as Map<*, *> }
    private fun put(store: InMemoryDocumentStore, file: String, value: Any) = store.write(DocumentId(file), YamlIO.dumpPlain(YamlIO.fromAny(value)))
    private fun probe(): InMemoryDocumentStore {
        val store = InMemoryDocumentStore(TodosSysdevFiles.inputs(sample).filter { it.toString().endsWith(".yaml") }.associate {
            val path = sample.relativize(it).toString().replace('\\', '/')
            val text = Files.readString(it)
            path to if ("/final/" in path) YamlIO.dumpPlain(YamlIO.fromAny(raw(text))) else text
        })
        val definition = raw(Files.readString(sample.resolve(TodosSysdevFiles.definition(sample, "transforms", "application-service-programs")))).toMutableMap()
        definition["dependsOn"] = emptyList<String>()
        definition["id"] = "service-program-probe"
        definition["output"] = "probe/programs.yaml"
        put(store, ".yde/transforms/design/detail/service-program-probe.yaml", definition)
        return store
    }
    private fun code(document: Map<*, *>): String = rows(document, "services").flatMap { rows(it, "operations") }
        .flatMap { rows(it, "tokens") }.joinToString("") { it["raw"]?.toString() ?: it["value"]?.toString().orEmpty() }

    @Test
    fun `全操作の本体と確認を確定し未解決の式を残さない`() {
        val document = raw(Files.readString(sample.resolve(output)))
        val services = rows(document, "services")
        val operations = services.flatMap { rows(it, "operations") }
        assertEquals(4, services.size)
        assertEquals(12, operations.size)
        assertTrue(operations.flatMap { rows(it, "tokens") }.all { it["valid"] == true })
        val text = code(document)
        assertTrue("cursor = linked.getFollows()" in text)
        assertTrue("before4.getUpdatedOn()" in text || "getUpdatedOn()" in text)
        assertTrue("countClosedHasNoOpenTask" in text)
        assertTrue("if (preview) return new OperationOutcome" in text)
        assertTrue("setDueOn(null)" in text)
        assertTrue("LocalDate.parse(null)" !in text)
        val edit = operations.single { it["method"] == "editTodo" }
        assertEquals("java.util.List<EditTodoInput.EditTodoCommentsItem>", rows(edit, "arguments").single { it["name"] == "comments" }["type"])
    }

    @Test
    fun `未対応の比較と解決できない参照では生成を止める`() {
        val store = probe()
        assertEquals(emptyList(), Generators.plan(WorkspaceSession(store), setOf("service-program-probe")).errors)
        val file = "design/detail/final/processes/register-todo.yaml"
        val original = raw(store.read(DocumentId(file))!!.text)
        for (change in listOf("comparison", "reference", "target", "type")) {
            val document = original.toMutableMap()
            document["steps"] = rows(original, "steps").map { step -> step.toMutableMap().also {
                if (it["constraint"] == "due-not-before-created") {
                    when (change) {
                        "comparison" -> it["rule"] = mapOf("check" to listOf(mapOf("mystery" to listOf(mapOf("path" to "due_on")))))
                        "reference" -> it["rule"] = mapOf("check" to listOf(mapOf("gte" to listOf(mapOf("path" to "missing"), mapOf("path" to "created_on")))))
                        "target" -> it["at"] = "missing[*]"
                        "type" -> it["rule"] = mapOf("check" to listOf(mapOf("gte" to listOf(mapOf("path" to "due_on"), mapOf("path" to "title")))))
                    }
                }
            } }
            put(store, file, document)
            val plan = Generators.plan(WorkspaceSession(store), setOf("service-program-probe"))
            assertTrue(plan.errors.any { "Service の式・参照・手順" in it }, "$change: ${plan.errors}")
            assertTrue(plan.transaction.operations.isEmpty())
        }
    }

    @Test
    fun `確認の引数を実行と違う指定のまま無視しない`() {
        val store = probe()
        val file = "design/detail/final/processes/register-todo.yaml"
        val document = raw(store.read(DocumentId(file))!!.text).toMutableMap()
        document["confirm"] = rows(document, "confirm").map { it.toMutableMap().also { confirm -> confirm["arguments"] = emptyList<String>() } }
        put(store, file, document)
        val plan = Generators.plan(WorkspaceSession(store), setOf("service-program-probe"))
        assertTrue(plan.errors.any { "確認と実行の引数" in it }, plan.errors.toString())
        assertTrue(plan.transaction.operations.isEmpty())
    }
}
