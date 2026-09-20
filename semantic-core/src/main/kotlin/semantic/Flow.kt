package semantic

import schema.FlowEdges
import schema.FlowArgs
import schema.QueryNodeStep
import schema.RefTarget

/**
 * 遷移検証のカーネル（R28-04。宣言は schema/Flow.kt）。定義体から名前で使う専用チェッカー（SP-13。SemanticCheckers の flow）。
 * エッジ文書の行（from 参照と to 参照が同じオブジェクト直下に並ぶ行）から有向エッジ集合を抽出し、
 * 到達閉包（BFS）で到達不能・行き止まり・宣言外遷移を判定する。
 *
 * - 母集団は型つきエンティティのみ。母集団が空のときは判定しない（判定保留 §4.26）
 * - start 宣言がない規則は到達不能を判定しない。start の参照はあるがデータが空のときも判定保留
 * - 未解決参照による候補喪失（CALC-17）: エッジ・開始が失われたら到達不能を、終端が失われたら
 *   行き止まりを、宣言エッジが失われたら宣言外遷移を黙らせる（解の不足を違反にしない）。
 *   行き止まりは from 側が解決していれば to 側が未解決でも「出るエッジあり」と数える
 *   （エッジの存在自体は行が証している）
 * - 決定性: 指摘はノードID昇順・関連ノードもID昇順
 */

/** 指摘の関連ノード（ID＋表示タイトル）。 */
data class SemanticFlowNode(val id: String, val title: String?)

enum class SemanticFlowFindingKind { UNREACHABLE, DEAD_END, UNDECLARED }

/**
 * フロー検証の1指摘。related は種別ごとの根拠:
 * UNREACHABLE = 開始ノード／DEAD_END = なし／UNDECLARED = 宣言にない遷移先。
 */
data class SemanticFlowFinding(
    val kind: SemanticFlowFindingKind,
    val nodeId: String,
    val related: List<SemanticFlowNode>,
)

/**
 * 遷移検証の1回分（P27 段A）: 対象のノード・指摘・判定しなかった種類とその理由。
 * 指摘の無いノードを「充足」と言い切れるのは、その種類が skipped に無いときだけ。
 */
data class SemanticFlowRun(
    val nodes: List<String>,
    val findings: List<SemanticFlowFinding>,
    val skipped: Map<SemanticFlowFindingKind, CheckReason>,
)

object SemanticFlow {

    fun findings(graph: SemanticEntityGraph, refs: List<ScannedRef>, rule: FlowArgs): List<SemanticFlowFinding> =
        run(graph, refs, rule).findings

    fun run(graph: SemanticEntityGraph, refs: List<ScannedRef>, rule: FlowArgs): SemanticFlowRun {
        val nodes = graph.entities.keys.filter { matches(graph, rule.nodes, it) }.sorted()
        if (nodes.isEmpty()) return SemanticFlowRun(emptyList(), emptyList(), emptyMap())
        val nodeSet = nodes.toSet()
        val skipped = LinkedHashMap<SemanticFlowFindingKind, CheckReason>()

        val declared = extractEdges(graph, refs, rule.edges, nodeSet)
        val starts = rule.startRelation?.let { extractTargets(graph, refs, it, nodeSet) }
        val terminals = rule.terminalRelation?.let { extractTargets(graph, refs, it, nodeSet) }
            ?: Targets(emptySet(), dropped = false)

        val findings = mutableListOf<SemanticFlowFinding>()

        // 到達不能: start 宣言があり・開始データがあり・エッジと開始に候補喪失がないときだけ判定
        if (starts != null && starts.ids.isNotEmpty() && !starts.dropped && !declared.dropped) {
            val adjacency = declared.pairs.groupBy({ it.first }, { it.second })
            val reachable = HashSet(starts.ids)
            val queue = ArrayDeque(starts.ids)
            while (queue.isNotEmpty()) {
                for (next in adjacency[queue.removeFirst()].orEmpty()) {
                    if (reachable.add(next)) queue.add(next)
                }
            }
            val startNodes = starts.ids.sorted().map { SemanticFlowNode(it, graph.entities[it]?.title) }
            nodes.filterNot { it in reachable }.forEach {
                findings += SemanticFlowFinding(SemanticFlowFindingKind.UNREACHABLE, it, startNodes)
            }
        } else {
            skipped[SemanticFlowFindingKind.UNREACHABLE] =
                if (starts == null || (starts.ids.isEmpty() && !starts.dropped)) CheckReason.NOT_DECLARED
                else CheckReason.UNRESOLVED_REFERENCE
        }

        // 行き止まり: 出るエッジがなく terminal でもないノード（終端の候補喪失時は黙る）
        if (!terminals.dropped) {
            nodes.filterNot { it in declared.outgoing || it in terminals.ids }.forEach {
                findings += SemanticFlowFinding(SemanticFlowFindingKind.DEAD_END, it, emptyList())
            }
        } else {
            skipped[SemanticFlowFindingKind.DEAD_END] = CheckReason.UNRESOLVED_REFERENCE
        }

        // 宣言外遷移: usage エッジ集合 ⊆ 宣言エッジ集合（宣言側の候補喪失時は黙る）
        if (rule.usage != null && !declared.dropped) {
            val usage = extractEdges(graph, refs, rule.usage, nodeSet)
            usage.pairs.filterNot { it in declared.pairs }
                .groupBy({ it.first }, { it.second })
                .toSortedMap()
                .forEach { (from, tos) ->
                    val targets = tos.distinct().sorted().map { SemanticFlowNode(it, graph.entities[it]?.title) }
                    findings += SemanticFlowFinding(SemanticFlowFindingKind.UNDECLARED, from, targets)
                }
        } else {
            skipped[SemanticFlowFindingKind.UNDECLARED] =
                if (rule.usage == null) CheckReason.NOT_DECLARED else CheckReason.UNRESOLVED_REFERENCE
        }

        return SemanticFlowRun(nodes, findings.sortedWith(compareBy({ it.nodeId }, { it.kind.ordinal })), skipped)
    }

    private data class Edges(
        val pairs: Set<Pair<String, String>>,
        /** 出るエッジを持つノード（to 側が未解決・母集団外でも from の出は数える）。 */
        val outgoing: Set<String>,
        /** 未解決参照でエッジ候補が失われた（CALC-17 の unknown-dropped）。 */
        val dropped: Boolean,
    )

    private data class Targets(val ids: Set<String>, val dropped: Boolean)

    /** relation で指されたノード集合（start / terminal 用）。母集団外の実在ノードは選択として無視する。 */
    private fun extractTargets(
        graph: SemanticEntityGraph,
        refs: List<ScannedRef>,
        relation: String,
        nodeSet: Set<String>,
    ): Targets {
        var dropped = false
        val ids = mutableSetOf<String>()
        for (ref in refs) {
            if (ref.relation != relation || ref.targetKind != RefTarget.ENTITY) continue
            when {
                ref.targetId in nodeSet -> ids += ref.targetId
                ref.targetId in graph.entities -> Unit
                else -> dropped = true
            }
        }
        return Targets(ids, dropped)
    }

    /** from / to 参照が同じ行オブジェクト直下に並ぶ組を有向エッジとして抽出する。 */
    private fun extractEdges(
        graph: SemanticEntityGraph,
        refs: List<ScannedRef>,
        edges: FlowEdges,
        nodeSet: Set<String>,
    ): Edges {
        var dropped = false
        val pairs = mutableSetOf<Pair<String, String>>()
        val outgoing = mutableSetOf<String>()
        val rows = refs.asSequence()
            .filter { it.targetKind == RefTarget.ENTITY && (it.relation == edges.fromRelation || it.relation == edges.toRelation) }
            .groupBy { it.document to it.path.dropLast(1) }
        for ((_, rowRefs) in rows) {
            val froms = rowRefs.filter { it.relation == edges.fromRelation }
            val tos = rowRefs.filter { it.relation == edges.toRelation }
            for (f in froms) {
                val fromInUniverse = f.targetId in nodeSet
                if (!fromInUniverse && f.targetId !in graph.entities) dropped = true
                for (t in tos) {
                    val toInUniverse = t.targetId in nodeSet
                    if (!toInUniverse && t.targetId !in graph.entities) dropped = true
                    if (!fromInUniverse) continue
                    outgoing += f.targetId
                    if (toInUniverse) pairs += f.targetId to t.targetId
                }
            }
        }
        return Edges(pairs, outgoing, dropped)
    }

    /** エンティティが選択（entity / schema / id）に一致するか（クエリの node ステップと同じ判定）。 */
    private fun matches(graph: SemanticEntityGraph, step: QueryNodeStep, id: String): Boolean {
        val node = graph.entities[id] ?: return false
        return step.matchesEntity(id, node)
    }
}
