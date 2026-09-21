package validate

import model.YPath

/** 指摘の重大度（構造・意味の検証で共用）。 */
enum class Severity { ERROR, WARNING, INFO }

/**
 * 構造検証の指摘の種類（P27 段B の残り）: どの種類の構造の決まりに反したかを、文面とは別にデータとして持つ。
 * code は、指摘の出どころ（workspace-core の validate.IssueSource の kind）に写す名前。
 */
enum class StructuralIssueKind(val code: String) {
    /** 必須の項目が未設定。 */
    REQUIRED("required"),

    /** 値の形（オブジェクト・配列・Map・スカラー）や型（整数・数値・真偽値）が違う。 */
    TYPE("type"),

    /** 選択肢に無い値。 */
    ENUM("enum"),

    /** 数値の範囲（min / max）の外。 */
    RANGE("range"),

    /** 文字数（minLength / maxLength）の外。 */
    LENGTH("length"),

    /** 形式（pattern・日付の形式）に合わない。 */
    FORMAT("format"),

    /** スキーマに定義されていない項目。 */
    UNKNOWN_KEY("unknown-key"),

    /** 選択肢の値（cases）によって、いまは使えない項目。 */
    INACTIVE_KEY("inactive-key"),

    /** phases に定義されていないフェーズの申告。 */
    PHASE("phase"),
}

/**
 * 構造検証（単一文書×スキーマ）の指摘（3層コア移行 Phase 3・YM-005。計画書§4.1）。
 * 意味検証（参照解決・カバレッジ・グラフ整合）の指摘はルート側の `validate.Issue` のままとし、
 * 構造と意味を別の型にする（計画書 Phase 3 作業順9）。
 */
data class StructuralIssue(
    val severity: Severity,
    val message: String,
    val path: YPath,
    val kind: StructuralIssueKind,
)
