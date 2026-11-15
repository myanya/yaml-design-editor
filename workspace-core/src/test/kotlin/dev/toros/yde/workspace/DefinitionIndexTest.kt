package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.EntityRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 定義の索引: 業務モデルの定義（操作・型の操作・前提・State・Lifecycle・エンティティ型）を、定義を書いた場所の出現として索引に載せる。
 * 設計書は同じ ID を詳細化の出現として書き、業務モデルから消えた定義を書き続けていれば、浮いた詳細化として分かる。
 */
class DefinitionIndexTest {
    private val files = mapOf(
        "_schemas/common.yaml" to """
            id: common
            kind: library
            definitions:
              progress:
                type: enum
                values: [{ value: new, label: 新規 }, { value: doing, label: 作業中 }, { value: done, label: 済 }]
              step:
                type: object
                label: 手順
                entity: { type: task-step, defines: true }
                fields:
                  - { key: id, type: id, required: true }
                  - { key: done, type: boolean }
                methods:
                  finish:
                    label: 手順を済にする
                    do: [{ set: { path: done, value: true } }]
        """.trimIndent(),
        "_schemas/task.yaml" to """
            id: task
            match: "tasks/*.yaml"
            entity: { type: task, defines: true }
            root:
              type: object
              label: 作業
              fields:
                - { key: id, type: id, required: true }
                - { key: status, type: common#progress, required: true }
                - { key: steps, type: array, item: { type: common#step } }
            actions:
              - id: finish-task
                label: 作業を済にする
                target: { entity: task }
                lifecycle: task-lifecycle
                require:
                  - id: no-open-step
                    match: [{ node: { entity: task, bind: t } }, { edge: { contains: true } }, { node: { entity: task-step, bind: s } }]
                    where: [{ of: s, path: done, equals: false }]
                    expect: absent
                    message: "{id} には済んでいない手順があります"
                  - match: [{ node: { entity: task, bind: t } }]
                    expect: exists
              - id: create-task
                lifecycle: task-lifecycle
                do: [{ create: { schema: task, id: { prefix: "T-", digits: 1 }, params: {} } }]
              - id: begin-task
                target: { entity: task }
                lifecycle: task-lifecycle
        """.trimIndent(),
        "_schemas/task-behavior.yaml" to """
            id: task-behavior
            kind: behavior
            states:
              - source: { entity: task, path: status, enum: common#progress }
                idPrefix: task-
              - { id: task-open, entity: task, label: 未完了 }
            lifecycles:
              - id: task-lifecycle
                entity: task
                states: [task-open, task-done]
                entry: [{ to: task-open, by: { action: create-task } }]
                transitions: [{ from: task-open, to: task-done, by: { action: finish-task } }]
                termination: { terminal: [task-done] }
            refinements:
              - state: task-open
                states: [task-new, task-doing]
                entry: [{ to: task-new, by: { action: create-task } }]
                transitions: [{ from: task-new, to: task-doing, by: { action: begin-task } }]
        """.trimIndent(),
        // 設計書: 操作と同じ ID を詳細化の出現として書く
        "_schemas/design.yaml" to """
            id: action-spec
            match: "design/*.yaml"
            entity: { type: yde-action, defines: false }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: note, type: string }
        """.trimIndent(),
        "tasks/T-1.yaml" to "id: T-1\nstatus: doing\nsteps: [{ id: S-1, done: false }]\n",
        "design/finish-task.yaml" to "id: finish-task\nnote: 済にする処理\n",
        "design/gone.yaml" to "id: gone-action\nnote: 業務モデルから消えた操作\n",
    )

    @Test
    fun `定義を書いた場所に 定義の出現として載る`() {
        val ws = WorkspaceReader.load(InMemoryDocumentStore(files))
        assertEquals(emptyList(), ws.schemaErrors)
        val index = ws.derived.semantic
        fun definition(id: String) = assertNotNull(index.occurrences(id).firstOrNull { it.role == EntityRole.DEFINING }, "$id: ${index.ids.keys}")
        // 操作と前提（名前を付けたものだけ。操作ID.前提ID）
        definition("finish-task").let {
            assertEquals(DefinitionIndex.ACTION, it.entityType)
            assertEquals("_schemas/task.yaml", it.document.value)
            assertEquals(listOf<Any>("actions", 0), it.path)
            assertEquals("作業を済にする", it.title)
        }
        definition("finish-task.no-open-step").let {
            assertEquals(DefinitionIndex.REQUIRE, it.entityType)
            assertEquals(listOf<Any>("actions", 0, "require", 0), it.path)
        }
        assertEquals(1, index.ids.keys.count { it.startsWith("finish-task.") }, "名前の無い前提は載せない")
        // 型の操作（エンティティ型.操作名）
        definition("task-step.finish").let {
            assertEquals(DefinitionIndex.ACTION, it.entityType)
            assertEquals(listOf<Any>("definitions", "step", "methods", "finish"), it.path)
        }
        // State: 列挙型から作ったものは source の宣言の場所。詳細化の親（述語なし）も
        definition("task-doing").let {
            assertEquals(DefinitionIndex.STATE, it.entityType)
            assertEquals("_schemas/task-behavior.yaml", it.document.value)
            assertEquals(listOf<Any>("states", 0), it.path)
            assertEquals("作業中", it.title)
        }
        assertEquals(DefinitionIndex.STATE, definition("task-open").entityType)
        assertEquals(DefinitionIndex.LIFECYCLE, definition("task-lifecycle").entityType)
        // エンティティ型: 定義の宣言の場所に 1 つ
        definition("task").let {
            assertEquals(DefinitionIndex.ENTITY, it.entityType)
            assertEquals("_schemas/task.yaml", it.document.value)
        }
        assertEquals(listOf<Any>("definitions", "step"), definition("task-step").path)
        // 項目（エンティティ型.キー）: 項目を書いた場所。スキーマの entity の宣言の文書はルートの項目、名前付きの型は定義の中
        definition("task.status").let {
            assertEquals(DefinitionIndex.FIELD, it.entityType)
            assertEquals("_schemas/task.yaml", it.document.value)
            assertEquals(listOf<Any>("root", "fields", 1), it.path)
        }
        assertEquals(listOf<Any>("definitions", "step", "fields", 1), definition("task-step.done").path)
        assertTrue(index.ids.keys.none { it.startsWith("yde-") || it.startsWith("action-spec.") }, "定義の種類を詳細化する設計書の項目は載せない")
        // 設計書の出現は、同じ ID の詳細化
        assertEquals(listOf(EntityRole.DEFINING, EntityRole.DETAIL), index.occurrences("finish-task").map { it.role })
    }

    @Test
    fun `業務モデルに無い ID を詳細化すると浮いた詳細化 定義の種類を定義として宣言すると定義の誤り`() {
        val ws = WorkspaceReader.load(InMemoryDocumentStore(files))
        val issues = WorkspaceCheckRunner.validateAll(ws).flatMap { (doc, v) -> v.issues.map { doc.value to it.message } }
        assertTrue(issues.any { it.first == "design/gone.yaml" && "gone-action" in it.second && "定義出現がありません" in it.second }, issues.toString())
        assertTrue(issues.none { it.first == "design/finish-task.yaml" }, issues.toString())
        val bad = files + ("_schemas/design.yaml" to files.getValue("_schemas/design.yaml").replace("defines: false", "defines: true"))
        val errors = WorkspaceReader.load(InMemoryDocumentStore(bad)).schemaErrors
        assertTrue(errors.any { "エンティティ型「yde-action」は業務モデルの定義の種類です" in it }, errors.toString())
    }

    @Test
    fun `文書だけを変えた増分の更新でも 定義の出現は残る`() {
        val s = WorkspaceSession(InMemoryDocumentStore(files))
        assertTrue(s.derived.semantic.occurrences("finish-task").any { it.role == EntityRole.DEFINING })
        val doc = dev.toros.yde.yaml.model.DocumentId("design/finish-task.yaml")
        s.replaceDocument(doc, dev.toros.yde.yaml.DefaultYamlCodec.parse(doc, "id: finish-task\nnote: 書き換えた\n"))
        assertTrue(s.derived.semantic.occurrences("finish-task").any { it.role == EntityRole.DEFINING })
        assertTrue(s.derived.semantic.occurrences("task-done").any { it.entityType == DefinitionIndex.STATE })
    }
}
