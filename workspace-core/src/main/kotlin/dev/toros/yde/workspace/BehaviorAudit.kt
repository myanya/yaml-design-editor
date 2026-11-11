package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.LifecycleDef
import dev.toros.yde.semantic.schema.LifecycleTransition
import dev.toros.yde.semantic.schema.TransitionCause

/** 構造上の欠落と、人間による設計判断が必要な箇所を混同しない。 */
enum class BehaviorAuditLevel { INFO, REVIEW }

data class BehaviorAuditFinding(
    val code: String,
    val level: BehaviorAuditLevel,
    val subject: String,
    val message: String,
)

/** State を起点に生成・流入・流出・終端をたどれるようにした Lifecycle の射影。 */
data class BehaviorStateCoverage(
    val stateId: String,
    val entryBy: List<TransitionCause>,
    val incoming: List<LifecycleTransition>,
    val outgoing: List<LifecycleTransition>,
    val terminal: Boolean,
)

data class BehaviorLifecycleCoverage(
    val lifecycleId: String,
    val entity: String,
    val openEnded: Boolean,
    val states: List<BehaviorStateCoverage>,
)

data class BehaviorAuditReport(
    val states: Int,
    val events: Int,
    val lifecycles: List<BehaviorLifecycleCoverage>,
    val schemaErrors: List<String>,
    val findings: List<BehaviorAuditFinding>,
) {
    /** 成立の保証ではなく、静的に調べた範囲の評価である。 */
    val status: String get() = when {
        schemaErrors.isNotEmpty() -> "schema-errors"
        states == 0 && events == 0 && lifecycles.isEmpty() -> "not-modeled"
        findings.any { it.level == BehaviorAuditLevel.REVIEW } -> "needs-review"
        else -> "structurally-checked"
    }
}

/**
 * 宣言を読み取り専用で点検する。参照・到達可能性の検証は BehaviorChecks の一実装を再利用する。
 * 未使用の宣言は合法（単独 State／記録専用 Event）なので ERROR にしない。
 */
object BehaviorAudit {
    val proofLimits = listOf(
        "任意の State 述語同士の排他性・網羅性は静的に証明していません",
        "Action の効果と Event の実発生・真正性は宣言との参照一致だけでは証明できません",
        "業務上必要なシナリオがすべて列挙されていることは証明していません",
    )

    fun inspect(schemas: List<WorkspaceSchema>): BehaviorAuditReport {
        val catalog = BehaviorChecks.compile(schemas)
        val includedStates = catalog.lifecycles.flatMap { it.states }.toSet()
        val linkedEvents = catalog.lifecycles.flatMap { lifecycle ->
            lifecycle.entry.map { it.by } + lifecycle.transitions.map { it.by }
        }.filterIsInstance<TransitionCause.Event>().map { it.id }.toSet()
        val findings = mutableListOf<BehaviorAuditFinding>()

        catalog.states.filter { it.id !in includedStates }.forEach { state ->
            findings += BehaviorAuditFinding(
                "STATE_WITHOUT_LIFECYCLE", BehaviorAuditLevel.INFO, state.id,
                "State「${state.id}」は Lifecycle に含まれません（独立した状態判定なら問題ありません）",
            )
        }
        catalog.events.filter { it.id !in linkedEvents }.forEach { event ->
            findings += BehaviorAuditFinding(
                "EVENT_WITHOUT_LIFECYCLE", BehaviorAuditLevel.INFO, event.id,
                "Event「${event.id}」は Lifecycle の原因に使われていません（記録専用なら問題ありません）",
            )
        }
        catalog.lifecycles.forEach { lifecycle ->
            // 同じ原因から複数の結果に分かれるのは合法だが、期待する条件分岐か確認してほしい。
            lifecycle.transitions.groupBy { it.from to it.by }.forEach { (key, transitions) ->
                val destinations = transitions.map { it.to }.distinct()
                if (destinations.size > 1) {
                    val kind = if (key.second is TransitionCause.Action) "Action" else "Event"
                    findings += BehaviorAuditFinding(
                        "MULTIPLE_TARGETS_FOR_CAUSE", BehaviorAuditLevel.REVIEW, lifecycle.id,
                        "Lifecycle「${lifecycle.id}」の State「${key.first}」で $kind「${key.second.id}」から複数の遷移先があります: ${destinations.joinToString(", ")}（条件分岐が意図どおりか確認してください）",
                    )
                }
            }
        }
        return BehaviorAuditReport(
            catalog.states.size, catalog.events.size,
            catalog.lifecycles.map(::coverage), catalog.errors, findings,
        )
    }

    private fun coverage(lifecycle: LifecycleDef): BehaviorLifecycleCoverage = BehaviorLifecycleCoverage(
        lifecycle.id, lifecycle.entity, lifecycle.openEnded,
        lifecycle.states.map { state ->
            BehaviorStateCoverage(
                state,
                lifecycle.entry.filter { it.to == state }.map { it.by },
                lifecycle.transitions.filter { it.to == state },
                lifecycle.transitions.filter { it.from == state },
                state in lifecycle.terminal,
            )
        },
    )
}
