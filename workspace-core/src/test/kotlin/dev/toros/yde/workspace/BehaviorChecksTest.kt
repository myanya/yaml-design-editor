package dev.toros.yde.workspace

import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BehaviorChecksTest {

    private val common = """
        id: common
        kind: library
        definitions:
          progress:
            type: enum
            values:
              - { value: new, label: 新規 }
              - { value: done, label: 完了 }
    """.trimIndent()

    private val task = """
        id: task
        kind: document
        match: "tasks/*.yaml"
        root: { type: object }
        actions:
          - id: make-task
            do: [ { create: { schema: task, params: { status: new } } } ]
          - id: finish-task
            target: { entity: task }
            do: [ { set: { of: self, path: status, value: done } } ]
    """.trimIndent()

    private fun inspect(behavior: String): BehaviorChecks.Catalog {
        val sources = listOf(
            DocumentId("_schemas/common.yaml") to common,
            DocumentId("_schemas/task.yaml") to task,
            DocumentId("_schemas/task-behavior.yaml") to behavior.trimIndent(),
        )
        val parsed = WorkspaceReader.parseSchemas(sources)
        assertTrue(parsed.errors.isEmpty(), "パースエラー: ${parsed.errors}")
        return BehaviorChecks.compile(parsed.schemas)
    }

    private val validBehavior = """
        id: task-behavior
        kind: behavior
        states:
          - source: { entity: task, path: status, enum: common#progress }
            idPrefix: task-
        lifecycles:
          - id: task-lifecycle
            entity: task
            states: [task-new, task-done]
            entry: [ { to: task-new, by: { action: make-task } } ]
            transitions: [ { from: task-new, to: task-done, by: { action: finish-task } } ]
            termination: { terminal: [task-done] }
    """

    @Test
    fun `enumの全値が既存の述語に展開されLifecycleが静的に成立する`() {
        val result = inspect(validBehavior)
        assertTrue(result.errors.isEmpty(), "${result.errors}")
        assertEquals(listOf("task-new", "task-done"), result.states.map { it.id })
        assertEquals(listOf("new", "done"), result.states.map { it.enumValue })
        assertEquals("新規", result.states.first().label)
        assertEquals("task", result.states.first().predicate.spec.start.entities.single())
        assertEquals(1, result.lifecycles.single().entry.size)
    }

    @Test
    fun `遷移を起こすActionが無い場合は不足した業務仕様として指摘する`() {
        val result = inspect(validBehavior.replace("action: finish-task", "action: missing-task"))
        assertTrue(result.errors.any { "Action「missing-task」が見つかりません" in it }, "${result.errors}")
    }

    @Test
    fun `enumの状態の抜けは網羅性の不足として指摘する`() {
        val result = inspect(validBehavior.replace("states: [task-new, task-done]", "states: [task-new]"))
        assertTrue(result.errors.any { "網羅されていません: task-done" in it }, "${result.errors}")
    }

    @Test
    fun `生成入口と到達可能性と終端を点検する`() {
        val behavior = validBehavior
            .replace("to: task-new, by: { action: make-task }", "to: task-done, by: { action: make-task }")
            .replace("terminal: [task-done]", "terminal: [task-new]")
        val issues = inspect(behavior).errors
        assertTrue(issues.any { "到達できません" in it }, "$issues")
        assertTrue(issues.any { "終端 State「task-new」から" in it }, "$issues")
    }

    @Test
    fun `生成入口のないLifecycleは宣言として受け付けない`() {
        assertFailsWith<IllegalArgumentException> {
            SchemaDocuments.parse(
                validBehavior.trimIndent().replace(
                    "entry: [ { to: task-new, by: { action: make-task } } ]",
                    "entry: []",
                ),
                "task-behavior", "task-behavior.yaml", null,
            )
        }
    }

    @Test
    fun `終了しないLifecycleはopenEndedを明示して許容できる`() {
        val result = inspect(validBehavior.replace("termination: { terminal: [task-done] }", "termination: { openEnded: true }"))
        assertTrue(result.errors.isEmpty(), "${result.errors}")
    }

    @Test
    fun `通常の名前付きpredicateも既存ConstraintRuleとしてパースする`() {
        val behavior = """
            id: custom-behavior
            kind: behavior
            states:
              - id: task-approved
                entity: task
                label: 承認済み
                predicate:
                  match: [ { node: { entity: task, bind: t } } ]
                  where: [ { of: t, path: approved, equals: true } ]
                  expect: exists
        """
        val catalog = inspect(behavior)
        assertTrue(catalog.errors.isEmpty(), "${catalog.errors}")
        assertEquals("task-approved", catalog.states.single().id)
        assertEquals(null, catalog.states.single().enumValue)
    }

    @Test
    fun `冒険者ギルドの依頼を元データを書き換えずに検証できる`() {
        val fixtures = System.getProperty("fixtures.root") ?: error("fixtures.root が未設定")
        val schemaDir = Path.of(fixtures).parent.resolve("labs/adventurers-guild/.yde/schemas")
        val sources = Files.list(schemaDir).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".yaml") }
                .sorted()
                .map { path -> DocumentId(".yde/schemas/${path.fileName}") to Files.readString(path) }
                .toList()
        }
        val parsed = WorkspaceReader.parseSchemas(sources)
        assertTrue(parsed.errors.isEmpty(), "${parsed.errors}")
        val catalog = BehaviorChecks.compile(parsed.schemas)
        assertTrue(catalog.errors.isEmpty(), "${catalog.errors}")
        val quest = catalog.lifecycles.single { it.id == "quest-lifecycle" }
        assertEquals(9, quest.states.size)
        assertEquals(14, quest.transitions.size)
    }
}
