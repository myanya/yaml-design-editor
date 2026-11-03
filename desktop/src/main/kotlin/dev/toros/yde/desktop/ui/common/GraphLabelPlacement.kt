package dev.toros.yde.desktop.ui.common

import kotlin.math.abs
import kotlin.math.hypot

/** 近傍が塞がる場合も、ノード用の空き配置へ逃がさず、線を基準にラベルを置く。 */
internal object GraphLabelPlacement {
    private const val GAP = 6f

    fun closest(points: List<LensPoint>, size: LensNodeSize, obstacles: List<LensBox>,
                fallback: LensPoint, inside: Boolean): LensBox {
        val segments = points.zipWithNext().filter { (a, b) -> a != b }
        if (segments.isEmpty()) return PinnedLayout.vacant(size,
            LensPoint(fallback.x - size.width / 2, fallback.y - size.height / 2), obstacles, inside = inside)
        val total = segments.sumOf { (a, b) -> hypot(b.x - a.x, b.y - a.y).toDouble() }.toFloat()
        data class Candidate(val box: LensBox, val distance: Float, val middle: Float)
        val candidates = mutableListOf<Candidate>()
        var traveled = 0f
        for ((a, b) in segments) {
            val length = hypot(b.x - a.x, b.y - a.y)
            val horizontal = a.y == b.y
            for (fraction in listOf(.5f, 0f, 1f)) {
                val point = LensPoint(a.x + (b.x - a.x) * fraction, a.y + (b.y - a.y) * fraction)
                val crossSize = if (horizontal) size.width else size.height
                val axisSize = if (horizontal) size.height else size.width
                val cross = ((if (horizontal) point.x else point.y) - crossSize / 2)
                    .let { if (inside) maxOf(0f, it) else it }
                val axis = if (horizontal) point.y else point.x
                // 直交方向に動かした際の禁止区間を併合し、障害物の個数に対する二重探索を避ける。
                val blocked = obstacles.filter {
                    val start = if (horizontal) it.x else it.y
                    val end = start + if (horizontal) it.width else it.height
                    cross < end + GAP && start < cross + crossSize + GAP
                }.map {
                    val start = if (horizontal) it.y else it.x
                    val end = start + if (horizontal) it.height else it.width
                    (start - axisSize - GAP) to (end + GAP)
                }.sortedBy { it.first }
                val merged = mutableListOf<Pair<Float, Float>>()
                blocked.forEach { interval ->
                    val last = merged.lastOrNull()
                    if (last != null && interval.first <= last.second)
                        merged[merged.lastIndex] = last.first to maxOf(last.second, interval.second)
                    else merged += interval
                }
                for (preferred in listOf(axis + GAP, axis - axisSize - GAP)) {
                    val at = if (inside) maxOf(0f, preferred) else preferred
                    val interval = merged.firstOrNull { at > it.first && at < it.second }
                    val choices = interval?.let { listOf(it.first, it.second) } ?: listOf(at)
                    for (position in choices.filter { !inside || it >= 0f }) {
                        val box = if (horizontal) LensBox(cross, position, size.width, size.height)
                            else LensBox(position, cross, size.width, size.height)
                        val distance = segments.minOf { (p, q) ->
                            val x = box.centerX.coerceIn(minOf(p.x, q.x), maxOf(p.x, q.x))
                            val y = box.centerY.coerceIn(minOf(p.y, q.y), maxOf(p.y, q.y))
                            hypot(box.centerX - x, box.centerY - y)
                        }
                        candidates += Candidate(box, distance, abs(traveled + length * fraction - total / 2))
                    }
                }
            }
            traveled += length
        }
        return candidates.minWith(compareBy<Candidate>({ it.distance }, { it.middle })).box
    }
}
