package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.TransitionCause
import dev.toros.yde.yaml.model.DocumentId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Event は操作ではなく、申告された事実に起因する前後差分の診断である。 */
class BehaviorEventTest {
    private val behavior = """
        id: task-behavior
        kind: behavior
        events:
          - { id: approval-granted, entity: flag, label: 許可確認 }
          - { id: approval-revoked, entity: flag, label: 許可取消 }
        states:
          - id: task-blocked
            entity: task
            predicate:
              match:
                - node: { entity: task, bind: t }
                - edge: { via: flag }
                - node: { entity: flag, bind: f }
              where: [ { of: f, path: enabled, equals: false } ]
              expect: exists
          - id: task-ready
            entity: task
            predicate:
              match:
                - node: { entity: task, bind: t }
                - edge: { via: flag }
                - node: { entity: flag, bind: f }
              where: [ { of: f, path: enabled, equals: true } ]
              expect: exists
        lifecycles:
          - id: task-lifecycle
            entity: task
            states: [task-blocked, task-ready]
            entry: [ { to: task-blocked, by: { action: create-task } } ]
            transitions: [ { from: task-blocked, to: task-ready, by: { event: approval-granted } } ]
            termination: { terminal: [task-ready] }
    """.trimIndent()

    private val taskSchema = """
        id: tasks
        kind: document
        match: "tasks/*.yaml"
        root:
          type: object
          entity: { type: task, defines: true }
          fields:
            - { key: id, type: id, required: true }
            - { key: flag, type: ref }
        actions:
          - id: create-task
            do: [ { create: { schema: tasks, params: {} } } ]
    """.trimIndent()

    private fun session(behaviorSchema: String = behavior) = WorkspaceSession(InMemoryDocumentStore(mapOf(
        "_schemas/tasks.yaml" to taskSchema,
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
        "_schemas/task-behavior.yaml" to behaviorSchema,
        "tasks/T-001.yaml" to "id: T-001\nflag: F-001\n",
        "flags/F-001.yaml" to "id: F-001\nenabled: false\n",
    ))).also {
        assertTrue(it.schemaErrors.isEmpty(), "${it.schemaErrors}")
        val catalog = BehaviorChecks.compile(it.schemas)
        assertTrue(catalog.errors.isEmpty(), "${catalog.errors}")
    }

    private fun changed() = PreparedChangeSet(
        listOf(PreparedDocumentChange(DocumentId("flags/F-001.yaml"),
            "id: F-001\nenabled: false\n", "id: F-001\nenabled: true\n")),
        emptyMap(), emptyMap(),
    )

    @Test
    fun `観測したEventで関連EntityのLifecycle遷移を照合できる`() {
        val session = session()
        val version = session.version
        val catalog = BehaviorChecks.compile(session.schemas)
        assertEquals(listOf("approval-granted", "approval-revoked"), catalog.events.map { it.id })

        val matched = BehaviorRuntime.reviewEvent(session, EventOccurrence("approval-granted", "F-001"), changed()).single()
        assertEquals(LifecycleVerdict.MATCHED, matched.verdict)
        assertEquals(listOf("task-blocked"), matched.before)
        assertEquals(listOf("task-ready"), matched.after)
        assertTrue(matched.indirectlyAffected)
        assertEquals(TransitionCause.Event("approval-granted"), matched.cause)

        val wrongEvent = BehaviorRuntime.reviewEvent(session, EventOccurrence("approval-revoked", "F-001"), changed()).single()
        assertEquals(LifecycleVerdict.UNDECLARED, wrongEvent.verdict)
        val asAction = BehaviorRuntime.review(session, ActionRequest("enable-flag", "F-001"), changed()).single()
        assertEquals(LifecycleVerdict.UNDECLARED, asAction.verdict)
        assertEquals(TransitionCause.Action("enable-flag"), asAction.cause)
        assertEquals(version, session.version)
        assertEquals(StateTruth.TRUE, BehaviorRuntime.evaluate(session, "T-001").single { it.stateId == "task-blocked" }.truth)
    }

    @Test
    fun `Eventの発生対象と宣言された型を照合する`() {
        val s = session()
        assertFailsWith<IllegalArgumentException> {
            BehaviorRuntime.reviewEvent(s, EventOccurrence("approval-granted"), changed())
        }
        assertFailsWith<IllegalArgumentException> {
            BehaviorRuntime.reviewEvent(s, EventOccurrence("approval-granted", "T-001"), changed())
        }
        assertFailsWith<IllegalArgumentException> {
            BehaviorRuntime.reviewEvent(s, EventOccurrence("unknown-event", "F-001"), changed())
        }
    }

    @Test
    fun `EventによるEntity生成はLifecycleのentryとして検証できる`() {
        val eventEntry = behavior
            .replace("- { id: approval-revoked, entity: flag, label: 許可取消 }",
                "- { id: task-arrived, entity: task, label: 依頼到着 }")
            .replace("entry: [ { to: task-blocked, by: { action: create-task } } ]",
                "entry: [ { to: task-blocked, by: { event: task-arrived } } ]")
        val session = session(eventEntry)
        val prepared = PreparedChangeSet(
            listOf(PreparedDocumentChange(DocumentId("tasks/T-002.yaml"), null, "id: T-002\nflag: F-001\n")),
            emptyMap(), emptyMap(),
        )
        val review = BehaviorRuntime.reviewEvent(session, EventOccurrence("task-arrived", "T-002"), prepared).single()
        assertEquals(LifecycleVerdict.MATCHED, review.verdict)
        assertEquals(emptyList(), review.before)
        assertEquals(listOf("task-blocked"), review.after)
    }

    @Test
    fun `Eventの参照不足と重複は静的に指摘する`() {
        fun inspect(text: String): BehaviorChecks.Catalog {
            val parsed = WorkspaceReader.parseSchemas(listOf(
                DocumentId("_schemas/tasks.yaml") to taskSchema,
                DocumentId("_schemas/behavior.yaml") to text,
            ))
            assertTrue(parsed.errors.isEmpty(), "${parsed.errors}")
            return BehaviorChecks.compile(parsed.schemas)
        }
        val missing = inspect(behavior.replace("by: { event: approval-granted }", "by: { event: unknown-event }"))
        assertTrue(missing.errors.any { "Event「unknown-event」が見つかりません" in it }, "${missing.errors}")

        val duplicate = inspect(behavior.replace("- { id: approval-revoked, entity: flag, label: 許可取消 }",
            "- { id: approval-granted, entity: flag, label: 許可取消 }"))
        assertTrue(duplicate.errors.any { "Event「approval-granted」が重複しています" in it }, "${duplicate.errors}")
    }

    @Test
    fun `Event宣言はbehavior文書にのみ置ける`() {
        val invalid = assertFailsWith<IllegalArgumentException> {
            SchemaDocuments.parse("id: task\nkind: document\nroot: {type: object}\nevents: [{id: something}]",
                "task", "task.yaml", null)
        }
        assertTrue(invalid.message.orEmpty().contains("events"))
    }

    @Test
    fun `Eventだけのbehavior宣言も有効`() {
        val parsed = WorkspaceReader.parseSchemas(listOf(
            DocumentId("_schemas/only-events.yaml") to "id: only-events\nkind: behavior\nevents: [{id: external-notification}]",
        ))
        assertTrue(parsed.errors.isEmpty(), "${parsed.errors}")
        assertEquals(listOf("external-notification"), parsed.schemas.single().events.map { it.id })
        assertTrue(SchemaDefinitionChecks.issuesOf(parsed.schemas.single()).isEmpty())
    }

    @Test
    fun `差分の無いEvent申告でも対象の存在と型を確認する`() {
        val s = session()
        assertEquals("approval-granted", BehaviorRuntime.validateEvent(s, EventOccurrence("approval-granted", "F-001")).id)
        assertFailsWith<IllegalArgumentException> {
            BehaviorRuntime.validateEvent(s, EventOccurrence("approval-granted"))
        }
        assertFailsWith<IllegalArgumentException> {
            BehaviorRuntime.validateEvent(s, EventOccurrence("approval-granted", "T-001"))
        }
        assertFailsWith<IllegalArgumentException> {
            BehaviorRuntime.validateEvent(s, EventOccurrence("approval-granted", "F-999"))
        }
    }

    @Test
    fun `Eventの申告は専用記録に追記され業務データを変更しない`() {
        val s = session()
        val now = Instant.parse("2026-11-11T00:00:00Z")
        val sourceBefore = s.storedText(DocumentId("flags/F-001.yaml"))
        val preview = BehaviorEventJournal.preview(
            s, EventOccurrence("approval-granted", "F-001"), "external-system", now, "ticket/123",
        )
        assertEquals(null, preview.lifecycleReviews)
        val path = BehaviorEventJournal.record(s, preview, now, "sample-001")
        assertEquals("_runs/events/EVT-sample-001.yaml", path)
        assertEquals(sourceBefore, s.storedText(DocumentId("flags/F-001.yaml")))
        val stored = BehaviorEventJournal.history(s).single()
        assertEquals(path, stored.file)
        assertEquals("approval-granted", stored.value["event"])
        assertEquals("F-001", stored.value["subject"])
        assertEquals("reported-not-verified", stored.value["occurrenceStatus"])
        assertEquals("ticket/123", stored.value["evidence"])
        val review = stored.value["review"] as Map<*, *>
        assertEquals("not-reviewed", review["basis"])
        assertFailsWith<IllegalArgumentException> { BehaviorEventJournal.record(s, preview, now, "sample-001") }
        assertFailsWith<IllegalArgumentException> { BehaviorEventJournal.record(s, preview, now, "../outside") }
    }

    @Test
    fun `Eventの変更候補照合は未適用と明記して記録する`() {
        val s = session()
        val now = Instant.parse("2026-11-11T00:00:00Z")
        val preview = BehaviorEventJournal.preview(
            s, EventOccurrence("approval-granted", "F-001"), "notification", now,
            prepared = changed(),
        )
        assertEquals(LifecycleVerdict.MATCHED, preview.lifecycleReviews!!.single().verdict)
        assertEquals(listOf("flags/F-001.yaml"), preview.proposedChanges.map { it.file })
        BehaviorEventJournal.record(s, preview, now, "candidate-001")
        val review = BehaviorEventJournal.history(s).single().value["review"] as Map<*, *>
        assertEquals("proposed-changes-only", review["basis"])
        val results = review["lifecycles"] as List<*>
        assertEquals("MATCHED", (results.single() as Map<*, *>)["verdict"])
        assertEquals("id: F-001\nenabled: false\n", s.storedText(DocumentId("flags/F-001.yaml")))
    }
}
