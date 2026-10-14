package dev.toros.yde.app.agent

import dev.toros.yde.workspace.WorkspacePaths

import dev.toros.yde.app.ai.AiProvider
import dev.toros.yde.app.ai.ChatUsage
import dev.toros.yde.app.ai.ToolChat
import dev.toros.yde.app.core.Workspace
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.app.schema.SchemaLoader
import dev.toros.yde.semantic.schema.globToRegex
import dev.toros.yde.yaml.validate.Severity
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createTempDirectory

/**
 * 評価セット（R4-02）と比較実験（R4-04）。
 * `_evals/` に置いた「ゴールデン入力＋期待結果」の定義でアクティビティを回帰評価する（アクティビティの「テスト」）。
 * - 実行はワークスペースの一時サンドボックスコピー上で行い、原本には一切触れない
 * - 期待結果は宣言的なチェック（終了状態・検証エラー・変更ファイル・パス内容）で機械判定する
 * - variants はモデル・エージェント・追加指示を差し替えて同じケース群を実行するA/B比較
 */

data class EvalSetupFile(val file: String, val content: String)

data class EvalContentCheck(
    val file: String,
    val path: String,                 // YPath（例 review_comments）。"" はルート
    val exists: Boolean = true,
    val contains: String? = null,
    val equals: String? = null,
)

data class EvalExpect(
    val status: String = "submitted", // submitted / aborted / limit / error / any
    val errorFree: Boolean? = null,   // true: 終了時点のステージング検証エラー0
    val changedFiles: List<String> = emptyList(),    // 各globに1件以上マッチする変更が必要
    val unchangedFiles: List<String> = emptyList(),  // マッチする変更があれば不合格
    val content: List<EvalContentCheck> = emptyList(),
)

data class EvalCase(
    val id: String,
    val name: String,
    val setup: List<EvalSetupFile> = emptyList(),    // サンドボックスへ上書き・作成するゴールデン入力
    val instruction: String? = null,                 // ケース固有の追加指示
    val expect: EvalExpect = EvalExpect(),
)

/** 比較実験の変異（R4-04）。指定した項目だけを差し替え、他はアクティビティ定義に従う。 */
data class EvalVariant(
    val name: String,
    val provider: AiProvider? = null,
    val model: String? = null,
    val agentId: String? = null,
    val instruction: String? = null,
    /**
     * コンテキストレシピの差し替え（R19-06）。null = アクティビティ定義のまま。
     * 非null はアクティビティの context.items を丸ごと置き換える（空 = 開示なしのA/Bも書ける）。
     * 「レシピA/B × 合格率 × トークン」の比較でレシピをデータでチューニングする器。
     */
    val contextItems: List<ContextItemDef>? = null,
)

data class EvalDef(
    val id: String,
    val name: String,
    val description: String? = null,
    val activityId: String,
    val variants: List<EvalVariant> = emptyList(),   // 空 = 現行定義のまま1系統（baseline）
    val cases: List<EvalCase>,
    val source: Path,
)

object Evals {

    private val allowedStatus = setOf("submitted", "aborted", "limit", "error", "cancelled", "any")

    data class LoadResult(val evals: List<EvalDef>, val errors: List<String>) {
        fun eval(id: String): EvalDef? = evals.firstOrNull { it.id == id }
    }

    fun load(workspace: Path): LoadResult {
        val dir = runCatching { WorkspacePaths.resolve(workspace, WorkspaceLayout.of(workspace).evalsDir) }
            .getOrElse { return LoadResult(emptyList(), listOf(it.message ?: "評価定義の場所を解決できません")) }
        if (!Files.isDirectory(dir)) return LoadResult(emptyList(), emptyList())
        val errors = mutableListOf<String>()
        val files = Files.list(dir).use { stream ->
            stream.filter { p ->
                val n = p.fileName.toString()
                Files.isRegularFile(p) && (n.endsWith(".yaml") || n.endsWith(".yml"))
            }.toList()
        }.sortedBy { it.fileName.toString() }
        val evals = files.mapNotNull { file ->
            runCatching {
                WorkspacePaths.resolve(workspace, workspace.toAbsolutePath().relativize(file).toString())
                val raw = YamlIO.parseToRaw(Files.readString(file)) as? Map<*, *>
                    ?: error("ルートはobjectである必要があります")
                parseEval(raw, file)
            }.onFailure { errors += "${file.fileName}: ${it.message ?: it::class.simpleName}" }.getOrNull()
        }
        return LoadResult(evals, errors)
    }

    internal fun parseEval(raw: Map<*, *>, source: Path): EvalDef {
        val id = raw["id"]?.toString()?.takeIf { it.isNotBlank() } ?: error("id が必要です")
        val cases = (raw["cases"] as? List<*>)?.map { parseCase(it) } ?: error("cases が必要です")
        if (cases.isEmpty()) error("cases は1件以上必要です")
        cases.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { error("ケースID「$it」が重複しています") }
        val variants = (raw["variants"] as? List<*>)?.map { parseVariant(it) } ?: emptyList()
        variants.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach { error("変異名「$it」が重複しています") }
        return EvalDef(
            id = id,
            name = raw["name"]?.toString() ?: id,
            description = raw["description"]?.toString(),
            activityId = raw["activity"]?.toString()?.takeIf { it.isNotBlank() } ?: error("activity が必要です"),
            variants = variants,
            cases = cases,
            source = source,
        )
    }

    private fun parseCase(raw: Any?): EvalCase {
        val map = raw as? Map<*, *> ?: error("cases の各要素はobjectである必要があります")
        val id = map["id"]?.toString()?.takeIf { it.isNotBlank() } ?: error("case に id が必要です")
        val setup = (map["setup"] as? List<*>)?.map { entry ->
            val m = entry as? Map<*, *> ?: error("case「$id」: setup の各要素はobjectである必要があります")
            EvalSetupFile(
                file = m["file"]?.toString()?.takeIf { it.isNotBlank() } ?: error("case「$id」: setup に file が必要です"),
                content = m["content"]?.toString() ?: error("case「$id」: setup に content が必要です"),
            )
        } ?: emptyList()
        setup.forEach {
            setupPath(it.file)
        }
        return EvalCase(
            id = id,
            name = map["name"]?.toString() ?: id,
            setup = setup,
            instruction = map["instruction"]?.toString(),
            expect = parseExpect(map["expect"], id),
        )
    }

    internal fun setupPath(file: String): String {
        val normalized = WorkspacePaths.relative(file).toString().replace('\\', '/')
        require(file.replace('\\', '/').split('/').none { it == ".." }) { "setup では親ディレクトリを指定できません: $file" }
        require(normalized.split('/').none { it.startsWith(".") } && !normalized.startsWith("_")) {
            "setup で予約ディレクトリ・隠しディレクトリは上書きできません: $file"
        }
        return normalized
    }

    private fun parseExpect(raw: Any?, caseId: String): EvalExpect {
        if (raw == null) return EvalExpect()
        val map = raw as? Map<*, *> ?: error("case「$caseId」: expect はobjectである必要があります")
        val status = map["status"]?.toString() ?: "submitted"
        require(status in allowedStatus) { "case「$caseId」: expect.status は ${allowedStatus.joinToString(" / ")} のいずれかです: $status" }
        val content = (map["content"] as? List<*>)?.map { entry ->
            val m = entry as? Map<*, *> ?: error("case「$caseId」: expect.content の各要素はobjectである必要があります")
            EvalContentCheck(
                file = m["file"]?.toString() ?: error("case「$caseId」: expect.content に file が必要です"),
                path = m["path"]?.toString() ?: "",
                exists = m["exists"] as? Boolean ?: true,
                contains = m["contains"]?.toString(),
                equals = m["equals"]?.toString(),
            )
        } ?: emptyList()
        fun globs(key: String): List<String> = when (val v = map[key]) {
            is List<*> -> v.map { it.toString() }
            null -> emptyList()
            else -> listOf(v.toString())
        }
        return EvalExpect(
            status = status,
            errorFree = map["errorFree"] as? Boolean,
            changedFiles = globs("changedFiles"),
            unchangedFiles = globs("unchangedFiles"),
            content = content,
        )
    }

    private fun parseVariant(raw: Any?): EvalVariant {
        val map = raw as? Map<*, *> ?: error("variants の各要素はobjectである必要があります")
        val name = map["name"]?.toString()?.takeIf { it.isNotBlank() } ?: error("variant に name が必要です")
        val modelMap = map["model"] as? Map<*, *>
        val provider = modelMap?.get("provider")?.toString()?.let { value ->
            AiProvider.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: error("variant「$name」: 未知のプロバイダです: $value")
        }
        return EvalVariant(
            name = name,
            provider = provider,
            model = modelMap?.get("id")?.toString(),
            agentId = map["agent"]?.toString(),
            instruction = map["instruction"]?.toString(),
            contextItems = if (map.containsKey("context")) Definitions.parseContextItems(map["context"]) else null,
        )
    }
}

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
