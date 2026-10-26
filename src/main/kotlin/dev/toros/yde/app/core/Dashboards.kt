package dev.toros.yde.app.core

import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.app.schema.fieldAt
import dev.toros.yde.semantic.DashboardResolution
import dev.toros.yde.semantic.DashboardViews
import dev.toros.yde.semantic.VirtualDashboardDef
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.pretty
import java.time.LocalDate

/** 同じ索引・原本・基準日を全区画に渡す窓口。時計は呼び出し側が採取する。 */
object Dashboards {
    fun resolve(
        view: VirtualDashboardDef, date: LocalDate, catalog: Views.LoadResult, index: IdIndex,
        schemaFor: (DocumentId) -> SchemaDef?, loader: (DocumentId) -> YNode?,
    ): DashboardResolution {
        val trees = mutableMapOf<DocumentId, YNode?>()
        fun tree(d: DocumentId): YNode? = if (trees.containsKey(d)) trees[d] else loader(d).also { trees[d] = it }
        return DashboardViews.resolve(view, date, catalog::dashboardGrid,
            { Views.resolve(it, index, ::tree, schemaFor) }, index.entries, index.refs, index.graph, ::tree,
            fieldAt = { d, path -> tree(d)?.let { schemaFor(d)?.fieldAt(it, path) } },
            chart = catalog::chartView)
    }

    /** CLIとread_viewは同じ解決結果を、原本の座標と判定できなかった対象つきで開示する。 */
    fun report(view: VirtualDashboardDef, result: DashboardResolution, errors: List<String> = emptyList()): Map<String, Any?> =
        linkedMapOf(
            "view" to view.id, "kind" to "dashboard", "date" to result.date.toString(), "actions" to view.actions,
            "placement" to view.placement.id,
            "sections" to result.sections.map { section -> linkedMapOf(
                "id" to section.definition.id, "label" to section.definition.label, "view" to section.definition.view,
                "group" to section.definition.group,
                "count" to if (section.error == null) section.count.toInt() else null,
                "rows" to section.rows.map { row -> linkedMapOf(
                    "key" to row.key, "id" to row.id, "title" to row.title,
                    "origins" to row.occurrences.map { linkedMapOf("file" to it.document.value, "path" to it.path.pretty()) },
                    "cells" to row.cells.map { cell -> linkedMapOf(
                        "label" to cell.column.label, "value" to cell.value?.let { dev.toros.yde.yaml.YamlIO.toAny(it) },
                        "file" to cell.document?.value, "path" to cell.path?.pretty(),
                    ) },
                ) },
                "pending" to section.pending.map { linkedMapOf("id" to it.row.id, "reason" to it.reason) },
                // チャートの区画（P46）: 項目×系列の数（描画は画面だけ）
                "chart" to section.chart?.let { dev.toros.yde.semantic.ChartViews.report(it) },
                "error" to section.error,
            ) }, "errors" to errors,
        )
}
