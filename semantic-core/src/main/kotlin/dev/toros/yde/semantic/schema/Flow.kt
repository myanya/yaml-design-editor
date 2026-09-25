package dev.toros.yde.semantic.schema

/**
 * 遷移検証（フロー検証）の宣言（R28-04。architecture/graph-query.md「クエリコア」）。
 * ノード母集団（例: 画面）を定義するスキーマの `flow:` に列挙する。`flow` は組込み定義体（builtin-definitions/flow.yaml）が
 * 作る語彙で、検証の前に「専用チェッカー flow を使う検査」（schema/Checker.kt の CheckerRule）へ展開される（P29 Phase 6 第4段）。
 *
 * エッジ文書の各行（from 参照と to 参照が同じオブジェクト直下に並ぶ行）を有向エッジとみなし、
 * 宣言されたエッジ集合の上で到達閉包を計算して検証する（Query では書けない任意深さの到達性＝
 * matrix と同格の専用高水準演算。CORE-CALCULUS §21）:
 * - 到達不能（unreachable）: start から到達できないノード（start 宣言があるときのみ判定）
 * - 行き止まり（deadEnd）: 出るエッジがなく、terminal としても宣言されていないノード
 * - 宣言外遷移（undeclared）: usage エッジのうち、宣言エッジ集合にない組（usage 宣言があるときのみ判定）
 *
 * - ノード母集団の選択はクエリの node ステップと同じ語彙（entity / schema / id。bind は不可）
 * - エッジ・開始・終端・usage は relation で指定する（`{relation: ...}` の形＝
 *   関係改名リファクタリング（rename-relation）の追随がクエリと同じ walker で効く）
 * - 未解決参照でエッジ・開始・終端が失われたときは「解の不足を理由とする判定」を黙らせる（CALC-17）
 */

/** エッジの抽出宣言: 同じ行オブジェクト直下の from 参照と to 参照の組を1エッジとする。 */
data class FlowEdges(
    val fromRelation: String,
    val toRelation: String,
) {
    companion object {
        fun parse(raw: Any?, where: String): FlowEdges {
            val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります（{from: {relation: 関係名}, to: {relation: 関係名}}）")
            m.keys.map { it.toString() }.firstOrNull { it !in setOf("from", "to") }
                ?.let { error("$where: 未知のキーです: $it（使えるキー: from, to）") }
            return FlowEdges(
                fromRelation = parseFlowRelation(m["from"], "$where.from"),
                toRelation = parseFlowRelation(m["to"], "$where.to"),
            )
        }
    }
}

private fun parseFlowRelation(raw: Any?, where: String): String {
    val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります（{relation: 関係名}）")
    m.keys.map { it.toString() }.firstOrNull { it != "relation" }
        ?.let { error("$where: 未知のキーです: $it（使えるキー: relation）") }
    return m["relation"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.relation が必要です")
}

/** フローのノード母集団のパース: kind: entity 限定・bind 不可・条件は1つ以上。 */
private fun parseFlowSelector(raw: Any?, where: String): QueryNodeStep {
    val step = QueryNodeStep.parse(raw ?: error("$where が必要です"), where)
    require(step.kind == QueryNodeKind.ENTITY) { "$where: フローのノード選択は kind: entity のみです" }
    require(step.bind == null) { "$where: フローのノード選択に bind は書けません" }
    require(step.hasFilter) {
        "$where: entity / schema / id のいずれかで母集団を絞る必要があります"
    }
    return step
}

/**
 * 遷移検証のチェッカー（CheckerKind.FLOW）へ渡す値（P29 Phase 6 第4段。以前の FlowRule から、検査の一般の項目
 * ——id・説明・重大度・activeFrom——を除いたもの。それらは CheckerRule が持つ）。
 */
data class FlowArgs(
    /** ノード母集団（例 {entity: screen}）。到達不能・行き止まりはこの集合の各ノードについて判定する。 */
    val nodes: QueryNodeStep,
    /** 宣言エッジ（遷移の原本）の抽出。 */
    val edges: FlowEdges,
    /** 開始ノードを指す relation（省略時は到達不能の判定をしない）。 */
    val startRelation: String? = null,
    /** 終端として許容するノードを指す relation（行き止まり判定から除外）。 */
    val terminalRelation: String? = null,
    /** 宣言外遷移の検出対象（このエッジ集合 ⊆ 宣言エッジ を要求。省略時は判定しない）。 */
    val usage: FlowEdges? = null,
) : CheckerArgs {
    companion object {
        private val KEYS = setOf("nodes", "edges", "start", "terminal", "usage")

        fun parse(m: Map<*, *>, where: String): FlowArgs {
            m.keys.map { it.toString() }.firstOrNull { it !in KEYS }
                ?.let { error("$where: 未知のキーです: $it（使えるキー: ${KEYS.joinToString(", ")}）") }
            return FlowArgs(
                nodes = parseFlowSelector(m["nodes"], "$where.nodes"),
                edges = FlowEdges.parse(m["edges"] ?: error("$where.edges が必要です"), "$where.edges"),
                startRelation = m["start"]?.let { parseFlowRelation(it, "$where.start") },
                terminalRelation = m["terminal"]?.let { parseFlowRelation(it, "$where.terminal") },
                usage = m["usage"]?.let { FlowEdges.parse(it, "$where.usage") },
            )
        }
    }
}
