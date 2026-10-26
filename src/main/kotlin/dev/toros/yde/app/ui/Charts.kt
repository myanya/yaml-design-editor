package dev.toros.yde.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.data.lineSeries
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.min
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnSeries
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerController
import com.patrykandpatrick.vico.compose.cartesian.marker.ColumnCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.LegendItem
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import com.patrykandpatrick.vico.compose.common.rememberHorizontalLegend
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme

// ================================================================ グラフ（Vico を包む）

/** 棒グラフの 1 系列。値はカテゴリの並びと同じ長さ。色は呼び出し側が意味で決める（準備完了＝青、未完＝橙など）。 */
class ChartSeries(val name: String, val values: List<Double>, val color: Color)

private val CategoryKey = ExtraStore.Key<List<String>>()

/**
 * 縦棒グラフ。ライブラリ（Vico）の型はこのファイルの外へ出さない（差し替えるときはここだけを書き換える）。
 * 系列が 2 つ以上なら凡例を出し、積み上げ（`stacked`）か並べて描く。棒の太さと間隔は表示幅とカテゴリ数から決め、
 * 上端を丸め、積み上げの区切りには背景色の細い縁を入れて見分けやすくする。マウスを載せると値が出る。
 * 件数の軸は 1 刻み。文字と線の色は Palette に合わせる。
 */
@Composable
fun BarChart(
    categories: List<String>,
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    stacked: Boolean = true,
    height: Dp = 220.dp,
    title: String? = null,
    /** 縦軸が件数（1 刻み・整数）か、金額のような数（刻みは自動・桁区切り）か。 */
    integerAxis: Boolean = true,
    /** 値の表示の形（DecimalFormat。軸とマウスを載せたときの文字。null＝整数なら桁区切り）。 */
    valueFormat: String? = null,
    /** 棒を押したとき（項目の番号・系列の番号。並べた棒では系列が決まらないので null）。null なら押せない。 */
    onSelect: ((categoryIndex: Int, seriesIndex: Int?) -> Unit)? = null,
) {
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(categories, series) {
        producer.runTransaction {
            columnSeries { series.forEach { s -> series(s.values.ifEmpty { listOf(0.0) }) } }
            extras { it[CategoryKey] = categories }
        }
    }
    val numberText = remember(integerAxis, valueFormat) { chartNumberFormatter(integerAxis, valueFormat) }
    // 押した位置の項目と系列: マウスを載せたときのマーカーの対象（項目 x と各系列の棒の上端 canvasY）を覚えておき、押したときに使う
    val targets = remember { mutableStateOf<List<CartesianMarker.Target>>(emptyList()) }
    val visibilityListener = remember { markerTargetsListener(targets) }
    val tapModifier = if (onSelect == null) Modifier else Modifier.tapWithoutConsuming tap@{ position ->
        val current = targets.value
        val x = current.firstOrNull()?.x?.toInt() ?: return@tap
        val columns = current.filterIsInstance<ColumnCartesianLayerMarkerTarget>().flatMap { it.columns }
        // 積み上げなら、押した高さがどの区間（下の棒の上端〜自分の上端）に入るかで系列が決まる。並べた棒では項目だけ
        val seriesIndex = if (!stacked || columns.isEmpty()) null else {
            val hit = columns.indices.firstOrNull { i -> columns[i].entry.y > 0.0 && columns[i].canvasY <= position.y }
            (hit ?: columns.indices.lastOrNull { columns[it].entry.y > 0.0 })?.let { columns[it].entry.seriesIndex }
        }
        onSelect(x, seriesIndex)
    }
    val colors = series.map { it.color }.ifEmpty { listOf(Palette.Primary) }
    val theme = rememberM3VicoTheme(
        columnCartesianLayerColors = colors,
        lineCartesianLayerColors = colors,
        lineColor = Palette.Border,
        textColor = Palette.SubText,
    )
    val axisLabel = rememberTextComponent(style = TextStyle(color = Palette.SubText, fontSize = 11.sp))
    val legendLabel = rememberTextComponent(style = TextStyle(color = Palette.Text, fontSize = 11.sp))
    val legendIcons = series.map { s -> rememberShapeComponent(fill = Fill(SolidColor(s.color)), shape = RoundedCornerShape(2.dp)) }
    val markerLabel = rememberTextComponent(style = TextStyle(color = Palette.Text, fontSize = 11.sp))
    // マウスを載せたときの文字: 「カテゴリ: 系列名 値・系列名 値」（既定の書式は系列名を持たず読めない）
    val markerFormatter = remember(series, categories) {
        object : DefaultCartesianMarker.ValueFormatter {
            override fun format(context: CartesianDrawingContext, targets: List<CartesianMarker.Target>): CharSequence {
                val label = targets.firstOrNull()?.let { categories.getOrNull(it.x.toInt()) }.orEmpty()
                val parts = targets.filterIsInstance<ColumnCartesianLayerMarkerTarget>().flatMap { it.columns }
                    .map { c -> (series.getOrNull(c.entry.seriesIndex)?.name ?: "") + " " + numberText(c.entry.y) }
                return (if (label.isNotEmpty()) "$label: " else "") + parts.joinToString("・")
            }
        }
    }
    val axisValueFormatter = remember(numberText) { CartesianValueFormatter { _, y, _ -> numberText(y) } }
    Column(modifier) {
        if (title != null) Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.SubText)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // 棒の太さと間隔は表示幅とカテゴリ数から決める（細く、幅いっぱいに太らせない。拡大・スクロールはさせない）
            val slot = maxWidth / categories.size.coerceAtLeast(1)
            val thickness = min(36.dp, slot * 0.45f)
            val spacing = (slot - thickness).coerceAtLeast(8.dp)
            val columns = series.map { s ->
                rememberLineComponent(
                    fill = Fill(SolidColor(s.color)), thickness = thickness,
                    shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                    strokeFill = Fill(SolidColor(Palette.Surface)), strokeThickness = 1.dp,
                )
            }
            ProvideVicoTheme(theme) {
                CartesianChartHost(
                    chart = rememberCartesianChart(
                        rememberColumnCartesianLayer(
                            columnProvider = ColumnCartesianLayer.ColumnProvider.series(columns),
                            columnCollectionSpacing = spacing,
                            mergeMode = { if (stacked) ColumnCartesianLayer.MergeMode.Stacked else ColumnCartesianLayer.MergeMode.Grouped() },
                        ),
                        // 件数の軸は 1 刻み、金額のような数は刻みを自動にして桁区切りで見せる
                        startAxis = VerticalAxis.rememberStart(
                            label = axisLabel,
                            valueFormatter = axisValueFormatter,
                            itemPlacer = if (integerAxis) VerticalAxis.ItemPlacer.step({ 1.0 }) else VerticalAxis.ItemPlacer.step(),
                        ),
                        bottomAxis = HorizontalAxis.rememberBottom(
                            label = axisLabel,
                            valueFormatter = CartesianValueFormatter { context, x, _ ->
                                context.model.extraStore.getOrNull(CategoryKey)?.getOrNull(x.toInt()) ?: ""
                            },
                            guideline = null,
                        ),
                        marker = rememberDefaultCartesianMarker(label = markerLabel, valueFormatter = markerFormatter),
                        markerVisibilityListener = visibilityListener,
                        markerController = CartesianMarkerController.rememberShowOnHover(),
                        legend = if (series.size >= 2) rememberHorizontalLegend(
                            items = { _ ->
                                series.forEachIndexed { i, s -> add(LegendItem(legendIcons[i], legendLabel, s.name)) }
                            },
                            iconSize = 10.dp,
                        ) else null,
                    ),
                    modelProducer = producer,
                    modifier = Modifier.fillMaxWidth().height(height).then(tapModifier),
                    scrollState = rememberVicoScrollState(scrollEnabled = false),
                    zoomState = rememberVicoZoomState(zoomEnabled = false),
                )
            }
        }
    }
}

/** マーカー（マウスを載せたときの対象）の変化を覚える。押したときに「どの項目・どの系列か」を決める材料になる。 */
private fun markerTargetsListener(holder: androidx.compose.runtime.MutableState<List<CartesianMarker.Target>>): CartesianMarkerVisibilityListener =
    object : CartesianMarkerVisibilityListener {
        override fun onShown(marker: CartesianMarker, targets: List<CartesianMarker.Target>) { holder.value = targets }
        override fun onUpdated(marker: CartesianMarker, targets: List<CartesianMarker.Target>) { holder.value = targets }
        override fun onHidden(marker: CartesianMarker) { holder.value = emptyList() }
    }

/**
 * 押下（押して動かさずに離す）を、イベントを消費せずに拾う（Vico のマウス追従をそのまま生かすため、Initial の段で見るだけ）。
 * 座標は修飾子を付けた部品の左上が原点。
 */
private fun Modifier.tapWithoutConsuming(onTap: (Offset) -> Unit): Modifier = pointerInput(onTap) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitPointerEvent(PointerEventPass.Initial)
            if (down.type != PointerEventType.Press) continue
            val start = down.changes.firstOrNull()?.position ?: continue
            var moved = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: break
                if ((change.position - start).getDistance() > 8f) moved = true
                if (event.type == PointerEventType.Release) {
                    if (!moved) onTap(change.position)
                    break
                }
            }
        }
    }
}

/** 数の文字: 件数は整数、それ以外は DecimalFormat の形（既定は桁区切り・小数は 2 桁まで）。 */
internal fun chartNumberFormatter(integerAxis: Boolean, valueFormat: String?): (Double) -> String {
    val symbols = DecimalFormatSymbols(Locale.US)
    val format = runCatching { DecimalFormat(valueFormat ?: if (integerAxis) "#,##0" else "#,##0.##", symbols) }
        .getOrElse { DecimalFormat("#,##0.##", symbols) }
    return { v -> format.format(v) }
}

/**
 * 折れ線グラフ（P46）。横軸は項目の並び、系列ごとに 1 本の線。棒グラフと同じ軸・凡例・マウスの文字で、
 * ライブラリ（Vico）の型はこのファイルの外へ出さない。
 */
@Composable
fun LineChart(
    categories: List<String>,
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    title: String? = null,
    valueFormat: String? = null,
    /** 点を押したとき（項目の番号・いちばん近い点の系列の番号）。null なら押せない。 */
    onSelect: ((categoryIndex: Int, seriesIndex: Int?) -> Unit)? = null,
) {
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(categories, series) {
        producer.runTransaction {
            lineSeries { series.forEach { s -> series(s.values.ifEmpty { listOf(0.0) }) } }
            extras { it[CategoryKey] = categories }
        }
    }
    val numberText = remember(valueFormat) { chartNumberFormatter(integerAxis = false, valueFormat = valueFormat) }
    val targets = remember { mutableStateOf<List<CartesianMarker.Target>>(emptyList()) }
    val visibilityListener = remember { markerTargetsListener(targets) }
    val tapModifier = if (onSelect == null) Modifier else Modifier.tapWithoutConsuming tap@{ position ->
        val current = targets.value
        val x = current.firstOrNull()?.x?.toInt() ?: return@tap
        val points = current.filterIsInstance<LineCartesianLayerMarkerTarget>().flatMap { it.points }
        val nearest = points.indices.minByOrNull { kotlin.math.abs(points[it].canvasY - position.y) }
        onSelect(x, nearest)
    }
    val colors = series.map { it.color }.ifEmpty { listOf(Palette.Primary) }
    val theme = rememberM3VicoTheme(
        columnCartesianLayerColors = colors,
        lineCartesianLayerColors = colors,
        lineColor = Palette.Border,
        textColor = Palette.SubText,
    )
    val axisLabel = rememberTextComponent(style = TextStyle(color = Palette.SubText, fontSize = 11.sp))
    val legendLabel = rememberTextComponent(style = TextStyle(color = Palette.Text, fontSize = 11.sp))
    val legendIcons = series.map { s -> rememberShapeComponent(fill = Fill(SolidColor(s.color)), shape = RoundedCornerShape(2.dp)) }
    val markerLabel = rememberTextComponent(style = TextStyle(color = Palette.Text, fontSize = 11.sp))
    val markerFormatter = remember(series, categories, numberText) {
        object : DefaultCartesianMarker.ValueFormatter {
            override fun format(context: CartesianDrawingContext, targets: List<CartesianMarker.Target>): CharSequence {
                val label = targets.firstOrNull()?.let { categories.getOrNull(it.x.toInt()) }.orEmpty()
                val parts = targets.filterIsInstance<LineCartesianLayerMarkerTarget>().flatMap { it.points }
                    .mapIndexed { i, p -> (series.getOrNull(i)?.name ?: "") + " " + numberText(p.entry.y) }
                return (if (label.isNotEmpty()) "$label: " else "") + parts.joinToString("・")
            }
        }
    }
    val axisValueFormatter = remember(numberText) { CartesianValueFormatter { _, y, _ -> numberText(y) } }
    val lines = series.map { s -> LineCartesianLayer.rememberLine(fill = LineCartesianLayer.LineFill.single(Fill(SolidColor(s.color)))) }
    Column(modifier) {
        if (title != null) Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.SubText)
        ProvideVicoTheme(theme) {
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(lineProvider = LineCartesianLayer.LineProvider.series(lines)),
                    startAxis = VerticalAxis.rememberStart(label = axisLabel, valueFormatter = axisValueFormatter),
                    bottomAxis = HorizontalAxis.rememberBottom(
                        label = axisLabel,
                        valueFormatter = CartesianValueFormatter { context, x, _ ->
                            context.model.extraStore.getOrNull(CategoryKey)?.getOrNull(x.toInt()) ?: ""
                        },
                        guideline = null,
                    ),
                    marker = rememberDefaultCartesianMarker(label = markerLabel, valueFormatter = markerFormatter),
                    markerVisibilityListener = visibilityListener,
                    markerController = CartesianMarkerController.rememberShowOnHover(),
                    legend = if (series.size >= 2) rememberHorizontalLegend(
                        items = { _ -> series.forEachIndexed { i, s -> add(LegendItem(legendIcons[i], legendLabel, s.name)) } },
                        iconSize = 10.dp,
                    ) else null,
                ),
                modelProducer = producer,
                modifier = Modifier.fillMaxWidth().height(height).then(tapModifier),
                scrollState = rememberVicoScrollState(scrollEnabled = false),
                zoomState = rememberVicoZoomState(zoomEnabled = false),
            )
        }
    }
}

/**
 * 円グラフ（P46）。Vico に無いので自前で描く: 値の割合で扇形を並べ、右に凡例（項目・値・割合）を出す。
 * 値が 0 以下・読めない項目は描かず凡例に「—」で出す。
 */
@Composable
fun PieChart(
    categories: List<String>,
    values: List<Double?>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    title: String? = null,
    valueFormat: String? = null,
    /** 扇形（または凡例の行）を押したとき（項目の番号）。null なら押せない。 */
    onSelect: ((categoryIndex: Int) -> Unit)? = null,
) {
    val numberText = remember(valueFormat) { chartNumberFormatter(integerAxis = false, valueFormat = valueFormat) }
    val positive = values.map { v -> if (v != null && v > 0 && !v.isNaN()) v else 0.0 }
    val total = positive.sum()
    val separator = Palette.Surface  // 扇形の境目の線（描画スコープの外で読む）
    // 押した位置の扇形: 中心からの角度（上を 0 として時計回り）を、扇形の並びに当てる（描いた円の大きさは onSizeChanged で覚える）
    val pieCanvasSize = remember { mutableStateOf(0f) }
    val tapModifier = if (onSelect == null || total <= 0.0) Modifier else Modifier.tapWithoutConsuming tap@{ position ->
        val side = pieCanvasSize.value
        if (side <= 0f) return@tap
        val cx = side / 2f
        val cy = side / 2f
        val dx = position.x - cx
        val dy = position.y - cy
        if (kotlin.math.sqrt(dx * dx + dy * dy) > side / 2f) return@tap
        val degrees = ((Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())) + 90.0) + 360.0) % 360.0
        var start = 0.0
        positive.forEachIndexed { i, v ->
            val sweep = v / total * 360.0
            if (v > 0.0 && degrees >= start && degrees < start + sweep) { onSelect(i); return@tap }
            start += sweep
        }
    }
    Column(modifier) {
        if (title != null) Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.SubText)
        Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(height).padding(8.dp).then(tapModifier).onSizeChanged { pieCanvasSize.value = it.width.toFloat() }) {
                val diameter = min(size.width, size.height)
                val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
                if (total <= 0.0) {
                    drawArc(Color.Gray.copy(alpha = 0.25f), 0f, 360f, true, topLeft, Size(diameter, diameter))
                    return@Canvas
                }
                var start = -90f
                positive.forEachIndexed { i, v ->
                    if (v <= 0.0) return@forEachIndexed
                    val sweep = (v / total * 360.0).toFloat()
                    drawArc(colors[i % colors.size], start, sweep, true, topLeft, Size(diameter, diameter))
                    drawArc(separator, start, sweep, true, topLeft, Size(diameter, diameter), style = Stroke(width = 2f))
                    start += sweep
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 8.dp)) {
                categories.forEachIndexed { i, label ->
                    val v = values.getOrNull(i)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp).then(if (onSelect != null) Modifier.clickable { onSelect(i) } else Modifier),
                    ) {
                        Box(Modifier.size(10.dp).background(colors[i % colors.size], RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(6.dp))
                        Text(label, fontSize = 11.sp, color = Palette.Text, modifier = Modifier.weight(1f), maxLines = 1)
                        val share = if (v != null && v > 0 && total > 0) " (" + DecimalFormat("0.#", DecimalFormatSymbols(Locale.US)).format(v / total * 100) + "%)" else ""
                        Text((v?.let(numberText) ?: "—") + share, fontSize = 11.sp, color = Palette.SubText)
                    }
                }
            }
        }
    }
}
