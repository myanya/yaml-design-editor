package dev.toros.yde.cli.commands

import dev.toros.yde.cli.CliResult
import dev.toros.yde.cli.asData
import dev.toros.yde.cli.workspaceOf
import dev.toros.yde.semantic.schema.TransitionCause
import dev.toros.yde.workspace.BehaviorScenarios
import java.nio.file.Files
import java.nio.file.Path

/** 業務の具体例を読み、Action 計画または Event 変更候補を元データに重ねるだけで照合する。 */
internal fun behaviorScenariosCommand(rest: List<String>): CliResult {
    require(rest.size == 2) { "behavior-scenarios は <workspace> <cases.yaml> を指定してください" }
    val session = workspaceOf(rest).mirror
    val cases = BehaviorScenarios.parse(Files.readString(Path.of(rest[1])))
    val report = BehaviorScenarios.run(session, cases)

    fun causeMap(cause: TransitionCause): Map<String, String> = when (cause) {
        is TransitionCause.Action -> mapOf("action" to cause.id)
        is TransitionCause.Event -> mapOf("event" to cause.id)
    }

    return asData(linkedMapOf(
        "status" to report.status,
        "proofStatus" to "not-proven",
        "reviewBasis" to "planned-action-or-proposed-event-changes",
        "schemaErrors" to report.schemaErrors,
        "summary" to linkedMapOf(
            "cases" to report.cases.size,
            "passed" to report.cases.count { it.status == "passed" },
            "failed" to report.cases.count { it.status == "failed" },
            "needsReview" to report.cases.count { it.status == "needs-review" },
            "declaredEdges" to report.coverage.size,
            "exercisedEdges" to report.coverage.count { it.exercisedBy.isNotEmpty() },
            "unexercisedEdges" to report.coverage.count { it.exercisedBy.isEmpty() },
        ),
        "cases" to report.cases.map { case ->
            linkedMapOf(
                "id" to case.id,
                "cause" to causeMap(case.cause),
                "status" to case.status,
                "expectationsMatch" to case.expectationsMatch,
                "proposalValid" to case.proposalValid,
                "findings" to case.findings,
                "reviews" to case.reviews.map { review ->
                    linkedMapOf(
                        "lifecycle" to review.lifecycleId,
                        "entity" to review.entityId,
                        "before" to review.before,
                        "after" to review.after,
                        "verdict" to review.verdict.name,
                        "indirectlyAffected" to review.indirectlyAffected,
                        "message" to review.message,
                    )
                },
            )
        },
        "coverage" to report.coverage.map { edge ->
            linkedMapOf(
                "lifecycle" to edge.lifecycleId,
                "from" to edge.from,
                "to" to edge.to,
                "by" to causeMap(edge.cause),
                "exercisedBy" to edge.exercisedBy,
            ).filterValues { it != null }
        },
        "proofLimits" to listOf(
            "指定された初期データと変更候補での具体例のみを確認し、全条件・全データの振る舞いを証明しません",
            "各ケースは同じ保存済みワークスペースから独立に実行し、ケース間で変更を引き継ぎません",
            "Event の発生事実は検証せず、申告された変更候補の State 遷移だけを照合します",
            "未実行の遷移は検証例が無いことを表し、到達不能や仕様の誤りを意味しません",
        ),
    ))
}
