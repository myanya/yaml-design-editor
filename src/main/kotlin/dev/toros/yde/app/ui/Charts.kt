package dev.toros.yde.app.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
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
) {
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(categories, series) {
        producer.runTransaction {
            columnSeries { series.forEach { s -> series(s.values.ifEmpty { listOf(0.0) }) } }
            extras { it[CategoryKey] = categories }
        }
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
                    .map { c -> (series.getOrNull(c.entry.seriesIndex)?.name ?: "") + " " + c.entry.y.toInt() }
                return (if (label.isNotEmpty()) "$label: " else "") + parts.joinToString("・")
            }
        }
    }
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
                        // 件数の軸は 1 刻み
                        startAxis = VerticalAxis.rememberStart(label = axisLabel, itemPlacer = VerticalAxis.ItemPlacer.step({ 1.0 })),
                        bottomAxis = HorizontalAxis.rememberBottom(
                            label = axisLabel,
                            valueFormatter = CartesianValueFormatter { context, x, _ ->
                                context.model.extraStore.getOrNull(CategoryKey)?.getOrNull(x.toInt()) ?: ""
                            },
                            guideline = null,
                        ),
                        marker = rememberDefaultCartesianMarker(label = markerLabel, valueFormatter = markerFormatter),
                        markerController = CartesianMarkerController.rememberShowOnHover(),
                        legend = if (series.size >= 2) rememberHorizontalLegend(
                            items = { _ ->
                                series.forEachIndexed { i, s -> add(LegendItem(legendIcons[i], legendLabel, s.name)) }
                            },
                            iconSize = 10.dp,
                        ) else null,
                    ),
                    modelProducer = producer,
                    modifier = Modifier.fillMaxWidth().height(height),
                    scrollState = rememberVicoScrollState(scrollEnabled = false),
                    zoomState = rememberVicoZoomState(zoomEnabled = false),
                )
            }
        }
    }
}
