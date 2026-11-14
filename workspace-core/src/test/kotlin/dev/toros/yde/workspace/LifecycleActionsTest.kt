package dev.toros.yde.workspace

import dev.toros.yde.semantic.VirtualDashboardDef
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.yaml.patch.PatchOp
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Lifecycle を正にする（ADR-0029）: 操作の `lifecycle:` が、状態の前提・状態の値の書き込み・記録の行を Lifecycle の遷移から作り、
 * ダッシュボードの `flow: {lifecycle}` が、段・区画・工程・入口・履歴を Lifecycle から作る。
 */
class LifecycleActionsTest {
    private val now = LocalDateTime.of(2026, 11, 14, 10, 0)

    private fun files(extraActions: String = "", behavior: String = BEHAVIOR): MutableMap<String, String> = mutableMapOf(
        "_schemas/common.yaml" to """
            id: common
            kind: library
            definitions:
              progress:
                type: enum
                values: [ { value: new, label: 未着手 }, { value: doing, label: 対応中 }, { value: done, label: 完了 } ]
        """.trimIndent(),
        "_schemas/task.yaml" to """
            id: task
            kind: document
            match: "tasks/*.yaml"
            filePattern: "tasks/{id}.yaml"
            root:
              type: object
              entity: { type: task, defines: true }
              fields:
                - { key: id, type: id, required: true }
                - { key: status, type: common#progress, required: true, default: new }
                - key: log
                  type: array
                  default: []
                  item:
                    type: object
                    fields:
                      - { key: date, type: date }
                      - { key: action, type: string }
                      - { key: from, type: common#progress }
                      - { key: to, type: common#progress }
                      - { key: note, type: string }
            actions:
              - id: create-task
                lifecycle: task-lifecycle
                do: [ { create: { schema: task, id: { prefix: "T-", digits: 3 }, params: {} } } ]
              - id: start-task
                target: { entity: task }
                lifecycle: task-lifecycle
              - id: finish-task
                target: { entity: task }
                params: [ { key: date, type: date, required: true }, { key: memo, type: string } ]
                lifecycle: { id: task-lifecycle, history: { date: ${'$'}date, note: { ${'$'}copy: memo } } }
        """.trimIndent() + extraActions,
        "_schemas/task-behavior.yaml" to behavior,
        "tasks/T-001.yaml" to "id: T-001\nstatus: new\nlog: []\n",
        "tasks/T-002.yaml" to "id: T-002\nstatus: done\nlog: []\n",
    )

    private fun session(files: Map<String, String> = files()) = WorkspaceSession(InMemoryDocumentStore(files)).also {
        assertEquals(emptyList(), it.schemaErrors)
    }

    private fun plan(s: WorkspaceSession, name: String, target: String? = null, vararg params: Pair<String, Any?>) =
        ActionPlanner.plan(s, ActionRequest(name, target, params.toMap()), now)

    @Test
    fun `遷移の操作は いまの状態から行き先を決め 状態の値と記録の行を書く`() {
        val s = session()
        val plan = plan(s, "start-task", "T-001")
        val ops = plan.changeSet.changes.single().ops
        assertEquals(PatchOp.Set(parseYPath("status"), YNode.Scalar("doing")).path, (ops[0] as PatchOp.Set).path)
        assertEquals("doing", scalarText((ops[0] as PatchOp.Set).value))
        val row = (ops[1] as PatchOp.Insert).value
        assertEquals("2026-11-14", scalarText(getAt(row, parseYPath("date"))))
        assertEquals("start-task", scalarText(getAt(row, parseYPath("action"))))
        assertEquals("new", scalarText(getAt(row, parseYPath("from"))))
        assertEquals("doing", scalarText(getAt(row, parseYPath("to"))))
        assertTrue(plan.summary.any { "未着手 → 対応中" in it }, plan.summary.toString())
        // 操作の lifecycle.history は記録の行に足し、date も上書きできる
        val finished = session(files().also { it["tasks/T-001.yaml"] = "id: T-001\nstatus: doing\nlog: []\n" })
        val row2 = (plan(finished, "finish-task", "T-001", "date" to "2026-11-01", "memo" to "済").changeSet.changes.single().ops[1] as PatchOp.Insert).value
        assertEquals("2026-11-01", scalarText(getAt(row2, parseYPath("date"))))
        assertEquals("済", scalarText(getAt(row2, parseYPath("note"))))
        // 変更セットは適用器を通り、Lifecycle の照合も宣言どおり
        val applied = ChangeSetApplier.apply(s, plan.changeSet, requireValid = true, now = now)
        assertTrue(applied.ok, applied.results.toString())
        assertTrue(s.storedText(DocumentId("tasks/T-001.yaml"))!!.contains("status: doing"))
    }

    @Test
    fun `いまの状態からこの操作の遷移が無ければ できる状態を添えて止め メニューでも無効にする`() {
        val s = session()
        val e = assertFailsWith<ActionFailure> { plan(s, "start-task", "T-002") }
        assertEquals("「T-002」は「完了」なので、「start-task」はできません（できるのは「未着手」のとき）", e.message)
        val menu = ActionPlanner.availability(s, ActionRequest("start-task", "T-002"))
        assertFalse(menu.enabled)
        assertTrue(menu.reason!!.contains("できるのは「未着手」"), menu.reason)
        assertTrue(ActionPlanner.availability(s, ActionRequest("start-task", "T-001")).enabled)
    }

    @Test
    fun `入口の操作は 作った文書に初めの状態と記録の行を書く`() {
        val s = session(files().also { it["tasks/T-001.yaml"] = "id: T-001\nstatus: doing\nlog: []\n" })
        val plan = plan(s, "create-task")
        val created = plan.changeSet.changes.single { it.create }.content!!
        assertEquals("T-003", scalarText(getAt(created, parseYPath("id"))))
        assertEquals("new", scalarText(getAt(created, parseYPath("status"))))
        assertEquals("create-task", scalarText(getAt(created, parseYPath("log[0].action"))))
        assertEquals(null, getAt(created, parseYPath("log[0].from")), "入口の行に前の状態は無い")
    }

    @Test
    fun `Lifecycle を書いた操作の誤りは読み込みで分かる`() {
        fun errorsOf(actions: String, behavior: String = BEHAVIOR) =
            WorkspaceSession(InMemoryDocumentStore(files(actions, behavior))).schemaErrors
        // 遷移も入口も無い
        assertTrue(errorsOf("\n  - id: other\n    target: { entity: task }\n    lifecycle: task-lifecycle\n").any { "入口も遷移もありません" in it })
        // 見つからない Lifecycle
        assertTrue(errorsOf("\n  - id: other\n    target: { entity: task }\n    lifecycle: no-such\n").any { "Lifecycle「no-such」が見つかりません" in it })
        // 同じ State から行き先が 2 つ（分かれる遷移）
        val branched = BEHAVIOR.replace(
            "- { from: task-new, to: task-doing, by: { action: start-task } }",
            "- { from: task-new, to: task-doing, by: { action: start-task } }\n      - { from: task-new, to: task-done, by: { action: start-task } }",
        )
        assertTrue(errorsOf("", branched).any { "行き先が 2 つ以上あります" in it }, errorsOf("", branched).toString())
        // do を省けるのは lifecycle を書いた操作だけ
        assertTrue(errorsOf("\n  - id: nothing\n    target: { entity: task }\n").any { "do（手の並び）が必要です" in it })
    }

    @Test
    fun `業務の流れを Lifecycle から作ると 段と区画と工程と入口と履歴ができる`() {
        val s = session()
        val dashboard = """
            id: home
            kind: dashboard
            flow: { lifecycle: task-lifecycle, view: tasks, group: 状況 }
            sections:
              - { id: all, view: tasks }
        """.trimIndent()
        val grid = "id: tasks\nrows:\n  match: [{node: {entity: task, bind: t}}]\ncolumns:\n  - {label: 状態, path: status}\n"
        val catalog = ViewDefinitions.load(
            listOf(DocumentId(".yde/views/home.yaml") to dashboard, DocumentId(".yde/views/tasks.yaml") to grid), s.schemas,
        )
        assertTrue(catalog.errors.isEmpty(), catalog.errors.toString())
        val home: VirtualDashboardDef = catalog.dashboards.single()
        assertEquals(listOf("task-new", "task-doing", "task-done", "all"), home.sections.map { it.id })
        assertEquals(listOf("未着手", "対応中", "完了"), home.sections.take(3).map { it.label })
        assertEquals(listOf("状況", "状況", "状況"), home.sections.take(3).map { it.group })
        val flow = assertNotNull(home.flow)
        assertEquals(listOf("task-new", "task-doing", "task-done"), flow.steps)
        assertEquals(listOf("start-task", "finish-task"), flow.moves.map { it.action })
        assertEquals("create-task", flow.start?.action)
        assertEquals("log", flow.history?.path)
        // lifecycle を書いたら段や工程は書かない
        val mixed = dashboard.replace("flow: { lifecycle: task-lifecycle, view: tasks, group: 状況 }", "flow: { lifecycle: task-lifecycle, view: tasks, steps: [all] }")
        val errors = ViewDefinitions.load(listOf(DocumentId(".yde/views/home.yaml") to mixed, DocumentId(".yde/views/tasks.yaml") to grid), s.schemas).errors
        assertTrue(errors.any { "lifecycle を書いたときに使えるキー" in it }, errors.toString())
    }

    private companion object {
        val BEHAVIOR = """
            id: task-behavior
            kind: behavior
            states:
              - source: { entity: task, path: status, enum: common#progress }
                idPrefix: task-
            lifecycles:
              - id: task-lifecycle
                entity: task
                states: [task-new, task-doing, task-done]
                entry: [ { to: task-new, by: { action: create-task } } ]
                transitions:
                  - { from: task-new, to: task-doing, by: { action: start-task } }
                  - { from: task-doing, to: task-done, by: { action: finish-task } }
                termination: { terminal: [task-done] }
                history: { path: log }
        """.trimIndent()
    }
}
