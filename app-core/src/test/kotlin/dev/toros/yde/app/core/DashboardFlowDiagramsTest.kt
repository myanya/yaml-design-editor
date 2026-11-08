package dev.toros.yde.app.core

import dev.toros.yde.app.view.flow.FlowConnectionState
import dev.toros.yde.app.view.flow.FlowElementKind
import dev.toros.yde.semantic.*
import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.*

/** 業務の流れの図（DashboardFlowDiagrams）: フロービューと同じ表示モデルにし、押した要素から段・工程・入口を引く。 */
class DashboardFlowDiagramsTest {
    private val flow = DashboardFlow(
        steps = listOf("open", "doing", "done"),
        moves = listOf(DashboardMove("open", "start", "doing"), DashboardMove("open", "note"), DashboardMove("doing", "finish", "done")),
        start = DashboardFlowStart("register", "open"),
    )
    private val view = VirtualDashboardDef("home", "home", null, DocumentId(".yde/views/home.yaml"),
        listOf(DashboardSection("open", "未着手", "grid"), DashboardSection("doing", "対応中", "grid"), DashboardSection("done", "完了", "grid")),
        flow = flow)

    private fun row(id: String) = VirtualRow(id, null, listOf(ScannedId(id, DocumentId("t/$id.yaml"), emptyList(), null, null, "t/$id.yaml")), emptyList(), null, null)

    private val result = DashboardFlowResult(flow,
        listOf(DashboardFlowStepResult(view.sections[0], "2", false), DashboardFlowStepResult(view.sections[1], "1", false),
            DashboardFlowStepResult(view.sections[2], "5", true)),
        listOf(
            DashboardFlowMoveResult(flow.moves[0], "着手する", listOf(row("T-1")), listOf(DashboardFlowBlocked(row("T-2"), "担当がいません"))),
            DashboardFlowMoveResult(flow.moves[1], "メモを書く", listOf(row("T-1"), row("T-2")), emptyList()),
            DashboardFlowMoveResult(flow.moves[2], "完了する", listOf(row("T-3")), emptyList()),
        ), startLabel = "登録する")

    @Test
    fun `段と工程と入口をフロービューの表示モデルにし押した要素から行き先を引く`() {
        val d = DashboardFlowDiagrams.overview(view, result)
        assertEquals(listOf("入口", "未着手\n2 件", "対応中\n1 件", "完了\n5 件（終点）"), d.model.nodes.map { it.label })
        assertEquals(listOf("box", "rounded", "rounded", "rounded"), d.model.nodes.map { it.shape })
        // 入口の線には操作の名前（工程と同じく線が操作を表す）
        assertEquals(listOf("登録する", "着手する 1/2（できない 1）", "メモを書く 2/2", "完了する 1/1"), d.model.edges.map { it.label })
        assertTrue(d.model.edges.all { it.from.state == FlowConnectionState.RESOLVED && it.to.state == FlowConnectionState.RESOLVED })
        // 段に留まる工程はその段への自己ループ
        val note = d.model.edges[2]
        assertEquals(note.from.target, note.to.target)
        assertEquals(DashboardFlowDiagram.Target.Step("doing"), d.target(FlowElementKind.NODE, assertNotNull(d.stepPath("doing"))))
        assertEquals(DashboardFlowDiagram.Target.Move(flow.moves[2]), d.target(FlowElementKind.EDGE, assertNotNull(d.movePath(flow.moves[2]))))
        assertEquals(DashboardFlowDiagram.Target.Start, d.target(FlowElementKind.NODE, assertNotNull(d.start)))
        assertEquals(DashboardFlowDiagram.Target.Start, d.target(FlowElementKind.EDGE, d.model.edges[0].output))
        // 図の要素は定義の場所を来歴に持つ（原本の編集はしない）
        assertTrue(d.model.nodes.none { it.detail.editable })
        assertEquals(listOf("flow", "moves", 0), d.model.edges[1].detail.origins.single().path)
    }

    @Test
    fun `位置の図はいまの段にいまここを付け工程の可否を名前に添え入口を出さない`() {
        val position = DashboardFlowPosition("T-2", listOf("open"), moves = listOf(
            DashboardFlowPositionMove(flow.moves[0], "着手する", DashboardMoveCheck(false, "担当がいません")),
            DashboardFlowPositionMove(flow.moves[1], "メモを書く", DashboardMoveCheck(true)),
        ))
        val d = DashboardFlowDiagrams.position(view, position) { if (it == "finish") "完了する" else null }
        assertNull(d.start)
        assertEquals(listOf("未着手\nいまここ", "対応中", "完了"), d.model.nodes.map { it.label })
        assertEquals(listOf("着手する（できない）", "メモを書く", "完了する"), d.model.edges.map { it.label })
    }
}
