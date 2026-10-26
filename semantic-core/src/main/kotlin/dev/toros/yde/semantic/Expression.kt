package dev.toros.yde.semantic

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.semantic.schema.CheckOp
import dev.toros.yde.semantic.schema.DerivationSelector
import dev.toros.yde.semantic.schema.EntityRole
import dev.toros.yde.semantic.schema.PathPattern
import dev.toros.yde.semantic.schema.ValueExpr
import dev.toros.yde.semantic.schema.ValueSemantics
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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
     * 順序の種類 kind（数値か、日付・時刻・日時＝意味の基底型の値の表現）で比べ、その種類として読めない辺があれば
     * null（評価不能＝Unknown。呼び出し側は判定保留＝黙る）。kind は呼び出し側が項目の型か `type` の宣言から決める（既定は数値）。
     * 文字列の条件は matches（正規表現の全体一致。正規表現として読めなければ null）・startsWith・endsWith・contains。
     * oneOf は選択肢が複数あるため test を使う（ここでは選択肢1つとの等値として扱う）。
     */
    fun compare(op: CheckOp, left: String, right: String, kind: ValueExpr.OrderKind = ValueExpr.OrderKind.NUMBER): Boolean? = when (op) {
        CheckOp.EQ, CheckOp.ONE_OF -> left == right
        CheckOp.NE -> left != right
        CheckOp.MATCHES -> regexOf(right)?.matches(left)
        CheckOp.STARTS_WITH -> left.startsWith(right)
        CheckOp.ENDS_WITH -> left.endsWith(right)
        CheckOp.CONTAINS -> left.contains(right)
        else -> {
            val order = order(kind, left, right)
            if (order == null) null
            else when (op) {
                CheckOp.LTE -> order <= 0
                CheckOp.GTE -> order >= 0
                CheckOp.LT -> order < 0
                CheckOp.GT -> order > 0
                else -> null
            }
        }
    }

    /** 2 つの値の順序（kind の種類として読めなければ null）。数値は数として（表記は toDouble の読み方のまま）、他は値の表現で比べる。 */
    private fun order(kind: ValueExpr.OrderKind, left: String, right: String): Int? {
        val value = kind.value ?: run {
            val l = left.toDoubleOrNull() ?: return null
            val r = right.toDoubleOrNull() ?: return null
            return l.compareTo(r)
        }
        return value.compare(left, right)
    }

    /**
     * 値の条件の判定（3値）。rights は右辺の値（得られなかった値は null）。oneOf 以外は右辺1つとの compare。
     * oneOf は「どれかと等しければ成立」——等しい選択肢がなく、得られなかった選択肢が残るなら null
     * （その選択肢が等しかったかもしれないため判定保留）。kind は順序系の演算子が比べる種類。
     */
    fun test(op: CheckOp, left: String, rights: List<String?>, kind: ValueExpr.OrderKind = ValueExpr.OrderKind.NUMBER): Boolean? {
        if (op != CheckOp.ONE_OF) return rights.firstOrNull()?.let { compare(op, left, it, kind) }
        if (rights.any { it != null && it == left }) return true
        return if (rights.any { it == null }) null else false
    }

    /**
     * 値を1つ受け取る計算（SP-10）。得られなければ null（number: 数として読めない。読み方は numberOf＝桁区切り・通貨記号・全角を許す。
     * year / month / day / yearMonth: 日付か日時の正準の形として読めない）。
     */
    fun unary(fn: ValueExpr.UnaryFn, text: String): String? = when (fn) {
        // 文字数の数え方は、フィールドの maxLength の検証と同じ（String.length）
        ValueExpr.UnaryFn.LENGTH -> text.length.toString()
        ValueExpr.UnaryFn.UPPER -> text.uppercase()
        ValueExpr.UnaryFn.LOWER -> text.lowercase()
        ValueExpr.UnaryFn.NUMBER -> numberOf(text)?.let(::numberText)
        ValueExpr.UnaryFn.YEAR -> dateOf(text)?.year?.toString()
        ValueExpr.UnaryFn.MONTH -> dateOf(text)?.monthValue?.toString()
        ValueExpr.UnaryFn.DAY -> dateOf(text)?.dayOfMonth?.toString()
        ValueExpr.UnaryFn.YEAR_MONTH -> dateOf(text)?.format(YEAR_MONTH_FORMAT)
    }

    private val YEAR_MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

    /**
     * 日付の部分を取り出すための読み（日付 `YYYY-MM-DD` か日時 `YYYY-MM-DD HH:MM[:SS]`。意味の基底型の正準の形と存在の規則を共有する）。
     * 時刻だけの値や、`2026/10/1` のような表記は読まない（表記から意味を決めない）。
     */
    private fun dateOf(text: String): LocalDate? = when {
        ValueSemantics.DATE.reads(text) -> ValueSemantics.DATE.parse(text) as? LocalDate
        ValueSemantics.DATETIME.reads(text) -> (ValueSemantics.DATETIME.parse(text) as? LocalDateTime)?.toLocalDate()
        else -> null
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
     * 日付・時刻・日時は値の表現（ValueSemantics）の順序で比べ、値は書かれた表記のまま返す。
     */
    fun aggregateDetailed(fn: ValueExpr.AggregateFn, values: List<String>, kind: ValueExpr.OrderKind): Aggregated? {
        if (fn == ValueExpr.AggregateFn.COUNT) return Aggregated(values.size.toString(), null)
        val value = kind.value
        if (value == null) {
            val numbers = values.map { numberOf(it) ?: return null }
            return when (fn) {
                ValueExpr.AggregateFn.SUM -> Aggregated(numberText(numbers.fold(BigDecimal.ZERO) { acc, n -> acc + n }), null)
                ValueExpr.AggregateFn.MIN -> numbers.withIndex().minByOrNull { it.value }?.let { Aggregated(numberText(it.value), it.index) }
                ValueExpr.AggregateFn.MAX -> numbers.withIndex().maxByOrNull { it.value }?.let { Aggregated(numberText(it.value), it.index) }
                ValueExpr.AggregateFn.COUNT -> null
            }
        }
        val keyed = values.map { text -> text.trim().let { t -> (value.orderKey(t) ?: return null) to t } }
        return when (fn) {
            ValueExpr.AggregateFn.MIN -> keyed.withIndex().minByOrNull { it.value.first }?.let { Aggregated(it.value.second, it.index) }
            ValueExpr.AggregateFn.MAX -> keyed.withIndex().maxByOrNull { it.value.first }?.let { Aggregated(it.value.second, it.index) }
            // 日付などは足せない（定義の読み取りが弾く）
            ValueExpr.AggregateFn.SUM, ValueExpr.AggregateFn.COUNT -> null
        }
    }

    /** 順序の種類として読めるか（数値か、意味の基底型の値）。 */
    fun readable(kind: ValueExpr.OrderKind, text: String): Boolean =
        kind.value?.reads(text.trim()) ?: (numberOf(text) != null)

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

    /**
     * 数として読む（値の式の number・四則演算・集計と、変換の $number が共有する 1 つの読み方）。
     * 桁区切り（`12,000`）・通貨記号（`¥` `$`・末尾の `円`）・全角の数字と記号・`▲` `△` の負を許す。読めなければ null。
     */
    fun numberOf(text: String): BigDecimal? {
        var s = toHalfWidth(text.trim())
        if (s.isEmpty()) return null
        var negative = false
        if (s.startsWith("▲") || s.startsWith("△")) { negative = true; s = s.substring(1).trim() }
        if (s.startsWith("-")) { negative = !negative; s = s.substring(1).trim() } else if (s.startsWith("+")) s = s.substring(1).trim()
        s = s.removePrefix("¥").removePrefix("$").removeSuffix("円").trim().replace(",", "")
        val n = s.toBigDecimalOrNull() ?: return null
        return if (negative) n.negate() else n
    }

    /**
     * 年月日として読む（変換の $date）。`2021-8-21`・`2021/8/21`・`2021.8.21`・`20210821`・`2021年8月21日`（全角の数字も）。
     * 暦に無い日付（2 月 30 日など）と、それ以外の形は null。
     */
    fun readDate(text: String): LocalDate? {
        val s = toHalfWidth(text.trim())
        val m = DATE_PARTS.matchEntire(s) ?: DATE_COMPACT.matchEntire(s) ?: return null
        val (y, mo, d) = m.destructured
        return runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()
    }

    /** 年月日を形にする（pattern は y・M・d と区切り。null＝yyyy-MM-dd）。 */
    fun formatDate(date: LocalDate, pattern: String?): String =
        date.format(if (pattern == null) DateTimeFormatter.ISO_LOCAL_DATE else DateTimeFormatter.ofPattern(pattern))

    /** 前後の空白を落とし、続く空白（全角の空白も）を半角の空白 1 つにまとめる（変換の $trim）。 */
    fun squashSpaces(text: String): String = text.trim().replace(SPACES, " ")

    private val DATE_PARTS = Regex("""(\d{4})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})\s*日?""")
    private val DATE_COMPACT = Regex("""(\d{4})(\d{2})(\d{2})""")
    private val SPACES = Regex("""[\s\p{Z}]+""")  // Java の \s は全角の空白（U+3000）を含まないため \p{Z} も

    /** 全角の数字と記号を半角にする（数と日付の読み取り用）。 */
    private fun toHalfWidth(text: String): String = text.map { c ->
        when (c) {
            in '０'..'９' -> '0' + (c - '０')
            '．' -> '.'
            '，' -> ','
            '－', '−', '‐', '–', '—' -> '-'
            '＋' -> '+'
            '／' -> '/'
            '￥' -> '¥'
            '＄' -> '$'
            else -> c
        }
    }.joinToString("")

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
