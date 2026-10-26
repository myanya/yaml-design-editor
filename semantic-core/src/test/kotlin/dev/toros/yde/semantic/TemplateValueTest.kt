package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ひな型の指示の拡張（R30-01 の一歩。samples/orders の「注文と明細を 1 回で登録する」が最初の利用例）:
 * - `$value`: 値の式（検査・ビューの列と同じ書き方）をいまの場所で計算する。数の結果は数の項、得られなければ「無い」
 * - `$no`: 繰り返しの 1 始まりの番号（`$index` は 0 始まり）
 * - `$number` の `$as`: 数を DecimalFormat の形の文字にする（連番の 0 詰め）
 */
class TemplateValueTest {

    private fun def(yaml: String): TransformDef = TransformDef.parse(YamlIO.parseToRaw(yaml.trimIndent()))

    private fun plain(node: YNode?): Any? = node?.let { YamlIO.toAny(it) }

    private fun expected(yaml: String): Any? = YamlIO.parseToRaw(yaml.trimIndent())

    private val order = """
        orderId: ORD-006
        orderedAt: 2026-10-25
        lines:
          - { productId: PRD-001, unitPrice: 300, quantity: 10 }
          - { productId: PRD-002, unitPrice: 120, quantity: 20 }
    """

    @Test
    fun `値の式をひな型に書ける 四則演算と集計はいまの場所の値で計算し 数の項になる`() {
        val transform = def(
            """
            id: t
            build:
              lines:
                ${'$'}each: lines
                ${'$'}build:
                  productId: { ${'$'}copy: productId }
                  amount: { ${'$'}value: { multiply: [ { path: unitPrice }, { path: quantity } ] } }
                  index: ${'$'}index
                  position: ${'$'}position
              totalAmount: { ${'$'}value: { sum: { each: { path: lines }, value: { multiply: [ { path: unitPrice }, { path: quantity } ] } } } }
              month: { ${'$'}value: { yearMonth: { path: orderedAt } } }
              missing: { ${'$'}value: { multiply: [ { path: nothing }, 2 ] } }
            """,
        )
        val out = SemanticTransform.run(transform, YamlIO.fromAny(expected(order)))
        assertEquals(
            expected(
                """
                lines:
                  - { productId: PRD-001, amount: 3000, index: 0, position: 1 }
                  - { productId: PRD-002, amount: 2400, index: 1, position: 2 }
                totalAmount: 5400
                month: "2026-10"
                """,
            ),
            plain(out.term),
        )
        // 出どころ: 値の式の結果は、いまの場所（入力の根・各要素）から来た
        assertTrue(out.origins.any { it.output == listOf("totalAmount") && it.path.isEmpty() }, out.origins.toString())
        assertTrue(out.origins.any { it.output == listOf("lines", 1, "amount") && it.path == listOf("lines", 1) }, out.origins.toString())
    }

    @Test
    fun `繰り返しの番号 position は 1 始まりで 受け取った値と合わせて明細番号を組み立てられる`() {
        // 穴の名前に no を使わない（YAML が真偽値に読む）
        val transform = def(
            """
            id: t
            params: [orderId]
            build:
              ${'$'}each: lines
              ${'$'}build:
                orderLineId: { ${'$'}format: "{orderId}-{n}", ${'$'}with: { orderId: ${'$'}orderId, n: { ${'$'}number: ${'$'}position, ${'$'}as: "00" } } }
            """,
        )
        val out = SemanticTransform.run(transform, YamlIO.fromAny(expected(order)), params = mapOf("orderId" to "ORD-006"))
        assertEquals(expected("[ { orderLineId: \"ORD-006-01\" }, { orderLineId: \"ORD-006-02\" } ]"), plain(out.term))
    }

    @Test
    fun `number の as は数の形の文字にし 読めない形と読めない値の式は定義の誤り`() {
        val grouped = def("id: t\nbuild: { price: { ${'$'}number: { ${'$'}copy: p }, ${'$'}as: \"#,##0\" } }")
        assertEquals(expected("price: \"12,000\""), plain(SemanticTransform.run(grouped, YamlIO.fromAny(expected("p: 12000"))).term))
        assertFailsWith<RuntimeException> { def("id: t\nbuild: { n: { ${'$'}number: 1, ${'$'}as: \"abc\" } }") }
        assertFailsWith<RuntimeException> { def("id: t\nbuild: { v: { ${'$'}value: { nosuch: 1 } } }") }
        // $value の of は、from のクエリで付けた名前だけ
        assertFailsWith<RuntimeException> { def("id: t\nbuild: { v: { ${'$'}value: { of: x, path: a } } }") }
    }
}
