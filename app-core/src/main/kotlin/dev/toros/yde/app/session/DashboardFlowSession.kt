package dev.toros.yde.app.session

import dev.toros.yde.app.core.Dashboards
import dev.toros.yde.app.core.Views
import dev.toros.yde.semantic.DashboardFlowPosition
import dev.toros.yde.semantic.DashboardFlowResult
import dev.toros.yde.semantic.DashboardFlowTransition
import dev.toros.yde.semantic.DashboardResolution
import dev.toros.yde.semantic.VirtualDashboardDef
import dev.toros.yde.workspace.ActionPlan
import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.deepCopy
import dev.toros.yde.yaml.patch.Patcher
import java.time.LocalDate

/*
 * ダッシュボードの流れ（flow）のセッション側の入口（Compose 非依存）。画面とテストが同じ判定を使う。
 * 名前はディスクを映すセッション、可否は編集用のセッション（操作のメニューと同じ。architecture/actions.md「入力準備と確認の有効期間」）。
 */

/** 段の件数と工程ごとの可否。 */
fun DocumentSession.dashboardFlow(view: VirtualDashboardDef, result: DashboardResolution): DashboardFlowResult? {
    val cw = core ?: return null
    if (view.flow == null) return null
    return runCatching { Dashboards.flow(view, result, cw.mirror, kernel ?: cw.mirror) }.getOrNull()
}

/** 対象 1 件の流れの中の位置（未保存の編集を重ねた索引と原本で判定する）。 */
fun DocumentSession.dashboardPosition(view: VirtualDashboardDef, id: String, date: LocalDate): DashboardFlowPosition? {
    val cw = core ?: return null
    if (view.flow == null || overlayIndex().entries[id].isNullOrEmpty()) return null
    return runCatching {
        Dashboards.position(view, id, date, overlayIndex(), ::schemaFor, unsavedFirstTree(), cw.mirror, kernel ?: cw.mirror)
    }.getOrNull()
}

/**
 * 計画の移り先の予告: 計画の基準（ディスクの内容）と、計画の変更セットを重ねた内容で、対象がいる段を比べる。
 * ダッシュボードは、画面で開いているもの → 既定 → この操作を工程に持つ流れの順に、対象が段にいるものを選ぶ。
 */
fun DocumentSession.actionTransition(
    catalog: Views.LoadResult, preferred: String?, action: String, target: String, plan: ActionPlan, date: LocalDate,
): Pair<VirtualDashboardDef, DashboardFlowTransition>? {
    val cw = core ?: return null
    if (plan.isEmpty) return null
    val candidates = (listOfNotNull(preferred?.let(catalog::dashboard), catalog.defaultDashboard()) +
        catalog.dashboards.filter { d -> d.flow?.moves?.any { it.action == action } == true })
        .distinctBy { it.id }.filter { it.flow != null }
    if (candidates.isEmpty()) return null
    // 計画の変更セットを、いまの原本の写しに当てた木（作る文書はその中身）
    val planned = runCatching {
        plan.changeSet.changes.associate { change ->
            val tree = if (change.create) change.content?.deepCopy()
                else cw.treeOf(DocumentId(change.file))?.deepCopy()?.also { root -> change.ops.forEach { Patcher.apply(root, ChangeSets.copyOp(it)) } }
            change.file to tree
        }
    }.getOrNull() ?: return null
    val after = cw.overlayIndex(planned.mapKeys { cw.resolve(it.key) })
    val afterTree: (DocumentId) -> YNode? = { d -> if (planned.containsKey(d.value)) planned[d.value] else cw.treeOf(d) }
    for (view in candidates) {
        val transition = runCatching {
            Dashboards.transition(view, target, action, date, ::schemaFor, cw.index to cw::treeOf, after to afterTree)
        }.getOrNull() ?: continue
        if (transition.before.isNotEmpty() || transition.after.isNotEmpty()) return view to transition
    }
    return null
}
