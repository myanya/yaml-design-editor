package dev.toros.yde.desktop.ui.common

import dev.toros.yde.desktop.DiagramGrid
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/** 図の px 座標を、親の内容領域を原点とする格子へ合わせる。境界でも格子点を保つ。 */
fun DiagramGrid.position(point: LensPoint, density: Float, frame: DiagramDragFrame): LensPoint {
    val step = spacing * density
    fun axis(value: Float, origin: Float, min: Float, max: Float): Float {
        if (!enabled) return value.coerceIn(min, max)
        val low = ceil((min - origin) / step)
        val high = floor((max - origin) / step)
        // 枠のほうが狭く格子点が存在しない場合は、所属する枠を優先する。
        if (low > high) return value.coerceIn(min, max)
        return origin + round((value - origin) / step).coerceIn(low, high) * step
    }
    return LensPoint(axis(point.x, frame.origin.x, frame.minX, frame.maxX),
        axis(point.y, frame.origin.y, frame.minY, frame.maxY))
}

/** 原点と、ノード左上が動ける範囲。最外層は負の座標も許す。 */
data class DiagramDragFrame(
    val origin: LensPoint = LensPoint(0f, 0f),
    val minX: Float = Float.NEGATIVE_INFINITY, val minY: Float = Float.NEGATIVE_INFINITY,
    val maxX: Float = Float.POSITIVE_INFINITY, val maxY: Float = Float.POSITIVE_INFINITY,
)

/** 丸め前の移動を蓄積し、小さなポインタ移動が吸着で消えないようにする。 */
class DiagramDrag(start: LensPoint) {
    private var raw = start

    fun move(dx: Float, dy: Float, zoom: Float, grid: DiagramGrid, density: Float, frame: DiagramDragFrame): LensPoint {
        raw = LensPoint((raw.x + dx / zoom).coerceIn(frame.minX, frame.maxX),
            (raw.y + dy / zoom).coerceIn(frame.minY, frame.maxY))
        return grid.position(raw, density, frame)
    }
}

/** 背景の格子は各内容領域で原点を切り替える。箱と見出しには点を重ねない。 */
data class DiagramGridRegion(val origin: LensPoint, val bounds: LensBox? = null, val excluded: List<LensBox> = emptyList())
