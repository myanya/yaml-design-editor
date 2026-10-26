package dev.toros.yde.app.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * レポート（R30-04）: 定義（build の `$view` でビューの行を受け取る）とひな形から文字を作り、出力先を埋め、派生物として書く。
 */
class ReportsTest {

    private lateinit var ws: Path

    @BeforeTest
    fun setup() {
        ws = createTempDirectory("yde-reports")
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
                - { key: total, type: int, required: true }
            """,
        )
        write("orders/ORD-001.yaml", "orderId: ORD-001\norderedAt: 2026-09-15\ntotal: 5400")
        write("orders/ORD-002.yaml", "orderId: ORD-002\norderedAt: 2026-10-05\ntotal: 3000")
        write("orders/ORD-003.yaml", "orderId: ORD-003\norderedAt: 2026-10-20\ntotal: 6300")
        write(
            "_views/orders.yaml",
            """
            id: orders
            rows: { match: [ { node: { entity: order, bind: o } } ] }
            columns:
              - { label: 受付日, path: orderedAt }
              - { label: 月, value: { yearMonth: { path: orderedAt } } }
              - { label: 合計, path: total }
            """,
        )
        write(
            "_reports/monthly.yaml",
            """
            id: monthly
            name: 月次
            params: [month]
            build:
              month: ${'$'}month
              orders: { ${'$'}view: orders, ${'$'}where: [{ path: values.月, equals: ${'$'}month }] }
              first: { ${'$'}view: orders, ${'$'}where: [{ path: values.月, equals: ${'$'}month }], ${'$'}one: true }
            template: monthly.md
            output: "reports/{month}.md"
            """,
        )
        write(
            "_reports/monthly.md",
            """
            # {month}
            {#each orders}
            - {id} {values.受付日} {values.合計:#,##0}
            {/each}
            先頭: {first.id} / 無い穴: {nothing}
            """,
        )
        write("_reports/broken.yaml", "id: broken\nbuild: { x: 1 }\ntemplate: broken.md\n")
        write("_reports/broken.md", "{#each x}")
        write("_reports/yaml-out.yaml", "id: yaml-out\nbuild: { x: 1 }\ntemplate: monthly.md\noutput: out.yaml\n")
    }

    @AfterTest
    fun cleanup() {
        Files.walk(ws).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test
    fun `定義とひな形から文字を作り 出力先を埋めて派生物として書く`() {
        val workspace = Workspace.open(ws)
        val catalog = Views.load(ws, workspace.schemas)
        val loaded = Reports.load(ws)
        // 読めたのは monthly だけ。閉じ忘れのひな形と YAML への出力は定義エラー
        assertEquals(listOf("monthly"), loaded.reports.map { it.def.id })
        assertTrue(loaded.errors.any { "broken" in it && "閉じられていません" in it }, loaded.errors.toString())
        assertTrue(loaded.errors.any { "yaml-out" in it && "YAML" in it }, loaded.errors.toString())
        val report = assertNotNull(loaded.report("monthly"))
        val result = Reports.run(report, mapOf("month" to "2026-10"), catalog, workspace.index, workspace::treeOf, workspace::schemaFor)
        assertEquals("# 2026-10\n- ORD-002 2026-10-05 3,000\n- ORD-003 2026-10-20 6,300\n先頭: ORD-002 / 無い穴: ", result.text)
        assertEquals("reports/2026-10.md", result.outputRel)
        assertEquals(listOf("nothing"), result.missing)
        // 受け取る値が無ければ誤り
        assertFailsWith<IllegalStateException> { Reports.run(report, emptyMap(), catalog, workspace.index, workspace::treeOf, workspace::schemaFor) }
        // 派生物として書く。YAML と定義の場所には書かない
        val written = Reports.write(ws, result.outputRel!!, result.text)
        assertEquals(result.text, Files.readString(written))
        assertFailsWith<IllegalArgumentException> { Reports.write(ws, "orders/x.yaml", "x") }
        assertFailsWith<IllegalArgumentException> { Reports.write(ws, "_reports/x.md", "x") }
    }

    private fun write(rel: String, content: String) {
        val file = ws.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent())
    }
}
