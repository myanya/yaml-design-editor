package dev.toros.yde.semantic

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.semantic.schema.DerivationSelector
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.PathPattern
import dev.toros.yde.semantic.schema.Projection
import dev.toros.yde.semantic.schema.QueryDir
import dev.toros.yde.semantic.schema.QueryEdgeStep
import dev.toros.yde.semantic.schema.QueryNodeKind
import dev.toros.yde.semantic.schema.QuerySpec
import dev.toros.yde.semantic.schema.ValueExpr

/**
 * 1つの環境での値の式の評価（P31・R31-01。CORE-CALCULUS「Expression Calculus」＝σ を固定した純粋な計算）。
 * 値は項（スカラー・配列・マップ）と出どころ（ADR-0010）。関数（集計・四則演算など）は引数を先に評価して値を受け取り、
 * 形を受け取る語（format・first・all・each）は自分の規則で評価する。集合（解・出現）から値へ落とす入口は all の 1 つで、
 * 解の一覧と起点を固定した照合は、クエリの評価器かビューが環境に渡す。クエリの check 判定も、解の束縛と出現の割当てを環境に
 * 写してここを呼ぶ（1つの意味には1つの評価器）。
 * - いまの要素（current）: `of` を省略した読み出し・参照の本数・書かれた場所の数・all の match の起点。無ければ「得られない」
 * - 予約の名前（reserved）: 短い書き方の穴 `{id}` `{title}` を、要素の項目より先に埋める
 * - 得られない値は null（判定保留の流儀）。埋まらなかった穴の名前は Result.missing に、理由は Result.reason に残す
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
        /** グラフ（参照の本数・書かれた場所の数・`in` 付きのいまの要素の読み出し・all の出現の選択に使う。無ければ、それらは「得られない」）。 */
        val graph: SemanticEntityGraph? = null,
        /** その ID へ入ってくる参照（refCount の dir: in）。 */
        val refsInto: (String) -> List<ScannedRef> = { emptyList() },
        /**
         * 同じ起点の全ての解（all の of の形）。解の一覧を持つクエリの評価器か、行を 1 つの解と見なすビューが渡す。
         * 無ければ「得られない」。
         */
        val solutions: (() -> List<SemanticQueryRow>)? = null,
        /** 起点を固定した照合（all の match の形）。クエリの評価器（SemanticQuery.matchesFrom）を持つ側が渡す。無ければ「得られない」。 */
        val solveFrom: ((SemanticQueryNodeRef, QuerySpec) -> SemanticQueryResult?)? = null,
        /** 予約の名前の値（{id} {title} など）。 */
        val reserved: Map<String, String> = emptyMap(),
        /** 穴に入る値の直し方（ファイル名に使える文字に直す、など）。 */
        val holeValue: (String) -> String = { it },
        /** 得られない読み出しと埋まらない穴の代わりの値（定義の点検で代表値を得るためのもの。null＝得られない）。 */
        val fallback: String? = null,
        /**
         * 値を読んだ場所（文書と、その中のパス）の項目の定義（解決済み）。読んだ値の順序の種類（数値・日付・時刻・日時）を
         * 項目の型から決めるために使う（式に `type` を書かないときの既定。スキーマを知る側＝カーネルやアプリが渡す。無ければ決まらない）。
         */
        val fieldAt: (DocumentId, YPath) -> FieldDef? = { _, _ -> null },
    ) {
        /** each の 1 つの要素を「いまの要素」とする環境（出現の割当ては引き継がない。束縛と解の一覧はそのまま）。 */
        fun forElement(element: SemanticQueryNodeRef?, occurrence: SemanticExpression.Occurrence?): Environment = Environment(
            tree = tree, rel = rel, current = element, currentOccurrence = occurrence, nodes = nodes,
            assignment = emptyMap(), graph = graph, refsInto = refsInto, solutions = solutions, solveFrom = solveFrom,
            reserved = buildMap {
                element?.let { e ->
                    put(RESERVED_ID, e.id)
                    e.title?.let { put(RESERVED_TITLE, it) }
                }
            },
            holeValue = holeValue, fallback = fallback, fieldAt = fieldAt,
        )

        /** その場所の値の順序の種類（項目の型から。場所が無い・型に順序が無ければ null）。 */
        fun kindAt(document: DocumentId?, path: YPath?): ValueExpr.OrderKind? =
            if (document == null || path == null) null else fieldAt(document, path)?.let { ValueExpr.OrderKind.ofType(it.type) }
    }

    /**
     * 評価の結果: 値（得られなければ null）と、埋まらなかった穴・読めなかった項目の名前（初出順）と、
     * 得られなかった理由（集計が値を読めなかった場所など。表示に添える文。無ければ null）。
     */
    class Result(val side: SemanticCheckSide?, val missing: List<String>, val reason: String? = null)

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
     * 配列の値を組み立てる。要素は値と出どころ（node の並びと同じ順）。来歴（inputs）にも要素を並べる（表示で材料をたどれるように）。
     * node を渡さなければ、要素の値をスカラーに並べた配列にする。
     */
    fun arraySide(
        elements: List<SemanticValueInput>,
        node: YNode.Seq? = null,
        bind: String? = null,
        id: String? = null,
        sourceDocument: DocumentId? = null,
        sourcePath: YPath? = null,
    ): SemanticCheckSide {
        val seq = node ?: YNode.Seq(elements.map { YNode.Scalar(it.value) }.toMutableList())
        return SemanticCheckSide(
            text = SemanticExpression.flowText(seq), bind = bind, id = id,
            sourceDocument = sourceDocument, sourcePath = sourcePath,
            inputs = elements, node = seq, elements = elements,
        )
    }

    /** 配列の要素の値（スカラーならその文字。書かれていなければ null。配列やマップは流れ書きの文字）。 */
    private fun elementText(node: YNode?): String? = when (node) {
        null -> null
        is YNode.Scalar -> scalarText(node)
        else -> SemanticExpression.flowText(node)
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
            if (ref.via.isNotEmpty()) return viaOccurrence(ref)
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
         * 参照を辿る（via）: 基点（of の束縛か、いまの要素）の出現に書かれた参照のうち、via の場所のもの（検査の edge と
         * 同じ突き合わせ＝参照の線として辿る。ref 型でない項目の値は辿らない）を辿り、相手の ID の出現を `in` の指定に合うものから
         * 定義した側を優先して 1 つ選ぶ。via が 2 つ以上なら段を重ねる: 2 段目からは相手の ID に書かれた参照をどの出現からでも辿り
         * （途中の相手は ID で決まり、出現を 1 つに選ぶのは最後の相手だけ）、`in` は最後の相手に効く。
         * 各段で参照がちょうど 1 本のときだけ相手が決まる（無い・複数は「得られない」で理由を残す）。
         */
        private fun viaOccurrence(ref: ValueExpr.ScopeRef): SemanticExpression.Occurrence? {
            if (ref.via.isEmpty()) return null
            val baseNode = node(ref.of) ?: return fail("いまの要素がありません")
            if (baseNode.kind != QueryNodeKind.ENTITY) return fail("文書からは参照を辿れません（via: ${ref.via.first().pretty()}）")
            val base = (if (ref.of == null) env.currentOccurrence else null) ?: occurrenceOf(baseNode, null)
                ?: return fail("${baseNode.id} の出現がありません")
            val graph = env.graph ?: return fail("参照を辿る材料（グラフ）がありません")
            var id = baseNode.id
            for ((i, hop) in ref.via.withIndex()) {
                val via = hop.pretty()
                val refs = graph.ownRefs(id).filter { r ->
                    QueryEdgeStep.viaMatches(via, r.viaKey, r.path) &&
                        // 最初の段は基点の出現に書かれた参照だけ。2 段目からは相手（ID）のどの出現に書かれた参照でもよい
                        (i > 0 || (r.document == base.document && r.path.size > base.path.size && r.path.subList(0, base.path.size) == base.path))
                }
                id = when (refs.size) {
                    0 -> return fail("$via に参照が書かれていません")
                    1 -> refs.single().targetId
                    else -> return fail("$via の参照が 1 つに決まりません（${refs.size} 本）")
                }
            }
            val occs = graph.entities[id]?.occurrences.orEmpty()
            return SemanticExpression.select(occs, ref.inDoc ?: DerivationSelector(), env.rel)
                ?.let { SemanticExpression.Occurrence(it.document, it.path) }
                ?: fail("参照先「$id」の出現がありません" + (ref.inDoc?.let { "（in の指定に合う文書）" } ?: ""))
        }

        /** 得られない理由を残して null。 */
        private fun fail(text: String): Nothing? {
            if (reason == null) reason = text
            return null
        }

        private fun fallbackSide(): SemanticCheckSide? = env.fallback?.let { SemanticCheckSide(text = it, raw = it) }

        fun eval(expr: ValueExpr): SemanticCheckSide? = when (expr) {
            is ValueExpr.Get -> get(expr)
            is ValueExpr.Literal -> expr.value?.let { SemanticCheckSide(text = it, raw = it) }
            is ValueExpr.Format -> eval(expr.template)?.let { template ->
                if (!template.isScalar) fail("ひな型が文字ではありません: ${template.text}") else format(expr, template)
            }
            is ValueExpr.MapValue -> eval(expr.input)?.let { input ->
                if (!input.isScalar) fail("map はスカラーだけを受け取ります: ${input.text}")
                else input.copy(text = Projection.applyMap(input.text, expr.using))
            }
            is ValueExpr.First -> expr.options.firstNotNullOfOrNull { eval(it) }
            is ValueExpr.Unary -> eval(expr.input)?.let { input ->
                when {
                    !input.isScalar && expr.fn == ValueExpr.UnaryFn.LENGTH -> fail("length は文字数です（配列の件数は count で数えます）: ${input.text}")
                    !input.isScalar -> fail("${expr.fn.id} はスカラーだけを受け取ります: ${input.text}")
                    else -> SemanticExpression.unary(expr.fn, input.text)?.let { text ->
                        // 文字数・数の読み・年月日の数は数値、大文字と小文字・年月（2026-10）はそのまま文字（順序の種類は決まらない＝文字の順）
                        val kind = when (expr.fn) {
                            ValueExpr.UnaryFn.LENGTH, ValueExpr.UnaryFn.NUMBER,
                            ValueExpr.UnaryFn.YEAR, ValueExpr.UnaryFn.MONTH, ValueExpr.UnaryFn.DAY -> ValueExpr.OrderKind.NUMBER
                            ValueExpr.UnaryFn.UPPER, ValueExpr.UnaryFn.LOWER, ValueExpr.UnaryFn.YEAR_MONTH -> null
                        }
                        input.copy(text = text, raw = input.raw ?: input.text, note = expr.fn.label ?: input.note, kind = kind)
                    }
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
                // 名前を書いたときは、その名前の保有の線（out＝保有する要素・in＝所属先）も数える（P40。クエリの線と同じ捉え方）
                val held = if (expr.relation == null) 0 else when (expr.dir) {
                    QueryDir.OUT -> graph.holdingsOf(node.id)
                    QueryDir.IN -> graph.holdersOf(node.id)
                }.count { h ->
                    h.relation == expr.relation &&
                        (expr.via == null || QueryEdgeStep.viaMatches(expr.via, h.viaKey, h.child.path))
                }
                val total = n + held
                SemanticCheckSide(text = total.toString(), id = node.id, raw = total.toString(), kind = ValueExpr.OrderKind.NUMBER)
            }
            // 書かれた場所の数（SP-05）: その ID が書かれている場所のうち、指定（スキーマ・ファイル・役割）に合うものを数える。
            // distinct: type は、合う場所で宣言されているエンティティ型の種類を数える（元の値に、型の並びを添える）
            is ValueExpr.OccurrenceCount -> node(expr.of)?.takeIf { it.kind == QueryNodeKind.ENTITY }?.let { node ->
                val graph = env.graph ?: return@let null
                val occurrences = graph.entities[node.id]?.occurrences.orEmpty()
                    .filter { expr.matches(it.schemaId, env.rel(it.document), it.role) }
                if (expr.distinct == null) {
                    SemanticCheckSide(text = occurrences.size.toString(), id = node.id, raw = occurrences.size.toString(), kind = ValueExpr.OrderKind.NUMBER)
                } else {
                    val types = occurrences.mapNotNull { it.entityType }.distinct()
                    SemanticCheckSide(text = types.size.toString(), id = node.id, raw = types.joinToString(", "), kind = ValueExpr.OrderKind.NUMBER)
                }
            }
            is ValueExpr.Aggregate -> aggregateOf(expr)
            is ValueExpr.All -> allOf(expr)
            is ValueExpr.Each -> eachOf(expr)
            is ValueExpr.Arith -> arithOf(expr)
        }

        /** 四則演算: 引数を先に評価し、スカラーだけを受け取る。結果は特定の 1 か所の値ではない（式を元の値として示す）。 */
        private fun arithOf(expr: ValueExpr.Arith): SemanticCheckSide? {
            val operands = expr.operands.map { eval(it) ?: return null }
            if (operands.any { !it.isScalar }) return fail("四則演算はスカラーだけを受け取ります")
            val text = SemanticExpression.arith(expr.op, operands.map { it.text }) ?: return null
            // 計算結果は特定の1か所に書かれた値ではない——式（3 + 12）を元の値として示し、移動先は最初の値の場所
            val first = operands.firstOrNull { it.sourceDocument != null }
            return SemanticCheckSide(
                text = text,
                id = first?.id,
                raw = operands.joinToString(" ${expr.op.symbol} ") { it.text },
                sourceDocument = first?.sourceDocument, sourcePath = first?.sourcePath,
                kind = ValueExpr.OrderKind.NUMBER,
            )
        }

        /**
         * 読み出し: 出現の要素からの相対パスの項をそのまま値にする。スカラーはその文字、配列は要素ごとに出どころを持つ配列、
         * マップは流れ書きの文字を持つ項。パスに `[*]` があれば、合う場所の値を並べた配列（書かれていない要素が 1 つでもあれば得られない）。
         */
        private fun get(expr: ValueExpr.Get): SemanticCheckSide? {
            val pretty = PathPattern.pretty(expr.path)
            val occ = occurrence(ValueExpr.ScopeRef(expr.of, expr.inDoc, expr.via)) ?: run {
                missing += pretty
                return fallbackSide()
            }
            val id = node(expr.of)?.id
            if (expr.spreads) {
                val element = SemanticExpression.nodeAt(env.tree, occ, emptyList()) ?: run {
                    missing += pretty
                    return fallbackSide()
                }
                val elements = spreadElements(element, expr.path, occ, prefix = null) ?: return fallbackSide()
                return arraySide(elements, bind = expr.of, id = id)
            }
            val node = SemanticExpression.nodeAt(env.tree, occ, expr.path)
            val absolute = occ.path + expr.path
            return when (node) {
                null -> {
                    missing += pretty
                    fallbackSide()
                }
                is YNode.Scalar -> {
                    val text = scalarText(node) ?: run {
                        missing += pretty
                        return fallbackSide()
                    }
                    SemanticCheckSide(
                        text = text,
                        bind = expr.of, id = id,
                        document = occ.document, occPath = occ.path, path = expr.path,
                        raw = text, sourceDocument = occ.document, sourcePath = absolute,
                        // 読んだ項目の型が、この値の順序の種類（比較・集計が `type` を書かないときの既定）
                        kind = env.kindAt(occ.document, absolute),
                    )
                }
                is YNode.Seq -> arraySide(
                    elements = node.items.mapIndexed { i, item ->
                        SemanticValueInput(PathPattern.pretty(expr.path + i), elementText(item), occ.document, absolute + i)
                    },
                    node = node, bind = expr.of, id = id, sourceDocument = occ.document, sourcePath = absolute,
                )
                is YNode.Map -> {
                    val text = SemanticExpression.flowText(node)
                    SemanticCheckSide(
                        text = text, bind = expr.of, id = id,
                        document = occ.document, occPath = occ.path, path = expr.path,
                        raw = text, sourceDocument = occ.document, sourcePath = absolute, node = node,
                    )
                }
            }
        }

        /**
         * `[*]` を含むパスの場所の値を集める（集める側で厳しく判定する: 書かれていない場所が 1 つでもあれば得られない）。
         * prefix は要素の名前の前に添える言葉（all で複数の要素から集めるとき、その ID）。
         */
        private fun spreadElements(
            element: YNode,
            pattern: List<Any>,
            occ: SemanticExpression.Occurrence,
            prefix: String?,
            ref: SemanticQueryNodeRef? = null,
        ): List<SemanticValueInput>? {
            val out = ArrayList<SemanticValueInput>()
            for (rel in PathPattern.expand(element, pattern)) {
                val name = (prefix?.let { "$it " } ?: "") + rel.pretty()
                val value = elementText(getAt(element, rel)) ?: run {
                    missing += name
                    return fail("$name が書かれていません")
                }
                out += SemanticValueInput(name, value, occ.document, occ.path + rel, ref)
            }
            return out
        }

        /**
         * 集計（配列を受け取る関数）: 引数を先に評価し、配列でなければ得られない。件数は値を読まない。合計・最小・最大は
         * 要素が順序の種類として読めなければ得られない。最小・最大の出どころは選ばれた要素、来歴は全ての要素。
         */
        private fun aggregateOf(expr: ValueExpr.Aggregate): SemanticCheckSide? {
            val input = eval(expr.input) ?: return fallbackSide()
            val elements = input.elements ?: run {
                fail("${expr.fn.label}の引数が配列ではありません: ${input.text}")
                return fallbackSide()
            }
            if (expr.fn == ValueExpr.AggregateFn.COUNT) {
                val n = elements.size.toString()
                val raw = if (elements.isEmpty()) n else "件数: " + elements.joinToString("・") { it.ref?.id ?: it.placeholder }
                return SemanticCheckSide(text = n, bind = input.bind, id = input.id, raw = raw, inputs = elements, kind = ValueExpr.OrderKind.NUMBER)
            }
            // 順序の種類: `type` の宣言か、読んだ項目の型（材料のどれかの場所から。決まらなければ数値）
            val kind = expr.kind
                ?: elements.firstNotNullOfOrNull { el -> env.kindAt(el.document, el.path) }
                ?: ValueExpr.OrderKind.NUMBER
            if (expr.fn == ValueExpr.AggregateFn.SUM && kind.value != null) {
                fail("${kind.label}は足せません（${expr.fn.label}の材料が${kind.label}の項目です）")
                return fallbackSide()
            }
            val values = elements.map { el ->
                val v = el.value ?: run {
                    missing += el.placeholder
                    fail("${el.placeholder} が書かれていません")
                    return fallbackSide()
                }
                if (!SemanticExpression.readable(kind, v)) {
                    fail("${el.placeholder} が${kind.label}として読めません: $v")
                    return fallbackSide()
                }
                v
            }
            val result = SemanticExpression.aggregateDetailed(expr.fn, values, kind) ?: run {
                fail(if (values.isEmpty()) "値がありません" else "${expr.fn.label}を計算できません")
                return fallbackSide()
            }
            val winner = result.winner?.let { elements[it] }
            return SemanticCheckSide(
                text = result.text,
                bind = input.bind, id = input.id,
                raw = "${expr.fn.label}: ${values.joinToString("・")}",
                sourceDocument = winner?.document, sourcePath = winner?.path,
                inputs = elements,
                kind = kind,
            )
        }

        /**
         * 集合から配列へ（all）: 解の一覧（of の形＝同じ起点の全ての解）か、起点を固定した照合（match の形）から、束縛の要素
         * （重複は 1 つ）を並べる。path があれば各要素の出現（定義した側を優先して 1 つ。`in` で絞る）から値を読み、`[*]` は平らに広がる。
         * path が無ければ ID の配列（各要素はそのノード＝each のいまの要素になれる）。読めない要素が 1 つでもあれば得られない。
         */
        private fun allOf(expr: ValueExpr.All): SemanticCheckSide? {
            val query = expr.query
            val rows: List<SemanticQueryRow> = if (query != null) {
                val origin = (if (expr.from == null) env.current else env.nodes[expr.from]) ?: run {
                    fail(if (expr.from == null) "いまの要素がありません" else "束縛「${expr.from}」がありません")
                    return fallbackSide()
                }
                val solve = env.solveFrom ?: run {
                    fail("起点を固定した照合はここでは評価できません（検査かビューの列で使えます）")
                    return fallbackSide()
                }
                solve(origin, query)?.rows ?: run {
                    fail("照合を評価できません")
                    return fallbackSide()
                }
            } else {
                env.solutions?.invoke() ?: run {
                    fail("解の一覧がありません（all の of の形は、検査かクエリ行のビューで使えます）")
                    return fallbackSide()
                }
            }
            val nodes = rows.mapNotNull { it.bindings[expr.of] }.distinctBy { it.kind to it.id }
            val path = expr.path
            val elements = if (path == null) {
                nodes.map { SemanticValueInput(expr.of, it.id, it.document, it.path, it) }
            } else {
                nodes.flatMap { n ->
                    val occ = occurrenceOf(n, expr.inDoc) ?: run {
                        fail("${n.id} の出現がありません" + (expr.inDoc?.let { "（in の指定に合う文書）" } ?: ""))
                        return fallbackSide()
                    }
                    val element = SemanticExpression.nodeAt(env.tree, occ, emptyList()) ?: run {
                        fail("${n.id} の要素が読めません")
                        return fallbackSide()
                    }
                    if (PathPattern.hasWildcard(path)) {
                        spreadElements(element, path, occ, prefix = n.id, ref = n) ?: return fallbackSide()
                    } else {
                        val value = elementText(getAt(element, path)) ?: run {
                            val name = "${n.id} ${path.pretty()}"
                            missing += name
                            fail("$name が書かれていません")
                            return fallbackSide()
                        }
                        listOf(SemanticValueInput(n.id, value, occ.document, occ.path + path, n))
                    }
                }
            }
            return arraySide(elements, bind = expr.of)
        }

        /**
         * 要素ごとの式（each）: 配列の各要素を「いまの要素」として value を評価し、配列にする。要素がノード（all で集めた ID）なら
         * その ID の要素、場所を持つ要素ならその場所がいまの要素。1 つでも得られなければ得られない（理由つき）。
         */
        private fun eachOf(expr: ValueExpr.Each): SemanticCheckSide? {
            val input = eval(expr.input) ?: return fallbackSide()
            val elements = input.elements ?: run {
                fail("each の対象が配列ではありません: ${input.text}")
                return fallbackSide()
            }
            val out = elements.map { el ->
                val ref = el.ref
                val occ = when {
                    // ノードの要素: いまの要素はその ID の出現（定義した側を優先）。場所を持つ要素: その場所
                    ref != null && el.document != null && el.path != null && el.value == ref.id ->
                        occurrenceOf(ref, null) ?: SemanticExpression.Occurrence(el.document, el.path)
                    el.document != null && el.path != null -> SemanticExpression.Occurrence(el.document, el.path)
                    else -> null
                }
                val child = Eval(env.forElement(ref, occ), missing)
                val side = child.eval(expr.value) ?: run {
                    fail("要素「${el.placeholder}」の値が得られません" + (child.reason?.let { ": $it" } ?: ""))
                    return fallbackSide()
                }
                SemanticValueInput(el.placeholder, side.text, side.sourceDocument ?: el.document, side.sourcePath ?: el.path, ref)
            }
            return arraySide(out, bind = input.bind, id = input.id)
        }

        /**
         * テンプレートの展開: 入れ物は「with で名付けた値 → 予約の名前 → 入れ物（scopes）→ いまの要素」の順に近い
         * （いまの要素は、書いた入れ物の後にいつも続く。検査の中にはいまの要素が無いので影響しない）。
         * 穴が埋まらなければ「得られない」（fallback があれば、それで埋めて続ける）。with の値はスカラーだけが穴に入る。
         */
        private fun format(expr: ValueExpr.Format, template: SemanticCheckSide): SemanticCheckSide? {
            val withSides = LinkedHashMap<String, SemanticCheckSide>()
            for ((name, e) in expr.with) eval(e)?.takeIf { it.isScalar }?.let { withSides[name] = it }
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
