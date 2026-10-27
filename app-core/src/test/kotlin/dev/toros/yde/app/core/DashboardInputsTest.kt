package dev.toros.yde.app.core

import dev.toros.yde.semantic.DashboardResolution
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ダッシュボードの共有の入力（inputs。P44）: 値は区画の filter と、表の params（クエリ行の where・クロス集計の cells.where）に渡る。
 * 空は「絞らない」。渡さなければ定義の default（基準日から）。
 */
class DashboardInputsTest {

    private lateinit var ws: Path
    private val date: LocalDate = LocalDate.of(2026, 10, 26)

    @BeforeTest
    fun setup() {
        ws = createTempDirectory("yde-dashboard-inputs")
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
                - { key: kind, type: enum, values: [a, b], required: true }
                - { key: total, type: int, required: true }
            """,
        )
        write("orders/ORD-001.yaml", "orderId: ORD-001\norderedAt: 2026-09-15\nkind: a\ntotal: 100")
        write("orders/ORD-002.yaml", "orderId: ORD-002\norderedAt: 2026-10-05\nkind: b\ntotal: 200")
        write("orders/ORD-003.yaml", "orderId: ORD-003\norderedAt: 2026-10-20\nkind: a\ntotal: 400")
        write(
            "_views/orders.yaml",
            """
            id: orders
            params: [from, to]
            rows:
              match: [ { node: { entity: order, bind: o } } ]
              where:
                - { of: o, path: orderedAt, gte: ${'$'}from, type: date }
                - { of: o, path: orderedAt, lte: ${'$'}to, type: date }
            columns: [ { label: 合計, path: total } ]
            """,
        )
        write(
            "_views/by-month.yaml",
            """
            id: by-month
            kind: matrix
            params: [from, to]
            rows: { value: { yearMonth: { path: orderedAt } } }
            totals: false
            unclassified: false
            cells:
              match: [ { node: { entity: order, bind: o } } ]
              where:
                - { of: o, path: orderedAt, gte: ${'$'}from, type: date }
              of: o
              columns: { path: kind, values: [a, b] }
              aggregate: sum
              path: total
            """,
        )
        write("_views/bad.yaml", "id: bad\nrows: { match: [ { node: { entity: order, bind: o } } ], where: [ { of: o, path: kind, equals: ${'$'}nothing } ] }\ncolumns: [ { label: 合計, path: total } ]\n")
        write(
            "_views/home.yaml",
            """
            id: home
            kind: dashboard
            inputs:
              - { key: from, label: から, type: date, default: ${'$'}monthStart }
              - { key: to, label: まで, type: date }
              - { key: kind, label: 区分, type: enum, values: [a, b] }
            sections:
              - { id: all, label: 全件, view: orders }
              - id: kind
                label: 区分
                view: orders
                filter: { match: [ { node: { entity: order, bind: o } } ], where: [ { of: o, path: kind, equals: ${'$'}kind } ], expect: exists }
              - { id: month, label: 月別, view: by-month }
            """,
        )
    }

    @AfterTest
    fun cleanup() {
        Files.walk(ws).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test
    fun `入力は区画の条件と表の params に渡り 空は絞らず 渡さなければ初期値`() {
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        // 宣言していない $名前 は定義エラー
        assertTrue(loaded.errors.any { "bad" in it && "params" in it }, loaded.errors.toString())
        val home = assertNotNull(loaded.dashboard("home"))
        fun counts(inputs: Map<String, String?>): Pair<Map<String, String>, DashboardResolution> {
            val r = Dashboards.resolve(home, date, loaded, workspace.index, workspace::schemaFor, workspace::treeOf, inputs)
            return r.sections.associate { it.definition.id to it.count } to r
        }
        // 渡さなければ from は初期値（10 月の初日）: 10 月の 2 件。kind は空＝絞らない
        val (defaults, r1) = counts(emptyMap())
        assertEquals(mapOf("all" to "2", "kind" to "2", "month" to "1"), defaults)
        assertEquals(mapOf("from" to "2026-10-01", "to" to null, "kind" to null), r1.inputs)
        // 空文字で絞らない（全 3 件・2 か月）
        val (none, _) = counts(mapOf("from" to ""))
        assertEquals(mapOf("all" to "3", "kind" to "3", "month" to "2"), none)
        // 区分 a と、まで 2026-10-10
        val (filtered, r3) = counts(mapOf("from" to "", "to" to "2026-10-10", "kind" to "a"))
        assertEquals(mapOf("all" to "2", "kind" to "1", "month" to "2"), filtered)
        assertEquals("a", r3.inputs["kind"])
        // クロス集計の集計にも効く（from=2026-10-01: 10 月の a は 400）
        val month = r1.sections.single { it.definition.id == "month" }
        assertEquals(listOf("2026-10"), month.rows.map { it.id })
        assertEquals("400", Views.cellText(month.rows.single().cells[0].value))
        // CLI / read_view の出力に入力が出る
        @Suppress("UNCHECKED_CAST")
        val report = Dashboards.report(home, r1)["inputs"] as List<Map<String, Any?>>
        assertEquals(listOf("from", "to", "kind"), report.map { it["key"] })
        assertEquals("2026-10-01", report[0]["value"])
        assertNull(report[1]["value"])
        // 表を単独で解決するとき（ビュータブ）も params を渡せる。無ければ絞らない
        val orders = loaded.view("orders")!!
        assertEquals(3, Views.resolve(orders, workspace.index, workspace::treeOf, workspace::schemaFor).size)
        assertEquals(1, Views.resolve(orders, workspace.index, workspace::treeOf, workspace::schemaFor, mapOf("from" to "2026-10-10")).size)
    }

    private fun write(rel: String, content: String) {
        val file = ws.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent())
    }
}
