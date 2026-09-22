package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.ValueExpr
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.semantic.schema.CheckerArgs
import dev.toros.yde.semantic.schema.ConstraintRule
import java.util.IdentityHashMap

/**
 * 検査の評価の覚え書き（P27 段B）: **同じ走査結果（同じ索引）の上では、検査 × 要素の結果は1回だけ評価する**。
 *
 * 文書ごとの検証は、同じ検査を何度も評価する——報告先の文書で1回、同じ要素が書かれた他の文書で案内のために1回ずつ、
 * 直せる場所への案内では案内先の文書ごとにワークスペースの全要素について。全体を検証すると、これが規模の2乗の伸びになる
 * （BACKLOG UX-116 の実測）。結果をここに覚えておけば、どの文書の検証から聞かれても、評価は最初の1回だけで済む。
 *
 * 前提: 覚えた結果を使い回せるのは、値を読む loader の中身が、この走査結果と同じ内容である間だけ
 * （ディスクの索引とディスクの loader・未保存の編集を重ねた索引と未保存を優先する loader・ステージングの索引と
 * ステージングの loader）。走査結果が変われば、呼び出し側が新しい覚え書きを作る（索引の一式と同じ寿命にする）。
 * 検査（ConstraintRule）と、チェッカーへ渡す値（CheckerArgs）は、中身ではなく**同じもの（同一のインスタンス）**かどうかで見分ける
 * ——スキーマを読み込んだ時点で1つずつ作られ、中身の比較は重いため。
 */
class SemanticCheckSession(
    private val ids: Map<String, List<ScannedId>>,
    private val refs: List<ScannedRef>,
    private val graph: SemanticEntityGraph,
) {
    private val results = IdentityHashMap<ConstraintRule, HashMap<String, SemanticRuleResult>>()
    private val checkerRuns = IdentityHashMap<CheckerArgs, SemanticCheckerRun>()
    private val memos = IdentityHashMap<Any, HashMap<String, Any>>()

    /** クエリの検査 × 要素の結果（SemanticRules.evaluate。最初の1回だけ評価する）。 */
    @Synchronized
    fun result(
        rule: ConstraintRule,
        startId: String,
        rel: (DocumentId) -> String,
        loader: (DocumentId) -> YNode?,
    ): SemanticRuleResult = results.getOrPut(rule) { HashMap() }.getOrPut(startId) {
        SemanticRules.evaluate(ids, refs, graph, rule, startId, rel = rel, loader = loader)
    }

    /**
     * 値を読む出現を固定した評価（指摘を「値が書かれた場所」へ出す検査）。出現ごとに結果が違い、その文書の検証でしか
     * 聞かれないため、覚えない。
     */
    fun pinnedResult(
        rule: ConstraintRule,
        startId: String,
        pin: Pair<ValueExpr.ScopeRef, SemanticExpression.Occurrence>,
        rel: (DocumentId) -> String,
        loader: (DocumentId) -> YNode?,
    ): SemanticRuleResult = SemanticRules.evaluate(ids, refs, graph, rule, startId, rel = rel, pin = pin, loader = loader)

    /** 専用チェッカーの1回分（渡す値ごとに、最初の1回だけ実行する）。 */
    @Synchronized
    fun checkerRun(args: CheckerArgs): SemanticCheckerRun =
        checkerRuns.getOrPut(args) { SemanticCheckers.run(graph, refs, args) }

    /**
     * 検査に紐づく、呼び出し側の計算の覚え書き（例: その検査に違反している要素の一覧——直せる場所への案内が、
     * 案内先の文書ごとに全要素を調べ直さないために使う）。owner は同じものかどうかで見分ける。
     */
    @Synchronized
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> memo(owner: Any, slot: String, compute: () -> T): T =
        memos.getOrPut(owner) { HashMap() }.getOrPut(slot) { compute() } as T
}
