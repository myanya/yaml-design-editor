package dev.toros.yde.desktop.ui.graph

import dev.toros.yde.desktop.DiagramNodeId
import dev.toros.yde.desktop.DiagramPosition
import dev.toros.yde.desktop.ui.common.LensArrangement
import dev.toros.yde.desktop.ui.common.LensNestedNode

/** 親の見出しを除いた内容領域を基準に、表示中の全ノードの現在位置を記録する。 */
internal fun lensPositionSnapshot(nodes: List<LensNestedNode>, ids: List<DiagramNodeId>, arrangement: LensArrangement,
    density: Float, previous: Map<DiagramNodeId, DiagramPosition>, pin: Int? = null): Map<DiagramNodeId, DiagramPosition> =
    nodes.mapIndexed { i, node ->
        val box = arrangement.boxes[i]
        val parent = node.parent
        val frame = parent?.let { ids[it] }
        val origin = parent?.let { arrangement.boxes[it] }
        val id = ids[i]
        id to DiagramPosition(id, frame, (box.x - (origin?.x ?: 0f)) / density,
            (box.y - (origin?.y ?: 0f) - (parent?.let { nodes[it].height } ?: 0f)) / density,
            i == pin || previous[id]?.takeIf { it.frame == frame }?.pinned == true)
    }.toMap()
