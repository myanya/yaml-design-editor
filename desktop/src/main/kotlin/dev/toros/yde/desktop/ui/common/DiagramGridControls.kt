package dev.toros.yde.desktop.ui.common

import dev.toros.yde.desktop.DiagramGrid
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlin.math.ceil
import kotlin.math.floor

/** 配置の変更とは独立した、図の編集補助の切替。 */
@Composable
fun DiagramGridControls(grid: DiagramGrid, enabled: Boolean, onChange: (DiagramGrid) -> Unit) {
    TextButton(onClick = { onChange(grid.copy(enabled = !grid.enabled)) }, enabled = enabled,
        modifier = Modifier.testTag("diagram-grid-toggle").semantics { stateDescription = if (grid.enabled) "ON" else "OFF" }) {
        Text("グリッド吸着: ${if (grid.enabled) "ON" else "OFF"}")
    }
    Box {
        var open by remember { mutableStateOf(false) }
        TextButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag("diagram-grid-spacing")) {
            Text("間隔: ${grid.spacing}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DiagramGrid.SPACINGS.forEach { spacing ->
                DropdownMenuItem(text = { Text("$spacing dip") }, onClick = { open = false; onChange(grid.copy(spacing = spacing)) })
            }
        }
    }
}

/** 画面に見える点だけを描く。縮小時は格子点を間引き、吸着する間隔は変更しない。PNG には呼ばない。 */
fun DrawScope.drawDiagramGrid(grid: DiagramGrid, pan: Offset, zoom: Float, displayDensity: Float, regions: List<DiagramGridRegion>) {
    if (!grid.enabled || zoom <= 0f) return
    var step = grid.spacing * displayDensity * zoom
    while (step < 8f * displayDensity) step *= 2f
    fun rectangle(box: LensBox) = Rect(pan.x + box.x * zoom, pan.y + box.y * zoom,
        pan.x + (box.x + box.width) * zoom, pan.y + (box.y + box.height) * zoom)
    val color = Palette.SubText.copy(alpha = 0.25f)
    regions.forEach { region ->
        val bounds = region.bounds?.let(::rectangle) ?: Rect(0f, 0f, size.width, size.height)
        val left = maxOf(0f, bounds.left); val top = maxOf(0f, bounds.top)
        val right = minOf(size.width, bounds.right); val bottom = minOf(size.height, bounds.bottom)
        if (left >= right || top >= bottom) return@forEach
        val excluded = Path().apply { region.excluded.forEach { addRect(rectangle(it)) } }
        val origin = Offset(pan.x + region.origin.x * zoom, pan.y + region.origin.y * zoom)
        clipRect(left, top, right, bottom) {
            clipPath(excluded, ClipOp.Difference) {
                val x0 = ceil((left - origin.x) / step).toInt(); val x1 = floor((right - origin.x) / step).toInt()
                val y0 = ceil((top - origin.y) / step).toInt(); val y1 = floor((bottom - origin.y) / step).toInt()
                for (x in x0..x1) for (y in y0..y1) {
                    drawCircle(color, displayDensity, Offset(origin.x + x * step, origin.y + y * step))
                }
            }
        }
    }
}
