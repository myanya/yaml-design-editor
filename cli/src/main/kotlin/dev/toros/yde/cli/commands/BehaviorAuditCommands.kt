package dev.toros.yde.cli.commands

import dev.toros.yde.cli.CliResult
import dev.toros.yde.cli.asData
import dev.toros.yde.cli.workspaceOf
import dev.toros.yde.semantic.schema.TransitionCause
import dev.toros.yde.workspace.BehaviorAudit
import dev.toros.yde.workspace.BehaviorAuditLevel

/** 定義済みの業務仕様の接続状況を表示する。妥当性の判定は workspace-core に委ねる。 */
internal fun behaviorAuditCommand(rest: List<String>): CliResult {
    require(rest.size == 1) { "behavior-audit はワークスペースだけを指定してください" }
    val ws = workspaceOf(rest)
    val report = BehaviorAudit.inspect(ws.mirror.schemas)

    fun causeMap(cause: TransitionCause): Map<String, String> = when (cause) {
        is TransitionCause.Action -> mapOf("action" to cause.id)
        is TransitionCause.Event -> mapOf("event" to cause.id)
    }

    fun transitionMap(transition: dev.toros.yde.semantic.schema.LifecycleTransition) = linkedMapOf(
        "from" to transition.from, "to" to transition.to, "by" to causeMap(transition.by),
    )

    return asData(linkedMapOf(
        "status" to if (ws.schemaErrors.isNotEmpty()) "schema-errors" else report.status,
        "proofStatus" to "not-proven",
        "summary" to linkedMapOf(
            "states" to report.states, "events" to report.events,
            "lifecycles" to report.lifecycles.size,
            "reviewFindings" to report.findings.count { it.level == BehaviorAuditLevel.REVIEW },
            "informationFindings" to report.findings.count { it.level == BehaviorAuditLevel.INFO },
        ),
        "schemaErrors" to ws.schemaErrors,
        "findings" to report.findings.map { finding ->
            linkedMapOf(
                "code" to finding.code,
                "level" to finding.level.name.lowercase(),
                "subject" to finding.subject,
                "message" to finding.message,
            )
        },
        "lifecycles" to report.lifecycles.map { lifecycle ->
            linkedMapOf(
                "id" to lifecycle.lifecycleId,
                "entity" to lifecycle.entity,
                "openEnded" to lifecycle.openEnded,
                "states" to lifecycle.states.map { state ->
                    linkedMapOf(
                        "id" to state.stateId,
                        "entryBy" to state.entryBy.map(::causeMap),
                        "incoming" to state.incoming.map(::transitionMap),
                        "outgoing" to state.outgoing.map(::transitionMap),
                        "terminal" to state.terminal,
                    )
                },
            )
        },
        "proofLimits" to BehaviorAudit.proofLimits,
    ))
}
