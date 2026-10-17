package dev.toros.yde.app.ui

import dev.toros.yde.semantic.ScannedId
import dev.toros.yde.semantic.ScannedRef
import dev.toros.yde.semantic.SemanticEntityGraph
import dev.toros.yde.semantic.SemanticHolding
import dev.toros.yde.semantic.SemanticSchemaGraph
import dev.toros.yde.semantic.schema.EntityRole
import dev.toros.yde.semantic.schema.RefTarget
import dev.toros.yde.yaml.model.pretty

// ================================================================ グラフレンズの節点と辺（architecture/graph-query.md「エンティティページ」）
//
// レンズに描く節点・辺・箱の入れ子を、エンティティグラフから作る純ロジック（Compose に依存しない。LensGraphTest）。
// 見え方は 2 つ: 一体（箱＝エンティティ。同じ ID の出現をまとめる）と、側面で分ける（箱＝出現。文書ごとの書かれ方そのまま）。
// 箱の入れ子は保有の線で決める。一体では定義出現の保有（誕生の場所）だけを入れ子にし、詳細化の出現の保有は線で描く。

/** レンズの見え方。 */
enum class LensMode(val label: String) {
    /** 箱＝エンティティ。同じ ID の出現を 1 つの箱にまとめる。 */
    ENTITY("一体"),

    /** 箱＝出現。文書ごとの書かれ方をそのまま描き、同じエンティティの箱を点線で結ぶ。 */
    OCCURRENCE("側面で分ける"),
}

/** レンズの段: 実体（箱＝エンティティ・線＝参照）か、型（箱＝エンティティ型・線＝関係＝スキーマの図。P42）。 */
enum class LensLevel(val label: String) {
    ENTITY("実体"),
    TYPE("型"),
}

/** レンズの節点。parent は箱の入れ子の親（保有の線）。 */
data class LensNode(
    val key: String,
    val id: String,
    /** 側面で分けるときの出現。一体のときは null。 */
    val occurrence: ScannedId?,
    val parent: String?,
    /** 親との保有の関係名（入れ子の線の名前）。 */
    val relation: String?,
)

/** 辺の種類: 参照・箱に入れない保有の線・同じエンティティの箱どうし（点線）。 */
enum class LensEdgeKind { REF, HOLDING, TIE }

/**
 * 辺。reversed は、関係レジストリの `arrow: reverse` で向きを逆にした参照の線（from が相手・to が参照を書いた側。文字は inverseLabel）。
 * 向きは描く絵と階層配置のためだけで、起点の近傍（向きを問わない）には効かない。
 */
data class LensGraphEdge(val from: String, val to: String, val relation: String?, val kind: LensEdgeKind, val reversed: Boolean = false)

data class LensGraph(val mode: LensMode, val nodes: List<LensNode>, val edges: List<LensGraphEdge>)

/** 絞り込みと畳みの指定。 */
data class LensSelectParams(
    val typeOf: (String) -> String,
    val hiddenTypes: Set<String> = emptySet(),
    val hiddenRelations: Set<String> = emptySet(),
    /** 起点のエンティティ ID（側面で分けるときは、その ID の全部の出現が起点）。 */
    val origin: String? = null,
    val depth: Int = 2,
    /** 利用者が開いた（true）・畳んだ（false）箱。無い箱は既定（起点の近傍だけ開く）。 */
    val expanded: Map<String, Boolean> = emptyMap(),
)

/** 見える節点 1 つ。parent は絞り込み後の入れ子の親（隠した型・隠した関係で切れた親は無い）。 */
data class LensVisibleNode(
    val node: LensNode,
    val parent: String?,
    val expanded: Boolean,
    /** 畳んだ箱の直下の子の数（開いた箱・子の無い箱は 0）。 */
    val hiddenChildren: Int,
)

/** 絞り込みと畳みを当てた見える部分。nodes は親が先（深さ優先）、edges は畳んだ箱の中の端を見える箱へ付け替えたもの。 */
data class LensSelection(val nodes: List<LensVisibleNode>, val edges: List<LensGraphEdge>)

object LensGraphs {

    /** 出現の箱の鍵（ID・文書・パス）。 */
    fun occurrenceKey(e: ScannedId): String = "${e.id}@${e.document.value}#${e.path.pretty()}"

    /** 出現の並び（エンティティページの合成と同じ: 定義出現 → 文書 → パス）。 */
    fun orderedOccurrences(graph: SemanticEntityGraph, id: String): List<ScannedId> =
        graph.entities[id]?.occurrences.orEmpty()
            .sortedWith(compareBy({ it.role != EntityRole.DEFINING }, { it.document.value }, { it.path.toString() }))

    /**
     * エンティティグラフから、見え方ごとの節点と辺を作る。節点は ID 昇順（側面で分けるときは ID → 出現の並び）。
     * reversed は矢印を逆向きに描く関係名（レジストリの `arrow: reverse`）。
     */
    fun build(graph: SemanticEntityGraph, refs: List<ScannedRef>, mode: LensMode, reversed: Set<String> = emptySet()): LensGraph = when (mode) {
        LensMode.ENTITY -> buildEntities(graph, refs, reversed)
        LensMode.OCCURRENCE -> buildOccurrences(graph, refs, reversed)
    }

    /** 参照の辺（関係が reversed にあれば向きを逆にする）。 */
    private fun refEdge(from: String, to: String, relation: String?, reversed: Set<String>): LensGraphEdge =
        if (relation != null && relation in reversed) LensGraphEdge(to, from, relation, LensEdgeKind.REF, reversed = true)
        else LensGraphEdge(from, to, relation, LensEdgeKind.REF)

    /**
     * スキーマの図（型の段）: 箱＝エンティティ型・線＝関係（宣言と導出）・箱の入れ子＝保有の宣言のうち子を定義する場所（誕生の場所。
     * 一体のレンズと同じ決まり）。誕生の場所に選ばれなかった保有は線（ひし形）。親の連鎖が自分へ戻れば切る。節点は型名の昇順。
     */
    fun buildTypes(graph: SemanticSchemaGraph, reversed: Set<String> = emptySet()): LensGraph {
        val types = graph.types.map { it.type }.sorted()
        val birth = HashMap<String, dev.toros.yde.semantic.SchemaGraphHolding>()
        graph.holdings.filter { it.childDefines && it.parent != it.child }
            .sortedWith(compareBy({ it.parent }, { it.relation }))
            .forEach { h -> birth.putIfAbsent(h.child, h) }
        types.forEach { t ->
            val seen = mutableSetOf(t)
            var cursor = birth[t]?.parent
            while (cursor != null) {
                if (!seen.add(cursor)) { birth.remove(t); break }
                cursor = birth[cursor]?.parent
            }
        }
        val nodes = types.map { t -> LensNode(t, t, null, birth[t]?.parent, birth[t]?.relation) }
        val edges = LinkedHashSet<LensGraphEdge>()
        graph.edges.forEach { e -> if (e.from != e.to) edges += refEdge(e.from, e.to, e.relation, reversed) }
        graph.holdings.forEach { h ->
            if (h.parent != h.child && birth[h.child] !== h) edges += LensGraphEdge(h.parent, h.child, h.relation, LensEdgeKind.HOLDING)
        }
        return LensGraph(LensMode.ENTITY, nodes, edges.toList())
    }

    private fun resolvedRefs(graph: SemanticEntityGraph, refs: List<ScannedRef>): List<Pair<ScannedId, ScannedRef>> =
        refs.asSequence()
            .filter { it.targetKind == RefTarget.ENTITY && graph.entities.containsKey(it.targetId) }
            .mapNotNull { r -> graph.sourceEntryOf(r)?.let { it to r } }
            .toList()

    private fun buildEntities(graph: SemanticEntityGraph, refs: List<ScannedRef>, reversed: Set<String>): LensGraph {
        val ids = graph.entities.keys.sorted()
        // 誕生の場所: 定義出現を直下に書いている外側の要素（親の ID 昇順の先頭）。親の連鎖が自分へ戻るなら切る
        val birth = HashMap<String, SemanticHolding>()
        ids.forEach { id ->
            graph.holdersOf(id).firstOrNull { it.child.role == EntityRole.DEFINING && it.parent.id != id }?.let { birth[id] = it }
        }
        ids.forEach { id ->
            val seen = mutableSetOf(id)
            var cursor = birth[id]?.parent?.id
            while (cursor != null) {
                if (!seen.add(cursor)) { birth.remove(id); break }
                cursor = birth[cursor]?.parent?.id
            }
        }
        val nodes = ids.map { id -> LensNode(id, id, null, birth[id]?.parent?.id, birth[id]?.relation) }
        val edges = LinkedHashSet<LensGraphEdge>()
        resolvedRefs(graph, refs).forEach { (src, r) ->
            if (src.id != r.targetId) edges += refEdge(src.id, r.targetId, r.relation, reversed)
        }
        // 箱に入れない保有（詳細化の出現の保有・誕生の場所に選ばれなかった保有）は線
        graph.holdings.forEach { h ->
            if (h.parent.id != h.child.id && birth[h.child.id] !== h) {
                edges += LensGraphEdge(h.parent.id, h.child.id, h.relation, LensEdgeKind.HOLDING)
            }
        }
        return LensGraph(LensMode.ENTITY, nodes, edges.toList())
    }

    private fun buildOccurrences(graph: SemanticEntityGraph, refs: List<ScannedRef>, reversed: Set<String>): LensGraph {
        val ids = graph.entities.keys.sorted()
        val nodes = mutableListOf<LensNode>()
        val edges = LinkedHashSet<LensGraphEdge>()
        ids.forEach { id ->
            val occurrences = orderedOccurrences(graph, id)
            val holders = graph.holdersOf(id)
            occurrences.forEach { occ ->
                val holder = holders.firstOrNull { it.child == occ && it.parent.id != id }
                nodes += LensNode(occurrenceKey(occ), id, occ, holder?.let { occurrenceKey(it.parent) }, holder?.relation)
            }
            // 同じエンティティの箱は、出現の並びの順に点線で結ぶ
            occurrences.zipWithNext().forEach { (a, b) ->
                edges += LensGraphEdge(occurrenceKey(a), occurrenceKey(b), null, LensEdgeKind.TIE)
            }
        }
        // 参照は、書いた出現から、相手の代表の出現（定義出現、無ければ先頭）へ
        fun representative(id: String): ScannedId? = graph.entities[id]?.let { it.defining ?: it.occurrences.firstOrNull() }
        resolvedRefs(graph, refs).forEach { (src, r) ->
            val target = representative(r.targetId) ?: return@forEach
            val from = occurrenceKey(src)
            val to = occurrenceKey(target)
            if (from != to) edges += refEdge(from, to, r.relation, reversed)
        }
        return LensGraph(LensMode.OCCURRENCE, nodes, edges.toList())
    }

    /**
     * 絞り込み（型・関係・起点の近傍）と畳みを当てる。
     * - 隠した型の節点は消える（起点は消えない）。親が消えた・親との関係を隠した子は、いちばん外側へ出る
     * - 起点があれば、辺と入れ子（親子）を幅優先で辿った近傍だけ。着いた箱の外側の箱も残す（箱の中を見せるには箱が要る）
     * - 畳んだ箱の中は見せず、中の箱につながる辺は見える箱へ付け替える。既定で開くのは起点の外側の箱と起点自身
     *   （深さ 2 なら起点の子も）。利用者が開いた・畳んだ箱はその指定が勝つ
     */
    fun select(graph: LensGraph, params: LensSelectParams): LensSelection {
        val origin = params.origin
        fun shownType(node: LensNode) = node.id == origin || params.typeOf(node.id) !in params.hiddenTypes
        val present = graph.nodes.filter(::shownType).associateBy { it.key }
        // 絞り込み後の親: 親が消えた・親との関係を隠した子はいちばん外側へ
        val parentOf = HashMap<String, String>()
        present.values.forEach { n ->
            val p = n.parent ?: return@forEach
            if (p in present && (n.relation == null || n.relation !in params.hiddenRelations)) parentOf[n.key] = p
        }
        val edges = graph.edges.filter { e ->
            e.from in present && e.to in present && (e.relation == null || e.relation !in params.hiddenRelations)
        }
        // 起点の近傍（辺と親子を両方向に辿る）
        var kept: Set<String> = present.keys
        if (origin != null) {
            val adjacency = HashMap<String, MutableList<String>>()
            fun link(a: String, b: String) {
                adjacency.getOrPut(a) { mutableListOf() } += b
                adjacency.getOrPut(b) { mutableListOf() } += a
            }
            edges.forEach { link(it.from, it.to) }
            parentOf.forEach { (child, parent) -> link(child, parent) }
            val seeds = present.values.filter { it.id == origin }.map { it.key }.sorted()
            val reached = LinkedHashSet(seeds)
            var frontier = seeds
            repeat(params.depth) {
                frontier = frontier.flatMap { adjacency[it].orEmpty() }.filter(reached::add).sorted()
            }
            // 着いた箱の外側の箱も残す
            val withAncestors = LinkedHashSet(reached)
            reached.forEach { key ->
                var cursor = parentOf[key]
                while (cursor != null && withAncestors.add(cursor)) cursor = parentOf[cursor]
            }
            kept = withAncestors
        }
        val keptNodes = present.filterKeys { it in kept }
        val children = HashMap<String, MutableList<String>>()
        keptNodes.keys.forEach { key -> parentOf[key]?.let { p -> if (p in keptNodes) children.getOrPut(p) { mutableListOf() } += key } }
        children.values.forEach { it.sortWith(compareBy({ keptNodes.getValue(it).id }, { it })) }
        val roots = keptNodes.keys.filter { parentOf[it] !in keptNodes }.sortedWith(compareBy({ keptNodes.getValue(it).id }, { it }))
        // 既定で開く箱: 起点の外側の箱と起点自身（深さ 2 なら起点の子も）
        val defaultOpen = HashSet<String>()
        if (origin != null) {
            keptNodes.values.filter { it.id == origin }.forEach { seed ->
                defaultOpen += seed.key
                if (params.depth >= 2) defaultOpen += children[seed.key].orEmpty()
                var cursor = parentOf[seed.key]
                while (cursor != null && defaultOpen.add(cursor)) cursor = parentOf[cursor]
            }
        }
        fun expanded(key: String) = params.expanded[key] ?: (key in defaultOpen)
        // 深さ優先で見える箱を並べる（親が先）。畳んだ箱の中は見えない
        val visible = mutableListOf<LensVisibleNode>()
        val visibleAncestor = HashMap<String, String>()   // 見えない箱 → それを含む見える箱
        fun walk(key: String, parent: String?) {
            val node = keptNodes.getValue(key)
            val kids = children[key].orEmpty()
            val open = kids.isNotEmpty() && expanded(key)
            visible += LensVisibleNode(node, parent, open, if (open) 0 else kids.size)
            if (open) kids.forEach { walk(it, key) }
            else {
                fun hide(k: String) { visibleAncestor[k] = key; children[k].orEmpty().forEach(::hide) }
                kids.forEach(::hide)
            }
        }
        roots.forEach { walk(it, null) }
        val visibleKeys = visible.map { it.node.key }.toSet()
        fun shownEnd(key: String): String? = if (key in visibleKeys) key else visibleAncestor[key]
        val shownEdges = LinkedHashSet<LensGraphEdge>()
        edges.forEach { e ->
            val from = shownEnd(e.from) ?: return@forEach
            val to = shownEnd(e.to) ?: return@forEach
            if (from != to) shownEdges += LensGraphEdge(from, to, e.relation, e.kind, e.reversed)
        }
        return LensSelection(visible, shownEdges.toList())
    }
}
