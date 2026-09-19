package semantic

import model.DocumentId
import model.YNode
import model.parseYPath
import schema.CheckOp
import schema.ConsistencyAssert
import schema.DerivationSelector
import schema.EntityRole
import yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Expression／要素述語の共有評価部（P29 Phase 3・R29-05）のテスト。
 * 整合検証の assert・check 判定述語・where 選択述語・期待値導出が共有する単一実装の規則を固定する。
 */
class SemanticExpressionTest {

    private fun idOf(doc: String, schemaId: String, role: EntityRole? = null) =
        ScannedId("A", DocumentId(doc), emptyList(), null, schemaId, doc, role = role)

    @Test
    fun `等値系はスカラー文字列の比較で常に評価できる`() {
        assertEquals(true, SemanticExpression.compare(CheckOp.EQ, "abc", "abc"))
        assertEquals(false, SemanticExpression.compare(CheckOp.EQ, "10", "10.0"))  // 数値の正規化はしない
        assertEquals(true, SemanticExpression.compare(CheckOp.NE, "a", "b"))
    }

    @Test
    fun `順序系は数値比較で数値として読めない辺があれば評価不能になる`() {
        assertEquals(true, SemanticExpression.compare(CheckOp.LTE, "10", "10.0"))
        assertEquals(false, SemanticExpression.compare(CheckOp.LT, "10", "10"))
        assertEquals(true, SemanticExpression.compare(CheckOp.GT, "11", "9"))
        assertEquals(false, SemanticExpression.compare(CheckOp.GTE, "1", "2"))
        assertNull(SemanticExpression.compare(CheckOp.LTE, "abc", "10"))  // Unknown＝呼び出し側は判定保留
        assertNull(SemanticExpression.compare(CheckOp.GT, "10", ""))
    }

    @Test
    fun `整合検証のassertはcheckと同じ要素述語へ落ちる`() {
        assertEquals(CheckOp.EQ, ConsistencyAssert.EQUALS.op)
        assertEquals(CheckOp.LTE, ConsistencyAssert.LTE.op)
        assertEquals(CheckOp.GTE, ConsistencyAssert.GTE.op)
    }

    @Test
    fun `式側の出現選択は定義出現優先で同格なら索引順`() {
        val detail = idOf("detail.yaml", "spec", EntityRole.DETAIL)
        val defining = idOf("list.yaml", "spec", EntityRole.DEFINING)
        val other = idOf("other.yaml", "memo")
        val rel: (DocumentId) -> String = { it.value }
        // 詳細化出現が索引で先にあっても定義出現を選ぶ（CALC-19）
        assertEquals(defining, SemanticExpression.select(listOf(detail, defining, other), DerivationSelector(schemaId = "spec"), rel))
        // selector に一致する定義出現がなければ索引順の先頭
        assertEquals(detail, SemanticExpression.select(listOf(detail, other), DerivationSelector(schemaId = "spec"), rel))
        assertEquals(other, SemanticExpression.select(listOf(detail, defining, other), DerivationSelector(fileGlob = "other.yaml"), rel))
        assertNull(SemanticExpression.select(listOf(detail), DerivationSelector(schemaId = "none"), rel))
    }

    @Test
    fun `Getは出現要素からの相対パスの単一値を読む`() {
        val doc = DocumentId("a.yaml")
        val root = YamlIO.parse("items:\n- { id: A, size: 10 }\n- { id: B }\n")
        val tree: (DocumentId) -> YNode? = { if (it == doc) root else null }
        val occ = SemanticExpression.Occurrence(doc, parseYPath("items[0]"))
        assertEquals("10", SemanticExpression.textAt(tree, occ, parseYPath("size")))
        assertNull(SemanticExpression.textAt(tree, occ, parseYPath("missing")))
        // ツリーが読めない文書は値なし（呼び出し側の量化では候補から外れる）
        assertNull(SemanticExpression.textAt(tree, SemanticExpression.Occurrence(DocumentId("x.yaml"), emptyList()), parseYPath("size")))
    }
}
