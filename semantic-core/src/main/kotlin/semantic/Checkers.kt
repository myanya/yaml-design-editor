package semantic

import schema.CheckerArgs
import schema.FlowArgs

/**
 * 専用チェッカーの指摘1件（SP-13。宣言は schema/Checker.kt）。
 * - kind: 指摘の種類（チェッカーごとの一覧 CheckerKind.findingKinds のどれか）
 * - subjectId: 対象の要素の ID（指摘の文面の `{id}`。要素を定義した場所へ報告するチェッカーの報告先）
 * - ref: 指摘に付いた参照（参照が書かれた場所へ報告するチェッカーの報告先）
 * - related: 関連するノード（文面の `{related}` `{count}` と、ジャンプ導線）
 * - values: チェッカーが返す値（文面に埋め込める。循環の検出の `path` など）
 */
data class SemanticCheckerFinding(
    val kind: String,
    val subjectId: String,
    val ref: ScannedRef? = null,
    val related: List<SemanticFlowNode> = emptyList(),
    val values: Map<String, String> = emptyMap(),
)

/**
 * 専用チェッカーの実行（SP-13）。渡す値の型で、どのチェッカーかが決まる。
 * どのチェッカーも、グラフと全参照だけを入力にした純粋で決定的な関数で、判定を保留する規則（CALC-17）を自分で持つ。
 */
object SemanticCheckers {

    fun findings(graph: SemanticEntityGraph, refs: List<ScannedRef>, args: CheckerArgs): List<SemanticCheckerFinding> = when (args) {
        is FlowArgs -> SemanticFlow.findings(graph, refs, args).map { f ->
            SemanticCheckerFinding(
                kind = when (f.kind) {
                    SemanticFlowFindingKind.UNREACHABLE -> "unreachable"
                    SemanticFlowFindingKind.DEAD_END -> "deadEnd"
                    SemanticFlowFindingKind.UNDECLARED -> "undeclared"
                },
                subjectId = f.nodeId,
                related = f.related,
            )
        }
    }
}
