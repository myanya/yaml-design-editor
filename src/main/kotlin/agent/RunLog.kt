package agent

import core.Workspace
import schema.SchemaLoader
import validate.Severity
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 実行記録（R2-08）。1回のエージェント実行を `_runs/` にYAML文書として保存する。
 * 実行の透明性（何を読み、何を書き、何を質問したか）の確保と、
 * 自己改善（P4: 採用率・失敗率・コストの集計）の材料が目的。`_runs/` は索引対象外。
 */
object RunLog {

    private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** 会話記録（transcript）1件あたりの保存上限。巨大なツール結果（全文読取り等）による記録の肥大を防ぐ。 */
    private const val TRANSCRIPT_ENTRY_LIMIT = 20_000

    /** extras は末尾に追記する追加セクション（例: 生成実行の入出力メッセージ `exchange`）。 */
    fun write(ws: Workspace, request: AgentRunRequest, result: AgentRunResult, extras: Map<String, Any?> = emptyMap()): Path {
        val dir = ws.root.resolve(ws.layout.runsDir)
        Files.createDirectories(dir)
        var file = dir.resolve("${LocalDateTime.now().format(fileStamp)}-${request.activity.id}.yaml")
        var seq = 1
        while (Files.exists(file)) {
            file = dir.resolve("${LocalDateTime.now().format(fileStamp)}-${request.activity.id}-${seq++}.yaml")
        }
        val issues = result.issues.flatMap { (rel, list) -> list.map { rel to it } }
        val body = linkedMapOf<String, Any?>(
            "activity" to request.activity.id,
            "agent" to request.agent.id,
            "model" to "${request.provider.name.lowercase()}/${request.model}",
            "finishedAt" to ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "status" to result.status.name,
            "message" to result.message,
            "turns" to result.turnsUsed,
            "usage" to linkedMapOf(
                "inputTokens" to result.usage.inputTokens,
                "outputTokens" to result.usage.outputTokens,
            ),
            "plan" to result.plan,
            "notes" to result.notes,
            "questions" to result.questions.map { (q, a) ->
                linkedMapOf("question" to q, "answer" to a)
            },
            "toolCalls" to result.toolLog.map { entry ->
                linkedMapOf(
                    "turn" to entry.turn,
                    "tool" to entry.name,
                    "arguments" to entry.arguments,
                    "result" to entry.result.take(500),
                    "failed" to entry.failed,
                )
            },
            // 会話記録: モデルが見た・出したメッセージの全文（toolCalls は要約、こちらがデバッグの主材料）
            "transcript" to result.transcript.map { entry ->
                LinkedHashMap<String, Any>().apply {
                    put("turn", entry.turn)
                    put("role", entry.role)
                    entry.tool?.let { put("tool", it) }
                    put("content", entry.content.take(TRANSCRIPT_ENTRY_LIMIT))
                }
            },
            "changes" to result.changeSet.changes.map { change ->
                linkedMapOf(
                    "file" to change.file,
                    "create" to change.create,
                    "ops" to change.ops.size,
                )
            },
            "validation" to linkedMapOf(
                "errors" to issues.count { it.second.severity == Severity.ERROR },
                "warnings" to issues.count { it.second.severity == Severity.WARNING },
            ),
        )
        body.putAll(extras)
        Files.writeString(file, YamlIO.dump(YamlIO.fromAny(cleanNulls(body))))
        return file
    }

    /** 採否の結果を実行記録へ追記する（レビュー後に呼ぶ）。 */
    fun appendAdoption(runFile: Path, adoptedFiles: List<String>, rejectedFiles: List<String>, applied: Boolean) {
        appendSection(runFile, "adoption", linkedMapOf(
            "applied" to applied,
            "adoptedFiles" to adoptedFiles,
            "rejectedFiles" to rejectedFiles,
            "decidedAt" to ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        ))
    }

    /** 自動適用の結果とバックアップ（適用前の原文）を実行記録へ追記する（R3-04）。 */
    fun appendAutoApply(runFile: Path, backup: List<Map<String, Any?>>) {
        appendSection(runFile, "autoApply", linkedMapOf(
            "appliedAt" to ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "backup" to backup,
        ))
    }

    /** 巻き戻しの実施を実行記録へ追記する。 */
    fun appendReverted(runFile: Path) {
        appendSection(runFile, "reverted", linkedMapOf(
            "revertedAt" to ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        ))
    }

    /** 承認待ちの変更セットを実行記録の隣に保存する（アプリ終了後もレビュー・適用できる耐久性のため）。 */
    fun writeChangeSetFile(runFile: Path, changeSetYaml: String): Path {
        val target = runFile.resolveSibling(runFile.fileName.toString().removeSuffix(".yaml") + "-changeset.yaml")
        Files.writeString(target, changeSetYaml)
        return target
    }

    private fun appendSection(runFile: Path, key: String, value: Any) {
        if (!Files.isRegularFile(runFile)) return
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val root = YamlIO.parseToRaw(Files.readString(runFile)) as? MutableMap<Any?, Any?> ?: return
            root[key] = value
            Files.writeString(runFile, YamlIO.dump(YamlIO.fromAny(root)))
        }
    }

    private fun cleanNulls(map: Map<String, Any?>): Map<String, Any?> =
        map.filterValues { it != null && (it !is List<*> || it.isNotEmpty()) }
}
