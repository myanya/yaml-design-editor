package dev.toros.yde.desktop.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.toros.yde.semantic.DiagramEdgeStyle

/** 部品の中心はノード内の座標。接続面へ投影するまでは端点の位置を固定しない。 */
internal data class DiagramPortPosition(val x: Float, val y: Float, val side: String)

internal fun DiagramBody.portPositions(offsetX: Float = 0f): Map<String, DiagramPortPosition> {
    val found = mutableListOf<Pair<String, DiagramPortPosition>>()
    fun walk(node: DiagramMeasured, at: Offset, clip: Rect) {
        val bounds = Rect(at, node.size).intersect(clip)
        val center = at + Offset(node.size.width / 2, node.size.height / 2)
        node.part.port?.let { key -> if (bounds.contains(center))
            found += key to DiagramPortPosition(center.x + offsetX, center.y, node.part.portSide) }
        node.children.forEach { (relative, child) -> walk(child, at + relative, bounds) }
    }
    walk(measured, Offset.Zero, Rect(Offset.Zero, measured.size))
    val counts = dev.toros.yde.semantic.DiagramPorts.parts(rendering.node).mapNotNull { it.port }.groupingBy { it }.eachCount()
    return found.filter { counts[it.first] == 1 }.toMap()
}

/** 指定したポートが無ければ線を出さない。未指定の端だけは従来の面選択を使う。 */
internal fun routeDiagramPorts(
    from: LensBox, to: LensBox, style: DiagramEdgeStyle,
    source: Map<String, DiagramPortPosition>, target: Map<String, DiagramPortPosition>,
    obstacles: List<LensBox>, ordinal: Int = 0,
): List<LensPoint> {
    if (!style.portsValid) return emptyList()
    val a = style.sourcePort?.let { source[it] ?: return emptyList() }
    val b = style.targetPort?.let { target[it] ?: return emptyList() }
    val defaults = OrthogonalRouting.preferred(from, to, right = true, ordinal = ordinal)
    fun port(position: DiagramPortPosition?, box: LensBox, other: LensBox, default: OrthogonalRouting.Port): OrthogonalRouting.Port {
        if (position == null) return default
        val side = when (position.side) {
            "left" -> OrthogonalRouting.Side.WEST
            "right" -> OrthogonalRouting.Side.EAST
            "top" -> OrthogonalRouting.Side.NORTH
            "bottom" -> OrthogonalRouting.Side.SOUTH
            else -> if (other.centerX >= box.centerX) OrthogonalRouting.Side.EAST else OrthogonalRouting.Side.WEST
        }
        val fraction = if (side.horizontal) position.y / box.height else position.x / box.width
        return OrthogonalRouting.Port(side, fraction.coerceIn(0f, 1f))
    }
    return OrthogonalRouting.route(from, to, obstacles, right = true, ordinal = ordinal,
        hint = OrthogonalRouting.Ports(port(a, from, to, defaults.from), port(b, to, from, defaults.to)), fixedPorts = true)
}

/** 接続面からの線の向きを維持し、菱形のポートを輪郭まで延ばす。 */
internal fun diagramPortOutline(box: LensBox, point: LensPoint, diamond: Boolean): LensPoint {
    if (!diamond) return point
    val dx = point.x - box.centerX; val dy = point.y - box.centerY
    return if (kotlin.math.abs(kotlin.math.abs(dx) - box.width / 2) < .1f)
        LensPoint(box.centerX + (if (dx < 0) -1 else 1) * box.width / 2 * (1 - kotlin.math.abs(dy) / (box.height / 2)), point.y)
    else LensPoint(point.x, box.centerY + (if (dy < 0) -1 else 1) * box.height / 2 * (1 - kotlin.math.abs(dx) / (box.width / 2)))
}
