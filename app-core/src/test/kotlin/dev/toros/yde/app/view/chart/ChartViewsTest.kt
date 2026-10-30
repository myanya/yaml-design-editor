package dev.toros.yde.app.view.chart

import dev.toros.yde.semantic.ScannedId
import dev.toros.yde.semantic.VirtualCell
import dev.toros.yde.semantic.VirtualGridDef
import dev.toros.yde.semantic.VirtualMatrixCells
import dev.toros.yde.semantic.VirtualMatrixDef
import dev.toros.yde.semantic.VirtualMatrixKey
import dev.toros.yde.semantic.VirtualRow
import dev.toros.yde.semantic.VirtualRowsDef
import dev.toros.yde.semantic.VirtualViewColumn
import dev.toros.yde.semantic.VirtualViews
import dev.toros.yde.semantic.schema.ValueExpr
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * チャート（kind: chart。P46）の解決: 元の表の解決結果（行×セル）を、項目（行）× 系列（数の列）の数にする。
 * 系列を書かなければ数として読める列すべて（クロス集計の合計・未分類の列は除く）、合計の行は除く、x の列で項目の文字を決める。
 */
class ChartViewsTest {

    private val doc = DocumentId("orders/a.yaml")

    private fun column(label: String) = VirtualViewColumn(label = label, path = emptyList())

    private fun row(id: String, title: String?, columns: List<VirtualViewColumn>, values: List<Any?>, origin: Boolean = true, valueRow: Boolean = false) =
        VirtualRow(
            id = id, title = title,
            occurrences = if (origin) listOf(ScannedId(id, doc, listOf("items", 0), title, "s", doc.value)) else emptyList(),
            cells = columns.mapIndexed { i, c -> VirtualCell(c, doc, null, values.getOrNull(i)?.let { YNode.Scalar(it) }) },
            detailDocument = null, detailPath = null, valueRow = valueRow,
        )

    private fun chart(kind: ChartKind, series: List<String> = emptyList(), x: String? = null, order: ChartOrder? = null) =
        VirtualChartDef("c", "売上", null, DocumentId("_views/c.yaml"), kind, "src", series, x, order = order)

    @Test
    fun `図の種類は横棒と面を含み 積む種類が分かる`() {
        assertEquals(ChartKind.HORIZONTAL_BAR, ChartKind.parse("horizontal-bar", "chart"))
        assertEquals(ChartKind.STACKED_AREA, ChartKind.parse("stacked-area", "chart"))
        assertEquals(listOf(ChartKind.STACKED_BAR, ChartKind.STACKED_HORIZONTAL_BAR, ChartKind.STACKED_AREA), ChartKind.entries.filter { it.stacked })
        assertEquals(ChartKind.SCATTER, ChartKind.parse("scatter", "chart"))
        assertTrue(runCatching { ChartKind.parse("bubble", "chart") }.isFailure)
    }

    @Test
    fun `散布図は x の数の列を横軸の値にして系列から除き 点の名前は label の列`() {
        val columns = listOf(column("名前"), column("売上高"), column("件数"), column("メモ"))
        val grid = VirtualGridDef(id = "src", name = "表", columns = columns, source = DocumentId("_views/src.yaml"), rows = VirtualRowsDef("C-*"))
        val rows = listOf(
            row("C-1", "山田", columns, listOf("山田商店", "12,000", 2, "x")),
            row("C-2", "鈴木", columns, listOf("鈴木", "3000", null, "y")),
            row("C-3", "佐藤", columns, listOf("佐藤", "不明", 1, "z")),
        )
        val def = VirtualChartDef("c", "散布", null, DocumentId("_views/c.yaml"), ChartKind.SCATTER, "src", x = "売上高", label = "名前")
        val data = ChartViews.resolve(def, grid, rows)
        assertEquals(listOf(12000.0, 3000.0, null), data.xValues)
        assertEquals(listOf("件数"), data.series.map { it.label })
        assertEquals(listOf(2.0, null, 1.0), data.series.single().values)
        assertEquals(listOf("山田商店", "鈴木", "佐藤"), data.categories.map { it.label })
        assertTrue(data.notes.any { "数として読めない" in it }, data.notes.toString())
        // CLI の出力は項目ごとに横軸の値 x を持つ
        @Suppress("UNCHECKED_CAST")
        val categories = ChartViews.report(data)["categories"] as List<Map<String, Any?>>
        assertEquals(12000L, categories[0]["x"])
        assertEquals(null, categories[2]["x"])
        assertTrue(ChartViews.renderText(data).contains("山田商店（C-1）: x=12000・件数=2"))
        // 対数の軸: 0 以下の値は描けないので注意に出す（数はそのまま）
        val log = ChartViews.resolve(def.copy(xScale = ChartScale.LOG), grid, rows + row("C-4", "高橋", columns, listOf("高橋", "0", 3, "w")))
        assertTrue(log.notes.any { "対数" in it && "1 点" in it }, log.notes.toString())
        assertEquals(listOf(12000.0, 3000.0, null, 0.0), log.xValues)
        assertEquals("log", (ChartViews.report(log)["scale"] as Map<*, *>)["x"])
    }

    @Test
    fun `系列を書かなければ数として読める列すべてが系列になり 文字の列は項目の文字に使える`() {
        val columns = listOf(column("名前"), column("売上高"), column("件数"), column("メモ"))
        val grid = VirtualGridDef(id = "src", name = "表", columns = columns, source = DocumentId("_views/src.yaml"), rows = VirtualRowsDef("C-*"))
        val rows = listOf(
            row("C-1", "山田", columns, listOf("山田商店", "12,000", 2, "x")),
            row("C-2", "鈴木", columns, listOf("鈴木", "3000", null, "y")),
        )
        val data = ChartViews.resolve(chart(ChartKind.BAR), grid, rows)
        assertEquals(listOf("売上高", "件数"), data.series.map { it.label })
        assertEquals(listOf(12000.0, 3000.0), data.series[0].values)
        assertEquals(listOf(2.0, null), data.series[1].values)
        assertEquals(listOf("山田", "鈴木"), data.categories.map { it.label })
        // x に列を書くと、その列の値が項目の文字（行のタイトルではなく）
        val named = ChartViews.resolve(chart(ChartKind.BAR, x = "名前"), grid, rows)
        assertEquals(listOf("山田商店", "鈴木"), named.categories.map { it.label })
        assertTrue(data.notes.isEmpty(), data.notes.toString())
        // order: 系列の値の順に項目を並べ替える（系列と材料も同じ順。値の無い項目は後ろ）
        val more = rows + row("C-3", "佐藤", columns, listOf("佐藤", "20000", 1, "z"))
        val desc = ChartViews.resolve(chart(ChartKind.PIE, order = ChartOrder(descending = true)), grid, more)
        assertEquals(listOf("佐藤", "山田", "鈴木"), desc.categories.map { it.label })
        assertEquals(listOf(20000.0, 12000.0, 3000.0), desc.series[0].values)
        assertEquals(listOf(1.0, 2.0, null), desc.series[1].values)
        val byCount = ChartViews.resolve(chart(ChartKind.BAR, order = ChartOrder(series = "件数", descending = false)), grid, more)
        assertEquals(listOf("佐藤", "山田", "鈴木"), byCount.categories.map { it.label })  // 件数 1・2・（無し）
        val unknown = ChartViews.resolve(chart(ChartKind.BAR, order = ChartOrder(series = "ない列", descending = true)), grid, more)
        assertTrue(unknown.notes.any { "ない列" in it }, unknown.notes.toString())
        assertEquals(listOf("佐藤", "山田", "鈴木"), unknown.categories.map { it.label })
    }

    @Test
    fun `クロス集計が元の表なら合計の行と合計と未分類の列を除き series に書けば合計の列も系列にできる`() {
        val columns = listOf(column("未処理"), column("出荷済み"), column("（未分類）"), column(VirtualViews.TOTAL_LABEL))
        val matrix = VirtualMatrixDef(
            cells = VirtualMatrixCells(all = emptyMap(), of = "o", aggregate = ValueExpr.AggregateFn.SUM, kind = null, key = VirtualMatrixKey.Value(listOf("status"), "status", emptyList())),
            rowsValue = null,
        )
        val grid = VirtualGridDef(id = "src", name = "月×状態", columns = emptyList(), source = DocumentId("_views/src.yaml"), rows = VirtualRowsDef("M-*"), matrix = matrix)
        val rows = listOf(
            row("2026-09", null, columns, listOf(0, 3000, 0, 3000), origin = false, valueRow = true),
            row("2026-10", null, columns, listOf(11800, 0, 0, 11800), origin = false, valueRow = true),
            row(VirtualViews.TOTAL_LABEL, null, columns, listOf(11800, 3000, 0, 14800), origin = false),
        )
        val data = ChartViews.resolve(chart(ChartKind.STACKED_BAR), grid, rows)
        assertEquals(listOf("2026-09", "2026-10"), data.categories.map { it.label })
        assertEquals(listOf("未処理", "出荷済み"), data.series.map { it.label })
        assertEquals(listOf(0.0, 11800.0), data.series[0].values)
        val trend = ChartViews.resolve(chart(ChartKind.LINE, series = listOf(VirtualViews.TOTAL_LABEL)), grid, rows)
        assertEquals(listOf(3000.0, 11800.0), trend.series.single().values)
        // 円は最初の系列だけ・無い列は注意に出る
        val pie = ChartViews.resolve(chart(ChartKind.PIE, series = listOf("未処理", "なし")), grid, rows)
        assertEquals(listOf("未処理"), pie.series.map { it.label })
        assertTrue(pie.notes.any { "なし" in it }, pie.notes.toString())
        val report = ChartViews.report(data)
        assertEquals("chart", report["kind"])
        assertEquals("stacked-bar", report["chart"])
        assertTrue(ChartViews.renderText(trend).contains("2026-10: 合計=11800"), ChartViews.renderText(trend))
    }
}
