package dev.toros.yde.workspace

import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BehaviorAuditTest {
    private val common = """
        id: common
        kind: library
        definitions:
          progress:
            type: enum
            values:
              - {value: new, label: 新規}
              - {value: done, label: 完了}
    """.trimIndent()

    private val task = """
        id: task
        kind: document
        match: "tasks/*.yaml"
        root: {type: object}
        actions:
          - id: create-task
            do: [{create: {schema: task, params: {status: new}}}]
    """.trimIndent()

    private val behavior = """
        id: task-behavior
        kind: behavior
        events:
          - {id: task-finished, entity: task}
        states:
          - source: {entity: task, path: status, enum: common#progress}
            idPrefix: task-
        lifecycles:
          - id: task-lifecycle
            entity: task
            states: [task-new, task-done]
            entry: [{to: task-new, by: {action: create-task}}]
            transitions: [{from: task-new, to: task-done, by: {event: task-finished}}]
            termination: {terminal: [task-done]}
    """.trimIndent()

    private fun inspect(spec: String, definitions: String = common): BehaviorAuditReport {
        val parsed = WorkspaceReader.parseSchemas(listOf(
            DocumentId("_schemas/common.yaml") to definitions,
            DocumentId("_schemas/task.yaml") to task,
            DocumentId("_schemas/task-behavior.yaml") to spec,
        ))
        assertTrue(parsed.errors.isEmpty(), "読み込み失敗: ${parsed.errors}")
        return BehaviorAudit.inspect(parsed.schemas)
    }

    @Test
    fun `原因付きLifecycleの各Stateを入口と流入と流出と終端で説明できる`() {
        val report = inspect(behavior)
        assertEquals("structurally-checked", report.status)
        assertTrue(report.schemaErrors.isEmpty(), "${report.schemaErrors}")
        assertTrue(report.findings.isEmpty(), "${report.findings}")
        assertEquals(2, report.states)
        assertEquals(1, report.events)
        val states = report.lifecycles.single().states
        assertEquals(1, states[0].entryBy.size)
        assertEquals(1, states[0].outgoing.size)
        assertTrue(states[1].terminal)
        assertEquals("task-new", states[1].incoming.single().from)
    }

    @Test
    fun `Lifecycleに未接続のStateとEventはエラーでなく情報とする`() {
        val extraState = """
            - id: task-ready
              entity: task
              predicate:
                match: [{node: {entity: task, bind: t}}]
                where: [{of: t, path: ready, equals: true}]
                expect: exists
        """.trimIndent().prependIndent("  ")
        val spec = behavior
            .replace("  - {id: task-finished, entity: task}", "  - {id: task-finished, entity: task}\n  - {id: audit-notice, entity: task}")
            .replace("lifecycles:", "$extraState\nlifecycles:")
        val report = inspect(spec)
        assertTrue(report.schemaErrors.isEmpty(), "${report.schemaErrors}")
        assertEquals("structurally-checked", report.status)
        assertEquals(setOf("STATE_WITHOUT_LIFECYCLE", "EVENT_WITHOUT_LIFECYCLE"), report.findings.map { it.code }.toSet())
        assertTrue(report.findings.all { it.level == BehaviorAuditLevel.INFO })
    }

    @Test
    fun `同一原因の分岐は合法だが人間のレビュー対象にする`() {
        val enum3 = common.replace("      - {value: done, label: 完了}", "      - {value: done, label: 完了}\n      - {value: held, label: 保留}")
        val branch = behavior
            .replace("states: [task-new, task-done]", "states: [task-new, task-done, task-held]")
            .replace("transitions: [{from: task-new, to: task-done, by: {event: task-finished}}]",
                "transitions:\n      - {from: task-new, to: task-done, by: {event: task-finished}}\n      - {from: task-new, to: task-held, by: {event: task-finished}}")
            .replace("terminal: [task-done]", "terminal: [task-done, task-held]")
        val report = inspect(branch, enum3)
        assertTrue(report.schemaErrors.isEmpty(), "${report.schemaErrors}")
        assertEquals("needs-review", report.status)
        assertEquals("MULTIPLE_TARGETS_FOR_CAUSE", report.findings.single().code)
        assertEquals(BehaviorAuditLevel.REVIEW, report.findings.single().level)
    }

    @Test
    fun `未宣言Eventの参照は既存検証を再利用して構造エラーとする`() {
        val report = inspect(behavior.replace("event: task-finished", "event: unknown-event"))
        assertEquals("schema-errors", report.status)
        assertTrue(report.schemaErrors.any { "Event「unknown-event」が見つかりません" in it })
    }

    @Test
    fun `業務モデルが空なら完全とは判定しない`() {
        assertEquals("not-modeled", BehaviorAudit.inspect(emptyList()).status)
    }
}
