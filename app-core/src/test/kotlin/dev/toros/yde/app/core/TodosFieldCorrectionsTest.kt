package dev.toros.yde.app.core

import dev.toros.yde.workspace.Generators
import dev.toros.yde.workspace.InMemoryDocumentStore
import dev.toros.yde.workspace.WorkspaceReader
import dev.toros.yde.workspace.WorkspaceSession
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TodosFieldCorrectionsTest {
    private val sample = Path.of("samples/todos-sysdev")
    private fun rows(m: Map<*, *>, key: String) = (m[key] as List<*>).map { it as Map<*, *> }
    private fun raw(text: String) = YamlIO.parseToRaw(text) as Map<*, *>
    private fun store(changes: String = "[]", explicit: Boolean = false, extra: Boolean = false): InMemoryDocumentStore {
        val files = sample.resolve(".yde/schemas/business").listDirectoryEntries("*.yaml").sorted().associate { file ->
            val source = Files.readString(file)
            val text = if (explicit && file.fileName.toString() == "common.yaml")
                source.replace("        type: 一行の文\n        role: title", "        type: 一行の文\n        maxLength: 100\n        role: title") else source
            ".yde/schemas/business/${file.fileName}" to text
        }.toMutableMap()
        for (name in listOf("basic-design-values", "basic-derived-model", "basic-derived", "basic-design-contract", "basic-targets", "basic-descriptions", "basic-type-changes", "basic-type-model", "basic-field-uses", "basic-field-changes", "basic-field-model")) {
            val path = ".yde/schemas/design/$name.yaml"
            files[path] = Files.readString(sample.resolve(path))
        }
        for (name in listOf("basic-derived", "basic-targets", "basic-descriptions", "basic-type-changes", "basic-type-model", "basic-field-uses", "basic-field-changes", "basic-field-model")) {
            val path = ".yde/transforms/design/$name.yaml"
            files[path] = Files.readString(sample.resolve(path))
        }
        for (name in listOf("basic-derived-input", "basic-targets-input", "basic-description-corrections-input", "basic-type-changes-input", "basic-type-model-input", "basic-field-uses-input", "basic-field-changes-input")) {
            val path = ".yde/views/design/$name.yaml"
            files[path] = Files.readString(sample.resolve(path))
        }
        files[".yde/schemas/design/basic-corrections.yaml"] = "id: basic-corrections\nmatch: design/basic/corrections*.yaml\nroot: {type: basic-design-contract#補正と指定一覧}\n"
        files[".yde/workspace.yaml"] = "ledger: [design]\nscopes: [{id: business, include: ['schemas/business/**']}]\n"
        files["design/basic/corrections.yaml"] = Files.readString(sample.resolve("design/basic/corrections.yaml"))
        files["design/basic/corrections-test.yaml"] = "corrections: $changes\n"
        if (extra) files[".yde/schemas/business/extra.yaml"] = extraSchema
        return InMemoryDocumentStore(files).also { assertEquals(emptyList(), WorkspaceReader.load(it).schemaErrors) }
    }
    private fun result(store: InMemoryDocumentStore): Map<*, *> {
        val plan = Generators.plan(WorkspaceSession(store))
        assertEquals(emptyList(), plan.errors)
        return raw(plan.outputs.single { it.generator == "basic-field-model" }.text)
    }
    private fun fields(model: Map<*, *>, entity: String) = rows(rows(model, "entities").single { it["id"] == entity }, "aspects").flatMap { rows(it, "fields") }
    private fun field(model: Map<*, *>, entity: String, key: String) = fields(model, entity).first { it["key"] == key }

    @Test
    fun `個別指定の場所を用意しても承認した説明以外の項目の値は変わらない`() {
        val plan = Generators.plan(WorkspaceSession(store()))
        assertEquals(emptyList(), plan.errors)
        val before = raw(plan.outputs.single { it.generator == "basic-type-model" }.text)["model"] as Map<*, *>
        val after = raw(plan.outputs.single { it.generator == "basic-field-model" }.text)["model"] as Map<*, *>
        fun values(value: Any?): Any? = when (value) {
            is Map<*, *> -> value.filterKeys { it != "declarations" }.mapValues { (_, v) -> values(v) }
            is List<*> -> value.map(::values)
            else -> value
        }
        for (entity in listOf("todo", "todo-task", "category", "tag")) {
            val expected = fields(before, entity).map { f ->
                if (entity == "todo" && f["key"] == "id") f + ("description" to "TODO を識別する番号。一覧と個票で共通に使い、個票のファイル名にも使います（例：TODO-001）。") else f
            }
            assertEquals(values(expected), values(fields(after, entity)), entity)
        }
    }

    @Test
    fun `共通型の補正と明記と個別補正を全側面へ反映し 承認済みの説明もそろえる`() {
        val store = store("[{id: COR-200, target: TYP-common.一行の文, set: {maxLength: 200}, reason: 共通}, {id: COR-201, target: FLD-todo.title, set: {maxLength: 150}, reason: 個別}]", explicit = true)
        val result = result(store)
        val model = result["model"] as Map<*, *>
        fields(model, "todo").filter { it["key"] == "title" }.also { assertEquals(1, it.size) }.forEach { assertEquals(150, it["maxLength"]) }
        fields(model, "todo-task").filter { it["key"] == "title" }.forEach { assertEquals(100, it["maxLength"]) }
        assertEquals(200, field(model, "category", "name")["maxLength"])
        fields(model, "todo").filter { it["key"] == "id" }.also { assertEquals(2, it.size) }.forEach {
            assertEquals("TODO を識別する番号。一覧と個票で共通に使い、個票のファイル名にも使います（例：TODO-001）。", it["description"])
        }
        assertEquals(emptyList<Any>(), result["pending"])
        val plan = Generators.plan(WorkspaceSession(store))
        val composed = raw(plan.outputs.single { it.generator == "basic-field-changes" }.text)
        assertTrue(rows(composed, "corrections").any { it["id"] == "COR-201" && it["reason"] == "個別" })
        plan.outputs.forEach { store.write(it.document, it.text) }
        assertTrue(Generators.plan(WorkspaceSession(store)).upToDate)
        val indexed = WorkspaceReader.load(store).derived.semantic.ids.values.flatten().filter { it.document.value.startsWith("design/") }.map { it.id }
        assertTrue(indexed.none { it.startsWith("TYP-") || it.startsWith("FLD-") || it.startsWith("ENT-") })
    }

    @Test
    fun `共有型の片方と配列マップと直書きの選択肢の子だけを補正できる`() {
        val model = result(store("[{id: COR-200, target: TYP-common.一行の文, set: {maxLength: 200}, reason: 共通}, {id: COR-201, target: FLD-extra.left.note, set: {maxLength: 60}, reason: 左}, {id: COR-202, target: FLD-extra.nested.item.value.note, set: {maxLength: 50}, reason: 入れ子}, {id: COR-203, target: FLD-extra.inline.cases.a.note, set: {maxLength: 70}, reason: 選択肢}]", extra = true))["model"] as Map<*, *>
        assertEquals(60, rows(field(model, "extra", "left"), "fields").first { it["key"] == "note" }["maxLength"])
        assertEquals(200, rows(field(model, "extra", "right"), "fields").first { it["key"] == "note" }["maxLength"])
        assertEquals(200, rows(rows(model, "types").single { it["name"] == "extra#part" }, "fields").first()["maxLength"])
        val nested = (field(model, "extra", "nested")["item"] as Map<*, *>)["value"] as Map<*, *>
        assertEquals(50, rows(nested, "fields").single()["maxLength"])
        val cases = field(model, "extra", "inline")["cases"] as Map<*, *>
        assertEquals(70, rows(cases, "a").single()["maxLength"])
    }

    @Test
    fun `型に所属する項目の補正は各利用箇所へ伝わり 個別の狭め方は残る`() {
        val model = result(store("[{id: COR-200, target: FLD-extra.part.note, set: {maxLength: 80}, reason: 部品共通}, {id: COR-201, target: FLD-extra.left.note, set: {maxLength: 60}, reason: 左のみ}]", extra = true))["model"] as Map<*, *>
        assertEquals(60, rows(field(model, "extra", "left"), "fields").first()["maxLength"])
        assertEquals(80, rows(field(model, "extra", "right"), "fields").first()["maxLength"])
    }

    @Test
    fun `個別の偽と零と空文字と空配列を初期値として保持する`() {
        val model = result(store("[{id: COR-200, target: FLD-extra.flag, set: {default: false}, reason: 偽}, {id: COR-201, target: FLD-extra.qty, set: {default: 0}, reason: 零}, {id: COR-202, target: FLD-extra.blank, set: {default: ''}, reason: 空文字}, {id: COR-203, target: FLD-extra.list, set: {default: []}, reason: 空配列}]", extra = true))["model"] as Map<*, *>
        for ((key, value) in mapOf("flag" to false, "qty" to 0, "blank" to "", "list" to emptyList<Any>())) assertEquals(value, field(model, "extra", key)["default"], key)
    }

    @Test
    fun `型を緩める指定と競合と消えた補正先では書き込まない`() {
        val inputs = listOf(
            "[{id: COR-200, target: FLD-extra.left.note, set: {maxLength: 150}, reason: 型より広い}]" to "schemaModel",
            "[{id: COR-200, target: FLD-todo.title, set: {maxLength: 60}, reason: A}, {id: COR-201, target: FLD-todo.title, set: {maxLength: 80}, reason: B}]" to "FLD-todo.title",
            "[{id: COR-200, target: FLD-extra.gone, set: {maxLength: 60}, reason: 消えた対象}]" to "FLD-extra.gone",
        )
        for ((changes, message) in inputs) {
            val input = store(changes, extra = true)
            Generators.plan(WorkspaceSession(store(extra = true))).outputs.forEach { input.write(it.document, it.text) }
            val plan = Generators.plan(WorkspaceSession(input))
            assertTrue(plan.errors.any { message in it }, plan.errors.toString())
            assertTrue(plan.transaction.operations.isEmpty())
        }
    }

    @Test
    fun `型参照の変更を解決し 継承した選択肢の子は未反映として残す`() {
        val result = result(store("[{id: COR-200, target: FLD-todo.title, set: {type: TYP-common.文章}, reason: 型の変更}, {id: COR-201, target: FLD-extra.inherited.cases.a.choiceNote, set: {maxLength: 60}, reason: 継承した選択肢}, {id: COR-202, target: FLD-extra.panel.mode.cases.a.panelNote, set: {maxLength: 60}, reason: 親の型から継承した選択肢}]", extra = true))
        assertEquals(setOf("FLD-extra.inherited.cases.a.choiceNote", "FLD-extra.panel.mode.cases.a.panelNote"), rows(result, "pending").map { it["target"] }.toSet())
        assertEquals("common#文章", field(result["model"] as Map<*, *>, "todo", "title")["type"])
    }

    private val extraSchema = """
        id: extra
        kind: document
        match: extra/*.yaml
        entity: {type: extra, defines: true}
        definitions:
          part:
            type: object
            fields:
              - {key: note, type: common#一行の文}
              - {key: next, type: array, item: {type: part}}
          choice:
            type: enum
            values: [a, b]
            cases: {a: [{key: choiceNote, type: common#一行の文}]}
          panelType:
            type: object
            fields:
              - {key: mode, type: enum, values: [a, b], cases: {a: [{key: panelNote, type: common#一行の文}]}}
        root:
          type: object
          fields:
            - {key: id, type: id}
            - {key: left, type: part}
            - {key: right, type: part}
            - {key: nested, type: array, item: {type: map, value: {type: object, fields: [{key: note, type: common#一行の文}]}}}
            - {key: inline, type: enum, values: [a, b], cases: {a: [{key: note, type: common#一行の文}]}}
            - {key: inherited, type: choice}
            - {key: panel, type: panelType}
            - {key: flag, type: boolean, default: true}
            - {key: qty, type: int, default: 5}
            - {key: blank, type: string, default: x}
            - {key: list, type: array, item: {type: string}, default: [x]}
    """.trimIndent()
}
