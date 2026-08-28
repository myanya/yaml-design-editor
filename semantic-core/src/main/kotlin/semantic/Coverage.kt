package semantic

import schema.CoverageRule
import schema.RefTarget

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
 * カバレッジ判定コア（R7-02。B-3・RB-03a で root validate.Validator から移設＝§17-⑧ 検証族の第一弾）。
 * 数え上げはグラフ整合（SemanticGraphIntegrity。R21-01）と共有する規則:
 * - 参照ベース（requiredBy）: この ID へのエンティティ参照の入次数（relation / 参照元スキーマで絞り込み）
 * - 出現ベース（detailIn）: この ID の、指定スキーマの文書への出現数（詳細文書の存在。R7-02）
 * activeFrom は「ID を定義する文書のフェーズ到達」で判定を保留する。フェーズの現在位置
 * （currentPhaseIndex）は文書ツリーを知る上位層が解決して渡す（null = 到達不明＝常時判定）。
 */
object SemanticCoverage {

    /**
     * 指定IDへの参照（入次数エッジ）の絞り込み。カバレッジ（requiredBy）と多重度（in）の共有カウンタ。
     * inbound はこの ID への全被参照（並びは呼び出し側の索引順のまま保つ）。
     */
    fun inboundRefs(
        inbound: List<ScannedRef>,
        relation: String? = null,
        fromSchemaIds: List<String> = emptyList(),
    ): List<ScannedRef> = inbound.filter { ref ->
        ref.targetKind == RefTarget.ENTITY &&
            (relation == null || ref.relation == relation) &&
            (fromSchemaIds.isEmpty() || ref.sourceSchemaId in fromSchemaIds)
    }

    /** 指定IDの、指定スキーマ群の文書への出現数。カバレッジ（detailIn）の共有カウンタ。 */
    fun occurrenceCount(occurrences: List<ScannedId>, schemaIds: List<String>): Int =
        occurrences.count { it.schemaId in schemaIds }

    /**
     * ID 1件に適用されるルールの判定（旧 Validator.coverageStatuses の判定コア）。
     * - occurrences: この ID の全出現／inbound: この ID への全被参照
     * - phaseOrder / currentPhaseIndex: activeFrom 判定（phases 未宣言は phaseOrder 空＝常時判定）
     */
    fun statuses(
        id: String,
        rules: List<CoverageRule>,
        occurrences: List<ScannedId>,
        inbound: List<ScannedRef>,
        phaseOrder: List<String> = emptyList(),
        currentPhaseIndex: Int? = null,
    ): List<SemanticCoverageStatus> = rules.mapNotNull { rule ->
        if (rule.idPattern != null && !idMatchesPattern(id, rule.idPattern)) return@mapNotNull null
        val count = if (rule.isOccurrence) {
            occurrenceCount(occurrences, rule.detailSchemaIds)
        } else inboundRefs(inbound, rule.relation, rule.fromSchemaIds).size
        val active = rule.activeFrom?.let { from ->
            val fromIdx = phaseOrder.indexOf(from)
            fromIdx < 0 || currentPhaseIndex == null || currentPhaseIndex >= fromIdx
        } ?: true
        SemanticCoverageStatus(rule, id, count, active)
    }
}
