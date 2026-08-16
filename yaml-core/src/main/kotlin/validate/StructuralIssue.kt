package validate

import model.YPath

/** 指摘の重大度（構造・意味の検証で共用）。 */
enum class Severity { ERROR, WARNING, INFO }

/**
 * 構造検証（単一文書×スキーマ）の指摘（3層コア移行 Phase 3・YM-005。計画書§4.1）。
 * 意味検証（参照解決・カバレッジ・グラフ整合）の指摘はルート側の `validate.Issue` のままとし、
 * 構造と意味を別の型にする（計画書 Phase 3 作業順9）。
 */
data class StructuralIssue(
    val severity: Severity,
    val message: String,
    val path: YPath,
)
