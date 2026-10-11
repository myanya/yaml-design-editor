package dev.toros.yde.semantic

import dev.toros.yde.yaml.model.YPath
import kotlin.math.max

data class FlowSize(val width: Float, val height: Float)
data class FlowPoint(val x: Float, val y: Float)
data class FlowRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right get() = x + width
    val bottom get() = y + height
    val center get() = FlowPoint(x + width / 2, y + height / 2)
    fun contains(p: FlowPoint) = p.x in x..right && p.y in y..bottom
}
data class FlowRoute(val output: YPath, val points: List<FlowPoint>, val label: FlowRect)
data class FlowArrangement(
    val nodes: Map<YPath, FlowRect>,
    val lanes: Map<YPath, FlowRect>,
    val edges: List<FlowRoute>,
    val size: FlowSize,
)

/** 計測済みの文字寸法だけを受け取る配置。選択状態・Compose・原本の項目名には依存しない。 */
object FlowLayout {
    fun arrange(
        model: FlowModel,
        labels: Map<YPath, FlowSize>,
        direction: String = "down",
    ): FlowArrangement {
        val right = direction == "right"
        fun size(path: YPath) = labels[path] ?: FlowSize(100f, 24f)
        // 主軸をyとして計算し、右向きの場合だけ最後に座標を転置する。
        fun dimensions(n: FlowNode): FlowSize {
            val s = size(n.output)
            val factor = if (n.shape == "diamond") 2f else 1f
            val w = max(140f, (s.width + 32f) * factor)
            val h = max(60f, (s.height + 24f) * factor)
            return if (right) FlowSize(h, w) else FlowSize(w, h)
        }
        val nodes = model.nodes
        val positions = nodes.mapIndexed { i, n -> n.output to i }.toMap()
        val adjacency = List(nodes.size) { mutableListOf<Int>() }
        model.edges.forEach { e ->
            val a = positions[e.from.target]
            val b = positions[e.to.target]
            if (a != null && b != null) adjacency[a] += b
        }
        // Kosarajuを反復走査で行い、長い業務フローでも再帰スタックを使わない。
        val seen = BooleanArray(nodes.size)
        val order = mutableListOf<Int>()
        nodes.indices.forEach { start ->
            if (!seen[start]) {
                val stack = ArrayDeque<Pair<Int, Boolean>>()
                stack.add(start to false)
                while (stack.isNotEmpty()) {
                    val (v, finished) = stack.removeLast()
                    if (finished) order += v
                    else if (!seen[v]) {
                        seen[v] = true
                        stack.add(v to true)
                        adjacency[v].asReversed().forEach { if (!seen[it]) stack.add(it to false) }
                    }
                }
            }
        }
        val reverse = List(nodes.size) { mutableListOf<Int>() }
        adjacency.forEachIndexed { a, bs -> bs.forEach { reverse[it] += a } }
        val component = IntArray(nodes.size) { -1 }
        var count = 0
        order.asReversed().forEach { start ->
            if (component[start] < 0) {
                val stack = ArrayDeque<Int>()
                stack.add(start)
                component[start] = count
                while (stack.isNotEmpty()) {
                    reverse[stack.removeLast()].forEach {
                        if (component[it] < 0) { component[it] = count; stack.add(it) }
                    }
                }
                count++
            }
        }
        val links = List(count) { linkedSetOf<Int>() }
        adjacency.forEachIndexed { a, bs -> bs.forEach { b ->
            if (component[a] != component[b]) links[component[a]] += component[b]
        } }
        val degree = IntArray(count)
        links.forEach { it.forEach { b -> degree[b]++ } }
        val ranks = IntArray(count)
        val queue = ArrayDeque<Int>()
        degree.indices.filter { degree[it] == 0 }.forEach(queue::add)
        while (queue.isNotEmpty()) {
            val a = queue.removeFirst()
            links[a].forEach { b ->
                ranks[b] = max(ranks[b], ranks[a] + 1)
                if (--degree[b] == 0) queue.add(b)
            }
        }
        val lanePaths = model.lanes.map { it.output }
        fun lane(n: FlowNode) = lanePaths.indexOf(n.lane?.target).let { if (it < 0) lanePaths.size else it }
        val sizes = nodes.map(::dimensions)
        val rowCount = (ranks.maxOrNull() ?: 0) + 1
        val rowHeight = FloatArray(rowCount) { 60f }
        val laneWidth = FloatArray(lanePaths.size + 1) { 200f }
        nodes.indices.groupBy { lane(nodes[it]) to ranks[component[it]] }.forEach { (group, members) ->
            laneWidth[group.first] = max(laneWidth[group.first], members.sumOf { (sizes[it].width + 40f).toDouble() }.toFloat())
            members.forEach { rowHeight[group.second] = max(rowHeight[group.second], sizes[it].height) }
        }
        model.lanes.forEachIndexed { i, l -> laneWidth[i] = max(laneWidth[i], size(l.output).width + 32f) }
        val rowY = FloatArray(rowCount)
        var totalHeight = 70f
        val gaps = FloatArray(rowCount) { 70f }
        model.edges.forEach { e -> positions[e.from.target]?.let { a ->
            val s = size(e.output)
            gaps[ranks[component[a]]] += (if (right) s.width else s.height) + 24f
        } }
        rowHeight.forEachIndexed { i, h -> rowY[i] = totalHeight; totalHeight += h + gaps[i] }
        val laneX = FloatArray(laneWidth.size)
        var totalWidth = 24f
        laneWidth.forEachIndexed { i, w -> laneX[i] = totalWidth; totalWidth += w }
        val nextX = mutableMapOf<Pair<Int, Int>, Float>()
        val boxes = linkedMapOf<YPath, FlowRect>()
        nodes.forEachIndexed { i, n ->
            val group = lane(n) to ranks[component[i]]
            val x = nextX[group] ?: (laneX[group.first] + 20f)
            boxes[n.output] = FlowRect(x, rowY[group.second], sizes[i].width, sizes[i].height)
            nextX[group] = x + sizes[i].width + 40f
        }
        // 各辺に独立した外周の経路とラベル領域を確保する。未解決の辺も一覧領域に残す。
        val usedGap = FloatArray(rowCount)
        val routes = model.edges.mapIndexed { i, e ->
            val a = boxes[e.from.target]
            val b = boxes[e.to.target]
            val s = size(e.output)
            val labelW = if (right) s.height + 12f else s.width + 12f
            val labelH = if (right) s.width + 12f else s.height + 12f
            val ai = positions[e.from.target]
            val bi = positions[e.to.target]
            if (a != null && b != null && ai != null && bi != null &&
                ranks[component[bi]] == ranks[component[ai]] + 1) {
                val rank = ranks[component[ai]]
                val y = rowY[rank] + rowHeight[rank] + 16f + usedGap[rank]
                usedGap[rank] += labelH + 16f
                val label = FlowRect(max(a.center.x, b.center.x) + 8f, y, labelW, labelH)
                val mid = y + labelH / 2
                return@mapIndexed FlowRoute(e.output, listOf(FlowPoint(a.center.x, a.bottom),
                    FlowPoint(a.center.x, mid), FlowPoint(b.center.x, mid), FlowPoint(b.center.x, b.y)), label)
            }
            val rail = totalWidth + 24f
            val labelY = totalHeight + i * 8f
            val rect = FlowRect(rail + 8f, labelY, labelW, labelH)
            val points = if (a == null || b == null) emptyList() else {
                val exit = FlowPoint(a.right, a.y + a.height * 0.7f)
                val enter = FlowPoint(b.right, b.y + b.height * 0.3f)
                listOf(exit, FlowPoint(rail, exit.y), FlowPoint(rail, labelY + labelH / 2),
                    FlowPoint(rail + labelW + 24f, labelY + labelH / 2),
                    FlowPoint(rail + labelW + 24f, enter.y), enter)
            }
            totalWidth += labelW + 64f
            FlowRoute(e.output, points, rect)
        }
        val laneBoxes = model.lanes.associate { l ->
            val i = lanePaths.indexOf(l.output)
            l.output to FlowRect(laneX[i], 20f, laneWidth[i], totalHeight - 20f)
        }
        fun transpose(r: FlowRect) = if (right) FlowRect(r.y, r.x, r.height, r.width) else r
        fun transpose(p: FlowPoint) = if (right) FlowPoint(p.y, p.x) else p
        val height = max(totalHeight + 40f, routes.maxOfOrNull { it.label.bottom + 24f } ?: 0f)
        totalWidth = max(totalWidth, routes.maxOfOrNull { it.label.right + 24f } ?: 0f)
        return FlowArrangement(boxes.mapValues { transpose(it.value) }, laneBoxes.mapValues { transpose(it.value) },
            routes.map { it.copy(points = it.points.map(::transpose), label = transpose(it.label)) },
            if (right) FlowSize(height, totalWidth + 24f) else FlowSize(totalWidth + 24f, height))
    }
}
