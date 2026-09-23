package dev.toros.yde.semantic.schema

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

    // ---------------------------------------------------------------- スキーマの値の式（P31・R31-01）

    @Test
    fun `スキーマの値の式は穴を含む文字列をformatの省略形として読む`() {
        val short = ValueExpr.parseValue("screens/{id}_{name}.yaml", "filePattern")
        assertEquals(ValueExpr.Format(ValueExpr.Literal("screens/{id}_{name}.yaml"), emptyList()), short)
        // 穴の無い文字列は決まった値
        assertEquals(ValueExpr.Literal("fixed.yaml"), ValueExpr.parseValue("fixed.yaml", "x"))
        // いまの要素から読む項目（予約の id を含む・with で付けた名前は含まない）
        assertEquals(listOf("id", "name"), short.currentReads())
        val full = ValueExpr.parseValue(
            mapOf("format" to "{dir}/{id}.yaml", "with" to mapOf("dir" to mapOf("lower" to mapOf("path" to "domain")))),
            "x",
        )
        assertEquals(listOf("id", "domain"), full.currentReads())
        assertTrue(full.binds().isEmpty(), "of を省略した読み出しは束縛を使わない")
    }

    @Test
    fun `束縛名の省略はスキーマの値の式だけで許される`() {
        // 検査の check では、of の省略は定義エラー（クエリの解に「いまの要素」は無い）
        assertFailsWith<IllegalStateException> { ValueExpr.parse(mapOf("path" to "name"), "x") }
        assertFailsWith<IllegalStateException> { ValueExpr.parse(mapOf("format" to "{name}"), "x") }
        assertFailsWith<IllegalStateException> { ValueExpr.parse(mapOf("refCount" to mapOf("dir" to "in")), "x") }
        // スキーマの値の式では、いまの要素
        val get = assertIs<ValueExpr.Get>(ValueExpr.parseValue(mapOf("path" to "name"), "x"))
        assertEquals(null, get.of)
        val format = assertIs<ValueExpr.Format>(ValueExpr.parseValue(mapOf("format" to "{name}"), "x"))
        assertTrue(format.scopes.isEmpty())
        // with の名前は英字で始まる英数字
        assertFailsWith<IllegalArgumentException> {
            ValueExpr.parseValue(mapOf("format" to "{a.b}", "with" to mapOf("a.b" to "x")), "x")
        }
    }
}
