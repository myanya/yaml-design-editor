package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.TransitionCause
import dev.toros.yde.workspace.changeset.ChangeSet
import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.validate.Severity
import java.time.LocalDateTime

/** 期待値は対象 Lifecycle と Entity をキーにして、前後の State と判定をすべて照合する。 */
data class ExpectedLifecycleReview(
    val lifecycleId: String,
    val entityId: String,
    val before: List<String>,
    val after: List<String>,
    val verdict: LifecycleVerdict,
    val indirectlyAffected: Boolean? = null,
)

data class BehaviorScenario(
    val id: String,
    val cause: TransitionCause,
    val receiver: String?,
    val params: Map<String, Any?>,
    val changeSet: ChangeSet?,
    val expected: List<ExpectedLifecycleReview>,
    val at: LocalDateTime? = null,
)

data class BehaviorScenarioResult(
    val id: String,
    val cause: TransitionCause,
    /** 期待した結果と一致したか。仕様上の要確認（UNDECLARED 等）は別に表示する。 */
    val expectationsMatch: Boolean,
    val status: String,
    val findings: List<String>,
    val reviews: List<LifecycleReview>,
    val proposalValid: Boolean?,
)

data class BehaviorScenarioEdge(
    val lifecycleId: String,
    val from: String?,
    val to: String,
    val cause: TransitionCause,
    val exercisedBy: List<String>,
)

data class BehaviorScenarioReport(
    val schemaErrors: List<String>,
    val cases: List<BehaviorScenarioResult>,
    val coverage: List<BehaviorScenarioEdge>,
) {
    val status: String get() = when {
        schemaErrors.isNotEmpty() -> "schema-errors"
        cases.any { it.status == "failed" } -> "failed"
        cases.any { it.status == "needs-review" } -> "needs-review"
        else -> "scenario-checked"
    }
}

/**
 * 具体例を元データからそれぞれ独立に検証する。Action 計画／Event 変更候補の既存経路を再利用し、
 * 保存・Event 発生事実の推測・ケース間での状態引き継ぎは行わない。
 */
object BehaviorScenarios {
    fun parse(text: String): List<BehaviorScenario> {
        val root = objectAt(YamlIO.parseToRaw(text), "シナリオ")
        onlyKeys(root, "シナリオ", "cases")
        val entries = listAt(root["cases"], "cases")
        require(entries.isNotEmpty()) { "cases にシナリオを1件以上指定してください" }
        val cases = entries.mapIndexed { index, item ->
            val where = "cases[$index]"
            val m = objectAt(item, where)
            onlyKeys(m, where, "id", "action", "event", "target", "subject", "params", "changeset", "expect", "at")
            val id = required(m, "id", where)
            val cause = TransitionCause.parse(m.filterKeys { it == "action" || it == "event" }, "$where の原因")
            if (cause is TransitionCause.Action) {
                require("subject" !in m && "changeset" !in m) { "$where: Action には subject / changeset を指定できません" }
            } else {
                require("target" !in m && "params" !in m) { "$where: Event には target / params を指定できません" }
                require("changeset" in m) { "$where: Event には未適用の changeset が必要です" }
            }
            val params = m["params"]?.let { objectAt(it, "$where.params").entries.associate { (k, v) ->
                k.toString() to v
            } } ?: emptyMap()
            val changeSet = m["changeset"]?.let { raw ->
                ChangeSets.parse(YamlIO.dump(YamlIO.fromAny(raw)))
            }
            val expected = listAt(m["expect"], "$where.expect").mapIndexed { j, raw ->
                val at = "$where.expect[$j]"
                val e = objectAt(raw, at)
                onlyKeys(e, at, "lifecycle", "entity", "before", "after", "verdict", "indirectlyAffected")
                val verdict = runCatching { LifecycleVerdict.valueOf(required(e, "verdict", at)) }
                    .getOrElse { error("$at.verdict は ${LifecycleVerdict.entries.joinToString()} のいずれかです") }
                ExpectedLifecycleReview(
                    required(e, "lifecycle", at), required(e, "entity", at),
                    stringsAt(e["before"], "$at.before"), stringsAt(e["after"], "$at.after"),
                    verdict, e["indirectlyAffected"]?.let { it as? Boolean ?: error("$at.indirectlyAffected は真偽値です") },
                )
            }
            require(expected.map { it.lifecycleId to it.entityId }.distinct().size == expected.size) {
                "$where.expect に Lifecycle / Entity が重複しています"
            }
            BehaviorScenario(
                id, cause, m[if (cause is TransitionCause.Action) "target" else "subject"]?.toString(),
                params, changeSet, expected,
                m["at"]?.toString()?.let { raw ->
                    runCatching { LocalDateTime.parse(raw) }.getOrElse { error("$where.at は ISO 8601 のローカル日時で指定してください") }
                },
            )
        }
        require(cases.map { it.id }.distinct().size == cases.size) { "cases.id は重複できません" }
        return cases
    }

    fun run(session: WorkspaceSession, scenarios: List<BehaviorScenario>, now: LocalDateTime = LocalDateTime.now()): BehaviorScenarioReport {
        val catalog = BehaviorChecks.compile(session.schemas)
        val schemaErrors = (session.schemaErrors + catalog.errors).distinct()
        val results = if (schemaErrors.isNotEmpty()) emptyList() else scenarios.map { scenario ->
            runOne(session, scenario, scenario.at ?: now)
        }
        val coverage = catalog.lifecycles.flatMap { lifecycle ->
            (lifecycle.entry.map { entry -> Triple(null, entry.to, entry.by) } +
                lifecycle.transitions.map { transition -> Triple(transition.from, transition.to, transition.by) }).map { (from, to, cause) ->
                val exercisedBy = results.filter { it.expectationsMatch }.filter { result ->
                    result.reviews.any { review ->
                        review.lifecycleId == lifecycle.id && review.cause == cause && review.verdict == LifecycleVerdict.MATCHED &&
                            review.before == listOfNotNull(from) && review.after == listOf(to)
                    }
                }.map { it.id }
                BehaviorScenarioEdge(lifecycle.id, from, to, cause, exercisedBy)
            }
        }
        return BehaviorScenarioReport(schemaErrors, results, coverage)
    }

    private fun runOne(session: WorkspaceSession, scenario: BehaviorScenario, at: LocalDateTime): BehaviorScenarioResult {
        var proposalValid: Boolean? = null
        val findings = mutableListOf<String>()
        val reviews = runCatching {
            val prepared = when (val cause = scenario.cause) {
                is TransitionCause.Action -> {
                    val plan = ActionPlanner.plan(session, ActionRequest(cause.id, scenario.receiver, scenario.params), at)
                    ChangeSetApplier.prepare(session, plan.changeSet, at)
                }
                is TransitionCause.Event -> ChangeSetApplier.prepare(session, requireNotNull(scenario.changeSet), at)
            }
            proposalValid = prepared.valid
            findings += prepared.failures.map { (file, message) -> "$file: $message" }
            prepared.issues.forEach { (file, issues) ->
                findings += issues.filter { it.severity == Severity.ERROR }.map { "$file: ${it.message}" }
            }
            if (findings.isNotEmpty()) emptyList() else when (val cause = scenario.cause) {
                is TransitionCause.Action -> BehaviorRuntime.review(session, ActionRequest(cause.id, scenario.receiver, scenario.params), prepared)
                is TransitionCause.Event -> BehaviorRuntime.reviewEvent(session, EventOccurrence(cause.id, scenario.receiver), prepared)
            }
        }.getOrElse { failure ->
            findings += failure.message ?: "シナリオの実行候補を作成できませんでした"
            emptyList()
        }
        if (findings.isEmpty()) {
            val expectedByKey = scenario.expected.associateBy { it.lifecycleId to it.entityId }
            val actualByKey = reviews.associateBy { it.lifecycleId to it.entityId }
            scenario.expected.forEach { expected ->
                val key = expected.lifecycleId to expected.entityId
                val actual = actualByKey[key]
                if (actual == null) findings += "${expected.lifecycleId}/${expected.entityId}: 期待した Lifecycle 照合がありません"
                else {
                    if (expected.before != actual.before) findings += "${expected.lifecycleId}/${expected.entityId}: before は ${expected.before} を期待、実際は ${actual.before}"
                    if (expected.after != actual.after) findings += "${expected.lifecycleId}/${expected.entityId}: after は ${expected.after} を期待、実際は ${actual.after}"
                    if (expected.verdict != actual.verdict) findings += "${expected.lifecycleId}/${expected.entityId}: verdict は ${expected.verdict} を期待、実際は ${actual.verdict}"
                    if (expected.indirectlyAffected != null && expected.indirectlyAffected != actual.indirectlyAffected) {
                        findings += "${expected.lifecycleId}/${expected.entityId}: indirectlyAffected は ${expected.indirectlyAffected} を期待、実際は ${actual.indirectlyAffected}"
                    }
                }
            }
            reviews.filter { (it.lifecycleId to it.entityId) !in expectedByKey }.forEach { actual ->
                findings += "${actual.lifecycleId}/${actual.entityId}: 期待値にない Lifecycle 照合（${actual.verdict}）です"
            }
        }
        val matches = findings.isEmpty()
        return BehaviorScenarioResult(
            scenario.id, scenario.cause, matches,
            when {
                !matches -> "failed"
                reviews.any { it.needsAttention } -> "needs-review"
                else -> "passed"
            }, findings, reviews, proposalValid,
        )
    }

    private fun objectAt(value: Any?, where: String): Map<*, *> =
        value as? Map<*, *> ?: error("$where は object で指定してください")

    private fun listAt(value: Any?, where: String): List<*> =
        value as? List<*> ?: error("$where は配列で指定してください")

    private fun stringsAt(value: Any?, where: String): List<String> = listAt(value, where).map {
        it as? String ?: error("$where は文字列の配列で指定してください")
    }

    private fun required(map: Map<*, *>, key: String, where: String): String =
        (map[key] as? String)?.takeIf { it.isNotBlank() } ?: error("$where.$key は空でない文字列が必要です")

    private fun onlyKeys(map: Map<*, *>, where: String, vararg allowed: String) {
        val unexpected = map.keys.filter { it !in allowed }
        require(unexpected.isEmpty()) { "$where に未定義のキーがあります: $unexpected" }
    }
}
