package dev.toros.yde.view.chart

import dev.toros.yde.app.index.IdEntry
import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.semantic.VirtualCell
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YPath

// ================================================================ 押した形の材料（drill）
//
// 棒・点・扇形を押したときに並べる材料。表のセルを押したときと同じく、セルの来歴の入力（集めた要素の値と場所）を使う。
// 描画（desktop）が読むので型は公開し、集める処理は ChartData.selection から呼ぶ。

/** 材料 1 件: 来歴の入力（集めた要素の値と場所）に、その場所を含む最も内側の ID を添える。 */
data class ChartMaterial(val id: String?, val title: String?, val label: String, val value: String?, val document: DocumentId?, val path: YPath?)

/** 押した項目の、系列 1 つぶんの材料（セルと、その来歴）。 */
data class ChartMaterialGroup(val series: ChartSeriesResolved, val value: Double?, val cell: VirtualCell?, val materials: List<ChartMaterial>)

/** チャートで押した項目（と系列。null＝その項目のすべての系列）。 */
data class ChartSelection(val category: ChartCategory, val seriesIndex: Int?, val groups: List<ChartMaterialGroup>)

internal object ChartSelections {
    fun select(data: ChartData, categoryIndex: Int, seriesIndex: Int?, index: IdIndex): ChartSelection? {
        val category = data.categories.getOrNull(categoryIndex) ?: return null
        val byDocument: Map<DocumentId, List<IdEntry>> by lazy { index.entries.values.flatten().groupBy { it.document } }
        fun innermost(document: DocumentId?, path: YPath?): IdEntry? {
            if (document == null || path == null) return null
            return byDocument[document].orEmpty()
                .filter { it.path.size <= path.size && path.subList(0, it.path.size) == it.path }
                .maxByOrNull { it.path.size }
        }
        val chosen = if (seriesIndex == null) data.series.indices.toList() else listOfNotNull(seriesIndex.takeIf { it in data.series.indices })
        val groups = chosen.map { si ->
            val s = data.series[si]
            val cell = s.cells.getOrNull(categoryIndex)
            val materials = cell?.trace?.inputs.orEmpty().map { input ->
                val entry = innermost(input.document, input.path)
                ChartMaterial(entry?.id, entry?.title, input.placeholder, input.value, input.document, input.path)
            }
            ChartMaterialGroup(s, s.values.getOrNull(categoryIndex), cell, materials)
        }
        return ChartSelection(category, seriesIndex, groups)
    }
}
