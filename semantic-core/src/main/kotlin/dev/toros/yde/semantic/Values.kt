package dev.toros.yde.semantic

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.semantic.schema.DerivationSelector
import dev.toros.yde.semantic.schema.Projection
import dev.toros.yde.semantic.schema.QueryDir
import dev.toros.yde.semantic.schema.QueryEdgeStep
import dev.toros.yde.semantic.schema.QueryNodeKind
import dev.toros.yde.semantic.schema.QuerySpec
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
        /**
         * 「束縛と文書の指定」ごとに決めた出現（クエリの check が σ を固定するときに渡す。null の値＝候補なし）。
         * 無ければ、束縛の出現は定義出現を優先して 1 つ選ぶ（ビューの行の束縛。グラフが要る）。
         */
        val assignment: Map<ValueExpr.ScopeRef, SemanticExpression.Occurrence?> = emptyMap(),
        /** グラフ（参照の本数・書かれた場所の数・`in` 付きのいまの要素の読み出しに使う。無ければ、それらは「得られない」）。 */
        val graph: SemanticEntityGraph? = null,
        /** その ID へ入ってくる参照（refCount の dir: in）。 */
        val refsInto: (String) -> List<ScannedRef> = { emptyList() },
        /**
         * 解にわたる集計（of＝束縛名。解の一覧を持つクエリの評価器か、行を 1 つの解と見なすビューが担う。無ければ「得られない」）。
         * of を省略した集計（いまの要素の中の配列）は、ここを通らずに評価器が自分で計算する。
         */
        val aggregate: ((ValueExpr.Aggregate) -> AggregateOutcome?)? = null,
        /**
         * 解の件数（count に match）: 起点のノードを固定してパターン照合を評価する（クエリの評価器＝SemanticQuery.matchesFrom を
         * 持つ側が渡す。無ければ「得られない」）。
         */
        val countFrom: ((SemanticQueryNodeRef, QuerySpec) -> SemanticQueryResult?)? = null,
        /** 予約の名前の値（{id} {title} など）。 */
        val reserved: Map<String, String> = emptyMap(),
        /** 穴に入る値の直し方（ファイル名に使える文字に直す、など）。 */
        val holeValue: (String) -> String = { it },
        /** 得られない読み出しと埋まらない穴の代わりの値（定義の点検で代表値を得るためのもの。null＝得られない）。 */
        val fallback: String? = null,
    )

    /**
     * 評価の結果: 値（得られなければ null）と、埋まらなかった穴・読めなかった項目の名前（初出順）と、
     * 得られなかった理由（集計が値を読めなかった場所など。表示に添える文。無ければ null）。
     */
    class Result(val side: SemanticCheckSide?, val missing: List<String>, val reason: String? = null)

    /** 集計の結果: 値（得られなければ null）・得られなかった理由・値が書かれていなかった場所。 */
    class AggregateOutcome(val side: SemanticCheckSide?, val reason: String? = null, val missing: List<String> = emptyList())

    /** 式を評価する（得られなければ null）。 */
    fun evaluate(expr: ValueExpr, env: Environment): SemanticCheckSide? = evaluateDetailed(expr, env).side

    /** 式を評価して、埋まらなかった穴の名前も返す。 */
    fun evaluateDetailed(expr: ValueExpr, env: Environment): Result {
        val missing = LinkedHashSet<String>()
        val eval = Eval(env, missing)
        val side = eval.eval(expr)
        return Result(side, missing.toList(), eval.reason)
    }

    /**
     * 集計の評価（共有）。items は集めた材料（クエリの解にわたる材料か、いまの要素の配列の要素。SemanticExpression.collect）。
     * 値が書かれていない・順序の種類として読めない材料が 1 つでもあれば「得られない」（理由つき。CORE-CALCULUS の集計の規則）。
     * 件数は値を読まない。結果の出どころ: 最小・最大は選ばれた材料の場所、合計・件数は特定の 1 か所ではない。inputs は全ての材料。
     */
    fun aggregate(expr: ValueExpr.Aggregate, items: List<SemanticExpression.Collected>): AggregateOutcome {
        val inputs = items.map { SemanticValueInput(it.placeholder, it.value, it.document, it.path) }
        if (expr.fn == ValueExpr.AggregateFn.COUNT) {
            val n = items.size.toString()
            return AggregateOutcome(SemanticCheckSide(text = n, raw = n, inputs = inputs))
        }
        val values = ArrayList<String>(items.size)
        for (item in items) {
            val v = item.value ?: return AggregateOutcome(null, "${item.placeholder} が書かれていません", listOf(item.placeholder))
            if (!SemanticExpression.readable(expr.kind, v)) {
                return AggregateOutcome(null, "${item.placeholder} が${expr.kind.label}として読めません: $v")
            }
            values += v
        }
        val result = SemanticExpression.aggregateDetailed(expr.fn, values, expr.kind)
            ?: return AggregateOutcome(null, if (values.isEmpty()) "値がありません" else "${expr.fn.label}を計算できません")
        val winner = result.winner?.let { items[it] }
        return AggregateOutcome(
            SemanticCheckSide(
                text = result.text,
                raw = "${expr.fn.label}: ${values.joinToString("・")}",
                sourceDocument = winner?.document, sourcePath = winner?.path,
                inputs = inputs,
            )
        )
    }

    private class Eval(private val env: Environment, private val missing: MutableSet<String>) {

        /** 得られなかった理由（最初のもの）。 */
        var reason: String? = null
            private set

        private fun node(of: String?): SemanticQueryNodeRef? = if (of == null) env.current else env.nodes[of]

        /**
         * 読み出しの相手の出現: 「束縛と文書の指定」に決めた出現があればそれ（null＝候補なし）。via があれば参照を辿った相手。
         * 決めた出現が無ければ、of の束縛（無ければいまの要素）の出現から `in` の指定に合うものを、定義した側を優先して
         * 1つ選ぶ（CALC-19。ビューは割当てを渡さず、この選び方に任せる）。of も in も無ければ、いまの要素の出現。
         */
        private fun occurrence(ref: ValueExpr.ScopeRef): SemanticExpression.Occurrence? {
            if (ref in env.assignment) return env.assignment[ref]
            if (ref.via != null) return viaOccurrence(ref)
            if (ref.of == null && ref.inDoc == null) return env.currentOccurrence
            val node = node(ref.of) ?: return null
            return occurrenceOf(node, ref.inDoc)
        }

        /** ノード（束縛かいまの要素）の出現のうち、文書の指定に合うもの（定義出現優先）。文書ノードはその文書の根。 */
        private fun occurrenceOf(node: SemanticQueryNodeRef, selector: DerivationSelector?): SemanticExpression.Occurrence? = when (node.kind) {
            QueryNodeKind.ENTITY -> SemanticExpression.select(
                env.graph?.entities?.get(node.id)?.occurrences.orEmpty(), selector ?: DerivationSelector(), env.rel,
            )?.let { SemanticExpression.Occurrence(it.document, it.path) }
            QueryNodeKind.DOCUMENT -> node.document?.let { SemanticExpression.Occurrence(it, emptyList()) }
        }

        /**
         * 参照を 1 段辿る（via）: 基点（of の束縛か、いまの要素）の出現に書かれた参照のうち、via の場所のもの（検査の edge と
         * 同じ突き合わせ＝参照の線として辿る。ref 型でない項目の値は辿らない）を辿り、相手の ID の出現から `in` の指定に合うものを
         * 定義した側を優先して 1 つ選ぶ。参照がちょうど 1 本のときだけ相手が決まる（無い・複数は「得られない」で理由を残す）。
         */
        private fun viaOccurrence(ref: ValueExpr.ScopeRef): SemanticExpression.Occurrence? {
            val via = ref.via?.pretty() ?: return null
            val baseNode = node(ref.of) ?: return fail("いまの要素がありません")
            if (baseNode.kind != QueryNodeKind.ENTITY) return fail("文書からは参照を辿れません（via: $via）")
            val base = (if (ref.of == null) env.currentOccurrence else null) ?: occurrenceOf(baseNode, null)
                ?: return fail("${baseNode.id} の出現がありません")
            val graph = env.graph ?: return fail("参照を辿る材料（グラフ）がありません")
            val refs = graph.ownRefs(baseNode.id).filter { r ->
                r.document == base.document && r.path.size > base.path.size && r.path.subList(0, base.path.size) == base.path &&
                    QueryEdgeStep.viaMatches(via, r.viaKey, r.path)
            }
            val target = when (refs.size) {
                0 -> return fail("$via に参照が書かれていません")
                1 -> refs.single().targetId
                else -> return fail("$via の参照が 1 つに決まりません（${refs.size} 本）")
            }
            val occs = graph.entities[target]?.occurrences.orEmpty()
            return SemanticExpression.select(occs, ref.inDoc ?: DerivationSelector(), env.rel)
                ?.let { SemanticExpression.Occurrence(it.document, it.path) }
                ?: fail("参照先「$target」の出現がありません" + (ref.inDoc?.let { "（in の指定に合う文書）" } ?: ""))
        }

        /** 得られない理由を残して null。 */
        private fun fail(text: String): Nothing? {
            if (reason == null) reason = text
            return null
        }

        private fun fallbackSide(): SemanticCheckSide? = env.fallback?.let { SemanticCheckSide(text = it, raw = it) }

        fun eval(expr: ValueExpr): SemanticCheckSide? = when (expr) {
            is ValueExpr.Get -> {
                val occ = occurrence(ValueExpr.ScopeRef(expr.of, expr.inDoc, expr.via))
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
            is ValueExpr.Aggregate -> aggregateOf(expr)
            is ValueExpr.CountMatches -> countMatchesOf(expr)
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
         * 集計: of があれば解にわたる集計（環境の aggregate）。of を省略すれば、いまの要素の出現（`in` があれば同じ ID の
         * 指定に合う出現）から、パスのパターンに合う場所（`[*]`＝配列の全要素）の値を集めて計算する（1 つの要素の中の集計）。
         * 得られなければ理由を残し、代表値（fallback）があればそれで続ける。
         */
        private fun aggregateOf(expr: ValueExpr.Aggregate): SemanticCheckSide? {
            val outcome = if (expr.of != null) env.aggregate?.invoke(expr) else {
                val pattern = expr.path ?: return null
                val occ = occurrence(ValueExpr.ScopeRef(null, expr.inDoc))
                if (occ == null) AggregateOutcome(null, "いまの要素がありません")
                else aggregate(expr, SemanticExpression.collect(env.tree, occ, pattern))
            }
            val side = outcome?.side
            if (side != null) return side
            outcome?.let { o ->
                missing += o.missing
                if (reason == null) reason = o.reason
            }
            return fallbackSide()
        }

        /**
         * 解の件数（count に match）: 起点（of の束縛か、いまの要素）を固定してパターン照合を評価し、解の件数を値にする。
         * 起点が match の先頭の node に合わなければ 0。来歴は見つかった解の束縛（起点を除く）で、各ノードの座標へ行ける。
         * 元の値は数えたノードの並び。評価器が無い（検査の外でグラフを持たない環境）なら理由を残して得られない。
         */
        private fun countMatchesOf(expr: ValueExpr.CountMatches): SemanticCheckSide? {
            val node = node(expr.of) ?: run {
                fail(if (expr.of == null) "いまの要素がありません" else "束縛「${expr.of}」がありません")
                return fallbackSide()
            }
            val countFrom = env.countFrom ?: run {
                fail("解の件数はここでは数えられません（ビューの列で数えます）")
                return fallbackSide()
            }
            val result = countFrom(node, expr.query) ?: run {
                fail("解の件数を数えられません")
                return fallbackSide()
            }
            val origin = expr.query.start.bind
            val counted = result.rows.map { row -> row.bindings.filter { (name, _) -> name != origin } }
            val inputs = counted.flatMap { bindings ->
                bindings.map { (name, ref) -> SemanticValueInput(name, ref.id, ref.document, ref.path) }
            }
            val n = result.total.toString()
            val raw = if (counted.isEmpty() || counted.all { it.isEmpty() }) n
            else "件数: " + counted.joinToString("・") { b -> b.values.joinToString("/") { it.id } }
            return SemanticCheckSide(text = n, id = node.id, raw = raw, inputs = inputs)
        }

        /**
         * テンプレートの展開: 入れ物は「with で名付けた値 → 予約の名前 → 入れ物（scopes）→ いまの要素」の順に近い
         * （いまの要素は、書いた入れ物の後にいつも続く。検査の中にはいまの要素が無いので影響しない）。
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
            val refs = (expr.scopes + ValueExpr.ScopeRef(null, null)).distinct()
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
