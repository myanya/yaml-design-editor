package dev.toros.yde.desktop.ui.view

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.core.DashboardFlowDiagram
import dev.toros.yde.app.core.DashboardFlowDiagrams
import dev.toros.yde.app.core.Dashboards
import dev.toros.yde.app.core.Views
import dev.toros.yde.app.view.flow.FlowLayoutMode
import dev.toros.yde.semantic.DashboardFlowMoveResult
import dev.toros.yde.semantic.DashboardFlowResult
import dev.toros.yde.semantic.DashboardFlows
import dev.toros.yde.semantic.DashboardMove
import dev.toros.yde.semantic.VirtualDashboardDef
import dev.toros.yde.yaml.model.YPath
import java.time.LocalDate
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.DiagramContext
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PanelShape

/** 工程の鍵（画面の覚え `dashboardMove` に置く）。 */
internal fun moveKey(m: DashboardMove): String = "${m.from}|${m.action}|${m.to.orEmpty()}"

/*
 * 業務の流れの図は、フロービューと同じ表示モデル（DashboardFlowDiagrams が FlowModel にする）を、フロービューと同じ FlowCanvas
 * （配置 GraphLayouts.arrangeFlow・描画 drawFlow・拡大縮小と PNG）で描く。押した要素は表示モデルの対応表（DashboardFlowDiagram.target）で
 * 段・工程・入口へ引く。位置の記録は 1 つ（dashboardFlowContext）で、台帳のフロータブ（DashboardFlowEditor）が書き、
 * ダッシュボードの流れ図と業務詳細の位置の図は読むだけ（editable = false）。文書に埋め込んだフロー図と台帳のフロータブの関係と同じ。
 */

/** 業務の流れの図の位置の記録の見分け（図＝ダッシュボード ID、条件＝表示モード plain と定義の向き）。3 つの図で共有する。 */
internal fun dashboardFlowContext(view: VirtualDashboardDef): DiagramContext = DiagramContext(
    mapOf("kind" to "dashboard-flow", "view" to view.id),
    mapOf("mode" to listOf(FlowLayoutMode.PLAIN.name.lowercase()), "direction" to listOf(view.flow?.direction?.id ?: "right")),
)

/**
 * ダッシュボードの流れ図。段を押すと区画の一覧、工程の線を押すと作業一覧、入口を押すと登録の操作。
 * 見出しで畳める（区画の群と同じ見出し・同じ覚え方。畳むと段の区画はタイルの並びに戻る）。
 */
@Composable
internal fun DashboardFlowOverview(
    state: AppState, view: VirtualDashboardDef, result: DashboardFlowResult, selectedSection: String?, selectedMove: String?, collapsed: Boolean,
) {
    val diagram = remember(view, result) { DashboardFlowDiagrams.overview(view, result) }
    val start = result.flow.start
    val startEntry = remember(start, state.indexVersion) { start?.let { s -> state.actionsForWorkspace().singleOrNull { it.name == s.action } } }
    val selected = selectedMove?.let { key -> diagram.moves.entries.firstOrNull { moveKey(it.value) == key }?.key }
        ?: selectedSection?.let(diagram::stepPath)
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupHeader("業務の流れ", result.flow.steps.size, collapsed, Modifier.testTag("dashboard-flow-header")) {
                state.memory.dashboardFlowCollapsed[view.id] = !collapsed
            }
            Spacer(Modifier.width(8.dp))
            Text(if (collapsed) "畳んでいる間は、段の区画を下のタイルに並べます"
                else "段を押すとその一覧、工程の線を押すと作業一覧、入口を押すと登録", fontSize = 11.sp, color = Palette.SubText,
                modifier = Modifier.weight(1f))
            // 文書に埋め込んだフロー図と同じく、台帳のフロータブで大きく開く（箱の位置はそこで編集する）
            if (!collapsed) TextButton(onClick = { state.openFlowView(view.id) }) { Text("拡大表示（配置の編集）", fontSize = 11.sp) }
        }
        if (!collapsed) FlowCanvas(state, diagram.model, diagram.direction, FlowLayoutMode.PLAIN, selected,
            Modifier.fillMaxWidth().height(300.dp).testTag("dashboard-flow"),
            dashboardFlowContext(view), editable = false) { kind, path ->
            when (val target = diagram.target(kind, path)) {
                is DashboardFlowDiagram.Target.Step -> { state.memory.dashboardSection[view.id] = target.id; state.memory.dashboardMove.remove(view.id) }
                is DashboardFlowDiagram.Target.Move -> state.memory.dashboardMove[view.id] = moveKey(target.move)
                DashboardFlowDiagram.Target.Start -> startEntry?.let { state.startAction(it, null) }
                null -> Unit
            }
        }
    }
}

private fun total(r: DashboardFlowMoveResult): Int = r.ready.size + r.blocked.size + r.unchecked.size

/**
 * 工程の作業一覧: 前の段の対象を「いま操作できる」「操作できない（理由）」に分け、行のボタンで操作のパネル（入力 → 確認 → 適用）を開く。
 * 番号を押すと業務詳細。適用すると対象は次の段へ移り、この一覧から外れる。
 */
@Composable
internal fun ColumnScope.MoveBody(state: AppState, result: DashboardFlowResult, move: DashboardFlowMoveResult) {
    fun label(step: String) = result.step(step)?.section?.label ?: step
    val from = label(move.move.from)
    val to = move.move.to?.let(::label)
    Text("${move.label}（$from → ${to ?: "$from のまま"}）", Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    Text("「$from」の ${total(move)} 件のうち、いま操作できるのは ${move.ready.size} 件です。行の「${move.label}」で入力・確認・適用へ進みます。番号を押すと業務詳細を開きます。",
        Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 11.sp, color = Palette.SubText)
    if (total(move) == 0) Text("この段に対象はありません", Modifier.padding(16.dp), color = Palette.SubText)
    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).testTag("flow-work-list")) {
        if (move.ready.isNotEmpty()) item { WorkHeader("いま操作できる（${move.ready.size}）", Palette.OkGreen) }
        items(move.ready, key = { "ready:" + it.key }) { row ->
            WorkRow(state, row.id, summary(row)) {
                Button(onClick = { state.startAction(move.move.action, row.id) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.testTag("flow-run-${row.id}")) { Text(move.label, fontSize = 12.sp) }
            }
        }
        if (move.blocked.isNotEmpty()) item { WorkHeader("操作できない（${move.blocked.size}）", Palette.WarnOrange) }
        items(move.blocked, key = { "blocked:" + it.row.key }) { b ->
            WorkRow(state, b.row.id, b.reason, warn = true) {}
        }
        if (move.unchecked.isNotEmpty()) item {
            Text("ほかに ${move.unchecked.size} 件は判定を省きました（1 つの工程で確かめるのは ${DashboardFlows.CHECK_LIMIT} 件まで）。段の一覧から開けます",
                Modifier.padding(vertical = 6.dp), fontSize = 11.sp, color = Palette.SubText)
        }
    }
}

@Composable
private fun WorkHeader(text: String, color: Color) {
    Text(text, Modifier.padding(top = 10.dp, bottom = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
}

@Composable
private fun WorkRow(state: AppState, id: String, text: String, warn: Boolean = false, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().border(1.dp, Palette.Border, PanelShape).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { state.openDashboardEntity(id) }) { Text(id, fontSize = 12.sp) }
        Text(text, Modifier.weight(1f), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (warn) Palette.WarnOrange else Palette.Text)
        action()
    }
    Spacer(Modifier.height(4.dp))
}

/** 行の 1 行要約（表示名と、表の最初のいくつかの列）。 */
private fun summary(row: dev.toros.yde.semantic.VirtualRow): String =
    (listOfNotNull(row.title) + row.cells.take(4).mapNotNull { c -> Views.cellText(c.value, 30).takeIf { it.isNotBlank() }?.let { "${c.column.label}: $it" } })
        .joinToString("・")

/**
 * 業務詳細の「流れの中の位置」: いる段（いまここ）・次にできること（可否と理由。押すと操作のパネル）・履歴。
 * 画面で開いているダッシュボード（無ければ既定）に流れがあり、対象がその段のどれかにいるときだけ出す。骨格（shell）が業務詳細に差し込む。
 */
@Composable
fun DashboardPositionPanel(state: AppState, id: String) {
    val ws = state.workspace ?: return
    val catalog = remember(ws, state.indexVersion, state.editVersion) { Views.load(ws, state.schemas, state.unsavedFirstTree()) }
    val view = (state.dashboardId?.let(catalog::dashboard) ?: catalog.defaultDashboard())?.takeIf { it.flow != null } ?: return
    val date = state.memory.dashboardDate[view.id] ?: LocalDate.now()
    val position = remember(view, id, date, state.indexVersion, state.editVersion) { state.dashboardPosition(view, id, date) } ?: return
    if (position.steps.isEmpty() && position.undetermined.isEmpty()) return
    fun label(step: String) = view.sections.firstOrNull { it.id == step }?.label ?: step
    val next = position.moves.associateBy { it.move }
    val diagram = remember(view, position) { DashboardFlowDiagrams.position(view, position, state::actionLabel) }
    val collapsed = state.memory.dashboardPositionCollapsed[view.id] == true
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).border(1.dp, Palette.Border, PanelShape).padding(10.dp).testTag("flow-position")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupHeader("流れの中の位置", position.moves.size, collapsed, Modifier.testTag("flow-position-header")) {
                state.memory.dashboardPositionCollapsed[view.id] = !collapsed
            }
            Spacer(Modifier.width(8.dp))
            Text(if (position.steps.isEmpty()) "どの段にいるか判定できません" else "いま「${position.steps.joinToString("・") { label(it) }}」にいます",
                fontSize = 12.sp, color = if (position.steps.isEmpty()) Palette.WarnOrange else Palette.Primary)
        }
        position.undetermined.forEach { (step, reason) -> Text("「${label(step)}」は判定できません: $reason", fontSize = 11.sp, color = Palette.WarnOrange) }
        if (collapsed) return@Column
        Spacer(Modifier.height(6.dp))
        // いまいる段を選択の色で示す。いまの段から出る工程の線を押すとその操作、できない工程は理由を知らせる
        FlowCanvas(state, diagram.model, diagram.direction, FlowLayoutMode.PLAIN, position.steps.firstOrNull()?.let(diagram::stepPath),
            Modifier.fillMaxWidth().height(260.dp),
            dashboardFlowContext(view), editable = false) { kind, path ->
            val target = diagram.target(kind, path) as? DashboardFlowDiagram.Target.Move ?: return@FlowCanvas
            val n = next[target.move] ?: return@FlowCanvas
            if (n.check.enabled) state.startAction(n.move.action, id) else state.status = "${n.label}: ${n.check.reason ?: "前提を満たしません"}"
        }
        Spacer(Modifier.height(6.dp))
        Text("次にできること", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (position.moves.isEmpty()) Text("この段から先の工程はありません（終点）", fontSize = 11.sp, color = Palette.SubText)
        position.moves.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = m.check.enabled, onClick = { state.startAction(m.move.action, id) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.testTag("flow-next-${m.move.action}")) {
                    Text(m.label + (m.move.to?.takeIf { !m.move.stays }?.let { " → " + label(it) } ?: ""), fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
                if (!m.check.enabled) Text(m.check.reason ?: "前提を満たしません", fontSize = 11.sp, color = Palette.WarnOrange)
            }
        }
        if (position.history.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("履歴（新しい順）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            position.history.asReversed().forEach { h ->
                val change = listOfNotNull(h.from, h.to).takeIf { it.isNotEmpty() }?.joinToString(" → ")
                Text(listOfNotNull(h.date, h.actionLabel ?: h.action, change?.let { "（$it）" }, h.note).joinToString("  "), fontSize = 11.sp)
            }
        }
    }
}

/**
 * 台帳のフロータブで開くダッシュボードの業務の流れ（箱の位置の編集）。図はダッシュボードと同じ表示モデル（同じ基準日・共有の入力の件数）で、
 * フロービューと同じ道具（ドラッグでの固定・再配置・グリッド・配置の初期化）を使う。記録はダッシュボードの流れ図と業務詳細の位置の図が読む。
 */
@Composable
internal fun DashboardFlowEditor(state: AppState, view: VirtualDashboardDef, catalog: Views.LoadResult, modifier: Modifier) {
    val date = state.memory.dashboardDate[view.id] ?: LocalDate.now()
    val inputs = view.inputs.mapNotNull { i -> state.memory.dashboardInputs[view.id to i.key]?.let { i.key to it } }.toMap()
    val result = remember(view, catalog, date, inputs, state.indexVersion, state.editVersion) {
        Dashboards.resolve(view, date, catalog, state.overlayIndex(), state::schemaFor, state.unsavedFirstTree(), inputs)
    }
    val flow = remember(view, result, state.indexVersion, state.editVersion) { state.dashboardFlow(view, result) }
    var selected by remember(view.id) { mutableStateOf<YPath?>(null) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ダッシュボード「${view.name}」の業務の流れです。ここで動かした箱の位置は、ダッシュボードの流れ図と業務詳細の「流れの中の位置」にも出ます",
                fontSize = 11.sp, color = Palette.SubText, modifier = Modifier.weight(1f))
            TextButton(onClick = { state.openDashboard(view.id) }) { Text("ダッシュボードで開く", fontSize = 11.sp) }
            TextButton(onClick = { state.openFile(view.source) }) { Text("ビュー定義", fontSize = 11.sp) }
        }
        if (flow == null) { Text("業務の流れを解決できません", color = Palette.ErrorRed); return@Column }
        val diagram = remember(view, flow) { DashboardFlowDiagrams.overview(view, flow) }
        FlowCanvas(state, diagram.model, diagram.direction, FlowLayoutMode.PLAIN, selected, Modifier.weight(1f).fillMaxWidth().testTag("dashboard-flow-editor"),
            dashboardFlowContext(view)) { _, path -> selected = path }
    }
}
