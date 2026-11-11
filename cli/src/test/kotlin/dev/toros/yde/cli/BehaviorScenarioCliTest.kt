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

class BehaviorScenarioCliTest {
    private lateinit var root: Path
    private lateinit var cases: Path

    @BeforeTest
    fun prepare() {
        root = createTempDirectory("yde-behavior-scenarios")
        Files.createDirectories(root.resolve("_schemas"))
        Files.createDirectories(root.resolve("items"))
        Files.writeString(root.resolve("_schemas/task.yaml"), """
            id: task
            kind: document
            match: "items/*.yaml"
            root:
              type: object
              entity: {type: task, defines: true}
              fields:
                - {key: id, type: id, required: true}
                - {key: status, type: enum, values: [new, done], required: true}
            actions:
              - id: finish-task
                target: {entity: task}
                do: [{set: {of: self, path: status, value: done}}]
              - id: create-task
                do: [{create: {schema: task, params: {status: new}}}]
        """.trimIndent())
        Files.writeString(root.resolve("_schemas/task-behavior.yaml"), """
            id: task-behavior
            kind: behavior
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
                entry: [{to: task-new, by: {action: create-task}}]
                transitions: [{from: task-new, to: task-done, by: {action: finish-task}}]
                termination: {terminal: [task-done]}
        """.trimIndent())
        Files.writeString(root.resolve("items/T-001.yaml"), "id: T-001\nstatus: new\n")
        cases = root.resolve("scenarios.yaml")
        Files.writeString(cases, """
            cases:
              - id: finish
                action: finish-task
                target: T-001
                expect:
                  - lifecycle: task-lifecycle
                    entity: T-001
                    before: [task-new]
                    after: [task-done]
                    verdict: MATCHED
        """.trimIndent())
    }

    @AfterTest
    fun cleanup() {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { file -> Files.deleteIfExists(file) } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun run(): Map<String, Any?> = YamlIO.parseToRaw(
        runCli(arrayOf("behavior-scenarios", root.toString(), cases.toString()))
    ) as Map<String, Any?>

    @Test
    fun `具体例の合否と未実行の入口をYAMLで示し業務データは不変`() {
        val result = run()
        assertEquals("scenario-checked", result["status"])
        assertEquals("not-proven", result["proofStatus"])
        val summary = result["summary"] as Map<String, Any?>
        assertEquals(1, summary["passed"])
        assertEquals(1, summary["unexercisedEdges"])
        val casesResult = result["cases"] as List<Map<String, Any?>>
        assertEquals("passed", casesResult.single()["status"])
        val coverage = result["coverage"] as List<Map<String, Any?>>
        assertTrue(coverage.any { (it["exercisedBy"] as List<*>).isEmpty() })
        assertEquals("id: T-001\nstatus: new\n", Files.readString(root.resolve("items/T-001.yaml")))
        assertTrue(Files.exists(cases))
        assertTrue(!Files.exists(root.resolve("_runs")))
    }

    @Test
    fun `未宣言の遷移は期待値を満たさず失敗を返す`() {
        Files.writeString(cases, Files.readString(cases).replace("verdict: MATCHED", "verdict: UNDECLARED"))
        val result = run()
        assertEquals("failed", result["status"])
        val casesResult = result["cases"] as List<Map<String, Any?>>
        assertTrue((casesResult.single()["findings"] as List<*>).any { "verdict" in it.toString() })
    }
}
