package dev.toros.yde.semantic

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.semantic.schema.Projection
import dev.toros.yde.semantic.schema.QueryDir
import dev.toros.yde.semantic.schema.QueryEdgeStep
import dev.toros.yde.semantic.schema.QueryNodeKind
import dev.toros.yde.semantic.schema.ValueExpr

/**
 * 1つの環境での値の式の評価（P31・R31-01。CORE-CALCULUS §7 の Expression＝σ を固定した単一値の純粋な計算）。
 * 以前はクエリの評価器（SemanticQuery）の中に閉じていた評価を、環境（Environment）を渡せば検査の外——新しい文書の
 * ファイル名（DocumentScaffolds）・変換のひな型など——からも同じ意味で使えるようにした。クエリの check 判定も、
 * 解の束縛と出現の割当てを環境に写して、ここを呼ぶ（1つの意味には1つの評価器）。
 * - いまの要素（current）: `of` を省略した読み出し・参照の本数・書かれた場所の数の相手。無ければ「得られない」
 * - 予約の名前（reserved）: 短い書き方の穴 `{id}` `{title}` を、要素の項目より先に埋める
 * - 得られない値は null（判定保留の流儀）。埋まらなかった穴の名前は Result.missing に残す（作成をエラーにする文面の材料）
 */
object SemanticValues {

    /** 予約の名前（ファイル名などの短い書き方で、要素の項目より先に埋まる）。 */
    const val RESERVED_ID = "id"
    const val RESERVED_TITLE = "title"

    /** 評価の環境。 */
    class Environment(
        val tree: (DocumentId) -> YNode?,
        val rel: (DocumentId) -> String = { it.value },
        /** いまの要素（無ければ、of の省略は「得られない」）。 */
        val current: SemanticQueryNodeRef? = null,
        /** いまの要素の出現（値を読む場所）。 */
        val currentOccurrence: SemanticExpression.Occurrence? = null,
        /** 名前を付けた束縛（クエリの解の束縛）。 */
        val nodes: Map<String, SemanticQueryNodeRef> = emptyMap(),
        /** 「束縛と文書の指定」ごとに決めた出現（クエリの check が σ を固定するときに渡す。null の値＝候補なし）。 */
        val assignment: Map<ValueExpr.ScopeRef, SemanticExpression.Occurrence?> = emptyMap(),
        /** グラフ（参照の本数・書かれた場所の数・`in` 付きのいまの要素の読み出しに使う。無ければ、それらは「得られない」）。 */
        val graph: SemanticEntityGraph? = null,
        /** その ID へ入ってくる参照（refCount の dir: in）。 */
        val refsInto: (String) -> List<ScannedRef> = { emptyList() },
        /** 解にわたる集計（クエリの評価器だけが担う。無ければ「得られない」）。 */
        val aggregate: ((ValueExpr.Aggregate) -> SemanticCheckSide?)? = null,
        /** 予約の名前の値（{id} {title} など）。 */
        val reserved: Map<String, String> = emptyMap(),
        /** 穴に入る値の直し方（ファイル名に使える文字に直す、など）。 */
        val holeValue: (String) -> String = { it },
        /** 得られない読み出しと埋まらない穴の代わりの値（定義の点検で代表値を得るためのもの。null＝得られない）。 */
        val fallback: String? = null,
    )

    /** 評価の結果: 値（得られなければ null）と、埋まらなかった穴・読めなかった項目の名前（初出順）。 */
    class Result(val side: SemanticCheckSide?, val missing: List<String>)

    /** 式を評価する（得られなければ null）。 */
    fun evaluate(expr: ValueExpr, env: Environment): SemanticCheckSide? = evaluateDetailed(expr, env).side

    /** 式を評価して、埋まらなかった穴の名前も返す。 */
    fun evaluateDetailed(expr: ValueExpr, env: Environment): Result {
        val missing = LinkedHashSet<String>()
        val side = Eval(env, missing).eval(expr)
        return Result(side, missing.toList())
    }

    private class Eval(private val env: Environment, private val missing: MutableSet<String>) {

        private fun node(of: String?): SemanticQueryNodeRef? = if (of == null) env.current else env.nodes[of]

        /**
         * 読み出しの相手の出現: 「束縛と文書の指定」に決めた出現があればそれ（null＝候補なし）。of を省略していれば
         * いまの要素の出現（`in` があれば、同じ ID の出現から指定に合うものを、定義した側を優先して1つ＝CALC-19）。
         */
        private fun occurrence(ref: ValueExpr.ScopeRef): SemanticExpression.Occurrence? {
            if (ref in env.assignment) return env.assignment[ref]
            if (ref.of != null) return null
            val selector = ref.inDoc ?: return env.currentOccurrence
            val id = env.current?.id ?: return null
            val occs = env.graph?.entities?.get(id)?.occurrences.orEmpty()
            return SemanticExpression.select(occs, selector, env.rel)?.let { SemanticExpression.Occurrence(it.document, it.path) }
        }

        private fun fallbackSide(): SemanticCheckSide? = env.fallback?.let { SemanticCheckSide(text = it, raw = it) }

        fun eval(expr: ValueExpr): SemanticCheckSide? = when (expr) {
            is ValueExpr.Get -> {
                val occ = occurrence(ValueExpr.ScopeRef(expr.of, expr.inDoc))
                val text = occ?.let { SemanticExpression.textAt(env.tree, it, expr.path) }
                if (text != null && occ != null) {
                    SemanticCheckSide(
                        text = text,
                        bind = expr.of, id = node(expr.of)?.id,
                        document = occ.document, occPath = occ.path, path = expr.path,
                        raw = text, sourceDocument = occ.document, sourcePath = occ.path + expr.path,
                    )
                } else {
                    missing += expr.path.pretty()
                    fallbackSide()
                }
            }
            is ValueExpr.Literal -> expr.value?.let { SemanticCheckSide(text = it, raw = it) }
            is ValueExpr.Format -> eval(expr.template)?.let { template -> format(expr, template) }
            is ValueExpr.MapValue -> eval(expr.input)?.let { it.copy(text = Projection.applyMap(it.text, expr.using)) }
            is ValueExpr.First -> expr.options.firstNotNullOfOrNull { eval(it) }
            is ValueExpr.Unary -> eval(expr.input)?.let { input ->
                SemanticExpression.unary(expr.fn, input.text)?.let { text ->
                    input.copy(text = text, raw = input.raw ?: input.text, note = expr.fn.label ?: input.note)
                }
            }
            // 参照の本数（SP-03）: グラフの隣接から数える。出ていく参照は相手が見つからないものも数え（CALC-20）、
            // 入ってくる参照はエンティティへの参照を数える。値の読み出しではないため、出現は選ばない
            is ValueExpr.RefCount -> node(expr.of)?.takeIf { it.kind == QueryNodeKind.ENTITY }?.let { node ->
                val graph = env.graph ?: return@let null
                val refs = when (expr.dir) {
                    QueryDir.OUT -> graph.ownRefs(node.id)
                    QueryDir.IN -> env.refsInto(node.id)
                }
                val n = refs.count { ref ->
                    (expr.relation == null || ref.relation == expr.relation) &&
                        (expr.via == null || QueryEdgeStep.viaMatches(expr.via, ref.viaKey, ref.path))
                }
                SemanticCheckSide(text = n.toString(), id = node.id, raw = n.toString())
            }
            // 書かれた場所の数（SP-05）: その ID が書かれている場所のうち、指定（スキーマ・ファイル・役割）に合うものを数える。
            // distinct: type は、合う場所で宣言されているエンティティ型の種類を数える（元の値に、型の並びを添える）
            is ValueExpr.OccurrenceCount -> node(expr.of)?.takeIf { it.kind == QueryNodeKind.ENTITY }?.let { node ->
                val graph = env.graph ?: return@let null
                val occurrences = graph.entities[node.id]?.occurrences.orEmpty()
                    .filter { expr.matches(it.schemaId, env.rel(it.document), it.role) }
                if (expr.distinct == null) {
                    SemanticCheckSide(text = occurrences.size.toString(), id = node.id, raw = occurrences.size.toString())
                } else {
                    val types = occurrences.mapNotNull { it.entityType }.distinct()
                    SemanticCheckSide(text = types.size.toString(), id = node.id, raw = types.joinToString(", "))
                }
            }
            is ValueExpr.Aggregate -> env.aggregate?.invoke(expr)
            is ValueExpr.Arith -> {
                val operands = expr.operands.map { eval(it) ?: return null }
                SemanticExpression.arith(expr.op, operands.map { it.text })?.let { text ->
                    // 計算結果は特定の1か所に書かれた値ではない——式（3 + 12）を元の値として示し、移動先は最初の値の場所
                    val first = operands.firstOrNull { it.sourceDocument != null }
                    SemanticCheckSide(
                        text = text,
                        id = first?.id,
                        raw = operands.joinToString(" ${expr.op.symbol} ") { it.text },
                        sourceDocument = first?.sourceDocument, sourcePath = first?.sourcePath,
                    )
                }
            }
        }

        /**
         * テンプレートの展開: 入れ物は「with で名付けた値 → 予約の名前 → 入れ物（scopes。空ならいまの要素）」の順に近い。
         * 穴が埋まらなければ「得られない」（fallback があれば、それで埋めて続ける）。
         */
        private fun format(expr: ValueExpr.Format, template: SemanticCheckSide): SemanticCheckSide? {
            val withSides = LinkedHashMap<String, SemanticCheckSide>()
            for ((name, e) in expr.with) eval(e)?.let { withSides[name] = it }
            val scopeNodes = ArrayList<Pair<SemanticExpression.Occurrence?, YNode>>()
            val withIndex = if (withSides.isEmpty()) -1 else scopeNodes.size.also {
                scopeNodes += null to YNode.Map(LinkedHashMap(withSides.mapValues { YNode.Scalar(it.value.text) }))
            }
            val reservedIndex = if (env.reserved.isEmpty()) -1 else scopeNodes.size.also {
                scopeNodes += null to YNode.Map(LinkedHashMap(env.reserved.mapValues { YNode.Scalar(it.value) }))
            }
            val refs = expr.scopes.ifEmpty { listOf(ValueExpr.ScopeRef(null, null)) }
            for (ref in refs) {
                val occ = occurrence(ref) ?: continue
                SemanticExpression.nodeAt(env.tree, occ, emptyList())?.let { scopeNodes += occ to it }
            }
            var evaluated = Projection.evaluate(template.text, scopeNodes.map { it.second }, env.holeValue)
            if (!evaluated.complete) {
                evaluated.resolutions.filter { it.value == null }.forEach { missing += it.placeholder }
                val fb = env.fallback ?: return null
                evaluated = evaluated.copy(text = Projection.fillRemaining(evaluated.text, fb), complete = true)
            }
            return template.copy(
                text = evaluated.text,
                raw = template.text,
                inputs = evaluated.resolutions.map { r ->
                    when (r.scopeIndex) {
                        withIndex -> withSides[r.placeholder].let { w ->
                            SemanticValueInput(r.placeholder, r.value, w?.sourceDocument, w?.sourcePath)
                        }
                        reservedIndex, null -> SemanticValueInput(r.placeholder, r.value, null, null)
                        else -> {
                            val occ = scopeNodes.getOrNull(r.scopeIndex)?.first
                            val inputPath = occ?.let { o -> runCatching { o.path + parseYPath(r.placeholder) }.getOrNull() }
                            SemanticValueInput(r.placeholder, r.value, occ?.document, inputPath)
                        }
                    }
                },
            )
        }
    }
}
