package dev.toros.yde.workspace

import dev.toros.yde.semantic.CheckOutcome
import dev.toros.yde.semantic.CheckReason
import dev.toros.yde.semantic.FieldResolver
import dev.toros.yde.semantic.SemanticQuery
import dev.toros.yde.semantic.SemanticRules
import dev.toros.yde.semantic.SemanticSnapshot
import dev.toros.yde.semantic.schema.LifecycleDef
import dev.toros.yde.semantic.schema.StateDef
import dev.toros.yde.semantic.schema.TransitionCause
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YDocument
import dev.toros.yde.yaml.model.YNode

/** State は保持値ではなく、現在のデータに対する名前付きの真偽判定である。 */
enum class StateTruth { TRUE, FALSE, UNKNOWN, NOT_APPLICABLE }

data class StateReading(
    val stateId: String,
    val entityId: String,
    val truth: StateTruth,
    val reason: CheckReason? = null,
)

/** Action の変更候補と Lifecycle 宣言の整合。どの判定もデータを書き換えない。 */
enum class LifecycleVerdict { MATCHED, UNCHANGED, UNDECLARED, UNDETERMINED, INCONSISTENT }

data class LifecycleReview(
    val lifecycleId: String,
    val entityId: String,
    val actionId: String,
    val before: List<String>,
    val after: List<String>,
    val verdict: LifecycleVerdict,
    val message: String,
) {
    val needsAttention: Boolean get() = verdict in setOf(
        LifecycleVerdict.UNDECLARED, LifecycleVerdict.UNDETERMINED, LifecycleVerdict.INCONSISTENT,
    )
}

/**
 * 宣言済み State を既存の SemanticRules で評価し、Action 計画の前後と Lifecycle を照合する。
 * 判定対象は変更された文書のエンティティと明示的な受け手。参照を通じて間接的に
 * 状態が変わる別文書の全件検査は、ワークスペース全体の依存性解析とともに後続で扱う。
 */
object BehaviorRuntime {
    private class Reader(
        val session: WorkspaceSession,
        val semantic: SemanticSnapshot,
        val documents: Map<DocumentId, WorkspaceDocument>,
    ) {
        private val bySchema = session.schemas.associateBy { it.id }
        private val loader: (DocumentId) -> YNode? = { id -> documents[id]?.document?.documents?.firstOrNull() }
        private val fieldAt: FieldResolver = { id, path ->
            val document = documents[id]
            val root = document?.document?.documents?.firstOrNull()
            if (root == null) null else document.schemaId?.let { bySchema[it]?.tree?.fieldAt(root, path) }
        }

        fun entityType(id: String): String? = semantic.graph.entities[id]?.entityType
        fun exists(id: String): Boolean = id in semantic.graph.entities

        fun read(state: StateDef, id: String): StateReading {
            if (entityType(id) != state.entity) return StateReading(state.id, id, StateTruth.NOT_APPLICABLE)
            val result = SemanticRules.evaluate(
                semantic.ids, semantic.refs, semantic.graph, state.predicate, id,
                loader = loader, fieldAt = fieldAt,
            )
            // Constraint の where は「検査する候補の選択」でもあるため、値が一致しないと
            // NOT_IN_SCOPE になる。一方 State は同じ対象 entity に対する真偽の問い。
            // where を外した起点が対象なら「条件に一致しない」は FALSE と読む。
            // 適用対象外（entity / match 自体が違う）とは区別する。
            val predicateIsFalse = result.reason == CheckReason.NOT_IN_SCOPE &&
                state.predicate.spec.where.isNotEmpty() &&
                SemanticQuery.startMatches(
                    semantic.ids, semantic.refs, semantic.graph,
                    state.predicate.spec.copy(where = emptyList()), id,
                    fieldAt = fieldAt, loader = loader,
                )
            val truth = when (result.outcome) {
                CheckOutcome.SATISFIED -> StateTruth.TRUE
                CheckOutcome.VIOLATED -> StateTruth.FALSE
                CheckOutcome.WITHHELD -> StateTruth.UNKNOWN
                CheckOutcome.NOT_APPLICABLE -> if (predicateIsFalse) StateTruth.FALSE else StateTruth.NOT_APPLICABLE
            }
            return StateReading(state.id, id, truth, result.reason)
        }
    }

    /** この時点の entity に適用可能な State の真偽を返す。Lifecycle の有無を問わない。 */
    fun evaluate(session: WorkspaceSession, entityId: String): List<StateReading> {
        val reader = Reader(session, session.derived.semantic, session.snapshot.documents)
        val entity = reader.entityType(entityId) ?: return emptyList()
        return BehaviorChecks.compile(session.schemas).states.filter { it.entity == entity }.map { reader.read(it, entityId) }
    }

    /** 検証・自動値の適用後に固定した変更候補を用いる。変更予定のファイルは保存しない。 */
    fun review(session: WorkspaceSession, action: ActionRequest, prepared: PreparedChangeSet): List<LifecycleReview> {
        if (prepared.failures.isNotEmpty() || prepared.documents.isEmpty()) return emptyList()
        val catalog = BehaviorChecks.compile(session.schemas)
        if (catalog.lifecycles.isEmpty()) return emptyList()
        val overlays: Map<DocumentId, YDocument?> = prepared.documents.associate { change ->
            change.id to YDocument(change.id, listOf(YamlIO.parse(change.after)))
        }
        val afterDocuments = session.snapshot.documents.toMutableMap()
        overlays.forEach { (id, document) ->
            if (document != null) afterDocuments[id] = WorkspaceDocument(
                document, null, session.snapshot.documents[id]?.schemaId
                    ?: WorkspaceReader.assignSchemaId(id.value, document, session.schemas, session.layout),
                session.layout.inLedger(id.value),
            )
        }
        val before = Reader(session, session.derived.semantic, session.snapshot.documents)
        val after = Reader(session, session.derivedWith(overlays).semantic, afterDocuments)
        val touched = prepared.documents.map { it.id }.toSet()
        val ids = (before.semantic.ids.values.flatten() + after.semantic.ids.values.flatten())
            .asSequence().filter { it.document in touched }.map { it.id }.toMutableSet()
        action.target?.let { target -> if (before.exists(target) || after.exists(target)) ids += target }
        val states = catalog.states.associateBy { it.id }
        return catalog.lifecycles.flatMap { lifecycle ->
            ids.sorted().mapNotNull { id ->
                if (before.entityType(id) != lifecycle.entity && after.entityType(id) != lifecycle.entity) return@mapNotNull null
                val was = before.exists(id) && before.entityType(id) == lifecycle.entity
                val isNow = after.exists(id) && after.entityType(id) == lifecycle.entity
                val beforeRead = if (was) lifecycle.states.mapNotNull(states::get).map { before.read(it, id) } else emptyList()
                val afterRead = if (isNow) lifecycle.states.mapNotNull(states::get).map { after.read(it, id) } else emptyList()
                val from = beforeRead.filter { it.truth == StateTruth.TRUE }.map { it.stateId }
                val to = afterRead.filter { it.truth == StateTruth.TRUE }.map { it.stateId }
                if (was && isNow && from == to && id != action.target && from.size == 1 &&
                    beforeRead.none { it.truth == StateTruth.UNKNOWN } && afterRead.none { it.truth == StateTruth.UNKNOWN }) {
                    return@mapNotNull null // 同一文書内の無関係な要素の「変化なし」は表示しない
                }
                assess(lifecycle, id, action.name, was, isNow, beforeRead, afterRead)
            }
        }
    }

    private fun assess(
        lifecycle: LifecycleDef,
        entityId: String,
        actionId: String,
        was: Boolean,
        isNow: Boolean,
        beforeRead: List<StateReading>,
        afterRead: List<StateReading>,
    ): LifecycleReview {
        val from = beforeRead.filter { it.truth == StateTruth.TRUE }.map { it.stateId }
        val to = afterRead.filter { it.truth == StateTruth.TRUE }.map { it.stateId }
        fun result(verdict: LifecycleVerdict, message: String) =
            LifecycleReview(lifecycle.id, entityId, actionId, from, to, verdict, message)
        if ((was && beforeRead.any { it.truth == StateTruth.UNKNOWN }) ||
            (isNow && afterRead.any { it.truth == StateTruth.UNKNOWN })) {
            return result(LifecycleVerdict.UNDETERMINED, "参照や比較値が未確定のため State を判定できません")
        }
        if ((was && from.size != 1) || (isNow && to.size != 1)) {
            return result(LifecycleVerdict.INCONSISTENT,
                "Lifecycle に属する State は存在するエンティティにつき一つに特定する必要があります（前: ${from.size}、後: ${to.size}）")
        }
        if (was && !isNow) return result(LifecycleVerdict.UNDECLARED, "エンティティが消滅しましたが、この Lifecycle には削除の原因が宣言されていません")
        if (!was && isNow) {
            val allowed = lifecycle.entry.any { it.to == to.single() && it.by == TransitionCause.Action(actionId) }
            return if (allowed) result(LifecycleVerdict.MATCHED, "生成入口「${to.single()}」と Action が一致します")
            else result(LifecycleVerdict.UNDECLARED, "State「${to.single()}」への生成入口に Action「$actionId」が宣言されていません")
        }
        if (from == to) return result(LifecycleVerdict.UNCHANGED, "State「${from.single()}」は変化しません")
        val allowed = lifecycle.transitions.any { it.from == from.single() && it.to == to.single() && it.by == TransitionCause.Action(actionId) }
        return if (allowed) result(LifecycleVerdict.MATCHED, "${from.single()} → ${to.single()} は Action「$actionId」に宣言されています")
        else result(LifecycleVerdict.UNDECLARED, "${from.single()} → ${to.single()} を引き起こす Action「$actionId」が宣言されていません")
    }
}
