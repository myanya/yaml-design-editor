package dev.toros.yde.app.view.chart

import dev.toros.yde.semantic.VirtualCell
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YPath

// ================================================================ 押した形の材料（drill）
//
// 棒・点・扇形を押したときに並べる材料。表のセルを押したときと同じく、セルの来歴の入力（集めた要素の値と場所）を使う。
// 描画（desktop）が読むので型は公開する。集める処理は索引を使うので、読み出しの層（app.core の ChartData.selection）に置く。

/** 材料 1 件: 来歴の入力（集めた要素の値と場所）に、その場所を含む最も内側の ID を添える。 */
data class ChartMaterial(val id: String?, val title: String?, val label: String, val value: String?, val document: DocumentId?, val path: YPath?)

/** 押した項目の、系列 1 つぶんの材料（セルと、その来歴）。 */
data class ChartMaterialGroup(val series: ChartSeriesResolved, val value: Double?, val cell: VirtualCell?, val materials: List<ChartMaterial>)

/** チャートで押した項目（と系列。null＝その項目のすべての系列）。 */
data class ChartSelection(val category: ChartCategory, val seriesIndex: Int?, val groups: List<ChartMaterialGroup>)
