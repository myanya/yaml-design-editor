package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.EventDef
import dev.toros.yde.workspace.changeset.ChangeSet
import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.workspace.changeset.FileChange
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.validate.Severity
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Event は申告された発生事実。State の変更や Event の真正性をここで推測しない。 */
data class EventReportPreview(
    val occurrence: EventOccurrence,
    val definition: EventDef,
    val source: String,
    val evidence: String?,
    val occurredAt: Instant,
    /** null は遷移を照合していないことを表す。空リスト（検査済み・該当なし）とは異なる。 */
    val lifecycleReviews: List<LifecycleReview>?,
    val proposedChanges: List<EventProposedChange>,
    val proposalValid: Boolean?,
    val proposalErrorCount: Int,
)

/** 確認した変更候補の同一性を示す。変更が実際に適用されたことの証拠ではない。 */
data class EventProposedChange(val file: String, val beforeHash: String?, val afterHash: String)

data class EventJournalEntry(val file: String, val value: Map<String, Any?>)

/**
 * 業務 Event の申告を、既存の変更セット適用経路を介して runs/events に追記する。
 * 原本と区別された記録であり、State を直接更新しない。外部証拠の真偽も保証しない。
 */
object BehaviorEventJournal {
    fun preview(
        session: WorkspaceSession,
        occurrence: EventOccurrence,
        source: String,
        occurredAt: Instant,
        evidence: String? = null,
        prepared: PreparedChangeSet? = null,
    ): EventReportPreview {
        require(source.isNotBlank()) { "Event の記録元（source）を指定してください" }
        require(evidence == null || evidence.isNotBlank()) { "証拠の参照（evidence）は空にできません" }
        val (definition, reviews) = if (prepared == null) {
            BehaviorRuntime.validateEvent(session, occurrence) to null
        } else {
            require(prepared.failures.isEmpty()) { "変更候補に失敗があるため Event を照合できません: ${prepared.failures}" }
            require(prepared.documents.isNotEmpty()) { "Event と照合する変更候補がありません" }
            val inspected = BehaviorRuntime.reviewEvent(session, occurrence, prepared)
            BehaviorChecks.compile(session.schemas).events.single { it.id == occurrence.eventId } to inspected
        }
        return EventReportPreview(
            occurrence, definition, source, evidence, occurredAt, reviews,
            prepared?.documents.orEmpty().map { change ->
                EventProposedChange(
                    change.id.value, change.before?.let(ChangeSets::contentHash), ChangeSets.contentHash(change.after),
                )
            },
            prepared?.valid,
            prepared?.issues?.values?.flatten()?.count { it.severity == Severity.ERROR } ?: 0,
        )
    }

    /** 同じ ID を持つ記録は上書きしない。CLI では既存の WorkspaceOwnership の排他下で呼ぶ。 */
    fun record(
        session: WorkspaceSession,
        preview: EventReportPreview,
        recordedAt: Instant = Instant.now(),
        recordId: String = UUID.randomUUID().toString(),
    ): String {
        require(recordId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "記録 ID は英数字・ハイフン・下線の64文字以内で指定してください" }
        val file = "${session.layout.runsDir}/events/EVT-$recordId.yaml"
        val documentId = DocumentId(file)
        require(session.storedText(documentId) == null) { "Event の記録が既に存在します: $file" }
        val body = linkedMapOf<String, Any?>(
            "kind" to "event-occurrence",
            "id" to "EVT-$recordId",
            "event" to preview.occurrence.eventId,
            "label" to preview.definition.label,
            "subject" to preview.occurrence.subjectId,
            "occurredAt" to preview.occurredAt.toString(),
            "recordedAt" to recordedAt.toString(),
            "source" to preview.source,
            "evidence" to preview.evidence,
            "occurrenceStatus" to "reported-not-verified",
            "review" to linkedMapOf(
                "basis" to if (preview.lifecycleReviews == null) "not-reviewed" else "proposed-changes-only",
                "proposalValid" to preview.proposalValid,
                "proposalErrorCount" to preview.proposalErrorCount,
                "changes" to preview.proposedChanges.map { change ->
                    linkedMapOf("file" to change.file, "beforeHash" to change.beforeHash, "afterHash" to change.afterHash)
                        .filterValues { it != null }
                },
                "lifecycles" to preview.lifecycleReviews.orEmpty().map { review ->
                    linkedMapOf(
                        "lifecycle" to review.lifecycleId,
                        "entity" to review.entityId,
                        "cause" to linkedMapOf("event" to review.causeId),
                        "before" to review.before,
                        "after" to review.after,
                        "verdict" to review.verdict.name,
                        "message" to review.message,
                        "indirectlyAffected" to review.indirectlyAffected,
                    )
                },
            ),
        ).filterValues { it != null }
        val change = FileChange(file, baseHash = null, ops = emptyList(), create = true, content = YamlIO.fromAny(body))
        val result = ChangeSetApplier.apply(
            session, ChangeSet(listOf(change)), requireValid = false,
            now = LocalDateTime.ofInstant(recordedAt, ZoneOffset.UTC),
        )
        require(result.ok) { "Event の記録に失敗しました: ${result.results.joinToString { it.message ?: it.file }}" }
        return file
    }

    /** runs は索引対象外なので、カーネルが保持する原文から記録を一覧にする。 */
    fun history(session: WorkspaceSession): List<EventJournalEntry> {
        val prefix = "${session.layout.runsDir}/events/"
        return session.storeBaseline().entries.asSequence()
            .filter { (id, _) -> id.value.startsWith(prefix) && id.value.endsWith(".yaml") }
            .mapNotNull { (id, text) ->
                val value = runCatching { YamlIO.parseToRaw(text) as? Map<*, *> }.getOrNull() ?: return@mapNotNull null
                if (value["kind"] != "event-occurrence") return@mapNotNull null
                EventJournalEntry(id.value, value.entries.associate { it.key.toString() to it.value })
            }
            .sortedByDescending { it.value["recordedAt"].toString() }
            .toList()
    }
}
