package dev.toros.yde.desktop.ui.view

import dev.toros.yde.app.view.flow.*
import dev.toros.yde.desktop.DiagramNodeId
import dev.toros.yde.desktop.DiagramPosition
import dev.toros.yde.desktop.ui.common.PinnedFlow
import dev.toros.yde.semantic.*
import dev.toros.yde.yaml.model.YPath

internal fun flowPositionIds(model: FlowModel): Map<YPath, DiagramNodeId> {
    val unique = model.nodes.groupingBy { it.key }.eachCount()
    return model.nodes.mapNotNull { n -> n.key?.takeIf { it.isNotBlank() && unique[it] == 1 }
        ?.let { n.output to DiagramNodeId("flow", it) } }.toMap()
}

internal fun flowLaneIds(model: FlowModel): Map<YPath, DiagramNodeId> {
    val unique = model.lanes.groupingBy { it.key }.eachCount()
    return model.lanes.mapNotNull { n -> n.key?.takeIf { it.isNotBlank() && unique[it] == 1 }
        ?.let { n.output to DiagramNodeId("lane", it) } }.toMap()
}

internal fun flowPositionFrames(model: FlowModel, mode: FlowLayoutMode): Map<YPath, DiagramNodeId?> {
    val unique = model.lanes.groupingBy { it.key }.eachCount()
    return model.nodes.mapNotNull { n ->
        val lane = if (mode == FlowLayoutMode.PLAIN) null else model.lanes.firstOrNull { it.output == n.lane?.target }
        val key = lane?.key?.takeIf { it.isNotBlank() && unique[it] == 1 }
        if (lane != null && key == null) null else n.output to key?.let { DiagramNodeId("lane", it) }
    }.toMap()
}

/** レーンの原点も記録して、子の相対位置と図の中の位置を両方再現する。 */
internal fun flowPositionSnapshot(model: FlowModel, mode: FlowLayoutMode, direction: String,
    labels: Map<YPath, FlowSize>, arrangement: FlowArrangement, density: Float,
    previous: Map<DiagramNodeId, DiagramPosition>, pin: YPath? = null): Map<DiagramNodeId, DiagramPosition> {
    val frames = flowPositionFrames(model, mode)
    val result = linkedMapOf<DiagramNodeId, DiagramPosition>()
    flowLaneIds(model).forEach { (path, id) -> arrangement.lanes[path]?.let { box ->
        result[id] = DiagramPosition(id, null, box.x / density, box.y / density, false)
    } }
    flowPositionIds(model).forEach { (path, id) ->
        val box = arrangement.nodes[path] ?: return@forEach
        if (path !in frames) return@forEach
        val frame = frames[path]
        val lane = model.nodes.first { it.output == path }.lane?.target
        val origin = lane?.let { arrangement.lanes[it] }?.let { r ->
            PinnedFlow.inset(labels, lane, mode, direction, model.lanes.map { it.output }).let { FlowPoint(r.x + it.x, r.y + it.y) }
        } ?: FlowPoint(0f, 0f)
        result[id] = DiagramPosition(id, frame, (box.x - origin.x) / density, (box.y - origin.y) / density,
            path == pin || previous[id]?.takeIf { it.frame == frame }?.pinned == true)
    }
    return result
}

/** 対象とその辺だけを追従させる。確定時にも他のノードの配置は計算し直さない。 */
internal fun flowDragPreview(base: FlowArrangement, path: YPath, point: FlowPoint, model: FlowModel, direction: String = "down"): FlowArrangement {
    val old = base.nodes[path] ?: return base
    if (old.x == point.x && old.y == point.y) return base
    val labels = base.edges.associate { it.output to FlowSize(it.label.width - 12f, it.label.height - 12f) }
    return PinnedFlow.routed(model, labels, base.nodes + (path to old.copy(x = point.x, y = point.y)), base.lanes,
        direction, base, setOf(path))
}
