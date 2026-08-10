package validate

import index.IdEntry
import index.IdIndex
import index.RefEntry
import schema.CoverageLevel
import schema.EnforcementDef
import schema.RelationDef
import java.nio.file.Path

/**
 * グラフ整合検証（R21-01。ARCHITECTURE §4.31）。関係レジストリの宣言（対象型・多重度・非循環）と
 * 強制スイッチ（enforcement）を EntityGraph の隣接上で判定する。
 * - 判定材料は索引（EntityGraph）＝カバレッジと同じ基準。per-file の検証からは
 *   「このファイルに座標を持つ指摘」だけを報告する。
 * - 判定保留の哲学（§4.26）: 型未宣言のエンティティ・未解決参照には黙る（未解決は V-02 の領分）。
 * - 入次数・出現数のカウンタはカバレッジ（requiredBy / detailIn）と共有する（coverage は糖衣として存続）。
 */
object GraphIntegrity {

    /**
     * 指定IDへの参照（入次数エッジ）。relation / 参照元スキーマで絞り込む。
     * カバレッジ（requiredBy）と多重度（in）の共有カウンタ。
     */
    fun inboundRefs(
        indexObj: IdIndex,
        id: String,
        relation: String? = null,
        fromSchemaIds: List<String> = emptyList(),
    ): List<RefEntry> = indexObj.graph.backlinksTo(setOf(id)).filter { ref ->
        ref.targetKind == schema.RefTarget.ENTITY &&
            (relation == null || ref.relation == relation) &&
            (fromSchemaIds.isEmpty() || ref.sourceSchemaId in fromSchemaIds)
    }

    /** 指定IDの、指定スキーマ群の文書への出現数。カバレッジ（detailIn）の共有カウンタ。 */
    fun occurrenceCount(indexObj: IdIndex, id: String, schemaIds: List<String>): Int =
        indexObj.occurrences(id).count { it.schemaId in schemaIds }

    private fun severityOf(level: CoverageLevel): Severity =
        if (level == CoverageLevel.ERROR) Severity.ERROR else Severity.WARNING

    /**
     * このファイルに座標を持つグラフ整合の指摘を返す。
     * レジストリが空なら何もしない（宣言のないワークスペースは挙動不変）。
     */
    fun issues(
        file: Path,
        indexObj: IdIndex,
        relations: Map<String, RelationDef>,
        enforcement: EnforcementDef = EnforcementDef(),
    ): List<Issue> {
        if (relations.isEmpty() && enforcement.undeclaredRelation == null) return emptyList()
        val issues = mutableListOf<Issue>()
        val graph = indexObj.graph

        // 1) 対象型・未宣言 relation: このファイルに書かれた参照値の座標へ指摘する
        for (ref in indexObj.refs) {
            if (ref.file != file) continue
            if (ref.targetKind != schema.RefTarget.ENTITY) continue  // 文書参照（R21-03）は別名前空間
            val name = ref.relation ?: continue
            val def = relations[name]
            if (def == null) {
                enforcement.undeclaredRelation?.let { level ->
                    issues += Issue(
                        severityOf(level),
                        "参照「${ref.targetId}」の関係「$name」は関係レジストリに宣言されていません",
                        ref.path,
                    )
                }
                continue
            }
            if (def.source.isNotEmpty()) {
                val sourceType = graph.sourceEntryOf(ref)?.entityType
                if (sourceType != null && sourceType !in def.source) {
                    issues += Issue(
                        severityOf(def.level),
                        "関係「$name」の参照元エンティティ型「$sourceType」は宣言（source: ${def.source.joinToString(", ")}）に一致しません",
                        ref.path,
                    )
                }
            }
            if (def.target.isNotEmpty()) {
                val targetType = graph.entities[ref.targetId]?.entityType
                if (targetType != null && targetType !in def.target) {
                    issues += Issue(
                        severityOf(def.level),
                        "関係「$name」の参照先エンティティ型「$targetType」は宣言（target: ${def.target.joinToString(", ")}）に一致しません",
                        ref.path,
                    )
                }
            }
        }

        // 2) 多重度: 母集団は source / target の型宣言に一致する型つきエンティティ。
        //    座標は該当エンティティの定義出現（なければ先頭出現）で、このファイルにあるものだけ報告する
        for (node in graph.entities.values) {
            val type = node.entityType ?: continue
            val anchor: IdEntry = node.defining ?: node.occurrences.first()
            if (anchor.file != file) continue
            for (def in relations.values) {
                val c = def.cardinality ?: continue
                if (type in def.source && (c.outMin != null || c.outMax != null)) {
                    // out はエンティティ単位の合算（同一関係の参照を複数出現に分けて書けるため）
                    val count = graph.outgoingRefs(node.id).count { it.relation == def.name }
                    c.outMin?.let { min ->
                        if (count < min) issues += Issue(
                            severityOf(def.level),
                            "エンティティ「${node.id}」の関係「${def.name}」の参照が不足しています（現在 $count 件／下限 $min 件）",
                            anchor.path,
                        )
                    }
                    c.outMax?.let { max ->
                        if (count > max) issues += Issue(
                            severityOf(def.level),
                            "エンティティ「${node.id}」の関係「${def.name}」の参照が過剰です（現在 $count 件／上限 $max 件）",
                            anchor.path,
                        )
                    }
                }
                if (type in def.target && (c.inMin != null || c.inMax != null)) {
                    val count = inboundRefs(indexObj, node.id, relation = def.name).size
                    c.inMin?.let { min ->
                        if (count < min) issues += Issue(
                            severityOf(def.level),
                            "エンティティ「${node.id}」への関係「${def.name}」の被参照が不足しています（現在 $count 件／下限 $min 件）",
                            anchor.path,
                        )
                    }
                    c.inMax?.let { max ->
                        if (count > max) issues += Issue(
                            severityOf(def.level),
                            "エンティティ「${node.id}」への関係「${def.name}」の被参照が過剰です（現在 $count 件／上限 $max 件）",
                            anchor.path,
                        )
                    }
                }
            }
        }

        // 3) 非循環: acyclic 宣言された relation のエッジ集合で循環を検出し、
        //    このファイル内の循環構成参照値の座標へ ERROR（宣言した時点で構造の前提のため常にエラー）
        for (def in relations.values.filter { it.acyclic }) {
            val cycle = detectCycles(indexObj, def.name)
            for (edge in cycle.edges) {
                if (edge.ref.file != file) continue
                issues += Issue(
                    Severity.ERROR,
                    "関係「${def.name}」は非循環（acyclic）ですが循環しています（循環経路: ${cycle.pathTextOf(edge)}）",
                    edge.ref.path,
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

    private data class Edge(val from: String, val to: String, val ref: RefEntry)

    /**
     * 指定 relation のエッジ集合で循環（強連結成分・自己ループ）を検出する。
     * エッジの両端はいずれも索引上のエンティティ（未解決参照は対象外）。決定性のため隣接はID昇順。
     */
    private fun detectCycles(indexObj: IdIndex, relation: String): CycleResult {
        val graph = indexObj.graph
        val edges = mutableListOf<Edge>()
        val adjacency = sortedMapOf<String, MutableList<String>>()
        for (id in graph.entities.keys.sorted()) {
            for (ref in graph.outgoingRefs(id)) {
                if (ref.relation != relation) continue
                if (ref.targetKind != schema.RefTarget.ENTITY) continue
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
