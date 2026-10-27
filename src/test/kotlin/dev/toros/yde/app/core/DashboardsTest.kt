package dev.toros.yde.app.core

import dev.toros.yde.app.cli.runCli
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.UiSpace
import dev.toros.yde.desktop.ui.FormCtx
import dev.toros.yde.desktop.ui.actionPanelContext
import dev.toros.yde.desktop.ui.slotAt
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.parseYPath
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class DashboardsTest {
    private lateinit var dir: Path
    private val date = LocalDate.parse("2026-10-25")
    private fun write(path: String, text: String) {
        val target = dir.resolve(path)
        Files.createDirectories(target.parent)
        Files.writeString(target, text.trimIndent())
    }

    @BeforeTest
    fun prepare() {
        dir = createTempDirectory("yde-dashboard-")
        write(".yde/workspace.yaml", "ledger: [data]")
        write(".yde/schemas/list.yaml", """
            id: list
            match: data/list.yaml
            entity: {type: task, idPattern: 'T-*', defines: true}
            root:
              type: object
              fields:
                - key: tasks
                  type: array
                  item:
                    type: object
                    fields:
                      - {key: id, type: id}
                      - {key: title, type: string, role: title}
        """)
        write(".yde/schemas/detail.yaml", """
            id: detail
            match: data/details/*.yaml
            entity: {type: task, idPattern: 'T-*', defines: false}
            root:
              type: object
              fields:
                - {key: id, type: id}
                - {key: status, type: enum, values: [OPEN, CLOSED]}
                - {key: due, type: date}
            actions:
              - id: finish
                label: 完了
                target: {entity: task}
                do:
                  - set: {of: self, in: {schemaId: detail}, path: status, value: CLOSED}
        """)
        write("data/list.yaml", """
            tasks:
              - {id: T-A, title: 作業A}
              - {id: T-B, title: 作業B}
              - {id: T-C, title: 作業C}
        """)
        write("data/details/T-A.yaml", "id: T-A\nstatus: OPEN\ndue: 2026-10-24")
        write("data/details/T-B.yaml", "id: T-B\nstatus: OPEN\ndue: 2026-10-25")
        write(".yde/views/grid.yaml", """
            id: grid
            rows: {idPattern: 'T-*'}
            columns:
              - {label: 状態, schemaId: detail, path: status}
              - {label: 期限, schemaId: detail, path: due}
        """)
        write(".yde/views/home.yaml", """
            id: home
            kind: dashboard
            sections:
              - id: overdue
                label: 期限超過
                view: grid
                filter:
                  match: [{node: {entity: task, bind: t}}]
                  where: [{of: t, path: status, equals: OPEN}]
                  check:
                    - lt: [{of: t, in: {schemaId: detail}, path: due}, ${'$'}today]
                      type: date
              - id: missing
                label: 個票未作成
                view: grid
                filter:
                  match: [{node: {entity: task, bind: t}}]
                  check: [{eq: [{occurrenceCount: {of: t, schema: detail}}, 0]}]
              - {id: all, label: 全件, view: grid}
        """)
    }

    @AfterTest
    fun cleanup() { Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }

    private fun resolve(state: AppState, at: LocalDate = date): dev.toros.yde.semantic.DashboardResolution {
        val catalog = Views.load(dir, state.schemas, state.unsavedFirstTree())
        assertTrue(catalog.errors.isEmpty(), catalog.errors.toString())
        return Dashboards.resolve(assertNotNull(catalog.dashboard("home")), at, catalog,
            state.overlayIndex(), state::schemaFor, state.unsavedFirstTree())
    }

    @Test
    fun `エージェントも指定日の同じ報告を読みステージングだけを重ねる`() {
        val ws = Workspace.open(dir)
        val staging = dev.toros.yde.agent.Staging(ws)
        val activity = dev.toros.yde.agent.ActivityDef("inspect", "確認", agentId = "test", instruction = "確認",
            source = dir.resolve(".yde/activities/inspect.yaml"))
        val toolbox = dev.toros.yde.agent.AgentToolbox(ws, staging, activity, setOf(dev.toros.yde.agent.ToolGroup.EXPLORE))
        val cli = YamlIO.parseToRaw(runCli(arrayOf("view", dir.toString(), "home", "--date", date.toString())))
        assertEquals(cli, YamlIO.parseToRaw(toolbox.viewText("home", date = date.toString())))
        staging.applyOps("data/details/T-A.yaml", listOf(dev.toros.yde.yaml.patch.PatchOp.Set(parseYPath("status"), dev.toros.yde.yaml.model.YNode.Scalar("CLOSED"))))
        val report = YamlIO.parseToRaw(toolbox.viewText("home", date = date.toString())) as Map<*, *>
        val section = (report["sections"] as List<*>).first() as Map<*, *>
        assertEquals(0, section["count"])
        assertTrue(Files.readString(dir.resolve("data/details/T-A.yaml")).contains("OPEN"))
    }

    @Test
    fun `参照したビューの改名は追随し削除と条件内の言及を検出する`() {
        val session = Workspace.open(dir).mirror
        assertFailsWith<IllegalStateException> {
            session.planRefactoring(dev.toros.yde.semantic.RefactoringRequest.DeleteEntity("grid"))
        }
        val rename = session.planRefactoring(dev.toros.yde.semantic.RefactoringRequest.RenameEntity("grid", "renamed"))
        assertTrue(session.applyRefactoring(rename).ok)
        val definition = YamlIO.parseToRaw(dev.toros.yde.yaml.DefaultYamlCodec.dump(
            assertNotNull(session.copyDocument(DocumentId(".yde/views/home.yaml"))))) as Map<*, *>
        assertTrue((definition["sections"] as List<*>).all { (it as Map<*, *>)["view"] == "renamed" })
        assertTrue(session.undo().ok)
        val fields = session.planRefactoring(dev.toros.yde.semantic.RefactoringRequest.RenameField("detail", "due", "deadline"))
        assertTrue(fields.warnings.any { "filter" in it && "due" in it }, fields.warnings.toString())
    }

    @Test
    fun `件数と一覧と原本座標を共有し基準日と個票未作成を区別する`() {
        val state = AppState().also { it.openWorkspace(dir) }
        val result = resolve(state)
        assertEquals(listOf("1", "1", "3"), result.sections.map { it.count })
        assertEquals("T-A", result.sections[0].rows.single().id)
        assertEquals("T-C", result.sections[1].rows.single().id)
        assertEquals(DocumentId("data/details/T-A.yaml"), result.sections[0].rows.single().cells[0].document)
        assertEquals("2", resolve(state, date.plusDays(1)).sections[0].count)
        val cli = YamlIO.parseToRaw(runCli(arrayOf("view", dir.toString(), "home", "--date", date.toString())))
        val catalog = Views.load(dir, state.schemas)
        assertEquals(Dashboards.report(assertNotNull(catalog.dashboard("home")), result), cli)
        val definition = state.ensureDoc(DocumentId(".yde/views/home.yaml"))
        val schema = assertNotNull(definition.schema)
        assertTrue(dev.toros.yde.workspace.DerivedStructures(schema.tree).validate(assertNotNull(definition.root)).isEmpty())
    }

    @Test
    fun `未保存の編集で一覧から外れても開いた業務詳細と操作対象を維持する`() {
        val state = AppState().also { it.openWorkspace(dir) }
        state.openDashboard("home")
        state.memory.dashboardDate["home"] = date
        val row = resolve(state).sections[0].rows.single()
        state.memory.dashboardRow["home" to "overdue"] = row.key
        state.openDashboardEntity(row.id)
        assertEquals("T-A", actionPanelContext(state, null, emptyList()).targetId)
        val doc = state.ensureDoc(DocumentId("data/details/T-A.yaml"))
        FormCtx(state, doc, emptyList()).setScalar(assertNotNull(slotAt(assertNotNull(doc.root), parseYPath("status"))), "CLOSED", FieldType.STRING)
        assertEquals("0", resolve(state).sections[0].count)
        assertEquals("T-A", actionPanelContext(state, doc, emptyList()).targetId)
        assertEquals("T-A", state.dashboardEntityId)
        assertNull(resolve(state).sections[0].selected(row.key))
        state.undo()
        assertEquals("1", resolve(state).sections[0].count)
        assertEquals("T-A", actionPanelContext(state, null, emptyList()).targetId)
    }

    @Test
    fun `比較できない日付はゼロ件と区別し対象と理由を開示する`() {
        write("data/details/T-A.yaml", "id: T-A\nstatus: OPEN\ndue: invalid")
        val state = AppState().also { it.openWorkspace(dir) }
        val section = resolve(state).sections[0]
        assertEquals("0", section.count)
        assertEquals("T-A", section.pending.single().row.id)
        assertTrue(section.pending.single().reason.isNotBlank())
        assertNull(section.error)
    }

    @Test
    fun `閲覧専用でも既存アクションで変更して保存とUndoと件数の更新ができる`() {
        val state = AppState().also { it.openWorkspace(dir) }
        state.openDashboard("home")
        state.openDashboardEntity("T-A")
        state.readOnlyMode = true
        state.startAction("finish", "T-A")
        state.planAction()
        assertNotNull(state.actionWork?.plan, state.actionWork?.error)
        assertTrue(state.applyAction().isSuccess)
        assertEquals("0", resolve(state).sections[0].count)
        assertTrue(Files.readString(dir.resolve("data/details/T-A.yaml")).contains("CLOSED"))
        state.undo()
        assertEquals("1", resolve(state).sections[0].count)
        assertEquals(0, state.dirtyDocCount())
        assertEquals(UiSpace.DASHBOARD, state.uiSpace)
        assertEquals("T-A", state.dashboardEntityId)
        assertEquals("home", state.dashboardId)
    }

    @Test
    fun `定義の未保存変更も反映し画面移動と戻るで基準日と区画を維持する`() {
        val state = AppState().also { it.openWorkspace(dir) }
        state.openDashboard("home")
        state.memory.dashboardDate["home"] = date
        state.memory.dashboardSection["home"] = "missing"
        state.openViewGrid("grid")
        state.goBack()
        assertEquals(UiSpace.DASHBOARD, state.uiSpace)
        assertEquals("home", state.dashboardId)
        assertEquals(date, state.memory.dashboardDate["home"])
        assertEquals("missing", state.memory.dashboardSection["home"])
        val doc = state.ensureDoc(DocumentId(".yde/views/home.yaml"))
        FormCtx(state, doc, emptyList()).setScalar(assertNotNull(slotAt(assertNotNull(doc.root), parseYPath("sections[0].label"))), "遅延", FieldType.STRING)
        assertEquals("遅延", resolve(state).sections[0].definition.label)
    }

    @Test
    fun `定義があれば業務から始まり既定指定と定義なしを区別する`() {
        val state = AppState().also { it.openWorkspace(dir) }
        assertEquals(UiSpace.DASHBOARD, state.uiSpace)
        assertEquals("home", state.dashboardId)
        write(".yde/views/second.yaml", "id: second\nkind: dashboard\ndefault: true\nsections: [{id: all, view: grid}]")
        state.openWorkspace(dir)
        assertEquals("second", state.dashboardId)
        Files.delete(dir.resolve(".yde/views/second.yaml"))
        Files.delete(dir.resolve(".yde/views/home.yaml"))
        state.openWorkspace(dir)
        assertEquals(UiSpace.EDIT, state.uiSpace)
        assertNull(state.dashboardId)
        assertNull(state.dashboardEntityId)
    }

    @Test
    fun `参照先と原本から戻っても業務の場所と一覧の覚えが保たれる`() {
        val state = AppState().also { it.openWorkspace(dir) }
        state.memory.dashboardSection["home"] = "all"
        val position = dev.toros.yde.desktop.TableScrollPosition().also { it.index = 1; it.horizontal = 150 }
        state.memory.dashboardScroll["home" to "all"] = position
        state.openDashboardEntity("T-A")
        state.navigateToId("T-B")
        assertEquals(UiSpace.DASHBOARD, state.uiSpace)
        assertEquals("T-B", state.dashboardEntityId)
        assertNull(state.openDoc)
        state.goBack()
        assertEquals("T-A", state.dashboardEntityId)
        state.goForward()
        assertEquals("T-B", state.dashboardEntityId)
        state.navigateToOccurrence(state.overlayIndex().occurrences("T-B").first())
        assertEquals(UiSpace.EDIT, state.uiSpace)
        state.goBack()
        assertEquals(UiSpace.DASHBOARD, state.uiSpace)
        assertEquals("T-B", state.dashboardEntityId)
        state.showSpace(UiSpace.LEDGER)
        state.openDashboard()
        assertEquals("T-B", state.dashboardEntityId)
        state.showDashboardOverview()
        assertNull(state.dashboardEntityId)
        assertNull(actionPanelContext(state, state.openDoc, emptyList()).targetId)
        assertEquals("all", state.memory.dashboardSection["home"])
        assertEquals(150, state.memory.dashboardScroll["home" to "all"]?.horizontal)
    }

    @Test
    fun `業務フォームの保存と別画面からのUndoで元の対象へ戻る`() {
        val state = AppState().also { it.openWorkspace(dir) }
        state.openDashboardEntity("T-A")
        val doc = state.ensureDoc(DocumentId("data/details/T-A.yaml"))
        FormCtx(state, doc, emptyList()).setScalar(assertNotNull(slotAt(assertNotNull(doc.root), parseYPath("status"))), "CLOSED", FieldType.STRING)
        state.saveCurrent()
        assertTrue(Files.readString(dir.resolve("data/details/T-A.yaml")).contains("CLOSED"))
        state.openFile(DocumentId("data/details/T-B.yaml"))
        state.undoFromUi()
        assertEquals(UiSpace.DASHBOARD, state.uiSpace)
        assertEquals("T-A", state.dashboardEntityId)
        assertEquals("1", resolve(state).sections[0].count)
    }

    @Test
    fun `参照先がない場合は移動せず削除された対象に別の対象を割り当てない`() {
        val state = AppState().also { it.openWorkspace(dir) }
        state.openDashboardEntity("T-A")
        state.openCrossView("missing")
        assertEquals("T-A", state.dashboardEntityId)
        assertTrue(state.status.contains("見つかりません"))
        state.dashboardEntityId = "deleted"
        assertNull(actionPanelContext(state, null, emptyList()).targetId)
        state.reload()
        assertEquals("deleted", state.dashboardEntityId)
        assertEquals(UiSpace.DASHBOARD, state.uiSpace)
    }
}
