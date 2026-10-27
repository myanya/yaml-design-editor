package dev.toros.yde.agent.eval

import dev.toros.yde.agent.Definitions
import dev.toros.yde.agent.EvalCase
import dev.toros.yde.agent.EvalDef
import dev.toros.yde.agent.EvalExpect
import dev.toros.yde.agent.EvalVariant
import dev.toros.yde.agent.Evals
import dev.toros.yde.agent.auto.AutomationEngine
import dev.toros.yde.agent.llm.ChatUsage
import dev.toros.yde.agent.llm.ToolChat
import dev.toros.yde.agent.run.AgentHost
import dev.toros.yde.agent.run.AgentKernel
import dev.toros.yde.agent.run.AgentRunRequest
import dev.toros.yde.agent.run.AgentRunResult
import dev.toros.yde.agent.run.RunLog
import dev.toros.yde.agent.run.RunStatus
import dev.toros.yde.agent.run.SessionFactory
import dev.toros.yde.app.core.Workspace
import dev.toros.yde.semantic.schema.globToRegex
import dev.toros.yde.workspace.WorkspacePaths
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.validate.Severity
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createTempDirectory

/**
 * 評価の実行系。ケース×変異ごとにサンドボックスを作り、カーネルを走らせ、期待結果を判定する。
 * LLMセッションは注入可能（テストでは台本を使う）。
 */
class EvalRunner(
    private val credentials: () -> AutomationEngine.Credentials,
    private val sessionFactory: SessionFactory = { provider, apiKey, model, system, tools ->
        ToolChat.newSession(provider, apiKey, model, system, tools)
    },
) {
    data class CaseResult(
        val variant: String,
        val caseId: String,
        val caseName: String,
        val pass: Boolean,
        val failures: List<String>,
        val status: RunStatus,
        val turns: Int,
        val usage: ChatUsage,
    )

    data class VariantSummary(
        val variant: String,
        val cases: Int,
        val passed: Int,
        val totalTurns: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val cachedInputTokens: Long,
    ) {
        val passRate: Double get() = if (cases == 0) 0.0 else passed.toDouble() / cases
    }

    data class Report(
        val evalId: String,
        val activityId: String,
        val results: List<CaseResult>,
        val reportFile: Path?,
    ) {
        fun summaries(): List<VariantSummary> =
            results.groupBy { it.variant }.map { (variant, list) ->
                VariantSummary(
                    variant = variant,
                    cases = list.size,
                    passed = list.count { it.pass },
                    totalTurns = list.sumOf { it.turns },
                    inputTokens = list.sumOf { it.usage.inputTokens },
                    outputTokens = list.sumOf { it.usage.outputTokens },
                    cachedInputTokens = list.sumOf { it.usage.cachedInputTokens },
                )
            }
    }

    /** 評価を実行し、レポートを原本ワークスペースの `_runs/` に保存する。 */
    suspend fun run(
        wsRoot: Path,
        eval: EvalDef,
        onLog: (String) -> Unit = {},
        writeReport: Boolean = true,
    ): Report {
        val variants = eval.variants.ifEmpty { listOf(EvalVariant("baseline")) }
        val results = mutableListOf<CaseResult>()
        for (variant in variants) {
            for (case in eval.cases) {
                onLog("実行中: ${eval.id} / ${variant.name} / ${case.id}")
                val result = runCase(wsRoot, eval, variant, case)
                onLog("  → ${if (result.pass) "合格" else "不合格: ${result.failures.firstOrNull() ?: ""}"}")
                results += result
            }
        }
        val reportFile = if (writeReport) runCatching { writeReport(wsRoot, eval, results) }.getOrNull() else null
        return Report(eval.id, eval.activityId, results, reportFile)
    }

    private suspend fun runCase(wsRoot: Path, eval: EvalDef, variant: EvalVariant, case: EvalCase): CaseResult {
        val sandbox = try { createSandbox(wsRoot) } catch (e: Exception) {
            return failedCase(variant, case, e.message ?: "評価用コピーを作成できません")
        }
        try {
            case.setup.forEach { setup ->
                val target = WorkspacePaths.resolve(sandbox, Evals.setupPath(setup.file))
                Files.createDirectories(target.parent)
                Files.writeString(target, setup.content)
            }
            val defs = Definitions.load(sandbox)
            val baseActivity = defs.activity(eval.activityId)
                ?: return failedCase(variant, case, "アクティビティ「${eval.activityId}」が見つかりません")
            // コンテキストレシピの差し替え（R19-06）: レシピだけを変えた同一ケースのA/B比較を可能にする
            val activity = variant.contextItems?.let { baseActivity.copy(contextItems = it) } ?: baseActivity
            val agentDef = defs.agent(variant.agentId ?: activity.agentId)
                ?: return failedCase(variant, case, "エージェント「${variant.agentId ?: activity.agentId}」が見つかりません")

            val creds = credentials()
            val provider = variant.provider ?: agentDef.provider ?: creds.provider
            // 変異のモデル指定が最優先。エージェント定義のモデルはプロバイダが一致する場合のみ引き継ぐ
            val model = variant.model
                ?: agentDef.model?.takeIf { agentDef.provider == null || agentDef.provider == provider }
                ?: creds.models[provider]?.takeIf { it.isNotBlank() }
                ?: provider.defaultModel
            val extra = listOfNotNull(case.instruction, variant.instruction)
                .filter { it.isNotBlank() }.joinToString("\n").ifBlank { null }

            val ws = Workspace.open(sandbox)
            val request = AgentRunRequest(
                activity = activity, agent = agentDef,
                provider = provider, model = model, apiKey = creds.apiKeys[provider].orEmpty(),
                extraInstruction = extra,
                apiKeys = creds.apiKeys, subAgents = defs.agents,
            )
            val host = object : AgentHost {
                override suspend fun askHuman(question: String): String? = null  // 評価は無人で行う
            }
            val result = AgentKernel.run(ws, request, host, sessionFactory)
            val failures = checkExpectations(case.expect, result)
            return CaseResult(
                variant = variant.name, caseId = case.id, caseName = case.name,
                pass = failures.isEmpty(), failures = failures,
                status = result.status, turns = result.turnsUsed, usage = result.usage,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return failedCase(variant, case, e.message ?: "評価に失敗しました")
        } finally {
            deleteRecursively(sandbox)
        }
    }

    private fun failedCase(variant: EvalVariant, case: EvalCase, message: String) = CaseResult(
        variant = variant.name, caseId = case.id, caseName = case.name,
        pass = false, failures = listOf(message),
        status = RunStatus.ERROR, turns = 0, usage = ChatUsage(),
    )

    private fun checkExpectations(expect: EvalExpect, result: AgentRunResult): List<String> {
        val failures = mutableListOf<String>()
        if (expect.status != "any" && result.status.name != expect.status.uppercase()) {
            failures += "終了状態が ${expect.status} ではありません: ${result.status}${result.message?.let { " — $it" } ?: ""}"
        }
        if (expect.errorFree == true) {
            val errors = result.issues.flatMap { (rel, list) ->
                list.filter { it.severity == Severity.ERROR }.map { "$rel: ${it.message}" }
            }
            if (errors.isNotEmpty()) failures += "検証エラーが残っています（${errors.size}件）: ${errors.first()}"
        }
        val changed = result.changeSet.changes.map { it.file }
        expect.changedFiles.forEach { glob ->
            val regex = globToRegex(glob)
            if (changed.none { regex.matches(it) }) {
                failures += "変更されるべきファイルが変更されていません: $glob（実際の変更: ${changed.ifEmpty { listOf("なし") }.joinToString(", ")}）"
            }
        }
        expect.unchangedFiles.forEach { glob ->
            val regex = globToRegex(glob)
            changed.filter { regex.matches(it) }.forEach { failures += "変更されてはならないファイルが変更されています: $it" }
        }
        expect.content.forEach { check ->
            val root = result.staging.tree(check.file)
            if (root == null) {
                if (check.exists) failures += "ファイルが読めません: ${check.file}"
                return@forEach
            }
            val node = getAt(root, parseYPath(check.path))
            if (!check.exists) {
                if (node != null) failures += "存在してはならないパスが存在します: ${check.file} :: ${check.path}"
                return@forEach
            }
            if (node == null) {
                failures += "期待するパスが存在しません: ${check.file} :: ${check.path}"
                return@forEach
            }
            // 期待結果は構造条件のみ（コメントの有無で揺れないよう、比較はコメント抜きで行う）
            val text = YamlIO.dumpPlain(node).trim()
            check.contains?.let {
                if (it !in text) failures += "${check.file} :: ${check.path} に「$it」が含まれていません"
            }
            check.equals?.let {
                if (text != it.trim()) failures += "${check.file} :: ${check.path} が期待値と一致しません（実際: ${text.take(120)}）"
            }
        }
        return failures
    }

    // ---------------------------------------------------------------- サンドボックスとレポート

    /** runs ゾーンと隠しディレクトリ（構成ディレクトリ `.yde` を除く）を除いてワークスペースを一時ディレクトリへコピーする。 */
    private fun createSandbox(wsRoot: Path): Path {
        val layout = WorkspaceLayout.of(wsRoot)
        val sandbox = createTempDirectory("yde-eval")
        try {
            Files.walk(wsRoot).use { stream ->
                stream.forEach { p ->
                    val rel = wsRoot.relativize(p).toString().replace('\\', '/')
                    if (rel.isEmpty()) return@forEach
                    if (layout.zoneOf(rel) == WorkspaceLayout.Zone.RUNS) return@forEach
                    if (rel.startsWith(".") && !(layout.configDir != null && (rel == layout.configDir || rel.startsWith(layout.configDir + "/")))) return@forEach
                    WorkspacePaths.resolve(wsRoot, rel)
                    val target = sandbox.resolve(rel)
                    if (Files.isDirectory(p)) Files.createDirectories(target)
                    else {
                        Files.createDirectories(target.parent)
                        Files.copy(p, target)
                    }
                }
            }
            return sandbox
        } catch (e: Exception) {
            deleteRecursively(sandbox)
            throw e
        }
    }

    private fun deleteRecursively(dir: Path) {
        runCatching {
            Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    private fun writeReport(wsRoot: Path, eval: EvalDef, results: List<CaseResult>): Path {
        val dir = WorkspacePaths.resolve(wsRoot, WorkspaceLayout.of(wsRoot).runsDir)
        Files.createDirectories(dir)
        var file = RunLog.destination(dir, "eval-${LocalDateTime.now().format(fileStamp)}-${eval.id}.yaml")
        var seq = 1
        while (Files.exists(file)) {
            file = RunLog.destination(dir, "eval-${LocalDateTime.now().format(fileStamp)}-${eval.id}-${seq++}.yaml")
        }
        val report = Report(eval.id, eval.activityId, results, null)
        val body = linkedMapOf<String, Any?>(
            "eval" to eval.id,
            "activity" to eval.activityId,
            "finishedAt" to ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "variants" to report.summaries().map { s ->
                LinkedHashMap<String, Any>().apply {
                    put("name", s.variant)
                    put("cases", s.cases)
                    put("passed", s.passed)
                    put("passRate", s.passRate)
                    put("turns", s.totalTurns)
                    put("inputTokens", s.inputTokens)
                    put("outputTokens", s.outputTokens)
                    if (s.cachedInputTokens > 0) put("cachedInputTokens", s.cachedInputTokens)
                }
            },
            "results" to results.map { r ->
                linkedMapOf<String, Any?>(
                    "variant" to r.variant,
                    "case" to r.caseId,
                    "name" to r.caseName,
                    "pass" to r.pass,
                    "status" to r.status.name,
                    "turns" to r.turns,
                    // レシピA/B（R19-06）: ケース単位のコスト比較ができるようトークンも記録する
                    "inputTokens" to r.usage.inputTokens,
                    "outputTokens" to r.usage.outputTokens,
                    "failures" to r.failures,
                ).filterValues { it != null && (it !is List<*> || it.isNotEmpty()) }
            },
        )
        Files.writeString(file, YamlIO.dump(YamlIO.fromAny(body)))
        return file
    }
}
