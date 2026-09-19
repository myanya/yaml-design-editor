package schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 値の式と値の条件の書き方（P29 Phase 6・SP-10。BACKLOG UX-107）のパースのテスト。
 * 書き間違いは定義エラー（実行時に黙って空にならない）。
 */
class ValueExprTest {

    @Test
    fun `決まった値はそのまま書ける`() {
        assertEquals(ValueExpr.Literal("10"), ValueExpr.parse(10, "x"))
        assertEquals(ValueExpr.Literal("approved"), ValueExpr.parse("approved", "x"))
        assertEquals(ValueExpr.Literal("true"), ValueExpr.parse(true, "x"))
        // 値が無いかもしれない決まった値は {value: ...} で受ける（ひな型用）
        assertEquals(ValueExpr.Literal(null), ValueExpr.parse(mapOf("value" to null), "x"))
    }

    @Test
    fun `値を1つ受け取る計算と四則演算は入れ子に書ける`() {
        val expr = ValueExpr.parse(
            mapOf("add" to listOf(mapOf("length" to mapOf("of" to "item", "path" to "name")), 2)),
            "x",
        )
        val arith = assertIs<ValueExpr.Arith>(expr)
        assertEquals(ValueExpr.ArithOp.ADD, arith.op)
        assertIs<ValueExpr.Unary>(arith.operands[0])
        assertEquals(ValueExpr.Literal("2"), arith.operands[1])
        // 読む束縛は入れ子の中まで拾う
        assertEquals(listOf("item"), expr.sources().map { it.of })
        assertEquals(1, expr.reads().size)
    }

    @Test
    fun `四則演算の値の個数の誤りは定義エラーになる`() {
        assertFailsWith<IllegalStateException> { ValueExpr.parse(mapOf("add" to "3"), "x") }
        assertFailsWith<IllegalArgumentException> { ValueExpr.parse(mapOf("add" to listOf(1)), "x") }
        assertFailsWith<IllegalArgumentException> { ValueExpr.parse(mapOf("subtract" to listOf(1, 2, 3)), "x") }
        assertFailsWith<IllegalArgumentException> { ValueExpr.parse(mapOf("divide" to listOf(1)), "x") }
    }

    @Test
    fun `oneOfの右は選択肢の並びで他の演算子の右は値の式1つ`() {
        val get = mapOf("of" to "item", "path" to "status")
        val short = ConstraintCheck.parse(mapOf("oneOf" to listOf(get, listOf("draft", "approved"))), "c")
        assertEquals(CheckOp.ONE_OF, short.terms.single().op)
        assertEquals(listOf(ValueExpr.Literal("draft"), ValueExpr.Literal("approved")), short.terms.single().rights)
        // 演算子を値として渡す形（定義体のひな型用）でも同じ
        val long = ConstraintCheck.parse(mapOf("op" to "oneOf", "left" to get, "right" to listOf("draft", "approved")), "c")
        assertEquals(short, long)
        assertFailsWith<IllegalStateException> { ConstraintCheck.parse(mapOf("oneOf" to listOf(get, "draft")), "c") }
        assertFailsWith<IllegalStateException> { ConstraintCheck.parse(mapOf("eq" to listOf(get, listOf("a", "b"))), "c") }
    }

    @Test
    fun `決まった値で書いた正規表現が読めなければ定義エラーになる`() {
        val get = mapOf("of" to "item", "path" to "code")
        ConstraintCheck.parse(mapOf("matches" to listOf(get, "[A-Z]+-[0-9]{3}")), "c")
        val e = assertFailsWith<IllegalStateException> { ConstraintCheck.parse(mapOf("matches" to listOf(get, "([A-Z")), "c") }
        assertTrue("正規表現として読めません" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `whereには値の条件を一つだけ書ける`() {
        val w = QueryWhere.parse(mapOf("of" to "item", "path" to "status", "oneOf" to listOf("draft", "approved")), "w")
        assertEquals(CheckOp.ONE_OF to listOf("draft", "approved"), w.test)
        assertEquals(CheckOp.GTE to listOf("3"), QueryWhere.parse(mapOf("of" to "item", "path" to "size", "gte" to 3), "w").test)
        assertEquals(false, QueryWhere.parse(mapOf("of" to "item", "path" to "note", "empty" to false), "w").empty)
        // 条件が無い・複数ある・形が違うのは定義エラー
        assertFailsWith<IllegalStateException> { QueryWhere.parse(mapOf("of" to "item", "path" to "status"), "w") }
        assertFailsWith<IllegalStateException> {
            QueryWhere.parse(mapOf("of" to "item", "path" to "status", "equals" to "a", "contains" to "b"), "w")
        }
        assertFailsWith<IllegalStateException> { QueryWhere.parse(mapOf("of" to "item", "path" to "status", "oneOf" to "draft"), "w") }
        assertFailsWith<IllegalStateException> { QueryWhere.parse(mapOf("of" to "item", "path" to "status", "empty" to "yes"), "w") }
        assertFailsWith<IllegalStateException> { QueryWhere.parse(mapOf("of" to "item", "path" to "code", "matches" to "([A-Z"), "w") }
    }
}
