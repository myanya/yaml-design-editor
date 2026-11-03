package dev.toros.yde.desktop.ui.common

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import dev.toros.yde.semantic.DiagramEdgeStyle
import kotlin.math.abs
import kotlin.math.hypot

internal data class DiagramEdgeVisual(val style: DiagramEdgeStyle, val texts: Map<String, TextLayoutResult>, val colors: DiagramColors,
    val labelText: TextLayoutResult? = null)
internal data class DiagramEdgeLabel(val text: TextLayoutResult, val bounds: Rect)

@Composable
internal fun <K> rememberDiagramEdges(styles: Map<K, DiagramEdgeStyle>): Map<K, DiagramEdgeVisual> {
    val measurer = rememberTextMeasurer()
    val colors = diagramColors()
    return remember(styles, measurer, colors) {
        styles.mapValues { (_, style) ->
            val texts = listOfNotNull(style.label, style.sourceLabel, style.targetLabel).filter { it.isNotEmpty() }.distinct().associateWith {
                measurer.measure(it, TextStyle(fontSize = 11.sp, color = colors.tone(style.tone)), maxLines = 3,
                    constraints = Constraints(maxWidth = 220))
            }
            DiagramEdgeVisual(style, texts, colors)
        }
    }
}

internal fun diagramMarkerLength(marker: String): Float = when (marker) {
    "arrow" -> 9f; "triangle" -> 13f; "diamond", "filled-diamond" -> 18f; else -> 0f
}

private fun direction(from: Offset, to: Offset): Offset {
    val delta = to - from
    val length = hypot(delta.x, delta.y)
    return if (length < 0.001f) Offset.Zero else delta / length
}

/** 同じ端の図形を始点と終点に使う。insideは折れ線の内側を指す。 */
internal fun diagramMarkerPoints(marker: String, tip: Offset, inside: Offset): List<Offset> {
    val u = direction(tip, inside)
    if (u == Offset.Zero || marker == "none") return emptyList()
    val v = Offset(-u.y, u.x)
    return when (marker) {
        "arrow" -> listOf(tip + u * 9f + v * 4f, tip, tip + u * 9f - v * 4f)
        "triangle" -> listOf(tip, tip + u * 13f + v * 6f, tip + u * 13f - v * 6f)
        "diamond", "filled-diamond" -> listOf(tip, tip + u * 9f + v * 5f, tip + u * 18f, tip + u * 9f - v * 5f)
        else -> emptyList()
    }
}

private fun cleanPoints(points: List<Offset>): List<Offset> = points.filterIndexed { i, p -> i == 0 || p != points[i - 1] }

private fun middle(points: List<Offset>): Offset {
    val lengths = points.zipWithNext().map { (a, b) -> hypot(b.x - a.x, b.y - a.y) }
    var remaining = lengths.sum() / 2
    lengths.forEachIndexed { i, length ->
        if (remaining <= length && length > 0) return points[i] + (points[i + 1] - points[i]) * (remaining / length)
        remaining -= length
    }
    return points.firstOrNull() ?: Offset.Zero
}

/** 端ラベルは論理的な始点・終点に合わせたstyleを使い、線と記号の横へ離す。 */
internal fun diagramEdgeLabels(visual: DiagramEdgeVisual, raw: List<Offset>, center: Offset? = null): List<DiagramEdgeLabel> {
    val points = cleanPoints(raw)
    val central = (visual.labelText ?: visual.style.label?.let { visual.texts[it] })?.let { text ->
        val at = center ?: middle(points)
        DiagramEdgeLabel(text, Rect(at - Offset(text.size.width / 2f, text.size.height / 2f), Size(text.size.width.toFloat(), text.size.height.toFloat())))
    }
    return listOfNotNull(central) + diagramEdgeEndLabels(visual, points)
}

/** 中央ラベルの配置前に、端の多重度が占める領域を確保する。 */
internal fun diagramEdgeEndLabels(visual: DiagramEdgeVisual, raw: List<Offset>): List<DiagramEdgeLabel> {
    val points = cleanPoints(raw)
    if (points.size < 2) return emptyList()
    fun label(text: String?, at: Offset): DiagramEdgeLabel? = text?.let { visual.texts[it] }?.let {
        DiagramEdgeLabel(it, Rect(at - Offset(it.size.width / 2f, it.size.height / 2f), Size(it.size.width.toFloat(), it.size.height.toFloat())))
    }
    fun endpoint(text: String?, marker: String, tip: Offset, inside: Offset): DiagramEdgeLabel? {
        val measured = text?.let { visual.texts[it] } ?: return null
        val u = direction(tip, inside)
        val normal = Offset(-u.y, u.x)
        val along = if (abs(u.x) > 0.5f) measured.size.width / 2f else measured.size.height / 2f
        val across = if (abs(u.x) > 0.5f) measured.size.height / 2f else measured.size.width / 2f
        return label(text, tip + u * (diagramMarkerLength(marker) + 6f + along) + normal * (across + 5f))
    }
    return listOfNotNull(
        endpoint(visual.style.sourceLabel, visual.style.sourceMarker, points.first(), points[1]),
        endpoint(visual.style.targetLabel, visual.style.targetMarker, points.last(), points[points.lastIndex - 1]))
}

internal fun DrawScope.drawDiagramEdge(visual: DiagramEdgeVisual, raw: List<Offset>, alpha: Float = 1f, emphasized: Boolean = false) {
    val points = cleanPoints(raw)
    if (points.size < 2) return
    val style = visual.style
    val color = if (emphasized) visual.colors.accent else visual.colors.tone(style.tone)
    val width = style.width * if (emphasized) 1.5f else 1f
    fun inset(tip: Offset, inside: Offset, marker: String): Offset {
        val length = hypot(inside.x - tip.x, inside.y - tip.y)
        val amount = if (marker in setOf("triangle", "diamond", "filled-diamond")) diagramMarkerLength(marker) else 0f
        return tip + direction(tip, inside) * minOf(amount, length / 2f)
    }
    val linePoints = points.toMutableList().apply {
        this[0] = inset(points[0], points[1], style.sourceMarker)
        this[lastIndex] = inset(points.last(), points[points.lastIndex - 1], style.targetMarker)
    }
    val path = Path().apply { moveTo(linePoints[0].x, linePoints[0].y); linePoints.drop(1).forEach { lineTo(it.x, it.y) } }
    val effect = when (style.stroke) {
        "dashed" -> PathEffect.dashPathEffect(floatArrayOf(7f, 5f))
        "dotted" -> PathEffect.dashPathEffect(floatArrayOf(2f, 4f))
        else -> null
    }
    drawPath(path, color, alpha = alpha, style = Stroke(width, pathEffect = effect))
    fun DrawScope.marker(name: String, tip: Offset, inside: Offset) {
        val vertices = diagramMarkerPoints(name, tip, inside)
        if (vertices.isEmpty()) return
        val shape = Path().apply { moveTo(vertices[0].x, vertices[0].y); vertices.drop(1).forEach { lineTo(it.x, it.y) }; if (name != "arrow") close() }
        if (name != "arrow") drawPath(shape, if (name == "filled-diamond") color else visual.colors.surface, alpha = alpha)
        drawPath(shape, color, style = Stroke(width), alpha = alpha)
    }
    marker(style.sourceMarker, points.first(), points[1])
    marker(style.targetMarker, points.last(), points[points.lastIndex - 1])
}

internal fun DrawScope.drawDiagramEdgeLabels(visual: DiagramEdgeVisual, points: List<Offset>, center: Offset? = null, alpha: Float = 1f) {
    diagramEdgeLabels(visual, points, center).forEach { label ->
        drawRect(visual.colors.surface, label.bounds.topLeft - Offset(2f, 0f), Size(label.bounds.width + 4f, label.bounds.height), alpha = alpha)
        drawText(label.text, topLeft = label.bounds.topLeft, alpha = alpha)
    }
}
