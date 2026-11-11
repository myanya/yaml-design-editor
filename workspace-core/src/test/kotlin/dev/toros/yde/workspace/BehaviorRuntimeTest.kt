package dev.toros.yde.workspace

import dev.toros.yde.yaml.model.DocumentId
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BehaviorRuntimeTest {
    private fun session(status: String = "new"): WorkspaceSession = WorkspaceSession(InMemoryDocumentStore(mapOf(
        "_schemas/common.yaml" to """
            id: common
            kind: library
            definitions:
              progress:
                type: enum
                values: [new, done]
        """.trimIndent(),
        "_schemas/task.yaml" to """
            id: task
            kind: document
            match: "tasks/*.yaml"
            root:
              type: object
              entity: { type: task, defines: true }
              fields:
                - { key: id, type: id, required: true }
                - { key: status, type: common#progress }
            actions:
              - id: create-task
                do: [ { create: { schema: task, params: { status: new } } } ]
              - id: finish-task
                target: { entity: task }
                do: [ { set: { of: self, path: status, value: done } } ]
              - id: edit-task
                target: { entity: task }
                do: [ { set: { of: self, path: status, value: done } } ]
        """.trimIndent(),
        "_schemas/task-behavior.yaml" to """
            id: task-behavior
            kind: behavior
            states:
              - source: { entity: task, path: status, enum: common#progress }
                idPrefix: task-
            lifecycles:
              - id: task-lifecycle
                entity: task
                states: [task-new, task-done]
                entry: [ { to: task-new, by: { action: create-task } } ]
                transitions: [ { from: task-new, to: task-done, by: { action: finish-task } } ]
                termination: { terminal: [task-done] }
        """.trimIndent(),
        "tasks/T-001.yaml" to "id: T-001\nstatus: $status\n",
    ))).also { assertTrue(it.schemaErrors.isEmpty(), "${it.schemaErrors}") }

    private fun changed(status: String, before: String? = "id: T-001\nstatus: new\n", id: String = "T-001") =
        PreparedChangeSet(
            listOf(PreparedDocumentChange(DocumentId("tasks/$id.yaml"), before, "id: $id\nstatus: $status\n")),
            emptyMap(), emptyMap(),
        )

    @Test
    fun `Stateを既存のSemanticRulesで評価し元データを変えない`() {
        val s = session()
        val version = s.version
        val values = BehaviorRuntime.evaluate(s, "T-001").associate { it.stateId to it.truth }
        assertEquals(mapOf("task-new" to StateTruth.TRUE, "task-done" to StateTruth.FALSE), values)
        assertEquals(emptyList(), BehaviorRuntime.evaluate(s, "missing-id"))
        assertEquals(version, s.version)
        assertFalse(s.snapshot.documents.getValue(DocumentId("tasks/T-001.yaml")).document.documents.isEmpty())
    }

    @Test
    fun `Actionの実際の計画による遷移はLifecycleと一致する`() {
        val s = session()
        val request = ActionRequest("finish-task", "T-001")
        val plan = ActionPlanner.plan(s, request, LocalDateTime.of(2026, 11, 11, 10, 0))
        val prepared = ChangeSetApplier.prepare(s, plan.changeSet)
        assertTrue(prepared.failures.isEmpty(), "${prepared.failures}")
        val result = BehaviorRuntime.review(s, request, prepared).single()
        assertEquals(LifecycleVerdict.MATCHED, result.verdict)
        assertEquals(listOf("task-new"), result.before)
        assertEquals(listOf("task-done"), result.after)
        assertFalse(result.needsAttention)
        assertEquals("new", BehaviorRuntime.evaluate(s, "T-001").single { it.truth == StateTruth.TRUE }.stateId.removePrefix("task-"))
    }

    @Test
    fun `Stateは変わるが原因Actionが遷移宣言に無ければ仕様不足として指摘する`() {
        val s = session()
        val result = BehaviorRuntime.review(s, ActionRequest("edit-task", "T-001"), changed("done")).single()
        assertEquals(LifecycleVerdict.UNDECLARED, result.verdict)
        assertTrue(result.needsAttention)
        assertTrue(result.message.contains("宣言されていません"))
    }

    @Test
    fun `生成したEntityは入口Actionとの一致を検査する`() {
        val s = session()
        val candidate = changed("new", null, "T-002")
        val matched = BehaviorRuntime.review(s, ActionRequest("create-task"), candidate).single()
        assertEquals(LifecycleVerdict.MATCHED, matched.verdict)
        assertEquals(emptyList(), matched.before)
        assertEquals(listOf("task-new"), matched.after)
        val missing = BehaviorRuntime.review(s, ActionRequest("edit-task"), candidate).single()
        assertEquals(LifecycleVerdict.UNDECLARED, missing.verdict)
    }

    @Test
    fun `業務Stateがどれも成立しないと不整合として報告する`() {
        val s = session(status = "unknown")
        val result = BehaviorRuntime.review(s, ActionRequest("finish-task", "T-001"), changed("done", "id: T-001\nstatus: unknown\n")).single()
        assertEquals(LifecycleVerdict.INCONSISTENT, result.verdict)
        assertTrue(result.needsAttention)
        assertEquals(emptyList(), result.before)
    }

    @Test
    fun `状態変更のないActionは未宣言の遷移と扱わない`() {
        val s = session()
        val result = BehaviorRuntime.review(s, ActionRequest("edit-task", "T-001"), changed("new")).single()
        assertEquals(LifecycleVerdict.UNCHANGED, result.verdict)
        assertFalse(result.needsAttention)
    }
}
