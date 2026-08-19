package semantic

import schema.CoverageLevel
import schema.RefTarget
import validate.Severity

/**
 * グラフ整合検証のカーネル（R21-01。3層コア移行 Phase 6A で validate/GraphIntegrity.kt から移動）。
 * 関係レジストリの宣言（対象型・多重度・非循環）と強制スイッチ（enforcement）を
 * SemanticEntityGraph の隣接上で判定し、全文書分の指摘を文書座標つきで返す。
 * ルート側 GraphIntegrity は IdIndex → カーネル座標の変換シェル（ファイル単位の絞り込みのみ）。
 * - 判定保留の哲学（§4.26）: 型未宣言のエンティティ・未解決参照には黙る（未解決は参照解決の領分）。
 */
object SemanticGraphIntegrity {

    private fun severityOf(level: CoverageLevel): Severity =
        if (level == CoverageLevel.ERROR) Severity.ERROR else Severity.WARNING

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
        val refsByTarget = refs.filter { it.targetKind == RefTarget.ENTITY }.groupBy { it.targetId }

        // 1) 対象型・未宣言 relation: 参照値の座標へ指摘する
        for (ref in refs) {
            if (ref.targetKind != RefTarget.ENTITY) continue  // 文書参照（R21-03）は別名前空間
            val name = ref.relation ?: continue
            val def = relations[name]
            if (def == null) {
                enforcement.undeclaredRelation?.let { level ->
                    issues += SemanticIssue(
                        severityOf(level),
                        "参照「${ref.targetId}」の関係「$name」は関係レジストリに宣言されていません",
                        ref.document, ref.path,
                    )
                }
                continue
            }
            if (def.source.isNotEmpty()) {
                val sourceType = graph.sourceEntryOf(ref)?.entityType
                if (sourceType != null && sourceType !in def.source) {
                    issues += SemanticIssue(
                        severityOf(def.level),
                        "関係「$name」の参照元エンティティ型「$sourceType」は宣言（source: ${def.source.joinToString(", ")}）に一致しません",
                        ref.document, ref.path,
                    )
                }
            }
            if (def.target.isNotEmpty()) {
                val targetType = graph.entities[ref.targetId]?.entityType
                if (targetType != null && targetType !in def.target) {
                    issues += SemanticIssue(
                        severityOf(def.level),
                        "関係「$name」の参照先エンティティ型「$targetType」は宣言（target: ${def.target.joinToString(", ")}）に一致しません",
                        ref.document, ref.path,
                    )
                }
            }
        }

        // 2) 多重度: 母集団は source / target の型宣言に一致する型つきエンティティ。
        //    座標は該当エンティティの定義出現（なければ先頭出現）
        for (node in graph.entities.values) {
            val type = node.entityType ?: continue
            val anchor: ScannedId = node.defining ?: node.occurrences.first()
            for (def in relations.values) {
                val c = def.cardinality ?: continue
                if (type in def.source && (c.outMin != null || c.outMax != null)) {
                    // out はエンティティ単位の合算（同一関係の参照を複数出現に分けて書けるため）
                    val count = graph.outgoingRefs(node.id).count { it.relation == def.name }
                    c.outMin?.let { min ->
                        if (count < min) issues += SemanticIssue(
                            severityOf(def.level),
                            "エンティティ「${node.id}」の関係「${def.name}」の参照が不足しています（現在 $count 件／下限 $min 件）",
                            anchor.document, anchor.path,
                        )
                    }
                    c.outMax?.let { max ->
                        if (count > max) issues += SemanticIssue(
                            severityOf(def.level),
                            "エンティティ「${node.id}」の関係「${def.name}」の参照が過剰です（現在 $count 件／上限 $max 件）",
                            anchor.document, anchor.path,
                        )
                    }
                }
                if (type in def.target && (c.inMin != null || c.inMax != null)) {
                    val count = refsByTarget[node.id].orEmpty().count { it.relation == def.name }
                    c.inMin?.let { min ->
                        if (count < min) issues += SemanticIssue(
                            severityOf(def.level),
                            "エンティティ「${node.id}」への関係「${def.name}」の被参照が不足しています（現在 $count 件／下限 $min 件）",
                            anchor.document, anchor.path,
                        )
                    }
                    c.inMax?.let { max ->
                        if (count > max) issues += SemanticIssue(
                            severityOf(def.level),
                            "エンティティ「${node.id}」への関係「${def.name}」の被参照が過剰です（現在 $count 件／上限 $max 件）",
                            anchor.document, anchor.path,
                        )
                    }
                }
            }
        }

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
