package semantic

import model.DocumentId
import model.YNode
import schema.ConstraintExpect
import schema.ConstraintRule
import schema.ValueExpr

/**
 * 検査の結果（P27 段A。CALC-31）: 検査1件を要素1つについて評価した結果は、次の4つのどれかになる。
 * 指摘が出るのは「違反」だけ。残りの3つは、これまでどれも「指摘なし」として区別されていなかった。
 * - 違反: 検査に反している
 * - 充足: 確かめて、問題が無かった
 * - 保留: データの不備（相手の見つからない参照など）のために、充足とも違反とも決められなかった（CALC-17 の判定保留）
 * - 対象外: この要素・この時点では、確かめるものが無い（検査の対象でない・フェーズ未到達・比べる値が書かれていない など）
 */
enum class CheckOutcome(val label: String) {
    VIOLATED("違反"),
    SATISFIED("充足"),
    WITHHELD("保留"),
    NOT_APPLICABLE("対象外"),
}

/** 充足とも違反とも言わなかった理由（保留・対象外の内訳）。text は利用者に見せる説明。 */
enum class CheckReason(val outcome: CheckOutcome, val text: String) {
    /** 解を探す途中で、相手の見つからない参照に出会った（足りないのか、参照の書き間違いなのかを決められない）。 */
    UNRESOLVED_REFERENCE(CheckOutcome.WITHHELD, "辿る途中に相手の見つからない参照があり、確かめられませんでした"),

    /** 値は書かれているが、大小の比較で数値として読めない。 */
    INCOMPARABLE_VALUE(CheckOutcome.WITHHELD, "比べる値を数値として読めません"),

    /** 起点の条件（型・スキーマ・ID のパターン・値の条件）に当たらない。 */
    NOT_IN_SCOPE(CheckOutcome.NOT_APPLICABLE, "この検査の対象の要素ではありません"),

    /** activeFrom のフェーズに達していない（呼び出し側が、文書のフェーズから決める）。 */
    PHASE_NOT_REACHED(CheckOutcome.NOT_APPLICABLE, "判定を始めるフェーズに達していません"),

    /** 比べる値が書かれていない（任意の項目を比べる検査では、ふつうのこと）。 */
    NO_VALUE(CheckOutcome.NOT_APPLICABLE, "比べる値が書かれていません"),

    /** 比べる組（解）が1つも無い。 */
    NO_SOLUTION(CheckOutcome.NOT_APPLICABLE, "比べる組がありません"),

    /** 「すべての要素について」の相手の集まりが空。 */
    EMPTY_COLLECTION(CheckOutcome.NOT_APPLICABLE, "調べる相手の集まりが空です"),

    /** 専用チェッカーの前提が宣言されていない（遷移検証の開始・利用側の遷移など）。 */
    NOT_DECLARED(CheckOutcome.NOT_APPLICABLE, "この種類の判定に必要な宣言がありません"),
}

/**
 * クエリの検査1件 × 要素1つの評価の結果。結果（outcome）と、文面・対応を作るための根拠を持つ。
 * 根拠は判定の種類ごと: check＝条件を満たさなかった組（violations）／expect・count＝見つかった解（matches）／
 * forAll＝解のなかった要素（forAll）。
 */
data class SemanticRuleResult(
    val outcome: CheckOutcome,
    val reason: CheckReason? = null,
    val violations: List<SemanticCheckViolation> = emptyList(),
    val matches: SemanticQueryResult? = null,
    val forAll: SemanticForAllResult? = null,
) {
    companion object {
        fun skipped(reason: CheckReason): SemanticRuleResult = SemanticRuleResult(reason.outcome, reason)
    }
}

/**
 * クエリの検査の判定（P27 段A）: 「どの結果になるか」を決める唯一の場所。文面の組み立てと配り方は呼び出し側（今は root の
 * validate.RuleDiagnostics）が持つ。判定の保留の規則（CALC-17）——解の不足を理由とする違反だけを保留にし、
 * 見つかった解が根拠になる違反（あってはならない解・上限の超過・条件を満たさない組）は保留にしない——もここに集める。
 * フェーズ（activeFrom）は文書の状態で決まるため、呼び出し側が先に判定する。
 */
object SemanticRules {

    fun evaluate(
        ids: Map<String, List<ScannedId>>,
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        rule: ConstraintRule,
        startId: String,
        /** 文書のワークスペース相対パス（値を読む文書の指定の fileGlob 判定に使う）。 */
        rel: (DocumentId) -> String = { it.value },
        /** 値を読む出現の固定（指摘を「値が書かれた場所」へ出す検査で、その場所の出現だけを調べるために使う）。 */
        pin: Pair<ValueExpr.ScopeRef, SemanticExpression.Occurrence>? = null,
        loader: (DocumentId) -> YNode?,
    ): SemanticRuleResult {
        if (!SemanticQuery.startMatches(ids, refs, graph, rule.spec, startId, loader)) {
            return SemanticRuleResult.skipped(CheckReason.NOT_IN_SCOPE)
        }
        if (rule.check.isNotEmpty()) {
            val run = SemanticQuery.checkRunFrom(ids, refs, graph, rule.spec, rule.check, startId, rel, pin, loader)
            return when {
                // 条件を満たさない組は、見つかった値が根拠（候補が失われていても違反は違反）
                run.violations.isNotEmpty() -> SemanticRuleResult(CheckOutcome.VIOLATED, violations = run.violations)
                // 失われた候補の中に、条件を満たさない組があったかもしれない
                run.unknownDropped -> SemanticRuleResult.skipped(CheckReason.UNRESOLVED_REFERENCE)
                run.incomparable -> SemanticRuleResult.skipped(CheckReason.INCOMPARABLE_VALUE)
                run.evaluated == 0 -> SemanticRuleResult.skipped(if (run.noValue) CheckReason.NO_VALUE else CheckReason.NO_SOLUTION)
                else -> SemanticRuleResult(CheckOutcome.SATISFIED)
            }
        }
        rule.forAll?.let { forAll ->
            val result = SemanticQuery.forAllFrom(ids, refs, graph, rule.spec, forAll, startId, rel, loader)
                ?: return SemanticRuleResult.skipped(CheckReason.EMPTY_COLLECTION)
            return when {
                result.missing.isNotEmpty() -> SemanticRuleResult(CheckOutcome.VIOLATED, forAll = result)
                result.undecided > 0 -> SemanticRuleResult(CheckOutcome.WITHHELD, CheckReason.UNRESOLVED_REFERENCE, forAll = result)
                else -> SemanticRuleResult(CheckOutcome.SATISFIED, forAll = result)
            }
        }
        val matches = SemanticQuery.matchesFrom(ids, refs, graph, rule.spec, startId, loader)
        val count = rule.count
        if (count != null) {
            val below = count.min != null && matches.total < count.min!!
            val above = count.max != null && matches.total > count.max!!
            return when {
                // 上限の超過は、見つかった解が根拠
                above -> SemanticRuleResult(CheckOutcome.VIOLATED, matches = matches)
                // 下限に足りないのは、解の不足が理由——候補が失われていたら決められない
                below && matches.unknownDropped -> SemanticRuleResult(CheckOutcome.WITHHELD, CheckReason.UNRESOLVED_REFERENCE, matches = matches)
                below -> SemanticRuleResult(CheckOutcome.VIOLATED, matches = matches)
                else -> SemanticRuleResult(CheckOutcome.SATISFIED, matches = matches)
            }
        }
        return when (rule.expect) {
            ConstraintExpect.EXISTS -> when {
                matches.total > 0 -> SemanticRuleResult(CheckOutcome.SATISFIED, matches = matches)
                matches.unknownDropped -> SemanticRuleResult(CheckOutcome.WITHHELD, CheckReason.UNRESOLVED_REFERENCE, matches = matches)
                else -> SemanticRuleResult(CheckOutcome.VIOLATED, matches = matches)
            }
            // あってはならない解は、見つかった解が根拠
            ConstraintExpect.ABSENT ->
                if (matches.total == 0) SemanticRuleResult(CheckOutcome.SATISFIED, matches = matches)
                else SemanticRuleResult(CheckOutcome.VIOLATED, matches = matches)
            null -> SemanticRuleResult(CheckOutcome.SATISFIED, matches = matches)
        }
    }
}
