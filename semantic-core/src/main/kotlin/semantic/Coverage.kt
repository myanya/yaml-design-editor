package semantic

import schema.CoverageRule

/** カバレッジ判定1件の結果（カーネル版。root validate.CoverageStatus はこの写し）。 */
data class SemanticCoverageStatus(
    val rule: CoverageRule,
    val id: String,
    val count: Int,
    /** activeFrom のフェーズに達しているか。false のルールは検証エラーにせず「フェーズ未到達」として表示する。 */
    val active: Boolean = true,
) {
    val satisfied: Boolean get() = count >= rule.minCount
}

/**
 * カバレッジ判定コア（R7-02。B-3・RB-03a で root validate.Validator から移設）。
 * 出現ベース（detailIn）のみ: この ID の、指定スキーマの文書への出現数（詳細文書の存在）。
 * 出現集合（σ の定義域）への Aggregate であり、参照エッジの数え上げ（cardinality / constraints）
 * では書けない演算面（CALC-21）。参照ベース（requiredBy）は R29-01・D-16 で削除した。
 * activeFrom は「ID を定義する文書のフェーズ到達」で判定を保留する。フェーズの現在位置
 * （currentPhaseIndex）は文書ツリーを知る上位層が解決して渡す（null = 到達不明＝常時判定）。
 */
object SemanticCoverage {

    /** 指定IDの、指定スキーマ群の文書への出現数。 */
    fun occurrenceCount(occurrences: List<ScannedId>, schemaIds: List<String>): Int =
        occurrences.count { it.schemaId in schemaIds }

    /**
     * ID 1件に適用されるルールの判定（旧 Validator.coverageStatuses の判定コア）。
     * - occurrences: この ID の全出現
     * - phaseOrder / currentPhaseIndex: activeFrom 判定（phases 未宣言は phaseOrder 空＝常時判定）
     */
    fun statuses(
        id: String,
        rules: List<CoverageRule>,
        occurrences: List<ScannedId>,
        phaseOrder: List<String> = emptyList(),
        currentPhaseIndex: Int? = null,
    ): List<SemanticCoverageStatus> = rules.mapNotNull { rule ->
        if (rule.idPattern != null && !idMatchesPattern(id, rule.idPattern)) return@mapNotNull null
        val count = occurrenceCount(occurrences, rule.detailSchemaIds)
        SemanticCoverageStatus(rule, id, count, phaseActive(rule.activeFrom, phaseOrder, currentPhaseIndex))
    }
}
