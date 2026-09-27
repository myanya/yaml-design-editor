package dev.toros.yde.semantic

import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.semantic.schema.QueryNodeKind
import dev.toros.yde.semantic.schema.ValueExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 1 つの要素の中の配列にわたる集計（値の式の集計のパスに `[*]`）と、順序の種類（`type: date`）のテスト。
 * 規則: 値が書かれていない・種類として読めない材料が 1 つでもあれば「得られない」（理由つき。CORE-CALCULUS「Aggregate」）。
 * 最小・最大の出どころは選ばれた要素で、来歴は全ての材料。
 */
class SemanticAggregateTest {

    private val doc = DocumentId("todos/T-1.yaml")

    private fun env(yaml: String): SemanticValues.Environment {
        val tree = YamlIO.parse(yaml.trimIndent())
        return SemanticValues.Environment(
            tree = { d -> tree.takeIf { d == doc } },
            current = SemanticQueryNodeRef(QueryNodeKind.ENTITY, "T-1", null, "todo", doc, emptyList()),
            currentOccurrence = SemanticExpression.Occurrence(doc, emptyList()),
        )
    }

    private fun maxDate(type: String? = "date"): ValueExpr =
        ValueExpr.parseValue(mapOf("max" to (mapOf("path" to "comments[*].date") + (type?.let { mapOf("type" to it) } ?: emptyMap()))), "x")

    private val threeComments = """
        id: T-1
        comments:
          - { date: "2026-09-02", body: 対応を開始した。 }
          - { date: "2026-09-05", body: 動作確認を実施した。 }
          - { date: "2026-09-01", body: 過去の記録を追記した。 }
    """

    @Test
    fun `日付の最大は配列の並び順によらず選ばれた要素の座標を持つ`() {
        val result = SemanticValues.evaluateDetailed(maxDate(), env(threeComments))
        val side = assertNotNull(result.side)
        assertEquals("2026-09-05", side.text)
        assertEquals("最大: 2026-09-02・2026-09-05・2026-09-01", side.raw)
        // 出どころは選ばれた要素（行き先）。来歴は全ての材料
        assertEquals(doc, side.sourceDocument)
        assertEquals(parseYPath("comments[1].date"), side.sourcePath)
        assertEquals(listOf("comments[0].date", "comments[1].date", "comments[2].date"), side.inputs.map { it.placeholder })
        assertEquals(parseYPath("comments[2].date"), side.inputs[2].path)
        // 並べ替えても値は同じ（出どころが動くだけ）
        val reordered = SemanticValues.evaluate(
            maxDate(),
            env("id: T-1\ncomments:\n  - { date: \"2026-09-05\" }\n  - { date: \"2026-09-01\" }\n  - { date: \"2026-09-02\" }"),
        )!!
        assertEquals("2026-09-05", reordered.text)
        assertEquals(parseYPath("comments[0].date"), reordered.sourcePath)
        // 最新の要素を消せば残りの最新へ
        val removed = SemanticValues.evaluate(maxDate(), env("id: T-1\ncomments:\n  - { date: \"2026-09-02\" }\n  - { date: \"2026-09-01\" }"))!!
        assertEquals("2026-09-02", removed.text)
    }

    @Test
    fun `順序の種類を宣言しなければ数値として読み日付は得られない`() {
        // 表記から意味を決めない: 日付として比べるなら type: date を書く
        val result = SemanticValues.evaluateDetailed(maxDate(type = null), env(threeComments))
        assertNull(result.side)
        assertEquals("comments[0].date が数値として読めません: 2026-09-02", result.reason)
    }

    @Test
    fun `書かれていない値や読めない日付が1つでもあれば得られず理由が残る`() {
        val missing = SemanticValues.evaluateDetailed(
            maxDate(),
            env("id: T-1\ncomments:\n  - { date: \"2026-09-02\" }\n  - { body: 日付なし }\n  - { date: \"2026-09-05\" }"),
        )
        assertNull(missing.side)
        assertEquals("comments[1].date が書かれていません", missing.reason)
        assertEquals(listOf("comments[1].date"), missing.missing)
        val unreadable = SemanticValues.evaluateDetailed(
            maxDate(),
            env("id: T-1\ncomments:\n  - { date: \"2026-09-02\" }\n  - { date: \"2026/09/05\" }"),
        )
        assertNull(unreadable.side)
        assertEquals("comments[1].date が日付として読めません: 2026/09/05", unreadable.reason)
    }

    @Test
    fun `配列が空か無ければ最大は得られずfirstで既定値に落とせる`() {
        val empty = SemanticValues.evaluateDetailed(maxDate(), env("id: T-1\ncomments: []"))
        assertNull(empty.side)
        assertEquals("値がありません", empty.reason)
        assertNull(SemanticValues.evaluate(maxDate(), env("id: T-1")))
        val withDefault = ValueExpr.parseValue(
            mapOf("first" to listOf(mapOf("max" to mapOf("path" to "comments[*].date", "type" to "date")), "未更新")),
            "x",
        )
        assertEquals("未更新", SemanticValues.evaluate(withDefault, env("id: T-1"))!!.text)
        assertEquals("2026-09-05", SemanticValues.evaluate(withDefault, env(threeComments))!!.text)
    }

    @Test
    fun `合計と件数と最小も配列の要素に広がる`() {
        val items = "id: T-1\nitems:\n  - { amount: 100 }\n  - { amount: 20.5 }"
        val sum = ValueExpr.parseValue(mapOf("sum" to mapOf("path" to "items[*].amount")), "x")
        assertEquals("120.5", SemanticValues.evaluate(sum, env(items))!!.text)
        assertEquals("0", SemanticValues.evaluate(sum, env("id: T-1\nitems: []"))!!.text)
        // 合計は特定の 1 か所の値ではない（座標なし）が、材料は来歴に残る
        val sumSide = SemanticValues.evaluate(sum, env(items))!!
        assertNull(sumSide.sourcePath)
        assertEquals(2, sumSide.inputs.size)
        val count = ValueExpr.parseValue(mapOf("count" to mapOf("path" to "items[*]")), "x")
        assertEquals("2", SemanticValues.evaluate(count, env(items))!!.text)
        assertEquals("0", SemanticValues.evaluate(count, env("id: T-1"))!!.text)
        // 同じ値が複数あれば最初の要素が出どころ（決定的）
        val min = ValueExpr.parseValue(mapOf("min" to mapOf("path" to "items[*].amount")), "x")
        val tie = SemanticValues.evaluate(min, env("id: T-1\nitems:\n  - { amount: 5 }\n  - { amount: 5 }"))!!
        assertEquals("5", tie.text)
        assertEquals(parseYPath("items[0].amount"), tie.sourcePath)
    }

    @Test
    fun `共有の集計は順序の種類で数値と日付を切り替える`() {
        val dates = listOf("2026-09-02", "2026-09-05", "2026-09-01")
        assertEquals("2026-09-05", SemanticExpression.aggregate(ValueExpr.AggregateFn.MAX, dates, ValueExpr.OrderKind.DATE))
        assertEquals("2026-09-01", SemanticExpression.aggregate(ValueExpr.AggregateFn.MIN, dates, ValueExpr.OrderKind.DATE))
        assertNull(SemanticExpression.aggregate(ValueExpr.AggregateFn.MAX, listOf("2026-09-02", "abc"), ValueExpr.OrderKind.DATE))
        assertNull(SemanticExpression.aggregate(ValueExpr.AggregateFn.MAX, dates, ValueExpr.OrderKind.NUMBER))
        // 既定は数値（文字の順なら 9 が大きい）
        assertEquals("10", SemanticExpression.aggregate(ValueExpr.AggregateFn.MAX, listOf("9", "10")))
        assertEquals(1, SemanticExpression.aggregateDetailed(ValueExpr.AggregateFn.MAX, listOf("9", "10"), ValueExpr.OrderKind.NUMBER)!!.winner)
        // 日付として読める規則は date 型の項目の検証と同じ（YYYY-MM-DD）
        assertFalse(SemanticExpression.readable(ValueExpr.OrderKind.DATE, "2026-9-5"))
        assertTrue(SemanticExpression.readable(ValueExpr.OrderKind.DATE, " 2026-09-05 "))
    }
}
