package semantic

import model.DocumentId
import model.YNode
import model.YPath
import model.getAt
import model.scalarText
import schema.CheckOp
import schema.DerivationSelector
import schema.EntityRole

/**
 * Core Calculus の Expression／要素述語の共有評価部（P29 Phase 3・R29-05。CORE-CALCULUS §7・§8）。
 * 「出現割当て σ が選んだ1出現からの単一値の読み出し（Get）」「単一値どうしの比較（要素述語）」
 * 「式側の決定的な出現選択（CALC-19）」の単一実装——整合検証（consistency の assert）・check 判定述語・
 * where 選択述語・期待値導出・テンプレート健全性検査が共有する（1つの意味には1つの評価器）。
 * 集合性はここへ持ち込まない: どの出現を読むかの量化（where=存在量化／check=反例量化。CALC-13）は
 * 述語境界＝呼び出し側の責務で、ここは σ 固定の単一値計算だけを担う（CALC-12）。
 */
object SemanticExpression {

    /** σ の割当て先1件: 出現の座標（文書＋要素パス）。 */
    data class Occurrence(val document: DocumentId, val path: YPath)

    /** Get（ノード）: 出現要素からの相対パスのノード。ツリーが読めない・パスが無ければ null。 */
    fun nodeAt(tree: (DocumentId) -> YNode?, occ: Occurrence, path: YPath): YNode? =
        tree(occ.document)?.let { getAt(it, occ.path + path) }

    /** Get（単一値）: 出現要素からの相対パスのスカラー値。値集合は作らない。 */
    fun textAt(tree: (DocumentId) -> YNode?, occ: Occurrence, path: YPath): String? =
        scalarText(nodeAt(tree, occ, path))

    /**
     * 要素述語（σ 固定の単一値比較。3値）: eq / ne はスカラー文字列の等値、順序系（lte / gte / lt / gt）は
     * 数値比較で、数値として読めない辺があれば null（評価不能＝Unknown。呼び出し側は判定保留＝黙る）。
     */
    fun compare(op: CheckOp, left: String, right: String): Boolean? = when (op) {
        CheckOp.EQ -> left == right
        CheckOp.NE -> left != right
        else -> {
            val l = left.toDoubleOrNull()
            val r = right.toDoubleOrNull()
            if (l == null || r == null) null
            else when (op) {
                CheckOp.LTE -> l <= r
                CheckOp.GTE -> l >= r
                CheckOp.LT -> l < r
                CheckOp.GT -> l > r
                else -> null
            }
        }
    }

    /**
     * 式側の σ 選択方針（CALC-19・意図的差分 D-17）: Expression は出現を量化せず、selector に一致する
     * 出現から「定義出現優先→索引順」の決定的な先勝ちで1つ選ぶ（CALC-11 の代表出現と同じ規則）。
     */
    fun select(occs: List<ScannedId>, selector: DerivationSelector, rel: (DocumentId) -> String): ScannedId? =
        preferDefining(occs).firstOrNull { selector.matches(it.schemaId, rel(it.document)) }

    /** 定義出現を先頭へ寄せる安定整列（索引順は保つ）。 */
    fun preferDefining(occs: List<ScannedId>): List<ScannedId> =
        occs.sortedBy { if (it.role == EntityRole.DEFINING) 0 else 1 }
}
