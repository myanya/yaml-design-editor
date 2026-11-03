package dev.toros.yde.desktop.ui.common

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max

/** 固定矩形を先に置き、未固定だけを配置する。方向・近さは好みで、固定位置は常に優先する。 */
internal object PinnedLayout {
    private const val GAP = 32f

    fun overlap(a: LensBox, b: LensBox, gap: Float = 0f): Boolean =
        a.x < b.x + b.width + gap && b.x < a.x + a.width + gap &&
            a.y < b.y + b.height + gap && b.y < a.y + a.height + gap

    fun arrange(sizes: List<LensNodeSize>, edges: List<LensEdgeSpec>, pins: Map<Int, LensPoint>,
                right: Boolean = false, inside: Boolean = false, crossLimit: Float? = null, crossStart: Float? = null): List<LensBox> {
        val placed = arrayOfNulls<LensBox>(sizes.size)
        pins.forEach { (i, p) -> if (i in sizes.indices) {
            require(p.x.isFinite() && p.y.isFinite()) { "固定位置が有限の数ではありません" }
            require(!inside || p.x >= 0f && p.y >= 0f) { "固定位置が親の内容領域の外にあります" }
            // 帯の拡大で既存ノードを押し出さない。crossStart は新しいノードにだけ適用する。
            placed[i] = LensBox(p.x, p.y, sizes[i].width, sizes[i].height)
        } }
        // 固定位置への距離が近い節点から扱う。繋がらない節点は入力順にする。
        val remaining = sizes.indices.filter { placed[it] == null }.toMutableSet()
        while (remaining.isNotEmpty()) {
            val i = remaining.firstOrNull { n -> edges.any { e ->
                e.from == n && placed.getOrNull(e.to) != null || e.to == n && placed.getOrNull(e.from) != null
            } } ?: remaining.first()
            val size = sizes[i]
            val before = edges.filter { it.to == i }.mapNotNull { placed.getOrNull(it.from) }
            val after = edges.filter { it.from == i }.mapNotNull { placed.getOrNull(it.to) }
            val preferred = when {
                before.isNotEmpty() -> if (right) LensPoint(before.maxOf { it.x + it.width } + 80f, before.map { it.y }.average().toFloat())
                    else LensPoint(before.map { it.x }.average().toFloat(), before.maxOf { it.y + it.height } + 80f)
                after.isNotEmpty() -> if (right) LensPoint(after.minOf { it.x } - size.width - 80f, after.map { it.y }.average().toFloat())
                    else LensPoint(after.map { it.x }.average().toFloat(), after.minOf { it.y } - size.height - 80f)
                else -> LensPoint(24f, 24f)
            }
            placed[i] = vacant(size, preferred, placed.filterNotNull(), right, inside, crossLimit, crossStart)
            remaining.remove(i)
        }
        return placed.map { it!! }
    }

    /** 既存矩形の縁を候補にして空きを探す。無制限の反復や固定ノードの押し出しをしない。 */
    fun vacant(size: LensNodeSize, preferred: LensPoint, obstacles: List<LensBox>, right: Boolean = false,
               inside: Boolean = false, crossLimit: Float? = null, crossStart: Float? = null): LensBox {
        fun allowed(x: Float, y: Float) = (!inside || x >= 0f && y >= 0f) &&
            (crossLimit == null || if (right) y >= 0f && y + size.height <= crossLimit else x >= 0f && x + size.width <= crossLimit) &&
            (crossStart == null || if (right) y >= crossStart else x >= crossStart)
        val px = maxOf(if (inside) max(0f, preferred.x) else preferred.x, if (!right) crossStart ?: -Float.MAX_VALUE else -Float.MAX_VALUE)
        val py = maxOf(if (inside) max(0f, preferred.y) else preferred.y, if (right) crossStart ?: -Float.MAX_VALUE else -Float.MAX_VALUE)
        val preferredBox = LensBox(px, py, size.width, size.height)
        if (allowed(px, py) && obstacles.none { overlap(preferredBox, it, 16f) }) return preferredBox
        val xs = (listOf(px, 0f) + obstacles.flatMap { listOf(it.x + it.width + GAP, it.x - size.width - GAP) })
            .distinct().sortedWith(compareBy({ abs(it - px) }, { it }))
        val ys = (listOf(py, 0f) + obstacles.flatMap { listOf(it.y + it.height + GAP, it.y - size.height - GAP) })
            .distinct().sortedWith(compareBy({ abs(it - py) }, { it }))
        data class Candidate(val x: Int, val y: Int, val cost: Float)
        val candidates = PriorityQueue(compareBy<Candidate>({ it.cost }, { ys[it.y] }, { xs[it.x] }))
        val seen = HashSet<Long>()
        fun offer(x: Int, y: Int) {
            if (x < xs.size && y < ys.size && seen.add((x.toLong() shl 32) or y.toLong()))
                candidates += Candidate(x, y, abs(xs[x] - px) + abs(ys[y] - py))
        }
        offer(0, 0)
        while (candidates.isNotEmpty()) {
            val p = candidates.remove()
            val box = LensBox(xs[p.x], ys[p.y], size.width, size.height)
            if (allowed(box.x, box.y) && obstacles.none { overlap(box, it, 16f) }) return box
            offer(p.x + 1, p.y); offer(p.x, p.y + 1)
        }
        error("親の内容領域に配置できません。固定位置または図の表示を見直してください")
    }

    /** 位置の保持と経路の決定を分け、移動時も同じ規則でつなぐ。 */
    fun route(a: LensBox, b: LensBox, obstacles: List<LensBox>, right: Boolean = false, ordinal: Int = 0): List<LensPoint> =
        OrthogonalRouting.route(a, b, obstacles, right, ordinal)

    fun lens(sizes: List<LensNodeSize>, edges: List<LensEdgeSpec>, pins: Map<Int, LensPoint>, kind: LensLayoutKind,
             inside: Boolean = false): LensArrangement {
        val boxes = arrange(sizes, edges, pins, inside = inside)
        val labels = mutableListOf<LensBox>()
        val parallels = edges.indices.groupBy { setOf(edges[it].from, edges[it].to) }
        val routes = edges.mapIndexed { i, e ->
            if (e.from !in boxes.indices || e.to !in boxes.indices) return@mapIndexed LensRoute(emptyList(), null)
            val points = route(boxes[e.from], boxes[e.to], boxes, ordinal = parallels.getValue(setOf(e.from, e.to)).indexOf(i))
            val center = points.getOrNull(points.size / 2) ?: LensPoint(boxes[e.from].centerX, boxes[e.from].centerY)
            val label = if (e.labelWidth > 0f) OrthogonalRouting.label(points, LensNodeSize("", e.labelWidth, e.labelHeight), boxes + labels, center, inside)
                .also { labels += it }.let { LensPoint(it.centerX, it.centerY) } else center
            LensRoute(points, label)
        }
        val collision = boxes.indices.any { i -> i in pins && boxes.indices.any { j -> j > i && j in pins && overlap(boxes[i], boxes[j]) } }
        val note = when {
            collision -> "ノード同士が重なっています。位置を調整してください"
            routes.any { it.points.isEmpty() } -> "現在の位置を保持しています。一部の辺は経路を確保できません"
            else -> "現在の位置を保持しています"
        }
        val rects = boxes + labels
        val points = routes.flatMap { it.points }
        val left = minOf(0f, rects.minOfOrNull { it.x } ?: 0f, points.minOfOrNull { it.x } ?: 0f)
        val top = minOf(0f, rects.minOfOrNull { it.y } ?: 0f, points.minOfOrNull { it.y } ?: 0f)
        return LensArrangement(kind, boxes, routes,
            maxOf(0f, rects.maxOfOrNull { it.x + it.width } ?: 0f, points.maxOfOrNull { it.x } ?: 0f) - left + 24f,
            maxOf(0f, rects.maxOfOrNull { it.y + it.height } ?: 0f, points.maxOfOrNull { it.y } ?: 0f) - top + 24f, note)
    }
}
