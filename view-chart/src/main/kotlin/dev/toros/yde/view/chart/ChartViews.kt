package dev.toros.yde.view.chart

import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.semantic.ExtensionViewDef
import dev.toros.yde.semantic.ExtensionViewResult
import dev.toros.yde.semantic.SemanticExpression
import dev.toros.yde.semantic.ViewContext
import dev.toros.yde.semantic.VirtualCell
import dev.toros.yde.semantic.VirtualGridDef
import dev.toros.yde.semantic.VirtualRow
import dev.toros.yde.semantic.VirtualViews
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.scalarText

/**
 * チャート（`kind: chart`。P46）。元の表（グリッドかクロス集計）の解決結果を、項目（行）× 系列（列）の数に直して描く「ビューの部品」。
 * データを持たず、集計は元の表がする（Excel の「表を作ってからグラフにする」と同じ）。描画は画面だけで、CLI と read_view は数を返す。
 * 置き場は台帳のビュータブ・文書ビューの区画（`chart: {view}`）・ダッシュボードの区画（`view: チャートID`）。
 * 拡張の種類（ADR-0014）で、定義を読むのは ChartViewKind。ここは定義と解決の結果の型で、描画（desktop）が読むので公開する。
 */

/** 図の種類。解決（項目 × 系列の数）はどれも同じで、違いは描き方だけ。 */
enum class ChartKind(val id: String, val label: String) {
    BAR("bar", "棒"),
    STACKED_BAR("stacked-bar", "積み上げ棒"),
    HORIZONTAL_BAR("horizontal-bar", "横棒"),
    STACKED_HORIZONTAL_BAR("stacked-horizontal-bar", "積み上げ横棒"),
    LINE("line", "折れ線"),
    AREA("area", "面"),
    STACKED_AREA("stacked-area", "積み上げ面"),
    PIE("pie", "円"),
    SCATTER("scatter", "散布図"),
    ;

    /** 系列を積む図（積み上げ棒・積み上げ横棒・積み上げ面）。積むのは描く部品の中で、解決の数は元の値のまま。 */
    val stacked: Boolean get() = this == STACKED_BAR || this == STACKED_HORIZONTAL_BAR || this == STACKED_AREA

    companion object {
        fun parse(text: String?, where: String): ChartKind =
            entries.firstOrNull { it.id == text } ?: error("$where は ${entries.joinToString(" / ") { it.id }} のいずれかです: $text")
    }
}

/** 図の形（棒・点・扇形）を押したときの、材料（集めた要素）の一覧の見せ方。 */
enum class ChartDrill(val id: String, val label: String) {
    /** 押した場所に浮いた一覧を出す（既定）。 */
    POPUP("popup", "浮いた一覧"),
    /** 図の下に一覧を出す。 */
    BELOW("below", "図の下"),
    /** 図の右に一覧を出す。 */
    RIGHT("right", "図の右"),
    /** 押しても何も出さない。 */
    NONE("none", "出さない"),
    ;

    companion object {
        fun parse(text: String?, where: String): ChartDrill =
            entries.firstOrNull { it.id == text } ?: error("$where は ${entries.joinToString(" / ") { it.id }} のいずれかです: $text")
    }
}

/** 散布図の軸の種類（`scale`）。対数は値が桁で散るときに使い、0 以下の値は描けない。 */
enum class ChartScale(val id: String, val label: String) {
    LINEAR("linear", "線形"),
    LOG("log", "対数"),
    ;

    companion object {
        fun parse(text: String?, where: String): ChartScale =
            entries.firstOrNull { it.id == text } ?: error("$where は ${entries.joinToString(" / ") { it.id }} のいずれかです: $text")
    }
}

/** 項目の並び: 系列（null＝最初の系列）の値の順（descending＝多い順）。値の無い項目は後ろ。 */
data class ChartOrder(val series: String? = null, val descending: Boolean = false)

/** チャートの宣言。 */
data class VirtualChartDef(
    override val id: String,
    override val name: String,
    override val description: String? = null,
    override val source: DocumentId,
    val kind: ChartKind,
    /** 元の表（グリッドかクロス集計のビュー ID）。行＝横軸の項目、列＝系列。 */
    val view: String,
    /** 系列にする列の見出し（空＝数の列すべて。クロス集計なら合計と未分類の列を除く）。 */
    val series: List<String> = emptyList(),
    /** 横軸の項目の文字にする列の見出し（null＝行のタイトルか ID）。 */
    val x: String? = null,
    /** 縦軸（値）の見出し。 */
    val yLabel: String? = null,
    /** 値の表示の形（DecimalFormat のパターン。軸とマウスを載せたときの文字。null＝桁区切り）。 */
    val format: String? = null,
    /** 図の形を押したときの材料の一覧の見せ方（`drill`）。 */
    val drill: ChartDrill = ChartDrill.POPUP,
    /** 項目の並び（null＝元の表の行の順）。 */
    val order: ChartOrder? = null,
    /** 散布図で点の名前にする列の見出し（null＝行のタイトルか ID）。散布図では x が横軸の数の列なので、名前は別の列で指す。 */
    val label: String? = null,
    /** 散布図の軸の種類（`scale: {x, y}`）。 */
    val xScale: ChartScale = ChartScale.LINEAR,
    val yScale: ChartScale = ChartScale.LINEAR,
) : ExtensionViewDef {
    override val viewKind: String get() = ChartViewKind.id
    override val kindLabel: String get() = ChartViewKind.label

    override fun listing(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to id, "name" to name, "kind" to viewKind, "chart" to kind.id, "source" to view,
        "series" to series.takeIf { it.isNotEmpty() },
    ).filterValues { it != null }

    override fun listingText(): String = "チャート（${kind.label}） 元の表=$view" + (if (series.isEmpty()) "" else " 系列=" + series.joinToString("/"))

    /** 元の表（グリッドかクロス集計）を同じ索引・原本で解決し、項目（行）× 系列（列）の数にする。 */
    override fun resolve(context: ViewContext): ChartData {
        val source = context.grid(view) ?: error("チャート「$id」の元の表「$view」が見つかりません（グリッドかクロス集計のビュー ID を view に書きます）")
        return ChartViews.resolve(this, source, context.rows(source))
    }
}

/** 横軸の項目 1 つ（元の表の 1 行）。 */
data class ChartCategory(val key: String, val label: String, val rowId: String, val hasOrigin: Boolean)

/** 系列 1 つ（元の表の 1 列）。values は項目の並びと同じ長さ（数として読めない・無いセルは null）。 */
data class ChartSeriesResolved(val label: String, val values: List<Double?>, val cells: List<VirtualCell?>)

/** 解決したチャート。 */
data class ChartData(
    override val def: VirtualChartDef,
    val sourceView: VirtualGridDef,
    val categories: List<ChartCategory>,
    val series: List<ChartSeriesResolved>,
    /** 描くときの注意（系列の列が無い・円は最初の系列だけ、など。定義エラーではない）。 */
    val notes: List<String>,
    /** 散布図の横軸の値（項目ごと。数として読めない項目は null。散布図でなければ null）。 */
    val xValues: List<Double?>? = null,
) : ExtensionViewResult {
    override val count: String get() = categories.size.toString()
    override val unit: String get() = "項目"

    override fun report(errors: List<String>): Map<String, Any?> = ChartViews.report(this, errors)

    override fun text(): String = ChartViews.renderText(this)

    /**
     * 押した形の材料を集める: 対応するセル（項目 × 系列）の来歴の入力（集めた要素・計算の材料）を並べ、各入力の場所を含む
     * 最も内側の ID（索引から）を添える。表のセルを押したときと同じ材料で、業務詳細（ID）か原本（場所）へ進める。
     * seriesIndex が null ならその項目のすべての系列。
     */
    fun selection(categoryIndex: Int, seriesIndex: Int?, index: IdIndex): ChartSelection? = ChartSelections.select(this, categoryIndex, seriesIndex, index)
}

internal object ChartViews {

    /**
     * 元の表の解決結果からチャートの数を取り出す。クロス集計の合計の行・合計と未分類の列は既定で除く（series に書けば入る）。
     * 系列を書かなければ、数として読めるセルを持つ列すべて（被参照の列は除く）。
     */
    fun resolve(def: VirtualChartDef, source: VirtualGridDef, rows: List<VirtualRow>): ChartData {
        val matrix = source.matrix
        val dataRows = rows.filter { r -> !(matrix != null && matrix.totals && r.id == VirtualViews.TOTAL_LABEL && r.occurrences.isEmpty() && !r.valueRow) }
        val columns = VirtualViews.columnsOf(source, rows)
        val excluded = buildSet {
            if (matrix != null) {
                if (matrix.totals) add(VirtualViews.TOTAL_LABEL)
                matrix.unclassified?.let { add(it) }
            }
        }
        val notes = mutableListOf<String>()
        val scatter = def.kind == ChartKind.SCATTER
        fun columnIndex(label: String, what: String): Int? =
            columns.indexOfFirst { it.label == label }.takeIf { it >= 0 } ?: run { notes += "${what}の列「$label」が元の表「${source.id}」にありません"; null }
        // 散布図: x は横軸の数の列（系列には入れない）、点の名前は label の列。他の図: x は項目の文字の列
        val xIndex = def.x?.let { columnIndex(it, "横軸") }
        val labelIndex = def.label?.let { columnIndex(it, "名前") }
        val seriesIndexes: List<Int> = if (def.series.isEmpty()) {
            columns.indices.filter { i ->
                val c = columns[i]
                c.backlinks == null && c.label !in excluded && !(scatter && i == xIndex) && dataRows.any { r -> numberOf(r.cells.getOrNull(i)) != null }
            }
        } else {
            def.series.mapNotNull { columnIndex(it, "系列") }
        }
        val nameIndex = if (scatter) labelIndex else xIndex
        val categories = dataRows.map { r ->
            val label = nameIndex?.let { i -> r.cells.getOrNull(i)?.value?.let { VirtualViews.cellText(it, 40) } } ?: r.title ?: r.id
            ChartCategory(r.key, label, r.id, r.occurrences.isNotEmpty())
        }
        val xValues: List<Double?>? = if (!scatter) null else xIndex?.let { i -> dataRows.map { r -> numberOf(r.cells.getOrNull(i)) } } ?: dataRows.map { null }
        if (scatter && xIndex != null && xValues != null && xValues.any { it == null }) notes += "横軸の値が数として読めない項目があります（その点は描きません）"
        val series = seriesIndexes.map { i ->
            ChartSeriesResolved(columns[i].label, dataRows.map { r -> numberOf(r.cells.getOrNull(i)) }, dataRows.map { r -> r.cells.getOrNull(i) })
        }
        if (series.isEmpty()) notes += "数の列がありません（系列にする列を series に書きます）"
        if (def.kind == ChartKind.PIE && series.size > 1) notes += "円グラフは最初の系列「${series.first().label}」だけを描きます"
        // 対数の軸: 0 以下の値は描けない（数はそのまま返し、注意に出す）
        if (scatter) {
            val nonPositiveX = if (def.xScale == ChartScale.LOG) xValues.orEmpty().count { it != null && it <= 0.0 } else 0
            val nonPositiveY = if (def.yScale == ChartScale.LOG) series.sumOf { s -> s.values.count { it != null && it <= 0.0 } } else 0
            if (nonPositiveX + nonPositiveY > 0) notes += "対数の軸では 0 以下の値は描きません（${nonPositiveX + nonPositiveY} 点）"
        }
        // 項目の並び（order）: 系列の値の順に項目を並べ替える（系列と材料も同じ順に）。値の無い項目は後ろ
        val order = def.order
        if (order == null || series.isEmpty() || scatter) return ChartData(def, source, categories, series, notes, xValues)
        val keyIndex = order.series?.let { label -> series.indexOfFirst { it.label == label } }?.also {
            if (it < 0) notes += "並びの系列「${order.series}」はこのチャートの系列にありません（最初の系列で並べます）"
        }?.takeIf { it >= 0 } ?: 0
        val key = series[keyIndex].values
        val permutation = categories.indices.sortedWith(
            compareBy<Int> { key[it] == null }.thenComparator { a, b ->
                val x = key[a]
                val y = key[b]
                when {
                    x == null || y == null -> 0
                    order.descending -> y.compareTo(x)
                    else -> x.compareTo(y)
                }
            },
        )
        return ChartData(
            def, source,
            permutation.map { categories[it] },
            series.map { s -> ChartSeriesResolved(s.label, permutation.map { s.values[it] }, permutation.map { s.cells[it] }) },
            notes,
        )
    }

    private fun numberOf(cell: VirtualCell?): Double? = scalarText(cell?.value)?.let { SemanticExpression.numberOf(it) }?.toDouble()

    /** CLI と read_view の出力（数のまま。描画は画面だけ）。 */
    fun report(data: ChartData, errors: List<String> = emptyList()): Map<String, Any?> = linkedMapOf(
        "view" to data.def.id,
        "kind" to "chart",
        "chart" to data.def.kind.id,
        "name" to data.def.name,
        "source" to data.sourceView.id,
        "x" to data.def.x,
        "label" to data.def.label,
        "scale" to linkedMapOf("x" to data.def.xScale.id, "y" to data.def.yScale.id),
        "y" to data.def.yLabel,
        "drill" to data.def.drill.id,
        "order" to data.def.order?.let { linkedMapOf("series" to it.series, "direction" to if (it.descending) "desc" else "asc") },
        "categories" to data.categories.mapIndexed { i, c ->
            linkedMapOf<String, Any?>("key" to c.key, "label" to c.label, "id" to c.rowId).also { m ->
                // 散布図: 項目ごとの横軸の値
                data.xValues?.let { xs -> m["x"] = xs.getOrNull(i)?.let(::plainNumber) }
            }
        },
        "series" to data.series.map { s -> linkedMapOf("label" to s.label, "values" to s.values.map { v -> v?.let(::plainNumber) }) },
        "notes" to data.notes,
        "errors" to errors,
    )

    /** エージェントへの開示テキスト（項目ごとに系列の値を並べる）。 */
    fun renderText(data: ChartData): String = buildString {
        appendLine("チャート: ${data.def.name}（${data.def.id}・${data.def.kind.label}・元の表: ${data.sourceView.id}）")
        data.def.description?.let { appendLine(it.trim()) }
        appendLine("項目: ${data.categories.size} 件、系列: ${data.series.joinToString("・") { it.label }.ifEmpty { "なし" }}")
        data.categories.forEachIndexed { i, c ->
            append("- ").append(c.label).append(if (c.label != c.rowId) "（${c.rowId}）" else "").append(": ")
            data.xValues?.getOrNull(i)?.let { append("x=").append(plainNumber(it)).append("・") }
            appendLine(data.series.joinToString("・") { s -> s.label + "=" + (s.values.getOrNull(i)?.let(::plainNumber)?.toString() ?: "—") })
        }
        data.notes.forEach { appendLine("注意: $it") }
    }

    /** 整数なら整数で見せる（5400.0 → 5400）。 */
    private fun plainNumber(v: Double): Any = if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) v.toLong() else v
}
