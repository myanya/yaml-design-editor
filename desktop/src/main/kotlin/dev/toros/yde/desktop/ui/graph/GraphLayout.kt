package dev.toros.yde.desktop.ui.graph

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 参照グラフの力学配置（R8-05: L-06。Fruchterman-Reingold の簡易実装）。
 * Compose 非依存の純ロジック。返す座標は [10, width-10]×[10, height-10] に収まる。
 * seed 固定で決定的（同じグラフなら同じ配置になり、再コンポーズで揺れない）。
 */
internal fun forceLayout(
    nodeCount: Int,
    edges: List<Pair<Int, Int>>,
    width: Float,
    height: Float,
    iterations: Int = 0,   // 0 = ノード数に応じて自動（大きいグラフほど回数を抑える）
    seed: Long = 1L,
): List<Pair<Float, Float>> {
    if (nodeCount <= 0) return emptyList()
    if (nodeCount == 1) return listOf(width / 2f to height / 2f)
    val rand = Random(seed)
    val xs = FloatArray(nodeCount) { 10f + rand.nextFloat() * (width - 20f) }
    val ys = FloatArray(nodeCount) { 10f + rand.nextFloat() * (height - 20f) }
    val k = sqrt(width * height / nodeCount)
    val iters = if (iterations > 0) iterations else (30_000 / nodeCount).coerceIn(50, 300)
    var temperature = maxOf(width, height) / 8f
    val cooling = temperature / (iters + 1)
    val dispX = FloatArray(nodeCount)
    val dispY = FloatArray(nodeCount)
    repeat(iters) {
        dispX.fill(0f)
        dispY.fill(0f)
        // 斥力（全ペア）
        for (i in 0 until nodeCount) {
            for (j in i + 1 until nodeCount) {
                var dx = xs[i] - xs[j]
                var dy = ys[i] - ys[j]
                if (dx * dx + dy * dy < 0.01f) {
                    dx = rand.nextFloat() - .5f
                    dy = rand.nextFloat() - .5f
                }
                val d = sqrt(dx * dx + dy * dy)
                val force = k * k / d
                dispX[i] += dx / d * force
                dispY[i] += dy / d * force
                dispX[j] -= dx / d * force
                dispY[j] -= dy / d * force
            }
        }
        // 引力（辺で結ばれたノード同士）
        for ((a, b) in edges) {
            if (a == b || a !in 0 until nodeCount || b !in 0 until nodeCount) continue
            val dx = xs[a] - xs[b]
            val dy = ys[a] - ys[b]
            val d = sqrt(dx * dx + dy * dy).coerceAtLeast(0.01f)
            val force = d * d / k
            dispX[a] -= dx / d * force
            dispY[a] -= dy / d * force
            dispX[b] += dx / d * force
            dispY[b] += dy / d * force
        }
        // 温度で変位を制限しつつ枠内に収める
        for (i in 0 until nodeCount) {
            val d = sqrt(dispX[i] * dispX[i] + dispY[i] * dispY[i]).coerceAtLeast(0.01f)
            val limited = minOf(d, temperature)
            xs[i] = (xs[i] + dispX[i] / d * limited).coerceIn(10f, width - 10f)
            ys[i] = (ys[i] + dispY[i] / d * limited).coerceIn(10f, height - 10f)
        }
        temperature -= cooling
    }
    return List(nodeCount) { xs[it] to ys[it] }
}
