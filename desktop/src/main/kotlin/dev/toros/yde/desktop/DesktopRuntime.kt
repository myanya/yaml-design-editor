package dev.toros.yde.desktop

import dev.toros.yde.agent.auto.AutomationEngine
import dev.toros.yde.agent.llm.AiProvider
import dev.toros.yde.app.core.FileWatcher
import dev.toros.yde.app.service.SourceDraftWorker
import dev.toros.yde.app.service.WorkspaceHost
import dev.toros.yde.app.service.WorkspaceOwnership
import dev.toros.yde.workspace.Timings
import dev.toros.yde.workspace.WorkspaceInit
import dev.toros.yde.app.service.WorkspaceOperations
import dev.toros.yde.app.service.WorkspaceWork
import dev.toros.yde.app.service.WorkspaceReads
import dev.toros.yde.app.service.ConnectionGrants
import dev.toros.yde.server.LocalServer
import dev.toros.yde.server.auth.AccessWorkspace
import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.desktop.connections.FileConnectionSettingsStore
import dev.toros.yde.desktop.connections.LocalConnectionController
import java.awt.EventQueue
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

/** 監視の差し込み口。テストでは実時間・OS の通知に依存せず変更を投入する。 */
internal interface WorkspaceMonitor : AutoCloseable {
    fun consumePaths(): Set<Path>?
}

/** アプリ全体の寿命。Window/AppRoot の生成・破棄から独立して状態と実行を所有する。 */
internal class DesktopRuntime(
    val host: WorkspaceHost = WorkspaceHost(owner = DesktopUiDispatcher, checkOwner = {
        check(EventQueue.isDispatchThread()) { "共有状態は所有スレッドから操作してください" }
    }),
    private val monitorFactory: (Path) -> WorkspaceMonitor? = { root ->
        FileWatcher.open(root)?.let { watcher -> object : WorkspaceMonitor {
            override fun consumePaths(): Set<Path>? = watcher.consumePaths()
            override fun close() = watcher.close()
        } }
    },
    private val ownership: WorkspaceOwnership = WorkspaceOwnership(),
) {
    private val lifetime = SupervisorJob()
    private val scope: CoroutineScope = CoroutineScope(lifetime + host.owner + CoroutineExceptionHandler { _, failure ->
        state.status = "バックグラウンド処理に失敗しました: ${failure.message}"
    })
    val state: AppState = AppState(host, scope)
    /** 登録がない既定状態は全拒否。共通窓口も現在の登録権限を保存直前まで再確認する。 */
    private val grants = ConnectionGrants()
    val operations = WorkspaceOperations(host, grants = grants::operationGrant)
    val work = WorkspaceWork(host, grants = grants::workGrant, directGrants = grants::directWorkGrant)
    @Volatile private var connectionWorkspace: Pair<Path, String>? = null
    private val reads = WorkspaceReads(host, grants::get)
    private val server = LocalServer(scope, grants, reads, work, operations, ::accessWorkspace)
    val access = server.access
    /** 起動時の設定復元でだけ有効化する。Window の開閉では起動・終了しない。 */
    val connections = server.connections
    val connectionSettings = LocalConnectionController(connections, access, FileConnectionSettingsStore(), reads::documentsForSettings, reads::actionsForSettings, ::accessWorkspace)
    private val connectionSubscription = host.observe { access.workspaceChanged() }
    private var jobs: WorkspaceJobs? = null
    private var stopping = false
    private var lease: WorkspaceOwnership.Lease? = null

    private fun accessWorkspace(): AccessWorkspace? {
        val status = host.status.value
        return connectionWorkspace?.takeIf { it.first == status.workspace && !status.closed }?.let { AccessWorkspace(it.second, status.epoch) }
    }

    init {
        // 外部変更の走査と照合は監視の周期が IO で行う（WorkspaceJobs.poll）。
        state.fileEventsInBackground = true
        state.workspaceOpener = { path ->
            scope.launch { openWorkspace(path) }
        }
        state.workspaceInitializer = { root, plan ->
            host.assertAvailable()
            check(!stopping) { "終了中は初期化できません" }
            if (lease?.root == ownership.identity(root)) WorkspaceInit.apply(root, plan)
            else ownership.acquire(root).use { WorkspaceInit.apply(root, plan) }
        }
    }

    suspend fun openWorkspace(path: Path): Boolean = withContext(host.owner) {
        if (stopping) return@withContext false
        state.dismissWorkspaceOpenFailure()
        try {
            // 表示状態の書込（図の配置）は文書の排他を取らないので、旧所有権を手放す前に完了を待つ。
            host.exclusive("ワークスペースの切替") { state.stateWrites.withLock {
                val root = path.toAbsolutePath().normalize()
                val previous = lease
                var next = previous
                // 段階ごとの時間（最初の描画で閉じて .yde/state/timings.log へ追記する。AppState.finishOpenTimings）
                val timings = Timings("ワークスペースを開く: $root")
                try {
                    // 所有権を取ってから読む。読込失敗では旧所有権と旧ジョブを保つ。
                    val (candidate, fresh, workspaceId) = withContext(Dispatchers.IO) {
                        Timings.within(timings) {
                            if (previous?.root != ownership.identity(root)) next = timings.phase("所有権を取る") { ownership.acquire(root) }
                            val loaded = timings.phase("読み込み") { host.documents.prepareReload(root) }
                            Triple(loaded, timings.phase("編集用の写しを作る") { loaded.workspace.mirror.fork() }, ChangeSets.contentHash(root.toRealPath().toString()))
                        }
                    }
                    check(!stopping) { "終了中はワークスペースを切り替えられません" }
                    jobs?.stop()
                    // activateWorkspace は最初に workspace を切り替える。以降は新しい場所の所有権を持つ。
                    lease = next
                    try { timings.phase("切り替え") { host.activateWorkspace(root, candidate, fresh) } }
                    finally { if (previous !== next) previous?.close() }
                    connectionWorkspace = root to workspaceId
                    timings.phase("画面の状態を戻す") { state.afterWorkspaceOpened(root) }
                    jobs = WorkspaceJobs(root)
                    state.openTimings = timings
                } finally {
                    if (next !== lease) next?.close()
                }
            } }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            state.reportWorkspaceOpenFailure(path, failure)
            false
        }
    }

    /** 完全終了。実行中の保存は反映まで待ち、監視の close と子ジョブの終了を待つ。 */
    suspend fun shutdown() = withContext(host.owner) {
        if (stopping) return@withContext
        stopping = true
        connectionSettings.stopAccepting()
        try { connections.shutdown() }
        catch (failure: Exception) {
            stopping = false
            connectionSettings.resumeAccepting()
            state.status = "接続を停止できないため終了を中断しました: ${failure.message}"
            throw failure
        }
        host.status.first { it.busy == null }
        host.exclusive("終了") { state.stateWrites.withLock { jobs?.stop(); jobs = null } }
        state.workspaceOpener = null
        state.workspaceInitializer = null
        connectionSubscription.close()
        state.close()
        host.close()
        lease?.close()
        lease = null
        lifetime.cancel()
    }

    /** application 自体が破棄された場合の後始末。通常の終了は shutdown を待つ。 */
    fun cancel() { scope.launch { shutdown() } }

    private inner class WorkspaceJobs(val root: Path) {
        private val job = SupervisorJob(lifetime)
        private val workspaceScope = CoroutineScope(scope.coroutineContext + job)
        private val drafts = SourceDraftWorker(host, workspaceScope)
        private val engine = AutomationEngine(
            root,
            credentials = {
                AutomationEngine.Credentials(
                    provider = AiSettings.provider,
                    models = AiProvider.entries.associateWith { AiSettings.modelFor(it) },
                    apiKeys = AiProvider.entries.associateWith { AiSettings.apiKeyFor(it) }.filterValues { it.isNotBlank() },
                )
            },
            dirtyChecker = { path ->
                host.status.value.busy != null || host.documents.document(path)?.let {
                    it.dirty || it.hasPendingSourceInput || it.draftDirty || it.draftBroken
                } == true
            },
        )
        private val controller = RunController(root, workspaceScope)
        private val external = ExternalWorkController(work, workspaceScope)

        init {
            state.automation = engine
            state.runController = controller
            state.externalWork = external
            workspaceScope.launch {
                host.status.first { it.busy == null }
                external.refresh()
            }
            engine.onUpdate = {
                workspaceScope.launch { if (state.automation === engine) state.automationVersion++ }
            }
            engine.start(workspaceScope)
            workspaceScope.launch {
                var monitor: WorkspaceMonitor? = null
                try {
                    // キャンセルと open の完了が重なっても、取得した監視を必ず finally で閉じる。
                    withContext(NonCancellable + Dispatchers.IO) { monitor = monitorFactory(root) }
                    // 候補の読込から監視登録までの隙間を照合する。共有セッションは IO へ渡さない。
                    val epoch = host.currentStatus().epoch
                    val check = host.documents.prepareDiskCheck()
                    val changed = withContext(Dispatchers.IO) { check?.changedFiles().orEmpty() }
                    if (job.isActive && host.currentStatus().epoch == epoch && host.documents.workspace == root) {
                        state.refreshFileTree()
                        poll(changed)
                    }
                    while (isActive) {
                        delay(if (monitor != null) 250L else 1_000L)
                        poll(monitor?.consumePaths())
                    }
                } finally { monitor?.close() }
            }
        }

        /**
         * 知らせの確認。所有スレッドで材料を作り、一覧の走査・本文の照合・再読込の候補の読込は IO で行い、
         * 同じ世代のときだけ所有スレッドで取り込む。共有セッションは IO へ渡さない。
         */
        suspend fun poll(paths: Set<Path>?) {
            if (host.documents.workspace != root || !job.isActive) return
            val check = state.prepareFileEvents(paths) ?: return
            val epoch = host.currentStatus().epoch
            val scan = withContext(Dispatchers.IO) { check.scan() }
            if (job.isActive && host.currentStatus().epoch == epoch && host.documents.workspace == root) state.applyFileEvents(scan)
        }

        fun cancel() {
            drafts.close()
            controller.cancel()
            engine.onUpdate = {}
            engine.stopAll()
            job.cancel()
        }

        suspend fun stop() {
            cancel()
            job.join()
            if (state.automation === engine) { state.automation = null; state.runController = null; state.externalWork = null }
        }
    }
}
