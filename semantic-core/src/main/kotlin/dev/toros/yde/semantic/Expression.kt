package dev.toros.yde.semantic

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.yaml.structure.StructureValidator
import dev.toros.yde.semantic.schema.CheckOp
import dev.toros.yde.semantic.schema.DerivationSelector
import dev.toros.yde.semantic.schema.EntityRole
import dev.toros.yde.semantic.schema.PathPattern
import dev.toros.yde.semantic.schema.ValueExpr
import java.math.BigDecimal
import java.math.MathContext
import java.util.concurrent.ConcurrentHashMap

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

    /** 集計の材料 1 件: 出現要素からの相対パス（placeholder）・値（書かれていなければ null）・絶対座標（要素が読めなければ null）。 */
    data class Collected(val placeholder: String, val value: String?, val document: DocumentId?, val path: YPath?)

    /**
     * Get（複数の場所）: 出現要素からのパスのパターンに合う場所の値（PathPattern.expand。`[*]` は配列の全ての要素に広がる）。
     * σ は固定のまま（出現は 1 つ）で、集合は 1 つの項の中にだけ現れる。出現の要素そのものが読めなければ値なしの 1 件
     * （得られない理由を、呼び出し側が場所ごとに扱えるように）。
     */
    fun collect(tree: (DocumentId) -> YNode?, occ: Occurrence, pattern: List<Any>): List<Collected> {
        val element = tree(occ.document)?.let { getAt(it, occ.path) }
            ?: return listOf(Collected(PathPattern.pretty(pattern), null, null, null))
        return PathPattern.expand(element, pattern).map { rel ->
            Collected(rel.pretty(), scalarText(getAt(element, rel)), occ.document, occ.path + rel)
        }
    }

    /**
     * 要素述語（σ 固定の単一値比較。3値）: eq / ne はスカラー文字列の等値、順序系（lte / gte / lt / gt）は
     * 数値比較で、数値として読めない辺があれば null（評価不能＝Unknown。呼び出し側は判定保留＝黙る）。
     * 文字列の条件は matches（正規表現の全体一致。正規表現として読めなければ null）・startsWith・endsWith・contains。
     * oneOf は選択肢が複数あるため test を使う（ここでは選択肢1つとの等値として扱う）。
     */
    fun compare(op: CheckOp, left: String, right: String): Boolean? = when (op) {
        CheckOp.EQ, CheckOp.ONE_OF -> left == right
        CheckOp.NE -> left != right
        CheckOp.MATCHES -> regexOf(right)?.matches(left)
        CheckOp.STARTS_WITH -> left.startsWith(right)
        CheckOp.ENDS_WITH -> left.endsWith(right)
        CheckOp.CONTAINS -> left.contains(right)
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
     * 値の条件の判定（3値）。rights は右辺の値（得られなかった値は null）。oneOf 以外は右辺1つとの compare。
     * oneOf は「どれかと等しければ成立」——等しい選択肢がなく、得られなかった選択肢が残るなら null
     * （その選択肢が等しかったかもしれないため判定保留）。
     */
    fun test(op: CheckOp, left: String, rights: List<String?>): Boolean? {
        if (op != CheckOp.ONE_OF) return rights.firstOrNull()?.let { compare(op, left, it) }
        if (rights.any { it != null && it == left }) return true
        return if (rights.any { it == null }) null else false
    }

    /** 値を1つ受け取る計算（SP-10）。得られなければ null（number: 数値として読めない）。 */
    fun unary(fn: ValueExpr.UnaryFn, text: String): String? = when (fn) {
        // 文字数の数え方は、フィールドの maxLength の検証と同じ（String.length）
        ValueExpr.UnaryFn.LENGTH -> text.length.toString()
        ValueExpr.UnaryFn.UPPER -> text.uppercase()
        ValueExpr.UnaryFn.LOWER -> text.lowercase()
        ValueExpr.UnaryFn.NUMBER -> numberOf(text)?.let(::numberText)
    }

    /**
     * 四則演算（SP-10）。どれかが数値として読めない・0 で割る場合は null（得られない＝判定保留）。
     * 足し算と掛け算は左から順にまとめる。割り切れない割り算は有効桁16桁で丸める。
     */
    fun arith(op: ValueExpr.ArithOp, operands: List<String>): String? {
        val numbers = operands.map { numberOf(it) ?: return null }
        if (numbers.isEmpty()) return null
        val result = numbers.drop(1).fold(numbers.first()) { acc, n ->
            when (op) {
                ValueExpr.ArithOp.ADD -> acc + n
                ValueExpr.ArithOp.SUBTRACT -> acc - n
                ValueExpr.ArithOp.MULTIPLY -> acc * n
                ValueExpr.ArithOp.DIVIDE -> if (n.signum() == 0) return null else acc.divide(n, MathContext.DECIMAL64)
            }
        }
        return numberText(result)
    }

    /** 集計の結果: 値と、最小・最大で選ばれた材料の番号（合計・件数では null）。 */
    data class Aggregated(val text: String, val winner: Int?)

    /**
     * 集計（SP-03）: 合計・最小・最大・件数。kind は順序の種類（数値か日付）。どれかがその種類として読めなければ null
     * （得られない＝判定保留）。値が1つも無いとき、合計は 0・最小と最大は null。件数は値を読まない。
     */
    fun aggregate(fn: ValueExpr.AggregateFn, values: List<String>, kind: ValueExpr.OrderKind = ValueExpr.OrderKind.NUMBER): String? =
        aggregateDetailed(fn, values, kind)?.text

    /**
     * 集計（結果の出どころつき）。最小・最大は同じ値が複数あれば最初の材料を選ぶ（決定的）。
     * 日付（YYYY-MM-DD）は文字の順がそのまま日付の順なので、読めることを確かめて文字で比べ、値はそのまま返す。
     */
    fun aggregateDetailed(fn: ValueExpr.AggregateFn, values: List<String>, kind: ValueExpr.OrderKind): Aggregated? {
        if (fn == ValueExpr.AggregateFn.COUNT) return Aggregated(values.size.toString(), null)
        return when (kind) {
            ValueExpr.OrderKind.NUMBER -> {
                val numbers = values.map { numberOf(it) ?: return null }
                when (fn) {
                    ValueExpr.AggregateFn.SUM -> Aggregated(numberText(numbers.fold(BigDecimal.ZERO) { acc, n -> acc + n }), null)
                    ValueExpr.AggregateFn.MIN -> numbers.withIndex().minByOrNull { it.value }?.let { Aggregated(numberText(it.value), it.index) }
                    ValueExpr.AggregateFn.MAX -> numbers.withIndex().maxByOrNull { it.value }?.let { Aggregated(numberText(it.value), it.index) }
                    ValueExpr.AggregateFn.COUNT -> null
                }
            }
            ValueExpr.OrderKind.DATE -> {
                val dates = values.map { dateOf(it) ?: return null }
                when (fn) {
                    ValueExpr.AggregateFn.MIN -> dates.withIndex().minByOrNull { it.value }?.let { Aggregated(it.value, it.index) }
                    ValueExpr.AggregateFn.MAX -> dates.withIndex().maxByOrNull { it.value }?.let { Aggregated(it.value, it.index) }
                    // 日付は足せない（定義の読み取りが弾く）
                    ValueExpr.AggregateFn.SUM, ValueExpr.AggregateFn.COUNT -> null
                }
            }
        }
    }

    /** 順序の種類として読めるか（数値・日付）。 */
    fun readable(kind: ValueExpr.OrderKind, text: String): Boolean = when (kind) {
        ValueExpr.OrderKind.NUMBER -> numberOf(text) != null
        ValueExpr.OrderKind.DATE -> dateOf(text) != null
    }

    /** 日付として読めれば、その表記（前後の空白を除く）。規則は date 型の項目の検証と同じ 1 か所（StructureValidator.isDateText）。 */
    private fun dateOf(text: String): String? = text.trim().takeIf { StructureValidator.isDateText(it) }

    /** 項の流れ書きの文字（配列は `[a, b]`・マップは `{k: v}`。文面と表示に使う。スカラーはその文字）。 */
    fun flowText(node: YNode?): String = when (node) {
        null -> ""
        is YNode.Scalar -> node.value?.toString() ?: ""
        is YNode.Seq -> node.items.joinToString(", ", "[", "]") { flowText(it) }
        is YNode.Map -> node.entries.entries.joinToString(", ", "{", "}") { (k, v) -> "$k: ${flowText(v)}" }
    }

    /** 「空」の判定（where の empty）: 書かれていない・空文字・null・空の配列・空の object。 */
    fun isEmpty(node: YNode?): Boolean = when (node) {
        null -> true
        is YNode.Scalar -> scalarText(node) == null
        is YNode.Seq -> node.items.isEmpty()
        is YNode.Map -> node.entries.isEmpty()
    }

    private fun numberOf(text: String): BigDecimal? = text.trim().toBigDecimalOrNull()

    /** 数値の表記を揃える（末尾の 0 と指数表記を落とす。`12.0` → `12`）。 */
    private fun numberText(n: BigDecimal): String = if (n.signum() == 0) "0" else n.stripTrailingZeros().toPlainString()

    private val regexCache = ConcurrentHashMap<String, Regex>()

    private fun regexOf(pattern: String): Regex? =
        regexCache.computeIfAbsent(pattern) { runCatching { Regex(it) }.getOrNull() ?: INVALID }.takeIf { it !== INVALID }

    /** 正規表現として読めなかった印（ConcurrentHashMap は null を持てないため）。 */
    private val INVALID = Regex("")

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
