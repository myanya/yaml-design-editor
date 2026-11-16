package dev.toros.yde.app.core

import dev.toros.yde.workspace.Generators
import dev.toros.yde.workspace.InMemoryDocumentStore
import dev.toros.yde.workspace.WorkspaceCheckRunner
import dev.toros.yde.workspace.WorkspaceReader
import dev.toros.yde.workspace.WorkspaceSession
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 新しい保存形式から、実際の生成計画で候補を合成する。確定設計書への切り替えとは分けて確かめる。 */
class TodosDesignGenerationTest {
    private val sample = Path.of("samples/todos-sysdev")
    private fun yaml(raw: Any?) = YamlIO.dumpPlain(YamlIO.fromAny(raw))
    private fun rows(m: Map<*, *>, key: String) = (m[key] as List<*>).map { it as Map<*, *> }
    private fun derived(): List<Map<String, Any?>> {
        val model = (YamlIO.parseToRaw(Files.readString(sample.resolve("design/basic/derived/model.yaml"))) as Map<*, *>)["model"] as Map<*, *>
        val shared = rows(model, "types").single { it["name"] == "common#一行の文" }
        val title = rows(rows(model, "entities").single { it["id"] == "todo" }, "aspects")
            .flatMap { rows(it, "fields") }.first { it["key"] == "title" }
        fun record(id: String, field: Map<*, *>): Map<String, Any?> {
            val d = rows(field, "declarations").single { it["inherited"] == false }
            return mapOf("id" to id, "value" to d["declared"],
                "source" to mapOf("id" to "DRV-$id", "document" to d["document"], "path" to d["path"])) +
                if (id.startsWith("FLD-")) mapOf("keySpace" to mapOf("scope" to "ENT-todo", "path" to listOf("key"))) else emptyMap()
        }
        val inherited = record("FLD-todo.title", title)
        val explicit = inherited + mapOf("id" to "FLD-todo.explicit", "value" to
            ((inherited["value"] as Map<*, *>).filterKeys { it != "role" } + mapOf("key" to "explicit", "maxLength" to 100)))
        return listOf(record("TYP-common.一行の文", shared), inherited, explicit)
    }

    private fun correction(id: String, target: String, set: Map<String, Any?>) =
        mapOf("id" to id, "target" to target, "set" to set, "reason" to "設計上の個別指定")

    private val addition = """
        additions:
          - id: ADD-version
            kind: field
            reason: 版番号方式
            field: {designId: FLD-todo.version, owner: ENT-todo, key: version, base: int, default: 0}
    """.trimIndent()

    // 保存形式から合成命令への対応付けも、出力する型定義の組み立ても YAML に置く。
    private val generator = """
        id: compose
        scope: business
        output: design/composed.yaml
        input:
          ${'$'}compose:
            derived:
              ${'$'}each: {${'$'}view: derived}
              ${'$'}flatten: true
              ${'$'}build: {${'$'}copy: values.records}
            additions:
              ${'$'}each: {${'$'}view: additions}
              ${'$'}build:
                id: {${'$'}copy: values.record.field.designId}
                value:
                  key: {${'$'}copy: values.record.field.key}
                  type: {${'$'}copy: values.record.field.base}
                  default: {${'$'}copy: values.record.field.default}
                source: {id: {${'$'}copy: id}, document: design/additions.yaml}
                keySpace: {scope: {${'$'}copy: values.record.field.owner}, path: [key]}
            changes:
              ${'$'}concat:
                - ${'$'}each: {${'$'}view: corrections}
                  ${'$'}build:
                    target: {${'$'}copy: values.record.target}
                    set: {${'$'}copy: values.record.set}
                    priority: individual
                    source: {id: {${'$'}copy: id}, document: design/corrections.yaml}
                - ${'$'}each: {${'$'}view: rule-changes}
                  ${'$'}build:
                    target: {${'$'}copy: values.record.target}
                    set: {${'$'}copy: values.record.set}
                    priority: rule
                    source: {id: {${'$'}copy: id}, document: design/expanded.yaml}
        build:
          id: COMPOSED
          records: {${'$'}copy: records}
          decisions: {${'$'}copy: decisions}
    """.trimIndent()
    private val schemaGenerator = """
        id: resolved-schema
        scope: business
        dependsOn: [compose]
        output: design/type-schema.yaml
        input: {${'$'}view: composed, ${'$'}one: true}
        build:
          id: common
          kind: library
          definitions:
            ${'$'}entries:
              ${'$'}each: values.records
              ${'$'}build:
                key: {${'$'}value: {extract: [{path: id}, '([^.]+)$']}}
                value: {${'$'}copy: value}
    """.trimIndent()

    private fun store(corrections: List<Map<String, Any?>>, rules: String = "changes: []", records: List<Map<String, Any?>> = derived()): InMemoryDocumentStore {
        val libraries = listOf("basic-design-values", "basic-design-contract").associate { name ->
            ".yde/schemas/$name.yaml" to Files.readString(sample.resolve(".yde/schemas/design/$name.yaml"))
        }
        val inputs = mapOf(
            ".yde/workspace.yaml" to "ledger: [design]\nscopes: [{id: business, include: ['schemas/business/**']}]\n",
            ".yde/schemas/business/source.yaml" to "id: source\nkind: library\ndefinitions: {note: {type: string}}\n",
            ".yde/schemas/derived.yaml" to "id: derived\nmatch: design/derived.yaml\nroot: {type: object, fields: [{key: id, type: id}, {key: records, type: any}]}\n",
            ".yde/schemas/corrections.yaml" to "id: corrections\nmatch: design/corrections.yaml\nroot: {type: basic-design-contract#補正と指定一覧}\n",
            ".yde/schemas/additions.yaml" to "id: additions\nmatch: design/additions.yaml\nroot: {type: basic-design-contract#追加一覧}\n",
            ".yde/schemas/expanded.yaml" to "id: expanded\nmatch: design/expanded.yaml\nroot: {type: basic-design-contract#展開した候補}\n",
            ".yde/schemas/composed.yaml" to "id: composed\nmatch: design/composed.yaml\nroot: {type: object, fields: [{key: id, type: id}, {key: records, type: any}, {key: decisions, type: any}]}\n",
            ".yde/views/derived.yaml" to "id: derived\nrows: {schemaIds: [derived], idPattern: DRV-*}\ncolumns: [{label: records, path: records}]\n",
            ".yde/views/additions.yaml" to "id: additions\nrows: {schemaIds: [additions], idPattern: ADD-*}\ncolumns: [{label: record, path: .}]\n",
            ".yde/views/corrections.yaml" to "id: corrections\nrows: {schemaIds: [corrections], idPattern: COR-*}\ncolumns: [{label: record, path: .}]\n",
            ".yde/views/rules.yaml" to "id: rule-changes\nrows: {schemaIds: [expanded], idPattern: CHG-*}\ncolumns: [{label: record, path: .}]\n",
            ".yde/views/composed.yaml" to "id: composed\nrows: {schemaIds: [composed], idPattern: COMPOSED}\ncolumns: [{label: records, schemaId: composed, path: records}]\n",
            ".yde/transforms/compose.yaml" to generator,
            ".yde/transforms/schema.yaml" to schemaGenerator,
            "design/derived.yaml" to yaml(mapOf("id" to "DRV-business", "records" to records)),
            "design/additions.yaml" to addition,
            "design/corrections.yaml" to yaml(mapOf("corrections" to corrections)),
            "design/expanded.yaml" to rules,
        )
        val store = InMemoryDocumentStore(libraries + inputs)
        val ws = WorkspaceReader.load(store)
        assertEquals(emptyList(), ws.schemaErrors)
        val errors = WorkspaceCheckRunner.validateAll(ws).values.flatMap { it.issues }.filter { it.severity.name == "ERROR" }
        assertEquals(emptyList(), errors)
        return store
    }

    @Test
    fun `新形式の補正と追加を生成で合成し 共通型の二百と項目に明記した百を既存の型解決へ渡す`() {
        val store = store(listOf(correction("COR-length", "TYP-common.一行の文", mapOf("maxLength" to 200)),
            correction("COR-version", "FLD-todo.version", mapOf("default" to 1))))
        val plan = Generators.plan(WorkspaceSession(store))
        assertEquals(emptyList(), plan.errors)
        val schema = plan.outputs.single { it.generator == "resolved-schema" }
        val loaded = WorkspaceReader.load(InMemoryDocumentStore(mapOf(
            ".yde/workspace.yaml" to "ledger: [data]\n", ".yde/schemas/result.yaml" to schema.text)))
        assertEquals(emptyList(), loaded.schemaErrors, plan.outputs.joinToString("\n") { it.text })
        val tree = loaded.schemas.single { it.id == "common" }.tree
        assertEquals(200, tree.resolveTypeName("title")!!.maxLength)
        assertEquals(100, tree.resolveTypeName("explicit")!!.maxLength)
        assertEquals(1, tree.resolveTypeName("version")!!.default)
        plan.outputs.forEach { store.write(it.document, it.text) }
        assertTrue(Generators.plan(WorkspaceSession(store)).upToDate, "再生成しても補正を重ね掛けしない")
    }

    @Test
    fun `規則の競合は生成を止め 個別指定があれば解決して全候補を記録する`() {
        val rules = """
            changes:
              - {id: CHG-a, rule: a, target: TYP-common.一行の文, set: {maxLength: 150}, reason: 方式 A}
              - {id: CHG-b, rule: b, target: TYP-common.一行の文, set: {maxLength: 200}, reason: 方式 B}
        """.trimIndent()
        val failed = Generators.plan(WorkspaceSession(store(emptyList(), rules)))
        assertTrue(failed.errors.any { "異なる規則の既定値" in it }, failed.errors.toString())
        assertTrue(failed.transaction.operations.isEmpty())
        assertTrue(failed.outputs.none { it.generator == "resolved-schema" })
        val success = Generators.plan(WorkspaceSession(store(listOf(correction("COR-length", "TYP-common.一行の文", mapOf("maxLength" to 180))), rules)))
        assertEquals(emptyList(), success.errors)
        val output = success.outputs.single { it.generator == "compose" }.text
        assertTrue(listOf("CHG-a", "CHG-b", "COR-length", "maxLength: 180").all { it in output }, output)
    }

    @Test
    fun `追加が既存項目と同じキーを持つときは 値が同じでも統合せず生成を止める`() {
        val store = store(emptyList())
        store.write(DocumentId("design/additions.yaml"), addition.replace("key: version", "key: title"))
        val plan = Generators.plan(WorkspaceSession(store))
        assertTrue(plan.errors.any { "重複" in it && "ENT-todo" in it }, plan.errors.toString())
        assertTrue(plan.transaction.operations.isEmpty())
    }

    @Test
    fun `前回の合成結果に対象が残っていても 消えた対象への補正で今回の生成を止める`() {
        val store = store(listOf(correction("COR-title", "FLD-todo.title", mapOf("label" to "見出し"))))
        val first = Generators.plan(WorkspaceSession(store))
        assertEquals(emptyList(), first.errors)
        first.outputs.forEach { store.write(it.document, it.text) }
        store.write(DocumentId("design/derived.yaml"), yaml(mapOf("id" to "DRV-business", "records" to derived().filter { it["id"] != "FLD-todo.title" })))
        val missing = Generators.plan(WorkspaceSession(store))
        assertTrue(missing.errors.any { "変更対象「FLD-todo.title」が今回の導出・追加にありません" in it }, missing.errors.toString())
        assertTrue(missing.transaction.operations.isEmpty())
        assertEquals(first.outputs.first().text, store.read(first.outputs.first().document)?.text)
    }
}
