package dev.toros.yde.cli.commands

import dev.toros.yde.cli.asText
import dev.toros.yde.cli.asData
import dev.toros.yde.cli.CliResult
import dev.toros.yde.agent.run.AgentEvent
import dev.toros.yde.agent.run.AgentHost
import dev.toros.yde.agent.run.AgentKernel
import dev.toros.yde.agent.run.AgentRunRequest
import dev.toros.yde.agent.auto.AutoApply
import dev.toros.yde.agent.auto.AutomationEngine
import dev.toros.yde.agent.Definitions
import dev.toros.yde.agent.eval.EvalRunner
import dev.toros.yde.agent.Evals
import dev.toros.yde.agent.auto.Foreach
import dev.toros.yde.agent.run.RunLog
import dev.toros.yde.agent.eval.RunMetrics
import dev.toros.yde.agent.run.RunStatus
import dev.toros.yde.agent.llm.AiProvider
import dev.toros.yde.app.core.Workspace
import dev.toros.yde.app.core.changeSetToYaml
import dev.toros.yde.cli.stringFlag
import dev.toros.yde.cli.workspaceOf
import java.nio.file.Files
import java.nio.file.Path

/**
 * エージェントの命令: activities（定義の一覧）・agent（アクティビティの実行。非対話なので ask_human には回答なし）・watch（自動実行）・metrics（実行記録の集計）・eval（評価セット）・revert（自動適用の巻き戻し）。LLM の鍵は環境変数から。
 * 業務ロジックは置かない（引数を窓口の入力へ写し、結果を YAML へ写すだけ）。入口は AppCli.kt の runCli。
 */

internal fun activitiesCommand(rest: List<String>): CliResult = run {
    val ws = workspaceOf(rest)
    val defs = Definitions.load(ws.root)
    asData(
        linkedMapOf(
            "agents" to defs.agents.map { linkedMapOf("id" to it.id, "name" to it.name, "tools" to it.tools.map { t -> t.id }) },
            "activities" to defs.activities.map {
                linkedMapOf(
                    "id" to it.id, "name" to it.name, "agent" to it.agentId,
                    "target" to it.targetMatch, "gate" to it.gate.name,
                )
            },
            "errors" to defs.errors,
        )
    )
}

internal fun metricsCommand(rest: List<String>): CliResult = run {
    val root = Path.of(rest.firstOrNull() ?: error("ワークスペースのパスを指定してください"))
    val records = RunMetrics.collect(root)
    val filter = stringFlag(rest, "--activity")
    val summaries = RunMetrics.summarize(records)
        .filter { filter == null || it.activityId == filter }
    asData(RunMetrics.toYamlMap(summaries))
}

internal fun revertCommand(rest: List<String>): CliResult = run {
    val ws = workspaceOf(rest)
    val runFile = rest.getOrNull(1)?.takeIf { !it.startsWith("--") }
        ?: error("実行記録（_runs/*.yaml）を指定してください")
    val result = AutoApply.revert(ws, ws.resolve(runFile), force = "--force" in rest)
    asData(linkedMapOf("ok" to result.ok, "messages" to result.messages))
}

/** 環境変数に設定されているAPIキーをプロバイダごとに集める（delegate や watch で使う）。 */
private fun envApiKeys(): Map<AiProvider, String> =
    AiProvider.entries.associateWith { System.getenv(it.envVar).orEmpty() }.filterValues { it.isNotBlank() }

/** 自動実行エンジンをヘッドレスで起動する（R3-01〜05）。 */
internal fun watchCommand(rest: List<String>): CliResult {
    val dir = Path.of(rest.firstOrNull() ?: error("ワークスペースのパスを指定してください"))
    val provider = stringFlag(rest, "--provider")?.let { flag ->
        AiProvider.entries.firstOrNull { it.name.equals(flag, ignoreCase = true) } ?: error("未知のプロバイダです: $flag")
    } ?: AiProvider.OPENAI
    val keys = envApiKeys()
    val engine = AutomationEngine(
        dir,
        credentials = { AutomationEngine.Credentials(provider = provider, apiKeys = keys) },
    )
    engine.logSink = { System.err.println(it) }
    engine.setEnabled(true)
    return if ("--once" in rest) {
        engine.tick(System.currentTimeMillis())
        kotlinx.coroutines.runBlocking { while (engine.processOnce()) { /* キューを消化 */ } }
        asData(
            linkedMapOf(
                "history" to engine.historySnapshot().map {
                    linkedMapOf("activity" to it.activityId, "status" to it.status, "detail" to it.detail)
                },
                "pendingReviews" to engine.pendingSnapshot().map {
                    linkedMapOf(
                        "activity" to it.activityId,
                        "reason" to it.reason,
                        "runLog" to it.runFile?.let { f -> it.ws.relPath(f) },
                        "changes" to it.result.changeSet.changes.map { c -> c.file },
                    )
                },
            )
        )
    } else {
        System.err.println("自動実行を開始しました（Ctrl+C で停止）。承認待ちは _runs/ の changeset を apply で適用できます。")
        kotlinx.coroutines.runBlocking { engine.start(this).join() }
        asText("")
    }
}

/** 評価セットをヘッドレス実行する（R4-02 / R4-04）。 */
internal fun evalCommand(rest: List<String>): CliResult {
    val root = Path.of(rest.firstOrNull() ?: error("ワークスペースのパスを指定してください"))
    val loaded = Evals.load(root)
    if (loaded.errors.isNotEmpty()) System.err.println("定義の警告:\n" + loaded.errors.joinToString("\n") { "- $it" })
    val evalId = rest.getOrNull(1)?.takeIf { !it.startsWith("--") }
        ?: return asData(
            linkedMapOf(
                "evals" to loaded.evals.map {
                    linkedMapOf(
                        "id" to it.id, "name" to it.name, "activity" to it.activityId,
                        "cases" to it.cases.size, "variants" to it.variants.map { v -> v.name },
                    )
                },
                "errors" to loaded.errors,
            )
        )
    val eval = loaded.eval(evalId) ?: error("評価セット「$evalId」が見つかりません（eval コマンドで一覧を確認できます）")
    val provider = stringFlag(rest, "--provider")?.let { flag ->
        AiProvider.entries.firstOrNull { it.name.equals(flag, ignoreCase = true) } ?: error("未知のプロバイダです: $flag")
    } ?: AiProvider.OPENAI
    val runner = EvalRunner(
        credentials = { AutomationEngine.Credentials(provider = provider, apiKeys = envApiKeys()) },
    )
    val report = kotlinx.coroutines.runBlocking {
        runner.run(root, eval, onLog = { System.err.println(it) })
    }
    return asData(
        linkedMapOf(
            "eval" to report.evalId,
            "activity" to report.activityId,
            "report" to report.reportFile?.let { Workspace.open(root).relPath(it) },
            "variants" to report.summaries().map { s ->
                linkedMapOf(
                    "name" to s.variant, "cases" to s.cases, "passed" to s.passed, "passRate" to s.passRate,
                    "inputTokens" to s.inputTokens, "outputTokens" to s.outputTokens,
                )
            },
            "results" to report.results.map { r ->
                linkedMapOf<String, Any?>(
                    "variant" to r.variant, "case" to r.caseId, "pass" to r.pass,
                    "status" to r.status.name,
                    "failures" to r.failures,
                ).filterValues { it != null && (it !is List<*> || it.isNotEmpty()) }
            },
        )
    )
}

/** アクティビティをヘッドレス実行する。非対話のため ask_human には「回答なし」を返す。 */
internal fun agentCommand(rest: List<String>): CliResult {
    val ws = Workspace.open(Path.of(rest.firstOrNull() ?: error("ワークスペースのパスを指定してください")))
    val activityId = rest.getOrNull(1)?.takeIf { !it.startsWith("--") } ?: error("アクティビティIDを指定してください")
    val defs = Definitions.load(ws.root)
    if (defs.errors.isNotEmpty()) System.err.println("定義の警告:\n" + defs.errors.joinToString("\n") { "- $it" })
    val activity = defs.activity(activityId) ?: error("アクティビティ「$activityId」が見つかりません（activities コマンドで確認できます）")
    val agentDef = defs.agent(activity.agentId) ?: error("エージェント「${activity.agentId}」が見つかりません")

    val provider = stringFlag(rest, "--provider")?.let { flag ->
        AiProvider.entries.firstOrNull { it.name.equals(flag, ignoreCase = true) } ?: error("未知のプロバイダです: $flag")
    } ?: agentDef.provider ?: AiProvider.OPENAI
    // エージェント定義のモデルIDは、その定義のプロバイダと一致する場合のみ引き継ぐ
    // （--provider で上書きしたときに他社のモデルIDを送らない）
    val pinnedModel = agentDef.model?.takeIf { agentDef.provider == null || agentDef.provider == provider }
    val model = stringFlag(rest, "--model") ?: pinnedModel ?: provider.defaultModel
    val apiKey = System.getenv(provider.envVar).orEmpty()
    require(apiKey.isNotBlank()) { "環境変数 ${provider.envVar} にAPIキーを設定してください" }

    val host = object : AgentHost {
        override suspend fun askHuman(question: String): String? {
            System.err.println("[質問] $question（非対話実行のため回答できません）")
            return null
        }
        override suspend fun onEvent(event: AgentEvent) {
            val line = when (event) {
                is AgentEvent.TurnStarted -> "--- ターン ${event.turn}/${event.maxTurns}"
                is AgentEvent.AssistantText -> event.text.lineSequence().joinToString("\n") { "  $it" }
                is AgentEvent.ToolInvoked -> "  [tool] ${event.name} ${event.summary}"
                is AgentEvent.ToolFailed -> "  [tool失敗] ${event.name}: ${event.message}"
                is AgentEvent.QuestionAsked -> "  [質問] ${event.question}"
                is AgentEvent.PlanUpdated -> "  [計画] ${event.steps.joinToString(" / ")}"
                is AgentEvent.Finished -> "=== 終了: ${event.status}${event.message?.let { " — $it" } ?: ""}"
            }
            System.err.println(line)
        }
    }
    // 一括実行（R18-01）: foreach で対象を列挙し、1件=1実行で直列に回す。
    // --apply 併用時は1件ごとに適用し、後続の項目のワークスペースから先行の成果が見えるようにする
    if ("--foreach" in rest) {
        val foreachDef = activity.foreach
            ?: error("アクティビティ「${activity.id}」に foreach 定義がありません")
        val userInstruction = stringFlag(rest, "--instruction")
        val apply = "--apply" in rest
        val enumeration = Foreach.enumerate(ws, foreachDef)
        if (enumeration.items.isEmpty()) {
            return asData(linkedMapOf("total" to enumeration.total, "executed" to 0, "message" to "対象がありません"))
        }
        val itemResults = mutableListOf<Map<String, Any?>>()
        enumeration.items.forEachIndexed { i, item ->
            System.err.println("=== 一括 ${i + 1}/${enumeration.items.size}: ${item.label}")
            val itemWs = Workspace.open(ws.root)
            val request = AgentRunRequest(
                activity = activity, agent = agentDef,
                provider = provider, model = model, apiKey = apiKey,
                extraInstruction = (listOfNotNull(userInstruction) + item.instruction).joinToString("\n"),
                apiKeys = envApiKeys(),
                subAgents = defs.agents,
                targetValues = item.values,
            )
            val result = kotlinx.coroutines.runBlocking { AgentKernel.run(itemWs, request, host) }
            val itemRunFile = RunLog.write(itemWs, request, result)
            var appliedOk: Boolean? = null
            if (apply && result.status == RunStatus.SUBMITTED && result.changeSet.changes.isNotEmpty()) {
                appliedOk = itemWs.apply(result.changeSet).ok
            }
            itemResults += linkedMapOf(
                "target" to item.key,
                "status" to result.status.name,
                "message" to result.message,
                "applied" to appliedOk,
                "changes" to result.changeSet.changes.map { it.file },
                "runLog" to itemWs.relPath(itemRunFile),
            )
        }
        return asData(
            linkedMapOf(
                "total" to enumeration.total,
                "executed" to enumeration.items.size,
                "items" to itemResults,
            )
        )
    }

    val request = AgentRunRequest(
        activity = activity, agent = agentDef,
        provider = provider, model = model, apiKey = apiKey,
        extraInstruction = stringFlag(rest, "--instruction"),
        apiKeys = envApiKeys(),
        subAgents = defs.agents,
    )
    val result = kotlinx.coroutines.runBlocking { AgentKernel.run(ws, request, host) }
    val runFile = RunLog.write(ws, request, result)

    val changeSetYaml = changeSetToYaml(result.changeSet)
    stringFlag(rest, "--out")?.let { Files.writeString(Path.of(it), changeSetYaml) }
    var appliedOk: Boolean? = null
    if ("--apply" in rest && result.status == RunStatus.SUBMITTED && result.changeSet.changes.isNotEmpty()) {
        appliedOk = ws.apply(result.changeSet).ok
    }
    return asData(
        linkedMapOf(
            "status" to result.status.name,
            "message" to result.message,
            "turns" to result.turnsUsed,
            "usage" to linkedMapOf("inputTokens" to result.usage.inputTokens, "outputTokens" to result.usage.outputTokens),
            "runLog" to ws.relPath(runFile),
            "applied" to appliedOk,
            "changes" to result.changeSet.changes.map { linkedMapOf("file" to it.file, "create" to it.create, "ops" to it.ops.size) },
        ),
        changeSet = result.changeSet,
    )
}
