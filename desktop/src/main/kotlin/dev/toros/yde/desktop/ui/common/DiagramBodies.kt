package dev.toros.yde.desktop.ui.common

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.core.FileLocations
import dev.toros.yde.desktop.AppState
import dev.toros.yde.semantic.*
import dev.toros.yde.yaml.model.DocumentId
import kotlin.math.roundToInt

/** 画像の基準文書は値の来歴。定数の画像だけは記法定義から解決する。 */
internal data class DiagramImageKey(val document: DocumentId?, val source: String)
private fun DiagramRendering.imageKey(part: DiagramPart) = DiagramImageKey(location(part.path + "source")?.document ?: definition, part.source.orEmpty())

internal data class DiagramColors(
    val text: Color, val muted: Color, val accent: Color, val warning: Color,
    val error: Color, val surface: Color, val border: Color,
) {
    fun tone(name: String): Color = when (name) {
        "muted" -> muted; "accent" -> accent; "warning" -> warning; "error" -> error; else -> text
    }
}

internal fun diagramColors() = DiagramColors(Palette.Text, Palette.SubText, Palette.Primary,
    Palette.WarnOrange, Palette.ErrorRed, Palette.Surface, Palette.Border)

/** 計測結果を配置・描画・押した場所の判定で共有する。座標は親からの相対位置。 */
internal data class DiagramMeasured(
    val part: DiagramPart, val size: Size, val text: TextLayoutResult? = null,
    val children: List<Pair<Offset, DiagramMeasured>> = emptyList(),
) {
    fun hit(point: Offset): DiagramPart? {
        if (point.x !in 0f..size.width || point.y !in 0f..size.height) return null
        return children.asReversed().firstNotNullOfOrNull { (at, child) -> child.hit(point - at) } ?: part
    }
}

internal class DiagramBody(
    val rendering: DiagramRendering, val measured: DiagramMeasured,
    val images: Map<DiagramImageKey, LoadedImage>, val colors: DiagramColors,
) {
    val size get() = measured.size
    fun locationAt(point: Offset): DiagramLocation? = measured.hit(point)?.let { part ->
        rendering.location(part.path + if (part.kind == "image") "source" else "text")
    }
}

@Composable
internal fun <K> rememberDiagramBodies(renderings: Map<K, DiagramRendering>, state: AppState): Map<K, DiagramBody> {
    val measurer = rememberTextMeasurer()
    val colors = diagramColors()
    fun parts(part: DiagramPart): List<DiagramPart> = listOf(part) + part.children.flatMap(::parts)
    val keys = renderings.values.flatMap { r -> r.node?.let(::parts).orEmpty()
        .filter { it.kind == "image" }.map { r.imageKey(it) } }.distinct()
    val images = keys.associateWith { image ->
        key(image) {
            val workspace = state.workspace
            val policy = state.filePolicy
            val file = remember(workspace, image, policy) {
                if (workspace == null || image.document == null) null else FileLocations.readable(image.source, image.document, workspace, policy)
            }
            if (file == null) remember(image) { LoadedImage.Failed("画像の場所がないか、読み込みが許可されていません") } else rememberLoadedImage(file)
        }
    }
    return remember(renderings, images, colors, measurer) {
        renderings.mapNotNull { (key, r) -> r.node?.let { key to DiagramBody(r, measureDiagram(it, measurer, colors), images, colors) } }.toMap()
    }
}

internal fun measureDiagram(part: DiagramPart, measurer: TextMeasurer, colors: DiagramColors, available: Float = 360f): DiagramMeasured {
    val width = (part.width ?: available).coerceIn(1f, 2048f)
    if (part.kind == "text") {
        val text = measurer.measure(part.text, TextStyle(fontSize = 12.sp, color = colors.tone(part.tone)),
            overflow = TextOverflow.Ellipsis, maxLines = part.maxLines,
            constraints = Constraints(maxWidth = width.roundToInt().coerceAtLeast(1)))
        return DiagramMeasured(part, Size(part.width ?: text.size.width.toFloat().coerceAtLeast(1f),
            part.height ?: text.size.height.toFloat().coerceAtLeast(1f)), text)
    }
    if (part.kind in setOf("image", "icon")) return DiagramMeasured(part, Size(part.width ?: 64f, part.height ?: 64f))
    if (part.kind == "divider") return DiagramMeasured(part, Size(width, part.height ?: 1f))
    val inner = (width - part.padding * 2).coerceAtLeast(1f)
    val row = part.layout == "row"
    val childWidth = if (row) ((inner - part.gap * (part.children.size - 1)) / part.children.size.coerceAtLeast(1)).coerceAtLeast(1f) else inner
    val children = part.children.map { measureDiagram(it, measurer, colors, childWidth) }
    val gaps = part.gap * (children.size - 1).coerceAtLeast(0)
    val contentWidth = if (row) children.sumOf { it.size.width.toDouble() }.toFloat() + gaps else children.maxOfOrNull { it.size.width } ?: 0f
    val contentHeight = if (row) children.maxOfOrNull { it.size.height } ?: 0f else children.sumOf { it.size.height.toDouble() }.toFloat() + gaps
    val size = Size(part.width ?: (contentWidth + part.padding * 2).coerceIn(1f, width),
        part.height ?: (contentHeight + part.padding * 2).coerceIn(1f, 2048f))
    var cursor = part.padding
    val placed = children.map { child ->
        val spare = if (row) size.height - part.padding * 2 - child.size.height else size.width - part.padding * 2 - child.size.width
        val cross = part.padding + when (part.align) { "center" -> spare / 2; "end" -> spare; else -> 0f }.coerceAtLeast(0f)
        val offset = if (row) Offset(cursor, cross) else Offset(cross, cursor)
        cursor += (if (row) child.size.width else child.size.height) + part.gap
        offset to child
    }
    return DiagramMeasured(part, size, children = placed)
}

/** 同じ計測結果を画面と画像出力に描く。枠で切り抜くので長い文字や画像が隣のノードへ漏れない。 */
internal fun DrawScope.drawDiagram(body: DiagramBody, at: Offset = Offset.Zero, alpha: Float = 1f) {
    val colors = body.colors
    fun DrawScope.drawPart(measured: DiagramMeasured) {
        val part = measured.part
        val s = measured.size
        val color = colors.tone(part.tone)
        clipRect(0f, 0f, s.width, s.height) {
            when (part.kind) {
                "box" -> {
                    val border = if (part.tone == "default") colors.border else color
                    when (part.shape) {
                        "rounded" -> {
                            drawRoundRect(colors.surface, size = s, cornerRadius = CornerRadius(8f), alpha = alpha)
                            drawRoundRect(border, size = s, cornerRadius = CornerRadius(8f), style = Stroke(1.5f), alpha = alpha)
                        }
                        "diamond" -> {
                            val path = Path().apply { moveTo(s.width / 2, 0f); lineTo(s.width, s.height / 2)
                                lineTo(s.width / 2, s.height); lineTo(0f, s.height / 2); close() }
                            drawPath(path, colors.surface, alpha = alpha); drawPath(path, border, style = Stroke(1.5f), alpha = alpha)
                        }
                        else -> { drawRect(colors.surface, size = s, alpha = alpha); drawRect(border, size = s, style = Stroke(1.5f), alpha = alpha) }
                    }
                }
                "text" -> measured.text?.let { drawText(it, alpha = alpha) }
                "divider" -> drawLine(colors.border, Offset(0f, s.height / 2), Offset(s.width, s.height / 2), alpha = alpha)
                "image", "icon" -> {
                    val image = if (part.kind == "image") body.images[body.rendering.imageKey(part)] else null
                    val painter = when (image) { is LoadedImage.Raster -> BitmapPainter(image.bitmap); is LoadedImage.Vector -> image.painter; else -> null }
                    if (painter != null && image != null && image.widthPx > 0 && image.heightPx > 0) {
                        val factor = if (part.fit == "cover") maxOf(s.width / image.widthPx, s.height / image.heightPx) else minOf(s.width / image.widthPx, s.height / image.heightPx)
                        val target = Size(image.widthPx * factor, image.heightPx * factor)
                        translate((s.width - target.width) / 2, (s.height - target.height) / 2) { with(painter) { draw(target, alpha = alpha) } }
                    } else {
                        val center = Offset(s.width / 2, s.height / 2)
                        val unit = minOf(s.width, s.height)
                        if (part.fallback == "person") {
                            drawCircle(color, unit * 0.15f, center - Offset(0f, unit * 0.19f), alpha = alpha)
                            drawRoundRect(color, center + Offset(-unit * 0.28f, unit * 0.02f), Size(unit * 0.56f, unit * 0.32f), CornerRadius(unit * 0.12f), alpha = alpha)
                        } else {
                            drawRect(color, Offset(3f, 3f), Size((s.width - 6).coerceAtLeast(1f), (s.height - 6).coerceAtLeast(1f)), style = Stroke(1f), alpha = alpha)
                            drawLine(color, Offset(3f, s.height - 3f), Offset(s.width - 3f, 3f), alpha = alpha)
                        }
                    }
                }
            }
            measured.children.forEach { (offset, child) -> translate(offset.x, offset.y) { drawPart(child) } }
        }
    }
    translate(at.x, at.y) { drawPart(body.measured) }
}
