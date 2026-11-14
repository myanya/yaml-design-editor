package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.TransitionCause
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.patch.PatchOp
import dev.toros.yde.yaml.model.scalarText
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * State の詳細化（ADR-0030）: 粗い Lifecycle の 1 つの State の中の流れを、同じ ID で別に書く。
 * 粗い流れはそれだけで点検し、操作・業務の流れ・シナリオは、親を子へ展開した流れを使う。
 */
class LifecycleRefinementTest {
    private val now = LocalDateTime.of(2026, 11, 14, 10, 0)

    private fun files(behavior: String = BEHAVIOR): Map<String, String> = mapOf(
        "_schemas/common.yaml" to """
            id: common
            kind: library
            definitions:
              progress:
                type: enum
                values:
                  - { value: new, label: 未着手 }
                  - { value: doing, label: 対応中 }
                  - { value: paused, label: 中断 }
                  - { value: done, label: 完了 }
                  - { value: dropped, label: 中止 }
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
                - { key: hot, type: boolean }
            actions:
              - id: create-task
                lifecycle: task-lifecycle
                do: [ { create: { schema: task, id: { prefix: "T-", digits: 3 }, params: {} } } ]
              - { id: start-task, target: { entity: task }, lifecycle: task-lifecycle }
              - { id: pause-task, target: { entity: task }, lifecycle: task-lifecycle }
              - { id: resume-task, target: { entity: task }, lifecycle: task-lifecycle }
              - { id: finish-task, target: { entity: task }, lifecycle: task-lifecycle }
              - { id: drop-task, target: { entity: task }, lifecycle: task-lifecycle }
        """.trimIndent(),
        "_schemas/task-behavior.yaml" to behavior,
        "tasks/T-001.yaml" to "id: T-001\nstatus: new\n",
        "tasks/T-002.yaml" to "id: T-002\nstatus: done\n",
        "tasks/T-003.yaml" to "id: T-003\nstatus: paused\n",
    )

    private fun session(behavior: String = BEHAVIOR) = WorkspaceSession(InMemoryDocumentStore(files(behavior)))

    private fun errorsOf(behavior: String) = session(behavior).schemaErrors

    private fun plan(s: WorkspaceSession, name: String, target: String?) = ActionPlanner.plan(s, ActionRequest(name, target, emptyMap()), now)

    @Test
    fun `詳細化した State は子へ展開され 親の位置に中の流れが入る`() {
        val s = session()
        assertEquals(emptyList(), s.schemaErrors)
        val catalog = BehaviorChecks.compile(s.schemas)
        val lifecycle = catalog.lifecycles.single()
        assertEquals(listOf("task-new", "task-doing", "task-paused", "task-done", "task-dropped"), lifecycle.states)
        assertEquals(listOf("task-new"), lifecycle.entry.map { it.to }, "親へ入る登録は、詳細化の入口の子へ")
        assertEquals(listOf("task-done", "task-dropped"), lifecycle.terminal)
        fun from(action: String) = lifecycle.transitions.filter { it.by == TransitionCause.Action(action) }.map { it.from to it.to }
        assertEquals(listOf("task-doing" to "task-done"), from("finish-task"), "出口で狭めた原因は、出られる子からだけ")
        assertEquals(
            listOf("task-new" to "task-dropped", "task-doing" to "task-dropped", "task-paused" to "task-dropped"), from("drop-task"),
            "狭めていない原因は、どの子からも出られる",
        )
        assertEquals(listOf("task-new" to "task-doing"), from("start-task"), "中の遷移はそのまま")
        assertEquals(listOf("task-open"), catalog.children.keys.toList())
        // 親は「子のどれか」が真のとき真（値は子に保存する）
        val open = catalog.states.single { it.id == "task-open" }
        assertEquals("未完了", open.label)
        val readings = BehaviorRuntime.evaluate(s, "T-001").associate { it.stateId to it.truth }
        assertEquals(StateTruth.TRUE, readings["task-open"])
        assertEquals(StateTruth.TRUE, readings["task-new"])
        assertEquals(StateTruth.FALSE, BehaviorRuntime.evaluate(s, "T-002").single { it.stateId == "task-open" }.truth)
    }

    @Test
    fun `操作は展開した流れで動き 断るときは子がそろった親の名前でまとめる`() {
        val s = session()
        // 中止は未完了のどの子からでも
        val dropped = plan(s, "drop-task", "T-003").changeSet.changes.single().ops.first() as PatchOp.Set
        assertEquals("dropped", scalarText(dropped.value))
        // 完了は対応中からだけ
        val e1 = assertFailsWith<ActionFailure> { plan(s, "finish-task", "T-001") }
        assertEquals("「T-001」は「未着手」なので、「finish-task」はできません（できるのは「対応中」のとき）", e1.message)
        // 中止できるのは未完了の子の全部 → 親の名前でまとめる
        val e2 = assertFailsWith<ActionFailure> { plan(s, "drop-task", "T-002") }
        assertEquals("「T-002」は「完了」なので、「drop-task」はできません（できるのは「未完了」のとき）", e2.message)
        // 入口は詳細化の入口の子
        val created = plan(s, "create-task", null).changeSet.changes.single { it.create }.content!!
        assertEquals("new", scalarText(dev.toros.yde.yaml.model.getAt(created, dev.toros.yde.yaml.model.parseYPath("status"))))
    }

    @Test
    fun `まとめは 葉が全部そろった親だけ`() {
        val catalog = BehaviorChecks.compile(session().schemas)
        assertEquals(listOf("task-open", "task-done"), catalog.collapse(listOf("task-new", "task-doing", "task-paused", "task-done")))
        assertEquals(listOf("task-new", "task-doing"), catalog.collapse(listOf("task-new", "task-doing")))
        assertEquals(listOf("task-new", "task-dropped"), catalog.leavesOf("task-new") + catalog.leavesOf("task-dropped"))
    }

    @Test
    fun `業務の流れの段は展開した葉の State`() {
        val s = session()
        val dashboard = "id: home\nkind: dashboard\nflow: { lifecycle: task-lifecycle, view: tasks }\nsections: []\n"
        val grid = "id: tasks\nrows:\n  match: [{node: {entity: task, bind: t}}]\ncolumns:\n  - {label: 状態, path: status}\n"
        val catalog = ViewDefinitions.load(
            listOf(DocumentId(".yde/views/home.yaml") to dashboard, DocumentId(".yde/views/tasks.yaml") to grid), s.schemas,
        )
        assertTrue(catalog.errors.isEmpty(), catalog.errors.toString())
        val flow = assertNotNull(catalog.dashboards.single().flow)
        assertEquals(listOf("task-new", "task-doing", "task-paused", "task-done", "task-dropped"), flow.steps)
        assertEquals("create-task", flow.start?.action)
    }

    @Test
    fun `詳細化の誤りは読み込みで分かる`() {
        fun assertError(fragment: String, behavior: String) {
            val errors = errorsOf(behavior)
            assertTrue(errors.any { fragment in it }, "「$fragment」が無い: $errors")
        }
        // 親へ入る原因に、詳細化の入口が無い
        assertError(
            "「task-open」へ入る遷移（by: {action: create-task}）の入口が、「task-open」の詳細化にありません",
            BEHAVIOR.replace("entry: [ { to: task-new, by: { action: create-task } } ]", "entry: [ { to: task-new, by: { action: start-task } } ]"),
        )
        // 出口の原因が、親から出る遷移に無い
        assertError(
            "「task-open」の詳細化の出口（by: {action: start-task}）は、「task-open」から出る遷移にありません",
            BEHAVIOR.replace("- { by: { action: finish-task }, from: [task-doing] }", "- { by: { action: start-task }, from: [task-doing] }"),
        )
        // 述語を書かない State に詳細化が無い
        assertError("詳細化（refinements の state: task-open）が要ります", BEHAVIOR.substringBefore("refinements:"))
        // enum から作った State は詳細化できない（親は述語を書かずに宣言する）
        assertError("predicate を書かずに宣言します", BEHAVIOR.replace("- state: task-open", "- state: task-done"))
        // 子を Lifecycle にも書かない（親だけを書く）
        assertError("State「task-new」は「task-open」の詳細化の子です", BEHAVIOR.replace("states: [task-open, task-done, task-dropped]", "states: [task-open, task-new, task-done, task-dropped]"))
        // 中の流れの行き止まり（中断から出る手が無い）
        assertError(
            "非終端 State「task-paused」には次の遷移が必要です",
            BEHAVIOR.replace("      - { from: task-paused, to: task-doing, by: { action: resume-task } }\n", "")
                .replace("- { by: { action: finish-task }, from: [task-doing] }", "- { by: { action: finish-task }, from: [task-doing] }\n      - { by: { action: drop-task }, from: [task-new, task-doing] }"),
        )
        // 子が同じ項目の enum から作った State でないと、親の述語を導けない
        assertError(
            "「子のどれか」の述語を導けません",
            BEHAVIOR.replace(
                "  - { id: task-open, entity: task, label: 未完了 }",
                "  - { id: task-open, entity: task, label: 未完了 }\n  - id: task-hot\n    entity: task\n" +
                    "    predicate: { match: [ { node: { entity: task, bind: self } } ], where: [ { of: self, path: hot, equals: true } ], expect: exists }",
            ).replace("states: [task-new, task-doing, task-paused]", "states: [task-new, task-doing, task-paused, task-hot]"),
        )
    }

    private companion object {
        val BEHAVIOR = """
            id: task-behavior
            kind: behavior
            states:
              - source: { entity: task, path: status, enum: common#progress }
                idPrefix: task-
              - { id: task-open, entity: task, label: 未完了 }
            lifecycles:
              - id: task-lifecycle
                entity: task
                states: [task-open, task-done, task-dropped]
                entry: [ { to: task-open, by: { action: create-task } } ]
                transitions:
                  - { from: task-open, to: task-done, by: { action: finish-task } }
                  - { from: task-open, to: task-dropped, by: { action: drop-task } }
                termination: { terminal: [task-done, task-dropped] }
            refinements:
              - state: task-open
                states: [task-new, task-doing, task-paused]
                entry: [ { to: task-new, by: { action: create-task } } ]
                transitions:
                  - { from: task-new, to: task-doing, by: { action: start-task } }
                  - { from: task-doing, to: task-paused, by: { action: pause-task } }
                  - { from: task-paused, to: task-doing, by: { action: resume-task } }
                exits:
                  - { by: { action: finish-task }, from: [task-doing] }
        """.trimIndent()
    }
}
