package semantic

import schema.RefTarget

/**
 * 循環の検出（R21-01 の非循環。P29 Phase 6 第4段で SemanticGraphIntegrity から移動）。
 * 定義体から名前で使う専用チェッカー（SP-13。SemanticCheckers の cycle）: 指定の関係のエッジ集合で、循環に含まれる
 * 参照の1つひとつを、循環の経路つきで返す（指摘の場所は、その参照が書かれた場所）。
 * - エッジの両端はいずれもグラフ上のエンティティ（未解決参照は対象外＝判定保留 §4.26）
 * - 決定性: 隣接はID昇順・代表の経路は強連結成分の最小IDを起点にした最短の閉路
 */
object SemanticCycles {

    fun findings(graph: SemanticEntityGraph, relation: String): List<SemanticCheckerFinding> {
        val cycle = detectCycles(graph, relation)
        return cycle.edges.map { edge ->
            SemanticCheckerFinding(
                kind = "cycle",
                subjectId = edge.from,
                ref = edge.ref,
                related = listOf(SemanticFlowNode(edge.to, graph.entities[edge.to]?.title)),
                values = mapOf("path" to cycle.pathTextOf(edge), "relation" to relation),
            )
        }
    }

    /** 循環検出の結果。edges は循環に参加しているエッジ（強連結成分内・自己ループ）。 */
    private class CycleResult(
        val edges: List<Edge>,
        private val cyclePathByScc: Map<Int, String>,
        private val sccOf: Map<String, Int>,
    ) {
        fun pathTextOf(edge: Edge): String =
            if (edge.from == edge.to) "${edge.from} → ${edge.to}"
            else cyclePathByScc[sccOf.getValue(edge.from)] ?: "${edge.from} → ${edge.to}"
    }

    private data class Edge(val from: String, val to: String, val ref: ScannedRef)

    /**
     * 指定 relation のエッジ集合で循環（強連結成分・自己ループ）を検出する。
     * エッジの両端はいずれもグラフ上のエンティティ（未解決参照は対象外）。決定性のため隣接はID昇順。
     */
    private fun detectCycles(graph: SemanticEntityGraph, relation: String): CycleResult {
        val edges = mutableListOf<Edge>()
        val adjacency = sortedMapOf<String, MutableList<String>>()
        for (id in graph.entities.keys.sorted()) {
            for (ref in graph.outgoingRefs(id)) {
                if (ref.relation != relation) continue
                if (ref.targetKind != RefTarget.ENTITY) continue
                if (ref.targetId !in graph.entities) continue
                edges += Edge(id, ref.targetId, ref)
                adjacency.getOrPut(id) { mutableListOf() }.add(ref.targetId)
            }
        }
        if (edges.isEmpty()) return CycleResult(emptyList(), emptyMap(), emptyMap())
        adjacency.values.forEach { it.sort() }

        // Tarjan の強連結成分（反復版。スタック深度をデータに依存させない）
        val sccOf = mutableMapOf<String, Int>()
        val indexOf = mutableMapOf<String, Int>()
        val lowlink = mutableMapOf<String, Int>()
        val onStack = mutableSetOf<String>()
        val stack = ArrayDeque<String>()
        var counter = 0
        var sccCount = 0
        val sccMembers = mutableMapOf<Int, MutableList<String>>()

        for (start in adjacency.keys.toList()) {
            if (start in indexOf) continue
            // (node, 次に見る隣接の位置) の反復DFS
            val work = ArrayDeque<Pair<String, Int>>()
            work.addLast(start to 0)
            indexOf[start] = counter; lowlink[start] = counter; counter++
            stack.addLast(start); onStack += start
            while (work.isNotEmpty()) {
                val (node, i) = work.removeLast()
                val neighbors = adjacency[node].orEmpty()
                if (i < neighbors.size) {
                    work.addLast(node to i + 1)
                    val next = neighbors[i]
                    if (next !in indexOf) {
                        indexOf[next] = counter; lowlink[next] = counter; counter++
                        stack.addLast(next); onStack += next
                        work.addLast(next to 0)
                    } else if (next in onStack) {
                        lowlink[node] = minOf(lowlink.getValue(node), indexOf.getValue(next))
                    }
                } else {
                    if (lowlink.getValue(node) == indexOf.getValue(node)) {
                        val members = mutableListOf<String>()
                        while (true) {
                            val popped = stack.removeLast()
                            onStack -= popped
                            sccOf[popped] = sccCount
                            members += popped
                            if (popped == node) break
                        }
                        sccMembers[sccCount] = members
                        sccCount++
                    }
                    work.lastOrNull()?.let { (parent, _) ->
                        lowlink[parent] = minOf(lowlink.getValue(parent), lowlink.getValue(node))
                    }
                }
            }
        }

        // 循環参加エッジ = 同一SCC内（サイズ2以上）のエッジ＋自己ループ
        val cyclic = edges.filter { e ->
            e.from == e.to || (sccOf[e.from] != null && sccOf[e.from] == sccOf[e.to] &&
                sccMembers[sccOf.getValue(e.from)].orEmpty().size >= 2)
        }
        // 代表循環経路: SCC内の最小IDを起点に、SCC内のBFSで起点へ戻る最短閉路を組む（決定性は隣接のID昇順）
        val cyclePathByScc = mutableMapOf<Int, String>()
        for ((scc, members) in sccMembers) {
            if (members.size < 2) continue
            val memberSet = members.toSet()
            val startNode = members.min()
            val parent = mutableMapOf<String, String>()
            val queue = ArrayDeque(listOf(startNode))
            var closing: String? = null
            outer@ while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                for (next in adjacency[current].orEmpty()) {
                    if (next !in memberSet) continue
                    if (next == startNode) {
                        closing = current
                        break@outer
                    }
                    if (next !in parent) {
                        parent[next] = current
                        queue.addLast(next)
                    }
                }
            }
            val path = generateSequence(closing) { parent[it] }.toList().reversed()
            cyclePathByScc[scc] = (listOf(startNode) + path.drop(if (path.firstOrNull() == startNode) 1 else 0) + startNode)
                .joinToString(" → ")
        }
        return CycleResult(cyclic, cyclePathByScc, sccOf)
    }
}
