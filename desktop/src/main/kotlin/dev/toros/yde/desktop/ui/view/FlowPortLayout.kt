package dev.toros.yde.desktop.ui.view

import dev.toros.yde.app.view.flow.FlowModel
import dev.toros.yde.desktop.ui.common.*
import dev.toros.yde.semantic.*
import dev.toros.yde.yaml.model.YPath

/** 全配置モード・移動プレビューで同じ接続点を使い、ノード位置は変えない。 */
internal fun withFlowPorts(base: FlowArrangement, model: FlowModel, bodies: Map<YPath, DiagramBody>): FlowArrangement {
    if (model.edges.none { it.rendering?.edge?.hasPorts == true }) return base
    fun FlowRect.box() = LensBox(x, y, width, height)
    val boxes = base.nodes.mapValues { it.value.box() }
    val ports = bodies.mapValues { it.value.portPositions() }
    val edges = model.edges.associateBy { it.output }
    val diamonds = model.nodes.filter { it.displayShape == "diamond" }.map { it.output }.toSet()
    val routes = base.edges.map { route ->
        val edge = edges[route.output] ?: return@map route
        val style = edge.rendering?.edge?.takeIf { it.hasPorts } ?: return@map route
        val from = boxes[edge.from.target]; val to = boxes[edge.to.target]
        val points = if (from == null || to == null) emptyList() else routeDiagramPorts(from, to, style,
            ports[edge.from.target].orEmpty(), ports[edge.to.target].orEmpty(), boxes.values.toList())
        val outlined = points.mapIndexed { i, point -> when (i) {
            0 -> diagramPortOutline(from!!, point, edge.from.target in diamonds)
            points.lastIndex -> diagramPortOutline(to!!, point, edge.to.target in diamonds)
            else -> point
        } }
        // ラベルは配線を確定してから、端ラベルと実際の文字寸法を含めて FlowLabelLayout で配置する。
        route.copy(points = outlined.map { FlowPoint(it.x, it.y) })
    }
    val points = routes.flatMap { it.points }
    return base.copy(edges = routes, size = FlowSize(
        maxOf(base.size.width, points.maxOfOrNull { it.x + 24f } ?: 0f, routes.maxOfOrNull { it.label.right + 24f } ?: 0f),
        maxOf(base.size.height, points.maxOfOrNull { it.y + 24f } ?: 0f, routes.maxOfOrNull { it.label.bottom + 24f } ?: 0f)))
}
