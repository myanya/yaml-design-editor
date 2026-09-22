package core

import index.IdIndex
import index.Query
import model.YNode
import model.getAt
import model.pretty
import java.nio.file.Path

/**
 * グラフトラバース評価器（R19-03 / ARCHITECTURE §4.28）。
 * ID索引を型付きグラフ（ノード=論理ID・出現=多段定義、エッジ=参照）と見て、宣言的な経路パターン
 * （steps）で多段に辿り、到達ノードを段階的開示の粒度（title / summary / outline / full）で
 * テキスト化する。消費者はコンテキストレシピ（graph 項目）・エージェントツール（traverse）・CLI の3面で、
 * 評価器はこの1実装に閉じる（Display / AiHttp / Projection の流儀）。
 *
 * 決定性の規約（prompt caching・再現性のため）:
 * - フロンティアはID昇順（in 方向は参照元の座標順）に安定ソートし、limitPerHop 超過は「他n件」で打切る
 * - 訪問済みIDへは戻らない（循環停止）
 * - budgetChars 超過は「…（予算 n 文字で打切り）」で決定的に切る
 */

/** トラバースの1ステップ。follow 省略は「現在のフロンティアをそのまま開示」（先頭ステップ＝起点の開示に使う）。 */
data class TraverseStep(
    val follow: String? = null,     // out（参照を辿る）/ in（被参照を辿る）/ null（移動しない）
    val relation: String? = null,   // エッジの関係種別（type: ref の relation）で絞る
    val via: String? = null,        // エッジのキー名（viaKey）で絞る
    val schema: String? = null,     // 到達ノードの出現スキーマIDで絞る
    val emit: String = "summary",   // title / summary / outline / full
    val limitPerHop: Int = 10,
) {
    companion object {
        val FOLLOWS = setOf("out", "in")
        val EMITS = setOf("title", "summary", "outline", "full")

        /** YAML/JSON 由来の raw Map からのパース（レシピ・ツール・CLI で共用）。 */
        fun parse(raw: Any?, where: String): TraverseStep {
            val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります")
            m.keys.map { it.toString() }.firstOrNull { it !in setOf("follow", "relation", "via", "schema", "emit", "limitPerHop") }
                ?.let { error("$where: 未知のキーです: $it（使えるキー: follow, relation, via, schema, emit, limitPerHop）") }
            val follow = m["follow"]?.toString()?.takeIf { it.isNotBlank() }
            if (follow != null && follow !in FOLLOWS) error("$where.follow は out / in のいずれかです: $follow")
            val emit = m["emit"]?.toString()?.takeIf { it.isNotBlank() } ?: "summary"
            if (emit !in EMITS) error("$where.emit は title / summary / outline / full のいずれかです: $emit")
            val limit = (m["limitPerHop"] as? Number)?.toInt() ?: 10
            require(limit >= 1) { "$where.limitPerHop は1以上である必要があります" }
            return TraverseStep(
                follow = follow,
                relation = m["relation"]?.toString()?.takeIf { it.isNotBlank() },
                via = m["via"]?.toString()?.takeIf { it.isNotBlank() },
                schema = m["schema"]?.toString()?.takeIf { it.isNotBlank() },
                emit = emit,
                limitPerHop = limit,
            )
        }
    }
}

data class TraverseSpec(
    val start: String,
    val steps: List<TraverseStep>,
    val budgetChars: Int = 8_000,
) {
    companion object {
        /**
         * 省略時の既定経路: 起点の要約 → 参照先の要約。
         * steps は**連鎖**（各ステップは前ステップの到達ノードから辿る）なので、
         * 起点の被参照が欲しい場合は明示的に [{emit: ...}, {follow: in, ...}] を書く（1ホップなら related でも足りる）。
         */
        val DEFAULT_STEPS = listOf(
            TraverseStep(emit = "summary"),
            TraverseStep(follow = "out", emit = "summary"),
        )
    }
}

object Traverse {

    /** フロンティアの1ノード（論理ID）。 */
    private data class Node(val id: String)

    fun run(
        index: IdIndex,
        loader: (model.DocumentId) -> YNode?,
        spec: TraverseSpec,
    ): String {
        require(spec.steps.isNotEmpty()) { "steps には1ステップ以上が必要です" }
        spec.steps.forEachIndexed { i, step ->
            if (i > 0 && step.follow == null) error("steps[$i]: 2ステップ目以降には follow（out / in）が必要です")
        }
        val sb = StringBuilder()
        sb.appendLine("トラバース: ${spec.start}")
        if (index.occurrences(spec.start).isEmpty()) {
            return sb.append("ID「${spec.start}」は見つかりません").toString()
        }
        val visited = mutableSetOf(spec.start)
        var frontier = listOf(spec.start)
        var truncated = false

        loop@ for ((i, step) in spec.steps.withIndex()) {
            if (step.follow != null) {
                val (next, total) = neighbors(index, frontier, step, visited)
                visited += next
                val header = buildString {
                    append("== ステップ$i（").append(step.follow)
                    step.relation?.let { append("・関係: ").append(it) }
                    step.via?.let { append("・via: ").append(it) }
                    step.schema?.let { append("・schema: ").append(it) }
                    append("・emit: ").append(step.emit).append("）: ").append(total).append("件")
                    if (total > next.size) append("（先頭 ${next.size} 件を表示）")
                }
                sb.appendLine(header)
                if (next.isEmpty()) {
                    sb.appendLine("（該当なし）")
                    frontier = emptyList()
                    continue@loop
                }
                frontier = next
            } else {
                sb.appendLine("== ステップ$i（起点・emit: ${step.emit}）")
            }
            for (id in frontier) {
                val block = emitNode(index, loader, id, step.emit)
                if (sb.length + block.length > spec.budgetChars) {
                    truncated = true
                    break@loop
                }
                sb.append(block)
            }
        }
        if (truncated) sb.appendLine("…（予算 ${spec.budgetChars} 文字で打切り）")
        return sb.toString().trimEnd()
    }

    /**
     * フロンティアからエッジを1段辿る。戻り値は（安定ソート・limit適用後の到達ID列, 打切り前の総数）。
     * 隣接の展開はパターン照合エンジンと共有する（`index.Query.neighborIds`。R22-02③ / ARCHITECTURE §4.32）——
     * Traverse は visited・limitPerHop・emit 描画だけを担う「Query の上の emit レンダラ」。
     */
    private fun neighbors(
        index: IdIndex,
        frontier: List<String>,
        step: TraverseStep,
        visited: Set<String>,
    ): Pair<List<String>, Int> {
        val follow = step.follow ?: error("follow は out / in のいずれかです: ${step.follow}")
        val ids = Query.neighborIds(index, frontier, follow, step.relation, step.via, step.schema)
        val fresh = ids.filter { it !in visited }
        return fresh.take(step.limitPerHop) to fresh.size
    }

    /** 1ノードの開示ブロック。出現（多段定義）ごとに emit の粒度で描く。 */
    private fun emitNode(
        index: IdIndex,
        loader: (model.DocumentId) -> YNode?,
        id: String,
        emit: String,
    ): String = buildString {
        val occurrences = index.occurrences(id)
        val title = occurrences.firstNotNullOfOrNull { it.title }
        append("- ").append(id)
        title?.let { append(" — ").append(it) }
        appendLine()
        if (emit == "title") return@buildString
        occurrences.forEach { e ->
            append("  [").append(e.document.value).append(" :: ").append(e.path.pretty())
            e.schemaId?.let { append("  schema: ").append(it) }
            appendLine("]")
            val element = loader(e.document)?.let { getAt(it, e.path) } ?: return@forEach
            when (emit) {
                "summary" -> (element as? YNode.Map)?.entries?.asSequence()
                    ?.filter { (_, v) -> v is YNode.Scalar }
                    ?.take(6)
                    ?.forEach { (k, v) ->
                        val text = (v as YNode.Scalar).value?.toString().orEmpty().replace("\n", " ")
                        appendLine("    $k: ${if (text.length > 60) text.take(60) + "…" else text}")
                    }
                "outline" -> Disclosure.outline(element).lines().forEach { appendLine("    $it") }
                "full" -> Disclosure.nodeYaml(element, emptyList(), maxChars = 4_000).trimEnd().lines()
                    .forEach { appendLine("    $it") }
            }
        }
    }
}
