package dev.toros.yde.semantic

import dev.toros.yde.yaml.model.YPath

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
    val laneHeaders: Map<YPath, FlowRect> = emptyMap(),
)
