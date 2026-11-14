package dev.toros.yde.workspace

import dev.toros.yde.yaml.model.DocumentId
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 条件に State を名前で書く（`where: [{of: t, state: …}]`。UX-315）: 読み込みのときに State の条件（値の項目の equals / oneOf）へ
 * 置き換える。操作の前提・スキーマの検査・型の検査・ダッシュボードの区画のどれにも書け、詳細化の親は「子のどれか」になる。
 */
class StateConditionsTest {
    private val now = LocalDateTime.of(2026, 11, 14, 10, 0)

    private fun files(extra: Map<String, String> = emptyMap()): Map<String, String> = mapOf(
        "_schemas/common.yaml" to """
            id: common
            kind: library
            definitions:
              progress:
                type: enum
                values: [new, doing, done, dropped]
        """.trimIndent(),
        "_schemas/task.yaml" to """
            id: task
            match: "tasks/*.yaml"
            root:
              type: object
              entity: { type: task, defines: true }
              fields:
                - { key: id, type: id, required: true }
                - { key: status, type: common#progress, required: true }
                - { key: memo, type: string }
                - { key: closed_on, type: date }
              constraints:
                # 型の検査: 終わっていないのに終わった日がある（not と State の名前）
                - id: open-has-no-closed-on
                  where: [{ not: { state: task-done } }, { path: closed_on, exists: true }]
                  expect: absent
                  level: error
            constraints:
              # スキーマの検査（パターン）: 未完了にメモが無い
              - id: open-needs-memo
                match: [{ node: { entity: task, bind: t } }]
                where: [{ of: t, state: task-open }, { of: t, path: memo, exists: false }]
                expect: absent
                level: warning
                message: "{id} にメモがありません"
            actions:
              - id: note-task
                target: { entity: task }
                params: [{ key: memo, type: string, required: true }]
                require:
                  - match: [{ node: { entity: task, bind: t } }]
                    where: [{ of: t, state: task-open }]
                    expect: exists
                    message: "{id} は終わっているのでメモを書けません"
                do: [{ set: { path: memo, value: ${'$'}memo, of: self } }]
        """.trimIndent(),
        "_schemas/task-behavior.yaml" to """
            id: task-behavior
            kind: behavior
            states:
              - source: { entity: task, path: status, enum: common#progress }
                idPrefix: task-
              - { id: task-open, entity: task, label: 未完了 }
              - id: task-memo
                entity: task
                predicate: { match: [ { node: { entity: task, bind: self } } ], where: [ { of: self, path: memo, exists: true } ], expect: exists }
            refinements:
              - state: task-open
                states: [task-new, task-doing]
                entry: [ { to: task-new, by: { action: note-task } } ]
                transitions: [ { from: task-new, to: task-doing, by: { action: note-task } } ]
        """.trimIndent(),
        "tasks/T-001.yaml" to "id: T-001\nstatus: doing\nmemo: 作業中\n",
        "tasks/T-002.yaml" to "id: T-002\nstatus: done\n",
        "tasks/T-003.yaml" to "id: T-003\nstatus: new\nclosed_on: 2026-11-01\n",
    ) + extra

    private fun session(extra: Map<String, String> = emptyMap()) = WorkspaceSession(InMemoryDocumentStore(files(extra)))

    @Test
    fun `操作の前提とスキーマの検査と型の検査に State の名前で書け 詳細化の親は子のどれかになる`() {
        val s = session()
        assertTrue(s.schemaErrors.none { "state" in it }, s.schemaErrors.toString())
        // 前提: 未完了（new・doing）だけ
        ActionPlanner.plan(s, ActionRequest("note-task", "T-001", mapOf("memo" to "x")), now)
        val refused = assertFailsWith<ActionFailure> { ActionPlanner.plan(s, ActionRequest("note-task", "T-002", mapOf("memo" to "x")), now) }
        assertTrue("T-002 は終わっているのでメモを書けません" in refused.message.orEmpty(), refused.message)
        val ws = WorkspaceReader.load(InMemoryDocumentStore(files()))
        val issues = WorkspaceCheckRunner.validateAll(ws).flatMap { (doc, v) -> v.issues.map { doc.value to it.message } }
        // スキーマの検査（未完了＝new の T-003 にメモが無い）と、型の検査（終わっていない T-003 に終わった日）。終わった T-002 は対象外
        assertTrue(issues.any { it.first == "tasks/T-003.yaml" && "T-003 にメモがありません" in it.second }, issues.toString())
        assertTrue(issues.any { it.first == "tasks/T-003.yaml" && "条件に合います" in it.second }, issues.toString())
        assertTrue(issues.none { it.first == "tasks/T-002.yaml" }, issues.toString())
    }

    @Test
    fun `ダッシュボードの区画にも State の名前を並べて書ける`() {
        val s = session()
        val dashboard = """
            id: home
            kind: dashboard
            sections:
              - id: active
                view: tasks
                filter:
                  match: [{ node: { entity: task, bind: t } }]
                  where: [{ of: t, state: [task-new, task-doing] }]
                  expect: exists
        """.trimIndent()
        val grid = "id: tasks\nrows:\n  match: [{node: {entity: task, bind: t}}]\ncolumns:\n  - {label: 状態, path: status}\n"
        val catalog = ViewDefinitions.load(listOf(DocumentId(".yde/views/home.yaml") to dashboard, DocumentId(".yde/views/tasks.yaml") to grid), s.schemas)
        assertEquals(emptyList(), catalog.errors)
        val filter = catalog.dashboards.single().sections.single().filter!!
        assertTrue(filter.toString().contains("oneOf") || filter.toString().contains("ONE_OF"), filter.toString())
    }

    @Test
    fun `見つからない State と 述語で書いた State と path を一緒に書いた条件は読み込みの誤り`() {
        fun errorsWith(where: String): List<String> {
            val schema = files()["_schemas/task.yaml"]!!.replace("where: [{ of: t, state: task-open }]", "where: [$where]")
            return session(mapOf("_schemas/task.yaml" to schema)).schemaErrors
        }
        assertTrue(errorsWith("{ of: t, state: task-nope }").any { "state「task-nope」" in it }, errorsWith("{ of: t, state: task-nope }").toString())
        assertTrue(errorsWith("{ of: t, state: task-memo }").any { "state「task-memo」" in it })
        // state と path は一緒に書けない
        assertTrue(errorsWith("{ of: t, state: task-open, path: status }").any { "of と state だけ" in it })
    }
}
