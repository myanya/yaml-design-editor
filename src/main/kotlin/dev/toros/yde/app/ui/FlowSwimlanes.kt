package dev.toros.yde.app.ui

import dev.toros.yde.semantic.*
import dev.toros.yde.yaml.model.YPath

/** ELKの段を使い、レーンの帯を固定する。座標は主軸をyとして計算し、右向きだけ最後に転置する。 */
internal object FlowSwimlanes {
    fun arrange(model: FlowModel, labels: Map<YPath, FlowSize>, seed: FlowArrangement, direction: String): FlowArrangement {
        val right = direction == "right"
        fun text(p: YPath) = labels[p] ?: FlowSize(100f, 24f)
        fun transpose(r: FlowRect) = if (right) FlowRect(r.y, r.x, r.height, r.width) else r
        fun transpose(p: FlowPoint) = if (right) FlowPoint(p.y, p.x) else p
        fun labelSize(p: YPath): FlowSize {
            val s = text(p)
            return if (right) FlowSize(s.height + 12f, s.width + 12f) else FlowSize(s.width + 12f, s.height + 12f)
        }
        val base = seed.nodes.mapValues { transpose(it.value) }
        // 同じ段でも菱形と四角では中心が異なる。主軸上で重なる区間を同じ段として扱う。
        val rank = mutableMapOf<YPath, Int>()
        var rankCount = 0
        var layerEnd = Float.NEGATIVE_INFINITY
        base.entries.sortedBy { it.value.y }.forEach { (path, box) ->
            if (box.y >= layerEnd) { rankCount++; layerEnd = box.bottom }
            else layerEnd = maxOf(layerEnd, box.bottom)
            rank[path] = rankCount - 1
        }
        val lanePaths = model.lanes.map { it.output }
        val laneOf = model.nodes.associate { n -> n.output to lanePaths.indexOf(n.lane?.target).let {
            if (it < 0) lanePaths.size else it
        } }
        val bandCount = lanePaths.size + if (laneOf.values.any { it == lanePaths.size }) 1 else 0
        val valid = model.edges.filter { it.from.target in base && it.to.target in base }
        val outgoing = valid.groupBy { it.from.target }
        val incoming = valid.groupBy { it.to.target }
        val shapes = model.nodes.associate { it.output to it.shape }
        fun adjacent(e: FlowEdge) = rank.getValue(e.to.target!!) == rank.getValue(e.from.target!!) + 1
        val longEdges = valid.filterNot(::adjacent)
        val rails = longEdges.groupBy { laneOf.getValue(it.from.target!!) }
        val groups = model.nodes.groupBy { laneOf.getValue(it.output) to rank.getValue(it.output) }
        val bodyWidths = FloatArray(bandCount) { lane ->
            val nodesWidth = groups.filterKeys { it.first == lane }.values.maxOfOrNull { nodes ->
                nodes.sumOf { base.getValue(it.output).width.toDouble() }.toFloat() + (nodes.size - 1) * 32f
            } ?: 140f
            val labelWidth = valid.filter { laneOf[it.from.target] == lane }.maxOfOrNull { labelSize(it.output).width } ?: 0f
            val headerWidth = model.lanes.getOrNull(lane)?.let { if (right) text(it.output).height else text(it.output).width } ?: 0f
            maxOf(nodesWidth + 48f, labelWidth + 32f, headerWidth + 24f)
        }
        val widths = FloatArray(bandCount) { bodyWidths[it] + (rails[it]?.size ?: 0) * 14f + 16f }
        val bandX = FloatArray(bandCount)
        var crossEnd = 24f
        widths.forEachIndexed { i, w -> bandX[i] = crossEnd; crossEnd += w }
        val header = maxOf(40f, model.lanes.maxOfOrNull { if (right) text(it.output).width + 24f else text(it.output).height + 24f } ?: 0f)
        val heights = FloatArray(rankCount) { r -> base.filterKeys { rank[it] == r }.values.maxOf { it.height } }

        // 段間に条件ラベルの寸法ぶんの経路を確保する。長い辺はノードのないレーン境界を通す。
        val gapSizes = FloatArray(rankCount + 1) { 24f }
        val exits = mutableMapOf<YPath, Float>()
        val entries = mutableMapOf<YPath, Float>()
        valid.forEach { e ->
            val gap = rank.getValue(e.from.target!!) + 1
            val size = labelSize(e.output)
            exits[e.output] = gapSizes[gap] + size.height / 2
            gapSizes[gap] += size.height + 16f
            if (!adjacent(e)) {
                val targetGap = rank.getValue(e.to.target!!)
                entries[e.output] = gapSizes[targetGap] + 6f
                gapSizes[targetGap] += 16f
            }
        }
        val gapY = FloatArray(gapSizes.size)
        val rowY = FloatArray(rankCount)
        var mainEnd = 24f + header
        gapSizes.forEachIndexed { r, gap ->
            gapY[r] = mainEnd; mainEnd += gap
            if (r < heights.size) { rowY[r] = mainEnd; mainEnd += heights[r] }
        }
        mainEnd += 24f
        val boxes = linkedMapOf<YPath, FlowRect>()
        groups.forEach { (group, nodes) ->
            val (lane, r) = group
            val width = nodes.sumOf { base.getValue(it.output).width.toDouble() }.toFloat() + (nodes.size - 1) * 32f
            var x = bandX[lane] + (bodyWidths[lane] - width) / 2
            nodes.forEach { n ->
                val size = base.getValue(n.output)
                boxes[n.output] = FlowRect(x, rowY[r] + (heights[r] - size.height) / 2, size.width, size.height)
                x += size.width + 32f
            }
        }
        var unresolvedY = mainEnd + 24f
        fun port(e: FlowEdge, box: FlowRect, source: Boolean): FlowPoint {
            val path = if (source) e.from.target else e.to.target
            val edges = (if (source) outgoing else incoming).getValue(path)
            val x = box.x + box.width * (0.2f + 0.6f * (edges.indexOf(e) + 1) / (edges.size + 1))
            val inset = if (shapes[path] == "diamond") kotlin.math.abs(x - box.center.x) * box.height / box.width else 0f
            return FlowPoint(x, if (source) box.bottom - inset else box.y + inset)
        }
        val routes = model.edges.map { e ->
            val s = labelSize(e.output)
            val a = boxes[e.from.target]; val b = boxes[e.to.target]
            if (a == null || b == null) {
                val rect = FlowRect(24f, unresolvedY, s.width, s.height)
                unresolvedY = rect.bottom + 16f
                FlowRoute(e.output, emptyList(), rect)
            } else {
                val lane = laneOf.getValue(e.from.target!!)
                val y = gapY[rank.getValue(e.from.target!!) + 1] + exits.getValue(e.output)
                val start = port(e, a, true)
                val end = port(e, b, false)
                val points = if (adjacent(e)) listOf(start, FlowPoint(start.x, y), FlowPoint(end.x, y), end)
                else {
                    val rail = bandX[lane] + bodyWidths[lane] + 8f + rails.getValue(lane).indexOf(e) * 14f
                    val entry = gapY[rank.getValue(e.to.target!!)] + entries.getValue(e.output)
                    listOf(start, FlowPoint(start.x, y), FlowPoint(rail, y), FlowPoint(rail, entry), FlowPoint(end.x, entry), end)
                }
                val x = (start.x + 8f).coerceAtMost(bandX[lane] + bodyWidths[lane] - s.width - 8f)
                FlowRoute(e.output, points.filterIndexed { i, p -> i == 0 || p != points[i - 1] },
                    FlowRect(x, y - s.height / 2, s.width, s.height))
            }
        }
        val lanes = model.lanes.mapIndexed { i, l -> l.output to FlowRect(bandX[i], 24f, widths[i], mainEnd - 24f) }.toMap()
        val headers = model.lanes.mapIndexed { i, l ->
            val area = transpose(FlowRect(bandX[i], 24f, widths[i], header))
            l.output to FlowRect(area.x + 8f, area.y + 8f, text(l.output).width, text(l.output).height)
        }.toMap()
        val finalNodes = boxes.mapValues { transpose(it.value) }
        val finalLanes = lanes.mapValues { transpose(it.value) }
        val finalRoutes = routes.map { it.copy(points = it.points.map(::transpose), label = transpose(it.label)) }
        val rects = finalNodes.values + finalLanes.values + finalRoutes.map { it.label }
        val points = finalRoutes.flatMap { it.points }
        return FlowArrangement(finalNodes, finalLanes, finalRoutes, FlowSize(
            maxOf(rects.maxOfOrNull { it.right } ?: 0f, points.maxOfOrNull { it.x } ?: 0f) + 24f,
            maxOf(rects.maxOfOrNull { it.bottom } ?: 0f, points.maxOfOrNull { it.y } ?: 0f) + 24f), headers)
    }
}
