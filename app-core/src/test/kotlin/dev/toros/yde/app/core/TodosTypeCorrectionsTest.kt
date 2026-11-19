package dev.toros.yde.app.core

import dev.toros.yde.workspace.BusinessModel
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

/** 試験用の変換ではなく、サンプルの設計 ID → 宣言 → 型解決の経路を確認する。 */
class TodosTypeCorrectionsTest {
    private val sample = Path.of("samples/todos-sysdev")
    private fun raw(text: String) = YamlIO.parseToRaw(text) as Map<*, *>
    private fun rows(m: Map<*, *>, key: String) = (m[key] as List<*>).map { it as Map<*, *> }

    private fun store(corrections: String = "[]", explicit: Boolean = false, extra: String? = null): InMemoryDocumentStore {
        val files = sample.resolve(".yde/schemas/business").listDirectoryEntries("*.yaml").sorted()
            .associate { file ->
                val source = Files.readString(file)
                val text = if (explicit && file.fileName.toString() == "common.yaml")
                    source.replace("        type: 一行の文\n        role: title", "        type: 一行の文\n        maxLength: 100\n        role: title") else source
                ".yde/schemas/business/${file.fileName}" to text
            }.toMutableMap()
        for (name in listOf("basic-design-values", "basic-derived-model", "basic-derived", "basic-design-contract", "basic-targets", "basic-type-changes", "basic-type-model")) {
            val path = ".yde/schemas/design/$name.yaml"
            files[path] = Files.readString(sample.resolve(path))
        }
        for (name in listOf("basic-derived", "basic-targets", "basic-type-changes", "basic-type-model")) {
            val path = ".yde/transforms/design/$name.yaml"
            files[path] = Files.readString(sample.resolve(path))
        }
        for (name in listOf("basic-derived-input", "basic-targets-input", "basic-description-corrections-input", "basic-type-changes-input")) {
            val path = ".yde/views/design/$name.yaml"
            files[path] = Files.readString(sample.resolve(path))
        }
        files[".yde/schemas/design/basic-corrections.yaml"] = "id: basic-corrections\nmatch: design/basic/corrections.yaml\nroot: {type: basic-design-contract#補正と指定一覧}\n"
        files[".yde/workspace.yaml"] = "ledger: [design]\nscopes: [{id: business, include: ['schemas/business/**']}]\n"
        files["design/basic/corrections.yaml"] = "corrections: $corrections\n"
        if (extra != null) files[".yde/schemas/business/extra.yaml"] = extra
        return InMemoryDocumentStore(files).also { assertEquals(emptyList(), WorkspaceReader.load(it).schemaErrors) }
    }

    private fun resolved(store: InMemoryDocumentStore): Map<*, *> {
        val plan = Generators.plan(WorkspaceSession(store))
        assertEquals(emptyList(), plan.errors)
        return raw(plan.outputs.single { it.generator == "basic-type-model" }.text)
    }

    @Test
    fun `共通型を設計IDで二百に補正すると未指定の全側面へ届き 明記した百は残る`() {
        for (explicit in listOf(false, true)) {
            val input = store("[{id: COR-200, target: TYP-common.一行の文, set: {maxLength: 200}, reason: 共通の上限}]", explicit)
            val result = resolved(input)
            val model = result["model"] as Map<*, *>
            assertEquals(200, rows(model, "types").single { it["name"] == "common#一行の文" }["maxLength"])
            for (entity in listOf("todo", "todo-task")) {
                val titles = rows(rows(model, "entities").single { it["id"] == entity }, "aspects")
                    .flatMap { rows(it, "fields") }.filter { it["key"] == "title" }
                assertTrue(titles.isNotEmpty())
                titles.forEach { assertEquals(if (explicit) 100 else 200, it["maxLength"], entity) }
            }
            assertEquals(emptyList<Any>(), result["pending"])
            val plan = Generators.plan(WorkspaceSession(input))
            val changes = raw(plan.outputs.single { it.generator == "basic-type-changes" }.text)
            assertEquals("COR-200", (rows(rows(changes, "decisions").single(), "selected").single()["source"] as Map<*, *>)["id"])
            assertEquals("共通の上限", rows(changes, "corrections").single()["reason"])
            plan.outputs.forEach { input.write(it.document, it.text) }
            assertTrue(Generators.plan(WorkspaceSession(input)).upToDate)
            val indexed = WorkspaceReader.load(input).derived.semantic.ids.values.flatten()
                .filter { it.document.value.startsWith("design/") }.map { it.id }
            assertTrue(indexed.none { it.startsWith("TYP-") || it.startsWith("FLD-") || it.startsWith("ENT-") })
        }
    }

    private fun normalize(value: Any?, documents: Map<String, String>): Any? = when (value) {
        is Map<*, *> -> value.mapValues { normalize(it.value, documents) }
        is List<*> -> value.map { normalize(it, documents) }
        is String -> documents[value] ?: value
        else -> value
    }

    @Test
    fun `補正がなければ操作や関係と原文を含む全モデルが仮の文書座標以外は一致する`() {
        val input = store()
        val original = YamlIO.toAny(BusinessModel.of(WorkspaceReader.load(input), "business")) as Map<*, *>
        val documents = rows(original, "schemas").mapIndexed { i, s -> s["document"].toString() to ".yde/schemas/input/$i.yaml" }.toMap()
        assertEquals(normalize(original, documents), resolved(input)["model"])
    }

    @Test
    fun `別の型を継承する型への補正はその宣言だけを変え 継承元を緩めない`() {
        val extra = """
            id: extra
            kind: library
            definitions:
              base: {type: string, maxLength: 300}
              short: {type: base, maxLength: 100}
        """.trimIndent()
        val model = resolved(store("[{id: COR-200, target: TYP-extra.short, set: {maxLength: 200}, reason: 上限の変更}]", extra = extra))["model"] as Map<*, *>
        val types = rows(model, "types")
        assertEquals(300, types.single { it["name"] == "extra#base" }["maxLength"])
        assertEquals(200, types.single { it["name"] == "extra#short" }["maxLength"])
        val invalid = Generators.plan(WorkspaceSession(store("[{id: COR-200, target: TYP-extra.short, set: {maxLength: 400}, reason: 継承元より広い}]", extra = extra)))
        assertTrue(invalid.errors.any { "schemaModel" in it }, invalid.errors.toString())
        assertTrue(invalid.transaction.operations.isEmpty())
    }

    @Test
    fun `異なる補正と存在しない対象は前回の生成物に頼らず停止する`() {
        val conflicting = store("[{id: COR-200, target: TYP-common.一行の文, set: {maxLength: 200}, reason: A}, {id: COR-201, target: TYP-common.一行の文, set: {maxLength: 300}, reason: B}]")
        val missing = store("[{id: COR-200, target: TYP-missing.type, set: {maxLength: 200}, reason: 消えた対象}]")
        val previous = Generators.plan(WorkspaceSession(store()))
        previous.outputs.forEach { missing.write(it.document, it.text) }
        for (input in listOf(conflicting, missing)) {
            val plan = Generators.plan(WorkspaceSession(input))
            assertTrue(plan.errors.isNotEmpty())
            assertTrue(plan.outputs.none { it.generator == "basic-type-model" })
            assertTrue(plan.transaction.operations.isEmpty())
        }
    }

    @Test
    fun `初期値の偽と零と空文字と空配列を補正から型解決まで保持する`() {
        val extra = """
            id: extra
            kind: library
            definitions:
              flag: {type: boolean}
              number: {type: int}
              text: {type: string}
              list: {type: array, item: {type: string}}
        """.trimIndent()
        val correction = "[{id: COR-200, target: TYP-extra.flag, set: {default: false}, reason: 偽}, {id: COR-201, target: TYP-extra.number, set: {default: 0}, reason: 零}, {id: COR-202, target: TYP-extra.text, set: {default: ''}, reason: 空文字}, {id: COR-203, target: TYP-extra.list, set: {default: []}, reason: 空配列}]"
        val model = resolved(store(correction, extra = extra))["model"] as Map<*, *>
        val types = rows(model, "types").associateBy { it["name"] }
        for ((name, value) in mapOf("flag" to false, "number" to 0, "text" to "", "list" to emptyList<Any>())) {
            assertEquals(value, types["extra#$name"]!!["default"], name)
        }
    }

    @Test
    fun `利用箇所や組込み型の未接続の補正を適用済みとして隠さない`() {
        val result = resolved(store("[{id: COR-200, target: FLD-todo.title, set: {maxLength: 60}, reason: 個別}, {id: COR-201, target: TYP-common.一行の文, set: {type: TYP-common.文章}, reason: 型}, {id: COR-202, target: TYP-boolean, set: {default: false}, reason: 組込み}]"))
        assertEquals(setOf("FLD-todo.title"), rows(result, "pending").map { it["target"] }.toSet())
        assertEquals(false, rows(result, "builtinPending").single()["value"])
        val model = result["model"] as Map<*, *>
        assertEquals(100, rows(model, "types").single { it["name"] == "common#一行の文" }["maxLength"])
    }
}
