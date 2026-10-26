package dev.toros.yde.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
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
 * ライブラリ（Vico）の型はこのファイルの外へ出さない。`area` で線の下を塗り（面）、`stacked` で系列を下から積んだ帯にする
 * （積み上げ面。描く値は積んだ値だが、マウスの文字と押したときの系列は元の値で決める）。
 */
@Composable
fun LineChart(
    categories: List<String>,
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    title: String? = null,
    valueFormat: String? = null,
    /** 線の下を塗る（面）。 */
    area: Boolean = false,
    /** 系列を下から積んで帯にする（積み上げ面）。 */
    stacked: Boolean = false,
    /** 点を押したとき（項目の番号・いちばん近い点の系列の番号。積み上げなら押した高さの帯の系列）。null なら押せない。 */
    onSelect: ((categoryIndex: Int, seriesIndex: Int?) -> Unit)? = null,
) {
    // 描く順: 積み上げは上の帯（積んだ値が大きい系列）から描き、下の帯を後から重ねる（塗りが 0 まで届くため）
    val drawOrder = remember(series, stacked) { if (stacked) series.indices.reversed().toList() else series.indices.toList() }
    val drawnValues = remember(series, stacked) {
        if (!stacked) series.map { it.values } else {
            val n = series.maxOfOrNull { it.values.size } ?: 0
            val cumulative = mutableListOf<List<Double>>()
            var below = List(n) { 0.0 }
            series.forEach { s ->
                below = List(n) { i -> below[i] + (s.values.getOrNull(i)?.takeIf { v -> !v.isNaN() && v > 0 } ?: 0.0) }
                cumulative += below
            }
            cumulative
        }
    }
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(categories, series, drawOrder, drawnValues) {
        producer.runTransaction {
            lineSeries { drawOrder.forEach { si -> series(drawnValues[si].ifEmpty { listOf(0.0) }) } }
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
        if (points.isEmpty()) return@tap
        // 点は描いた順（drawOrder）に並ぶ。系列の番号へ戻す
        fun pointOf(si: Int) = points.getOrNull(drawOrder.indexOf(si))
        val nearest = series.indices.minByOrNull { si -> pointOf(si)?.let { p -> kotlin.math.abs(p.canvasY - position.y) } ?: Float.MAX_VALUE }
        // 積み上げなら、押した高さがどの帯（下の帯の上端〜自分の上端）に入るか。値 0 の帯は飛ばす
        val hit = if (!stacked) nearest else series.indices.firstOrNull { si ->
            (series[si].values.getOrNull(x) ?: 0.0) > 0.0 && (pointOf(si)?.canvasY ?: Float.MAX_VALUE) <= position.y
        } ?: nearest
        onSelect(x, hit)
    }
    val colors = drawOrder.map { series[it].color }.ifEmpty { listOf(Palette.Primary) }
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
    val markerFormatter = remember(series, categories, numberText, drawOrder, stacked) {
        object : DefaultCartesianMarker.ValueFormatter {
            override fun format(context: CartesianDrawingContext, targets: List<CartesianMarker.Target>): CharSequence {
                val x = targets.firstOrNull()?.x?.toInt() ?: 0
                val label = categories.getOrNull(x).orEmpty()
                // 点は描いた順（drawOrder）。文字は系列の順に、積み上げなら積んだ値でなく元の値で
                val parts = targets.filterIsInstance<LineCartesianLayerMarkerTarget>().flatMap { it.points }
                    .mapIndexed { k, p ->
                        val si = drawOrder.getOrNull(k) ?: k
                        val value = if (stacked) series.getOrNull(si)?.values?.getOrNull(x) ?: p.entry.y else p.entry.y
                        si to ((series.getOrNull(si)?.name ?: "") + " " + numberText(value))
                    }
                    .sortedBy { it.first }.map { it.second }
                return (if (label.isNotEmpty()) "$label: " else "") + parts.joinToString("・")
            }
        }
    }
    val axisValueFormatter = remember(numberText) { CartesianValueFormatter { _, y, _ -> numberText(y) } }
    val lines = drawOrder.map { si ->
        val color = series[si].color
        // 面: 線の下を塗る。積み上げは帯が重ならないよう濃く、1 本の面は薄く
        if (area) LineCartesianLayer.rememberLine(
            fill = LineCartesianLayer.LineFill.single(Fill(SolidColor(color))),
            areaFill = LineCartesianLayer.AreaFill.single(Fill(SolidColor(color.copy(alpha = if (stacked) 0.8f else 0.3f)))),
        ) else LineCartesianLayer.rememberLine(fill = LineCartesianLayer.LineFill.single(Fill(SolidColor(color))))
    }
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
 * 横棒グラフ（P46 の残り）。Vico に横向きの棒が無いので自前で描く: 項目ごとに 1 行、左に項目名、右へ棒を伸ばし、棒の端に値を出す。
 * 系列が 2 つ以上なら積み上げ（`stacked`）か、項目の中に系列ごとの細い棒を並べる。行の高さは表示の高さと項目数から決め、
 * 収まらなければ縦にスクロールする。棒（積み上げなら区間）を押すと項目と系列が決まる。
 */
@Composable
fun HorizontalBarChart(
    categories: List<String>,
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
    height: Dp = 220.dp,
    title: String? = null,
    valueFormat: String? = null,
    /** 棒を押したとき（項目の番号・系列の番号。1 系列と積み上げでない並べた棒では、押した棒の系列）。null なら押せない。 */
    onSelect: ((categoryIndex: Int, seriesIndex: Int?) -> Unit)? = null,
) {
    val numberText = remember(valueFormat) { chartNumberFormatter(integerAxis = false, valueFormat = valueFormat) }
    val n = categories.size
    fun positive(v: Double?): Double = if (v == null || v.isNaN() || v < 0) 0.0 else v
    // 目盛りの最大: 積み上げなら項目ごとの和の最大、並べるなら値の最大
    val single = stacked || series.size <= 1
    val maxValue = remember(series, single) {
        if (single) categories.indices.maxOfOrNull { i -> series.sumOf { positive(it.values.getOrNull(i)) } } ?: 0.0
        else series.maxOfOrNull { s -> s.values.maxOfOrNull { positive(it) } ?: 0.0 } ?: 0.0
    }
    val rowHeight = if (n == 0) 20.dp else (height / n).coerceIn(18.dp, 30.dp)
    val barHeight = if (single) rowHeight - 6.dp else ((rowHeight - 6.dp) / series.size).coerceAtLeast(4.dp)
    val endShape = RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)
    Column(modifier) {
        if (title != null) Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.SubText)
        if (series.size >= 2) ChartLegend(series.map { it.name to it.color })
        Column(Modifier.fillMaxWidth().heightIn(max = height).verticalScroll(rememberScrollState())) {
            categories.forEachIndexed { i, label ->
                Row(Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label, fontSize = 11.sp, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End, modifier = Modifier.width(150.dp).padding(end = 8.dp),
                    )
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                        // 値の文字の分（右端）を空けて、最大値が収まる幅に比例させる
                        val usable = (maxWidth - 72.dp).coerceAtLeast(0.dp)
                        fun widthOf(v: Double): Dp = if (maxValue <= 0.0) 0.dp else usable * (positive(v) / maxValue).toFloat()
                        if (single) {
                            val total = series.sumOf { positive(it.values.getOrNull(i)) }
                            Row(Modifier.fillMaxWidth().height(barHeight).align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                                series.forEachIndexed { si, s ->
                                    val w = widthOf(positive(s.values.getOrNull(i)))
                                    if (w > 0.dp) {
                                        Box(
                                            Modifier.width(w).fillMaxHeight()
                                                .background(s.color, if (series.size == 1 || si == series.lastIndex) endShape else RoundedCornerShape(0.dp))
                                                .then(if (onSelect != null) Modifier.clickable { onSelect(i, if (stacked) si else 0) } else Modifier),
                                        )
                                        if (si < series.lastIndex) Spacer(Modifier.width(1.dp))
                                    }
                                }
                                Spacer(Modifier.width(4.dp))
                                Text(numberText(total), fontSize = 10.sp, color = Palette.SubText, maxLines = 1)
                            }
                        } else {
                            Column(Modifier.fillMaxWidth().align(Alignment.CenterStart), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                series.forEachIndexed { si, s ->
                                    val v = positive(s.values.getOrNull(i))
                                    Row(Modifier.fillMaxWidth().height(barHeight), verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            Modifier.width(widthOf(v)).fillMaxHeight().background(s.color, endShape)
                                                .then(if (onSelect != null) Modifier.clickable { onSelect(i, si) } else Modifier),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(numberText(v), fontSize = 9.sp, color = Palette.SubText, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 自前で描く図の凡例（色の箱と系列の名前を横に並べる）。 */
@Composable
private fun ChartLegend(items: List<Pair<String, Color>>) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { (name, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(4.dp))
                Text(name, fontSize = 11.sp, color = Palette.Text, maxLines = 1)
            }
        }
    }
}

/** 散布図の 1 系列。値は項目の並びと同じ長さ（数として読めない項目は null＝描かない）。 */
class ScatterSeries(val name: String, val values: List<Double?>, val color: Color)

/**
 * 散布図の目盛り。線形は 1・2・5 の刻みで両端を刻みに揃え、値が全部 0 以上なら 0 から。
 * 対数（`log`）は 10 の累乗を両端にし、目盛りは累乗（桁が少なければ 2 倍と 5 倍も）。0 以下の値は位置を持たない。
 */
private class ScatterAxis(val lo: Double, val hi: Double, val step: Double, val log: Boolean) {
    /** 軸上の位置（0〜1）。対数なら log10 の等間隔。 */
    fun fraction(v: Double): Float =
        if (log) ((Math.log10(v) - Math.log10(lo)) / (Math.log10(hi) - Math.log10(lo))).toFloat() else ((v - lo) / (hi - lo)).toFloat()

    fun ticks(): List<Double> = if (!log) generateSequence(lo) { it + step }.takeWhile { it <= hi + step / 2 }.toList() else {
        val decades = generateSequence(lo) { it * 10 }.takeWhile { it <= hi * 1.0001 }.toList()
        if (decades.size > 4) decades else decades.flatMap { d -> listOf(d, d * 2, d * 5) }.filter { it <= hi * 1.0001 }
    }

    companion object {
        fun of(values: List<Double>, log: Boolean): ScatterAxis {
            if (log) {
                val positive = values.filter { it > 0.0 }
                val min = positive.minOrNull() ?: 1.0
                val max = positive.maxOrNull() ?: 10.0
                val lo = Math.pow(10.0, Math.floor(Math.log10(min)))
                val hi = Math.pow(10.0, Math.ceil(Math.log10(max))).let { if (it <= lo) lo * 10 else it }
                return ScatterAxis(lo, hi, 0.0, true)
            }
            val min = values.minOrNull() ?: 0.0
            val max = values.maxOrNull() ?: 1.0
            val a = if (min >= 0.0) 0.0 else min
            val b = if (max <= a) a + 1.0 else max
            val rough = (b - a) / 5
            val magnitude = Math.pow(10.0, Math.floor(Math.log10(rough)))
            val ratio = rough / magnitude
            val step = (if (ratio <= 1.0) 1.0 else if (ratio <= 2.0) 2.0 else if (ratio <= 5.0) 5.0 else 10.0) * magnitude
            return ScatterAxis(Math.floor(a / step) * step, Math.ceil(b / step) * step, step, false)
        }
    }
}

private data class ScatterPoint(val category: Int, val series: Int, val x: Double, val y: Double)

/**
 * 散布図（P46 の残り）。元の表の 2 つの数の列を軸にし、行を点にする。Vico の折れ線は横軸が項目の並びなので自前で描く:
 * 目盛りは 1・2・5 の刻み、点は系列ごとの色、マウスを載せると近い点の名前と値、押すとその点（項目と系列）が決まる。
 */
@Composable
fun ScatterChart(
    categories: List<String>,
    /** 項目ごとの横軸の値（数として読めない項目は null）。 */
    xs: List<Double?>,
    series: List<ScatterSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    title: String? = null,
    /** 横軸の見出し（列の名前）。 */
    xLabel: String? = null,
    valueFormat: String? = null,
    /** 軸を対数にする（値が桁で散るとき）。対数の軸では 0 以下の値は描かない。 */
    xLog: Boolean = false,
    yLog: Boolean = false,
    /** 点を押したとき（項目の番号・系列の番号）。null なら押せない。 */
    onSelect: ((categoryIndex: Int, seriesIndex: Int?) -> Unit)? = null,
) {
    val yText = remember(valueFormat) { chartNumberFormatter(integerAxis = false, valueFormat = valueFormat) }
    val xText = remember { chartNumberFormatter(integerAxis = false, valueFormat = null) }
    val points = remember(categories, xs, series, xLog, yLog) {
        series.flatMapIndexed { si, s ->
            categories.indices.mapNotNull { i ->
                val x = xs.getOrNull(i)
                val y = s.values.getOrNull(i)
                if (x == null || y == null || x.isNaN() || y.isNaN() || (xLog && x <= 0.0) || (yLog && y <= 0.0)) null else ScatterPoint(i, si, x, y)
            }
        }
    }
    val xAxis = remember(points, xLog) { ScatterAxis.of(points.map { it.x }, xLog) }
    val yAxis = remember(points, yLog) { ScatterAxis.of(points.map { it.y }, yLog) }
    val density = LocalDensity.current.density
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Palette.SubText, fontSize = 10.sp)
    val tipStyle = TextStyle(color = Palette.Text, fontSize = 11.sp)
    val canvasSize = remember { mutableStateOf(IntSize.Zero) }
    val hover = remember { mutableStateOf<Offset?>(null) }
    // 余白（px）: 左＝縦軸の文字、下＝横軸の文字（見出しがあればもう 1 行）
    fun plotRect(size: IntSize): Rect = Rect(
        left = 56f * density, top = 8f * density,
        right = size.width - 12f * density, bottom = size.height - (if (xLabel != null) 40f else 24f) * density,
    )
    fun px(plot: Rect, x: Double): Float = plot.left + xAxis.fraction(x) * plot.width
    fun py(plot: Rect, y: Double): Float = plot.bottom - yAxis.fraction(y) * plot.height
    fun nearest(position: Offset): ScatterPoint? {
        val size = canvasSize.value
        if (size.width <= 0 || size.height <= 0 || points.isEmpty()) return null
        val plot = plotRect(size)
        fun distance(p: ScatterPoint) = (Offset(px(plot, p.x), py(plot, p.y)) - position).getDistance()
        return points.minByOrNull(::distance)?.takeIf { distance(it) <= 14f * density }
    }
    val tapModifier = if (onSelect == null) Modifier else Modifier.tapWithoutConsuming { position -> nearest(position)?.let { onSelect(it.category, it.series) } }
    val colors = series.map { it.color }.ifEmpty { listOf(Palette.Primary) }
    Column(modifier) {
        if (title != null) Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.SubText)
        if (series.size >= 2) ChartLegend(series.map { it.name to it.color })
        Canvas(
            Modifier.fillMaxWidth().height(height).onSizeChanged { canvasSize.value = it }
                .hoverPosition { hover.value = it }.then(tapModifier),
        ) {
            val plot = plotRect(IntSize(size.width.toInt(), size.height.toInt()))
            val gridColor = Palette.Border.copy(alpha = 0.6f)
            // 目盛りの線と文字
            yAxis.ticks().forEach { v ->
                val y = py(plot, v)
                drawLine(gridColor, Offset(plot.left, y), Offset(plot.right, y), strokeWidth = 1f)
                val m = textMeasurer.measure(yText(v), labelStyle)
                drawText(m, topLeft = Offset(plot.left - 6f * density - m.size.width, y - m.size.height / 2f))
            }
            xAxis.ticks().forEach { v ->
                val x = px(plot, v)
                drawLine(gridColor, Offset(x, plot.top), Offset(x, plot.bottom), strokeWidth = 1f)
                val m = textMeasurer.measure(xText(v), labelStyle)
                drawText(m, topLeft = Offset(x - m.size.width / 2f, plot.bottom + 4f * density))
            }
            drawLine(Palette.Border, Offset(plot.left, plot.top), Offset(plot.left, plot.bottom), strokeWidth = 1f)
            drawLine(Palette.Border, Offset(plot.left, plot.bottom), Offset(plot.right, plot.bottom), strokeWidth = 1f)
            if (xLabel != null) {
                val m = textMeasurer.measure(xLabel, labelStyle)
                drawText(m, topLeft = Offset(plot.right - m.size.width, plot.bottom + 20f * density))
            }
            // 点
            val radius = 4f * density
            points.forEach { p -> drawCircle(colors[p.series % colors.size], radius, Offset(px(plot, p.x), py(plot, p.y))) }
            // マウスを載せた点: 輪で強調し、名前と値を添える（右に出ないなら左へ）
            val near = hover.value?.let(::nearest) ?: return@Canvas
            val center = Offset(px(plot, near.x), py(plot, near.y))
            drawCircle(colors[near.series % colors.size], radius + 3f * density, center, style = Stroke(width = 2f * density))
            val text = categories.getOrNull(near.category).orEmpty() + ": " + series[near.series].name + " " + yText(near.y) +
                "（" + (xLabel ?: "x") + " " + xText(near.x) + "）"
            val m = textMeasurer.measure(text, tipStyle)
            val pad = 4f * density
            var left = center.x + 10f * density
            if (left + m.size.width + pad * 2 > size.width) left = (center.x - 10f * density - m.size.width - pad * 2).coerceAtLeast(0f)
            val top = (center.y - m.size.height - 10f * density).coerceAtLeast(0f)
            drawRect(Palette.Surface, Offset(left, top), Size(m.size.width + pad * 2, m.size.height + pad * 2))
            drawRect(Palette.Border, Offset(left, top), Size(m.size.width + pad * 2, m.size.height + pad * 2), style = Stroke(width = 1f))
            drawText(m, topLeft = Offset(left + pad, top + pad))
        }
    }
}

/** マウスの位置（載せている間）を拾う。イベントは消費しない（押下は tapWithoutConsuming が見る）。 */
private fun Modifier.hoverPosition(onMove: (Offset?) -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            when (event.type) {
                PointerEventType.Move, PointerEventType.Enter -> onMove(event.changes.firstOrNull()?.position)
                PointerEventType.Exit -> onMove(null)
                else -> Unit
            }
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
