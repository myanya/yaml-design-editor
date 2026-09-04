package semantic

import schema.MatrixRule
import schema.QueryNodeStep

/**
 * 網羅マトリクス検証のカーネル（R28-03。宣言は schema/Matrix.kt）。
 * SemanticEntityGraph 上で「行 × 列の全組合せに証人が存在するか」を判定する。
 * - 母集団は型つきエンティティのみ（型未宣言・未解決参照には黙る＝判定保留 §4.26）
 * - 決定性: 不足セルは列ID昇順
 */

/** 不足セルの列側（ID＋表示タイトル）。 */
data class SemanticMatrixColumn(val id: String, val title: String?)

object SemanticMatrix {

    /**
     * 行 rowId の不足セル（証人のない列。ID昇順）。
     * rowId が行の母集団に入らないとき・列の母集団が空のときは判定しない（空リスト）。
     */
    fun missingColumns(graph: SemanticEntityGraph, rule: MatrixRule, rowId: String): List<SemanticMatrixColumn> {
        if (!matches(graph, rule.rows, rowId)) return emptyList()
        val columns = graph.entities.keys.filter { matches(graph, rule.columns, it) }.sorted()
        if (columns.isEmpty()) return emptyList()
        val covered = mutableSetOf<String>()
        for (wid in graph.entities.keys) {
            if (!matches(graph, rule.witness.node, wid)) continue
            val outs = graph.outgoingRefs(wid)
            if (outs.none { it.relation == rule.witness.rowRelation && it.targetId == rowId }) continue
            outs.asSequence()
                .filter { it.relation == rule.witness.columnRelation }
                .forEach { covered += it.targetId }
        }
        return columns.filterNot { it in covered }
            .map { SemanticMatrixColumn(it, graph.entities[it]?.title) }
    }

    /** エンティティが選択（entity / schema / id）に一致するか（クエリの node ステップと同じ判定）。 */
    private fun matches(graph: SemanticEntityGraph, step: QueryNodeStep, id: String): Boolean {
        val node = graph.entities[id] ?: return false
        if (step.idPattern != null && !idMatchesPattern(id, step.idPattern)) return false
        return (step.entity == null || node.entityType == step.entity) &&
            (step.schema == null || node.occurrences.any { it.schemaId == step.schema })
    }
}
