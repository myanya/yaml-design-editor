package dev.toros.yde.desktop.ui.view

import dev.toros.yde.semantic.DashboardFlow
import dev.toros.yde.semantic.DashboardFlowDirection
import dev.toros.yde.semantic.DashboardMove

/**
 * ダッシュボードの流れ図の配置（Compose を使わない純粋な計算。座標は dp）。決まった規則で並べ、手で動かす位置は持たない。
 * - 段＝箱。入口の段から工程（前へ進む線）で辿った最長の段数を列（下向きなら行）にし、同じ列の中は定義の順に並べる
 * - 前へ進む工程＝箱の間の矢印と、その中ほどの札。戻る工程（入口からの辿りで逆向きになる線）＝箱の上（下向きなら左）を回る弧
 * - 段に留まる工程（to なし）＝箱の中の小さな札（線は引かない）
 */
internal object DashboardFlowLayout {
    const val BOX_W = 150f
    const val HEADER_H = 56f
    const val CHIP_W = 150f
    const val CHIP_H = 24f
    private const val STAY_H = 26f
    private const val GAP_MAIN = 190f
    private const val GAP_CROSS = 56f
    private const val ARC_STEP = 28f
    private const val START_W = 150f
    private const val START_H = 30f
    private const val MARGIN = 8f

    data class Rect(val x: Float, val y: Float, val w: Float, val h: Float) {
        val right: Float get() = x + w
        val bottom: Float get() = y + h
        val cx: Float get() = x + w / 2
        val cy: Float get() = y + h / 2
        fun overlaps(o: Rect): Boolean = x < o.right && o.x < right && y < o.bottom && o.y < bottom
    }

    data class Point(val x: Float, val y: Float)

    /** 線。points は折れ線、label は札の矩形（入口の線は札なし）。 */
    data class Edge(val move: DashboardMove?, val points: List<Point>, val label: Rect?, val back: Boolean)

    data class Layout(
        val width: Float,
        val height: Float,
        val boxes: Map<String, Rect>,
        /** 箱の中の、段に留まる工程の札。 */
        val stays: Map<DashboardMove, Rect>,
        val edges: List<Edge>,
        /** 入口の札（流れに start があるとき）。 */
        val start: Rect?,
    )

    /** 段の列（入口からの最長の段数）と、戻る工程。入口から着かない段は 0 列。 */
    fun ranks(flow: DashboardFlow): Pair<Map<String, Int>, Set<DashboardMove>> {
        val moving = flow.moves.filter { !it.stays && it.to in flow.steps && it.from in flow.steps }
        val origin = flow.start?.to?.takeIf { it in flow.steps } ?: flow.steps.first()
        // 入口から定義の順に深さ優先で辿り、辿っている途中の段へ戻る線を「戻る工程」とする
        val back = mutableSetOf<DashboardMove>()
        val state = mutableMapOf<String, Int>()  // 1 = 辿っている途中、2 = 済み
        fun visit(step: String) {
            state[step] = 1
            moving.filter { it.from == step }.forEach { m ->
                val to = m.to!!
                when (state[to]) {
                    null -> visit(to)
                    1 -> back += m
                    else -> Unit
                }
            }
            state[step] = 2
        }
        visit(origin)
        flow.steps.filter { it !in state }.forEach(::visit)
        val forward = moving.filter { it !in back }
        val rank = flow.steps.associateWith { 0 }.toMutableMap()
        repeat(flow.steps.size) {
            forward.forEach { m -> val to = m.to!!; if (rank.getValue(to) < rank.getValue(m.from) + 1) rank[to] = rank.getValue(m.from) + 1 }
        }
        return rank to back
    }

    fun arrange(flow: DashboardFlow): Layout {
        val (rank, back) = ranks(flow)
        val right = flow.direction == DashboardFlowDirection.RIGHT
        val columns = flow.steps.groupBy { rank.getValue(it) }.toSortedMap()
        val row = columns.values.flatMap { steps -> steps.mapIndexed { i, s -> s to i } }.toMap()
        val stays = flow.steps.associateWith { s -> flow.moves.filter { it.from == s && it.stays } }
        fun height(step: String) = HEADER_H + stays.getValue(step).size * STAY_H + if (stays.getValue(step).isEmpty()) 0f else 6f
        val backCount = back.size
        // 主の向き（右向きなら横）の位置: 列ごと。交差の向きの位置: 行ごとに、その行の箱の最大の高さ（右向き）か幅（下向き）
        val startSpace = if (flow.start != null) START_W + 60f else 0f
        val boxes = mutableMapOf<String, Rect>()
        if (right) {
            val top = MARGIN + if (backCount > 0) CHIP_H + backCount * ARC_STEP + 8f else 0f
            val rowHeights = flow.steps.groupBy { row.getValue(it) }.mapValues { (_, s) -> s.maxOf(::height) }
            fun rowY(r: Int) = top + (0 until r).sumOf { (rowHeights.getValue(it) + GAP_CROSS).toDouble() }.toFloat()
            flow.steps.forEach { s ->
                boxes[s] = Rect(MARGIN + startSpace + rank.getValue(s) * (BOX_W + GAP_MAIN), rowY(row.getValue(s)), BOX_W, height(s))
            }
        } else {
            val left = MARGIN + if (backCount > 0) CHIP_W + 12f + backCount * ARC_STEP else 0f
            val top = MARGIN + if (flow.start != null) START_H + 50f else 0f
            val rankHeights = columns.mapValues { (_, s) -> s.maxOf(::height) }
            fun rankY(r: Int) = top + columns.keys.filter { it < r }.sumOf { (rankHeights.getValue(it) + GAP_MAIN * .6f).toDouble() }.toFloat()
            flow.steps.forEach { s ->
                boxes[s] = Rect(left + row.getValue(s) * (BOX_W + GAP_CROSS + 40f), rankY(rank.getValue(s)), BOX_W, height(s))
            }
        }
        val stayChips = flow.steps.flatMap { s ->
            val box = boxes.getValue(s)
            stays.getValue(s).mapIndexed { i, m -> m to Rect(box.x + 6f, box.y + HEADER_H + i * STAY_H, BOX_W - 12f, CHIP_H - 2f) }
        }.toMap()
        // 線の端: 右向きは箱の見出しの高さの中央（同じ行の線をまっすぐに）、下向きは箱の上下の中央
        fun out(b: Rect) = if (right) Point(b.right, b.y + HEADER_H / 2) else Point(b.cx, b.bottom)
        fun into(b: Rect) = if (right) Point(b.x, b.y + HEADER_H / 2) else Point(b.cx, b.y)
        val edges = mutableListOf<Edge>()
        // 入口の札（線は前へ進む工程と同じ扱い）
        val start = flow.start?.takeIf { it.to in boxes }?.let { s ->
            val b = boxes.getValue(s.to)
            val rect = if (right) Rect(MARGIN, b.y + HEADER_H / 2 - START_H / 2, START_W, START_H)
                else Rect(b.cx - START_W / 2, MARGIN, START_W, START_H)
            edges += Edge(null, listOf(if (right) Point(rect.right, rect.cy) else Point(rect.cx, rect.bottom), into(b)), null, back = false)
            rect
        }
        val placed = mutableListOf<Rect>()
        val obstacles = boxes.values + listOfNotNull(start)
        fun free(r: Rect) = r.x >= 0f && r.y >= 0f && obstacles.none { it.overlaps(r) } && placed.none { it.overlaps(r) }
        var arc = 0
        flow.moves.filter { !it.stays && it.to in boxes && it.from in boxes }.sortedBy { if (it in back) 0 else 1 }.forEach { m ->
            val from = boxes.getValue(m.from)
            val to = boxes.getValue(m.to!!)
            if (m in back) {
                arc++
                val points = if (right) {
                    val y = from.y - 8f - arc * ARC_STEP
                    listOf(Point(from.cx + 10f, from.y), Point(from.cx + 10f, y), Point(to.cx - 10f, y), Point(to.cx - 10f, to.y))
                } else {
                    val x = minOf(from.x, to.x) - 12f - arc * ARC_STEP
                    listOf(Point(from.x, from.y + HEADER_H / 2), Point(x, from.y + HEADER_H / 2), Point(x, to.y + HEADER_H / 2), Point(to.x, to.y + HEADER_H / 2))
                }
                val label = if (right) Rect((points[1].x + points[2].x) / 2 - CHIP_W / 2, points[1].y - CHIP_H / 2, CHIP_W, CHIP_H)
                    else Rect(points[1].x - CHIP_W - 6f, (points[1].y + points[2].y) / 2 - CHIP_H / 2, CHIP_W, CHIP_H)
                placed += label
                edges += Edge(m, points, label, back = true)
            } else {
                val a = out(from)
                val b = into(to)
                // 札は自分の線の上で、箱とほかの札に重ならない位置まで中ほどから滑らせる。線の上に場所が無ければ、交差の向きにずらす
                fun at(t: Float) = Rect(a.x + (b.x - a.x) * t - CHIP_W / 2, a.y + (b.y - a.y) * t - CHIP_H / 2, CHIP_W, CHIP_H)
                val along = listOf(.5f, .4f, .6f, .3f, .7f, .2f, .8f, .1f, .9f).map(::at)
                val mid = at(.5f)
                val aside = (1..8).flatMap { k -> listOf(k, -k) }.map { k ->
                    if (right) mid.copy(y = mid.y + k * (CHIP_H + 6f)) else mid.copy(x = mid.x + k * (CHIP_W + 8f))
                }
                val label = (along + aside).firstOrNull(::free) ?: mid
                placed += label
                edges += Edge(m, listOf(a, b), label, back = false)
            }
        }
        val all = boxes.values + stayChips.values + edges.mapNotNull { it.label } + listOfNotNull(start)
        return Layout(all.maxOf { it.right } + MARGIN, all.maxOf { it.bottom } + MARGIN, boxes, stayChips, edges, start)
    }
}
