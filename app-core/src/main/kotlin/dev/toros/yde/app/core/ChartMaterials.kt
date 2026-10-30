package dev.toros.yde.app.core

import dev.toros.yde.app.index.IdEntry
import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.app.view.chart.ChartData
import dev.toros.yde.app.view.chart.ChartMaterial
import dev.toros.yde.app.view.chart.ChartMaterialGroup
import dev.toros.yde.app.view.chart.ChartSelection
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YPath

/**
 * チャートで押した形の材料を集める: 対応するセル（項目 × 系列）の来歴の入力（集めた要素・計算の材料）を並べ、各入力の場所を含む
 * 最も内側の ID（索引から）を添える。表のセルを押したときと同じ材料で、業務詳細（ID）か原本（場所）へ進める。
 * seriesIndex が null ならその項目のすべての系列。索引を使うので、チャートの種類（app.view.chart。カーネルだけを使う）でなくここに置く。
 */
fun ChartData.selection(categoryIndex: Int, seriesIndex: Int?, index: IdIndex): ChartSelection? {
    val category = categories.getOrNull(categoryIndex) ?: return null
    val byDocument: Map<DocumentId, List<IdEntry>> by lazy { index.entries.values.flatten().groupBy { it.document } }
    fun innermost(document: DocumentId?, path: YPath?): IdEntry? {
        if (document == null || path == null) return null
        return byDocument[document].orEmpty()
            .filter { it.path.size <= path.size && path.subList(0, it.path.size) == it.path }
            .maxByOrNull { it.path.size }
    }
    val chosen = if (seriesIndex == null) series.indices.toList() else listOfNotNull(seriesIndex.takeIf { it in series.indices })
    val groups = chosen.map { si ->
        val s = series[si]
        val cell = s.cells.getOrNull(categoryIndex)
        val materials = cell?.trace?.inputs.orEmpty().map { input ->
            val entry = innermost(input.document, input.path)
            ChartMaterial(entry?.id, entry?.title, input.placeholder, input.value, input.document, input.path)
        }
        ChartMaterialGroup(s, s.values.getOrNull(categoryIndex), cell, materials)
    }
    return ChartSelection(category, seriesIndex, groups)
}
