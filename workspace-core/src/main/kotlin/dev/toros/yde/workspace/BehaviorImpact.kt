package dev.toros.yde.workspace

import dev.toros.yde.semantic.SemanticSnapshot
import dev.toros.yde.semantic.schema.RefTarget
import dev.toros.yde.yaml.model.DocumentId

/**
 * State が参照する別文書への波及候補。変更前後の双方の関係を無向にたどり、
 * 付け替え・削除で消えた関係も見落とさない（過大な候補は許す）。
 *
 * グラフ走査は O(V + E)。State の判定はこの候補集合だけで行う。
 * 利用者定義の式が参照関係を介さずに全ワークスペースを読む場合は対象外。
 */
internal object BehaviorImpact {
    fun affected(
        before: SemanticSnapshot,
        after: SemanticSnapshot,
        touched: Set<DocumentId>,
        receiver: String?,
    ): Set<String> {
        val edges = HashMap<String, MutableSet<String>>()
        val seeds = LinkedHashSet<String>()
        val touchedDocumentIds = (before.scans + after.scans)
            .filter { it.document in touched }.map { it.documentId }.toSet() + touched.map { it.value }

        fun connect(a: String, b: String) {
            if (a == b) return
            edges.getOrPut(a) { LinkedHashSet() }.add(b)
            edges.getOrPut(b) { LinkedHashSet() }.add(a)
        }

        fun index(snapshot: SemanticSnapshot) {
            val graph = snapshot.graph
            val idsInDocument = snapshot.ids.values.flatten().groupBy({ it.document }, { it.id })
            touched.forEach { document -> seeds += idsInDocument[document].orEmpty() }

            graph.holdings.forEach { connect(it.parent.id, it.child.id) }
            snapshot.refs.forEach { ref ->
                val sources = graph.sourceEntryOf(ref)?.let { listOf(it.id) }
                    ?: idsInDocument[ref.document].orEmpty()
                val targets = when (ref.targetKind) {
                    RefTarget.ENTITY -> listOf(ref.targetId)
                    RefTarget.DOCUMENT -> graph.documents[ref.targetId]?.entityIds.orEmpty()
                }
                sources.forEach { source -> targets.forEach { target -> connect(source, target) } }

                // 文書自身に ID がない場合でも、そこへの文書参照の書き手は候補に含める。
                if (ref.targetKind == RefTarget.DOCUMENT && ref.targetId in touchedDocumentIds) seeds += sources
            }
        }

        index(before)
        index(after)
        receiver?.let { if (it in before.graph.entities || it in after.graph.entities) seeds += it }
        val seen = LinkedHashSet(seeds)
        val pending = ArrayDeque(seeds)
        while (pending.isNotEmpty()) {
            edges[pending.removeFirst()].orEmpty().forEach { id ->
                if (seen.add(id)) pending.addLast(id)
            }
        }
        val known = before.graph.entities.keys + after.graph.entities.keys
        return seen.filterTo(LinkedHashSet()) { it in known }
    }
}
