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

/** 実際の導出定義が保持する全スキーマを、合成・型解決へ渡す。設計項目 ID への対応付けは次の段階。 */
class TodosSchemaInputsTest {
    private val sample = Path.of("samples/todos-sysdev")
    private fun rows(m: Map<*, *>, key: String) = (m[key] as List<*>).map { it as Map<*, *> }
    private fun yaml(value: Any?) = YamlIO.dumpPlain(YamlIO.fromAny(value))

    private fun store(changes: List<Map<String, Any?>> = emptyList(), explicit: Boolean = false): InMemoryDocumentStore {
        val files = sample.resolve(".yde/schemas/business").listDirectoryEntries("*.yaml").sorted()
        val schemas = files.associate { file ->
            val text = Files.readString(file)
            // 検証用の写しで、共通型と同じ 100 の明記を追加する。
            val amended = if (explicit && file.fileName.toString() == "common.yaml")
                text.replace("        type: 一行の文\n        role: title", "        type: 一行の文\n        maxLength: 100\n        role: title") else text
            ".yde/schemas/business/${file.fileName}" to amended
        }
        val inputs = mapOf(
            ".yde/workspace.yaml" to "ledger: [design]\nscopes: [{id: business, include: ['schemas/business/**']}]\n",
            ".yde/schemas/derived.yaml" to "id: derived\nmatch: design/basic/derived/model.yaml\nroot: {type: object, fields: [{key: id, type: id}, {key: model, type: any}]}\n",
            ".yde/schemas/composed.yaml" to "id: composed\nmatch: design/composed.yaml\nroot: {type: object, fields: [{key: id, type: id}, {key: records, type: any}, {key: decisions, type: any}]}\n",
            ".yde/views/derived.yaml" to "id: derived\nrows: {schemaIds: [derived], idPattern: DRV-business}\ncolumns: [{label: model, schemaId: derived, path: model}]\n",
            ".yde/views/composed.yaml" to "id: composed\nrows: {schemaIds: [composed], idPattern: COMPOSED}\ncolumns: [{label: records, schemaId: composed, path: records}]\n",
            ".yde/transforms/derived.yaml" to Files.readString(sample.resolve(".yde/transforms/design/basic/basic-derived.yaml")),
        ).toMutableMap()
        // スキーマの原文を $compose の記録へ写し、宣言の座標へ補正する。YAML で組み立て、型を Kotlin で組み直さない。
        inputs[".yde/transforms/compose.yaml"] = """
            id: compose
            scope: business
            dependsOn: [basic-derived]
            output: design/composed.yaml
            input:
              ${'$'}compose:
                derived:
                  ${'$'}each: {${'$'}view: derived}
                  ${'$'}flatten: true
                  ${'$'}build:
                    ${'$'}each: values.model.schemas
                    ${'$'}build:
                      id: {${'$'}copy: id}
                      value: {${'$'}copy: declared}
                      source: {id: {${'$'}copy: id}, document: {${'$'}copy: document}}
                additions: []
                changes: CHANGES
            build:
              id: COMPOSED
              records: {${'$'}copy: records}
              decisions: {${'$'}copy: decisions}
        """.trimIndent().replace("changes: CHANGES", "changes:\n" + yaml(changes).trimEnd().prependIndent("      "))
        inputs[".yde/transforms/resolve.yaml"] = """
            id: resolve
            scope: business
            dependsOn: [compose]
            output: design/resolved.yaml
            input: {${'$'}view: composed, ${'$'}one: true}
            build:
              ${'$'}schemaModel:
                ${'$'}each: values.records
                ${'$'}build: {${'$'}copy: value}
        """.trimIndent()
        return InMemoryDocumentStore(schemas + inputs)
    }

    private fun normalize(value: Any?, documents: Map<String, String>): Any? = when (value) {
        is Map<*, *> -> value.mapValues { normalize(it.value, documents) }
        is List<*> -> value.map { normalize(it, documents) }
        is String -> documents[value] ?: value
        else -> value
    }

    @Test
    fun `実際の導出定義から全スキーマを合成して解き直しても 側面と入れ子と操作を失わない`() {
        val store = store()
        val original = YamlIO.toAny(BusinessModel.of(WorkspaceReader.load(store), "business")) as Map<*, *>
        val sources = rows(original, "schemas")
        assertEquals(8, sources.size)
        val documents = sources.mapIndexed { index, source -> source["document"].toString() to ".yde/schemas/input/$index.yaml" }.toMap()
        val plan = Generators.plan(WorkspaceSession(store))
        assertEquals(emptyList(), plan.errors)
        val resolved = YamlIO.parseToRaw(plan.outputs.single { it.generator == "resolve" }.text)
        assertEquals(normalize(original, documents), resolved, "仮の文書座標以外は、全側面・項目・操作・関係を含めて一致する")
        plan.outputs.forEach { store.write(it.document, it.text) }
        assertTrue(Generators.plan(WorkspaceSession(store)).upToDate)
    }

    @Test
    fun `全スキーマへの共通型の補正が各エンティティへ届き 個別の百の明記は残る`() {
        val change = mapOf("target" to "common", "path" to listOf("definitions", "一行の文", "maxLength"),
            "value" to 200, "priority" to "individual", "source" to mapOf("id" to "COR-length"))
        for (explicit in listOf(false, true)) {
            val plan = Generators.plan(WorkspaceSession(store(listOf(change), explicit)))
            assertEquals(emptyList(), plan.errors)
            val resolved = YamlIO.parseToRaw(plan.outputs.single { it.generator == "resolve" }.text) as Map<*, *>
            val common = rows(resolved, "types").single { it["name"] == "common#一行の文" }
            assertEquals(200, common["maxLength"])
            for (entity in listOf("todo", "todo-task")) {
                val aspects = rows(rows(resolved, "entities").single { it["id"] == entity }, "aspects")
                val titles = aspects.flatMap { rows(it, "fields") }.filter { it["key"] == "title" }
                assertTrue(titles.isNotEmpty())
                titles.forEach { assertEquals(if (explicit) 100 else 200, it["maxLength"], entity) }
            }
            val composed = plan.outputs.single { it.generator == "compose" }.text
            assertTrue("COR-length" in composed && ".yde/schemas/business/common.yaml" in composed)
        }
    }
}
