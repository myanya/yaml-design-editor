package dev.toros.yde.semantic

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.semantic.schema.CheckOp
import dev.toros.yde.semantic.schema.ValueExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 値の条件と値の計算の部品（P29 Phase 6・SP-10。BACKLOG UX-107）の単体テスト。
 * 部品は純粋な関数で、評価できないときは null（判定保留）を返す。
 */
class ValueOperationsTest {

    @Test
    fun `文字列の条件は前方・後方・部分の一致と正規表現の全体一致`() {
        assertEquals(true, SemanticExpression.compare(CheckOp.STARTS_WITH, "UC-001", "UC-"))
        assertEquals(false, SemanticExpression.compare(CheckOp.STARTS_WITH, "SCR-001", "UC-"))
        assertEquals(true, SemanticExpression.compare(CheckOp.ENDS_WITH, "order_id", "_id"))
        assertEquals(true, SemanticExpression.compare(CheckOp.CONTAINS, "注文を確定する", "確定"))
        assertEquals(false, SemanticExpression.compare(CheckOp.CONTAINS, "注文を確定する", "取消"))
        // 正規表現は全体一致（フィールドの pattern と同じ規則）
        assertEquals(true, SemanticExpression.compare(CheckOp.MATCHES, "UC-001", "UC-[0-9]{3}"))
        assertEquals(false, SemanticExpression.compare(CheckOp.MATCHES, "UC-0012", "UC-[0-9]{3}"))
    }

    @Test
    fun `正規表現として読めない右辺は判定保留になる`() {
        assertNull(SemanticExpression.compare(CheckOp.MATCHES, "abc", "([a-z"))
    }

    @Test
    fun `oneOfはどれかと等しければ成立し得られない選択肢が残れば判定保留になる`() {
        assertEquals(true, SemanticExpression.test(CheckOp.ONE_OF, "draft", listOf("draft", "approved")))
        assertEquals(false, SemanticExpression.test(CheckOp.ONE_OF, "rejected", listOf("draft", "approved")))
        // 等しい選択肢があれば、得られない選択肢があっても成立
        assertEquals(true, SemanticExpression.test(CheckOp.ONE_OF, "draft", listOf(null, "draft")))
        // 等しい選択肢がなく、得られない選択肢が残る＝その選択肢が等しかったかもしれない
        assertNull(SemanticExpression.test(CheckOp.ONE_OF, "rejected", listOf("draft", null)))
    }

    @Test
    fun `oneOf以外のtestは右辺1つとの比較で右辺が得られなければ判定保留になる`() {
        assertEquals(true, SemanticExpression.test(CheckOp.LTE, "3", listOf("10")))
        assertNull(SemanticExpression.test(CheckOp.LTE, "3", listOf(null)))
    }

    @Test
    fun `値を1つ受け取る計算は文字数と大文字小文字と数値の読み直し`() {
        assertEquals("5", SemanticExpression.unary(ValueExpr.UnaryFn.LENGTH, "注文を確定"))
        assertEquals("ORDER_ID", SemanticExpression.unary(ValueExpr.UnaryFn.UPPER, "order_id"))
        assertEquals("order_id", SemanticExpression.unary(ValueExpr.UnaryFn.LOWER, "Order_ID"))
        // number は表記を揃える（eq は文字列の等値のため、数値どうしを等値で比べる前に通す）
        assertEquals("12", SemanticExpression.unary(ValueExpr.UnaryFn.NUMBER, "12.0"))
        assertEquals("1200", SemanticExpression.unary(ValueExpr.UnaryFn.NUMBER, "1.2e3"))
        assertEquals("0", SemanticExpression.unary(ValueExpr.UnaryFn.NUMBER, "0.00"))
        assertNull(SemanticExpression.unary(ValueExpr.UnaryFn.NUMBER, "十二"))
    }

    @Test
    fun `四則演算は数値として読めない値や0での割り算で得られない`() {
        assertEquals("15", SemanticExpression.arith(ValueExpr.ArithOp.ADD, listOf("3", "12")))
        assertEquals("6.5", SemanticExpression.arith(ValueExpr.ArithOp.ADD, listOf("1", "2.5", "3")))
        assertEquals("-9", SemanticExpression.arith(ValueExpr.ArithOp.SUBTRACT, listOf("3", "12")))
        assertEquals("36", SemanticExpression.arith(ValueExpr.ArithOp.MULTIPLY, listOf("3", "12.0")))
        assertEquals("2.5", SemanticExpression.arith(ValueExpr.ArithOp.DIVIDE, listOf("5", "2")))
        // 小数の誤差を持ち込まない（0.1 + 0.2 = 0.3）
        assertEquals("0.3", SemanticExpression.arith(ValueExpr.ArithOp.ADD, listOf("0.1", "0.2")))
        assertNull(SemanticExpression.arith(ValueExpr.ArithOp.ADD, listOf("3", "多数")))
        assertNull(SemanticExpression.arith(ValueExpr.ArithOp.DIVIDE, listOf("3", "0")))
    }

    @Test
    fun `空の判定は書かれていない値と空文字と空の入れ物`() {
        assertTrue(SemanticExpression.isEmpty(null))
        assertTrue(SemanticExpression.isEmpty(YNode.Scalar(null)))
        assertTrue(SemanticExpression.isEmpty(YNode.Scalar("")))
        assertTrue(SemanticExpression.isEmpty(YNode.Seq()))
        assertTrue(SemanticExpression.isEmpty(YNode.Map()))
        assertFalse(SemanticExpression.isEmpty(YNode.Scalar("x")))
        assertFalse(SemanticExpression.isEmpty(YNode.Scalar(0)))
        assertFalse(SemanticExpression.isEmpty(YNode.Seq(mutableListOf(YNode.Scalar("x")))))
    }
}
