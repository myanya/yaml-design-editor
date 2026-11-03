package dev.toros.yde.desktop.ui.graph

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.toros.yde.desktop.ui.common.*
import java.nio.file.Path as FilePath
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 配置に渡した辺（箱の添字）。描画は kind で矢じり（参照）・ひし形（保有）・点線（同じエンティティ）を描き分ける。
 * label は辺に添える文字（関係の label。逆向きに描く辺は inverseLabel）。
 */
internal data class LensArrangedEdge(val from: Int, val to: Int, val relation: String?, val kind: LensEdgeKind, val label: String?)

/**
 * 配置に渡した箱・辺と、その結果。箱の集合が変わって計算し直している間も、前の絵を描き続けるために一緒に持つ。
 * nested は配置に渡した箱（見出しの大きさと親）で、描画が見出しの高さと入れ子に使う。
 */
internal data class LensArranged(
    val nodes: List<LensVisibleNode>,
    val nested: List<LensNestedNode>,
    val edges: List<LensArrangedEdge>,
    val result: LensArrangement,
    val bodies: Map<String, DiagramBody> = emptyMap(),
)

/** 箱ごとの隣（箱の添字）とつながる辺（辺の添字）。ホバーの強調に使う。 */
internal data class LensAdjacency(val neighbors: List<Set<Int>>, val incident: List<Set<Int>>)

/** 描画に使う色（Palette から 1 度だけ読む。DrawScope の中で Palette を読まない）。 */
internal data class LensPaint(val border: Color, val surface: Color, val primary: Color, val subText: Color)

/** 描く材料の一式（画面の Canvas と PNG の書き出しで同じ描き方をする）。文字は箱の鍵で引く。 */
internal class LensScene(
    val arranged: LensArranged,
    val originKeys: Set<String>,
    val typeColorOf: (String) -> Color,
    val idTexts: Map<String, TextLayoutResult>,
    val titleTexts: Map<String, TextLayoutResult>,
    val subTexts: Map<String, TextLayoutResult>,
    val labelTexts: Map<String, TextLayoutResult>,
    val paint: LensPaint,
)

// ================================================================ 描画（画面と PNG で共通）

/**
 * 配置を描く。`zoom` は文字を出すかの判定に使う（縮小すると省く）。
 * `hovered` があれば、その箱と隣だけを濃く、他を薄くする。
 * 箱は親が先に並ぶので、そのまま順に描けば中の箱が上に乗る。開いた箱は見出しの下に薄い区画を持つ。
 */
internal fun DrawScope.drawLens(scene: LensScene, zoom: Float, hovered: Int?, adjacency: LensAdjacency) {
    val a = scene.arranged
    val result = a.result
    val paint = scene.paint
    val neighbors = hovered?.let { adjacency.neighbors.getOrNull(it) }
    val incident = hovered?.let { adjacency.incident.getOrNull(it) }
    fun edgeAlpha(i: Int) = if (incident == null) 1f else if (i in incident) 1f else 0.15f
    fun nodeAlpha(i: Int) = if (hovered == null) 1f else if (i == hovered || neighbors?.contains(i) == true) 1f else 0.25f
    // 箱: 型の色で縁取った箱に ID とタイトル。起点は太い縁、ホバー中は太く。開いた箱は塗りを薄くして中の箱を目立たせる
    result.boxes.forEachIndexed { i, b ->
        val node = a.nodes.getOrNull(i) ?: return@forEachIndexed
        val key = node.node.key
        val color = scene.typeColorOf(key)
        val alpha = nodeAlpha(i)
        val topLeft = Offset(b.x, b.y)
        val size = Size(b.width, b.height)
        val isOrigin = key in scene.originKeys
        val body = a.bodies[key]
        if (body == null || node.expanded || node.hiddenChildren > 0) {
            drawRoundRect(color.copy(alpha = if (i == hovered) 0.3f else if (node.expanded) 0.05f else 0.12f), topLeft, size, CornerRadius(8f), alpha = alpha)
            drawRoundRect(
                if (isOrigin) paint.primary else color, topLeft, size, CornerRadius(8f), alpha = alpha,
                style = Stroke(if (isOrigin || i == hovered) 3.5f else 1.5f),
            )
        }
        if (node.expanded) {
            val header = a.nested.getOrNull(i)?.height ?: 0f
            drawLine(color, Offset(b.x, b.y + header), Offset(b.x + b.width, b.y + header), strokeWidth = 1f, alpha = alpha * 0.6f)
        }
        if (body != null) {
            drawDiagram(body, Offset(b.x + if (node.expanded || node.hiddenChildren > 0) TOGGLE_ZONE else 0f, b.y), alpha)
            if (isOrigin || i == hovered) drawRect(if (isOrigin) paint.primary else color, topLeft, size, style = Stroke(3f), alpha = alpha)
            if (node.expanded || node.hiddenChildren > 0) {
                val x = b.x + 7f; val y = b.y + 8f
                val toggle = Path().apply { moveTo(x, y); lineTo(x + 7, y + if (node.expanded) 0 else 4)
                    lineTo(x + if (node.expanded) 3.5f else 0f, y + 8); close() }
                drawPath(toggle, paint.subText, alpha = alpha)
            }
        } else if (zoom >= 0.4f) {
            val idText = scene.idTexts[key] ?: return@forEachIndexed
            var y = b.y + 6f
            drawText(idText, topLeft = Offset(b.x + 10f, y), alpha = alpha)
            y += idText.size.height
            scene.titleTexts[key]?.let { drawText(it, topLeft = Offset(b.x + 10f, y), alpha = alpha); y += it.size.height }
            scene.subTexts[key]?.let { drawText(it, topLeft = Offset(b.x + 10f, y), alpha = alpha) }
        }
    }
    // 辺: 折れ線に、参照は矢じり・保有は親の側のひし形・同じエンティティは点線。ラベルは読める倍率のときだけ
    val dashed = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))
    result.routes.forEachIndexed { i, route ->
        val pts = route.points
        if (pts.size < 2) return@forEachIndexed
        val edge = a.edges.getOrNull(i)
        val emphasized = incident != null && i in incident
        val alpha = edgeAlpha(i)
        val lineColor = if (emphasized) paint.primary else paint.border
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
        }
        val strokeWidth = (if (emphasized) 2.2f else 1.2f) / zoom.coerceIn(0.3f, 1f)
        drawPath(
            path, lineColor, alpha = alpha,
            style = if (edge?.kind == LensEdgeKind.TIE) Stroke(strokeWidth, pathEffect = dashed) else Stroke(strokeWidth),
        )
        val markColor = if (emphasized) paint.primary else paint.subText
        when (edge?.kind) {
            LensEdgeKind.HOLDING -> {
                // 保有の線: 親の側（出発点）に塗りつぶしのひし形（UML の合成と同じ印）。矢じりは付けない
                val p0 = pts[0]
                val p1 = pts[1]
                val len0 = sqrt((p1.x - p0.x) * (p1.x - p0.x) + (p1.y - p0.y) * (p1.y - p0.y)).coerceAtLeast(1f)
                val ux = (p1.x - p0.x) / len0
                val uy = (p1.y - p0.y) / len0
                val diamond = Path().apply {
                    moveTo(p0.x, p0.y)
                    lineTo(p0.x + ux * 7 + uy * 4, p0.y + uy * 7 - ux * 4)
                    lineTo(p0.x + ux * 14, p0.y + uy * 14)
                    lineTo(p0.x + ux * 7 - uy * 4, p0.y + uy * 7 + ux * 4)
                    close()
                }
                drawPath(diamond, markColor, alpha = alpha)
            }
            LensEdgeKind.TIE -> Unit
            else -> {
                val p = pts[pts.size - 2]
                val q = pts.last()
                val length = sqrt((q.x - p.x) * (q.x - p.x) + (q.y - p.y) * (q.y - p.y)).coerceAtLeast(1f)
                val dx = (q.x - p.x) / length
                val dy = (q.y - p.y) / length
                val arrow = Path().apply {
                    moveTo(q.x, q.y)
                    lineTo(q.x - dx * 9 + dy * 4, q.y - dy * 9 - dx * 4)
                    lineTo(q.x - dx * 9 - dy * 4, q.y - dy * 9 + dx * 4)
                    close()
                }
                drawPath(arrow, markColor, alpha = alpha)
            }
        }
        if (zoom >= 0.5f) {
            val text = edge?.label?.let { scene.labelTexts[it] } ?: return@forEachIndexed
            val c = route.label ?: return@forEachIndexed
            val w = text.size.width.toFloat()
            val h = text.size.height.toFloat()
            drawRect(paint.surface, Offset(c.x - w / 2 - 2, c.y - h / 2), Size(w + 4, h), alpha = alpha)
            drawText(text, topLeft = Offset(c.x - w / 2, c.y - h / 2), alpha = alpha)
        }
    }
}

/** 配置の全体を等倍で PNG に書き出す（長辺は 8,000 px まで縮める）。 */
internal fun exportLensPng(path: FilePath, scene: LensScene, background: Color, density: Density) {
    val result = scene.arranged.result
    // 動かした箱が左上の外に出ていれば、その分だけ寄せて全部を入れる
    val points = result.routes.flatMap { it.points }
    val minX = minOf(0f, result.boxes.minOfOrNull { it.x } ?: 0f, points.minOfOrNull { it.x } ?: 0f)
    val minY = minOf(0f, result.boxes.minOfOrNull { it.y } ?: 0f, points.minOfOrNull { it.y } ?: 0f)
    val shiftX = if (minX < 0f) 24f - minX else 0f
    val shiftY = if (minY < 0f) 24f - minY else 0f
    val contentWidth = (result.width + shiftX).coerceAtLeast(1f)
    val contentHeight = (result.height + shiftY).coerceAtLeast(1f)
    val scale = minOf(1f, 8000f / maxOf(contentWidth, contentHeight))
    val width = (contentWidth * scale).roundToInt().coerceAtLeast(1)
    val height = (contentHeight * scale).roundToInt().coerceAtLeast(1)
    val bitmap = ImageBitmap(width, height)
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, Size(width.toFloat(), height.toFloat())) {
        drawRect(background)
        scale(scale, pivot = Offset.Zero) {
            translate(shiftX, shiftY) { drawLens(scene, 1f, null, LensAdjacency(emptyList(), emptyList())) }
        }
    }
    val target = if (path.fileName.toString().lowercase().endsWith(".png")) path else path.resolveSibling(path.fileName.toString() + ".png")
    ImageIO.write(bitmap.toAwtImage(), "png", target.toFile())
}
