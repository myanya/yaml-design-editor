package dev.toros.yde.desktop.ui.view

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.core.Views
import dev.toros.yde.semantic.DashboardFlow
import dev.toros.yde.semantic.DashboardFlowMoveResult
import dev.toros.yde.semantic.DashboardFlowResult
import dev.toros.yde.semantic.DashboardFlows
import dev.toros.yde.semantic.DashboardMove
import dev.toros.yde.semantic.VirtualDashboardDef
import java.time.LocalDate
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PanelShape
import dev.toros.yde.desktop.ui.common.UiTooltip

/** 工程の鍵（画面の覚え `dashboardMove` に置く）。 */
internal fun moveKey(m: DashboardMove): String = "${m.from}|${m.action}|${m.to.orEmpty()}"

/** 流れ図の箱と札の見え方。 */
internal enum class FlowTone { NORMAL, SELECTED, CURRENT, MUTED, WARN, DISABLED }

/**
 * 流れ図の描画（配置は DashboardFlowLayout）。箱＝段、矢印の札＝工程、箱の中の札＝段に留まる工程、左端（上端）の札＝入口。
 * 押せるかは onStep / onMove / onStart の有無と、札の見え方（DISABLED は押せない）で決まる。
 */
@Composable
internal fun FlowDiagram(
    flow: DashboardFlow,
    stepTitle: (String) -> String,
    stepNote: (String) -> String?,
    stepTone: (String) -> FlowTone,
    moveText: (DashboardMove) -> String,
    moveTone: (DashboardMove) -> FlowTone,
    moveHint: (DashboardMove) -> String? = { null },
    onStep: ((String) -> Unit)? = null,
    onMove: ((DashboardMove) -> Unit)? = null,
    startText: String? = null,
    onStart: (() -> Unit)? = null,
    /** 開いたときに見える位置まで横に送る段（業務詳細の「いまここ」）。 */
    focus: String? = null,
    modifier: Modifier = Modifier,
) {
    val layout = remember(flow) { DashboardFlowLayout.arrange(flow) }
    val lineColors = layout.edges.map { e -> e.move?.let { color(moveTone(it), line = true) } ?: Palette.SubText }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(focus, layout) {
        val box = focus?.let { layout.boxes[it] } ?: return@LaunchedEffect
        scroll.scrollTo(with(density) { (box.x - 24f).coerceAtLeast(0f).dp.roundToPx() })
    }
    Box(modifier.horizontalScroll(scroll)) {
        Box(Modifier.size(layout.width.dp, layout.height.dp)) {
            Canvas(Modifier.matchParentSize()) {
                layout.edges.forEachIndexed { i, edge ->
                    val c = lineColors[i]
                    val pts = edge.points.map { Offset(it.x.dp.toPx(), it.y.dp.toPx()) }
                    val path = Path().apply { moveTo(pts.first().x, pts.first().y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
                    drawPath(path, c, style = Stroke(width = 1.5.dp.toPx()))
                    // 矢じり（最後の線分の向き）
                    val a = pts[pts.size - 2]
                    val b = pts.last()
                    val len = kotlin.math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat().coerceAtLeast(1f)
                    val ux = (b.x - a.x) / len
                    val uy = (b.y - a.y) / len
                    val s = 7.dp.toPx()
                    drawPath(Path().apply {
                        moveTo(b.x, b.y)
                        lineTo(b.x - ux * s - uy * s * .6f, b.y - uy * s + ux * s * .6f)
                        lineTo(b.x - ux * s + uy * s * .6f, b.y - uy * s - ux * s * .6f)
                        close()
                    }, c)
                }
            }
            flow.steps.forEach { step ->
                val r = layout.boxes.getValue(step)
                val tone = stepTone(step)
                Column(
                    Modifier.offset(r.x.dp, r.y.dp).size(r.w.dp, r.h.dp)
                        .background(background(tone), PanelShape)
                        .border(if (tone == FlowTone.SELECTED || tone == FlowTone.CURRENT) 2.dp else 1.dp, color(tone, line = false), PanelShape)
                        .then(if (onStep != null) Modifier.clickable { onStep(step) } else Modifier)
                        .testTag("flow-step-$step").semantics(mergeDescendants = true) {}
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(stepTitle(step), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (tone == FlowTone.MUTED) Palette.SubText else Palette.Text)
                    stepNote(step)?.let { Text(it, fontSize = 12.sp, maxLines = 1, color = color(tone, line = false)) }
                }
            }
            layout.stays.forEach { (m, r) -> MoveChip(m, r, moveText(m), moveTone(m), moveHint(m), onMove, prefix = "↺ ") }
            layout.edges.forEach { e -> val m = e.move; val r = e.label; if (m != null && r != null) MoveChip(m, r, moveText(m), moveTone(m), moveHint(m), onMove) }
            layout.start?.let { r ->
                val text = startText ?: return@let
                Box(Modifier.offset(r.x.dp, r.y.dp).size(r.w.dp, r.h.dp)
                    .border(1.dp, Palette.Primary, PanelShape).background(Palette.Surface, PanelShape)
                    .then(if (onStart != null) Modifier.clickable(onClick = onStart) else Modifier)
                    .testTag("flow-start"), contentAlignment = Alignment.Center) {
                    Text(text, fontSize = 12.sp, color = Palette.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun MoveChip(
    m: DashboardMove, r: DashboardFlowLayout.Rect, text: String, tone: FlowTone, hint: String?,
    onMove: ((DashboardMove) -> Unit)?, prefix: String = "",
) {
    val clickable = onMove != null && tone != FlowTone.DISABLED && tone != FlowTone.MUTED
    val chip = @Composable {
        Box(Modifier.size(r.w.dp, r.h.dp)
            .background(if (tone == FlowTone.SELECTED) Palette.Selected else Palette.EditorBg, PanelShape)
            .border(1.dp, color(tone, line = true), PanelShape)
            .then(if (clickable) Modifier.clickable { onMove?.invoke(m) } else Modifier)
            .testTag("flow-move-${moveKey(m)}").semantics(mergeDescendants = true) {}, contentAlignment = Alignment.Center) {
            Text(prefix + text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = color(tone, line = false),
                modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
    Box(Modifier.offset(r.x.dp, r.y.dp)) { if (hint != null) UiTooltip(hint) { chip() } else chip() }
}

private fun color(tone: FlowTone, line: Boolean): Color = when (tone) {
    FlowTone.SELECTED, FlowTone.CURRENT -> Palette.Primary
    FlowTone.WARN -> Palette.WarnOrange
    FlowTone.DISABLED, FlowTone.MUTED -> if (line) Palette.Border else Palette.SubText
    FlowTone.NORMAL -> if (line) Palette.SubText else Palette.Text
}

private fun background(tone: FlowTone): Color = when (tone) {
    FlowTone.SELECTED, FlowTone.CURRENT -> Palette.Selected
    else -> Palette.Surface
}

/** ダッシュボードの流れ図: 段の件数と、工程ごとの「いま操作できる数／その段の数」。段を押すと区画の一覧、工程を押すと作業一覧。 */
@Composable
internal fun DashboardFlowOverview(state: AppState, view: VirtualDashboardDef, result: DashboardFlowResult, selectedSection: String?, selectedMove: String?) {
    val steps = result.steps.associateBy { it.section.id }
    val moves = result.moves.associateBy { it.move }
    fun label(step: String) = steps[step]?.section?.label ?: step
    val start = result.flow.start
    val startEntry = remember(start, state.indexVersion) { start?.let { s -> state.actionsForWorkspace().singleOrNull { it.name == s.action } } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text("業務の流れ", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.SubText)
        FlowDiagram(
            result.flow,
            stepTitle = ::label,
            stepNote = { s -> steps[s]?.let { st -> if (st.error != null) "表示できません" else "${st.count} 件" + if (st.terminal) "（終点）" else "" } },
            stepTone = { s -> when {
                selectedMove == null && s == selectedSection -> FlowTone.SELECTED
                steps[s]?.terminal == true -> FlowTone.MUTED
                else -> FlowTone.NORMAL
            } },
            moveText = { m -> moves[m]?.let { r -> "${r.label}  ${r.ready.size}/${total(r)}" } ?: m.action },
            moveTone = { m -> val r = moves[m]; when {
                moveKey(m) == selectedMove -> FlowTone.SELECTED
                r == null || total(r) == 0 -> FlowTone.MUTED
                r.blocked.isNotEmpty() -> FlowTone.WARN
                else -> FlowTone.NORMAL
            } },
            moveHint = { m -> moves[m]?.let { r ->
                "「${label(m.from)}」の ${total(r)} 件のうち ${r.ready.size} 件を操作できます" +
                    (if (r.blocked.isNotEmpty()) "（できない ${r.blocked.size} 件）" else "") + "。押すと作業一覧を出します"
            } },
            onStep = { s -> state.memory.dashboardSection[view.id] = s; state.memory.dashboardMove.remove(view.id) },
            onMove = { m -> state.memory.dashboardMove[view.id] = moveKey(m) },
            startText = result.startLabel?.let { "＋ $it" },
            onStart = startEntry?.let { e -> { state.startAction(e, null) } },
        )
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
    val flow = view.flow ?: return
    val date = state.memory.dashboardDate[view.id] ?: LocalDate.now()
    val position = remember(view, id, date, state.indexVersion, state.editVersion) { state.dashboardPosition(view, id, date) } ?: return
    if (position.steps.isEmpty() && position.undetermined.isEmpty()) return
    fun label(step: String) = view.sections.firstOrNull { it.id == step }?.label ?: step
    val next = position.moves.associateBy { it.move }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).border(1.dp, Palette.Border, PanelShape).padding(10.dp).testTag("flow-position")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("流れの中の位置", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(if (position.steps.isEmpty()) "どの段にいるか判定できません" else "いま「${position.steps.joinToString("・") { label(it) }}」にいます",
                fontSize = 12.sp, color = if (position.steps.isEmpty()) Palette.WarnOrange else Palette.Primary)
        }
        position.undetermined.forEach { (step, reason) -> Text("「${label(step)}」は判定できません: $reason", fontSize = 11.sp, color = Palette.WarnOrange) }
        Spacer(Modifier.height(6.dp))
        FlowDiagram(
            // 入口（登録）は対象 1 件の位置には要らないので描かない
            flow.copy(start = null),
            focus = position.steps.firstOrNull(),
            stepTitle = ::label,
            stepNote = { s -> if (s in position.steps) "いまここ" else null },
            stepTone = { s -> if (s in position.steps) FlowTone.CURRENT else FlowTone.MUTED },
            moveText = { m -> next[m]?.label ?: state.actionLabel(m.action) },
            moveTone = { m -> val n = next[m]; when {
                n == null -> FlowTone.MUTED
                !n.check.enabled -> FlowTone.DISABLED
                else -> FlowTone.NORMAL
            } },
            moveHint = { m -> next[m]?.let { n -> if (n.check.enabled) "押すと「${n.label}」の入力・確認へ進みます" else n.check.reason } },
            onMove = { m -> state.startAction(m.action, id) },
        )
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
