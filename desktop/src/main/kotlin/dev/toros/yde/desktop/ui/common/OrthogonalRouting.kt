package dev.toros.yde.desktop.ui.common

import java.util.PriorityQueue
import kotlin.math.abs

/** ノードを動かさず、接続面・短い折れ線・その近くのラベルを決める。 */
internal object OrthogonalRouting {
    private const val CLEARANCE = 20f
    private const val BEND_COST = 28f
    enum class Side(val dx: Float, val dy: Float) {
        EAST(1f, 0f), SOUTH(0f, 1f), WEST(-1f, 0f), NORTH(0f, -1f);
        val horizontal get() = dx != 0f
    }
    data class Port(val side: Side, val fraction: Float = .5f) {
        fun at(b: LensBox) = when (side) {
            Side.EAST -> LensPoint(b.x + b.width, b.y + b.height * fraction)
            Side.WEST -> LensPoint(b.x, b.y + b.height * fraction)
            Side.SOUTH -> LensPoint(b.x + b.width * fraction, b.y + b.height)
            Side.NORTH -> LensPoint(b.x + b.width * fraction, b.y)
        }
    }
    data class Ports(val from: Port, val to: Port)

    fun preferred(a: LensBox, b: LensBox, right: Boolean, ordinal: Int = 0): Ports {
        val fraction = .5f + listOf(0f, .12f, -.12f, .24f, -.24f)[ordinal % 5]
        if (a == b) {
            val side = if (right) Side.EAST else Side.SOUTH
            return Ports(Port(side, .32f), Port(side, .68f))
        }
        val xGap = maxOf(b.x - a.x - a.width, a.x - b.x - b.width)
        val yGap = maxOf(b.y - a.y - a.height, a.y - b.y - b.height)
        val horizontal = if (right) xGap >= 0f || yGap < 0f else yGap < 0f && xGap >= 0f
        return if (horizontal) {
            if (b.centerX >= a.centerX) Ports(Port(Side.EAST, fraction), Port(Side.WEST, fraction))
            else Ports(Port(Side.WEST, fraction), Port(Side.EAST, fraction))
        } else {
            if (b.centerY >= a.centerY) Ports(Port(Side.SOUTH, fraction), Port(Side.NORTH, fraction))
            else Ports(Port(Side.NORTH, fraction), Port(Side.SOUTH, fraction))
        }
    }

    /** 移動前の接続面を優先する。相手を通り越したときは向かい合う面へ切り替える。 */
    fun hint(a: LensBox, b: LensBox, points: List<LensPoint>): Ports? {
        if (points.size < 2) return null
        fun port(box: LensBox, at: LensPoint, outside: LensPoint): Port? {
            val dx = outside.x - at.x; val dy = outside.y - at.y
            val side = when {
                abs(dy) < .01f && dx > 0 -> Side.EAST
                abs(dy) < .01f && dx < 0 -> Side.WEST
                abs(dx) < .01f && dy > 0 -> Side.SOUTH
                abs(dx) < .01f && dy < 0 -> Side.NORTH
                else -> return null
            }
            val fraction = if (side.horizontal) (at.y - box.y) / box.height else (at.x - box.x) / box.width
            return Port(side, fraction.coerceIn(.05f, .95f))
        }
        return Ports(port(a, points.first(), points[1]) ?: return null,
            port(b, points.last(), points[points.lastIndex - 1]) ?: return null)
    }

    fun faces(a: LensBox, b: LensBox, ports: Ports): Boolean {
        fun faces(from: LensBox, to: LensBox, p: Port) =
            (to.centerX - from.centerX) * p.side.dx + (to.centerY - from.centerY) * p.side.dy >= 0f
        return a == b || faces(a, b, ports.from) && faces(b, a, ports.to)
    }

    fun intersects(p: LensPoint, q: LensPoint, r: LensBox): Boolean =
        if (abs(p.x - q.x) < .01f) p.x > r.x + .01f && p.x < r.x + r.width - .01f &&
            maxOf(minOf(p.y, q.y), r.y) < minOf(maxOf(p.y, q.y), r.y + r.height) - .01f
        else if (abs(p.y - q.y) < .01f) p.y > r.y + .01f && p.y < r.y + r.height - .01f &&
            maxOf(minOf(p.x, q.x), r.x) < minOf(maxOf(p.x, q.x), r.x + r.width) - .01f
        else false

    private fun distance(a: LensPoint, b: LensPoint) = abs(a.x - b.x) + abs(a.y - b.y)
    private fun simplify(points: List<LensPoint>): List<LensPoint> {
        val result = mutableListOf<LensPoint>()
        for (p in points) {
            if (result.lastOrNull() == p) continue
            while (result.size >= 2) {
                val a = result[result.lastIndex - 1]; val b = result.last()
                if ((a.x == b.x && b.x == p.x || a.y == b.y && b.y == p.y) &&
                    (b.x - a.x) * (p.x - b.x) + (b.y - a.y) * (p.y - b.y) >= 0f) result.removeAt(result.lastIndex)
                else break
            }
            result += p
        }
        return result
    }

    fun route(a: LensBox, b: LensBox, obstacles: List<LensBox>, right: Boolean = false, ordinal: Int = 0,
        hint: Ports? = null, fixedPorts: Boolean = false): List<LensPoint> {
        var primary = hint?.takeIf { fixedPorts || faces(a, b, it) } ?: preferred(a, b, right, ordinal)
        // 寸法や接続点の端数による、ごく短い段差を作らない。
        if (!fixedPorts && primary.from.side.dx == -primary.to.side.dx && primary.from.side.dy == -primary.to.side.dy) {
            val p = primary.from.at(a); val q = primary.to.at(b)
            if (primary.from.side.horizontal && abs(p.y - q.y) <= 8f) {
                val y = (p.y + q.y) / 2
                if (y in a.y..(a.y + a.height) && y in b.y..(b.y + b.height))
                    primary = Ports(primary.from.copy(fraction = (y - a.y) / a.height), primary.to.copy(fraction = (y - b.y) / b.height))
            } else if (!primary.from.side.horizontal && abs(p.x - q.x) <= 8f) {
                val x = (p.x + q.x) / 2
                if (x in a.x..(a.x + a.width) && x in b.x..(b.x + b.width))
                    primary = Ports(primary.from.copy(fraction = (x - a.x) / a.width), primary.to.copy(fraction = (x - b.x) / b.width))
            }
        }
        val clearance = CLEARANCE + ordinal * 8f
        // 他の箱には小さな余白も取る。接続先の箱は端点から外へ出られるよう実寸で扱う。
        val blockedBoxes = obstacles.map { r -> if (r == a || r == b) r else LensBox(r.x - 6f, r.y - 6f, r.width + 12f, r.height + 12f) }
        fun blocked(p: LensPoint, q: LensPoint) = blockedBoxes.any { intersects(p, q, it) }
        data class Ends(val start: LensPoint, val exit: LensPoint, val entry: LensPoint, val end: LensPoint, val ports: Ports)
        fun ends(p: Ports): Ends? {
            val start = p.from.at(a); val end = p.to.at(b)
            val gap = (end.x - start.x) * p.from.side.dx + (end.y - start.y) * p.from.side.dy
            val facing = p.from.side.dx == -p.to.side.dx && p.from.side.dy == -p.to.side.dy
            val stub = if (facing && gap > 0f) minOf(clearance, gap / 2) else clearance
            val exit = LensPoint(start.x + p.from.side.dx * stub, start.y + p.from.side.dy * stub)
            val entry = LensPoint(end.x + p.to.side.dx * stub, end.y + p.to.side.dy * stub)
            return if (blocked(start, exit) || blocked(entry, end)) null else Ends(start, exit, entry, end, p)
        }
        fun valid(path: List<LensPoint>, p: Ports): Boolean {
            if (path.size < 2 || path.zipWithNext().any { (s, t) -> blocked(s, t) }) return false
            val first = path[1]; val last = path[path.lastIndex - 1]
            return (first.x - path.first().x) * p.from.side.dx + (first.y - path.first().y) * p.from.side.dy > 0f &&
                (last.x - path.last().x) * p.to.side.dx + (last.y - path.last().y) * p.to.side.dy > 0f &&
                path.windowed(3).none { (s, t, u) -> (t.x - s.x) * (u.x - t.x) + (t.y - s.y) * (u.y - t.y) < 0f }
        }
        fun cost(path: List<LensPoint>) = path.zipWithNext().sumOf { (s, t) -> distance(s, t).toDouble() } + (path.size - 2) * BEND_COST
        fun short(e: Ends): List<LensPoint>? {
            val s = e.exit; val t = e.entry
            val mx = (s.x + t.x) / 2; val my = (s.y + t.y) / 2
            val candidates = listOf(
                listOf(s, LensPoint(mx, s.y), LensPoint(mx, t.y), t),
                listOf(s, LensPoint(s.x, my), LensPoint(t.x, my), t),
                listOf(s, LensPoint(s.x, t.y), t), listOf(s, LensPoint(t.x, s.y), t),
            ).map { simplify(listOf(e.start) + it + e.end) }.filter { valid(it, e.ports) }
            return candidates.minByOrNull(::cost)
        }
        // 通常の接続は向かい合う面の中央で折る。近い箱では stub の長さだけで逆走させない。
        val first = ends(primary)
        first?.let { short(it)?.let { path -> return path } }
        val detourSides = if (right) listOf(Side.NORTH, Side.SOUTH, Side.WEST, Side.EAST) else listOf(Side.WEST, Side.EAST, Side.NORTH, Side.SOUTH)
        val alternatives = (if (fixedPorts) emptyList() else listOf(preferred(a, b, !right, ordinal)) + detourSides.map { side ->
            Ports(Port(side, primary.from.fraction), Port(side, primary.to.fraction))
        }).distinct().filter { it != primary }.mapNotNull(::ends)
        alternatives.mapNotNull(::short).minByOrNull(::cost)?.let { return it }

        // 障害物が多い場合は格子を探索する。進入軸を状態に含め、長さと折れ曲がりの両方を評価する。
        fun search(e: Ends): List<LensPoint>? {
            val xs = (listOf(e.exit.x, e.entry.x) + blockedBoxes.flatMap { listOf(it.x - clearance, it.x + it.width + clearance) }).distinct().sorted()
            val ys = (listOf(e.exit.y, e.entry.y) + blockedBoxes.flatMap { listOf(it.y - clearance, it.y + it.height + clearance) }).distinct().sorted()
            if (xs.size.toLong() * ys.size > 250_000) return null
            val width = xs.size
            fun point(i: Int) = LensPoint(xs[i % width], ys[i / width])
            val begin = ys.indexOf(e.exit.y) * width + xs.indexOf(e.exit.x)
            val end = ys.indexOf(e.entry.y) * width + xs.indexOf(e.entry.x)
            val start = begin * 2 + if (e.ports.from.side.horizontal) 0 else 1
            val targetAxis = if (e.ports.to.side.horizontal) 0 else 1
            val costs = FloatArray(width * ys.size * 2) { Float.POSITIVE_INFINITY }
            val previous = IntArray(costs.size) { -1 }
            data class Visit(val state: Int, val cost: Float, val estimate: Float)
            val queue = PriorityQueue(compareBy<Visit>({ it.estimate }, { it.state }))
            costs[start] = 0f; queue += Visit(start, 0f, distance(e.exit, e.entry))
            var best = -1; var bestCost = Float.POSITIVE_INFINITY; var visits = 0
            while (queue.isNotEmpty() && visits++ < 100_000) {
                val visit = queue.remove()
                if (visit.estimate >= bestCost) break
                if (visit.cost != costs[visit.state]) continue
                val index = visit.state / 2; val axis = visit.state % 2
                if (index == end) {
                    val score = visit.cost + if (axis == targetAxis) 0f else BEND_COST
                    if (score < bestCost) { best = visit.state; bestCost = score }
                    continue
                }
                val x = index % width; val y = index / width
                val next = listOfNotNull(if (x > 0) index - 1 else null, if (x + 1 < width) index + 1 else null,
                    if (y > 0) index - width else null, if (y + 1 < ys.size) index + width else null)
                val p = point(index)
                for (n in next) {
                    val q = point(n)
                    if (blocked(p, q)) continue
                    val nextAxis = if (p.y == q.y) 0 else 1
                    val state = n * 2 + nextAxis
                    val score = visit.cost + distance(p, q) + if (axis == nextAxis) 0f else BEND_COST
                    if (score < costs[state]) {
                        costs[state] = score; previous[state] = visit.state
                        queue += Visit(state, score, score + distance(q, e.entry))
                    }
                }
            }
            if (best < 0) return null
            val path = mutableListOf(e.end)
            var cursor = best
            while (cursor >= 0) { path += point(cursor / 2); cursor = previous[cursor] }
            path += e.start
            return simplify(path.reversed()).takeIf { valid(it, e.ports) }
        }
        return (listOfNotNull(first) + alternatives).mapNotNull(::search).minByOrNull(::cost).orEmpty()
    }

    /** 折り返し幅を選ぶ側も、通常の配置と同じ近傍候補を使う。 */
    fun labelNear(points: List<LensPoint>, size: LensNodeSize, obstacles: List<LensBox>, inside: Boolean = false): LensBox? {
        val segments = points.zipWithNext().filter { (a, b) -> distance(a, b) > 0f }
        val total = segments.sumOf { (a, b) -> distance(a, b).toDouble() }.toFloat()
        var traveled = 0f
        val candidates = mutableListOf<Pair<Float, LensBox>>()
        segments.forEach { (a, b) ->
            val length = distance(a, b)
            val horizontal = a.y == b.y
            for (fraction in listOf(.5f, 0f, 1f)) for (gap in listOf(6f, 16f, 28f)) for (sign in listOf(1, -1)) {
                val center = LensPoint(a.x + (b.x - a.x) * fraction, a.y + (b.y - a.y) * fraction)
                val box = if (horizontal) LensBox(center.x - size.width / 2,
                    if (sign > 0) center.y + gap else center.y - gap - size.height, size.width, size.height)
                else LensBox(if (sign > 0) center.x + gap else center.x - gap - size.width,
                    center.y - size.height / 2, size.width, size.height)
                if ((!inside || box.x >= 0f && box.y >= 0f) && obstacles.none { PinnedLayout.overlap(box, it, 2f) } &&
                    segments.none { (p, q) -> intersects(p, q, box) }) candidates +=
                    (abs(traveled + length * fraction - total / 2) + gap * 2 + if (horizontal) 0f else 24f) to box
            }
            traveled += length
        }
        return candidates.minByOrNull { it.first }?.second
    }

    /** fallback は中心。線がある場合は以前のラベル位置でなく、その線からの距離で退避先を選ぶ。 */
    fun label(points: List<LensPoint>, size: LensNodeSize, obstacles: List<LensBox>, fallback: LensPoint, inside: Boolean = false): LensBox =
        labelNear(points, size, obstacles, inside) ?: GraphLabelPlacement.closest(points, size, obstacles, fallback, inside)
}
