package dev.toros.yde.semantic.schema

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.scalarText

/** フェーズ1つ分の記入進捗（requiredFrom を持つフィールドの記入済み／総数）。 */
data class PhaseProgress(val phase: String, val filled: Int, val total: Int)

/**
 * フェーズ別の記入進捗（requiredFrom を持つフィールドの記入済み／総数）。phases 未宣言のスキーマでは空。
 * 未来フェーズの未記入はエラーではなくこの進捗として可視化する（architecture/schema.md「フェーズ」）。
 * 存在しないオブジェクト配下は総数に数えるが、存在しない配列要素・Map項目は数えない（構造が確定してから数える）。
 * B-3（RB-04）で root validate.Validator から移設（構造層の走査のため SchemaTree の拡張＝yaml-core）。
 */
fun SchemaTree.phaseProgress(root: YNode?): List<PhaseProgress> {
    val phases = phases ?: return emptyList()
    val filled = IntArray(phases.order.size)
    val total = IntArray(phases.order.size)
    fun present(node: YNode?): Boolean = when (node) {
        null -> false
        is YNode.Scalar -> scalarText(node) != null
        else -> true
    }
    fun walk(field: FieldDef, node: YNode?) {
        val f = resolve(field) ?: field
        f.requiredFrom?.let { from ->
            val idx = phases.order.indexOf(from)
            if (idx >= 0) {
                total[idx]++
                if (present(node)) filled[idx]++
            }
        }
        when (f.type) {
            FieldType.OBJECT -> {
                val map = node as? YNode.Map
                if (node != null && map == null) return
                for (ch in effectiveFields(f.fields, map).fields) {
                    val key = ch.key ?: continue
                    walk(ch, map?.entries?.get(key))
                }
            }
            FieldType.ARRAY -> (node as? YNode.Seq)?.items?.forEach { child -> f.item?.let { walk(it, child) } }
            FieldType.MAP -> (node as? YNode.Map)?.entries?.values?.forEach { v -> f.value?.let { walk(it, v) } }
            else -> Unit
        }
    }
    walk(this.root, root)
    return phases.order.mapIndexed { i, p -> PhaseProgress(p, filled[i], total[i]) }
}

/**
 * 現在フェーズと記入進捗の1行サマリ（UI・CLI・エージェントの表示で共用）。
 * `phases` 未宣言のスキーマでは null（呼び出し側は表示自体を省略する）。
 */
fun SchemaTree.phaseSummaryText(root: YNode?): String? {
    val phases = phases ?: return null
    val current = currentPhaseValue(root)?.takeIf { it in phases.order } ?: phases.order.firstOrNull()
    val parts = phaseProgress(root).filter { it.total > 0 }
        .joinToString(", ") { "${it.phase} ${it.filled}/${it.total}" }
    return "phase=$current" + (if (parts.isNotBlank()) "（$parts）" else "")
}
