package dev.toros.yde.agent

import dev.toros.yde.app.core.changeSetToYaml
import dev.toros.yde.agent.llm.AiProvider
import dev.toros.yde.agent.llm.ToolChat
import dev.toros.yde.app.core.FileWatcher
import dev.toros.yde.app.core.Workspace
import dev.toros.yde.app.core.WorkspaceScan
import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.workspace.layout.WorkspaceLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import dev.toros.yde.app.schema.SchemaLoader
import dev.toros.yde.semantic.schema.globToRegex
import dev.toros.yde.yaml.validate.Severity
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
        /**
         * 一括実行（foreach。R18-01）の対象キー。非null のとき重複ガードは
         * （アクティビティ×対象）単位になり、同一アクティビティを対象違いで複数積める。
         */
        val targetKey: String? = null,
        /** foreach 対象のプレースホルダ値（コンテキストレシピの解決に渡す。R19-02）。 */
        val targetValues: Map<String, String> = emptyMap(),
    )

    /** 一括投入（enqueueForeach）の結果。total は投入対象の件数、enqueued は実際に積めた数。 */
    data class ForeachExpansion(val total: Int, val enqueued: Int, val skipped: Int, val error: String? = null)

    /** 一括実行の対象プレビュー（選択UI用）。items は limit で切った展開分、total は条件に一致した全件数。 */
    data class ForeachPreviewResult(val items: List<ForeachItem>, val total: Int, val error: String? = null)

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

    /** 実行中項目の対象キー（一括実行の重複ガード用）。 */
    @Volatile
    private var runningTarget: String? = null

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

    /**
     * 実行キューへ追加する。重複ガード: targetKey なしは同一アクティビティが待機・実行中なら追加しない。
     * targetKey あり（一括実行の項目）は同一（アクティビティ×対象）のときだけ追加しない。
     */
    fun enqueue(
        activityId: String,
        reason: String,
        extraInstruction: String? = null,
        chainDepth: Int = 0,
        visited: Set<String> = emptySet(),
        targetKey: String? = null,
        targetValues: Map<String, String> = emptyMap(),
    ): Boolean {
        synchronized(lock) {
            val duplicate =
                if (targetKey == null) queue.any { it.activityId == activityId } || runningActivity == activityId
                else queue.any { it.activityId == activityId && it.targetKey == targetKey } ||
                    (runningActivity == activityId && runningTarget == targetKey)
            if (duplicate) {
                log("スキップ: $activityId${targetKey?.let { "（対象 $it）" } ?: ""} は既に待機中または実行中です")
                return false
            }
            queue.addLast(
                QueueItem(
                    activityId, reason, extraInstruction,
                    chainDepth = chainDepth, visited = visited, targetKey = targetKey, targetValues = targetValues,
                )
            )
        }
        log("キュー追加: $activityId（$reason）")
        notifyUpdate()
        return true
    }

    /**
     * 一括実行（foreach。R18-01）の対象列挙だけを行う（選択UI用）。
     * 列挙はこの呼び出し時点のスナップショット——投入までに原本が変わっても追いかけない。
     */
    fun previewForeach(activityId: String): ForeachPreviewResult {
        val defs = Definitions.load(dir)
        val activity = defs.activity(activityId)
            ?: return ForeachPreviewResult(emptyList(), 0, "アクティビティ「$activityId」が見つかりません")
        val foreachDef = activity.foreach
            ?: return ForeachPreviewResult(emptyList(), 0, "アクティビティ「$activityId」に foreach 定義がありません")
        val enumeration = runCatching { Foreach.enumerate(Workspace.open(dir), foreachDef) }
            .getOrElse { return ForeachPreviewResult(emptyList(), 0, "対象の列挙に失敗しました: ${it.message}") }
        return ForeachPreviewResult(enumeration.items, enumeration.total)
    }

    /** 列挙済みの項目（選択後のサブセットでよい）を1件=1実行としてキューへ投入する。 */
    fun enqueueForeachItems(activityId: String, items: List<ForeachItem>): ForeachExpansion {
        var enqueued = 0
        items.forEachIndexed { i, item ->
            val added = enqueue(
                activityId,
                "一括 ${i + 1}/${items.size}: ${item.label}",
                extraInstruction = item.instruction,
                targetKey = item.key,
                targetValues = item.values,
            )
            if (added) enqueued++
        }
        log("一括投入: $activityId — ${items.size} 件中 $enqueued 件をキューへ追加しました")
        return ForeachExpansion(items.size, enqueued, items.size - enqueued)
    }

    /**
     * 一括実行（foreach。R18-01): 対象を列挙し、全件をキューへ展開する（CLI・無選択の投入用）。
     * 展開はこの呼び出し時の1回だけ（実行中の変更で対象が増えても追いかけない。再投入は再度この操作を行う）。
     */
    fun enqueueForeach(activityId: String): ForeachExpansion {
        val preview = previewForeach(activityId)
        preview.error?.let { return ForeachExpansion(0, 0, 0, it) }
        val result = enqueueForeachItems(activityId, preview.items)
        return ForeachExpansion(preview.total, result.enqueued, result.skipped)
    }

    // ---------------------------------------------------------------- トリガ（R3-01）

    /**
     * トリガ判定を1回行う。時刻を引数に取るためテストから決定的に呼べる。
     * scanChanges = false ではファイルスタンプの全走査を省略する（デバウンス済み変更の発火と
     * スケジュール判定だけ行う）。ネイティブ監視（R8-03）が「変更なし」と分かっているときの節約用。
     */
    fun tick(now: Long = System.currentTimeMillis(), scanChanges: Boolean = true) {
        if (!enabled) return
        val defs = Definitions.load(dir)
        detectChanges(now, defs, scanChanges)
        checkSchedules(now, defs)
    }

    private fun relOf(p: Path): String = IdIndex.relPathOf(dir, p)

    private fun scanStamps(): Map<Path, Long> {
        val layout = WorkspaceLayout.of(dir)
        return WorkspaceScan.stamps(dir, layout).filterKeys { !layout.isReserved(relOf(it)) }
    }

    private fun detectChanges(now: Long, defs: Definitions.LoadResult, scan: Boolean = true) {
        val changed = if (scan) {
            val next = scanStamps()
            val diff = (next.keys + stamps.keys).filter { next[it] != stamps[it] }.map { relOf(it) }
            stamps = next
            diff
        } else emptyList()
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
            queue.removeFirstOrNull()?.also {
                runningActivity = it.activityId
                runningTarget = it.targetKey
            }
        } ?: return false
        try {
            executeItem(item)
        } finally {
            runningActivity = null
            runningTarget = null
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
            targetValues = item.targetValues,
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
                recordHistory(activity.id, "完了", "${targetPrefix(item)}変更なし — ${result.message ?: ""}")
                chainNext(activity, item, success = true, result = result)
            }
            result.status == RunStatus.SUBMITTED && activity.approval == ApprovalMode.AUTO -> {
                val dirty = result.changeSet.changes.filter { change ->
                    runCatching { dirtyChecker(ws.resolve(change.file)) }.getOrDefault(false)
                }
                // 派生スキーマの導出元文書（R11-03）への書込は検証ルールを無人で変えるため、自動適用しない
                val deriveSources = result.changeSet.changes.filter { change ->
                    ws.schemas.any { s -> s.derive?.matchesSource(change.file) == true }
                }
                if (deriveSources.isNotEmpty()) {
                    addPendingReview(
                        activity, item, result, runFile, ws,
                        reason = "検証スキーマの導出元文書を変更するため承認待ちへ降格: ${deriveSources.joinToString(", ") { it.file }}",
                    )
                } else if (dirty.isNotEmpty()) {
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
                        recordHistory(activity.id, "自動適用", "${targetPrefix(item)}${result.changeSet.changes.size} ファイル — ${result.message ?: ""}")
                        chainNext(activity, item, success = true, result = result)
                    } else {
                        val why = applied.results.mapNotNull { it.message }.joinToString("; ").ifBlank { "適用に失敗しました" }
                        handleFailure(activity, item, "適用失敗: $why", result)
                    }
                }
            }
            result.status == RunStatus.SUBMITTED -> addPendingReview(activity, item, result, runFile, ws, item.reason)
            else -> handleFailure(activity, item, "${result.status}: ${result.message ?: ""}${validationSummary(result)}", result)
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
            chainNext(
                activity,
                QueueItem(review.activityId, "承認待ちの採否", chainDepth = review.chainDepth, visited = review.visited),
                success,
                result = review.result,
                adoptedFiles = adoptedFiles,
            )
        }
        notifyUpdate()
    }

    /** 手動実行（実行ダイアログ）の採否通知。エンジン有効時のみ連鎖を発火させる。 */
    fun notifyManualCompletion(
        activityId: String,
        success: Boolean,
        result: AgentRunResult? = null,
        adoptedFiles: List<String>? = null,
    ) {
        if (!enabled) return
        val defs = Definitions.load(dir)
        defs.activity(activityId)?.let {
            chainNext(it, QueueItem(activityId, "手動実行"), success, result = result, adoptedFiles = adoptedFiles)
        }
    }

    // ---------------------------------------------------------------- 連鎖（R3-02）とリトライ（R3-03）

    /**
     * 連鎖先の決定と発火。R9-04: chain.rules（出力値による分岐）を先に評価し、
     * 不一致なら onSuccess / onFailure へフォールバックする。深さ上限・循環検出は分岐先にも同様に効く。
     */
    private fun chainNext(
        activity: ActivityDef,
        item: QueueItem,
        success: Boolean,
        result: AgentRunResult? = null,
        adoptedFiles: List<String>? = null,
    ) {
        val changedFiles = adoptedFiles ?: result?.changeSet?.changes?.map { it.file } ?: emptyList()
        val notesText = result?.let { (it.notes + listOfNotNull(it.message)).joinToString("\n") } ?: ""
        val next = activity.chain.selectNext(success, changedFiles, notesText) ?: return
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

    /** 一括実行の項目は履歴に対象キーを前置する（どの対象の結果かを追えるように）。 */
    private fun targetPrefix(item: QueueItem): String = item.targetKey?.let { "[$it] " } ?: ""

    private fun handleFailure(activity: ActivityDef, item: QueueItem, message: String, result: AgentRunResult? = null) {
        // 収束判定: 同じ失敗を繰り返した場合は試行を打ち切る
        if (item.lastFailure != null && item.lastFailure == message) {
            recordHistory(activity.id, "失敗", "${targetPrefix(item)}同一の失敗を繰り返したため中断: ${message.take(200)}")
            chainNext(activity, item, success = false, result = result)
            return
        }
        if (item.attempt < activity.retry.maxAttempts) {
            recordHistory(activity.id, "再試行", "${targetPrefix(item)}試行 ${item.attempt} が失敗: ${message.take(200)}")
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
            recordHistory(activity.id, "失敗", "${targetPrefix(item)}${message.take(300)}")
            chainNext(activity, item, success = false, result = result)
        }
    }

    // ---------------------------------------------------------------- 常駐ループ

    /** 監視と実行の常駐ループを開始する。停止はスコープのキャンセルか stopAll()。 */
    fun start(scope: CoroutineScope): Job {
        loopScope = scope
        // ネイティブ監視が使えるときは、変更イベントがあったときと定期フォールバック時だけ
        // ファイルスタンプを全走査する（R8-03。取りこぼしに備え10秒毎の走査は残す）
        val watcher = FileWatcher.open(dir)
        var lastFullScan = 0L
        return scope.launch {
            try {
                while (isActive) {
                    if (!enabled) {
                        delay(500)
                        continue
                    }
                    val now = System.currentTimeMillis()
                    val scanDue = watcher == null || watcher.consumeChanged() || now - lastFullScan >= 10_000
                    if (scanDue) lastFullScan = now
                    runCatching { tick(now, scanChanges = scanDue) }
                        .onFailure { log("トリガ判定でエラー: ${it.message}") }
                    val worked = runCatching { processCancellable() }
                        .onFailure { log("実行でエラー: ${it.message}") }
                        .getOrDefault(false)
                    if (!worked) delay(1_000)
                }
            } finally {
                watcher?.close()
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
