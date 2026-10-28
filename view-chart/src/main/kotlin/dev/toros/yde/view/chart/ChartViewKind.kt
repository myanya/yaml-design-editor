package dev.toros.yde.view.chart

import dev.toros.yde.semantic.ExtensionViewDef
import dev.toros.yde.semantic.VirtualGridDef
import dev.toros.yde.semantic.VirtualViewDefinition
import dev.toros.yde.workspace.ViewKind
import dev.toros.yde.workspace.WorkspaceSchema
import dev.toros.yde.yaml.model.DocumentId

/**
 * チャート（`kind: chart`）の差し込み口の実装（ADR-0014）。入口の登録の一覧（desktop/ext/DesktopExtensions.kt・cli/CliExtensions.kt）に並べて使う。
 * 定義の形の断片は資源 view-chart/schema.yaml で、ビューのメタスキーマに合成される。
 */
object ChartViewKind : ViewKind {
    override val id: String = "chart"
    override val label: String = "チャート"
    override val sectionKey: String = "chart"
    override val inDashboard: Boolean = true

    override val schemaFragment: String by lazy {
        requireNotNull(ChartViewKind::class.java.getResourceAsStream("/view-chart/schema.yaml")) { "view-chart の断片（資源 view-chart/schema.yaml）が見つかりません" }
            .use { it.readBytes().decodeToString() }
    }

    private val keys = setOf("id", "name", "description", "kind", "chart", "view", "series", "x", "y", "drill", "order", "label", "scale")

    /**
     * 図の種類（chart）・元の表（view）・系列にする列（series）・横軸の列（x。散布図では数の列）・点の名前の列（label。散布図）・縦軸の見出しと形（y）。
     * 元の表との整合（存在・列の有無）は lint で点検する。
     */
    override fun parse(raw: Map<*, *>, source: DocumentId): VirtualChartDef {
        raw.keys.firstOrNull { it !in keys }?.let { error("kind: chart では使えないキーです: $it（使えるキー: ${keys.joinToString(", ")}）") }
        fun text(key: String): String? = if (!raw.containsKey(key)) null else
            (raw[key] as? String)?.takeIf { it.isNotBlank() } ?: error("$key は空でない文字列で書きます")
        val id = text("id") ?: error("id が必要です")
        val kind = ChartKind.parse(text("chart") ?: error("chart（図の種類: ${ChartKind.entries.joinToString(" / ") { it.id }}）が必要です"), "chart")
        val view = text("view") ?: error("view（元の表＝グリッドかクロス集計のビュー ID）が必要です")
        val x = text("x")
        // 散布図: x は横軸の数の列（必須）、label は点の名前の列。他の図では x が項目の文字で、label は書けない
        val label = text("label")
        if (kind == ChartKind.SCATTER && x == null) error("chart: scatter には x（横軸にする数の列）が必要です")
        if (label != null && kind != ChartKind.SCATTER) error("label（点の名前の列）は chart: scatter で使います（他の図では x が項目の文字です）")
        // 軸の種類（散布図だけ）: {x: linear | log, y: linear | log}
        val scale = raw["scale"]?.let { s ->
            val sm = s as? Map<*, *> ?: error("scale は {x: linear | log, y: linear | log} で書きます")
            if (kind != ChartKind.SCATTER) error("scale（軸の種類）は chart: scatter で使います")
            sm.keys.firstOrNull { it != "x" && it != "y" }?.let { error("scale に書けるキーは x / y です: $it") }
            ChartScale.parse(sm["x"]?.toString() ?: "linear", "scale.x") to ChartScale.parse(sm["y"]?.toString() ?: "linear", "scale.y")
        }
        val series = when (val s = raw["series"]) {
            null -> emptyList()
            is List<*> -> s.map { it?.toString()?.takeIf { t -> t.isNotBlank() } ?: error("series は列の見出しの配列で書きます") }
            else -> error("series は列の見出しの配列で書きます")
        }
        val y = raw["y"]?.let { it as? Map<*, *> ?: error("y は {label, format} で書きます") }
        y?.keys?.firstOrNull { it != "label" && it != "format" }?.let { error("y に書けるキーは label / format です: $it") }
        val format = y?.get("format")?.toString()?.takeIf { it.isNotBlank() }?.also { f ->
            require(f.any { it == '0' || it == '#' } && runCatching { java.text.DecimalFormat(f) }.isSuccess) { "y.format は数の形（DecimalFormat のパターン。例 #,##0）で書きます: $f" }
        }
        // 押したときの材料の見せ方（省略時は浮いた一覧）
        val drill = if (!raw.containsKey("drill")) ChartDrill.POPUP else ChartDrill.parse(raw["drill"]?.toString(), "drill")
        // 項目の並び: {series: 列の見出し（省略＝最初の系列）, direction: asc | desc}
        val order = raw["order"]?.let { o ->
            val om = o as? Map<*, *> ?: error("order は {series, direction} で書きます（例 {direction: desc}）")
            om.keys.firstOrNull { it != "series" && it != "direction" }?.let { error("order に書けるキーは series / direction です: $it") }
            val direction = om["direction"]?.toString() ?: "asc"
            require(direction == "asc" || direction == "desc") { "order.direction は asc / desc で書きます: $direction" }
            ChartOrder(series = om["series"]?.toString()?.takeIf { it.isNotBlank() }, descending = direction == "desc")
        }
        return VirtualChartDef(
            id = id, name = text("name") ?: id, description = text("description"), source = source,
            kind = kind, view = view, series = series, x = x, label = label,
            xScale = scale?.first ?: ChartScale.LINEAR, yScale = scale?.second ?: ChartScale.LINEAR,
            yLabel = y?.get("label")?.toString()?.takeIf { it.isNotBlank() }, format = format, drill = drill, order = order,
        )
    }

    /** 元の表が一意なグリッドかクロス集計であること。系列と横軸の列は、列が宣言で決まるグリッドだけ点検する。 */
    override fun lint(def: ExtensionViewDef, all: List<VirtualViewDefinition>, schemas: List<WorkspaceSchema>): List<String> {
        val chart = def as VirtualChartDef
        val source = all.filter { it.id == chart.view }.singleOrNull() as? VirtualGridDef
            ?: return listOf("チャート「${chart.id}」の元の表（view）「${chart.view}」は一意なグリッドまたはクロス集計を参照してください")
        if (source.matrix != null || source.columns.any { it.each != null }) return emptyList()
        return (chart.series + listOfNotNull(chart.x, chart.label, chart.order?.series)).filterNot { label -> source.columns.any { it.label == label } }
            .map { "チャート「${chart.id}」の列「$it」は元の表「${source.id}」にありません" }
    }
}
