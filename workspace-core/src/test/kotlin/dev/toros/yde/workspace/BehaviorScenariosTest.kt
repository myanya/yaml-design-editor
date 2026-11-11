package dev.toros.yde.workspace

import dev.toros.yde.yaml.model.DocumentId
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BehaviorScenariosTest {
    private fun session(): WorkspaceSession = WorkspaceSession(InMemoryDocumentStore(mapOf(
        "_schemas/task.yaml" to """
            id: task
            kind: document
            match: "tasks/*.yaml"
            root:
              type: object
              entity: {type: task, defines: true}
              fields:
                - {key: id, type: id, required: true}
                - {key: flag, type: ref}
            actions:
              - id: create-task
                do: [{create: {schema: task, params: {}}}]
        """.trimIndent(),
        "_schemas/flag.yaml" to """
            id: flag
            kind: document
            match: "flags/*.yaml"
            root:
              type: object
              entity: {type: flag, defines: true}
              fields:
                - {key: id, type: id, required: true}
                - {key: enabled, type: boolean, required: true}
            actions:
              - id: enable-flag
                target: {entity: flag}
                do: [{set: {of: self, path: enabled, value: true}}]
        """.trimIndent(),
        "_schemas/task-behavior.yaml" to """
            id: task-behavior
            kind: behavior
            events:
              - {id: approval-granted, entity: flag}
              - {id: approval-revoked, entity: flag}
            states:
              - id: task-blocked
                entity: task
                predicate:
                  match:
                    - node: {entity: task, bind: t}
                    - edge: {via: flag}
                    - node: {entity: flag, bind: f}
                  where: [{of: f, path: enabled, equals: false}]
                  expect: exists
              - id: task-ready
                entity: task
                predicate:
                  match:
                    - node: {entity: task, bind: t}
                    - edge: {via: flag}
                    - node: {entity: flag, bind: f}
                  where: [{of: f, path: enabled, equals: true}]
                  expect: exists
            lifecycles:
              - id: task-lifecycle
                entity: task
                states: [task-blocked, task-ready]
                entry: [{to: task-blocked, by: {action: create-task}}]
                transitions:
                  - {from: task-blocked, to: task-ready, by: {action: enable-flag}}
                  - {from: task-blocked, to: task-ready, by: {event: approval-granted}}
                termination: {terminal: [task-ready]}
        """.trimIndent(),
        "tasks/T-001.yaml" to "id: T-001\nflag: F-001\n",
        "flags/F-001.yaml" to "id: F-001\nenabled: false\n",
    ))).also { assertTrue(it.schemaErrors.isEmpty(), "${it.schemaErrors}") }

    private val cases = """
        cases:
          - id: action-approved
            action: enable-flag
            target: F-001
            at: 2026-11-11T10:00:00
            expect:
              - lifecycle: task-lifecycle
                entity: T-001
                before: [task-blocked]
                after: [task-ready]
                verdict: MATCHED
                indirectlyAffected: true
          - id: event-approved
            event: approval-granted
            subject: F-001
            changeset:
              changes:
                - file: flags/F-001.yaml
                  ops: [{op: set, path: enabled, value: true}]
            expect:
              - lifecycle: task-lifecycle
                entity: T-001
                before: [task-blocked]
                after: [task-ready]
                verdict: MATCHED
          - id: wrong-event
            event: approval-revoked
            subject: F-001
            changeset:
              changes:
                - file: flags/F-001.yaml
                  ops: [{op: set, path: enabled, value: true}]
            expect:
              - lifecycle: task-lifecycle
                entity: T-001
                before: [task-blocked]
                after: [task-ready]
                verdict: UNDECLARED
    """.trimIndent()

    @Test
    fun `ActionとEventの具体例は独立に実行され未検証の入口が残る`() {
        val s = session()
        val version = s.version
        val source = s.storedText(DocumentId("flags/F-001.yaml"))
        val report = BehaviorScenarios.run(s, BehaviorScenarios.parse(cases), LocalDateTime.of(2026, 11, 11, 10, 0))
        assertEquals(emptyList(), report.schemaErrors)
        assertEquals("needs-review", report.status)
        assertEquals(listOf("passed", "passed", "needs-review"), report.cases.map { it.status })
        assertTrue(report.cases.all { it.expectationsMatch && it.proposalValid == true })
        assertTrue(report.cases.all { it.reviews.single().indirectlyAffected })
        assertEquals(3, report.coverage.size)
        assertEquals(2, report.coverage.count { it.exercisedBy.isNotEmpty() })
        assertEquals(listOf("action-approved"), report.coverage.single { it.cause.id == "enable-flag" }.exercisedBy)
        assertEquals(listOf("event-approved"), report.coverage.single { it.cause.id == "approval-granted" }.exercisedBy)
        assertFalse(report.coverage.single { it.from == null }.exercisedBy.isNotEmpty())
        assertEquals(source, s.storedText(DocumentId("flags/F-001.yaml")))
        assertEquals(version, s.version)
    }

    @Test
    fun `期待したStateと異なるとケースは失敗し網羅に加算しない`() {
        val s = session()
        val mismatched = cases.replace("after: [task-ready]", "after: [task-blocked]")
        val report = BehaviorScenarios.run(s, BehaviorScenarios.parse(mismatched))
        assertEquals("failed", report.status)
        assertTrue(report.cases.all { it.status == "failed" })
        assertTrue(report.cases.all { it.findings.any { finding -> "after" in finding } })
        assertEquals(0, report.coverage.count { it.exercisedBy.isNotEmpty() })
    }

    @Test
    fun `計画不能と不正な変更候補を失敗として報告する`() {
        val s = session()
        val bad = cases.replace("target: F-001", "target: F-999")
            .replace("path: enabled", "path: missing")
        val report = BehaviorScenarios.run(s, BehaviorScenarios.parse(bad))
        assertEquals("failed", report.status)
        assertTrue(report.cases.all { it.status == "failed" })
        assertTrue(report.cases.any { it.proposalValid == false })
        assertTrue(report.cases.all { it.findings.isNotEmpty() })
    }

    @Test
    fun `不明なキーや重複期待値とEventの変更候補不足を拒否する`() {
        assertFailsWith<IllegalArgumentException> {
            BehaviorScenarios.parse("cases: [{id: x, event: approval-granted, subject: F-001, expect: []}]")
        }
        assertFailsWith<IllegalArgumentException> {
            BehaviorScenarios.parse("cases: [{id: x, action: enable-flag, targte: F-001, expect: []}]")
        }
        assertFailsWith<IllegalArgumentException> {
            BehaviorScenarios.parse(cases.replace("id: event-approved", "id: action-approved"))
        }
        val duplicated = cases.replace(
            "                indirectlyAffected: true",
            """                indirectlyAffected: true
              - lifecycle: task-lifecycle
                entity: T-001
                before: [task-blocked]
                after: [task-ready]
                verdict: MATCHED""",
        )
        assertFailsWith<IllegalArgumentException> { BehaviorScenarios.parse(duplicated) }
    }
}
