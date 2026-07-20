package agent

import ai.AiProvider
import ai.ToolChat
import core.Workspace
import core.WorkspaceScan
import core.changeSetToYaml
import index.IdIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import schema.SchemaLoader
import schema.globToRegex
import validate.Severity
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 自動化エンジン（R3-01〜R3-05）。トリガ監視・実行キュー・承認ポリシー・連鎖・リトライ・停止を司る。
 * - 実行は常に直列（同一文書への並行変更と索引競合を構造的に排除する）
 * - `approval: auto` はゲート通過・スコープ内・非編集中のときだけ自動適用し、条件を欠くと承認待ちへ降格する
 * - 自動適用の直後にファイルスタンプ基準を取り直し、自分の変更で on-change を再発火させない
 * - `tick(now)` / `processOnce()` を公開しており、テストはタイマーなしで決定的に検証できる
 */
class AutomationEngine(
    private val dir: Path,
    private val credentials: () -> Credentials,
    private val dirtyChecker: (Path) -> Boolean = { false },
    private val sessionFactory: SessionFactory = { provider, apiKey, model, system, tools ->
        ToolChat.newSession(provider, apiKey, model, system, tools)
    },
    private val maxChainDepth: Int = 5,
) {
    /** 実行時に参照する接続情報（UI: AiSettings、CLI: 環境変数から構築）。 */
    data class Credentials(
        val provider: AiProvider,
        val models: Map<AiProvider, String> = emptyMap(),
        val apiKeys: Map<AiProvider, String> = emptyMap(),
    )

    data class QueueItem(
        val activityId: String,
        val reason: String,
        val extraInstruction: String? = null,
        val attempt: Int = 1,
        val chainDepth: Int = 0,
        val visited: Set<String> = emptySet(),
        val lastFailure: String? = null,
    )

    /** 人間の承認待ちの実行結果。変更セットは実行記録の隣にもファイル保存される（耐久性）。 */
    class PendingReview(
        val id: String,
        val activityId: String,
        val activityName: String,
        val reason: String,
        val result: AgentRunResult,
        val runFile: Path?,
        val ws: Workspace,
        val chainDepth: Int,
        val visited: Set<String>,
    )

    data class HistoryEntry(val atMillis: Long, val activityId: String, val status: String, val detail: String?)

    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    var runningActivity: String? = null
        private set

    /** UIへの更新通知（Composeのスナップショット状態を進めるために使う）。 */
    @Volatile
    var onUpdate: () -> Unit = {}

    /** CLI等でログを逐次表示したい場合のシンク。 */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    private val lock = Any()
    private val queue = ArrayDeque<QueueItem>()
    private val pendingReviews = mutableListOf<PendingReview>()
    private val history = mutableListOf<HistoryEntry>()
    private val logLines = ArrayDeque<String>()
    private var stamps: Map<Path, Long> = emptyMap()

    /** on-change / on-invalid の待機中変更: activityId -> (最初の検出時刻, 変更ファイル相対パス) */
    private val pendingChanges = mutableMapOf<String, Pair<Long, MutableSet<String>>>()
    private val lastScheduled = mutableMapOf<String, Long>()
    private var currentJob: Job? = null
    private var loopScope: CoroutineScope? = null

    // ---------------------------------------------------------------- UI用スナップショット

    fun queueSnapshot(): List<QueueItem> = synchronized(lock) { queue.toList() }
    fun pendingSnapshot(): List<PendingReview> = synchronized(lock) { pendingReviews.toList() }
    fun historySnapshot(limit: Int = 30): List<HistoryEntry> = synchronized(lock) { history.takeLast(limit).reversed() }
    fun logSnapshot(limit: Int = 60): List<String> = synchronized(lock) { logLines.takeLast(limit) }

    // ---------------------------------------------------------------- 制御

    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) {
            // 有効化前から存在する差分では発火させない（基準の取り直し）
            stamps = scanStamps()
            log("自動実行を有効化しました（トリガ監視を開始）")
        } else {
            log("自動実行を無効化しました")
        }
        notifyUpdate()
    }

    /** 常時停止: 監視を止め、キューを消去し、実行中の1件をキャンセルする（承認待ちは残す）。 */
    fun stopAll() {
        enabled = false
        synchronized(lock) {
            queue.clear()
            pendingChanges.clear()
        }
        currentJob?.cancel()
        log("停止: キューを消去し、実行中の処理をキャンセルしました")
        notifyUpdate()
    }

    /** 実行キューへ追加する。同一アクティビティが待機・実行中の場合は追加しない。 */
    fun enqueue(
        activityId: String,
        reason: String,
        extraInstruction: String? = null,
        chainDepth: Int = 0,
        visited: Set<String> = emptySet(),
    ): Boolean {
        synchronized(lock) {
            if (queue.any { it.activityId == activityId } || runningActivity == activityId) {
                log("スキップ: $activityId は既に待機中または実行中です")
                return false
            }
            queue.addLast(QueueItem(activityId, reason, extraInstruction, chainDepth = chainDepth, visited = visited))
        }
        log("キュー追加: $activityId（$reason）")
        notifyUpdate()
        return true
    }

    // ---------------------------------------------------------------- トリガ（R3-01）

    /** トリガ判定を1回行う。時刻を引数に取るためテストから決定的に呼べる。 */
    fun tick(now: Long = System.currentTimeMillis()) {
        if (!enabled) return
        val defs = Definitions.load(dir)
        detectChanges(now, defs)
        checkSchedules(now, defs)
    }

    private fun relOf(p: Path): String = IdIndex.relPathOf(dir, p)

    private fun isReserved(rel: String): Boolean = listOf(
        SchemaLoader.SCHEMA_DIR, SchemaLoader.AGENTS_DIR, SchemaLoader.ACTIVITIES_DIR,
        SchemaLoader.RUNS_DIR, SchemaLoader.EVALS_DIR, SchemaLoader.SKILLS_DIR,
        SchemaLoader.VIEWS_DIR,
    ).any { rel.startsWith("$it/") }

    private fun scanStamps(): Map<Path, Long> =
        WorkspaceScan.stamps(dir).filterKeys { !isReserved(relOf(it)) }

    private fun detectChanges(now: Long, defs: Definitions.LoadResult) {
        val next = scanStamps()
        val changed = (next.keys + stamps.keys).filter { next[it] != stamps[it] }.map { relOf(it) }
        stamps = next
        if (changed.isNotEmpty()) {
            for (activity in defs.activities) {
                if (activity.trigger.type != TriggerType.ON_CHANGE && activity.trigger.type != TriggerType.ON_INVALID) continue
                val glob = activity.trigger.match ?: activity.targetMatch ?: "**/*.{yaml,yml}"
                val regex = runCatching { globToRegex(glob) }.getOrNull() ?: continue
                val hits = changed.filter { regex.matches(it) }
                if (hits.isNotEmpty()) {
                    val entry = pendingChanges.getOrPut(activity.id) { now to mutableSetOf() }
                    entry.second.addAll(hits)
                }
            }
        }
        // デバウンス経過分を発火させる
        val ready = pendingChanges.filterKeys { id ->
            val activity = defs.activity(id) ?: return@filterKeys true
            now - pendingChanges.getValue(id).first >= activity.trigger.debounceSeconds * 1000
        }
        for ((id, pending) in ready) {
            pendingChanges.remove(id)
            val activity = defs.activity(id) ?: continue
            val files = pending.second.sorted()
            if (activity.trigger.type == TriggerType.ON_INVALID) {
                // 変更されたファイルに検証エラーがあるときだけ起動する
                val ws = runCatching { Workspace.open(dir) }.getOrNull() ?: continue
                val errors = files.sumOf { rel ->
                    runCatching { ws.validate(rel) }.getOrDefault(emptyList()).count { it.severity == Severity.ERROR }
                }
                if (errors == 0) {
                    log("$id: 変更ファイルに検証エラーがないため起動しません")
                    continue
                }
                enqueue(
                    id, "検証エラー $errors 件（変更 ${files.size} ファイル）",
                    extraInstruction = "次の変更されたファイルに検証エラーがあります: ${files.take(20).joinToString(", ")}",
                )
            } else {
                enqueue(
                    id, "ファイル更新 ${files.size} 件",
                    extraInstruction = "変更されたファイル: ${files.take(20).joinToString(", ")}",
                )
            }
        }
    }

    private fun checkSchedules(now: Long, defs: Definitions.LoadResult) {
        for (activity in defs.activities) {
            if (activity.trigger.type != TriggerType.SCHEDULE) continue
            val every = (activity.trigger.everyMinutes ?: continue) * 60_000
            val last = lastScheduled.getOrPut(activity.id) { now }  // 有効化直後は起動しない
            if (now - last >= every) {
                lastScheduled[activity.id] = now
                enqueue(activity.id, "スケジュール（${activity.trigger.everyMinutes}分毎）")
            }
        }
    }

    // ---------------------------------------------------------------- 実行（R3-05: 直列）

    /** キューの先頭を1件実行する。実行した場合 true。 */
    suspend fun processOnce(): Boolean {
        val item = synchronized(lock) {
            if (runningActivity != null) return false
            queue.removeFirstOrNull()?.also { runningActivity = it.activityId }
        } ?: return false
        try {
            executeItem(item)
        } finally {
            runningActivity = null
            notifyUpdate()
        }
        return true
    }

    private suspend fun executeItem(item: QueueItem) {
        val defs = Definitions.load(dir)
        val activity = defs.activity(item.activityId)
            ?: return recordHistory(item.activityId, "スキップ", "アクティビティ定義が見つかりません")
        val agentDef = defs.agent(activity.agentId)
            ?: return recordHistory(item.activityId, "スキップ", "エージェント「${activity.agentId}」が見つかりません")
        val creds = credentials()
        val provider = agentDef.provider ?: creds.provider
        val apiKey = creds.apiKeys[provider].orEmpty()
        if (apiKey.isBlank()) return recordHistory(activity.id, "スキップ", "${provider.label} のAPIキーが未設定です")
        val model = agentDef.model?.takeIf { agentDef.provider == null || agentDef.provider == provider }
            ?: creds.models[provider]?.takeIf { it.isNotBlank() }
            ?: provider.defaultModel

        val ws = Workspace.open(dir)
        val request = AgentRunRequest(
            activity = activity, agent = agentDef,
            provider = provider, model = model, apiKey = apiKey,
            extraInstruction = item.extraInstruction,
            apiKeys = creds.apiKeys, subAgents = defs.agents,
        )
        log("実行開始: ${activity.id}（試行 ${item.attempt}/${activity.retry.maxAttempts}、契機: ${item.reason}）")
        val host = object : AgentHost {
            override suspend fun askHuman(question: String): String? {
                log("[${activity.id}] 質問（無人実行のため回答できません）: ${question.take(120)}")
                return null
            }
            override suspend fun onEvent(event: AgentEvent) {
                when (event) {
                    is AgentEvent.TurnStarted -> log("[${activity.id}] ターン ${event.turn}/${event.maxTurns}")
                    is AgentEvent.ToolInvoked -> log("[${activity.id}] 🔧 ${event.name}")
                    is AgentEvent.ToolFailed -> log("[${activity.id}] ⚠ ${event.name}: ${event.message.take(120)}")
                    is AgentEvent.Finished -> log("[${activity.id}] 終了: ${event.status}")
                    else -> Unit
                }
            }
        }
        val result = AgentKernel.run(ws, request, host, sessionFactory)
        val runFile = runCatching { RunLog.write(ws, request, result) }.getOrNull()

        when {
            result.status == RunStatus.SUBMITTED && result.changeSet.changes.isEmpty() -> {
                recordHistory(activity.id, "完了", "変更なし — ${result.message ?: ""}")
                chainNext(activity, item, success = true)
            }
            result.status == RunStatus.SUBMITTED && activity.approval == ApprovalMode.AUTO -> {
                val dirty = result.changeSet.changes.filter { change ->
                    runCatching { dirtyChecker(ws.resolve(change.file)) }.getOrDefault(false)
                }
                if (dirty.isNotEmpty()) {
                    addPendingReview(
                        activity, item, result, runFile, ws,
                        reason = "エディタで編集中のファイルがあるため承認待ちへ降格: ${dirty.joinToString(", ") { it.file }}",
                    )
                } else {
                    val applied = AutoApply.applyWithBackup(
                        ws, result.changeSet, runFile,
                        requireValid = activity.gate == ValidationGate.ERROR_FREE,
                    )
                    if (applied.ok) {
                        stamps = scanStamps()  // 自己変更で on-change を再発火させない
                        recordHistory(activity.id, "自動適用", "${result.changeSet.changes.size} ファイル — ${result.message ?: ""}")
                        chainNext(activity, item, success = true)
                    } else {
                        val why = applied.results.mapNotNull { it.message }.joinToString("; ").ifBlank { "適用に失敗しました" }
                        handleFailure(activity, item, "適用失敗: $why")
                    }
                }
            }
            result.status == RunStatus.SUBMITTED -> addPendingReview(activity, item, result, runFile, ws, item.reason)
            else -> handleFailure(activity, item, "${result.status}: ${result.message ?: ""}${validationSummary(result)}")
        }
    }

    private fun validationSummary(result: AgentRunResult): String {
        val errors = result.issues.flatMap { (rel, list) ->
            list.filter { it.severity == Severity.ERROR }.map { "$rel: ${it.message}" }
        }
        return if (errors.isEmpty()) "" else "\n残存する検証エラー:\n" + errors.take(10).joinToString("\n") { "- $it" }
    }

    private fun addPendingReview(
        activity: ActivityDef,
        item: QueueItem,
        result: AgentRunResult,
        runFile: Path?,
        ws: Workspace,
        reason: String,
    ) {
        val review = PendingReview(
            id = runFile?.fileName?.toString() ?: "run-${System.currentTimeMillis()}",
            activityId = activity.id,
            activityName = activity.name,
            reason = reason,
            result = result,
            runFile = runFile,
            ws = ws,
            chainDepth = item.chainDepth,
            visited = item.visited,
        )
        synchronized(lock) { pendingReviews += review }
        // 耐久性: アプリを閉じても変更セットを失わないよう、実行記録の隣に保存する
        runFile?.let { rf ->
            runCatching { RunLog.writeChangeSetFile(rf, changeSetToYaml(result.changeSet)) }
        }
        recordHistory(activity.id, "承認待ち", reason)
    }

    /** 承認待ちの採否が決まったときに呼ぶ（レビューUIから）。連鎖はここで発火する。 */
    fun completeReview(review: PendingReview, adoptedFiles: List<String>, applied: Boolean) {
        synchronized(lock) { pendingReviews.removeIf { it.id == review.id } }
        val success = applied && adoptedFiles.isNotEmpty()
        recordHistory(review.activityId, if (success) "採用" else "却下", "採用 ${adoptedFiles.size} ファイル")
        if (applied) stamps = scanStamps()
        val defs = Definitions.load(dir)
        defs.activity(review.activityId)?.let { activity ->
            chainNext(activity, QueueItem(review.activityId, "承認待ちの採否", chainDepth = review.chainDepth, visited = review.visited), success)
        }
        notifyUpdate()
    }

    /** 手動実行（実行ダイアログ）の採否通知。エンジン有効時のみ連鎖を発火させる。 */
    fun notifyManualCompletion(activityId: String, success: Boolean) {
        if (!enabled) return
        val defs = Definitions.load(dir)
        defs.activity(activityId)?.let { chainNext(it, QueueItem(activityId, "手動実行"), success) }
    }

    // ---------------------------------------------------------------- 連鎖（R3-02）とリトライ（R3-03）

    private fun chainNext(activity: ActivityDef, item: QueueItem, success: Boolean) {
        val next = (if (success) activity.chain.onSuccess else activity.chain.onFailure) ?: return
        if (item.chainDepth + 1 > maxChainDepth) {
            recordHistory(next, "スキップ", "連鎖の深さ上限（$maxChainDepth）に達しました")
            return
        }
        if (next == activity.id || next in item.visited) {
            recordHistory(next, "スキップ", "連鎖の循環を検出しました（${(item.visited + activity.id).joinToString(" → ")} → $next）")
            return
        }
        enqueue(
            next,
            "連鎖: ${activity.id} の${if (success) "成功" else "失敗"}",
            chainDepth = item.chainDepth + 1,
            visited = item.visited + activity.id,
        )
    }

    private fun handleFailure(activity: ActivityDef, item: QueueItem, message: String) {
        // 収束判定: 同じ失敗を繰り返した場合は試行を打ち切る
        if (item.lastFailure != null && item.lastFailure == message) {
            recordHistory(activity.id, "失敗", "同一の失敗を繰り返したため中断: ${message.take(200)}")
            chainNext(activity, item, success = false)
            return
        }
        if (item.attempt < activity.retry.maxAttempts) {
            recordHistory(activity.id, "再試行", "試行 ${item.attempt} が失敗: ${message.take(200)}")
            val feedback = buildString {
                item.extraInstruction?.let { appendLine(it) }
                appendLine("前回（試行 ${item.attempt}）は失敗しました: $message")
                append("同じ失敗を繰り返さないよう、原因を踏まえて進めてください。")
            }
            // 同一アクティビティの再試行は enqueue の重複ガードを通さず直接積む
            synchronized(lock) {
                queue.addLast(item.copy(attempt = item.attempt + 1, extraInstruction = feedback, lastFailure = message))
            }
            notifyUpdate()
        } else {
            recordHistory(activity.id, "失敗", message.take(300))
            chainNext(activity, item, success = false)
        }
    }

    // ---------------------------------------------------------------- 常駐ループ

    /** 監視と実行の常駐ループを開始する。停止はスコープのキャンセルか stopAll()。 */
    fun start(scope: CoroutineScope): Job {
        loopScope = scope
        return scope.launch {
            while (isActive) {
                if (!enabled) {
                    delay(500)
                    continue
                }
                runCatching { tick(System.currentTimeMillis()) }
                    .onFailure { log("トリガ判定でエラー: ${it.message}") }
                val worked = runCatching { processCancellable() }
                    .onFailure { log("実行でエラー: ${it.message}") }
                    .getOrDefault(false)
                if (!worked) delay(1_000)
            }
        }
    }

    /** 実行中の1件だけを stopAll() でキャンセルできるよう、子ジョブとして実行する。 */
    private suspend fun processCancellable(): Boolean {
        val scope = loopScope ?: return processOnce()
        var executed = false
        val job = scope.launch { executed = processOnce() }
        currentJob = job
        job.join()
        currentJob = null
        return executed
    }

    // ---------------------------------------------------------------- 記録

    private fun recordHistory(activityId: String, status: String, detail: String?) {
        synchronized(lock) {
            history += HistoryEntry(System.currentTimeMillis(), activityId, status, detail)
            while (history.size > 100) history.removeAt(0)
        }
        log("$activityId: $status${detail?.let { " — ${it.lineSequence().first()}" } ?: ""}")
    }

    private fun log(message: String) {
        val line = "${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))} $message"
        synchronized(lock) {
            logLines.addLast(line)
            while (logLines.size > 200) logLines.removeFirst()
        }
        logSink?.invoke(line)
        notifyUpdate()
    }

    private fun notifyUpdate() {
        runCatching { onUpdate() }
    }
}
