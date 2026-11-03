package dev.toros.yde.desktop.ui.common

import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.ceil

/** 負の位置・端ラベル・記号を含めた境界。画面と同じDrawScopeの描画を保存する。 */
internal fun diagramBounds(rectangles: List<Rect>, points: List<Offset> = emptyList()): Rect {
    val xs = rectangles.flatMap { listOf(it.left, it.right) } + points.map { it.x }
    val ys = rectangles.flatMap { listOf(it.top, it.bottom) } + points.map { it.y }
    return Rect((xs.minOrNull() ?: 0f) - 24f, (ys.minOrNull() ?: 0f) - 24f,
        (xs.maxOrNull() ?: 1f) + 24f, (ys.maxOrNull() ?: 1f) + 24f)
}

internal fun exportDiagramPng(path: Path, bounds: Rect, background: Color, density: Density, draw: DrawScope.() -> Unit) {
    val factor = minOf(1f, 8000f / maxOf(bounds.width, bounds.height).coerceAtLeast(1f))
    val width = ceil(bounds.width * factor).toInt().coerceAtLeast(1)
    val height = ceil(bounds.height * factor).toInt().coerceAtLeast(1)
    val bitmap = ImageBitmap(width, height)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) {
        drawRect(background)
        scale(factor, pivot = Offset.Zero) { translate(-bounds.left, -bounds.top) { draw() } }
    }
    val target = if (path.fileName.toString().lowercase().endsWith(".png")) path else path.resolveSibling(path.fileName.toString() + ".png")
    check(ImageIO.write(bitmap.toAwtImage(), "png", target.toFile())) { "PNGを書き出せませんでした" }
}
