package dev.toros.yde.desktop.ui.view

import dev.toros.yde.app.view.flow.FlowModel
import dev.toros.yde.desktop.DiagramNodeId
import dev.toros.yde.desktop.DiagramPosition
import dev.toros.yde.desktop.ui.common.LensBox
import dev.toros.yde.desktop.ui.common.LensPoint
import dev.toros.yde.desktop.ui.common.OrthogonalRouting
import dev.toros.yde.desktop.ui.common.PinnedFlow
import dev.toros.yde.semantic.FlowArrangement
import dev.toros.yde.semantic.FlowPoint
import dev.toros.yde.semantic.FlowRect
import dev.toros.yde.semantic.FlowSize
import dev.toros.yde.yaml.model.YPath

/*
 * フロー図の線の経由点（ADR-0028。architecture/diagram-positions.md「線の経由点」）。
 * 線のラベルを動かした位置（ラベルの中心）を、箱と同じ位置の記録に線の key で残し、線は「元の箱 → 経由点 → 先の箱」を
 * 既存の直角配線で 2 回引いてつなぐ。経路そのものは保存しない。
 */

/** 経由点の記録の種類（位置の記録の node.kind）。 */
internal const val FLOW_EDGE_KIND: String = "flow-edge"

/** 経由点を持てる線と、その記録の識別。key の欠落・重複した線と、ポートを指定した線（ADR-0024）は持たない。 */
internal fun flowEdgeIds(model: FlowModel): Map<YPath, DiagramNodeId> {
    val unique = model.edges.groupingBy { it.key }.eachCount()
    return model.edges.mapNotNull { e ->
        e.key?.takeIf { it.isNotBlank() && unique[it] == 1 && e.rendering?.edge?.hasPorts != true && e.from.target != null && e.to.target != null }
            ?.let { e.output to DiagramNodeId(FLOW_EDGE_KIND, it) }
    }.toMap()
}

/** 記録にある経由点（図の座標。density は dip から描画の単位への倍率）。 */
internal fun flowEdgeVias(model: FlowModel, positions: Map<DiagramNodeId, DiagramPosition>, density: Float): Map<YPath, FlowPoint> =
    flowEdgeIds(model).mapNotNull { (path, id) ->
        positions[id]?.takeIf { it.frame == null }?.let { path to FlowPoint(it.x * density, it.y * density) }
    }.toMap()

/** 経由点の記録（図の座標から dip へ。経由点は常に固定）。 */
internal fun flowEdgeVia(id: DiagramNodeId, center: FlowPoint, density: Float): DiagramPosition =
    DiagramPosition(id, null, center.x / density, center.y / density, pinned = true)

/**
 * 経由点を通るように線を引き直す。ラベル（文字の寸法＋余白）を経由点を中心に置き、元の箱からラベル、ラベルから先の箱を
 * それぞれ直角配線で引いて、ラベルの中心でつなぐ（つなぎ目はラベルの下に隠れる）。経由点の無い線はそのまま。
 * 経路を確保できない線は点を空にする（画面は既存の「経路を確保できません」の知らせを出す）。
 */
internal fun withFlowVias(base: FlowArrangement, model: FlowModel, vias: Map<YPath, FlowPoint>, labels: Map<YPath, FlowSize>, direction: String): FlowArrangement {
    if (vias.isEmpty()) return base
    fun FlowRect.box() = LensBox(x, y, width, height)
    val obstacles = base.nodes.values.map { it.box() }
    val edges = model.edges.associateBy { it.output }
    val diamonds = model.nodes.filter { it.displayShape == "diamond" }.map { it.output }.toSet()
    val right = direction == "right"
    val routes = base.edges.map { route ->
        val via = vias[route.output] ?: return@map route
        val edge = edges[route.output] ?: return@map route
        val a = base.nodes[edge.from.target] ?: return@map route
        val b = base.nodes[edge.to.target] ?: return@map route
        val size = labels[route.output] ?: FlowSize(100f, 24f)
        val label = LensBox(via.x - (size.width + 12f) / 2, via.y - (size.height + 12f) / 2, size.width + 12f, size.height + 12f)
        val first = OrthogonalRouting.route(a.box(), label, obstacles, right)
        val second = OrthogonalRouting.route(label, b.box(), obstacles, right)
        val rect = FlowRect(label.x, label.y, label.width, label.height)
        if (first.isEmpty() || second.isEmpty()) return@map route.copy(points = emptyList(), label = rect)
        val joined = (first + LensPoint(via.x, via.y) + second).fold(mutableListOf<LensPoint>()) { acc, p ->
            if (acc.lastOrNull()?.let { it.x == p.x && it.y == p.y } != true) acc += p
            acc
        }
        val outlined = joined.mapIndexed { i, p -> when {
            i == 0 && edge.from.target in diamonds -> PinnedFlow.diamondOutline(a, p)
            i == joined.lastIndex && edge.to.target in diamonds -> PinnedFlow.diamondOutline(b, p)
            else -> p
        } }
        route.copy(points = outlined.map { FlowPoint(it.x, it.y) }, label = rect)
    }
    val points = routes.flatMap { it.points }
    return base.copy(edges = routes, size = FlowSize(
        maxOf(base.size.width, points.maxOfOrNull { it.x + 24f } ?: 0f, routes.maxOfOrNull { it.label.right + 24f } ?: 0f),
        maxOf(base.size.height, points.maxOfOrNull { it.y + 24f } ?: 0f, routes.maxOfOrNull { it.label.bottom + 24f } ?: 0f)))
}
