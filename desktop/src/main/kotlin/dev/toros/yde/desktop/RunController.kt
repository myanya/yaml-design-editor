package dev.toros.yde.desktop

import androidx.compose.runtime.*
import dev.toros.yde.agent.ActivityDef
import dev.toros.yde.agent.auto.ForeachItem
import dev.toros.yde.agent.run.*
import dev.toros.yde.app.core.Workspace
import java.nio.file.Path
import kotlinx.coroutines.*

/**
 * エージェント実行の状態機械（選択→実行→レビュー→完了）。
 * DesktopRuntime がワークスペースの寿命で持ち、
 * ウィンドウがコンポジションから外れても実行・レビューが失われないようにする
 * （architecture/ui.md「UI シェル」。実行状態をコンポーザブルローカルへ戻さない）。
 */
class RunController(val wsPath: Path, private val scope: CoroutineScope) {

    enum class Stage { SELECT, RUNNING, REVIEW, DONE }

    /** エージェントからのブロッキングな質問（R2-07）。回答は respond で完了させる。 */
    class PendingQuestion(val question: String, private val answer: CompletableDeferred<String?>) {
        fun respond(text: String?) { answer.complete(text) }
        internal suspend fun await(): String? = answer.await()
    }

    /** 一括実行（foreach。R18-01）の対象プレビュー。SELECT ステージのインライン区画で選択して投入する。 */
    class ForeachPreview(
        val activityId: String,
        val items: List<ForeachItem>,
        val total: Int,
    )

    var stage by mutableStateOf(Stage.SELECT)
        private set
    var selectedActivity by mutableStateOf<ActivityDef?>(null)
    var extraInstruction by mutableStateOf("")
    var errorMessage by mutableStateOf("")
    var foreachPreview by mutableStateOf<ForeachPreview?>(null)
        private set
    var foreachSelected by mutableStateOf<Set<String>>(emptySet())
        private set

    fun showForeachPreview(preview: ForeachPreview) {
        foreachPreview = preview
        foreachSelected = preview.items.map { it.key }.toSet()  // 既定は全選択
    }

    fun clearForeachPreview() {
        foreachPreview = null
        foreachSelected = emptySet()
    }

    fun toggleForeachTarget(key: String) {
        foreachSelected = if (key in foreachSelected) foreachSelected - key else foreachSelected + key
    }

    fun setForeachSelection(keys: Set<String>) {
        foreachSelected = keys
    }
    /** 定義再読込のトリガ（インクリメントで Definitions.load をやり直す）。 */
    var defsVersion by mutableStateOf(0)
        private set
    val events = mutableStateListOf<String>()
    var plan by mutableStateOf<List<String>>(emptyList())
        private set
    var pendingQuestion by mutableStateOf<PendingQuestion?>(null)
        private set
    var runResult by mutableStateOf<AgentRunResult?>(null)
        private set
    var runFile by mutableStateOf<Path?>(null)
        private set
    var coreWs by mutableStateOf<Workspace?>(null)
        private set
    var doneMessage by mutableStateOf("")
        private set
    var runActivityId by mutableStateOf<String?>(null)
        private set
    private var runJob: Job? = null

    val running: Boolean get() = stage == Stage.RUNNING

    fun refreshDefs() { defsVersion++ }

    fun startRun(activityId: String, request: AgentRunRequest) {
        errorMessage = ""
        events.clear(); plan = emptyList(); runResult = null; runFile = null
        runActivityId = activityId
        stage = Stage.RUNNING
        // カーネルからの通知も所有スレッドへ返す。窓を隠しても回答待ちと実行は保持する。
        val host = object : AgentHost {
            override suspend fun askHuman(question: String): String? {
                val deferred = CompletableDeferred<String?>()
                val pending = PendingQuestion(question, deferred)
                withContext(DesktopUiDispatcher) { pendingQuestion = pending }
                try {
                    return pending.await()
                } finally {
                    withContext(NonCancellable + DesktopUiDispatcher) { pendingQuestion = null }
                }
            }
            override suspend fun onEvent(event: AgentEvent): Unit = withContext(DesktopUiDispatcher) {
                when (event) {
                    is AgentEvent.TurnStarted -> events.add("── ターン ${event.turn}/${event.maxTurns}")
                    is AgentEvent.AssistantText -> events.add(event.text)
                    is AgentEvent.ToolInvoked -> events.add("🔧 ${event.name} ${event.summary}")
                    is AgentEvent.ToolFailed -> events.add("⚠ ${event.name}: ${event.message}")
                    is AgentEvent.QuestionAsked -> events.add("❓ ${event.question}")
                    is AgentEvent.PlanUpdated -> { plan = event.steps; events.add("📋 計画: ${event.steps.joinToString(" / ")}") }
                    is AgentEvent.Finished -> events.add("── 終了: ${event.status}${event.message?.let { " — $it" } ?: ""}")
                }
            }
        }
        runJob = scope.launch {
            val ws = withContext(Dispatchers.IO) { Workspace.open(wsPath) }
            coreWs = ws
            val result = withContext(Dispatchers.IO) { AgentKernel.run(ws, request, host) }
            runResult = result
            runFile = withContext(Dispatchers.IO) { runCatching { RunLog.write(ws, request, result) }.getOrNull() }
            stage = if (result.changeSet.changes.isEmpty() && result.status != RunStatus.SUBMITTED) {
                doneMessage = "${statusLabel(result.status)}: ${result.message ?: ""}（変更はありません）"
                Stage.DONE
            } else Stage.REVIEW
        }
    }

    fun cancel() {
        val job = runJob ?: return
        runJob = null
        if (stage == Stage.RUNNING) {
            doneMessage = "キャンセルしました（原本は変更されていません）"
            stage = Stage.DONE
        }
        job.cancel()
    }

    /** レビューの採否が確定したときに呼ぶ（メッセージを表示して完了状態へ）。 */
    fun finish(message: String) {
        doneMessage = message
        stage = Stage.DONE
    }

    fun resetToSelect() {
        doneMessage = ""
        errorMessage = ""
        stage = Stage.SELECT
    }
}

internal fun statusLabel(status: RunStatus): String = when (status) {
    RunStatus.SUBMITTED -> "提出されました"
    RunStatus.ABORTED -> "中断されました"
    RunStatus.LIMIT -> "上限に達しました"
    RunStatus.CANCELLED -> "キャンセルされました"
    RunStatus.ERROR -> "エラーが発生しました"
}
