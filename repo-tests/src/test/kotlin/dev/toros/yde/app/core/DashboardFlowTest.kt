package dev.toros.yde.app.core

import dev.toros.yde.cli.runCli
import dev.toros.yde.workspace.ActionPlanner
import dev.toros.yde.workspace.ActionRequest
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/**
 * ダッシュボードの流れ（flow）: 段の件数・工程ごとの可否・対象 1 件の位置・移り先の予告を、CLI・read_view・窓口で同じに返す
 * （architecture/dashboards.md「業務の流れ」）。
 */
class DashboardFlowTest {
    private lateinit var dir: Path
    private val date = LocalDate.parse("2026-11-08")
    private fun write(path: String, text: String) {
        val target = dir.resolve(path)
        Files.createDirectories(target.parent)
        Files.writeString(target, text.trimIndent())
    }

    @BeforeTest
    fun prepare() {
        dir = createTempDirectory("yde-dashboard-flow-")
        write(".yde/workspace.yaml", "ledger: [tasks]")
        write(".yde/schemas/task.yaml", """
            id: task
            match: "tasks/*.yaml"
            filePattern: "tasks/{id}.yaml"
            entity: {type: task, idPattern: 'T-*', defines: true}
            root:
              type: object
              fields:
                - {key: id, type: id, required: true}
                - {key: title, type: string, role: title}
                - key: status
                  type: enum
                  values: [{value: open, label: 未着手}, {value: doing, label: 対応中}, {value: done, label: 完了}, {value: dropped, label: 中止}]
                - {key: owner, type: string}
                - key: log
                  type: array
                  item:
                    type: object
                    fields:
                      - {key: date, type: date}
                      - {key: action, type: string}
                      - {key: from, type: enum, values: [{value: open, label: 未着手}, {value: doing, label: 対応中}, {value: done, label: 完了}, {value: dropped, label: 中止}]}
                      - {key: to, type: enum, values: [{value: open, label: 未着手}, {value: doing, label: 対応中}, {value: done, label: 完了}, {value: dropped, label: 中止}]}
                      - {key: note, type: string}
            actions:
              - id: register
                label: 登録する
                params: [{key: id, type: id, required: true}]
                do:
                  - create: {schema: task, params: {id: ${'$'}id, status: open}}
              - id: start
                label: 着手する
                target: {entity: task}
                require:
                  - match: [{node: {entity: task, bind: t}}]
                    where: [{of: t, path: status, equals: open}]
                    expect: exists
                    message: "{id} は未着手ではありません"
                  - match: [{node: {entity: task, bind: t}}]
                    where: [{of: t, path: owner, exists: true}]
                    expect: exists
                    message: "{id} には担当がいません"
                do:
                  - insert: {of: self, path: log, value: {date: '2026-11-08', action: start, from: open, to: doing}}
                  - set: {of: self, path: status, value: doing}
              - id: finish
                label: 完了する
                target: {entity: task}
                require:
                  - match: [{node: {entity: task, bind: t}}]
                    where: [{of: t, path: status, equals: doing}]
                    expect: exists
                do:
                  - set: {of: self, path: status, value: done}
              - id: skip
                label: 飛ばして完了する
                target: {entity: task}
                do:
                  - set: {of: self, path: status, value: done}
        """)
        write("tasks/T-1.yaml", "id: T-1\ntitle: 一\nstatus: open\nowner: 太郎")
        write("tasks/T-2.yaml", "id: T-2\ntitle: 二\nstatus: open")
        write("tasks/T-3.yaml", """
            id: T-3
            title: 三
            status: doing
            owner: 花子
            log:
              - {date: 2026-11-01, action: start, from: open, to: doing, note: 着手}
        """)
        write("tasks/T-4.yaml", "id: T-4\ntitle: 四\nstatus: done\nowner: 太郎")
        write(".yde/views/tasks.yaml", """
            id: tasks
            rows:
              match: [{node: {entity: task, bind: t}}]
            columns:
              - {label: 状態, path: status}
        """)
        fun section(id: String) = """
              - id: $id
                view: tasks
                filter:
                  match: [{node: {entity: task, bind: t}}]
                  where: [{of: t, path: status, equals: $id}]
                  expect: exists"""
        write(".yde/views/home.yaml", """
            id: home
            kind: dashboard
            flow:
              start: {action: register, to: open}
              steps: [open, doing, done]
              moves:
                - {from: open, action: start, to: doing}
                - {from: doing, action: finish, to: done}
              history: {path: log}
            sections:${section("open")}${section("doing")}${section("done")}
              - {id: all, view: tasks}
        """)
    }

    @AfterTest
    fun cleanup() { Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }

    private fun cli(vararg args: String): Map<*, *> = YamlIO.parseToRaw(runCli(arrayOf(*args))) as Map<*, *>

    @Test
    fun `段の件数と工程ごとの可否を理由つきで返しCLIとread_viewと窓口が一致する`() {
        val ws = Workspace.open(dir)
        val catalog = Views.load(dir, ws.schemas)
        assertTrue(catalog.errors.isEmpty(), catalog.errors.toString())
        val view = assertNotNull(catalog.dashboard("home"))
        val result = Dashboards.resolve(view, date, catalog, ws.index, ws::schemaFor, ws::treeOf)
        val flow = assertNotNull(Dashboards.flow(view, result, ws.mirror))
        assertEquals(listOf("2", "1", "1"), flow.steps.map { it.count })
        assertEquals(listOf(false, false, true), flow.steps.map { it.terminal })
        val start = flow.moves.first()
        assertEquals("着手する", start.label)
        assertEquals(listOf("T-1"), start.ready.map { it.id })
        assertEquals("T-2", start.blocked.single().row.id)
        assertTrue(start.blocked.single().reason.contains("担当"), start.blocked.single().reason)
        assertEquals(listOf("T-3"), flow.moves[1].ready.map { it.id })
        assertEquals("登録する", flow.startLabel)

        val report = cli("view", dir.toString(), "home", "--date", date.toString())
        assertEquals(Dashboards.report(view, result, flow = flow), report)
        val toolbox = dev.toros.yde.app.tools.Readouts(dev.toros.yde.app.tools.ToolContext(ws, dev.toros.yde.app.tools.WorkingCopy(ws)))
        assertEquals(report, YamlIO.parseToRaw(toolbox.viewText("home", date = date.toString())))
        // 流れの無いダッシュボードの出力は変えない
        assertFalse("flow" in Dashboards.report(view.copy(flow = null), result))
    }

    @Test
    fun `対象1件のいる段と次にできる工程と履歴を返し共有の入力では絞らない`() {
        val position = cli("view", dir.toString(), "home", "--entity", "T-3", "--date", date.toString())
        assertEquals(listOf(mapOf("id" to "doing", "label" to "doing")), position["steps"])
        val move = (position["moves"] as List<*>).single() as Map<*, *>
        assertEquals("finish", move["action"])
        assertEquals(true, move["enabled"])
        val history = (position["history"] as List<*>).single() as Map<*, *>
        assertEquals("start", history["action"])
        assertEquals("着手する", history["label"])
        assertEquals("未着手", history["from"])
        assertEquals("対応中", history["to"])
        assertEquals("着手", history["note"])
        // 前提を満たさない工程は無効で理由が付く
        val blocked = cli("view", dir.toString(), "home", "--entity", "T-2", "--date", date.toString())
        val start = (blocked["moves"] as List<*>).single() as Map<*, *>
        assertEquals(false, start["enabled"])
        assertTrue((start["reason"] as String).contains("担当"))
        // 終点の対象は次の工程が無い
        val done = cli("view", dir.toString(), "home", "--entity", "T-4", "--date", date.toString())
        assertEquals(emptyList<Any?>(), done["moves"])
        assertFailsWith<IllegalArgumentException> { runCli(arrayOf("view", dir.toString(), "home", "--entity", "T-9")) }
    }

    @Test
    fun `移り先の予告は計画を重ねた状態で段を求め宣言にない移り方を見分ける`() {
        val ws = Workspace.open(dir)
        val view = assertNotNull(Views.load(dir, ws.schemas).dashboard("home"))
        fun transition(action: String): dev.toros.yde.semantic.DashboardFlowTransition {
            val plan = ActionPlanner.plan(ws.mirror, ActionRequest(action, "T-1"), LocalDateTime.of(2026, 11, 8, 9, 0))
            val copy = dev.toros.yde.app.tools.WorkingCopy(ws)
            plan.changeSet.changes.forEach { copy.applyOps(it.file, it.ops) }
            return assertNotNull(Dashboards.transition(view, "T-1", action, date, ws::schemaFor,
                ws.index to ws::treeOf, copy.index() to { d -> copy.tree(d.value) }))
        }
        val start = transition("start")
        assertEquals(listOf("open"), start.before)
        assertEquals(listOf("doing"), start.after)
        assertTrue(start.moved)
        assertFalse(start.undeclared)
        val skip = transition("skip")
        assertEquals(listOf("done"), skip.after)
        // 未着手から「飛ばして完了する」工程は流れに無いので、段を変える操作は宣言外
        assertTrue(skip.declared.isEmpty())
        assertTrue(skip.undeclared)
    }

    @Test
    fun `工程と入口の操作は受け手の種類まで点検する`() {
        val text = Files.readString(dir.resolve(".yde/views/home.yaml"))
        write(".yde/views/home.yaml", text.replace("action: finish", "action: missing").replace("action: register", "action: start"))
        val ws = Workspace.open(dir)
        val errors = Views.load(dir, ws.schemas).errors
        assertTrue(errors.any { "flow.moves" in it && "missing" in it }, errors.toString())
        assertTrue(errors.any { "flow.start" in it && "start" in it }, errors.toString())
    }
}
