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
 * 軸を値の式で広げるクロス集計（`rows.value` / `cells.columns.value`）のテスト（P46）。
 * 月の文書を置かずに、注文の受付日から `yearMonth` で月ごとの行（列）を作れることを固定する。
 * チャート（`kind: chart`）のテストは app.view.chart の ChartViewKindTest。
 */
class PivotViewsTest {

    private lateinit var ws: Path

    @BeforeTest
    fun setup() {
        ws = createTempDirectory("yde-pivot")
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
    fun `値の軸の行の未分類は 列の未分類と別の見出しを付けられ false で出さない`() {
        // 受付日の無い注文（行の値が得られない）と、列の軸に無い状態の注文（列の未分類）
        write("orders/ORD-005.yaml", "orderId: ORD-005\nstatus: pending\ntotal: 100")
        write("orders/ORD-006.yaml", "orderId: ORD-006\norderedAt: 2026-10-01\nstatus: lost\ntotal: 7")
        fun view(id: String, rows: String) = write(
            "_views/$id.yaml",
            """
            id: $id
            kind: matrix
            rows: $rows
            unclassified: 決まった値でない状態
            cells:
              match: [ { node: { entity: order, bind: o } } ]
              of: o
              columns: { path: status, values: [ pending, shipped ] }
            """,
        )
        view("named-rows", "{ value: { yearMonth: { path: orderedAt } }, unclassified: （受付日なし） }")
        view("hidden-rows", "{ value: { yearMonth: { path: orderedAt } }, unclassified: false }")
        view("shared-label", "{ value: { yearMonth: { path: orderedAt } } }")
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        assertTrue(loaded.errors.isEmpty(), loaded.errors.toString())
        fun resolve(id: String) = Views.resolve(loaded.view(id)!!, workspace.index, workspace::treeOf, workspace::schemaFor)
        val named = resolve("named-rows")
        assertEquals(listOf("2026-08", "2026-09", "2026-10", "（受付日なし）", "合計"), named.map { it.id })
        assertEquals(listOf("pending", "shipped", "決まった値でない状態", "合計"), Views.columnsOf(loaded.view("named-rows")!!, named).map { it.label })
        assertEquals(listOf("1", "0", "0", "1"), named.single { it.id == "（受付日なし）" }.cells.map(::text))
        assertEquals(listOf("2026-08", "2026-09", "2026-10", "合計"), resolve("hidden-rows").map { it.id })
        // 書かなければ、今までどおり列の見出しと同じ
        assertTrue("決まった値でない状態" in resolve("shared-label").map { it.id })
        // 値の軸でない行には書けない
        write("_views/wrong.yaml", "id: wrong\nkind: matrix\nrows: { idPattern: \"ORD-*\", unclassified: x }\ncells:\n  match: [ { node: { entity: order, bind: o } } ]\n  of: o\n  columns: { path: status }\n")
        assertTrue(Views.load(ws, workspace.schemas).errors.any { "rows.unclassified" in it })
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
        // 見出しの式（label）と先に並べる値（order）: 2026-10 を先頭に、残りは値の順。見出しは年と月の文字
        val labeled = Views.resolve(loaded.view("order-by-month-labeled")!!, workspace.index, workspace::treeOf, workspace::schemaFor)
        assertEquals(listOf("2026年10月", "2026年8月", "2026年9月", "（未分類）", "合計"), labeled.first().cells.map { it.column.label })
        assertEquals(listOf("0", "5400", "0", "0", "5400"), labeled[0].cells.map(::text))
        assertEquals(listOf("5500", "0", "0", "0", "5500"), labeled[3].cells.map(::text))
    }

    @Suppress("unused")
    private fun scalar(v: Any?): YNode = YNode.Scalar(v)
}
