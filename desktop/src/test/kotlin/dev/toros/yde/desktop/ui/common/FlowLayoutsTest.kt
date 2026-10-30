package dev.toros.yde.desktop.ui.common

import dev.toros.yde.semantic.*
import dev.toros.yde.app.view.flow.*
import dev.toros.yde.yaml.model.YPath
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.geom.Path2D
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.test.*

class FlowLayoutsTest {
    private val detail = FlowDetail(null, emptyList(), false)
    private fun node(i: Int, title: String = "工程$i", shape: String = "box", lane: Int? = null) =
        FlowNode(listOf("nodes", i), "N$i", title, shape,
            lane?.let { FlowConnection("L$it", FlowConnectionState.RESOLVED, listOf("lanes", it)) }, detail)
    private fun edge(i: Int, from: Int, to: Int, title: String = "条件$i") = FlowEdge(listOf("edges", i), "E$i", title,
        FlowConnection("N$from", FlowConnectionState.RESOLVED, listOf("nodes", from)),
        FlowConnection("N$to", FlowConnectionState.RESOLVED, listOf("nodes", to)), detail)
    private fun overlap(a: FlowRect, b: FlowRect) = a.x < b.right - .1f && b.x < a.right - .1f &&
        a.y < b.bottom - .1f && b.y < a.bottom - .1f

    @Test
    fun `予約の確認と変更と取消が循環しても複数段に配置される`() {
        val nodes = listOf(
            node(0, "会議の開催予定が生じる", "rounded"),
            node(1, "会議室を予約する"),
            node(2, "予約後に何をするか", "diamond"),
            node(3, "空き状況・自分の予約を確認する"),
            node(4, "予約内容を変更する"),
            node(5, "自分の予約を取り消す"),
            node(6, "総務部へ代理取消を引き継ぐ", "rounded"),
            node(7, "予約取消済み", "rounded"),
            node(8, "会議室を利用する（システム外）", "rounded"),
            node(9, "予約せずに終了", "rounded"),
        ).map { it.copy(lane = FlowConnection("L0", FlowConnectionState.RESOLVED, listOf("lanes", 0))) }
        val connections = listOf(0 to 1, 1 to 2, 1 to 9, 2 to 3, 3 to 2, 2 to 4, 4 to 2, 2 to 5,
            2 to 6, 5 to 7, 2 to 8)
        val conditions = listOf("会議室を確保する", "予約成立", "予約を見送る", "内容を確認する", "確認完了",
            "予定を変更する", "変更完了・元の予約を維持", "取消する（開始1時間前まで）", "取消する（期限後）",
            "取消完了", "利用日時を迎える")
        val model = FlowModel(nodes, connections.mapIndexed { i, (a, b) -> edge(i, a, b, conditions[i]) },
            listOf(FlowLane(listOf("lanes", 0), "L0", "一般社員（予約者）")))
        val texts = texts(model)
        val sizes = texts.mapValues { measure(it.value) }
        for (direction in listOf("down", "right")) {
            val result = GraphLayouts.arrangeFlow(model, sizes, direction)
            render(model, result, texts, "reservation-$direction")
            assertEquals(result, GraphLayouts.arrangeFlow(model, sizes, direction))
            val occupied = result.nodes.values + result.edges.map { it.label }
            occupied.forEachIndexed { i, a -> occupied.drop(i + 1).forEach { b -> assertFalse(overlap(a, b), "$a / $b") } }
            result.nodes.values.forEach { assertTrue(it.right <= result.size.width && it.bottom <= result.size.height) }
            assertTrue(result.edges.all { it.points.size >= 2 })
            model.edges.zip(result.edges).forEach { (edge, route) ->
                listOf(edge.from.target to route.points.first(), edge.to.target to route.points.last()).forEach { (path, p) ->
                    if (model.nodes.single { it.output == path }.shape == "diamond") {
                        val r = result.nodes.getValue(path!!)
                        val distance = kotlin.math.abs(p.x - r.center.x) / (r.width / 2) +
                            kotlin.math.abs(p.y - r.center.y) / (r.height / 2)
                        assertEquals(1f, distance, .001f, "矢印を菱形の輪郭へ接続する")
                    }
                }
            }
            val cyclic = listOf(1, 2, 3, 4).map { result.nodes.getValue(listOf("nodes", it)) }
            val ranks = cyclic.map { if (direction == "down") it.y else it.x }.distinct()
            assertTrue(ranks.size >= 3, "循環する工程を同じ段へ押し込めない: $ranks")
            assertTrue(result.size.width < 3000 && result.size.height < 3000, "戻り線ごとに外周を増やさない: ${result.size}")
            model.nodes.filter { it.lane != null }.forEach { n ->
                val lane = result.lanes.getValue(n.lane!!.target!!)
                val box = result.nodes.getValue(n.output)
                assertTrue(lane.contains(box.center), "レーン内に配置する: $n / $lane / $box")
            }
        }
    }

    @Test
    fun `閉路と自己ループと並行辺も経路と条件を失わない`() {
        val model = FlowModel((0..2).map { node(it) },
            listOf(edge(0, 0, 1), edge(1, 1, 0), edge(2, 1, 1), edge(3, 0, 1), edge(4, 1, 2)), emptyList())
        for (direction in listOf("down", "right")) {
            val result = GraphLayouts.arrangeFlow(model, emptyMap(), direction)
            assertEquals(5, result.edges.size)
            assertEquals(5, result.edges.map { it.points }.distinct().size)
            assertTrue(result.edges.all { it.points.size >= 2 && it.label.width > 0 })
            val occupied = result.nodes.values + result.edges.map { it.label }
            occupied.forEachIndexed { i, a -> occupied.drop(i + 1).forEach { b -> assertFalse(overlap(a, b)) } }
        }
    }

    @Test
    fun `複数レーンの所属と未割当ノードと未解決辺を保持する`() {
        val missing = edge(2, 1, 2).copy(to = FlowConnection("missing", FlowConnectionState.UNRESOLVED, null))
        val model = FlowModel(listOf(node(0, lane = 1), node(1, lane = 0), node(2)),
            listOf(edge(0, 0, 1), edge(1, 1, 2), missing),
            (0..1).map { FlowLane(listOf("lanes", it), "L$it", "主体$it") })
        for (direction in listOf("down", "right")) {
            val result = GraphLayouts.arrangeFlow(model, model.lanes.associate { it.output to FlowSize(500f, 40f) }, direction)
            assertEquals(3, result.nodes.size)
            assertEquals(2, result.lanes.size)
            assertTrue(result.lanes.values.all { it.width >= 516f }, "レーン見出しを枠内に収める")
            model.nodes.filter { it.lane != null }.forEach { node ->
                assertTrue(result.lanes.getValue(node.lane!!.target!!).contains(result.nodes.getValue(node.output).center))
            }
            assertTrue(result.edges.last().points.isEmpty())
            assertTrue(result.edges.last().label.bottom <= result.size.height)
        }
    }

    @Test
    fun `空の図と長い鎖を配置できる`() {
        for (mode in FlowLayoutMode.entries) for (direction in listOf("down", "right")) {
            assertTrue(GraphLayouts.arrangeFlow(FlowModel(emptyList(), emptyList(), emptyList()), emptyMap(), direction, mode).nodes.isEmpty())
            val emptyLane = FlowModel(emptyList(), emptyList(), listOf(FlowLane(listOf("lanes", 0), "L0", "担当者")))
            val layout = GraphLayouts.arrangeFlow(emptyLane, emptyMap(), direction, mode)
            assertEquals(if (mode == FlowLayoutMode.PLAIN) 0 else 1, layout.lanes.size)
        }
        val model = FlowModel((0..2000).map { node(it) }, (0..1999).map { edge(it, it, it + 1) }, emptyList())
        assertEquals(2001, GraphLayouts.arrangeFlow(model, emptyMap()).nodes.size)
    }

    @Test
    fun `表示を切り替えても工程と遷移を保ちスイムレーンは定義順の帯になる`() {
        val nodes = listOf(node(0, "社員からの依頼", "rounded", 0), node(1, "依頼を受け付ける", "diamond", 1),
            node(2, "依頼内容を確認する", lane = 0), node(3, "予約・取消を代行する", lane = 1),
            node(4, "外部への連絡", "rounded"), node(5, "結果を確認する", "rounded", 0))
        val missing = edge(9, 0, 1).copy(to = FlowConnection("missing", FlowConnectionState.UNRESOLVED, null))
        val model = FlowModel(nodes, listOf(edge(0, 0, 1, "依頼"), edge(1, 1, 2, "確認が必要"),
            edge(2, 2, 1, "再依頼"), edge(3, 1, 3, "予約"), edge(4, 1, 3, "取消"),
            edge(5, 3, 3, "再入力"), edge(6, 3, 4, "完了"), edge(7, 4, 5, "連絡済み"), missing),
            listOf(FlowLane(listOf("lanes", 0), "L0", "一般社員"), FlowLane(listOf("lanes", 1), "L1", "総務部"),
                FlowLane(listOf("lanes", 2), "L2", "空のレーンも残す")))
        val texts = texts(model)
        val sizes = texts.mapValues { measure(it.value) }
        for (direction in listOf("down", "right")) for (mode in FlowLayoutMode.entries) {
            val result = GraphLayouts.arrangeFlow(model, sizes, direction, mode)
            render(model, result, texts, "options-${mode.name.lowercase()}-$direction")
            assertEquals(nodes.map { it.output }.toSet(), result.nodes.keys)
            assertEquals(model.edges.map { it.output }, result.edges.map { it.output })
            assertEquals(result, GraphLayouts.arrangeFlow(model, sizes, direction, mode))
            assertTrue(result.edges.last().points.isEmpty())
            val occupied = result.nodes.values + result.edges.map { it.label }
            occupied.forEachIndexed { i, a -> occupied.drop(i + 1).forEach { b -> assertFalse(overlap(a, b), "$mode $direction: $a / $b") } }
            occupied.forEach { assertTrue(it.x >= 0 && it.y >= 0 && it.right <= result.size.width && it.bottom <= result.size.height) }
            if (mode == FlowLayoutMode.PLAIN) assertTrue(result.lanes.isEmpty())
            else nodes.filter { it.lane != null }.forEach { n ->
                val lane = result.lanes.getValue(n.lane!!.target!!)
                val box = result.nodes.getValue(n.output)
                assertTrue(box.x >= lane.x && box.y >= lane.y && box.right <= lane.right && box.bottom <= lane.bottom)
            }
            if (mode == FlowLayoutMode.SWIMLANES) {
                assertNotEquals(result.edges[3].points.first(), result.edges[4].points.first(), "並行辺の接続点を分ける")
                val lanes = model.lanes.map { result.lanes.getValue(it.output) }
                lanes.zipWithNext().forEach { (a, b) ->
                    if (direction == "down") {
                        assertEquals(a.y, b.y); assertEquals(a.height, b.height); assertEquals(a.right, b.x)
                    } else {
                        assertEquals(a.x, b.x); assertEquals(a.width, b.width); assertEquals(a.bottom, b.y)
                    }
                }
                val unassigned = result.nodes.getValue(listOf("nodes", 4))
                assertTrue(lanes.none { overlap(it, unassigned) })
                result.laneHeaders.forEach { (p, h) ->
                    val lane = result.lanes.getValue(p)
                    assertTrue(h.x >= lane.x && h.y >= lane.y && h.right <= lane.right && h.bottom <= lane.bottom)
                    result.nodes.values.forEach { assertFalse(overlap(h, it)) }
                }
                result.edges.forEach { route -> route.points.zipWithNext().forEachIndexed { segment, (a, b) ->
                    assertTrue(a.x == b.x || a.y == b.y, "直交経路を保つ")
                    result.nodes.forEach { (path, node) ->
                        val crosses = if (a.x == b.x) a.x > node.x + .1f && a.x < node.right - .1f &&
                            minOf(a.y, b.y) < node.bottom - .1f && maxOf(a.y, b.y) > node.y + .1f
                        else a.y > node.y + .1f && a.y < node.bottom - .1f &&
                            minOf(a.x, b.x) < node.right - .1f && maxOf(a.x, b.x) > node.x + .1f
                        val diamond = model.nodes.single { it.output == path }.shape == "diamond"
                        val endpointBox = diamond && ((segment == 0 && node.contains(route.points.first())) ||
                            (segment == route.points.size - 2 && node.contains(route.points.last())))
                        // 菱形の輪郭へ接続する部分だけは外接矩形の内側に入る。
                        assertFalse(crosses && !endpointBox, "線が工程を横切らない: $a / $b / $node")
                    }
                } }
            }
        }
        assertEquals(3, model.lanes.size)
        assertEquals("L0", model.nodes.first().lane!!.key)
    }

    @Test
    fun `同じ段の判断と完了状態は寸法が違っても段を分離しない`() {
        val model = FlowModel(listOf(node(0, lane = 0), node(1, shape = "diamond", lane = 0),
            node(2, shape = "rounded", lane = 0)), listOf(edge(0, 0, 1), edge(1, 0, 2)),
            listOf(FlowLane(listOf("lanes", 0), "L0", "担当者")))
        for (direction in listOf("down", "right")) {
            val seed = FlowArrangement(mapOf(
                listOf("nodes", 0) to FlowRect(24f, 24f, 140f, 60f),
                listOf("nodes", 1) to FlowRect(24f, 180f, 300f, 100f),
                listOf("nodes", 2) to FlowRect(380f, 180f, 140f, 60f)), emptyMap(), emptyList(), FlowSize(600f, 400f))
            val oriented = if (direction == "down") seed else seed.copy(nodes = seed.nodes.mapValues { (_, r) ->
                FlowRect(r.y, r.x, r.height, r.width)
            })
            val result = FlowSwimlanes.arrange(model, emptyMap(), oriented, direction)
            val a = result.nodes.getValue(listOf("nodes", 1)); val b = result.nodes.getValue(listOf("nodes", 2))
            if (direction == "down") assertEquals(a.center.y, b.center.y) else assertEquals(a.center.x, b.center.x)
        }
    }

    private val font = Font("Dialog", Font.PLAIN, 14)
    private val metrics = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().let {
        try { it.font = font; it.fontMetrics } finally { it.dispose() }
    }
    private fun lines(text: String): List<String> {
        val result = mutableListOf<String>(); var line = ""
        for (c in text) {
            if (metrics.stringWidth(line + c) > 220 && line.isNotEmpty()) { result += line; line = "" }
            line += c
        }
        result += line
        return result
    }
    private fun measure(text: String): FlowSize {
        val rows = lines(text)
        return FlowSize(rows.maxOf { metrics.stringWidth(it) }.toFloat(), (rows.size * metrics.height).toFloat())
    }
    private fun texts(model: FlowModel) = (model.nodes.map { it.output to it.label.orEmpty() } +
        model.edges.map { it.output to it.label.orEmpty() } + model.lanes.map { it.output to it.label.orEmpty() }).toMap()

    /** 配置結果の視覚レビュー用。図の座標と文字寸法は検証と同じものを使う。 */
    private fun render(model: FlowModel, result: FlowArrangement, texts: Map<YPath, String>, name: String) {
        val image = BufferedImage(ceil(result.size.width).toInt(), ceil(result.size.height).toInt(), BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(15, 25, 30); g.fillRect(0, 0, image.width, image.height); g.font = font
        fun text(path: YPath, box: FlowRect) {
            g.color = Color(225, 235, 240)
            val rows = lines(texts[path].orEmpty())
            rows.forEachIndexed { i, line -> g.drawString(line, box.center.x - metrics.stringWidth(line) / 2,
                box.center.y - rows.size * metrics.height / 2 + metrics.ascent + i * metrics.height) }
        }
        result.lanes.forEach { (p, box) ->
            g.color = Color(50, 75, 85); g.drawRect(box.x.toInt(), box.y.toInt(), box.width.toInt(), box.height.toInt())
            text(p, result.laneHeaders[p] ?: FlowRect(box.x + 8, box.y + 8, box.width - 16, measure(texts[p].orEmpty()).height))
        }
        result.edges.forEach { route ->
            g.color = Color(140, 175, 185); g.stroke = BasicStroke(1.5f)
            route.points.zipWithNext().forEach { (a, b) -> g.drawLine(a.x.toInt(), a.y.toInt(), b.x.toInt(), b.y.toInt()) }
            if (route.points.size >= 2) {
                val a = route.points[route.points.lastIndex - 1]; val b = route.points.last()
                val length = kotlin.math.hypot(b.x - a.x, b.y - a.y).coerceAtLeast(1f)
                val dx = (b.x - a.x) / length; val dy = (b.y - a.y) / length
                for (side in listOf(-1, 1)) g.drawLine(b.x.toInt(), b.y.toInt(),
                    (b.x - dx * 9 + dy * 4 * side).toInt(), (b.y - dy * 9 - dx * 4 * side).toInt())
            }
        }
        result.edges.forEach { route ->
            g.color = Color(15, 25, 30); val r = route.label
            g.fillRect(r.x.toInt(), r.y.toInt(), r.width.toInt(), r.height.toInt()); text(route.output, r)
        }
        model.nodes.forEach { node ->
            val r = result.nodes.getValue(node.output)
            val shape = if (node.shape == "diamond") Path2D.Float().apply {
                moveTo(r.center.x.toDouble(), r.y.toDouble()); lineTo(r.right.toDouble(), r.center.y.toDouble())
                lineTo(r.center.x.toDouble(), r.bottom.toDouble()); lineTo(r.x.toDouble(), r.center.y.toDouble()); closePath()
            } else java.awt.geom.RoundRectangle2D.Float(r.x, r.y, r.width, r.height,
                if (node.shape == "rounded") 20f else 0f, if (node.shape == "rounded") 20f else 0f)
            g.color = Color(24, 40, 48); g.fill(shape); g.color = Color(90, 135, 150); g.draw(shape); text(node.output, r)
        }
        g.dispose()
        val file = File("build/reports/flow-layout/$name.png"); file.parentFile.mkdirs(); ImageIO.write(image, "png", file)
    }
}
