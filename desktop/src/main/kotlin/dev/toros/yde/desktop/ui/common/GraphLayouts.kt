package dev.toros.yde.desktop.ui.common

import dev.toros.yde.semantic.*
import dev.toros.yde.view.flow.*
import dev.toros.yde.yaml.model.YPath
import org.eclipse.elk.alg.force.options.ForceMetaDataProvider
import org.eclipse.elk.alg.force.options.StressMetaDataProvider
import org.eclipse.elk.alg.force.options.StressOptions
import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.alg.layered.options.CycleBreakingStrategy
import org.eclipse.elk.alg.radial.options.RadialMetaDataProvider
import org.eclipse.elk.alg.radial.options.RadialOptions
import org.eclipse.elk.alg.spore.options.SporeMetaDataProvider
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeLabelPlacement
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.core.options.HierarchyHandling
import org.eclipse.elk.core.math.ElkPadding
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.graph.ElkEdge
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.util.ElkGraphUtil
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

// ================================================================ グラフレンズとフローの配置（ELK を包む）
//
// ライブラリ ELK（Eclipse Layout Kernel）はこのファイルの中だけで使い、型を外へ出さない。
// Compose に依存しない純ロジック（GraphLayoutsTest）。大きさは呼び手が計測した px で受け取り、座標も px で返す。

/** レンズの配置の種類（画面の切替）。 */
enum class LensLayoutKind(val label: String, val description: String) {
    RADIAL("放射", "起点を中心に、近い順に輪で並べる（起点があるとき）"),
    LAYERED("階層", "参照の向きに沿って上から下へ並べる"),
    STRESS("応力", "つながりの近さを距離に映す（${GraphLayouts.STRESS_MAX_NODES} 節点まで）"),
    FORCE("力学", "軽い力学配置（計算が速い）"),
}

/** 配置に渡す節点。大きさは文字を計測した px。 */
data class LensNodeSize(val id: String, val width: Float, val height: Float)

/** 配置に渡す辺（節点の添字）。ラベルの大きさは px（無ければ 0）。 */
data class LensEdgeSpec(val from: Int, val to: Int, val labelWidth: Float = 0f, val labelHeight: Float = 0f)

/**
 * 入れ子の配置に渡す節点。width / height は箱の見出し（子を持たない箱なら箱そのもの）の大きさ、parent は入れ子の親の添字。
 * 子を持つ箱の大きさは、見出しの下に子の配置を収めた大きさになる。
 */
data class LensNestedNode(val id: String, val width: Float, val height: Float, val parent: Int? = null)

data class LensPoint(val x: Float, val y: Float)

/** 節点の箱（左上と大きさ）。 */
data class LensBox(val x: Float, val y: Float, val width: Float, val height: Float) {
    val centerX: Float get() = x + width / 2
    val centerY: Float get() = y + height / 2
    fun contains(px: Float, py: Float): Boolean = px >= x && px <= x + width && py >= y && py <= y + height
}

/** 辺の経路（始点から終点への折れ線。空なら描かない）とラベルの中心。 */
data class LensRoute(val points: List<LensPoint>, val label: LensPoint?)

/**
 * 配置の結果。`boxes` は渡した節点と同じ順、`routes` は渡した辺と同じ順。座標は 0 以上で、`width` × `height` に収まる。
 * `kind` は実際に使った種類（起点が無い・節点が多いなどで代替したときは渡した種類と違い、`note` に理由が入る）。
 */
data class LensArrangement(
    val kind: LensLayoutKind,
    val boxes: List<LensBox>,
    val routes: List<LensRoute>,
    val width: Float,
    val height: Float,
    val note: String? = null,
)

/** 画面に収める倍率と平行移動。 */
data class LensViewport(val zoom: Float, val panX: Float, val panY: Float)

/** 配置の全体（content）を画面（view）に収める倍率と位置。拡大はせず（最大 1 倍）、中央に置く。 */
internal fun fitViewport(contentWidth: Float, contentHeight: Float, viewWidth: Float, viewHeight: Float): LensViewport {
    if (contentWidth <= 0f || contentHeight <= 0f || viewWidth <= 0f || viewHeight <= 0f) return LensViewport(1f, 0f, 0f)
    val zoom = minOf(viewWidth / contentWidth, viewHeight / contentHeight, 1f).coerceAtLeast(0.02f)
    return LensViewport(zoom, (viewWidth - contentWidth * zoom) / 2, (viewHeight - contentHeight * zoom) / 2)
}

object GraphLayouts {
    /** 応力配置を許す節点数の上限（全対の計算で、これを超えると数十秒かかる）。 */
    const val STRESS_MAX_NODES = 150
    private const val MARGIN = 24f
    private const val PARALLEL_GAP = 14f
    private val lock = Any()

    /** ELK の配置の目録は 1 回だけ登録する。 */
    private val ready: Boolean by lazy {
        LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(
            LayeredMetaDataProvider(), ForceMetaDataProvider(), StressMetaDataProvider(),
            RadialMetaDataProvider(), SporeMetaDataProvider(),
        )
        true
    }

    /** フローの循環・レーン・条件ラベルを同じ配置問題として解く。ELKの型はこの窓口の外へ出さない。 */
    @JvmOverloads
    fun arrangeFlow(model: FlowModel, labels: Map<YPath, FlowSize>, direction: String = "down",
                    mode: FlowLayoutMode = FlowLayoutMode.GROUPED): FlowArrangement {
        if (mode == FlowLayoutMode.SWIMLANES) {
            val seed = arrangeFlow(model, labels, direction, FlowLayoutMode.PLAIN)
            return FlowSwimlanes.arrange(model, labels, seed, direction)
        }
        return synchronized(lock) {
            val graph = newGraph()
            graph.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered")
            graph.setProperty(CoreOptions.DIRECTION, if (direction == "right") Direction.RIGHT else Direction.DOWN)
            graph.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN)
            graph.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)
            graph.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, 64.0)
            graph.setProperty(LayeredOptions.SPACING_EDGE_NODE_BETWEEN_LAYERS, 24.0)
            graph.setProperty(CoreOptions.SPACING_EDGE_LABEL, 8.0)
            graph.setProperty(CoreOptions.SPACING_EDGE_NODE, 24.0)
            graph.setProperty(LayeredOptions.THOROUGHNESS, 10)
            graph.setProperty(LayeredOptions.CYCLE_BREAKING_STRATEGY, CycleBreakingStrategy.DEPTH_FIRST)
            graph.setProperty(CoreOptions.RANDOM_SEED, 1)
            fun size(path: YPath) = labels[path] ?: FlowSize(100f, 24f)
            fun dimensions(node: FlowNode): FlowSize {
                val text = size(node.output)
                val factor = if (node.shape == "diamond") 2f else 1f
                return FlowSize(maxOf(140f, (text.width + 32f) * factor), maxOf(60f, (text.height + 24f) * factor))
            }
            val lanes = (if (mode == FlowLayoutMode.GROUPED) model.lanes else emptyList()).associate { lane ->
                val childWidth = model.nodes.filter { it.lane?.target == lane.output }.maxOfOrNull { dimensions(it).width } ?: 0f
                lane.output to ElkGraphUtil.createNode(graph).apply {
                    identifier = lane.key
                    // 子が細いレーンでも見出しが収まる余白を確保する。空レーンにも寸法を残す。
                    width = size(lane.output).width + 16.0
                    height = size(lane.output).height + 48.0
                    val rightPadding = maxOf(24.0, size(lane.output).width + 16.0 - childWidth - 24.0)
                    setProperty(CoreOptions.PADDING, ElkPadding(size(lane.output).height + 24.0, rightPadding, 24.0, 24.0))
                    setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered")
                    setProperty(LayeredOptions.CYCLE_BREAKING_STRATEGY, CycleBreakingStrategy.DEPTH_FIRST)
                }
            }
            val nodes = model.nodes.associate { node ->
                val dimensions = dimensions(node)
                node.output to ElkGraphUtil.createNode(lanes[node.lane?.target] ?: graph).apply {
                    identifier = node.key
                    width = dimensions.width.toDouble()
                    height = dimensions.height.toDouble()
                }
            }
            val edges = model.edges.associate { edge ->
                val from = nodes[edge.from.target]
                val to = nodes[edge.to.target]
                edge.output to if (from == null || to == null) null else {
                    ElkGraphUtil.createSimpleEdge(from, to).apply {
                        identifier = edge.key
                        val text = size(edge.output)
                        ElkGraphUtil.createLabel(edge.label ?: "", this).apply {
                            width = text.width + 12.0
                            height = text.height + 12.0
                            setProperty(CoreOptions.EDGE_LABELS_PLACEMENT, EdgeLabelPlacement.CENTER)
                        }
                    }
                }
            }
            run(graph)
            fun offset(node: ElkNode?): FlowPoint {
                var x = MARGIN; var y = MARGIN
                var current = node
                while (current != null && current != graph) {
                    x += current.x.toFloat(); y += current.y.toFloat(); current = current.parent
                }
                return FlowPoint(x, y)
            }
            fun box(node: ElkNode): FlowRect {
                val p = offset(node)
                return FlowRect(p.x, p.y, node.width.toFloat(), node.height.toFloat())
            }
            var unresolvedY = graph.height.toFloat() + MARGIN * 2
            val diamonds = model.nodes.filter { it.shape == "diamond" }.map { it.output }.toSet()
            // ELKの接続点は外接矩形上。直交線の向きを保ったまま菱形の輪郭まで延ばす。
            fun outline(path: YPath?, point: FlowPoint): FlowPoint {
                if (path !in diamonds) return point
                val r = nodes[path]?.let(::box) ?: return point
                val dx = point.x - r.center.x; val dy = point.y - r.center.y
                return if (abs(abs(dx) - r.width / 2) < .1f)
                    FlowPoint(r.center.x + (if (dx < 0) -1 else 1) * r.width / 2 * (1 - abs(dy) / (r.height / 2)), point.y)
                else FlowPoint(point.x, r.center.y + (if (dy < 0) -1 else 1) * r.height / 2 * (1 - abs(dx) / (r.width / 2)))
            }
            val routes = model.edges.map { source ->
                val edge = edges[source.output]
                if (edge == null) {
                    val text = size(source.output)
                    val label = FlowRect(MARGIN, unresolvedY, text.width + 12f, text.height + 12f)
                    unresolvedY = label.bottom + 12f
                    FlowRoute(source.output, emptyList(), label)
                } else {
                    val origin = offset(edge.containingNode)
                    val points = edge.sections.flatMap { section ->
                        listOf(FlowPoint(section.startX.toFloat(), section.startY.toFloat())) +
                            section.bendPoints.map { FlowPoint(it.x.toFloat(), it.y.toFloat()) } +
                            FlowPoint(section.endX.toFloat(), section.endY.toFloat())
                    }.map { FlowPoint(it.x + origin.x, it.y + origin.y) }.toMutableList()
                    if (points.size >= 2) {
                        points[0] = outline(source.from.target, points.first())
                        points[points.lastIndex] = outline(source.to.target, points.last())
                    }
                    val label = edge.labels.single()
                    FlowRoute(source.output, points, FlowRect(origin.x + label.x.toFloat(), origin.y + label.y.toFloat(),
                        label.width.toFloat(), label.height.toFloat()))
                }
            }
            val boxes = nodes.mapValues { box(it.value) }
            val laneBoxes = lanes.mapValues { box(it.value) }
            val rectangles = boxes.values + laneBoxes.values + routes.map { it.label }
            val points = routes.flatMap { it.points }
            FlowArrangement(boxes, laneBoxes, routes, FlowSize(
                maxOf(rectangles.maxOfOrNull { it.right } ?: 0f, points.maxOfOrNull { it.x } ?: 0f) + MARGIN,
                maxOf(rectangles.maxOfOrNull { it.bottom } ?: 0f, points.maxOfOrNull { it.y } ?: 0f) + MARGIN))
        }
    }

    /**
     * 節点と辺を配置する。`origin` は起点の添字（放射配置の中心）。
     * 放射は起点が無ければ応力に、応力は節点が [STRESS_MAX_NODES] を超えれば階層に代替する。ELK が失敗したら軽い力学配置に戻す。
     */
    fun arrange(
        nodes: List<LensNodeSize>,
        edges: List<LensEdgeSpec>,
        kind: LensLayoutKind,
        origin: Int? = null,
    ): LensArrangement {
        if (nodes.isEmpty()) return LensArrangement(kind, emptyList(), emptyList(), 0f, 0f)
        // 範囲外・自己参照の辺は配置に渡さず、経路を空にする
        val valid = edges.map { e -> e.takeIf { it.from in nodes.indices && it.to in nodes.indices && it.from != it.to } }
        val root = origin?.takeIf { it in nodes.indices }
        var effective = kind
        var note: String? = null
        if (effective == LensLayoutKind.RADIAL && root == null) {
            effective = LensLayoutKind.STRESS
            note = "起点が無いので応力配置にしました"
        }
        if (effective == LensLayoutKind.STRESS && nodes.size > STRESS_MAX_NODES) {
            effective = LensLayoutKind.LAYERED
            note = "節点が $STRESS_MAX_NODES を超えるので階層配置にしました（応力配置は $STRESS_MAX_NODES 節点まで）"
        }
        if (effective == LensLayoutKind.FORCE) return simpleForce(nodes, valid, note)
        return try {
            synchronized(lock) {
                when (effective) {
                    LensLayoutKind.LAYERED -> layered(nodes, valid)
                    LensLayoutKind.STRESS -> stress(nodes, valid)
                    LensLayoutKind.RADIAL -> radial(nodes, valid, root!!)
                    LensLayoutKind.FORCE -> error("力学配置は ELK を使わない")
                }
            }.copy(note = note)
        } catch (e: Exception) {
            simpleForce(nodes, valid, "配置の計算に失敗したので力学配置にしました: ${e.message ?: e::class.simpleName}")
        }
    }

    /**
     * 利用者が動かした節点を反映した配置。`moved` は節点の添字 → 新しい左上の位置。
     * 動かした節点につながる辺は直線に引き直し、他の辺は元の経路のまま。全体の大きさも広げる。
     */
    fun withMovedBoxes(arrangement: LensArrangement, edges: List<LensEdgeSpec>, moved: Map<Int, LensPoint>): LensArrangement {
        if (moved.isEmpty()) return arrangement
        val boxes = arrangement.boxes.mapIndexed { i, b -> moved[i]?.let { b.copy(x = it.x, y = it.y) } ?: b }
        val touched = edges.map { e -> e.takeIf { (it.from in moved || it.to in moved) && it.from in boxes.indices && it.to in boxes.indices && it.from != it.to } }
        val rerouted = straightRoutes(boxes, touched)
        val routes = arrangement.routes.mapIndexed { i, r -> if (touched.getOrNull(i) != null) rerouted[i] else r }
        val points = routes.flatMap { it.points } + routes.mapNotNull { it.label }
        val width = maxOf(arrangement.width, boxes.maxOf { it.x + it.width } + MARGIN, (points.maxOfOrNull { it.x } ?: 0f) + MARGIN)
        val height = maxOf(arrangement.height, boxes.maxOf { it.y + it.height } + MARGIN, (points.maxOfOrNull { it.y } ?: 0f) + MARGIN)
        return arrangement.copy(boxes = boxes, routes = routes, width = width, height = height)
    }

    /**
     * 箱の入れ子を含む配置（グラフレンズの保有の箱）。段ごとに配置する: 子を持つ箱は先に中（子）を配置してその大きさを決め、
     * 外側の段ではその大きさの 1 つの節点として置く。段をまたぐ辺（箱の中の箱と外の箱を結ぶ辺）は、外側の段では
     * それぞれを含む箱の間の辺として配置に効かせ、描くときは実際の箱の縁から縁への直線にする。
     * 返す boxes の座標は全体の中の絶対位置（子の箱は親の箱の中）。起点は放射配置の中心（その段では起点を含む箱）。
     */
    fun arrangeNested(
        nodes: List<LensNestedNode>,
        edges: List<LensEdgeSpec>,
        kind: LensLayoutKind,
        origin: Int? = null,
    ): LensArrangement {
        if (nodes.isEmpty()) return LensArrangement(kind, emptyList(), emptyList(), 0f, 0f)
        // 親の添字（範囲外・自分自身・循環は無視して外側の箱にする）
        val parent = IntArray(nodes.size) { -1 }
        nodes.forEachIndexed { i, n ->
            val p = n.parent ?: return@forEachIndexed
            if (p !in nodes.indices || p == i) return@forEachIndexed
            var cursor = p
            var cyclic = false
            while (cursor >= 0) { if (cursor == i) { cyclic = true; break }; cursor = parent[cursor] }
            if (!cyclic) parent[i] = p
        }
        if (parent.all { it < 0 }) {
            return arrange(nodes.map { LensNodeSize(it.id, it.width, it.height) }, edges, kind, origin)
        }
        val children = List(nodes.size) { mutableListOf<Int>() }
        nodes.indices.forEach { i -> if (parent[i] >= 0) children[parent[i]] += i }
        // 外側から順の先祖の並び（自分を含む）
        val chain = List(nodes.size) { i ->
            generateSequence(i) { parent[it].takeIf { p -> p >= 0 } }.toList().reversed()
        }
        /** 段 level（親の添字。-1 = いちばん外側）の中で、節点 i を含む直下の箱。その段に無ければ null。 */
        fun memberAt(level: Int, i: Int): Int? {
            val c = chain[i]
            val at = if (level < 0) 0 else c.indexOf(level) + 1
            return if (level >= 0 && c.indexOf(level) < 0) null else c.getOrNull(at)
        }
        val valid = edges.map { e -> e.takeIf { it.from in nodes.indices && it.to in nodes.indices && it.from != it.to } }
        // 各辺の段: 2 つの端が分かれる段（いちばん深い共通の先祖。片方がもう片方を含むなら段に渡さず直線）
        val levelOf = IntArray(valid.size) { Int.MIN_VALUE }
        valid.forEachIndexed { i, e ->
            if (e == null) return@forEachIndexed
            val a = chain[e.from]
            val b = chain[e.to]
            var common = -1
            var k = 0
            while (k < a.size && k < b.size && a[k] == b[k]) { common = a[k]; k++ }
            levelOf[i] = if (common == e.from || common == e.to) Int.MIN_VALUE else common
        }
        val size = Array(nodes.size) { i -> nodes[i].width to nodes[i].height }
        val inner = HashMap<Int, LensArrangement>()   // 箱 → その中（子）の配置
        val innerMembers = HashMap<Int, List<Int>>()
        /** 段を配置する。子を持つ箱は先に中を配置して大きさを決める。 */
        fun layoutLevel(level: Int, members: List<Int>): LensArrangement {
            members.forEach { m -> if (children[m].isNotEmpty()) layoutLevel(m, children[m]).also { inner[m] = it } }
            innerMembers[level] = members
            val localIndex = members.withIndex().associate { (i, m) -> m to i }
            val localEdges = valid.mapIndexedNotNull { i, e ->
                if (e == null || levelOf[i] != level) null
                else memberAt(level, e.from)?.let { f -> memberAt(level, e.to)?.let { t -> LensEdgeSpec(localIndex.getValue(f), localIndex.getValue(t), e.labelWidth, e.labelHeight) } }
            }
            val localOrigin = origin?.let { memberAt(level, it) }?.let { localIndex[it] }
            return arrange(members.map { m -> LensNodeSize(nodes[m].id, size[m].first, size[m].second) }, localEdges, kind, localOrigin)
                .also { a ->
                    if (level >= 0) {
                        // 箱の大きさ = 見出しと、中の配置（余白込み）を収める大きさ
                        size[level] = maxOf(nodes[level].width, a.width) to (nodes[level].height + a.height)
                    }
                }
        }
        val roots = nodes.indices.filter { parent[it] < 0 }
        val top = layoutLevel(-1, roots)
        // 絶対位置: 外側から順に、中の配置を親の箱の見出しの下へずらす
        val boxes = arrayOfNulls<LensBox>(nodes.size)
        fun place(level: Int, arrangement: LensArrangement, offsetX: Float, offsetY: Float) {
            val members = innerMembers.getValue(level)
            members.forEachIndexed { i, m ->
                val b = arrangement.boxes[i]
                boxes[m] = LensBox(b.x + offsetX, b.y + offsetY, b.width, b.height)
                inner[m]?.let { a ->
                    val box = boxes[m]!!
                    place(m, a, box.x + (box.width - a.width) / 2, box.y + nodes[m].height)
                }
            }
        }
        place(-1, top, 0f, 0f)
        val placed = boxes.map { it!! }
        // 経路: 段の中の辺（両端がその段の箱）はその段の経路をずらして使い、段をまたぐ辺と箱と中の箱を結ぶ辺は直線
        val levelRoutes = HashMap<Int, Pair<LensArrangement, Pair<Float, Float>>>()
        fun collect(level: Int, arrangement: LensArrangement, offsetX: Float, offsetY: Float) {
            levelRoutes[level] = arrangement to (offsetX to offsetY)
            innerMembers.getValue(level).forEachIndexed { i, m ->
                inner[m]?.let { a ->
                    val b = arrangement.boxes[i]
                    val box = LensBox(b.x + offsetX, b.y + offsetY, b.width, b.height)
                    collect(m, a, box.x + (box.width - a.width) / 2, box.y + nodes[m].height)
                }
            }
        }
        collect(-1, top, 0f, 0f)
        val straight = straightRoutes(placed, valid)
        val routes = valid.mapIndexed { i, e ->
            if (e == null) LensRoute(emptyList(), null) else {
                val level = levelOf[i]
                val direct = level != Int.MIN_VALUE && memberAt(level, e.from) == e.from && memberAt(level, e.to) == e.to
                if (!direct) straight[i] else {
                    val (arrangement, offset) = levelRoutes.getValue(level)
                    val members = innerMembers.getValue(level)
                    val localIndex = members.withIndex().associate { (k, m) -> m to k }
                    // その段の辺の並び（levelOf が一致する辺の順）の中での位置
                    val position = valid.indices.filter { k -> valid[k] != null && levelOf[k] == level && memberAt(level, valid[k]!!.from) != null && memberAt(level, valid[k]!!.to) != null }.indexOf(i)
                    val r = arrangement.routes.getOrNull(position)
                    if (r == null || localIndex[e.from] == null) straight[i]
                    else LensRoute(r.points.map { LensPoint(it.x + offset.first, it.y + offset.second) }, r.label?.let { LensPoint(it.x + offset.first, it.y + offset.second) })
                }
            }
        }
        val points = routes.flatMap { it.points } + routes.mapNotNull { it.label }
        val width = maxOf(top.width, placed.maxOf { it.x + it.width } + MARGIN, (points.maxOfOrNull { it.x } ?: 0f) + MARGIN)
        val height = maxOf(top.height, placed.maxOf { it.y + it.height } + MARGIN, (points.maxOfOrNull { it.y } ?: 0f) + MARGIN)
        return LensArrangement(top.kind, placed, routes, width, height, top.note)
    }

    /**
     * 利用者が動かした箱を反映した入れ子の配置。動かした箱の中の箱も一緒に動く（中の箱を別に動かしていればそちらが勝つ）。
     * 動いた箱につながる辺は直線に引き直し、他の辺は元の経路のまま。全体の大きさも広げる。
     */
    fun withMovedNested(
        arrangement: LensArrangement,
        nodes: List<LensNestedNode>,
        edges: List<LensEdgeSpec>,
        moved: Map<Int, LensPoint>,
    ): LensArrangement {
        if (moved.isEmpty()) return arrangement
        val old = arrangement.boxes
        fun delta(i: Int): Pair<Float, Float> {
            var cursor: Int? = i
            while (cursor != null && cursor in old.indices) {
                moved[cursor]?.let { return (it.x - old[cursor!!].x) to (it.y - old[cursor!!].y) }
                cursor = nodes.getOrNull(cursor)?.parent
            }
            return 0f to 0f
        }
        val deltas = old.indices.map(::delta)
        val boxes = old.mapIndexed { i, b -> b.copy(x = b.x + deltas[i].first, y = b.y + deltas[i].second) }
        fun movedIndex(i: Int) = deltas.getOrNull(i)?.let { it.first != 0f || it.second != 0f } ?: false
        val touched = edges.map { e -> e.takeIf { (movedIndex(it.from) || movedIndex(it.to)) && it.from in boxes.indices && it.to in boxes.indices && it.from != it.to } }
        val rerouted = straightRoutes(boxes, touched)
        val routes = arrangement.routes.mapIndexed { i, r -> if (touched.getOrNull(i) != null) rerouted[i] else r }
        val points = routes.flatMap { it.points } + routes.mapNotNull { it.label }
        val width = maxOf(arrangement.width, boxes.maxOf { it.x + it.width } + MARGIN, (points.maxOfOrNull { it.x } ?: 0f) + MARGIN)
        val height = maxOf(arrangement.height, boxes.maxOf { it.y + it.height } + MARGIN, (points.maxOfOrNull { it.y } ?: 0f) + MARGIN)
        return arrangement.copy(boxes = boxes, routes = routes, width = width, height = height)
    }

    // ---------------------------------------------------------------- 各配置

    /** 階層（Sugiyama 系）。辺の経路とラベルの位置は ELK の結果を使う。 */
    private fun layered(nodes: List<LensNodeSize>, edges: List<LensEdgeSpec?>): LensArrangement {
        val graph = newGraph()
        val elkNodes = nodes.map { createNode(graph, it) }
        val elkEdges = edges.map { e ->
            e?.let {
                val edge = ElkGraphUtil.createSimpleEdge(elkNodes[it.from], elkNodes[it.to])
                if (it.labelWidth > 0f) {
                    ElkGraphUtil.createLabel("label", edge).apply {
                        width = it.labelWidth.toDouble()
                        height = it.labelHeight.toDouble()
                        setProperty(CoreOptions.EDGE_LABELS_PLACEMENT, EdgeLabelPlacement.CENTER)
                    }
                }
                edge
            }
        }
        graph.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered")
        graph.setProperty(CoreOptions.DIRECTION, Direction.DOWN)
        graph.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, 70.0)
        graph.setProperty(LayeredOptions.SPACING_EDGE_NODE_BETWEEN_LAYERS, 30.0)
        graph.setProperty(LayeredOptions.EDGE_ROUTING, EdgeRouting.SPLINES)
        graph.setProperty(LayeredOptions.THOROUGHNESS, 10)
        run(graph)
        val boxes = elkNodes.map { it.box() }
        val routes = elkEdges.map { edge -> edge?.let(::routeOf) ?: LensRoute(emptyList(), null) }
        return normalized(LensLayoutKind.LAYERED, boxes, routes)
    }

    /** 応力（stress majorization）。配置のあとに重なり除去を当て、辺は箱の縁から縁への直線。 */
    private fun stress(nodes: List<LensNodeSize>, edges: List<LensEdgeSpec?>): LensArrangement {
        val graph = newGraph()
        val elkNodes = nodes.map { createNode(graph, it) }
        edges.forEach { e -> e?.let { ElkGraphUtil.createSimpleEdge(elkNodes[it.from], elkNodes[it.to]) } }
        graph.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.stress")
        graph.setProperty(StressOptions.DESIRED_EDGE_LENGTH, 200.0)
        run(graph)
        graph.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.sporeOverlap")
        run(graph)
        val boxes = elkNodes.map { it.box() }
        return normalized(LensLayoutKind.STRESS, boxes, straightRoutes(boxes, edges))
    }

    /**
     * 放射。配置は木を前提にするので、起点からの幅優先の木（最短の道すじ）だけを渡し、木に入らない辺は直線で描く。
     * 起点から辿れない節点は配置の下に 1 列に並べる。
     */
    private fun radial(nodes: List<LensNodeSize>, edges: List<LensEdgeSpec?>, root: Int): LensArrangement {
        val tree = spanningTree(nodes.size, edges, root)
        val reached = setOf(root) + tree.map { it.second }
        val graph = newGraph()
        val elkNodes = HashMap<Int, ElkNode>()
        nodes.indices.filter { it in reached }.forEach { elkNodes[it] = createNode(graph, nodes[it]) }
        tree.forEach { (parent, child) -> ElkGraphUtil.createSimpleEdge(elkNodes.getValue(parent), elkNodes.getValue(child)) }
        graph.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.radial")
        graph.setProperty(RadialOptions.CENTER_ON_ROOT, true)
        graph.setProperty(CoreOptions.SPACING_NODE_NODE, 30.0)
        run(graph)
        val placed = elkNodes.mapValues { it.value.box() }
        val bottom = placed.values.maxOf { it.y + it.height } + 60f
        var x = placed.values.minOf { it.x }
        val boxes = nodes.indices.map { i ->
            placed[i] ?: LensBox(x, bottom, nodes[i].width, nodes[i].height).also { x += nodes[i].width + 20f }
        }
        return normalized(LensLayoutKind.RADIAL, boxes, straightRoutes(boxes, edges))
    }

    /** 軽い力学配置（[forceLayout]）。ELK が無くても動く逃げ道でもある。 */
    private fun simpleForce(nodes: List<LensNodeSize>, edges: List<LensEdgeSpec?>, note: String?): LensArrangement {
        val width = maxOf(600f, sqrt(nodes.size.toFloat()) * 220f)
        val height = width * 0.7f
        val centers = forceLayout(nodes.size, edges.mapNotNull { e -> e?.let { it.from to it.to } }, width, height)
        val boxes = nodes.mapIndexed { i, n -> LensBox(centers[i].first - n.width / 2, centers[i].second - n.height / 2, n.width, n.height) }
        return normalized(LensLayoutKind.FORCE, boxes, straightRoutes(boxes, edges)).copy(note = note)
    }

    // ---------------------------------------------------------------- 部品

    private fun newGraph(): ElkNode {
        check(ready)
        return ElkGraphUtil.createGraph().apply { setProperty(CoreOptions.SPACING_NODE_NODE, 40.0) }
    }

    private fun createNode(graph: ElkNode, n: LensNodeSize): ElkNode = ElkGraphUtil.createNode(graph).apply {
        width = n.width.toDouble()
        height = n.height.toDouble()
        identifier = n.id
    }

    private fun run(graph: ElkNode) = RecursiveGraphLayoutEngine().layout(graph, BasicProgressMonitor())

    private fun ElkNode.box() = LensBox(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat())

    /** ELK が求めた辺の経路（始点・曲がり角・終点）とラベルの中心。 */
    private fun routeOf(edge: ElkEdge): LensRoute {
        val section = edge.sections.firstOrNull() ?: return LensRoute(emptyList(), null)
        val points = listOf(LensPoint(section.startX.toFloat(), section.startY.toFloat())) +
            section.bendPoints.map { LensPoint(it.x.toFloat(), it.y.toFloat()) } +
            LensPoint(section.endX.toFloat(), section.endY.toFloat())
        val label = edge.labels.firstOrNull()?.let { LensPoint((it.x + it.width / 2).toFloat(), (it.y + it.height / 2).toFloat()) }
        return LensRoute(points, label ?: midpoint(points))
    }

    private fun midpoint(points: List<LensPoint>): LensPoint? {
        if (points.size < 2) return null
        val m = points.size / 2
        return if (points.size % 2 == 0) LensPoint((points[m - 1].x + points[m].x) / 2, (points[m - 1].y + points[m].y) / 2) else points[m]
    }

    /** 起点からの幅優先の木。返すのは（親, 子）の並びで、向きは辺の向きによらず外向き。隣は添字の昇順で決定的。 */
    internal fun spanningTree(nodeCount: Int, edges: List<LensEdgeSpec?>, root: Int): List<Pair<Int, Int>> {
        val adjacency = List(nodeCount) { mutableListOf<Int>() }
        edges.forEach { e -> e?.let { adjacency[it.from] += it.to; adjacency[it.to] += it.from } }
        val seen = BooleanArray(nodeCount).also { it[root] = true }
        val tree = mutableListOf<Pair<Int, Int>>()
        var frontier = listOf(root)
        while (frontier.isNotEmpty()) {
            val next = mutableListOf<Int>()
            frontier.forEach { v -> adjacency[v].sorted().forEach { w -> if (!seen[w]) { seen[w] = true; tree += v to w; next += w } } }
            frontier = next
        }
        return tree
    }

    /**
     * 箱の縁から縁への直線。同じ 2 点間に複数の辺（関係の違い・逆向き）があれば、重ならないよう垂直方向に等間隔でずらす。
     * ずらす向きは添字の小さい節点から大きい節点への向きで決め、逆向きの辺も同じ側に並ぶ。
     */
    internal fun straightRoutes(boxes: List<LensBox>, edges: List<LensEdgeSpec?>): List<LensRoute> {
        val offsets = FloatArray(edges.size)
        edges.withIndex().filter { it.value != null }
            .groupBy { (_, e) -> minOf(e!!.from, e.to) to maxOf(e.from, e.to) }
            .values.forEach { group -> group.forEachIndexed { i, (index, _) -> offsets[index] = (i - (group.size - 1) / 2f) * PARALLEL_GAP } }
        return edges.mapIndexed { i, e ->
            if (e == null) LensRoute(emptyList(), null) else {
                val a = boxes[e.from]
                val b = boxes[e.to]
                val dx = b.centerX - a.centerX
                val dy = b.centerY - a.centerY
                val length = hypot(dx, dy).coerceAtLeast(1f)
                val sign = if (e.from < e.to) 1f else -1f
                val ox = -dy / length * offsets[i] * sign
                val oy = dx / length * offsets[i] * sign
                val p = clipToBox(a, a.centerX + ox, a.centerY + oy, b.centerX + ox, b.centerY + oy)
                val q = clipToBox(b, b.centerX + ox, b.centerY + oy, a.centerX + ox, a.centerY + oy)
                LensRoute(listOf(p, q), LensPoint((p.x + q.x) / 2, (p.y + q.y) / 2))
            }
        }
    }

    /** 箱の中の点 (sx, sy) から (tx, ty) へ向かう線が箱の縁と交わる点。 */
    private fun clipToBox(box: LensBox, sx: Float, sy: Float, tx: Float, ty: Float): LensPoint {
        val dx = tx - sx
        val dy = ty - sy
        if (dx == 0f && dy == 0f) return LensPoint(sx, sy)
        val stepX = if (dx != 0f) (box.width / 2) / abs(dx) else Float.MAX_VALUE
        val stepY = if (dy != 0f) (box.height / 2) / abs(dy) else Float.MAX_VALUE
        val s = minOf(stepX, stepY, 1f)
        return LensPoint(sx + dx * s, sy + dy * s)
    }

    /** 座標を 0 以上に寄せて余白を付け、全体の大きさを求める。 */
    private fun normalized(kind: LensLayoutKind, boxes: List<LensBox>, routes: List<LensRoute>): LensArrangement {
        val points = routes.flatMap { it.points } + routes.mapNotNull { it.label }
        val minX = minOf(boxes.minOf { it.x }, points.minOfOrNull { it.x } ?: Float.MAX_VALUE)
        val minY = minOf(boxes.minOf { it.y }, points.minOfOrNull { it.y } ?: Float.MAX_VALUE)
        val shiftX = MARGIN - minX
        val shiftY = MARGIN - minY
        val movedBoxes = boxes.map { it.copy(x = it.x + shiftX, y = it.y + shiftY) }
        val movedRoutes = routes.map { r ->
            LensRoute(r.points.map { LensPoint(it.x + shiftX, it.y + shiftY) }, r.label?.let { LensPoint(it.x + shiftX, it.y + shiftY) })
        }
        val movedPoints = movedRoutes.flatMap { it.points } + movedRoutes.mapNotNull { it.label }
        val width = maxOf(movedBoxes.maxOf { it.x + it.width }, movedPoints.maxOfOrNull { it.x } ?: 0f) + MARGIN
        val height = maxOf(movedBoxes.maxOf { it.y + it.height }, movedPoints.maxOfOrNull { it.y } ?: 0f) + MARGIN
        return LensArrangement(kind, movedBoxes, movedRoutes, width, height)
    }
}
