package dev.toros.yde.desktop.ui.view

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import dev.toros.yde.app.view.flow.FlowModel
import dev.toros.yde.desktop.ui.common.*
import dev.toros.yde.semantic.*
import dev.toros.yde.yaml.model.YPath

/** 幅ごとの実測を再利用し、ドラッグのたびに文字を測り直さない。 */
internal fun measureFlowLabels(model: FlowModel, texts: Map<YPath, TextLayoutResult>,
    visuals: Map<YPath, DiagramEdgeVisual>, measurer: TextMeasurer): Map<YPath, List<TextLayoutResult>> =
    model.edges.mapNotNull { edge ->
        val visual = visuals[edge.output]
        val original = if (visual == null) texts[edge.output] else visual.labelText ?: visual.style.label?.let { visual.texts[it] }
        original?.let {
            val text = if (original.didOverflowHeight) measurer.measure(original.layoutInput.text, original.layoutInput.style,
                constraints = original.layoutInput.constraints) else original
            val widths = (listOf(.8f, .6f, .45f, .3f).map { (text.size.width * it).toInt().coerceAtLeast(48) } + 48)
                .distinct().filter { it < text.size.width }
            val wrapped = widths.map { width -> measurer.measure(text.layoutInput.text, text.layoutInput.style,
                constraints = Constraints(maxWidth = width)) }.filter { !it.hasVisualOverflow }
            edge.output to (listOf(text) + wrapped).distinctBy { it.size }
        }
    }.toMap()

/** 全ての配線が決まった後に置く。画面・押した場所・PNGが同じ計測結果を参照する。 */
internal fun withFlowLabels(scene: FlowScene, measures: Map<YPath, List<TextLayoutResult>>): FlowScene {
    fun FlowRect.box() = LensBox(x, y, width, height)
    val base = scene.arrangement
    val headers = base.lanes.mapNotNull { (path, box) -> base.laneHeaders[path]?.box() ?: scene.texts[path]?.let {
        LensBox(box.x + 8f, box.y + 8f, it.size.width.toFloat(), it.size.height.toFloat())
    } }
    val obstacles = base.nodes.values.map { it.box() } + headers +
        base.edges.flatMap { route -> scene.edgeVisuals[route.output]?.let { visual ->
            diagramEdgeEndLabels(visual, route.points.map { Offset(it.x, it.y) }).map { label ->
                val r = label.bounds
                LensBox(r.left - 2f, r.top, r.width + 4f, r.height)
            }
        }.orEmpty() }
    // 経由点のラベルは利用者が置いた場所から動かさない。ほかのラベルはそれを避ける
    val occupied = base.edges.filter { it.output in scene.fixedLabels }.map { it.label.box() }.toMutableList()
    val texts = scene.texts.toMutableMap()
    val visuals = scene.edgeVisuals.toMutableMap()
    val routes = base.edges.map { route ->
        val choices = measures[route.output].orEmpty()
        if (choices.isEmpty()) return@map route
        val visual = visuals[route.output]
        fun size(text: TextLayoutResult) = LensNodeSize("", text.size.width + if (visual == null) 12f else 4f,
            text.size.height + if (visual == null) 12f else 0f)
        if (route.output in scene.fixedLabels) {
            val text = choices.first()
            val s = size(text)
            val center = route.label.center
            if (visual == null) texts[route.output] = text else visuals[route.output] = visual.copy(labelText = text)
            return@map route.copy(label = FlowRect(center.x - s.width / 2, center.y - s.height / 2, s.width, s.height))
        }
        val points = route.points.map { LensPoint(it.x, it.y) }
        val avoid = obstacles + occupied
        // まず線の近くに収まる最も広い幅を選び、不要な折り返しを増やさない。
        val nearby = choices.firstNotNullOfOrNull { text ->
            OrthogonalRouting.labelNear(points, size(text), avoid)?.let { text to it }
        }
        val (text, label) = nearby ?: choices.first().let { text ->
            text to OrthogonalRouting.label(points, size(text), avoid, LensPoint(route.label.center.x, route.label.center.y))
        }
        occupied += label
        if (visual == null) texts[route.output] = text else visuals[route.output] = visual.copy(labelText = text)
        route.copy(label = FlowRect(label.x, label.y, label.width, label.height))
    }
    return scene.copy(arrangement = base.copy(edges = routes), texts = texts, edgeVisuals = visuals)
}
