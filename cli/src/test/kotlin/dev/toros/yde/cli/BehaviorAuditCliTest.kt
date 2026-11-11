package dev.toros.yde.cli

import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BehaviorAuditCliTest {
    private lateinit var root: Path

    @BeforeTest
    fun prepare() {
        root = createTempDirectory("yde-behavior-audit")
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
              - {id: task-finished, entity: task}
              - {id: notification-only, entity: task}
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
        Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach { file -> Files.deleteIfExists(file) } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun audit(): Map<String, Any?> =
        YamlIO.parseToRaw(runCli(arrayOf("behavior-audit", root.toString()))) as Map<String, Any?>

    @Test
    fun `State単位の接続一覧と未接続Eventを読み取り専用で返す`() {
        val result = audit()
        assertEquals("structurally-checked", result["status"])
        assertEquals("not-proven", result["proofStatus"])
        assertEquals(emptyList<Any>(), result["schemaErrors"])
        val info = result["findings"] as List<Map<String, Any?>>
        assertEquals("EVENT_WITHOUT_LIFECYCLE", info.single()["code"])
        assertEquals("info", info.single()["level"])
        val lifecycles = result["lifecycles"] as List<Map<String, Any?>>
        val states = lifecycles.single()["states"] as List<Map<String, Any?>>
        assertEquals("task-new", states[0]["id"])
        assertEquals(false, states[0]["terminal"])
        assertEquals(1, (states[0]["outgoing"] as List<*>).size)
        assertEquals(true, states[1]["terminal"])
        assertTrue((result["proofLimits"] as List<*>).isNotEmpty())
        assertEquals("id: T-001\nstatus: new\n", Files.readString(root.resolve("items/T-001.yaml")))
        assertEquals(false, Files.exists(root.resolve("_runs")))
    }

    @Test
    fun `不足したEventの定義がある場合は完成と判定しない`() {
        val path = root.resolve("_schemas/task-behavior.yaml")
        Files.writeString(path, Files.readString(path).replace("event: task-finished", "event: missing-event"))
        val result = audit()
        assertEquals("schema-errors", result["status"])
        assertTrue((result["schemaErrors"] as List<*>).any { "missing-event" in it.toString() })
    }
}
