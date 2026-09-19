package semantic

import schema.CoverageLevel
import schema.RefTarget
import validate.Severity

/**
 * グラフ整合検証のカーネル（R21-01。3層コア移行 Phase 6A で validate/GraphIntegrity.kt から移動）。
 * 関係レジストリの宣言のうち非循環（acyclic）と、強制スイッチ（enforcement）の宣言のない関係名を
 * SemanticEntityGraph の隣接上で判定し、全文書分の指摘を文書座標つきで返す。
 * 対象型と多重度は、組込み定義体が作る検査になった（P29 Phase 6 第2段）。
 * ルート側 GraphIntegrity は IdIndex → カーネル座標の変換シェル（ファイル単位の絞り込みのみ）。
 * - 判定保留の哲学（§4.26）: 型未宣言のエンティティ・未解決参照には黙る（未解決は参照解決の領分）。
 */
object SemanticGraphIntegrity {

    private fun severityOf(level: CoverageLevel): Severity =
        level.toSeverity()

    /**
     * グラフ整合の指摘（全文書分）。レジストリが空なら何もしない（宣言のないワークスペースは挙動不変）。
     */
    fun issues(
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        declarations: SemanticDeclarations,
    ): List<SemanticIssue> {
        val relations = declarations.relations
        val enforcement = declarations.enforcement
        if (relations.isEmpty() && enforcement.undeclaredRelation == null) return emptyList()
        val issues = mutableListOf<SemanticIssue>()

        // 1) 宣言のない関係名（enforcement.undeclaredRelation）: 参照値の座標へ指摘する。
        //    データではなく宣言そのものの検査のため、定義体にせずここに残す（P29 Phase 6・DL-04）
        enforcement.undeclaredRelation?.let { level ->
            for (ref in refs) {
                if (ref.targetKind != RefTarget.ENTITY) continue  // 文書参照（R21-03）は別名前空間
                val name = ref.relation ?: continue
                if (name in relations) continue
                issues += SemanticIssue(
                    severityOf(level),
                    "参照「${ref.targetId}」の関係「$name」は関係レジストリに宣言されていません",
                    ref.document, ref.path,
                )
            }
        }

        // 2) 多重度と対象型は、組込み定義体（builtin-definitions/relations.yaml）が作る検査になった
        //    （P29 Phase 6 第2段。クエリ＋参照の本数＋値の比較／ノードの条件の否定＋参照の場所への指摘）

        // 3) 非循環: acyclic 宣言された relation のエッジ集合で循環を検出し、
        //    循環構成参照値の座標へ ERROR（宣言した時点で構造の前提のため常にエラー）
        for (def in relations.values.filter { it.acyclic }) {
            val cycle = detectCycles(graph, def.name)
            for (edge in cycle.edges) {
                issues += SemanticIssue(
                    Severity.ERROR,
                    "関係「${def.name}」は非循環（acyclic）ですが循環しています（循環経路: ${cycle.pathTextOf(edge)}）",
                    edge.ref.document, edge.ref.path,
                )
            }
        }
        return issues
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
