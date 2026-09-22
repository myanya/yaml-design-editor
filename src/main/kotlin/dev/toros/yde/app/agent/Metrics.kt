package dev.toros.yde.app.agent

import dev.toros.yde.app.schema.SchemaLoader
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt

/**
 * 実行メトリクス（R4-01）。`_runs` の実行記録を集計し、アクティビティの動作品質を可視化する。
 * 実行時に追加の記録は行わず、既存の実行記録（採否・自動適用・巻き戻しの追記を含む）だけから計算する。
 * 「人間による修正量」は直接測れないため、部分採用で却下されたファイルの割合を代理指標とする。
 */
object RunMetrics {

    /** 1件の実行記録から集計に使う項目を抜き出したもの。 */
    data class RunRecord(
        val file: String,
        val activityId: String,
        val model: String?,
        val status: String,
        val turns: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val cachedInputTokens: Long,
        val validationErrors: Int,
        val decided: Boolean,      // 採否（adoption）が記録済み
        val applied: Boolean,      // 採用して適用された
        val adoptedFiles: Int,
        val rejectedFiles: Int,
        val autoApplied: Boolean,
        val reverted: Boolean,
    )

    /** アクティビティ単位の集計。率は0〜1（小数3桁丸め）。 */
    data class ActivitySummary(
        val activityId: String,
        val runs: Int,
        val submitted: Int,
        val failed: Int,           // ABORTED / LIMIT / ERROR / CANCELLED
        val decided: Int,
        val adopted: Int,
        val adoptedFiles: Int,
        val rejectedFiles: Int,
        val autoApplied: Int,
        val reverted: Int,
        val totalTurns: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val cachedInputTokens: Long,
    ) {
        /** 提出率: ゲートを通って変更セットを提出できた実行の割合。 */
        val submitRate: Double get() = ratio(submitted, runs)
        /** 採用率: 採否が決まった実行のうち、採用（適用）された割合。 */
        val adoptionRate: Double get() = ratio(adopted, decided)
        /** 却下ファイル率: 部分採用で却下されたファイルの割合（人間による修正量の代理指標）。 */
        val rejectedFileRate: Double get() = ratio(rejectedFiles, adoptedFiles + rejectedFiles)
        /** 巻き戻し率: 自動適用のうち revert された割合（自動承認の信頼度）。 */
        val revertRate: Double get() = ratio(reverted, autoApplied)
        val avgTurns: Double get() = ratio(totalTurns, runs)
    }

    /** runs ゾーン配下の実行記録を読み込む。変更セット・評価レポート等の付随ファイルは除外する。 */
    fun collect(workspaceRoot: Path): List<RunRecord> {
        val dir = workspaceRoot.resolve(WorkspaceLayout.of(workspaceRoot).runsDir)
        if (!Files.isDirectory(dir)) return emptyList()
        val files = Files.list(dir).use { stream ->
            stream.filter { p ->
                val n = p.fileName.toString()
                Files.isRegularFile(p) && (n.endsWith(".yaml") || n.endsWith(".yml")) &&
                    !n.endsWith("-changeset.yaml")
            }.toList()
        }.sortedBy { it.fileName.toString() }
        return files.mapNotNull { file ->
            runCatching { parseRun(file) }.getOrNull()
        }
    }

    fun summarize(records: List<RunRecord>): List<ActivitySummary> =
        records.groupBy { it.activityId }.map { (id, runs) ->
            ActivitySummary(
                activityId = id,
                runs = runs.size,
                submitted = runs.count { it.status == RunStatus.SUBMITTED.name },
                failed = runs.count { it.status != RunStatus.SUBMITTED.name },
                decided = runs.count { it.decided },
                adopted = runs.count { it.applied },
                adoptedFiles = runs.sumOf { it.adoptedFiles },
                rejectedFiles = runs.sumOf { it.rejectedFiles },
                autoApplied = runs.count { it.autoApplied },
                reverted = runs.count { it.reverted },
                totalTurns = runs.sumOf { it.turns },
                inputTokens = runs.sumOf { it.inputTokens },
                outputTokens = runs.sumOf { it.outputTokens },
                cachedInputTokens = runs.sumOf { it.cachedInputTokens },
            )
        }.sortedBy { it.activityId }

    /** CLI・UI共通のYAML化（機械可読な集計レポート）。 */
    fun toYamlMap(summaries: List<ActivitySummary>): Map<String, Any?> = linkedMapOf(
        "activities" to summaries.map { s ->
            linkedMapOf(
                "activity" to s.activityId,
                "runs" to s.runs,
                "submitted" to s.submitted,
                "failed" to s.failed,
                "submitRate" to s.submitRate,
                "decided" to s.decided,
                "adopted" to s.adopted,
                "adoptionRate" to s.adoptionRate,
                "rejectedFileRate" to s.rejectedFileRate,
                "autoApplied" to s.autoApplied,
                "reverted" to s.reverted,
                "avgTurns" to s.avgTurns,
                "inputTokens" to s.inputTokens,
                "outputTokens" to s.outputTokens,
                "cachedInputTokens" to s.cachedInputTokens,
            )
        },
        "total" to linkedMapOf(
            "runs" to summaries.sumOf { it.runs },
            "submitted" to summaries.sumOf { it.submitted },
            "adopted" to summaries.sumOf { it.adopted },
            "inputTokens" to summaries.sumOf { it.inputTokens },
            "outputTokens" to summaries.sumOf { it.outputTokens },
            "cachedInputTokens" to summaries.sumOf { it.cachedInputTokens },
        ),
    )

    private fun parseRun(file: Path): RunRecord? {
        val root = YamlIO.parseToRaw(Files.readString(file)) as? Map<*, *> ?: return null
        // 実行記録の必須項目を欠くYAML（評価レポート等）は集計対象外
        val activityId = root["activity"]?.toString() ?: return null
        val status = root["status"]?.toString() ?: return null
        val usage = root["usage"] as? Map<*, *>
        val validation = root["validation"] as? Map<*, *>
        val adoption = root["adoption"] as? Map<*, *>
        return RunRecord(
            file = file.fileName.toString(),
            activityId = activityId,
            model = root["model"]?.toString(),
            status = status,
            turns = (root["turns"] as? Number)?.toInt() ?: 0,
            inputTokens = (usage?.get("inputTokens") as? Number)?.toLong() ?: 0,
            outputTokens = (usage?.get("outputTokens") as? Number)?.toLong() ?: 0,
            cachedInputTokens = (usage?.get("cachedInputTokens") as? Number)?.toLong() ?: 0,
            validationErrors = (validation?.get("errors") as? Number)?.toInt() ?: 0,
            decided = adoption != null,
            applied = adoption?.get("applied") == true,
            adoptedFiles = (adoption?.get("adoptedFiles") as? List<*>)?.size ?: 0,
            rejectedFiles = (adoption?.get("rejectedFiles") as? List<*>)?.size ?: 0,
            autoApplied = root["autoApply"] != null,
            reverted = root["reverted"] != null,
        )
    }

    private fun ratio(num: Int, den: Int): Double =
        if (den == 0) 0.0 else (num * 1000.0 / den).roundToInt() / 1000.0
}
