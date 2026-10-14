package dev.toros.yde.app.agent

import dev.toros.yde.app.core.Workspace
import dev.toros.yde.app.schema.SchemaLoader
import dev.toros.yde.yaml.validate.Severity
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 実行記録（R2-08）。1回のエージェント実行を `_runs/` にYAML文書として保存する。
 * 実行の透明性（何を読み、何を書き、何を質問したか）の確保と、
 * 自己改善（P4: 採用率・失敗率・コストの集計）の材料が目的。`_runs/` は索引対象外。
 */
object RunLog {

    /** 定義の ID が保存先の階層を変えないようにし、既存のリンクも検査する。 */
    internal fun destination(dir: Path, name: String): Path {
        require('/' !in name && '\\' !in name) { "実行記録の名前にパスは指定できません: $name" }
        return dev.toros.yde.workspace.WorkspacePaths.resolve(dir, name)
    }

    private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** 会話記録（transcript）1件あたりの保存上限。巨大なツール結果（全文読取り等）による記録の肥大を防ぐ。 */
    private const val TRANSCRIPT_ENTRY_LIMIT = 20_000

    /** extras は末尾に追記する追加セクション（例: 生成実行の入出力メッセージ `exchange`）。 */
    fun write(ws: Workspace, request: AgentRunRequest, result: AgentRunResult, extras: Map<String, Any?> = emptyMap()): Path {
        val dir = ws.resolve(ws.layout.runsDir)
        Files.createDirectories(dir)
        var file = destination(dir, "${LocalDateTime.now().format(fileStamp)}-${request.activity.id}.yaml")
        var seq = 1
        while (Files.exists(file)) {
            file = destination(dir, "${LocalDateTime.now().format(fileStamp)}-${request.activity.id}-${seq++}.yaml")
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
            "usage" to LinkedHashMap<String, Any>().apply {
                put("inputTokens", result.usage.inputTokens)
                put("outputTokens", result.usage.outputTokens)
                // キャッシュ読取（入力の内数。architecture/agents.md「コンテキスト」）。報告のないプロバイダでは出力しない
                if (result.usage.cachedInputTokens > 0) put("cachedInputTokens", result.usage.cachedInputTokens)
            },
            // コンテキスト会計（R19-01）: 毎ターン再送される固定部の内訳と、送信ごとの入力トークン実測
            "context" to (if (result.contextStats.isEmpty()) null else linkedMapOf(
                "sections" to result.contextStats.map { stat ->
                    linkedMapOf("section" to stat.section, "chars" to stat.chars, "tokensEst" to stat.tokensEst)
                },
                "turnInputTokens" to result.turnInputTokens,
            )),
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
                    "resultChars" to entry.resultChars,   // 打切り前の全長（会計。以降の全ターンに乗る履歴コスト）
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
    fun appendAutoApply(runFile: Path, backup: List<Map<String, Any?>>, state: String) {
        writeSection(runFile, "autoApply", linkedMapOf(
            "state" to state,
            (if (state == "applied") "appliedAt" else "preparedAt") to ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "backup" to backup,
        ), requireAbsent = state == "prepared")
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
        runCatching { writeSection(runFile, key, value) }
    }

    /** バックアップは失敗を呼出元へ返す。既存ログを直接上書きせず、永続化した一時ファイルと置き換える。 */
    private fun writeSection(runFile: Path, key: String, value: Any, requireAbsent: Boolean = false) {
        require(Files.isRegularFile(runFile)) { "実行記録が存在しません: $runFile" }
        val root = (YamlIO.parseToRaw(Files.readString(runFile)) as? Map<*, *>)?.toMutableMap()
            ?: error("実行記録がオブジェクトではありません: $runFile")
        require(!requireAbsent || key !in root) { "既存のバックアップは上書きできません: $runFile" }
        root[key] = value
        val temp = Files.createTempFile(runFile.toAbsolutePath().parent, ".run-", ".tmp")
        try {
            val text = YamlIO.dump(YamlIO.fromAny(root))
            // 復元経路の読込上限を超えるバックアップで原本を書き換えない。
            YamlIO.parseToRaw(text)
            Files.writeString(temp, text)
            FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
            // 原子的置換が使えない環境では、バックアップ保証のため適用を中止する。
            Files.move(temp, runFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun cleanNulls(map: Map<String, Any?>): Map<String, Any?> =
        map.filterValues { it != null && (it !is List<*> || it.isNotEmpty()) }
}
