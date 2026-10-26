package dev.toros.yde.app.core

import dev.toros.yde.yaml.model.YNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 軸を値の式で広げるクロス集計（`rows.value` / `cells.columns.value`）とチャート（`kind: chart`）のテスト（P46）。
 * 月の文書を置かずに、注文の受付日から `yearMonth` で月ごとの行（列）を作れること、チャートが元の表の数を項目×系列にすることを固定する。
 */
class PivotAndChartViewsTest {

    private lateinit var ws: Path

    @BeforeTest
    fun setup() {
        ws = createTempDirectory("yde-pivot-chart")
        write(
            "_schemas/order.yaml",
            """
            id: order
            match: "orders/*.yaml"
            entity: { type: order, idPattern: "ORD-*", defines: true }
            root:
              type: object
              fields:
                - { key: orderId, type: id, required: true }
                - { key: orderedAt, type: date, required: true }
                - { key: status, type: enum, values: [pending, shipped], required: true }
                - { key: total, type: int, required: true }
            """,
        )
        write("orders/ORD-001.yaml", "orderId: ORD-001\norderedAt: 2026-08-15\nstatus: shipped\ntotal: 5400")
        write("orders/ORD-002.yaml", "orderId: ORD-002\norderedAt: 2026-09-05\nstatus: shipped\ntotal: 3000")
        write("orders/ORD-003.yaml", "orderId: ORD-003\norderedAt: 2026-10-20\nstatus: pending\ntotal: 6300")
        write("orders/ORD-004.yaml", "orderId: ORD-004\norderedAt: 2026-10-24\nstatus: pending\ntotal: 5500")
        write(
            "_views/month-by-status.yaml",
            """
            id: month-by-status
            name: 月×状態の売上
            kind: matrix
            rows: { value: { yearMonth: { path: orderedAt } } }
            cells:
              match: [ { node: { entity: order, bind: o } } ]
              of: o
              columns: { path: status, values: [ { value: pending, label: 未処理 }, { value: shipped, label: 出荷済み } ] }
              aggregate: sum
              path: total
            """,
        )
        write(
            "_views/order-by-month.yaml",
            """
            id: order-by-month
            name: 注文×月
            kind: matrix
            rows: { idPattern: "ORD-*" }
            totals: false
            cells:
              match: [ { node: { entity: order, bind: o } } ]
              of: o
              columns: { value: { yearMonth: { path: orderedAt } } }
              aggregate: sum
              path: total
            """,
        )
        write(
            "_views/monthly-chart.yaml",
            """
            id: monthly-chart
            name: 月別売上
            kind: chart
            chart: stacked-bar
            view: month-by-status
            y: { label: 売上高（円）, format: "#,##0" }
            """,
        )
        write("_views/trend-chart.yaml", "id: trend-chart\nkind: chart\nchart: line\nview: month-by-status\nseries: [合計]\ndrill: below\norder: { direction: desc }\n")
        write("_views/bad-chart.yaml", "id: bad-chart\nkind: chart\nchart: pie\nview: nosuch\n")
        write("_views/bad-drill.yaml", "id: bad-drill\nkind: chart\nchart: bar\nview: month-by-status\ndrill: sideways\n")
    }

    @AfterTest
    fun cleanup() {
        Files.walk(ws).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun write(rel: String, content: String) {
        val file = ws.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent() + "\n")
    }

    private fun text(cell: ViewCell): String = Views.cellText(cell.value)

    @Test
    fun `行の軸を値の式にすると 月の文書が無くても受付日の年月ごとの行になり 値の順に並ぶ`() {
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        assertTrue(loaded.errors.none { "month-by-status" in it }, loaded.errors.toString())
        val view = loaded.view("month-by-status")!!
        val rows = Views.resolve(view, workspace.index, workspace::treeOf, workspace::schemaFor)
        assertEquals(listOf("2026-08", "2026-09", "2026-10", "合計"), rows.map { it.id })
        assertEquals(listOf("未処理", "出荷済み", "（未分類）", "合計"), Views.columnsOf(view, rows).map { it.label })
        fun cells(id: String) = rows.single { it.id == id }.cells.map(::text)
        assertEquals(listOf("0", "5400", "0", "5400"), cells("2026-08"))
        assertEquals(listOf("11800", "0", "0", "11800"), cells("2026-10"))
        assertEquals(listOf("11800", "8400", "0", "20200"), cells("合計"))
        // 値の軸の行は原本の出現を持たないが合計の行ではない。セルの来歴から注文へ辿れる
        val october = rows.single { it.id == "2026-10" }
        assertTrue(october.valueRow && october.occurrences.isEmpty())
        assertEquals(listOf("ORD-003", "ORD-004"), october.cells[0].trace!!.inputs.map { it.placeholder })
        // ヘッダ用の lower は列を決めない（列は解決した行から）
        assertTrue(Views.effective(view, workspace.index).columns.isEmpty())
    }

    @Test
    fun `列の軸を値の式にすると データに現れた値が列になる`() {
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        val rows = Views.resolve(loaded.view("order-by-month")!!, workspace.index, workspace::treeOf, workspace::schemaFor)
        assertEquals(listOf("ORD-001", "ORD-002", "ORD-003", "ORD-004"), rows.map { it.id })
        assertEquals(listOf("2026-08", "2026-09", "2026-10", "（未分類）"), rows.first().cells.map { it.column.label })
        assertEquals(listOf("5400", "0", "0", "0"), rows[0].cells.map(::text))
        assertEquals(listOf("0", "0", "5500", "0"), rows[3].cells.map(::text))
    }

    @Test
    fun `チャートは元の表を数にし 定義の誤りは読み込みで分かる`() {
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        assertTrue(loaded.errors.any { "bad-chart" in it && "nosuch" in it }, loaded.errors.toString())
        val chart = loaded.chartView("monthly-chart")!!
        val data = assertNotNull(Views.resolveChart(chart, loaded, workspace.index, workspace::treeOf, workspace::schemaFor))
        assertEquals(listOf("2026-08", "2026-09", "2026-10"), data.categories.map { it.label })
        assertEquals(listOf("未処理", "出荷済み"), data.series.map { it.label })
        assertEquals(listOf(0.0, 0.0, 11800.0), data.series[0].values)
        assertEquals(listOf(5400.0, 3000.0, 0.0), data.series[1].values)
        // order: {direction: desc} で合計の多い順（2026-10 11,800 → 2026-08 5,400 → 2026-09 3,000）
        val trend = assertNotNull(Views.resolveChart(loaded.chartView("trend-chart")!!, loaded, workspace.index, workspace::treeOf, workspace::schemaFor))
        assertEquals(listOf("2026-10", "2026-08", "2026-09"), trend.categories.map { it.label })
        assertEquals(listOf(11800.0, 5400.0, 3000.0), trend.series.single().values)
        // CLI の出力と同じ形（数のまま）
        @Suppress("UNCHECKED_CAST")
        val series = dev.toros.yde.semantic.ChartViews.report(data)["series"] as List<Map<String, Any?>>
        assertEquals(listOf(0L, 0L, 11800L), series[0]["values"])
    }

    @Test
    fun `押した形の材料は対応するセルの来歴で 要素の ID と原本の場所へ辿れる`() {
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        assertTrue(loaded.errors.any { "bad-drill" in it && "sideways" in it }, loaded.errors.toString())
        assertEquals(dev.toros.yde.semantic.ChartDrill.BELOW, loaded.chartView("trend-chart")!!.drill)
        assertEquals(dev.toros.yde.semantic.ChartDrill.POPUP, loaded.chartView("monthly-chart")!!.drill)
        val data = assertNotNull(Views.resolveChart(loaded.chartView("monthly-chart")!!, loaded, workspace.index, workspace::treeOf, workspace::schemaFor))
        // 2026-10 × 未処理（11,800）の棒 → 材料は ORD-003（6,300）と ORD-004（5,500）。場所を含む最も内側の ID が添えられる
        val picked = assertNotNull(Views.chartSelection(data, 2, 0, workspace.index))
        assertEquals("2026-10", picked.category.label)
        val group = picked.groups.single()
        assertEquals("未処理", group.series.label)
        assertEquals(11800.0, group.value)
        assertEquals(listOf("ORD-003", "ORD-004"), group.materials.map { it.id })
        assertEquals(listOf("6300", "5500"), group.materials.map { it.value })
        assertEquals("orders/ORD-003.yaml", group.materials.first().document?.value)
        // 系列を決めずに押すと、その項目のすべての系列が並ぶ
        val all = assertNotNull(Views.chartSelection(data, 0, null, workspace.index))
        assertEquals(listOf("未処理", "出荷済み"), all.groups.map { it.series.label })
        assertEquals(listOf("ORD-001"), all.groups[1].materials.map { it.id })
    }

    @Test
    fun `ダッシュボードの区画にチャートを置ける`() {
        write(
            "_views/home.yaml",
            """
            id: home
            kind: dashboard
            sections:
              - { id: monthly, label: 月別売上, view: monthly-chart }
              - { id: all, label: すべて, view: month-by-status }
            """,
        )
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        assertTrue(loaded.errors.none { "home" in it }, loaded.errors.toString())
        val result = Dashboards.resolve(loaded.dashboard("home")!!, java.time.LocalDate.of(2026, 10, 26), loaded, workspace.index, workspace::schemaFor, workspace::treeOf)
        val monthly = result.sections.first()
        assertNotNull(monthly.chart)
        assertEquals("3", monthly.count)
        val table = result.sections[1]
        assertEquals("3", table.count)  // 値の軸の行 3 つ（合計の行は数えない）
        val report = Dashboards.report(loaded.dashboard("home")!!, result)
        @Suppress("UNCHECKED_CAST")
        val sections = report["sections"] as List<Map<String, Any?>>
        assertNotNull(sections[0]["chart"])
    }

    @Suppress("unused")
    private fun scalar(v: Any?): YNode = YNode.Scalar(v)
}
