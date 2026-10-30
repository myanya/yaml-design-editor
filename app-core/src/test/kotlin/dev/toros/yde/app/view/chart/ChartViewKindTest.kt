package dev.toros.yde.app.view.chart

import dev.toros.yde.app.core.Dashboards
import dev.toros.yde.app.core.Views
import dev.toros.yde.app.core.Workspace
import dev.toros.yde.app.core.selection
import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.workspace.BuiltinSchemaResources
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.model.DocumentId
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
 * チャート（`kind: chart`。差し込み口を通る組込みの種類。ADR-0014・ADR-0015）のテスト。
 * 元の表（月×状態のクロス集計）の数を項目×系列にすること・定義の誤り・押した形の材料・ダッシュボードの区画・文書ビューの区画と、
 * メタスキーマへの断片の合成を固定する。
 */
class ChartViewKindTest {

    private lateinit var ws: Path

    @BeforeTest
    fun setup() {
        ws = createTempDirectory("yde-chart")
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
        write("_views/bad-scatter.yaml", "id: bad-scatter\nkind: chart\nchart: scatter\nview: month-by-status\n")
        write("_views/bad-label.yaml", "id: bad-label\nkind: chart\nchart: bar\nview: month-by-status\nlabel: 未処理\n")
        write("_views/bad-scale.yaml", "id: bad-scale\nkind: chart\nchart: bar\nview: month-by-status\nscale: {x: log}\n")
        write(
            "_views/order-by-month-labeled.yaml",
            """
            id: order-by-month-labeled
            kind: matrix
            rows: { idPattern: "ORD-*" }
            cells:
              match: [ { node: { entity: order, bind: o } } ]
              of: o
              columns:
                value: { yearMonth: { path: orderedAt } }
                label: { format: "{y}年{m}月", with: { y: { year: { path: orderedAt } }, m: { month: { path: orderedAt } } } }
                order: ["2026-10"]
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

    private fun chart(loaded: Views.LoadResult, id: String): VirtualChartDef = loaded.extensionView(id) as VirtualChartDef

    private fun resolve(def: VirtualChartDef, loaded: Views.LoadResult, index: IdIndex, loader: (DocumentId) -> YNode?, schemaFor: (DocumentId) -> SchemaDef?): ChartData =
        Views.resolveExtension(def, loaded, index, loader, schemaFor) as ChartData

    @Test
    fun `チャートは元の表を数にし 定義の誤りは読み込みで分かる`() {
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        assertTrue(loaded.errors.any { "bad-chart" in it && "nosuch" in it }, loaded.errors.toString())
        // 散布図は x（横軸の数の列）が必須、label は散布図だけ
        assertTrue(loaded.errors.any { "bad-scatter" in it && "x" in it }, loaded.errors.toString())
        assertTrue(loaded.errors.any { "bad-label" in it && "label" in it }, loaded.errors.toString())
        assertTrue(loaded.errors.any { "bad-scale" in it && "scale" in it }, loaded.errors.toString())
        val chart = chart(loaded, "monthly-chart")
        val data = assertNotNull(resolve(chart, loaded, workspace.index, workspace::treeOf, workspace::schemaFor))
        assertEquals(listOf("2026-08", "2026-09", "2026-10"), data.categories.map { it.label })
        assertEquals(listOf("未処理", "出荷済み"), data.series.map { it.label })
        assertEquals(listOf(0.0, 0.0, 11800.0), data.series[0].values)
        assertEquals(listOf(5400.0, 3000.0, 0.0), data.series[1].values)
        // order: {direction: desc} で合計の多い順（2026-10 11,800 → 2026-08 5,400 → 2026-09 3,000）
        val trend = assertNotNull(resolve(chart(loaded, "trend-chart"), loaded, workspace.index, workspace::treeOf, workspace::schemaFor))
        assertEquals(listOf("2026-10", "2026-08", "2026-09"), trend.categories.map { it.label })
        assertEquals(listOf(11800.0, 5400.0, 3000.0), trend.series.single().values)
        // CLI の出力と同じ形（数のまま）
        @Suppress("UNCHECKED_CAST")
        val series = data.report()["series"] as List<Map<String, Any?>>
        assertEquals(listOf(0L, 0L, 11800L), series[0]["values"])
    }

    @Test
    fun `押した形の材料は対応するセルの来歴で 要素の ID と原本の場所へ辿れる`() {
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        assertTrue(loaded.errors.any { "bad-drill" in it && "sideways" in it }, loaded.errors.toString())
        assertEquals(ChartDrill.BELOW, chart(loaded, "trend-chart").drill)
        assertEquals(ChartDrill.POPUP, chart(loaded, "monthly-chart").drill)
        val data = assertNotNull(resolve(chart(loaded, "monthly-chart"), loaded, workspace.index, workspace::treeOf, workspace::schemaFor))
        // 2026-10 × 未処理（11,800）の棒 → 材料は ORD-003（6,300）と ORD-004（5,500）。場所を含む最も内側の ID が添えられる
        val picked = assertNotNull(data.selection(2, 0, workspace.index))
        assertEquals("2026-10", picked.category.label)
        val group = picked.groups.single()
        assertEquals("未処理", group.series.label)
        assertEquals(11800.0, group.value)
        assertEquals(listOf("ORD-003", "ORD-004"), group.materials.map { it.id })
        assertEquals(listOf("6300", "5500"), group.materials.map { it.value })
        assertEquals("orders/ORD-003.yaml", group.materials.first().document?.value)
        // 系列を決めずに押すと、その項目のすべての系列が並ぶ
        val all = assertNotNull(data.selection(0, null, workspace.index))
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
        assertNotNull(monthly.embedded as? ChartData)
        assertEquals("3", monthly.count)
        val table = result.sections[1]
        assertEquals("3", table.count)  // 値の軸の行 3 つ（合計の行は数えない）
        val report = Dashboards.report(loaded.dashboard("home")!!, result)
        @Suppress("UNCHECKED_CAST")
        val sections = report["sections"] as List<Map<String, Any?>>
        assertNotNull(sections[0]["chart"])
    }

    @Test
    fun `文書ビューの区画にチャートを埋め込める`() {
        write("_views/order-doc.yaml", "id: order-doc\nkind: document\nschemaId: order\nsections:\n  - { label: 月別, chart: { view: monthly-chart } }\n  - { label: 誤り, chart: { view: month-by-status } }\n")
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        val doc = assertNotNull(loaded.docView("order-doc"), loaded.errors.toString())
        assertEquals(listOf("chart", "chart"), doc.sections.map { it.embed?.kind })
        // 埋め込み先はチャートであること（クロス集計は置けない）
        assertTrue(loaded.errors.any { "order-doc" in it && "sections[1].chart.view" in it && "一意なチャート（kind: chart）" in it }, loaded.errors.toString())
    }

    @Test
    fun `メタスキーマに断片が合成され チャートの定義文書がそのまま検証を通る`() {
        val view = BuiltinSchemaResources.kernelSchemas(WorkspaceLayout.LEGACY, listOf(ChartViewKind)).single { it.id == "builtin-view" }
        val keys = view.tree.root.fields.map { it.key }
        assertTrue(listOf("chart", "series", "drill", "scale").all { it in keys }, keys.toString())
        assertTrue("chart" in view.tree.root.fields.single { it.key == "kind" }.values)
        // カーネルに種類を渡さなければ中核の種類だけ（chart の項目は無い）
        val core = BuiltinSchemaResources.kernelSchemas(WorkspaceLayout.LEGACY).single { it.id == "builtin-view" }
        assertTrue(core.tree.root.fields.none { it.key == "drill" })
        // 定義文書の検証（メタスキーマ）に指摘が出ない
        val workspace = Workspace.open(ws)
        assertEquals(emptyList(), workspace.validate("_views/monthly-chart.yaml").map { it.message })
    }
}
