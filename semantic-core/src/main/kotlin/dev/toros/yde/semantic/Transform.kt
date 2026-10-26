package dev.toros.yde.semantic

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.semantic.schema.DerivationSelector
import dev.toros.yde.semantic.schema.Projection
import dev.toros.yde.semantic.schema.QueryNodeKind
import dev.toros.yde.semantic.schema.Template
import dev.toros.yde.semantic.schema.TemplateCondition
import dev.toros.yde.semantic.schema.TemplateSite
import dev.toros.yde.semantic.schema.TemplateTest
import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.semantic.schema.ValueExpr
import dev.toros.yde.yaml.YamlIO
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.IdentityHashMap
import java.util.Locale

/** 出力の場所（output）の値が、入力のどの文書のどの場所から来たか（TF-06。document が null＝呼び出し側が文書を告げなかった入力）。 */
data class TransformOrigin(val output: YPath, val document: DocumentId?, val path: YPath)

/** 変換の結果: 組み立てた項（何も得られなければ null）と、値の出どころ。 */
class TransformResult(val term: YNode?, val origins: List<TransformOrigin>)

/**
 * 「YAML から YAML への変換」の評価器（P27 段C。計算体系の6つ目の体系「組み立て」＝CORE-CALCULUS §26）。宣言は schema.TransformDef。
 * - 純粋で決定的: 入力を書き換えず、同じ入力と定義からは同じ出力になる。出力は新しい項で、入力の部分木は写し（コメントと表記は持ち込まない）
 * - 値が得られない指示は「無い」（null）になり、マップのキーごと出力から落ちる・配列の要素なら飛ばされる
 * - 規則を当てること（`$apply`）は、必ず終わる: 同じ規則を同じ場所に当てている最中に、もう一度同じ規則を同じ場所に当てると定義エラー
 *   （入力の場所は有限なので、入れ子の深さは「規則の数 × 場所の数」を超えない。CORE-CALCULUS §19 の「止まらない計算を入れない」を守る形）
 * - クエリで選んだ束縛の値は、文書の指定（`$in`）に合う出現のうち、定義した側を優先して1つから読む（値の式と同じ決定的な選び方。CALC-19）
 * - 値の条件・文字のひな型・辞書での置き換えは、既存の共有部品（SemanticExpression.test / isEmpty・Projection）を使う
 */
object SemanticTransform {

    /** 呼び出し側が渡した項1つを変換する（定義に from を書かない変換。例: 構造のスキーマの導出）。 */
    fun run(
        def: TransformDef,
        input: YNode,
        document: DocumentId? = null,
        params: Map<String, Any?> = emptyMap(),
    ): TransformResult {
        require(def.from == null) { "変換「${def.id}」はクエリで入力を選ぶ定義です（from.match）。索引を渡して実行してください" }
        val run = Run(def, params, bindings = emptyMap())
        val term = run.build(def.build, TemplatePlace(input, document, emptyList()), emptyList(), emptyMap())
        return TransformResult(term, run.origins)
    }

    /**
     * クエリ（from.match）で選んだ行ごとに組み立てる。出力は、行ごとの結果を並べた配列（何も得られなかった行は飛ばす）。
     * 「いまの場所」は、クエリの最初の束縛（起点）の要素。
     */
    fun run(
        def: TransformDef,
        ids: Map<String, List<ScannedId>>,
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        rel: (DocumentId) -> String = { it.value },
        params: Map<String, Any?> = emptyMap(),
        loader: (DocumentId) -> YNode?,
    ): TransformResult {
        val spec = def.from ?: error("変換「${def.id}」は、呼び出し側が入力の項を渡す定義です（from がありません）")
        val trees = HashMap<DocumentId, YNode?>()
        fun tree(d: DocumentId): YNode? = trees.getOrPut(d) { loader(d) }
        val rows = SemanticQuery.run(ids, refs, graph, spec, loader = ::tree).rows
        val origins = mutableListOf<TransformOrigin>()
        val items = mutableListOf<YNode>()
        for (row in rows) {
            val bindings = row.bindings.mapValues { (_, ref) ->
                { selector: DerivationSelector? -> placeOf(ref, selector, graph, rel, ::tree) }
            }
            val run = Run(def, params, bindings)
            val here = spec.start.bind?.let { bindings[it]?.invoke(null) }
            val built = run.build(def.build, here, listOf(items.size), emptyMap()) ?: continue
            items += built
            origins += run.origins
        }
        return TransformResult(YNode.Seq(items), origins)
    }

    /** 束縛の要素の場所: エンティティは、文書の指定に合う出現のうち定義した側を優先して1つ・文書はそのルート。 */
    private fun placeOf(
        ref: SemanticQueryNodeRef,
        selector: DerivationSelector?,
        graph: SemanticEntityGraph,
        rel: (DocumentId) -> String,
        tree: (DocumentId) -> YNode?,
    ): TemplatePlace? = when (ref.kind) {
        QueryNodeKind.ENTITY -> SemanticExpression.preferDefining(graph.entities[ref.id]?.occurrences.orEmpty())
            .firstOrNull { selector == null || selector.matches(it.schemaId, rel(it.document)) }
            ?.let { occ -> tree(occ.document)?.let { getAt(it, occ.path) }?.let { TemplatePlace(it, occ.document, occ.path) } }
        QueryNodeKind.DOCUMENT -> graph.documents[ref.id]?.let { doc -> tree(doc.document)?.let { TemplatePlace(it, doc.document, emptyList()) } }
    }

    /**
     * ひな型 1 つを、受け取った値（params）・いまの場所（here）・束縛（名前 → 文書の指定に合う場所）で組み立てる
     * （操作の計画＝workspace-core の ActionPlanner が使う。変換定義の rules は無いので `$apply` は「無い」になる）。
     */
    fun build(
        template: Template,
        params: Map<String, Any?>,
        here: TemplatePlace?,
        bindings: Map<String, (DerivationSelector?) -> TemplatePlace?> = emptyMap(),
    ): TransformResult {
        val run = Run(TransformDef("template", null, params.keys.toList(), null, template, emptyList()), params, bindings)
        return TransformResult(run.build(template, here, emptyList(), emptyMap()), run.origins)
    }

    /** 条件（並べて「かつ」）が、受け取った値・いまの場所・束縛の上で成り立つか（値が得られず決められない条件は成り立たない）。 */
    fun holds(
        conditions: List<TemplateCondition>,
        params: Map<String, Any?>,
        here: TemplatePlace?,
        bindings: Map<String, (DerivationSelector?) -> TemplatePlace?> = emptyMap(),
    ): Boolean {
        if (conditions.isEmpty()) return true
        val run = Run(TransformDef("template", null, params.keys.toList(), null, Template.Literal(null), emptyList()), params, bindings)
        return conditions.all { run.holds(it, here, emptyMap()) }
    }

    /** `$number` の `$as` の形の記号（小数点は `.`・桁区切りは `,`。表示の整形 ValueFormat と同じ）。 */
    private val NUMBER_SYMBOLS: DecimalFormatSymbols = DecimalFormatSymbols(Locale.US)

    /** 入力の中の場所1つ（項と、その座標）。 */
    class TemplatePlace(val node: YNode, val document: DocumentId?, val path: YPath) {
        fun at(relative: YPath): TemplatePlace? =
            if (relative.isEmpty()) this else getAt(node, relative)?.let { TemplatePlace(it, document, path + relative) }
    }

    /** 1回の組み立て（1つの入力、またはクエリの1行）。 */
    private class Run(
        val def: TransformDef,
        val params: Map<String, Any?>,
        val bindings: Map<String, (DerivationSelector?) -> TemplatePlace?>,
    ) {
        val origins = mutableListOf<TransformOrigin>()

        /** 当てている最中の「規則 × 場所」（同じ組へもう一度入ったら、止まらない定義）。 */
        private val active = IdentityHashMap<YNode, MutableSet<String>>()

        init {
            params.keys.firstOrNull { it !in def.params }
                ?.let { error("変換「${def.id}」は「$it」を受け取りません（受け取る値: ${def.params.joinToString(", ").ifEmpty { "なし" }}）") }
        }

        private fun place(site: TemplateSite, here: TemplatePlace?): TemplatePlace? {
            val base = if (site.of == null) here else bindings[site.of]?.invoke(site.inDoc)
            return base?.at(site.path)
        }

        /** ひな型を、いまの場所（here）で組み立てる。out は出力の中の場所（出どころの記録用）。loop は繰り返しの中の `$key` / `$index`。 */
        fun build(t: Template, here: TemplatePlace?, out: YPath, loop: Map<String, Any?>): YNode? = when (t) {
            is Template.Literal -> YNode.Scalar(t.value)
            is Template.Param -> (if (t.name in loop) loop[t.name] else params[t.name])?.let { YamlIO.fromAny(it) }
            is Template.MapOf -> {
                val entries = LinkedHashMap<String, YNode>()
                for ((key, child) in t.entries) build(child, here, out + key, loop)?.let { entries[key] = it }
                YNode.Map(entries)
            }
            is Template.ListOf -> {
                val items = mutableListOf<YNode>()
                for (child in t.items) build(child, here, out + items.size, loop)?.let { items += it }
                YNode.Seq(items)
            }
            is Template.Copy -> place(t.site, here)?.let { found ->
                origins += TransformOrigin(out, found.document, found.path)
                copyOf(found.node)
            }
            is Template.Format -> place(t.site, here)?.let { found ->
                // $with: 計算した値を穴へ（いまの場所で計算し、場所の値より先に埋まる）
                val computed = LinkedHashMap<String, YNode>()
                for ((name, child) in t.with) build(child, here, out, loop)?.let { computed[name] = it }
                val scopes = if (computed.isEmpty()) listOf(found.node) else listOf(YNode.Map(computed), found.node)
                val evaluated = Projection.evaluate(t.template, scopes)
                if (!evaluated.complete) null
                else {
                    origins += TransformOrigin(out, found.document, found.path)
                    YNode.Scalar(evaluated.text)
                }
            }
            is Template.DateOf -> build(t.input, here, out, loop)?.let { input ->
                scalarText(input)?.let(SemanticExpression::readDate)?.let { YNode.Scalar(SemanticExpression.formatDate(it, t.pattern)) }
            }
            is Template.NumberOf -> build(t.input, here, out, loop)?.let { input ->
                scalarText(input)?.let(SemanticExpression::numberOf)?.let { n ->
                    // 形（$as）があればその形の文字（連番の 0 詰めなど）、無ければ数の項
                    if (t.pattern != null) YNode.Scalar(DecimalFormat(t.pattern, NUMBER_SYMBOLS).format(n)) else numberTerm(n)
                }
            }
            is Template.TrimOf -> build(t.input, here, out, loop)?.let { input ->
                scalarText(input)?.let { SemanticExpression.squashSpaces(it) }?.takeIf { it.isNotEmpty() }?.let { YNode.Scalar(it) }
            }
            is Template.ValueOf -> valueOf(t.expr, here, out)
            is Template.MapValue -> build(t.input, here, out, loop)?.let { input ->
                val text = scalarText(input)
                if (text != null && text in t.using) YNode.Scalar(t.using.getValue(text)) else input
            }
            is Template.First -> t.options.firstNotNullOfOrNull { build(it, here, out, loop) }
            is Template.Each -> place(t.site, here)?.let { found -> each(t, found, out, loop) }
            is Template.Apply -> place(t.site, here)?.let { found -> apply(t.rule, found, out, loop) }
            is Template.Concat -> {
                val items = mutableListOf<YNode>()
                for (part in t.parts) {
                    val base = items.size
                    val recordedBefore = origins.size
                    // 部品は、仮の場所（out の下の [base]）で組み立てる
                    when (val built = build(part, here, out + base, loop)) {
                        null -> Unit
                        is YNode.Seq -> {
                            relocateOrigins(recordedBefore, provisional = out + base, out = out, base = base, size = built.items.size)
                            items += built.items
                        }
                        else -> error("変換「${def.id}」: \$concat の中身は配列になるひな型で書きます（${out.pretty()}）")
                    }
                }
                YNode.Seq(items)
            }
            is Template.Guarded -> if (t.conditions.all { holds(it, here, loop) }) build(t.inner, here, out, loop) else null
        }

        /**
         * $concat の部品は、仮の場所（provisional＝out の下の [base]）で組み立てている（部品の配列の i 番目は、仮の場所の下の [i]）。
         * その部品の組み立てで記録した出どころ（from 番目以降）を、つなげた後の本当の場所（out の下の [base + i]）へ直す。
         * 配列をまるごと写した部品（出どころが仮の場所そのもの）は、要素ごとの出どころへ分ける。
         */
        private fun relocateOrigins(from: Int, provisional: YPath, out: YPath, base: Int, size: Int) {
            val recorded = origins.subList(from, origins.size).toList()
            while (origins.size > from) origins.removeAt(origins.size - 1)
            for (o in recorded) {
                when {
                    o.output == provisional ->
                        for (i in 0 until size) origins += TransformOrigin(out + (base + i), o.document, o.path + i)
                    o.output.size > provisional.size && o.output.subList(0, provisional.size) == provisional -> {
                        val index = o.output[provisional.size] as? Int
                        origins += if (index == null) o
                        else o.copy(output = out + (base + index) + o.output.subList(provisional.size + 1, o.output.size))
                    }
                    else -> origins += o
                }
            }
        }

        private fun each(t: Template.Each, found: TemplatePlace, out: YPath, loop: Map<String, Any?>): YNode? = when (val node = found.node) {
            is YNode.Seq -> if (t.entries) null else {
                val items = mutableListOf<YNode>()
                node.items.forEachIndexed { i, child ->
                    // `$index` は 0 始まり・`$position` は 1 始まり（明細番号のような「何番目か」に）
                    build(t.body, TemplatePlace(child, found.document, found.path + i), out + items.size, loop + ("index" to i) + ("position" to i + 1))
                        ?.let { items += it }
                }
                YNode.Seq(items)
            }
            is YNode.Map -> if (!t.entries) null else {
                val entries = LinkedHashMap<String, YNode>()
                for ((key, child) in node.entries) {
                    build(t.body, TemplatePlace(child, found.document, found.path + key), out + key, loop + ("key" to key))?.let { entries[key] = it }
                }
                YNode.Map(entries)
            }
            // 配列でもマップでもない値の上では、繰り返せない（無いものとして扱う）
            is YNode.Scalar -> null
        }

        /** 名前の規則を、その場所に当てる。同じ名前の規則は上から順に条件を調べ、最初に合ったものを使う（どれも合わなければ「無い」）。 */
        private fun apply(ruleId: String, found: TemplatePlace, out: YPath, loop: Map<String, Any?>): YNode? {
            val entered = active.getOrPut(found.node) { mutableSetOf() }
            if (!entered.add(ruleId)) {
                error("変換「${def.id}」: 規則「$ruleId」を、同じ場所（${found.path.pretty()}）へ当てている最中に、もう一度当てています。規則は、入力の木の子へ向かって当ててください（\$at・\$each・\$eachEntry）")
            }
            try {
                val rule = def.rules.firstOrNull { r -> r.id == ruleId && r.conditions.all { holds(it, found, loop) } } ?: return null
                return build(rule.build, found, out, loop)
            } finally {
                entered.remove(ruleId)
            }
        }

        /** 条件（「または」）が成り立つか。値が得られず決められない条件は、成り立たない（否定でも成り立たない）。 */
        fun holds(condition: TemplateCondition, here: TemplatePlace?, loop: Map<String, Any?>): Boolean =
            condition.alternatives.any { test ->
                val result = evaluate(test, here, loop)
                if (test.negated) result == false else result == true
            }

        private fun evaluate(test: TemplateTest, here: TemplatePlace?, loop: Map<String, Any?>): Boolean? {
            val node = place(test.site, here)?.node
            fun operands(): List<String?> = test.operands.flatMap { operand ->
                when (operand) {
                    is Template.Literal -> listOf(operand.value?.toString())
                    is Template.Param -> when (val v = if (operand.name in loop) loop[operand.name] else params[operand.name]) {
                        is Collection<*> -> v.map { it?.toString() }
                        else -> listOf(v?.toString())
                    }
                    else -> listOf(null)
                }
            }
            return when (test.kind) {
                TemplateTest.Kind.EXISTS -> (node != null) == test.flag
                TemplateTest.Kind.EMPTY -> SemanticExpression.isEmpty(node) == test.flag
                TemplateTest.Kind.EQUALS -> scalarText(node)?.let { text -> operands().firstOrNull()?.let { text == it } }
                TemplateTest.Kind.NOT_EQUALS -> scalarText(node)?.let { text -> operands().firstOrNull()?.let { text != it } }
                TemplateTest.Kind.TEST -> scalarText(node)?.let { text -> SemanticExpression.test(test.op!!, text, operands()) }
            }
        }

        /** 数の項: 整数なら整数の値（YAML を読んだときと同じ Int / Long）、そうでなければ小数の値。 */
        private fun numberTerm(n: BigDecimal): YNode.Scalar {
            val whole = n.stripTrailingZeros()
            return YNode.Scalar(
                when {
                    whole.scale() > 0 -> n.toDouble()
                    whole.toBigInteger().bitLength() < 32 -> whole.toInt()
                    else -> whole.toBigInteger().toLong()
                }
            )
        }

        /**
         * 値の式をいまの場所で計算する（`$value`。R30-01）。いまの場所と、式が指す束縛の要素だけを読める合成の環境で評価する
         * （文書は仮の名前で、いまの場所をその根にする。グラフ・解の一覧・項目の型は無いので、all・refCount・occurrenceCount は
         * 「無い」になり、順序の種類は式の `type` で宣言する）。出どころはいまの場所。
         */
        private fun valueOf(expr: ValueExpr, here: TemplatePlace?, out: YPath): YNode? {
            val base = here ?: return null
            val docs = HashMap<DocumentId, YNode>()
            val hereDoc = DocumentId("\$here")
            docs[hereDoc] = base.node
            val nodes = HashMap<String, SemanticQueryNodeRef>()
            val assignment = HashMap<ValueExpr.ScopeRef, SemanticExpression.Occurrence?>()
            for (name in expr.binds()) {
                val place = bindings[name]?.invoke(null) ?: continue
                val doc = DocumentId("\$bind:$name")
                docs[doc] = place.node
                nodes[name] = SemanticQueryNodeRef(QueryNodeKind.ENTITY, name, null, null, doc, emptyList())
                assignment[ValueExpr.ScopeRef(name, null)] = SemanticExpression.Occurrence(doc, emptyList())
            }
            val env = SemanticValues.Environment(
                tree = { docs[it] },
                current = SemanticQueryNodeRef(QueryNodeKind.ENTITY, "\$here", null, null, hereDoc, emptyList()),
                currentOccurrence = SemanticExpression.Occurrence(hereDoc, emptyList()),
                nodes = nodes,
                assignment = assignment,
            )
            val side = SemanticValues.evaluate(expr, env) ?: return null
            origins += TransformOrigin(out, base.document, base.path)
            return termOf(side)
        }

        /** 値の式の結果を項に: 配列・マップはその項の写し、数（順序の種類が数値）は数の項、それ以外は文字。 */
        private fun termOf(side: SemanticCheckSide): YNode = when {
            side.node != null -> copyOf(side.node)
            side.kind == ValueExpr.OrderKind.NUMBER -> SemanticExpression.numberOf(side.text)?.let(::numberTerm) ?: YNode.Scalar(side.text)
            else -> YNode.Scalar(side.text)
        }

        /** 部分木の写し（値と形だけ。コメントと表記は持ち込まない＝出力は派生した成果物）。 */
        private fun copyOf(node: YNode): YNode = when (node) {
            is YNode.Scalar -> YNode.Scalar(node.value)
            is YNode.Seq -> YNode.Seq(node.items.mapTo(mutableListOf()) { copyOf(it) })
            is YNode.Map -> YNode.Map(LinkedHashMap<String, YNode>().also { m -> node.entries.forEach { (k, v) -> m[k] = copyOf(v) } })
        }
    }
}
