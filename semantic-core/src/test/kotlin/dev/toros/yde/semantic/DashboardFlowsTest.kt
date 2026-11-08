package dev.toros.yde.semantic

import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.*

/** ダッシュボードの流れ（flow）の定義の点検と、移り先の予告の判定（DashboardFlows）。 */
class DashboardFlowsTest {
    private fun dashboard(flow: DashboardFlow) = VirtualDashboardDef(
        "home", "home", null, DocumentId(".yde/views/home.yaml"),
        listOf("open", "doing", "done", "dropped").map { DashboardSection(it, it, "grid") }, flow = flow,
    )

    private val flow = DashboardFlow(
        steps = listOf("open", "doing", "done", "dropped"),
        moves = listOf(
            DashboardMove("open", "start", "doing"),
            DashboardMove("open", "note"),
            DashboardMove("doing", "finish", "done"),
            DashboardMove("doing", "back", "open"),
            DashboardMove("open", "drop", "dropped"),
            DashboardMove("doing", "drop", "dropped"),
        ),
        start = DashboardFlowStart("register", "open"),
    )

    @Test
    fun `段と工程と入口が区画に合い入口から全ての段へ着けば誤りは無い`() {
        assertEquals(emptyList(), DashboardFlows.lint(dashboard(flow)))
        assertFalse(flow.terminal("open"))
        assertTrue(flow.terminal("done"))
        assertTrue(flow.movesFrom("open").single { it.action == "note" }.stays)
    }

    @Test
    fun `区画に無い段と宣言に無い移り先と入口から着かない段を誤りにする`() {
        val unknown = flow.copy(steps = flow.steps + "missing")
        assertTrue(DashboardFlows.lint(dashboard(unknown)).single().contains("missing"))
        val badMove = flow.copy(moves = flow.moves + DashboardMove("open", "skip", "nowhere"))
        assertTrue(DashboardFlows.lint(dashboard(badMove)).single().contains("nowhere"))
        val badStart = flow.copy(start = DashboardFlowStart("register", "nowhere"))
        assertTrue(DashboardFlows.lint(dashboard(badStart)).single().contains("flow.start.to"))
        // dropped へ入る工程を外すと、入口の段から着かない
        val unreachable = flow.copy(moves = flow.moves.filter { it.to != "dropped" })
        assertTrue(DashboardFlows.lint(dashboard(unreachable)).single().contains("dropped"))
    }

    @Test
    fun `移り先の予告は宣言した移り先と後の段を比べ留まる工程は前の段を移り先とみなす`() {
        val d = dashboard(flow)
        val declared = DashboardFlows.declaredTargets(d, listOf("open"), "start")
        assertEquals(listOf<String?>("doing"), declared)
        assertFalse(DashboardFlowTransition("T-1", listOf("open"), listOf("doing"), declared).undeclared)
        assertTrue(DashboardFlowTransition("T-1", listOf("open"), listOf("done"), declared).undeclared)
        // 留まる工程（to なし）: 段が変わらなければ宣言どおり、変われば宣言外
        val stays = DashboardFlows.declaredTargets(d, listOf("open"), "note")
        assertEquals(listOf<String?>(null), stays)
        assertFalse(DashboardFlowTransition("T-1", listOf("open"), listOf("open"), stays).undeclared)
        assertTrue(DashboardFlowTransition("T-1", listOf("open"), listOf("doing"), stays).undeclared)
        // 前の段に宣言が無い操作（流れの外の操作）: 段を変えれば宣言外、変えなければ何も言わない
        val none = DashboardFlows.declaredTargets(d, listOf("done"), "start")
        assertTrue(none.isEmpty())
        assertTrue(DashboardFlowTransition("T-1", listOf("done"), listOf("doing"), none).undeclared)
        assertFalse(DashboardFlowTransition("T-1", listOf("done"), listOf("done"), none).undeclared)
        // 前に段にいない対象は判定しない
        assertFalse(DashboardFlowTransition("T-1", emptyList(), listOf("open"), none).undeclared)
        // 同じ操作が段ごとに違う移り先を持つ（取消は未処理からも対応中からも）
        assertEquals(listOf<String?>("dropped"), DashboardFlows.declaredTargets(d, listOf("doing"), "drop"))
    }
}
