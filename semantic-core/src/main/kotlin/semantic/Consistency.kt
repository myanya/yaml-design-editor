package semantic

import model.DocumentId
import model.YNode
import model.YPath
import model.getAt
import model.pretty
import model.scalarText
import schema.ConsistencyAssert
import schema.ConsistencyRule
import validate.Severity

/** 整合検証の指摘1件（来歴つき。root validate.Issue + IssueDerivation はこの写し）。 */
data class SemanticConsistencyFinding(
    val severity: Severity,
    val message: String,
    /** 指摘座標（出現要素のパス＋検査パス）。 */
    val path: YPath,
    /** 来歴（R16-03c）: 導出値・元値（テンプレート）・導出規則の座標・入力値（UIの「導出値を適用」導線用）。 */
    val expected: String,
    val raw: String?,
    val sourceDocument: DocumentId?,
    val sourcePath: YPath?,
    val inputs: List<SemanticDerivation.Input>,
)

/**
 * 整合検証（R16-03a/b: ID要素の宣言値が導出値と assert の関係を満たすこと）の判定コア。
 * B-3（RB-05）で root validate.Validator から移設（§17-⑧ 検証族）。導出の解決は SemanticDerivation
 * （Phase 6D 移設済み）を使い、判定保留の哲学（§4.26: 未記入・導出不能・数値でない比較は黙る）を保つ。
 * activeFrom は自文書のフェーズ到達で判定を保留する（カバレッジ SemanticCoverage と同じ規則）。
 */
object SemanticConsistency {

    fun check(
        rules: List<ConsistencyRule>,
        /** この文書の ID 出現（指摘の anchor）。 */
        localIds: List<ScannedId>,
        /** この文書のツリー。 */
        root: YNode?,
        phaseOrder: List<String> = emptyList(),
        currentPhaseIndex: Int? = null,
        /** 対象IDの出現の解決（索引。上位層が注入する）。 */
        occurrences: (String) -> List<ScannedId>,
        rel: (DocumentId) -> String,
        tree: (DocumentId) -> YNode?,
    ): List<SemanticConsistencyFinding> {
        val docRoot = root ?: return emptyList()
        val findings = mutableListOf<SemanticConsistencyFinding>()
        for (rule in rules) {
            // activeFrom: カバレッジと同じく自文書のフェーズ到達まで判定を保留する（共有実装 R29-03）
            if (!phaseActive(rule.activeFrom, phaseOrder, currentPhaseIndex)) continue
            for (entry in localIds) {
                val rulePattern = rule.idPattern
                if (rulePattern != null && !idMatchesPattern(entry.id, rulePattern)) continue
                val element = getAt(docRoot, entry.path) ?: continue
                // 検査する値が未記入なら判定しない（未記入は required の領分。二重計上を避ける）
                val actual = scalarText(getAt(element, rule.path)) ?: continue
                val result = SemanticDerivation.resolve(
                    def = rule.expected,
                    startId = entry.id,
                    occurrences = occurrences,
                    rel = rel,
                    tree = tree,
                    startOccurrences = occurrences(entry.id),
                )
                val expected = result.value
                // 期待値が評価できない（写像行なし・プレースホルダ未解決）ときは判定保留（§4.26）
                if (expected == null || !result.complete) continue
                // 判定（R16-03b）: equals は等値、lte / gte は数値としての包含（数値でなければ判定保留）。
                // 要素述語の評価は check 判定述語と共有する（SemanticExpression.compare。R29-05）
                val satisfied: Boolean? = SemanticExpression.compare(rule.assert.op, actual, expected)
                if (satisfied == false) {
                    val severity = rule.level.toSeverity()
                    val relationText = when (rule.assert) {
                        ConsistencyAssert.EQUALS -> "導出値「$expected」と一致しません"
                        ConsistencyAssert.LTE -> "導出値「$expected」以下である必要があります"
                        ConsistencyAssert.GTE -> "導出値「$expected」以上である必要があります"
                    }
                    findings += SemanticConsistencyFinding(
                        severity = severity,
                        message = "「${rule.path.pretty()}」の値「$actual」は$relationText" +
                            (rule.description?.let { " — $it" } ?: ""),
                        path = entry.path + rule.path,
                        expected = expected,
                        raw = result.raw,
                        sourceDocument = result.sourceDocument,
                        sourcePath = result.sourcePath,
                        inputs = result.inputs,
                    )
                }
            }
        }
        return findings
    }
}
