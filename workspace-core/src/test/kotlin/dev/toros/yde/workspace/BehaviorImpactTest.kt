package dev.toros.yde.workspace

import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BehaviorImpactTest {
    /** 依頼 → 規程 → 許可フラグという、文書をまたぐ2段の参照。 */
    private fun session() = WorkspaceSession(InMemoryDocumentStore(mapOf(
        "_schemas/tasks.yaml" to """
            id: tasks
            kind: document
            match: "tasks/*.yaml"
            root:
              type: object
              entity: { type: task, defines: true }
              fields:
                - { key: id, type: id, required: true }
                - { key: policy, type: ref }
            actions:
              - id: create-task
                do: [ { create: { schema: tasks, params: {} } } ]
        """.trimIndent(),
        "_schemas/policies.yaml" to """
            id: policies
            kind: document
            match: "policies/*.yaml"
            root:
              type: object
              entity: { type: policy, defines: true }
              fields:
                - { key: id, type: id, required: true }
                - { key: flag, type: ref }
        """.trimIndent(),
        "_schemas/flags.yaml" to """
            id: flags
            kind: document
            match: "flags/*.yaml"
            root:
              type: object
              entity: { type: flag, defines: true }
              fields:
                - { key: id, type: id, required: true }
                - { key: enabled, type: boolean, required: true }
            actions:
              - id: enable-flag
                target: { entity: flag }
                do: [ { set: { of: self, path: enabled, value: true } } ]
        """.trimIndent(),
        "_schemas/task-behavior.yaml" to """
            id: task-behavior
            kind: behavior
            states:
              - id: task-blocked
                entity: task
                predicate:
                  match:
                    - node: { entity: task, bind: t }
                    - edge: { via: policy }
                    - node: { entity: policy, bind: p }
                    - edge: { via: flag }
                    - node: { entity: flag, bind: f }
                  where: [ { of: f, path: enabled, equals: false } ]
                  expect: exists
              - id: task-ready
                entity: task
                predicate:
                  match:
                    - node: { entity: task, bind: t }
                    - edge: { via: policy }
                    - node: { entity: policy, bind: p }
                    - edge: { via: flag }
                    - node: { entity: flag, bind: f }
                  where: [ { of: f, path: enabled, equals: true } ]
                  expect: exists
            lifecycles:
              - id: task-lifecycle
                entity: task
                states: [task-blocked, task-ready]
                entry: [ { to: task-blocked, by: { action: create-task } } ]
                transitions: [ { from: task-blocked, to: task-ready, by: { action: enable-flag } } ]
                termination: { terminal: [task-ready] }
        """.trimIndent(),
        "tasks/T-001.yaml" to "id: T-001\npolicy: P-001\n",
        "policies/P-001.yaml" to "id: P-001\nflag: F-001\n",
        "flags/F-001.yaml" to "id: F-001\nenabled: false\n",
        "tasks/T-002.yaml" to "id: T-002\npolicy: P-002\n",
        "policies/P-002.yaml" to "id: P-002\nflag: F-002\n",
        "flags/F-002.yaml" to "id: F-002\nenabled: false\n",
    ))).also {
        assertTrue(it.schemaErrors.isEmpty(), "${it.schemaErrors}")
        assertTrue(BehaviorChecks.compile(it.schemas).errors.isEmpty())
    }

    private fun changed(after: String) = PreparedChangeSet(
        listOf(PreparedDocumentChange(DocumentId("flags/F-001.yaml"), "id: F-001\nenabled: false\n", after)),
        emptyMap(), emptyMap(),
    )

    @Test
    fun `別文書の2段先の変更でもState遷移を検出し無関係なEntityは含めない`() {
        val session = session()
        val originalVersion = session.version
        val before = BehaviorRuntime.evaluate(session, "T-001").associate { it.stateId to it.truth }
        assertEquals(StateTruth.TRUE, before["task-blocked"])
        assertEquals(StateTruth.FALSE, before["task-ready"])

        val reviews = BehaviorRuntime.review(session, ActionRequest("enable-flag", "F-001"),
            changed("id: F-001\nenabled: true\n"))
        assertEquals(listOf("T-001"), reviews.map { it.entityId })
        assertEquals(LifecycleVerdict.MATCHED, reviews.single().verdict)
        assertEquals(listOf("task-blocked"), reviews.single().before)
        assertEquals(listOf("task-ready"), reviews.single().after)
        assertTrue(reviews.single().indirectlyAffected)
        assertEquals(originalVersion, session.version)
        assertEquals(StateTruth.TRUE, BehaviorRuntime.evaluate(session, "T-001").single { it.stateId == "task-blocked" }.truth)
    }

    @Test
    fun `古い関係を消す変更でも変更前の線を使って影響候補を残す`() {
        val session = session()
        // F-001 の ID を変えると P-001 の参照先が消える。変更後だけのグラフでは T-001 に辿れない。
        val reviews = BehaviorRuntime.review(session, ActionRequest("enable-flag", "F-001"),
            changed("id: F-003\nenabled: true\n"))
        assertEquals(listOf("T-001"), reviews.map { it.entityId })
        assertTrue(reviews.single().needsAttention)
        assertEquals(LifecycleVerdict.UNDETERMINED, reviews.single().verdict)
    }
}
