package dev.toros.yde.desktop.ui.graph

import dev.toros.yde.desktop.ui.common.*
import dev.toros.yde.semantic.DiagramEdgeStyle
import dev.toros.yde.semantic.DiagramLinkSource

/** 畳んだ祖先へ移った端だけは箱につなぐ。入力の端点を処理してから表示の反転を当てる。 */
internal fun lensEndpointStyle(style: DiagramEdgeStyle, edge: LensGraphEdge, sources: List<DiagramLinkSource>,
    fromId: String, toId: String): DiagramEdgeStyle {
    val source = sources.singleOrNull()
    val attached = if (source == null) style else style.copy(
        sourcePort = style.sourcePort.takeIf { source.sourceId == if (edge.reversed) toId else fromId },
        targetPort = style.targetPort.takeIf { source.targetId == if (edge.reversed) fromId else toId })
    return if (edge.reversed) attached.reversedEndpoints() else attached
}

/** 自動配置・固定・ドラッグの後で、表示部品の接続点へ配線する。画面とPNGの両方がこの結果を使う。 */
internal fun withLensPorts(base: LensArranged): LensArranged {
    if (base.edges.none { it.visual?.style?.hasPorts == true }) return base
    val boxes = base.result.boxes
    val ports = base.nodes.map { n -> base.bodies[n.node.key]?.portPositions(
        if (n.expanded || n.hiddenChildren > 0) TOGGLE_ZONE else 0f).orEmpty() }
    fun ancestors(index: Int): Set<Int> {
        val result = mutableSetOf<Int>()
        var parent = base.nested[index].parent
        while (parent != null && result.add(parent)) parent = base.nested[parent].parent
        return result
    }
    fun diamond(index: Int): Boolean {
        val n = base.nodes[index]
        return !n.expanded && n.hiddenChildren == 0 && base.bodies[n.node.key]?.rendering?.node?.let { it.kind == "box" && it.shape == "diamond" } == true
    }
    val occupied = base.result.routes.mapIndexedNotNull { i, route ->
        val visual = base.edges[i].visual ?: return@mapIndexedNotNull null
        val text = visual.texts[visual.style.label] ?: return@mapIndexedNotNull null
        val label = route.label ?: return@mapIndexedNotNull null
        if (visual.style.hasPorts) null else LensBox(label.x - text.size.width / 2 - 3f, label.y - text.size.height / 2,
            text.size.width + 6f, text.size.height.toFloat())
    }.toMutableList()
    val routes = base.result.routes.mapIndexed { i, route ->
        val edge = base.edges[i]
        val style = edge.visual?.style?.takeIf { it.hasPorts } ?: return@mapIndexed route
        val from = boxes[edge.from]; val to = boxes[edge.to]
        // 端点を含む親の枠は外へ通り抜けられる。無関係の箱は避ける。
        val parents = ancestors(edge.from) + ancestors(edge.to)
        val obstacles = boxes.filterIndexed { j, _ -> j !in parents }
        val points = routeDiagramPorts(from, to, style, ports[edge.from], ports[edge.to], obstacles).mapIndexed { j, point ->
            when (j) {
                0 -> diagramPortOutline(from, point, diamond(edge.from))
                else -> point
            }
        }.toMutableList()
        if (points.isNotEmpty()) points[points.lastIndex] = diagramPortOutline(to, points.last(), diamond(edge.to))
        val text = edge.visual.texts[style.label]
        val label = text?.let {
            OrthogonalRouting.label(points, LensNodeSize("", it.size.width + 6f, it.size.height.toFloat()), obstacles + occupied,
                route.label ?: LensPoint((from.centerX + to.centerX) / 2, (from.centerY + to.centerY) / 2))
        }
        if (label != null) occupied += label
        LensRoute(points, label?.let { LensPoint(it.centerX, it.centerY) })
    }
    val points = routes.flatMap { it.points }
    return base.copy(result = base.result.copy(routes = routes,
        width = maxOf(base.result.width, points.maxOfOrNull { it.x + 24f } ?: 0f),
        height = maxOf(base.result.height, points.maxOfOrNull { it.y + 24f } ?: 0f)))
}
