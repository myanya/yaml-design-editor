package dev.toros.yde.desktop.ui.common

import dev.toros.yde.app.view.flow.*
import dev.toros.yde.semantic.*
import dev.toros.yde.yaml.model.YPath

/** レーンの内容領域を基準とする固定位置を、図の座標へ組み立てる。 */
internal object PinnedFlow {
    fun inset(labels: Map<YPath, FlowSize>, lane: YPath, mode: FlowLayoutMode, direction: String, lanes: List<YPath>): FlowPoint =
        if (mode == FlowLayoutMode.SWIMLANES) {
            if (direction == "right") FlowPoint(maxOf(40f, lanes.maxOfOrNull { (labels[it]?.width ?: 100f) + 24f } ?: 40f), 24f)
            else FlowPoint(24f, maxOf(40f, lanes.maxOfOrNull { (labels[it]?.height ?: 24f) + 24f } ?: 40f))
        } else FlowPoint(24f, (labels[lane]?.height ?: 24f) + 24f)

    fun arrange(model: FlowModel, labels: Map<YPath, FlowSize>, direction: String, mode: FlowLayoutMode,
                nodeSizes: Map<YPath, FlowSize>, pins: Map<YPath, FlowPoint>, lanePositions: Map<YPath, FlowPoint> = emptyMap()): FlowArrangement {
        val right = direction == "right"
        val lanes = if (mode == FlowLayoutMode.PLAIN) emptyList() else model.lanes
        val laneOf = model.nodes.associate { n -> n.output to n.lane?.target?.takeIf { target -> lanes.any { it.output == target } } }
        val sizes = model.nodes.associate { n ->
            val text = labels[n.output] ?: FlowSize(100f, 24f)
            val factor = if (n.displayShape == "diamond") 2f else 1f
            n.output to (nodeSizes[n.output] ?: FlowSize(maxOf(140f, (text.width + 32f) * factor), maxOf(60f, (text.height + 24f) * factor)))
        }
        fun local(members: List<FlowNode>, inside: Boolean, limit: Float? = null, crossStart: Float? = null): Map<YPath, LensBox> {
            val indices = members.mapIndexed { i, n -> n.output to i }.toMap()
            val edges = model.edges.mapNotNull { e -> indices[e.from.target]?.let { f -> indices[e.to.target]?.let { t -> LensEdgeSpec(f, t) } } }
            val fixed = pins.mapNotNull { (path, p) -> indices[path]?.let { it to LensPoint(p.x, p.y) } }.toMap()
            val boxes = PinnedLayout.arrange(members.map { n -> sizes.getValue(n.output).let { LensNodeSize(n.key.orEmpty(), it.width, it.height) } },
                edges, fixed, right, inside, limit, crossStart)
            return members.mapIndexed { i, n -> n.output to boxes[i] }.toMap()
        }
        val insets = lanes.associate { it.output to inset(labels, it.output, mode, direction, lanes.map { l -> l.output }) }
        val inner = lanes.associate { lane ->
            val members = model.nodes.filter { laneOf[it.output] == lane.output }
            val cross = members.maxOfOrNull { n ->
                val s = sizes.getValue(n.output); val p = pins[n.output]
                if (right) (p?.y ?: 24f) + s.height else (p?.x ?: 24f) + s.width
            }?.plus(48f)?.coerceAtLeast(260f) ?: 260f
            lane.output to local(members, true, cross.takeIf { mode == FlowLayoutMode.SWIMLANES })
        }
        val laneSizes = lanes.associate { lane ->
            val boxes = inner.getValue(lane.output).values; val inset = insets.getValue(lane.output)
            lane.output to FlowSize(maxOf((boxes.maxOfOrNull { it.x + it.width } ?: 180f) + inset.x + 24f,
                (labels[lane.output]?.width ?: 0f) + 48f),
                (boxes.maxOfOrNull { it.y + it.height } ?: 80f) + inset.y + 24f)
        }
        val laneBoxes = linkedMapOf<YPath, FlowRect>()
        val nodeBoxes = linkedMapOf<YPath, FlowRect>()
        val unassigned = model.nodes.filter { laneOf[it.output] == null }
        if (mode == FlowLayoutMode.SWIMLANES && lanes.isNotEmpty()) {
            val main = laneSizes.values.maxOf { if (right) it.width else it.height }
            var cross = maxOf(24f, lanes.maxOfOrNull { lane -> lanePositions[lane.output]?.let { p ->
                val size = laneSizes.getValue(lane.output)
                (if (right) p.y + size.height else p.x + size.width) + 16f
            } ?: 24f } ?: 24f)
            lanes.forEach { lane ->
                val size = laneSizes.getValue(lane.output)
                val saved = lanePositions[lane.output]
                laneBoxes[lane.output] = if (right) FlowRect(saved?.x ?: 24f, saved?.y ?: cross, main, size.height)
                    else FlowRect(saved?.x ?: cross, saved?.y ?: 24f, size.width, main)
                if (saved == null) cross += (if (right) size.height else size.width) + 16f
            }
            // 未割当は帯の外に置く。保存位置も同じ領域で扱う。
            val outside = local(unassigned, false, crossStart = cross)
            outside.forEach { (path, b) ->
                nodeBoxes[path] = FlowRect(b.x, b.y, b.width, b.height)
            }
        } else {
            val roots = lanes.map { l -> laneSizes.getValue(l.output).let { LensNodeSize(l.key.orEmpty(), it.width, it.height) } } +
                unassigned.map { n -> sizes.getValue(n.output).let { LensNodeSize(n.key.orEmpty(), it.width, it.height) } }
            fun rootOf(path: YPath?): Int? = if (path == null) null else laneOf[path]?.let { p -> lanes.indexOfFirst { it.output == p } }
                ?: unassigned.indexOfFirst { it.output == path }.takeIf { it >= 0 }?.plus(lanes.size)
            val edges = model.edges.mapNotNull { e -> rootOf(e.from.target)?.let { f -> rootOf(e.to.target)?.let { t -> LensEdgeSpec(f, t) } } }
            val fixed = (lanes.mapIndexedNotNull { i, n -> lanePositions[n.output]?.let { i to LensPoint(it.x, it.y) } } +
                unassigned.mapIndexedNotNull { i, n -> pins[n.output]?.let { i + lanes.size to LensPoint(it.x, it.y) } }).toMap()
            val placed = PinnedLayout.arrange(roots, edges, fixed, right)
            lanes.forEachIndexed { i, lane -> val b = placed[i]; laneBoxes[lane.output] = FlowRect(b.x, b.y, b.width, b.height) }
            unassigned.forEachIndexed { i, n -> val b = placed[i + lanes.size]; nodeBoxes[n.output] = FlowRect(b.x, b.y, b.width, b.height) }
        }
        lanes.forEach { lane ->
            val box = laneBoxes.getValue(lane.output); val inset = insets.getValue(lane.output)
            inner.getValue(lane.output).forEach { (path, b) -> nodeBoxes[path] = FlowRect(box.x + inset.x + b.x, box.y + inset.y + b.y, b.width, b.height) }
        }
        val result = routed(model, labels, nodeBoxes, laneBoxes, direction)
        return if (mode != FlowLayoutMode.SWIMLANES) result else result.copy(laneHeaders = laneBoxes.mapValues { (path, r) ->
            val p = insets.getValue(path)
            if (right) FlowRect(r.x, r.y, p.x, r.height) else FlowRect(r.x, r.y, r.width, p.y)
        })
    }

    /** ドラッグの途中にも使う。ノードを動かさずに経路とラベルだけを求める。 */
    fun routed(model: FlowModel, labels: Map<YPath, FlowSize>, boxes: Map<YPath, FlowRect>, lanes: Map<YPath, FlowRect>,
               direction: String): FlowArrangement {
        val obstacles = boxes.values.map { LensBox(it.x, it.y, it.width, it.height) }
        val diamonds = model.nodes.filter { it.displayShape == "diamond" }.map { it.output }.toSet()
        fun outline(path: YPath?, p: LensPoint): LensPoint {
            val r = boxes[path]?.takeIf { path in diamonds } ?: return p
            return if (direction == "right") LensPoint(r.center.x + (if (p.x < r.center.x) -1 else 1) *
                r.width / 2 * (1 - kotlin.math.abs(p.y - r.center.y) / (r.height / 2)), p.y)
            else LensPoint(p.x, r.center.y + (if (p.y < r.center.y) -1 else 1) *
                r.height / 2 * (1 - kotlin.math.abs(p.x - r.center.x) / (r.width / 2)))
        }
        val usedLabels = mutableListOf<LensBox>()
        val routes = model.edges.mapIndexed { i, e ->
            val a = boxes[e.from.target]; val b = boxes[e.to.target]
            val points = if (a == null || b == null) emptyList() else PinnedLayout.route(
                LensBox(a.x, a.y, a.width, a.height), LensBox(b.x, b.y, b.width, b.height), obstacles, direction == "right", i)
            val preferred = points.getOrNull(points.size / 2) ?: LensPoint(24f, (obstacles.maxOfOrNull { it.y + it.height } ?: 0f) + 40f)
            val size = labels[e.output] ?: FlowSize(100f, 24f)
            val label = PinnedLayout.vacant(LensNodeSize("", size.width + 12f, size.height + 12f), preferred, obstacles + usedLabels)
            usedLabels += label
            val outlined = points.mapIndexed { index, p ->
                val point = when (index) { 0 -> outline(e.from.target, p); points.lastIndex -> outline(e.to.target, p); else -> p }
                FlowPoint(point.x, point.y)
            }
            FlowRoute(e.output, outlined, FlowRect(label.x, label.y, label.width, label.height))
        }
        val rects = boxes.values + lanes.values + routes.map { it.label }
        val points = routes.flatMap { it.points }
        val left = minOf(0f, rects.minOfOrNull { it.x } ?: 0f, points.minOfOrNull { it.x } ?: 0f)
        val top = minOf(0f, rects.minOfOrNull { it.y } ?: 0f, points.minOfOrNull { it.y } ?: 0f)
        return FlowArrangement(boxes, lanes, routes, FlowSize(
            maxOf(0f, rects.maxOfOrNull { it.right } ?: 0f, points.maxOfOrNull { it.x } ?: 0f) - left + 24f,
            maxOf(0f, rects.maxOfOrNull { it.bottom } ?: 0f, points.maxOfOrNull { it.y } ?: 0f) - top + 24f))
    }
}
