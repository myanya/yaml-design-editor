package dev.toros.yde.cli.commands

import dev.toros.yde.cli.CliResult
import dev.toros.yde.cli.asData
import dev.toros.yde.cli.stringFlag
import dev.toros.yde.cli.workspaceOf
import dev.toros.yde.workspace.BehaviorChecks
import dev.toros.yde.workspace.BehaviorEventJournal
import dev.toros.yde.workspace.ChangeSetApplier
import dev.toros.yde.workspace.EventOccurrence
import dev.toros.yde.workspace.changeset.ChangeSets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.OffsetDateTime

/** Event は操作の実行命令ではない。記録しても業務文書は更新しない。 */
internal fun eventCommand(rest: List<String>): CliResult = run {
    val ws = workspaceOf(rest)
    if ("--history" in rest) {
        require("--record" !in rest) { "--history と --record は併用できません" }
        asData(linkedMapOf("events" to BehaviorEventJournal.history(ws.mirror).map { entry ->
            val report = entry.value["review"] as? Map<*, *>
            linkedMapOf(
                "file" to entry.file,
                "id" to entry.value["id"],
                "event" to entry.value["event"],
                "subject" to entry.value["subject"],
                "occurredAt" to entry.value["occurredAt"],
                "recordedAt" to entry.value["recordedAt"],
                "source" to entry.value["source"],
                "reviewBasis" to report?.get("basis"),
            ).filterValues { it != null }
        }))
    } else {
        val id = rest.getOrNull(1)?.takeIf { !it.startsWith("--") }
        if (id == null) {
            require("--record" !in rest) { "記録する Event ID を指定してください" }
            val catalog = BehaviorChecks.compile(ws.mirror.schemas)
            asData(linkedMapOf(
                "schemaErrors" to catalog.errors,
                "events" to catalog.events.map { event ->
                    linkedMapOf("id" to event.id, "entity" to event.entity,
                        "label" to event.label, "description" to event.description).filterValues { it != null }
                },
            ))
        } else {
            val record = "--record" in rest
            val source = stringFlag(rest, "--source")
                ?: if (record) error("記録時は --source で申告元を指定してください") else "preview"
            val now = Instant.now()
            val occurredAt = stringFlag(rest, "--occurred-at")?.let { input ->
                runCatching { OffsetDateTime.parse(input).toInstant() }
                    .getOrElse { error("--occurred-at はタイムゾーン付き ISO 8601 日時で指定してください: $input") }
            } ?: now
            val prepared = stringFlag(rest, "--changeset")?.let { filename ->
                ChangeSetApplier.prepare(ws.mirror, ChangeSets.parse(Files.readString(Path.of(filename))))
            }
            val preview = BehaviorEventJournal.preview(
                ws.mirror, EventOccurrence(id, stringFlag(rest, "--on")), source, occurredAt,
                stringFlag(rest, "--evidence"), prepared,
            )
            val file = if (record) BehaviorEventJournal.record(ws.mirror, preview, recordedAt = now) else null
            asData(linkedMapOf(
                "event" to preview.occurrence.eventId,
                "label" to preview.definition.label,
                "subject" to preview.occurrence.subjectId,
                "occurredAt" to preview.occurredAt.toString(),
                "source" to preview.source,
                "evidence" to preview.evidence,
                "occurrenceStatus" to "reported-not-verified",
                "reviewBasis" to if (prepared == null) "not-reviewed" else "proposed-changes-only",
                "proposalValid" to preview.proposalValid,
                "proposalErrorCount" to preview.proposalErrorCount,
                "changes" to preview.proposedChanges.map { change ->
                    linkedMapOf("file" to change.file, "beforeHash" to change.beforeHash,
                        "afterHash" to change.afterHash).filterValues { it != null }
                },
                "lifecycles" to preview.lifecycleReviews.orEmpty().map { result ->
                    linkedMapOf(
                        "lifecycle" to result.lifecycleId, "entity" to result.entityId,
                        "before" to result.before, "after" to result.after,
                        "verdict" to result.verdict.name, "message" to result.message,
                        "indirectlyAffected" to result.indirectlyAffected,
                    )
                },
                "needsAttention" to preview.lifecycleReviews.orEmpty().count { it.needsAttention },
                "recorded" to record,
                "file" to file,
            ).filterValues { it != null })
        }
    }
}
