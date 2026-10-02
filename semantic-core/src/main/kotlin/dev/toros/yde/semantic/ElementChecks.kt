package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.CheckTerm
import dev.toros.yde.semantic.schema.DisplayCore
import dev.toros.yde.semantic.schema.ElementRule
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.QueryNodeKind
import dev.toros.yde.semantic.schema.QueryWhere
import dev.toros.yde.semantic.schema.SchemaTree
import dev.toros.yde.semantic.schema.ValueExpr
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.yaml.model.scalarText

/**
 * 型の検査（要素ごとの検査。オブジェクト型。P33 第三弾）の評価。文書をスキーマに沿って歩き、解決した型が検査を持つ object の要素ごとに、
 * その要素を「いまの要素」として `where`（要素を絞る）と `check`（値の条件）を評価する。評価器は値の式の共有評価部
 * （SemanticValues＝ビューの列・検査の check と同じ）で、順序の比較の種類は項目の型から決まる。
 * 結果は検査の 4 つの結果と同じ（違反・充足・保留・対象外）。グラフ（他の要素・参照）は使わない——ID の無い要素でも評価できる。
 * 文面と配り方は呼び出し側（workspace-core の CodedDocumentChecks）が持つ。
 */
object SemanticElementChecks {

    /** 要素 1 つ × 検査 1 つの結果。 */
    data class Result(
        val rule: ElementRule,
        /** 要素の型の呼び名（型の名前か、object の表示名）。文面に使う。 */
        val typeLabel: String,
        val document: DocumentId,
        /** 要素の場所。 */
        val path: YPath,
        /** 要素自身の ID（type: id の項目があれば）。 */
        val elementId: String?,
        val outcome: CheckOutcome,
        val reason: CheckReason? = null,
        /** 違反のとき、満たさなかった条件と比べた値（expect: absent の違反では null）。 */
        val term: CheckTerm? = null,
        val left: SemanticCheckSide? = null,
        val right: SemanticCheckSide? = null,
    )

    /** 文書 1 つの、型の検査を持つ全ての要素の結果（対象外は含めない）。 */
    fun run(tree: SchemaTree, root: YNode, document: DocumentId, rel: (DocumentId) -> String = { it.value }): List<Result> {
        val out = mutableListOf<Result>()
        fun walk(def: FieldDef, node: YNode, path: YPath) {
            val eff = tree.resolve(def) ?: def
            when (eff.type) {
                FieldType.OBJECT -> {
                    val m = node as? YNode.Map ?: return
                    val fields = tree.effectiveFields(eff.fields, m).fields
                    if (eff.checks.isNotEmpty()) out += evaluate(tree, root, document, rel, eff, fields, m, path)
                    fields.forEach { f ->
                        val k = f.key ?: return@forEach
                        m.entries[k]?.let { walk(f, it, path + k) }
                    }
                }
                FieldType.ARRAY -> {
                    val seq = node as? YNode.Seq ?: return
                    val item = eff.item ?: return
                    seq.items.forEachIndexed { i, child -> walk(item, child, path + i) }
                }
                FieldType.MAP -> {
                    val m = node as? YNode.Map ?: return
                    val value = eff.value ?: return
                    m.entries.forEach { (k, child) -> walk(value, child, path + k) }
                }
                else -> Unit
            }
        }
        walk(tree.root, root, emptyList())
        return out
    }

    private fun evaluate(
        tree: SchemaTree,
        root: YNode,
        document: DocumentId,
        rel: (DocumentId) -> String,
        eff: FieldDef,
        fields: List<FieldDef>,
        element: YNode.Map,
        path: YPath,
    ): List<Result> {
        val idKey = fields.firstOrNull { (tree.resolve(it) ?: it).type == FieldType.ID }?.key
        val elementId = idKey?.let { scalarText(element.entries[it]) }
        val title = DisplayCore.titleOf(element, tree, eff)
        val typeLabel = eff.lineage.firstOrNull() ?: eff.label ?: eff.key ?: "object"
        val occurrence = SemanticExpression.Occurrence(document, path)
        val env = SemanticValues.Environment(
            tree = { d -> root.takeIf { d == document } },
            rel = rel,
            current = SemanticQueryNodeRef(QueryNodeKind.ENTITY, elementId ?: path.pretty(), title, eff.entity?.type, document, path),
            currentOccurrence = occurrence,
            reserved = buildMap {
                elementId?.let { put(SemanticValues.RESERVED_ID, it) }
                title?.let { put(SemanticValues.RESERVED_TITLE, it) }
            },
            fieldAt = { d, p -> if (d == document) tree.fieldAt(root, p) else null },
        )
        fun kindAt(relative: YPath): ValueExpr.OrderKind? = tree.fieldAt(root, path + relative)?.let { ValueExpr.OrderKind.ofType(it.type) }

        /** 要素を絞る条件（3 値）: 値が無く判定できなければ null。 */
        fun predValue(pred: QueryWhere): Boolean? {
            val node = getAt(element, pred.path)
            val text = scalarText(node)
            val kind = pred.kind ?: kindAt(pred.path) ?: ValueExpr.OrderKind.NUMBER
            val test = pred.test
            val plain: Boolean? = when {
                pred.equals != null -> text?.let { it == pred.equals }
                pred.notEquals != null -> text?.let { it != pred.notEquals }
                pred.exists != null -> (node != null) == pred.exists
                pred.empty != null -> SemanticExpression.isEmpty(node) == pred.empty
                test != null -> text?.let { SemanticExpression.test(test.first, it, test.second, kind) }
                else -> null
            }
            return plain?.let { if (pred.negated) !it else it }
        }

        val results = mutableListOf<Result>()
        for (rule in eff.checks) {
            fun result(outcome: CheckOutcome, reason: CheckReason? = null, term: CheckTerm? = null, left: SemanticCheckSide? = null, right: SemanticCheckSide? = null) =
                Result(rule, typeLabel, document, path, elementId, outcome, reason, term, left, right)
            // where: すべての条件（1 件の中の「または」はどれか）が成り立つ要素だけが対象。判定できなければ対象外
            val selected = rule.where.map { clause -> clause.alternatives.any { predValue(it) == true } }
            if (selected.any { !it }) continue
            if (rule.absent) {
                results += result(CheckOutcome.VIOLATED)
                continue
            }
            var violated: Result? = null
            var withheld: Result? = null
            for (check in rule.check) {
                var unknown = false
                var noValue = false
                var satisfied = false
                var firstFailed: Result? = null
                for (term in check.terms) {
                    val left = SemanticValues.evaluate(term.left, env)
                    val rights = term.rights.map { SemanticValues.evaluate(it, env) }
                    val comparable = left != null && left.isScalar && rights.all { it == null || it.isScalar }
                    val kind = term.kind ?: left?.kind ?: rights.firstNotNullOfOrNull { it?.kind } ?: ValueExpr.OrderKind.NUMBER
                    val plain = left?.takeIf { comparable }?.let { SemanticExpression.test(term.op, it.text, rights.map { r -> r?.text }, kind) }
                    if (left == null || plain == null) {
                        unknown = true
                        if (left == null || rights.any { it == null }) noValue = true
                        continue
                    }
                    val right = rights.singleOrNull() ?: SemanticCheckSide(text = rights.filterNotNull().joinToString("・") { it.text })
                    if (plain != term.negated) {
                        satisfied = true
                        break
                    }
                    if (firstFailed == null) firstFailed = result(CheckOutcome.VIOLATED, term = term, left = left, right = right)
                }
                when {
                    satisfied -> Unit
                    unknown -> if (withheld == null) withheld = result(CheckOutcome.WITHHELD, if (noValue) CheckReason.NO_VALUE else CheckReason.INCOMPARABLE_VALUE)
                    else -> if (violated == null) violated = firstFailed
                }
            }
            results += violated ?: withheld ?: result(CheckOutcome.SATISFIED)
        }
        return results
    }
}
