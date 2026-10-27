package dev.toros.yde.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.toros.yde.desktop.AppState
import dev.toros.yde.app.core.Views
import dev.toros.yde.semantic.ChartData
import dev.toros.yde.semantic.ChartDrill
import dev.toros.yde.semantic.ChartKind
import dev.toros.yde.semantic.ChartScale
import dev.toros.yde.semantic.VirtualChartDef
import dev.toros.yde.yaml.model.pretty

// ================================================================ チャート（kind: chart。P46）

/** 系列の色。意味は持たず、系列の順で回す（ライトとダークの両方で読める、落ち着いた色）。 */
private val seriesColors: List<Color>
    @Composable get() = listOf(
        Palette.ChartReady, Palette.ChartPending, Palette.OkGreen, Palette.Secondary, Palette.ErrorRed, Palette.Folder, Palette.Primary,
    )

/**
 * チャートの描画 1 枚（台帳のビュータブ・文書ビューの区画・ダッシュボードの区画で共用）。
 * データは元の表の解決結果（ChartData）で、ここは種類に応じて棒・積み上げ棒・折れ線・円の部品に渡すだけ。
 * 形（棒・点・扇形）を押すと、そのセルの材料（集めた要素と値）を定義の `drill` の置き方（浮いた一覧・図の下・図の右）で出し、
 * 各要素の業務詳細（onOpenEntity）か原本へ進める。「元の表を開く」で台帳のビュータブの元の表へ。
 */
@Composable
fun ChartCard(
    state: AppState,
    data: ChartData,
    modifier: Modifier = Modifier,
    height: Dp = 260.dp,
    showSourceButton: Boolean = true,
    /** 材料の ID を押したときの行き先（ダッシュボードなら業務詳細、それ以外はエンティティページ）。 */
    onOpenEntity: (String) -> Unit = { state.openCrossView(it) },
) {
    val def = data.def
    val colors = seriesColors
    val labels = data.categories.map { it.label }
    val series = data.series.mapIndexed { i, s -> ChartSeries(s.label, s.values.map { it ?: 0.0 }, colors[i % colors.size]) }
    var selection by remember(data) { mutableStateOf<Views.ChartSelection?>(null) }
    var tapAt by remember(data) { mutableStateOf(IntOffset.Zero) }
    val drill = def.drill
    val select: ((Int, Int?) -> Unit)? = if (drill == ChartDrill.NONE) null else { ci, si ->
        selection = Views.chartSelection(data, ci, si, state.overlayIndex())
    }
    Column(modifier.fillMaxWidth().border(1.dp, Palette.Border, PanelShape).background(Palette.Surface, PanelShape).padding(10.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(def.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
                def.description?.let { Text(it.replace("\n", " "), fontSize = 11.sp, color = Palette.SubText) }
            }
            if (showSourceButton) TextButton(onClick = { state.openViewGrid(def.view) }) { Text("元の表を開く", fontSize = 11.sp) }
        }
        val chart: @Composable () -> Unit = {
            when {
                data.categories.isEmpty() -> Text("項目がありません（元の表に行がありません）", Modifier.padding(16.dp), fontSize = 12.sp, color = Palette.SubText)
                series.isEmpty() -> Text("数の列がありません（series に系列にする列を書きます）", Modifier.padding(16.dp), fontSize = 12.sp, color = Palette.WarnOrange)
                def.kind == ChartKind.PIE -> PieChart(
                    labels, data.series.first().values, colors, height = height, title = def.yLabel, valueFormat = def.format,
                    onSelect = select?.let { s -> { ci -> s(ci, 0) } },
                )
                // 散布図: 横軸は x の数の列（xValues）、縦軸は系列。数として読めない項目は描かないので、値は null のまま渡す
                def.kind == ChartKind.SCATTER -> ScatterChart(
                    labels, data.xValues.orEmpty(), data.series.mapIndexed { i, s -> ScatterSeries(s.label, s.values, colors[i % colors.size]) },
                    height = height, title = def.yLabel, xLabel = def.x, valueFormat = def.format,
                    xLog = def.xScale == ChartScale.LOG, yLog = def.yScale == ChartScale.LOG, onSelect = select,
                )
                def.kind == ChartKind.LINE || def.kind == ChartKind.AREA || def.kind == ChartKind.STACKED_AREA -> LineChart(
                    labels, series, height = height, title = def.yLabel, valueFormat = def.format,
                    area = def.kind != ChartKind.LINE, stacked = def.kind == ChartKind.STACKED_AREA, onSelect = select,
                )
                def.kind == ChartKind.HORIZONTAL_BAR || def.kind == ChartKind.STACKED_HORIZONTAL_BAR -> HorizontalBarChart(
                    labels, series, stacked = def.kind == ChartKind.STACKED_HORIZONTAL_BAR, height = height, title = def.yLabel,
                    valueFormat = def.format, onSelect = select,
                )
                else -> BarChart(
                    labels, series, stacked = def.kind == ChartKind.STACKED_BAR, height = height, title = def.yLabel,
                    integerAxis = false, valueFormat = def.format, onSelect = select,
                )
            }
        }
        val current = selection
        when (drill) {
            ChartDrill.RIGHT -> Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) { chart() }
                if (current != null) MaterialsPanel(state, data, current, onOpenEntity, { selection = null }, Modifier.width(340.dp).padding(start = 8.dp))
            }
            ChartDrill.BELOW -> Column(Modifier.fillMaxWidth()) {
                chart()
                if (current != null) MaterialsPanel(state, data, current, onOpenEntity, { selection = null }, Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            else -> Box(Modifier.fillMaxWidth().tapPosition { tapAt = it }) {
                chart()
                if (current != null && drill == ChartDrill.POPUP) {
                    Popup(alignment = Alignment.TopStart, offset = tapAt, onDismissRequest = { selection = null }, properties = PopupProperties(focusable = true)) {
                        MaterialsPanel(state, data, current, onOpenEntity, { selection = null }, Modifier.width(360.dp))
                    }
                }
            }
        }
        data.notes.forEach { Text("注意: $it", fontSize = 11.sp, color = Palette.WarnOrange) }
        if (drill != ChartDrill.NONE && current == null && data.categories.isNotEmpty() && series.isNotEmpty()) {
            Text("棒・点・帯を押すと、その値の材料（集めた要素）が出ます", fontSize = 10.sp, color = Palette.SubText)
        }
    }
}

/** 押した位置（浮いた一覧をその場所に出すため）。イベントは消費しない。 */
private fun Modifier.tapPosition(onTap: (IntOffset) -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press) {
                event.changes.firstOrNull()?.position?.let { p -> onTap(IntOffset(p.x.toInt(), p.y.toInt())) }
            }
        }
    }
}

/**
 * 押した形の材料の一覧: 項目（と系列）・値、集めた要素（ID と名前・値）ごとに「詳細」（業務詳細かエンティティページ）と「原本」。
 * 項目そのものが実体（顧客など）ならその詳細へも。表のセルを押したときの来歴メニューと同じ材料。
 */
@Composable
private fun MaterialsPanel(
    state: AppState,
    data: ChartData,
    selection: Views.ChartSelection,
    onOpenEntity: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val numberText = remember(data.def.format) { chartNumberFormatter(integerAxis = false, valueFormat = data.def.format) }
    Column(
        modifier.border(1.dp, Palette.Border, PanelShape).background(Palette.PanelBg, PanelShape).padding(10.dp)
            .heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val groups = selection.groups
            val header = selection.category.label +
                (if (selection.seriesIndex != null && groups.size == 1) " × " + groups.single().series.label else "")
            Text(header, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.padding(0.dp)) { Icon(Icons.Default.Close, contentDescription = "閉じる") }
        }
        if (selection.category.hasOrigin) {
            TextButton(onClick = { onOpenEntity(selection.category.rowId) }) { Text("この項目の詳細: " + selection.category.rowId, fontSize = 11.sp) }
        }
        selection.groups.forEach { group ->
            Text(
                group.series.label + ": " + (group.value?.let(numberText) ?: "—") +
                    (if (group.materials.isNotEmpty()) "（材料 ${group.materials.size} 件）" else ""),
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, modifier = Modifier.padding(top = 6.dp),
            )
            group.cell?.trace?.raw?.let { raw -> if (group.materials.isEmpty()) Text(raw, fontSize = 10.sp, color = Palette.SubText) }
            if (group.materials.isEmpty()) {
                val cell = group.cell
                val document = cell?.document
                if (document != null) {
                    TextButton(onClick = { state.openFile(document, cell.path) }) {
                        Text("原本へ: ${document.value} :: ${cell.path?.pretty()}", fontSize = 11.sp)
                    }
                } else Text("材料はありません", fontSize = 11.sp, color = Palette.SubText)
            }
            group.materials.forEach { m ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val label = if (m.id != null) m.id + (m.title?.let { " $it" } ?: "") else "{${m.label}}"
                    Text(label, fontSize = 11.sp, color = Palette.Text, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(m.value?.let { v -> dev.toros.yde.semantic.SemanticExpression.numberOf(v)?.toDouble()?.let(numberText) ?: v } ?: "—",
                        fontSize = 11.sp, color = Palette.SubText, modifier = Modifier.padding(horizontal = 6.dp))
                    val id = m.id  // 別モジュールの公開プロパティはスマートキャストできない
                    if (id != null) TextButton(onClick = { onOpenEntity(id) }) { Text("詳細", fontSize = 11.sp) }
                    val document = m.document
                    if (document != null) TextButton(onClick = { state.openFile(document, m.path) }) { Text("原本", fontSize = 11.sp) }
                }
            }
        }
        TextButton(onClick = { state.openViewGrid(data.sourceView.id) }) { Text("元の表を開く", fontSize = 11.sp) }
    }
}

/** 台帳のビュータブでチャートを選んだとき: 元の表を同じ索引・未保存編集で解決して描く。 */
@Composable
internal fun ChartGridView(state: AppState, loaded: Views.LoadResult, chart: VirtualChartDef) {
    val data = remember(chart, loaded, state.indexVersion, state.editVersion) {
        Views.resolveChart(chart, loaded, state.overlayIndex(), state.unsavedFirstTree(), state::schemaFor)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        if (data == null) {
            Text("元の表「${chart.view}」が見つかりません（グリッドかクロス集計のビュー ID を view に書きます）", fontSize = 12.sp, color = Palette.WarnOrange)
        } else {
            ChartCard(state, data, height = 320.dp)
            Spacer(Modifier.width(8.dp))
        }
    }
}

/** 文書ビューの区画（`chart: {view}`）に埋め込むチャート。フロー図の埋め込みと同じ位置づけ。 */
@Composable
internal fun EmbeddedChartPane(state: AppState, chartId: String) {
    val ws = state.workspace ?: return
    val loaded = remember(ws, state.indexVersion) { Views.load(ws, state.schemas) }
    val chart = loaded.chartView(chartId)
    if (chart == null) {
        Text("チャート「$chartId」が見つかりません", fontSize = 11.sp, color = Palette.WarnOrange)
        return
    }
    val data = remember(chart, loaded, state.indexVersion, state.editVersion) {
        Views.resolveChart(chart, loaded, state.overlayIndex(), state.unsavedFirstTree(), state::schemaFor)
    }
    if (data == null) Text("元の表「${chart.view}」が見つかりません", fontSize = 11.sp, color = Palette.WarnOrange)
    else ChartCard(state, data, height = 240.dp)
}
