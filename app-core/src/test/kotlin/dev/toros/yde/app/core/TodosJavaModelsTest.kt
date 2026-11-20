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

/** Form と行モデルの共通型、物理名の補正、生成できない入力の停止を確認する。 */
class TodosJavaModelsTest {
    private val sample = Path.of("samples/todos-sysdev")
    private fun raw(text: String) = YamlIO.parseToRaw(text) as Map<*, *>
    private fun rows(value: Map<*, *>, key: String) = (value[key] as List<*>).map { it as Map<*, *> }
    private fun store() = InMemoryDocumentStore(TodosSysdevFiles.inputs(sample).filter { it.toString().endsWith(".yaml") }
        .associate { sample.relativize(it).toString().replace('\\', '/') to Files.readString(it) })
    private fun write(store: InMemoryDocumentStore, file: String, value: Any?) = store.write(DocumentId(file), YamlIO.dumpPlain(YamlIO.fromAny(value)))

    @Test
    fun `すべての物理列に型があり 親キーと配列の要素とfalseを保つ`() {
        val model = raw(Files.readString(sample.resolve("design/detail/final/application-data-model.yaml")))
        val tables = raw(Files.readString(sample.resolve("design/detail/final/tables.yaml")))
        val expected = rows(tables, "tables").flatMap { rows(it, "columns") }.map { it["id"] }.toSet()
        val columns = rows(model, "models").flatMap { rows(it, "columns") }.associateBy { it["source"] }
        assertEquals(expected, columns.keys)
        fun type(id: String) = (columns.getValue("COL-$id")["java"] as Map<*, *>)["qualifiedName"]
        assertEquals("java.lang.String", type("todo_task.todo_id"))
        assertEquals("java.lang.String", type("todo_tags.tag_id"))
        assertEquals("java.lang.Long", type("todo_task.seq"))
        assertEquals("java.lang.Boolean", type("todo_task.done"))
        assertEquals("java.time.LocalDate", type("todo.created_on"))
        assertEquals(mapOf("value" to false, "literal" to "boolean"), columns.getValue("COL-todo_task.done")["default"])
    }

    @Test
    fun `Form は共通型を読み 数と日付と真偽値の配列にも要素型を使う`() {
        val input = store()
        val source = TodosSysdevFiles.definition(sample, "transforms", "screen-designs-operations-derived")
        val probe = raw(Files.readString(sample.resolve(source))).toMutableMap()
        probe["id"] = "java-fields-probe"
        probe["dependsOn"] = listOf("application-java-types")
        probe["each"] = "fields"
        probe["output"] = "probe/{key}.yaml"
        probe["input"] = mapOf("fields" to listOf(
            mapOf("key" to "optionalFlag", "base" to "boolean"),
            mapOf("key" to "quantity", "base" to "int"),
            mapOf("key" to "dates", "base" to "array", "item" to mapOf("base" to "date")),
            mapOf("key" to "flags", "base" to "array", "item" to mapOf("base" to "boolean")),
            mapOf("key" to "amounts", "base" to "array", "item" to mapOf("base" to "number")),
        ))
        probe["build"] = mapOf("\$apply" to "java-field")
        write(input, ".yde/transforms/java-fields-probe.yaml", probe)
        val plan = Generators.plan(WorkspaceSession(input), setOf("java-fields-probe"))
        assertEquals(emptyList(), plan.errors)
        val fields = plan.outputs.filter { it.generator == "java-fields-probe" }.map { raw(it.text) }.associate { it["item"].toString() to it["type"].toString() }
        assertEquals(mapOf("optionalFlag" to "Boolean", "quantity" to "Long", "dates" to "List<LocalDate>", "flags" to "List<Boolean>", "amounts" to "List<BigDecimal>"), fields)
    }

    @Test
    fun `物理名の補正は行モデルへ伝わり SQLとJavaの不一致と名前衝突は停止する`() {
        val input = store()
        val correctionFile = "design/detail/inputs/tables/corrections.yaml"
        val correction = raw(input.read(DocumentId(correctionFile))!!.text).toMutableMap()
        correction["corrections"] = listOf(mapOf("id" to "DCOR-java", "target" to "COL-todo.title", "set" to mapOf("name" to "subject"), "reason" to "物理名の補正の検証"))
        write(input, correctionFile, correction)
        val changed = Generators.plan(WorkspaceSession(input), setOf("application-data-model"))
        assertEquals(emptyList(), changed.errors)
        val model = raw(changed.outputs.single { it.generator == "application-data-model" }.text)
        val todo = rows(model, "models").single { it["table"] == "todo" }
        val title = rows(todo, "columns").single { it["source"] == "COL-todo.title" }
        assertEquals("subject", title["column"])
        assertEquals("subject", title["name"])
        correction["corrections"] = listOf(
            mapOf("id" to "DCOR-collision", "target" to "COL-todo.title", "set" to mapOf("name" to "id"), "reason" to "衝突の検証"),
            mapOf("id" to "DCOR-type", "target" to "SQL-boolean", "set" to mapOf("sql" to "VARCHAR"), "reason" to "型の不一致の検証"),
        )
        write(input, correctionFile, correction)
        val failed = Generators.plan(WorkspaceSession(input), setOf("application-data-model"))
        assertTrue(failed.errors.any { "Java の boolean と SQL" in it }, failed.errors.toString())
        assertTrue(failed.errors.any { "重複" in it || "重な" in it }, failed.errors.toString())
        assertTrue(failed.transaction.operations.isEmpty())
    }

    @Test
    fun `対応を削除してもStringに置き換えず確定前に止める`() {
        val input = store()
        val file = "design/detail/inputs/application/java-types.yaml"
        val original = raw(input.read(DocumentId(file))!!.text).toMutableMap()
        original["mappings"] = rows(original, "mappings").filterNot { it["base"] == "boolean" }
        write(input, file, original)
        val failed = Generators.plan(WorkspaceSession(input), setOf("application-data-model"))
        assertTrue(failed.errors.any { "java" in it && "必須" in it }, failed.errors.toString())
        assertTrue(failed.transaction.operations.isEmpty())
        val form = Generators.plan(WorkspaceSession(input), setOf("application-form-gate"))
        assertTrue(form.errors.any { "type" in it && "必須" in it }, form.errors.toString())
        assertTrue(form.transaction.operations.isEmpty())
    }
}
