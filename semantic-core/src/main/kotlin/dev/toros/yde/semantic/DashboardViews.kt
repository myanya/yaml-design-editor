package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.ActionRequire
import dev.toros.yde.semantic.schema.ValueExpr
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import java.time.LocalDate

/** 区画のタイルの並べ方（`placement`）。auto は画面の幅と区画の数から画面が決める。 */
enum class DashboardPlacement(val id: String, val label: String) {
    AUTO("auto", "自動"),
    HORIZONTAL("horizontal", "横（折り返し）"),
    VERTICAL("vertical", "縦（左の一覧）"),
    ;

    companion object {
        fun parse(text: String?, where: String): DashboardPlacement =
            entries.firstOrNull { it.id == text } ?: error("$where は ${entries.joinToString(" / ") { it.id }} のいずれかです: $text")
    }
}

/** 業務の入口。原本を持たず、既存グリッドと操作の参照だけを合成する。 */
data class VirtualDashboardDef(
    override val id: String,
    override val name: String,
    override val description: String? = null,
    override val source: DocumentId,
    val sections: List<DashboardSection>,
    val actions: List<String> = emptyList(),
    val default: Boolean = false,
    /** 区画のタイルの並べ方。 */
    val placement: DashboardPlacement = DashboardPlacement.AUTO,
) : VirtualViewDefinition

data class DashboardSection(
    val id: String,
    val label: String,
    val view: String,
    /** 操作の前提と同じ、パラメータを受け取る Query / Predicate の宣言を再利用する。 */
    val filter: ActionRequire? = null,
    /** 群の名前（同じ名前の区画を見出し付きでまとめ、畳める。null＝無名の群で先頭に並ぶ）。 */
    val group: String? = null,
)

/** 区画を群ごとに分ける。無名の群（group なし）が先頭で、名前のある群は最初に現れた順。区画の順は定義のまま。 */
fun VirtualDashboardDef.sectionGroups(): List<Pair<String?, List<DashboardSection>>> {
    val grouped = sections.groupBy { it.group }
    return listOfNotNull(grouped[null]?.let { null to it }) + grouped.entries.filter { it.key != null }.map { it.key to it.value }
}

data class DashboardPending(val row: VirtualRow, val reason: String)

data class DashboardSectionResult(
    val definition: DashboardSection,
    val view: VirtualGridDef?,
    val rows: List<VirtualRow>,
    val pending: List<DashboardPending> = emptyList(),
    val error: String? = null,
    /** チャートの区画（P46）: 元の表を解決して数にしたもの。view は null。 */
    val chart: ChartData? = null,
    /** 元の表のクエリ行が `limit` で切れていたとき（件数は切れた後の数。黙らずに示す）。 */
    val truncation: ResolvedRows? = null,
) {
    /** 件数とドリルダウンの母集団は同じ。合計行は原本を持たないため含めない（値の軸の行は数える）。チャートは項目の数。 */
    val count: String get() = chart?.categories?.size?.toString()
        ?: SemanticExpression.aggregate(ValueExpr.AggregateFn.COUNT, rows.filter { it.occurrences.isNotEmpty() || it.valueRow }.map { it.key })!!

    fun selected(key: String?): VirtualRow? = rows.singleOrNull { it.key == key && it.occurrences.isNotEmpty() }
}

data class DashboardResolution(val date: LocalDate, val sections: List<DashboardSectionResult>)

/** 演算は既存評価器へ委譲し、画面・CLI・エージェントに同じ行と判定理由を返す。 */
object DashboardViews {
    fun resolve(
        dashboard: VirtualDashboardDef,
        date: LocalDate,
        view: (String) -> VirtualGridDef?,
        rows: (VirtualGridDef) -> ResolvedRows,
        ids: Map<String, List<ScannedId>>,
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        loader: (DocumentId) -> YNode?,
        fieldAt: FieldResolver = NO_FIELDS,
        /** チャートの区画（P46）: ID からチャートの宣言を引く（無ければ null）。 */
        chart: (String) -> VirtualChartDef? = { null },
    ): DashboardResolution {
        val cache = mutableMapOf<String, ResolvedRows>()
        return DashboardResolution(date, dashboard.sections.map { section ->
            chart(section.view)?.let { chartDef ->
                return@map runCatching {
                    require(section.filter == null) { "チャートの区画にはfilterを指定できません（絞り込みは元の表で）" }
                    val source = view(chartDef.view) ?: error("チャート「${chartDef.id}」の元の表「${chartDef.view}」は一意なグリッドまたはクロス集計を参照してください")
                    val data = ChartViews.resolve(chartDef, source, cache.getOrPut(source.id) { rows(source) }.rows)
                    DashboardSectionResult(section, null, emptyList(), chart = data)
                }.getOrElse { DashboardSectionResult(section, null, emptyList(), error = it.message ?: "チャートを解決できません") }
            }
            val grid = view(section.view)
            if (grid == null) return@map DashboardSectionResult(section, null, emptyList(),
                error = "ビュー「${section.view}」は一意なグリッド・クロス集計・チャートを参照してください")
            runCatching {
                require(grid.matrix == null || section.filter == null) { "クロス集計の区画にはfilterを指定できません" }
                val resolved = cache.getOrPut(grid.id) { rows(grid) }
                val sourceRows = resolved.rows
                val rule = section.filter?.rule(mapOf("today" to date.toString()))
                val pending = mutableListOf<DashboardPending>()
                val outcomes = mutableMapOf<String, SemanticRuleResult>()
                val selected = if (rule == null) sourceRows else sourceRows.filter { row ->
                    if (row.occurrences.isEmpty()) return@filter false
                    val result = outcomes.getOrPut(row.id) {
                        SemanticRules.evaluate(ids, refs, graph, rule, row.id, loader = loader, fieldAt = fieldAt)
                    }
                    if (result.outcome == CheckOutcome.WITHHELD || result.reason == CheckReason.NO_VALUE) {
                        pending += DashboardPending(row, result.reason?.text ?: "判定できません")
                    }
                    result.outcome == CheckOutcome.SATISFIED
                }
                DashboardSectionResult(section, grid, selected, pending, truncation = resolved.takeIf { it.truncated })
            }.getOrElse { DashboardSectionResult(section, grid, emptyList(), error = it.message ?: "表示を解決できません") }
        })
    }
}
