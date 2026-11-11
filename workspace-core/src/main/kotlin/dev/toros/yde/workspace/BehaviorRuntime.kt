package dev.toros.yde.workspace

import dev.toros.yde.semantic.CheckOutcome
import dev.toros.yde.semantic.CheckReason
import dev.toros.yde.semantic.FieldResolver
import dev.toros.yde.semantic.SemanticQuery
import dev.toros.yde.semantic.SemanticRules
import dev.toros.yde.semantic.SemanticSnapshot
import dev.toros.yde.semantic.schema.LifecycleDef
import dev.toros.yde.semantic.schema.EventDef
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

/** 観測された Event の申告。事実そのものの検証・保存は呼び出し側の責任とする。 */
data class EventOccurrence(val eventId: String, val subjectId: String? = null)

/** 原因（Action／Event）の変更候補と Lifecycle 宣言の整合。どの判定もデータを書き換えない。 */
enum class LifecycleVerdict { MATCHED, UNCHANGED, UNDECLARED, UNDETERMINED, INCONSISTENT }

data class LifecycleReview(
    val lifecycleId: String,
    val entityId: String,
    val actionId: String,
    val before: List<String>,
    val after: List<String>,
    val verdict: LifecycleVerdict,
    val message: String,
    /** 変更文書の外にある Entity が、関係を介して影響候補となったか。 */
    val indirectlyAffected: Boolean = false,
    /** 原因の種類も保持する。actionId は既存の利用者向けに残す（Event の場合は Event ID）。 */
    val cause: TransitionCause = TransitionCause.Action(actionId),
) {
    val causeId: String get() = cause.id
    val needsAttention: Boolean get() = verdict in setOf(
        LifecycleVerdict.UNDECLARED, LifecycleVerdict.UNDETERMINED, LifecycleVerdict.INCONSISTENT,
    )
}

/**
 * 宣言済み State を既存の SemanticRules で評価し、Action／Event の前後と Lifecycle を照合する。
 * 判定対象は変更文書・受け手と、変更前後の参照／保有グラフ上でつながるエンティティ。
 * 関係を介さない全体集計など、任意の式の依存性解析は後続段階で扱う。
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

    /** Action の計画から生成された変更候補を検証する。既存の呼び出し経路を維持する。 */
    fun review(session: WorkspaceSession, action: ActionRequest, prepared: PreparedChangeSet): List<LifecycleReview> =
        reviewCause(session, TransitionCause.Action(action.name), action.target, prepared)

    /**
     * 観測された Event に付随する外部変更候補の State 遷移を、Action と同じ規則で検証する。
     * Event の発生は自動的に推測・生成しない。発生事実の取り込みと保存も行わない。
     */
    fun reviewEvent(session: WorkspaceSession, event: EventOccurrence, prepared: PreparedChangeSet): List<LifecycleReview> =
        reviewCause(session, TransitionCause.Event(event.eventId), event.subjectId, prepared)

    /** 差分が無い Event の申告も、種類・対象 ID・対象 Entity 型を確認できる。発生の真偽は判定しない。 */
    fun validateEvent(session: WorkspaceSession, event: EventOccurrence): EventDef {
        val matches = BehaviorChecks.compile(session.schemas).events.filter { it.id == event.eventId }
        require(matches.size == 1) { "Event「${event.eventId}」の定義は一つ必要です（実際: ${matches.size}）" }
        val definition = matches.single()
        require(definition.entity == null || event.subjectId != null) {
            "Event「${event.eventId}」には対象 entity「${definition.entity}」の subjectId が必要です"
        }
        event.subjectId?.let { id ->
            val type = session.derived.semantic.graph.entities[id]?.entityType
            require(type != null) { "Event「${event.eventId}」の対象「$id」が見つかりません" }
            require(definition.entity == null || definition.entity == type) {
                "Event「${event.eventId}」の対象「$id」の型が宣言された entity「${definition.entity}」と異なります"
            }
        }
        return definition
    }

    private fun reviewCause(
        session: WorkspaceSession,
        cause: TransitionCause,
        receiver: String?,
        prepared: PreparedChangeSet,
    ): List<LifecycleReview> {
        val catalog = BehaviorChecks.compile(session.schemas)
        val eventDef = if (cause is TransitionCause.Event) {
            val matches = catalog.events.filter { it.id == cause.id }
            require(matches.size == 1) { "Event「${cause.id}」の定義は一つ必要です（実際: ${matches.size}）" }
            matches.single()
        } else null
        if (eventDef?.entity != null) {
            require(receiver != null) { "Event「${cause.id}」には対象 entity「${eventDef.entity}」の subjectId が必要です" }
        }
        if (prepared.failures.isNotEmpty() || prepared.documents.isEmpty()) return emptyList()
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
        if (eventDef != null && receiver != null) {
            val types = listOfNotNull(before.entityType(receiver), after.entityType(receiver)).toSet()
            require(types.isNotEmpty()) { "Event「${cause.id}」の対象「$receiver」が見つかりません" }
            require(eventDef.entity == null || eventDef.entity in types) {
                "Event「${cause.id}」の対象「$receiver」の型が宣言された entity「${eventDef.entity}」と異なります"
            }
        }
        val touched = prepared.documents.map { it.id }.toSet()
        val ids = BehaviorImpact.affected(before.semantic, after.semantic, touched, receiver)
        val directlyTouched = (before.semantic.ids.values.flatten() + after.semantic.ids.values.flatten())
            .filter { it.document in touched }.map { it.id }.toSet()
        val states = catalog.states.associateBy { it.id }
        return catalog.lifecycles.flatMap { lifecycle ->
            ids.sorted().mapNotNull { id ->
                if (before.entityType(id) != lifecycle.entity && after.entityType(id) != lifecycle.entity) return@mapNotNull null
                val was = before.exists(id) && before.entityType(id) == lifecycle.entity
                val isNow = after.exists(id) && after.entityType(id) == lifecycle.entity
                val beforeRead = if (was) lifecycle.states.mapNotNull(states::get).map { before.read(it, id) } else emptyList()
                val afterRead = if (isNow) lifecycle.states.mapNotNull(states::get).map { after.read(it, id) } else emptyList()
                // 同じ関係成分にいるだけで、実際には State が変わらなかった別文書の
                // Entity を表示するとノイズになる。保留理由の変化は残す。
                if (id !in directlyTouched && id != receiver && was == isNow &&
                    beforeRead.map { it.truth to it.reason } == afterRead.map { it.truth to it.reason }) {
                    return@mapNotNull null
                }
                val from = beforeRead.filter { it.truth == StateTruth.TRUE }.map { it.stateId }
                val to = afterRead.filter { it.truth == StateTruth.TRUE }.map { it.stateId }
                if (was && isNow && from == to && id != receiver && from.size == 1 &&
                    beforeRead.none { it.truth == StateTruth.UNKNOWN } && afterRead.none { it.truth == StateTruth.UNKNOWN }) {
                    return@mapNotNull null // 影響候補でも State が変わらない無関係な要素は表示しない
                }
                assess(lifecycle, id, cause, was, isNow, beforeRead, afterRead)
                    .copy(indirectlyAffected = id !in directlyTouched)
            }
        }
    }

    private fun assess(
        lifecycle: LifecycleDef,
        entityId: String,
        cause: TransitionCause,
        was: Boolean,
        isNow: Boolean,
        beforeRead: List<StateReading>,
        afterRead: List<StateReading>,
    ): LifecycleReview {
        val from = beforeRead.filter { it.truth == StateTruth.TRUE }.map { it.stateId }
        val to = afterRead.filter { it.truth == StateTruth.TRUE }.map { it.stateId }
        val causeKind = if (cause is TransitionCause.Action) "Action" else "Event"
        fun result(verdict: LifecycleVerdict, message: String) =
            LifecycleReview(lifecycle.id, entityId, cause.id, from, to, verdict, message, cause = cause)
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
            val allowed = lifecycle.entry.any { it.to == to.single() && it.by == cause }
            return if (allowed) result(LifecycleVerdict.MATCHED, "生成入口「${to.single()}」と $causeKind が一致します")
            else result(LifecycleVerdict.UNDECLARED, "State「${to.single()}」への生成入口に $causeKind「${cause.id}」が宣言されていません")
        }
        if (from == to) return result(LifecycleVerdict.UNCHANGED, "State「${from.single()}」は変化しません")
        val allowed = lifecycle.transitions.any { it.from == from.single() && it.to == to.single() && it.by == cause }
        return if (allowed) result(LifecycleVerdict.MATCHED, "${from.single()} → ${to.single()} は $causeKind「${cause.id}」に宣言されています")
        else result(LifecycleVerdict.UNDECLARED, "${from.single()} → ${to.single()} を引き起こす $causeKind「${cause.id}」が宣言されていません")
    }
}
