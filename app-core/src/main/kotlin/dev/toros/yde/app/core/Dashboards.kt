package dev.toros.yde.app.core

import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.app.schema.fieldAt
import dev.toros.yde.semantic.DashboardFlowPosition
import dev.toros.yde.semantic.DashboardFlowResult
import dev.toros.yde.semantic.DashboardFlowTransition
import dev.toros.yde.semantic.DashboardFlows
import dev.toros.yde.semantic.DashboardMoveCheck
import dev.toros.yde.semantic.DashboardResolution
import dev.toros.yde.semantic.DashboardViews
import dev.toros.yde.semantic.VirtualDashboardDef
import dev.toros.yde.workspace.ActionPlanner
import dev.toros.yde.workspace.ActionRequest
import dev.toros.yde.workspace.WorkspaceSession
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.pretty
import java.time.LocalDate

/** 同じ索引・原本・基準日を全区画に渡す窓口。時計は呼び出し側が採取する。 */
object Dashboards {
    fun resolve(
        view: VirtualDashboardDef, date: LocalDate, catalog: Views.LoadResult, index: IdIndex,
        schemaFor: (DocumentId) -> SchemaDef?, loader: (DocumentId) -> YNode?,
        /** 共有の入力の値。鍵があればその値（空＝絞らない）、無ければ定義の default（基準日から）。 */
        inputs: Map<String, String?> = emptyMap(),
    ): DashboardResolution {
        val trees = mutableMapOf<DocumentId, YNode?>()
        fun tree(d: DocumentId): YNode? = if (trees.containsKey(d)) trees[d] else loader(d).also { trees[d] = it }
        val values = view.inputs.associate { input ->
            input.key to (if (inputs.containsKey(input.key)) inputs[input.key]?.takeIf { it.isNotBlank() } else input.defaultFor(date))
        }
        return DashboardViews.resolve(view, date, catalog::dashboardGrid,
            { grid, params -> Views.resolveDetailed(grid, index, ::tree, schemaFor, params) }, index.entries, index.refs, index.graph, ::tree,
            fieldAt = { d, path -> tree(d)?.let { schemaFor(d)?.fieldAt(it, path) } },
            embedded = catalog::extensionView, inputs = values)
    }

    /** 操作の名前（定義の label）。 */
    fun actionLabels(session: WorkspaceSession): (String) -> String? {
        val labels = ActionPlanner.catalog(session.schemas).associate { it.name to it.label }
        return { labels[it] }
    }

    /** 操作の可否（セッションの前提の判定。手は実行しない）。 */
    fun actionCheck(session: WorkspaceSession): (String, String) -> DashboardMoveCheck = { action, target ->
        val a = ActionPlanner.availability(session, ActionRequest(action, target))
        DashboardMoveCheck(a.enabled, a.reason, a.needsInput)
    }

    /**
     * 流れ（flow）の段の件数と工程ごとの可否。段の対象は解決済みの区画の行（件数と同じ母集団）。
     * 名前は labels のセッション、可否は check のセッション（画面は編集用のセッションで判定する）。
     */
    fun flow(view: VirtualDashboardDef, result: DashboardResolution, labels: WorkspaceSession, check: WorkspaceSession = labels): DashboardFlowResult? =
        DashboardFlows.resolve(view, result, actionLabels(labels), actionCheck(check))

    /** 対象 1 件の流れの中の位置（いる段・次にできる工程とその可否・履歴）。共有の入力は使わない。 */
    fun position(
        view: VirtualDashboardDef, id: String, date: LocalDate, index: IdIndex,
        schemaFor: (DocumentId) -> SchemaDef?, loader: (DocumentId) -> YNode?,
        labels: WorkspaceSession, check: WorkspaceSession = labels,
    ): DashboardFlowPosition? {
        val trees = mutableMapOf<DocumentId, YNode?>()
        fun tree(d: DocumentId): YNode? = if (trees.containsKey(d)) trees[d] else loader(d).also { trees[d] = it }
        return DashboardFlows.position(view, id, date, index.entries, index.refs, index.graph, ::tree, actionLabels(labels), actionCheck(check),
            fieldAt = { d, path -> tree(d)?.let { schemaFor(d)?.fieldAt(it, path) } })
    }

    /**
     * 移り先の予告: 操作の前（before の索引と原本）と後（after の索引と原本＝計画した変更セットを重ねたもの）で、対象がいる段。
     * 宣言した工程の移り先と比べられるように、この操作の宣言も添える。
     */
    fun transition(
        view: VirtualDashboardDef, id: String, action: String, date: LocalDate, schemaFor: (DocumentId) -> SchemaDef?,
        before: Pair<IdIndex, (DocumentId) -> YNode?>, after: Pair<IdIndex, (DocumentId) -> YNode?>,
    ): DashboardFlowTransition? {
        if (view.flow == null) return null
        fun steps(state: Pair<IdIndex, (DocumentId) -> YNode?>): List<String> {
            val (index, loader) = state
            return DashboardFlows.stepsOf(view, id, date, index.entries, index.refs, index.graph, loader,
                fieldAt = { d, path -> loader(d)?.let { schemaFor(d)?.fieldAt(it, path) } }).first
        }
        val from = steps(before)
        return DashboardFlowTransition(id, from, steps(after), DashboardFlows.declaredTargets(view, from, action))
    }

    /** 流れの開示（CLI と read_view と保存版の読み出し）。行は ID だけ（行の中身は区画の rows にある）。 */
    fun flowReport(result: DashboardFlowResult): Map<String, Any?> = linkedMapOf(
        "direction" to result.flow.direction.id,
        "start" to result.flow.start?.let { linkedMapOf("action" to it.action, "label" to result.startLabel, "to" to it.to) },
        "steps" to result.steps.map { step -> linkedMapOf(
            "id" to step.section.id, "label" to step.section.label, "count" to step.count?.toIntOrNull(), "terminal" to step.terminal,
        ) },
        "moves" to result.moves.map { m -> linkedMapOf(
            "from" to m.move.from, "action" to m.move.action, "label" to m.label, "to" to m.move.to,
            "ready" to m.ready.map { it.id },
            "blocked" to m.blocked.map { linkedMapOf("id" to it.row.id, "reason" to it.reason) },
            "unchecked" to m.unchecked.map { it.id },
        ) },
    ).also { m ->
        // 段の群（枠）: Lifecycle の詳細化の親。群から出る工程は、中のどの段の対象にも当たる
        if (result.groups.isNotEmpty()) m["groups"] = result.groups.map { g -> linkedMapOf(
            "id" to g.section.id, "label" to g.section.label, "count" to g.count?.toIntOrNull(),
            "steps" to result.flow.groups.firstOrNull { it.id == g.section.id }?.steps.orEmpty(),
        ) }
    }

    /** 対象 1 件の位置の開示（CLI の view --entity）。 */
    fun positionReport(view: VirtualDashboardDef, position: DashboardFlowPosition): Map<String, Any?> = linkedMapOf(
        "view" to view.id, "kind" to "dashboard-position", "id" to position.id,
        "steps" to position.steps.map { step -> linkedMapOf("id" to step, "label" to view.sections.firstOrNull { it.id == step }?.label) },
        // いる段を囲む群（枠）
        "groups" to position.steps.flatMap { view.flow?.groupsOf(it).orEmpty() }.distinct()
            .map { g -> linkedMapOf("id" to g.id, "label" to view.sections.firstOrNull { it.id == g.id }?.label) },
        "undetermined" to position.undetermined.map { (step, reason) -> linkedMapOf("id" to step, "reason" to reason) },
        "moves" to position.moves.map { m -> linkedMapOf(
            "from" to m.move.from, "action" to m.move.action, "label" to m.label, "to" to m.move.to,
            "enabled" to m.check.enabled, "reason" to m.check.reason, "needsInput" to m.check.needsInput,
        ) },
        "history" to position.history.map { h -> linkedMapOf(
            "date" to h.date, "action" to h.action, "label" to h.actionLabel, "from" to h.from, "to" to h.to, "note" to h.note,
        ) },
    )

    /** CLIとread_viewは同じ解決結果を、原本の座標と判定できなかった対象つきで開示する。 */
    fun report(view: VirtualDashboardDef, result: DashboardResolution, errors: List<String> = emptyList(), flow: DashboardFlowResult? = null): Map<String, Any?> =
        linkedMapOf(
            "view" to view.id, "kind" to "dashboard", "date" to result.date.toString(), "actions" to view.actions,
            "placement" to view.placement.id,
            // 共有の入力: 定義と、今回効かせた値（null＝絞っていない）
            "inputs" to view.inputs.map { i -> linkedMapOf("key" to i.key, "label" to i.label, "type" to i.type.id, "value" to result.inputs[i.key], "default" to i.default) },
            "sections" to result.sections.map { section -> linkedMapOf(
                "id" to section.definition.id, "label" to section.definition.label, "view" to section.definition.view,
                "group" to section.definition.group,
                "count" to if (section.error == null) section.count.toInt() else null,
                // 元の表のクエリ行が limit で切れたとき（書かなければ上限なし）
                "truncated" to section.truncation?.let { linkedMapOf("limit" to it.limit, "total" to it.total) },
                "rows" to section.rows.map { row -> linkedMapOf(
                    "key" to row.key, "id" to row.id, "title" to row.title,
                    "origins" to row.occurrences.map { linkedMapOf("file" to it.document.value, "path" to it.path.pretty()) },
                    "cells" to row.cells.map { cell -> linkedMapOf(
                        "label" to cell.column.label, "value" to cell.value?.let { dev.toros.yde.yaml.YamlIO.toAny(it) },
                        "file" to cell.document?.value, "path" to cell.path?.pretty(),
                    ) },
                ) },
                "pending" to section.pending.map { linkedMapOf("id" to it.row.id, "reason" to it.reason) },
            ).also { m ->
                // 拡張の種類の区画（チャートなど）: 種類の ID を鍵に、その結果の値（描画は画面だけ）
                section.embedded?.let { m[it.def.viewKind] = it.report() }
                m["error"] = section.error
            } }, "errors" to errors,
        ).also { m -> flow?.let { m["flow"] = flowReport(it) } }
}
