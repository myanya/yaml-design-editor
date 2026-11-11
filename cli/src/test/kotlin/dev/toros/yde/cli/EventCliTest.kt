package dev.toros.yde.cli

import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventCliTest {
    private lateinit var root: Path

    @BeforeTest
    fun prepare() {
        root = createTempDirectory("yde-events")
        Files.createDirectories(root.resolve("_schemas"))
        Files.createDirectories(root.resolve("items"))
        Files.writeString(root.resolve("_schemas/tasks.yaml"), """
            id: tasks
            kind: document
            match: "items/*.yaml"
            root:
              type: object
              entity: {type: task, defines: true}
              fields:
                - {key: id, type: id, required: true}
                - {key: status, type: enum, values: [new, done], required: true}
        """.trimIndent())
        Files.writeString(root.resolve("_schemas/task-behavior.yaml"), """
            id: task-behavior
            kind: behavior
            events:
              - {id: task-created, entity: task}
              - {id: task-finished, entity: task, label: 完了通知}
            states:
              - id: task-new
                entity: task
                predicate:
                  match: [{node: {entity: task, bind: t}}]
                  where: [{of: t, path: status, equals: new}]
                  expect: exists
              - id: task-done
                entity: task
                predicate:
                  match: [{node: {entity: task, bind: t}}]
                  where: [{of: t, path: status, equals: done}]
                  expect: exists
            lifecycles:
              - id: task-lifecycle
                entity: task
                states: [task-new, task-done]
                entry: [{to: task-new, by: {event: task-created}}]
                transitions: [{from: task-new, to: task-done, by: {event: task-finished}}]
                termination: {terminal: [task-done]}
        """.trimIndent())
        Files.writeString(root.resolve("items/T-001.yaml"), "id: T-001\nstatus: new\n")
    }

    @AfterTest
    fun cleanup() {
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsed(text: String) = YamlIO.parseToRaw(text) as Map<String, Any?>
    private fun event(vararg args: String) = parsed(runCli(arrayOf("event", root.toString(), *args)))

    @Test
    fun `一覧とプレビューは業務文書も記録も変更しない`() {
        val list = event()
        assertEquals(emptyList<Any>(), list["schemaErrors"])
        val names = (list["events"] as List<Map<String, Any?>>).map { it["id"] }
        assertEquals(listOf("task-created", "task-finished"), names)
        val result = event("task-finished", "--on", "T-001", "--source", "manual")
        assertEquals(false, result["recorded"])
        assertEquals("reported-not-verified", result["occurrenceStatus"])
        assertEquals("not-reviewed", result["reviewBasis"])
        assertEquals(emptyList<Any>(), event("--history")["events"])
        assertFalse(Files.exists(root.resolve("_runs/events")))
        assertEquals("id: T-001\nstatus: new\n", Files.readString(root.resolve("items/T-001.yaml")))
    }

    @Test
    fun `変更候補を照合した申告記録は候補のみと表示して保存する`() {
        val changeFile = root.resolve("candidate.txt")
        Files.writeString(changeFile, """
            changes:
              - file: items/T-001.yaml
                ops:
                  - {op: set, path: status, value: done}
        """.trimIndent())
        val result = event(
            "task-finished", "--on", "T-001", "--source", "external-workflow",
            "--evidence", "notification/123", "--occurred-at", "2026-11-11T09:00:00+09:00",
            "--changeset", changeFile.toString(), "--record",
        )
        assertEquals(true, result["recorded"])
        assertEquals("proposed-changes-only", result["reviewBasis"])
        assertEquals(true, result["proposalValid"])
        assertEquals(0, result["needsAttention"])
        assertEquals("MATCHED", (result["lifecycles"] as List<Map<String, Any?>>).single()["verdict"])
        assertEquals("id: T-001\nstatus: new\n", Files.readString(root.resolve("items/T-001.yaml")))
        val file = result["file"] as String
        assertTrue(file.startsWith("_runs/events/EVT-"), file)
        val written = parsed(Files.readString(root.resolve(file)))
        assertEquals("event-occurrence", written["kind"])
        assertEquals("reported-not-verified", written["occurrenceStatus"])
        assertEquals("2026-11-11T00:00:00Z", written["occurredAt"])
        assertEquals("notification/123", written["evidence"])
        val records = event("--history")["events"] as List<Map<String, Any?>>
        assertEquals(1, records.size)
        assertEquals("task-finished", records.single()["event"])
    }

    @Test
    fun `出典が無い記録と未知の対象を拒否する`() {
        assertFailsWith<IllegalStateException> { event("task-finished", "--on", "T-001", "--record") }
        assertFailsWith<IllegalArgumentException> { event("task-finished", "--on", "T-999", "--source", "manual") }
        assertFailsWith<IllegalArgumentException> { event("unknown", "--source", "manual") }
        assertFalse(Files.exists(root.resolve("_runs/events")))
    }
}
