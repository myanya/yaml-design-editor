package dev.toros.yde.app.core

import dev.toros.yde.workspace.Generators
import dev.toros.yde.workspace.InMemoryDocumentStore
import dev.toros.yde.workspace.WorkspaceReader
import dev.toros.yde.workspace.WorkspaceSession
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 実際の変換定義で、設計 ID と宣言の対応・説明の食い違いと個別補正を確認する。 */
class TodosDesignTargetsTest {
    private val sample = Path.of("samples/todos-sysdev")
    private fun raw(text: String) = YamlIO.parseToRaw(text) as Map<*, *>
    private fun rows(m: Map<*, *>, key: String) = (m[key] as List<*>).map { it as Map<*, *> }
    private val accepted = "TODO を識別する番号。一覧と個票で共通に使い、個票のファイル名にも使います（例：TODO-001）。"
    // サンプルの業務モデルは説明をそろえ、代表の説明の補正（COR-001）を無くした（ADR-0042）。
    // 食い違いの検出と、補正での採用は、この試験の入力で元の食い違いを作って確かめる。
    private val listDescription = "一覧内で一意のID。例：TODO-001。個票のIDとファイル名にも使用します。"
    private val detailDescription = "一覧のIDと一致させ、todos/details/<ID>.yaml に保存します。"
    private val corrections = """
        corrections:
          - id: COR-001
            target: FLD-todo.id
            set:
              description: $accepted
            reason: 一覧と個票で異なる ID の説明を、共通の番号とファイル名の関係を示す代表文にそろえる
    """.trimIndent() + "\n"

    private fun store(correctionText: String = corrections, extra: String? = null): InMemoryDocumentStore {
        val files = sample.resolve(".yde/schemas/business").listDirectoryEntries("*.yaml")
            .associate { ".yde/schemas/business/${it.fileName}" to Files.readString(it) }.toMutableMap()
        val common = ".yde/schemas/business/common.yaml"
        assertEquals(2, files.getValue(common).split(accepted).size - 1, "一覧の行と個票の ID の説明")
        files[common] = files.getValue(common).replaceFirst(accepted, listDescription).replaceFirst(accepted, detailDescription)
        for (name in listOf("basic-design-values", "basic-derived-model", "basic-derived", "basic-design-contract", "basic-targets", "basic-descriptions")) {
            val path = TodosSysdevFiles.definition(sample, "schemas", name)
            files[path] = Files.readString(sample.resolve(path))
        }
        for (name in listOf("basic-derived", "basic-targets", "basic-descriptions")) {
            val path = TodosSysdevFiles.definition(sample, "transforms", name)
            files[path] = Files.readString(sample.resolve(path))
        }
        for (name in listOf("basic-derived-input", "basic-targets-input", "basic-description-corrections-input")) {
            val path = TodosSysdevFiles.definition(sample, "views", name)
            files[path] = Files.readString(sample.resolve(path))
        }
        // 生成中の対象は通常の索引に頼らない。旧設計書を置かない環境でも新形式の入力を読める。
        files[".yde/schemas/design/basic/basic-corrections.yaml"] = "id: basic-corrections\nmatch: design/basic/inputs/entities/corrections.yaml\nroot: {type: basic-design-contract#補正と指定一覧}\n"
        files[".yde/workspace.yaml"] = "ledger: [design]\nscopes: [{id: business, include: ['schemas/business/**']}]\n"
        files["design/basic/inputs/entities/corrections.yaml"] = correctionText
        if (extra != null) files[".yde/schemas/business/extra.yaml"] = extra
        return InMemoryDocumentStore(files).also { assertEquals(emptyList(), WorkspaceReader.load(it).schemaErrors) }
    }

    @Test
    fun `一覧と個票の説明を先頭で選ばず 指摘して書込みを止める`() {
        val plan = Generators.plan(WorkspaceSession(store("corrections: []")))
        assertTrue(plan.errors.any { "FLD-todo.id" in it && "description" in it }, plan.errors.toString())
        assertTrue(plan.outputs.none { it.generator == "basic-descriptions" })
        assertTrue(plan.transaction.operations.isEmpty())
        val targets = rows(raw(plan.outputs.single { it.generator == "basic-targets" }.text), "targets")
        val id = targets.single { it["designId"] == "FLD-todo.id" }
        assertEquals(2, rows(id, "occurrences").size)
        assertEquals(2, rows(id, "descriptions").size)
        val paths = rows(id, "occurrences").flatMap { rows(it, "declarations") }
            .filter { it["inherited"] == false }.map { it["path"] }
        assertTrue(paths.any { "TODO一覧の行" in it.toString() } && paths.any { "TODO個票" in it.toString() }, paths.toString())
        val legacyFields = rows(raw(Files.readString(sample.resolve("design/basic/final/entities.yaml"))), "entities")
            .flatMap { rows(it, "items") }.filter { it["addedBy"] == null }.map { it["id"] }.toSet()
        assertTrue(targets.map { it["designId"] }.containsAll(legacyFields), "既存の全項目 ID を維持する（方式が足した項目は、業務モデルの宣言に無いので除く）")
        val legacyTypes = rows(raw(Files.readString(sample.resolve("design/basic/final/types.yaml"))), "types").map { it["id"] }
        assertTrue(targets.map { it["designId"] }.containsAll(legacyTypes), "組込みの型を含め、既存の論理型 ID を維持する")
        val type = targets.single { it["designId"] == "TYP-common.一行の文" }
        assertTrue(rows(rows(type, "occurrences").single(), "declarations").any {
            it["inherited"] == false && it["path"] == listOf("definitions", "一行の文")
        })
    }

    @Test
    fun `個別補正で説明を採用し 元の説明と全候補を残して再生成できる`() {
        val store = store()
        val plan = Generators.plan(WorkspaceSession(store))
        assertEquals(emptyList(), plan.errors)
        val result = raw(plan.outputs.single { it.generator == "basic-descriptions" }.text)
        val id = rows(result, "items").single { it["designId"] == "FLD-todo.id" }
        assertEquals(accepted, id["description"])
        assertEquals(2, rows(id, "descriptions").size)
        val decision = rows(result, "decisions").single { it["target"] == "FLD-todo.id" }
        assertEquals(3, rows(decision, "candidates").size)
        assertEquals("COR-001", (rows(decision, "selected").single()["source"] as Map<*, *>)["id"])
        assertEquals(rows(raw(corrections), "corrections").single()["reason"], rows(result, "corrections").single()["reason"])
        plan.outputs.forEach { store.write(it.document, it.text) }
        assertTrue(Generators.plan(WorkspaceSession(store)).upToDate)
        val designIds = WorkspaceReader.load(store).derived.semantic.ids.values.flatten()
            .filter { it.document.value.startsWith("design/") }.map { it.id }
        assertTrue(designIds.none { it.startsWith("FLD-") || it.startsWith("TYP-") || it.startsWith("ENT-") }, "導出と合成の材料を確定項目として索引に混ぜない")
    }

    private val extra = """
        id: extra
        kind: document
        match: extra/*.yaml
        entity: {type: extra, defines: true}
        definitions:
          part:
            type: object
            fields:
              - {key: note, type: string, description: 共通の注記}
              - {key: next, type: array, item: {type: part}}
        root:
          type: object
          fields:
            - {key: id, type: id}
            - {key: left, type: part}
            - {key: right, type: part}
            - key: nested
              type: array
              item:
                type: map
                value:
                  type: object
                  fields: [{key: note, type: string, description: 入れ子の注記}]
            - key: choice
              type: enum
              values: [a, b]
              cases:
                a: [{key: note, type: string, description: A の注記}]
                b: [{key: note, type: string, description: B の注記}]
    """.trimIndent()

    @Test
    fun `異なる個別補正同士は 後の文を採用せず競合として止める`() {
        val conflict = corrections + "\n  - {id: COR-002, target: FLD-todo.id, set: {description: 別の代表文}, reason: 別の指定}\n"
        val plan = Generators.plan(WorkspaceSession(store(conflict)))
        assertTrue(plan.errors.any { "FLD-todo.id" in it && "異なる個別指定" in it }, plan.errors.toString())
        assertTrue(plan.transaction.operations.isEmpty())
    }

    @Test
    fun `違う型の名前から同じ設計IDができても 黙って統合しない`() {
        val store = store()
        store.write(DocumentId(".yde/schemas/business/alpha.yaml"), "id: alpha\nkind: library\ndefinitions: {beta.item: {type: string}}\n")
        store.write(DocumentId(".yde/schemas/business/alpha-beta.yaml"), "id: alpha.beta\nkind: library\ndefinitions: {item: {type: string}}\n")
        val plan = Generators.plan(WorkspaceSession(store))
        assertTrue(plan.errors.any { "TYP-alpha.beta.item" in it && "重複" in it }, plan.errors.toString())
        assertTrue(plan.transaction.operations.isEmpty())
    }

    @Test
    fun `共通の子を使う二つの項目と配列とマップと選択肢を別の場所として保持する`() {
        val plan = Generators.plan(WorkspaceSession(store(extra = extra)))
        assertEquals(emptyList(), plan.errors)
        val targets = rows(raw(plan.outputs.single { it.generator == "basic-targets" }.text), "targets").associateBy { it["designId"] }
        for (id in listOf("FLD-extra.left.note", "FLD-extra.right.note", "FLD-extra.part.note", "FLD-extra.nested.item.value.note", "FLD-extra.choice.cases.a.note", "FLD-extra.choice.cases.b.note")) {
            assertTrue(id in targets, "$id: ${targets.keys}")
        }
        val a = targets.getValue("FLD-extra.choice.cases.a.note")
        assertEquals(listOf("fields", "choice", "cases", "a", "fields", "note"), a["path"])
        assertEquals(listOf("A の注記"), rows(a, "descriptions").map { it["value"] })
        assertEquals(listOf("B の注記"), rows(targets.getValue("FLD-extra.choice.cases.b.note"), "descriptions").map { it["value"] })
        assertTrue(targets.size < 100, "再帰する型は参照で閉じ、無限に項目を増やさない")
    }

    @Test
    fun `前回の出力に対象が残っていても 今回消えた項目への補正は止める`() {
        val text = corrections + "\n  - {id: COR-002, target: FLD-extra.right.note, set: {description: 右側の注記}, reason: 用途を説明する}\n"
        val store = store(text, extra)
        val first = Generators.plan(WorkspaceSession(store))
        assertEquals(emptyList(), first.errors)
        first.outputs.forEach { store.write(it.document, it.text) }
        store.write(DocumentId(".yde/schemas/business/extra.yaml"), extra.replace("    - {key: right, type: part}\n", ""))
        val failed = Generators.plan(WorkspaceSession(store))
        assertTrue(failed.errors.any { "FLD-extra.right.note" in it && "今回の導出" in it }, failed.errors.toString())
        assertTrue(failed.transaction.operations.isEmpty())
    }
}
