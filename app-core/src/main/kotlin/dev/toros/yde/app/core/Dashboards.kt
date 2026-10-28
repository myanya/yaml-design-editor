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
        /** 共有の入力の値。鍵があればその値（空＝絞らない）、無ければ定義の default（基準日から）。 */
        inputs: Map<String, String?> = emptyMap(),
    ): DashboardResolution {
        val trees = mutableMapOf<DocumentId, YNode?>()
        fun tree(d: DocumentId): YNode? = if (trees.containsKey(d)) trees[d] else loader(d).also { trees[d] = it }
        val values = view.inputs.associate { input ->
            input.key to (if (inputs.containsKey(input.key)) inputs[input.key]?.takeIf { it.isNotBlank() } else input.defaultFor(date))
        }
        return DashboardViews.resolve(view, date, catalog::dashboardGrid,
            { grid, params -> Views.resolveDetailed(grid, index, ::tree, schemaFor, params) }, index.entries, index.refs, index.graph, ::tree,
            fieldAt = { d, path -> tree(d)?.let { schemaFor(d)?.fieldAt(it, path) } },
            embedded = catalog::extensionView, inputs = values)
    }

    /** CLIとread_viewは同じ解決結果を、原本の座標と判定できなかった対象つきで開示する。 */
    fun report(view: VirtualDashboardDef, result: DashboardResolution, errors: List<String> = emptyList()): Map<String, Any?> =
        linkedMapOf(
            "view" to view.id, "kind" to "dashboard", "date" to result.date.toString(), "actions" to view.actions,
            "placement" to view.placement.id,
            // 共有の入力: 定義と、今回効かせた値（null＝絞っていない）
            "inputs" to view.inputs.map { i -> linkedMapOf("key" to i.key, "label" to i.label, "type" to i.type.id, "value" to result.inputs[i.key], "default" to i.default) },
            "sections" to result.sections.map { section -> linkedMapOf(
                "id" to section.definition.id, "label" to section.definition.label, "view" to section.definition.view,
                "group" to section.definition.group,
                "count" to if (section.error == null) section.count.toInt() else null,
                // 元の表のクエリ行が limit で切れたとき（書かなければ上限なし）
                "truncated" to section.truncation?.let { linkedMapOf("limit" to it.limit, "total" to it.total) },
                "rows" to section.rows.map { row -> linkedMapOf(
                    "key" to row.key, "id" to row.id, "title" to row.title,
                    "origins" to row.occurrences.map { linkedMapOf("file" to it.document.value, "path" to it.path.pretty()) },
                    "cells" to row.cells.map { cell -> linkedMapOf(
                        "label" to cell.column.label, "value" to cell.value?.let { dev.toros.yde.yaml.YamlIO.toAny(it) },
                        "file" to cell.document?.value, "path" to cell.path?.pretty(),
                    ) },
                ) },
                "pending" to section.pending.map { linkedMapOf("id" to it.row.id, "reason" to it.reason) },
            ).also { m ->
                // 拡張の種類の区画（チャートなど）: 種類の ID を鍵に、その結果の値（描画は画面だけ）
                section.embedded?.let { m[it.def.viewKind] = it.report() }
                m["error"] = section.error
            } }, "errors" to errors,
        )
}
