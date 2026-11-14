package dev.toros.yde.desktop.ui.view

import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import dev.toros.yde.app.view.flow.*
import dev.toros.yde.semantic.*
import dev.toros.yde.desktop.ui.common.*
import dev.toros.yde.yaml.model.YPath
import java.nio.file.Path as FilePath
import kotlin.math.sqrt

internal data class FlowScene(
    val model: FlowModel, val arrangement: FlowArrangement, val texts: Map<YPath, TextLayoutResult>,
    val bodies: Map<YPath, DiagramBody>, val edgeVisuals: Map<YPath, DiagramEdgeVisual>,
    val colors: DiagramColors, val selectedColor: Color,
    /** 線の中央のラベルを隠す（選んだ線のラベルは出す）。端のラベルは残す。 */
    val hideLabels: Boolean = false,
    /** 経由点を持つ線（ラベルは経由点を中心に置いたまま動かさない。ADR-0028）。 */
    val fixedLabels: Set<YPath> = emptySet(),
) {
    /** その線の中央のラベルを描くか。 */
    fun showsLabel(output: YPath, selected: YPath? = null) = !hideLabels || output == selected

    /** 描く端・中央のラベルの見え方（隠すときは中央を外す）。 */
    fun visualFor(output: YPath, selected: YPath? = null): DiagramEdgeVisual? =
        edgeVisuals[output]?.let { if (showsLabel(output, selected)) it else it.withoutCenterLabel() }
}

/** 画面とPNGは同じ計測結果と描画を使う。 */
internal fun DrawScope.drawFlow(scene: FlowScene, selected: YPath? = null) {
    val (model, arrangement, texts, bodies, edgeVisuals, colors, selectedColor) = scene
    val border = colors.border; val primary = colors.accent; val surface = colors.surface; val subtext = colors.muted
    arrangement.lanes.forEach { (path, r) ->
        // 選んだ枠（業務の流れの群の区画）は選択の色で
        if (path == selected) drawRect(primary, Offset(r.x, r.y), Size(r.width, r.height), style = Stroke(2f))
        else drawRect(border, Offset(r.x, r.y), Size(r.width, r.height), style = Stroke(1f))
        val header = arrangement.laneHeaders[path]
        texts[path]?.let { drawText(it, topLeft = Offset(header?.x ?: (r.x + 8f), header?.y ?: (r.y + 8f))) }
    }
    arrangement.edges.forEach { route ->
        val visual = edgeVisuals[route.output]
        if (visual != null) {
            drawDiagramEdge(visual, route.points.map { Offset(it.x, it.y) }, emphasized = route.output == selected)
            return@forEach
        }
        val color = if (route.output == selected) primary else subtext
        route.points.zipWithNext().forEach { (a, b) -> drawLine(color, Offset(a.x, a.y), Offset(b.x, b.y), 2f) }
        if (route.points.size >= 2) {
            val b = route.points.last(); val a = route.points[route.points.lastIndex - 1]
            val length = sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)).coerceAtLeast(1f)
            val dx = (b.x - a.x) / length; val dy = (b.y - a.y) / length
            drawLine(color, Offset(b.x, b.y), Offset(b.x - dx * 10 + dy * 5, b.y - dy * 10 - dx * 5), 2f)
            drawLine(color, Offset(b.x, b.y), Offset(b.x - dx * 10 - dy * 5, b.y - dy * 10 + dx * 5), 2f)
        }
    }
    // 後続の接続線で条件の文字を上書きしないよう、ラベルは線の後にまとめて描く。
    arrangement.edges.forEach { route ->
        val r = route.label
        val visual = scene.visualFor(route.output, selected)
        if (visual != null) {
            drawDiagramEdgeLabels(visual, route.points.map { Offset(it.x, it.y) }, Offset(r.center.x, r.center.y))
            return@forEach
        }
        if (!scene.showsLabel(route.output, selected)) return@forEach
        drawRect(if (route.output == selected) selectedColor else surface, Offset(r.x, r.y), Size(r.width, r.height))
        texts[route.output]?.let { drawText(it, topLeft = Offset(r.x + 6, r.y + 6)) }
    }
    model.nodes.forEach { n ->
        val r = arrangement.nodes.getValue(n.output)
        val fill = if (n.output == selected) selectedColor else surface
        val outline = if (n.output == selected) primary else border
        val offset = Offset(r.x, r.y); val size = Size(r.width, r.height)
        val body = bodies[n.output]
        if (body != null) drawDiagram(body, offset) else when (n.shape) {
            "diamond" -> {
                val path = Path().apply { moveTo(r.center.x, r.y); lineTo(r.right, r.center.y)
                    lineTo(r.center.x, r.bottom); lineTo(r.x, r.center.y); close() }
                drawPath(path, fill); drawPath(path, outline, style = Stroke(2f))
            }
            "rounded" -> {
                drawRoundRect(fill, offset, size, CornerRadius(20f))
                drawRoundRect(outline, offset, size, CornerRadius(20f), style = Stroke(2f))
            }
            else -> { drawRect(fill, offset, size); drawRect(outline, offset, size, style = Stroke(2f)) }
        }
        if (body != null && n.output == selected) drawRect(primary, offset, size, style = Stroke(3f))
        if (body == null) texts[n.output]?.let { drawText(it, topLeft = Offset(r.center.x - it.size.width / 2, r.center.y - it.size.height / 2)) }
    }

}

internal fun flowBounds(scene: FlowScene): Rect {
    val a = scene.arrangement
    val centers = a.edges.filter { scene.showsLabel(it.output) && it.output !in scene.edgeVisuals }.map { it.label }
    val boxes = (a.nodes.values + a.lanes.values + centers).map { Rect(it.x, it.y, it.right, it.bottom) }
    val labels = a.edges.flatMap { route -> scene.visualFor(route.output)?.let {
        diagramEdgeLabels(it, route.points.map { p -> Offset(p.x, p.y) }, Offset(route.label.center.x, route.label.center.y)).map { label -> label.bounds }
    }.orEmpty() }
    return diagramBounds(boxes + labels, a.edges.flatMap { it.points }.map { Offset(it.x, it.y) })
}

internal fun exportFlowPng(path: FilePath, scene: FlowScene, background: Color, density: Density) =
    exportDiagramPng(path, flowBounds(scene), background, density) { drawFlow(scene) }
