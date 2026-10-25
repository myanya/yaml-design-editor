package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.ActionRequire
import dev.toros.yde.semantic.schema.ValueExpr
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import java.time.LocalDate

/** 業務の入口。原本を持たず、既存グリッドと操作の参照だけを合成する。 */
data class VirtualDashboardDef(
    override val id: String,
    override val name: String,
    override val description: String? = null,
    override val source: DocumentId,
    val sections: List<DashboardSection>,
    val actions: List<String> = emptyList(),
    val default: Boolean = false,
) : VirtualViewDefinition

data class DashboardSection(
    val id: String,
    val label: String,
    val view: String,
    /** 操作の前提と同じ、パラメータを受け取る Query / Predicate の宣言を再利用する。 */
    val filter: ActionRequire? = null,
)

data class DashboardPending(val row: VirtualRow, val reason: String)

data class DashboardSectionResult(
    val definition: DashboardSection,
    val view: VirtualGridDef?,
    val rows: List<VirtualRow>,
    val pending: List<DashboardPending> = emptyList(),
    val error: String? = null,
) {
    /** 件数とドリルダウンの母集団は同じ。合計行は原本を持たないため含めない。 */
    val count: String get() = SemanticExpression.aggregate(ValueExpr.AggregateFn.COUNT,
        rows.filter { it.occurrences.isNotEmpty() }.map { it.key })!!

    fun selected(key: String?): VirtualRow? = rows.singleOrNull { it.key == key && it.occurrences.isNotEmpty() }
}

data class DashboardResolution(val date: LocalDate, val sections: List<DashboardSectionResult>)

/** 演算は既存評価器へ委譲し、画面・CLI・エージェントに同じ行と判定理由を返す。 */
object DashboardViews {
    fun resolve(
        dashboard: VirtualDashboardDef,
        date: LocalDate,
        view: (String) -> VirtualGridDef?,
        rows: (VirtualGridDef) -> List<VirtualRow>,
        ids: Map<String, List<ScannedId>>,
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        loader: (DocumentId) -> YNode?,
        fieldAt: FieldResolver = NO_FIELDS,
    ): DashboardResolution {
        val cache = mutableMapOf<String, List<VirtualRow>>()
        return DashboardResolution(date, dashboard.sections.map { section ->
            val grid = view(section.view)
            if (grid == null) return@map DashboardSectionResult(section, null, emptyList(),
                error = "ビュー「${section.view}」は一意なグリッドまたはクロス集計を参照してください")
            runCatching {
                require(grid.matrix == null || section.filter == null) { "クロス集計の区画にはfilterを指定できません" }
                val sourceRows = cache.getOrPut(grid.id) { rows(grid) }
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
                DashboardSectionResult(section, grid, selected, pending)
            }.getOrElse { DashboardSectionResult(section, grid, emptyList(), error = it.message ?: "表示を解決できません") }
        })
    }
}
