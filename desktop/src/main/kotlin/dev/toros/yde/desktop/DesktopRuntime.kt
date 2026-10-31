package dev.toros.yde.desktop

import dev.toros.yde.agent.auto.AutomationEngine
import dev.toros.yde.agent.llm.AiProvider
import dev.toros.yde.app.core.FileWatcher
import dev.toros.yde.app.service.SourceDraftWorker
import dev.toros.yde.app.service.WorkspaceHost
import dev.toros.yde.app.service.WorkspaceOperations
import dev.toros.yde.app.service.WorkspaceWork
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
) {
    private val lifetime = SupervisorJob()
    private val scope: CoroutineScope = CoroutineScope(lifetime + host.owner + CoroutineExceptionHandler { _, failure ->
        state.status = "バックグラウンド処理に失敗しました: ${failure.message}"
    })
    val state: AppState = AppState(host, scope)
    /** 登録がない既定状態は全拒否。共通窓口も現在の登録権限を保存直前まで再確認する。 */
    private val grants = ConnectionGrants()
    val operations = WorkspaceOperations(host, grants = grants::operationGrant)
    val work = WorkspaceWork(host, grants = grants::workGrant)
    @Volatile private var connectionWorkspace: Pair<Path, String>? = null
    private val server = LocalServer(scope, grants, ::accessWorkspace)
    val access = server.access
    /** 起動時の設定復元でだけ有効化する。Window の開閉では起動・終了しない。 */
    val connections = server.connections
    val connectionSettings = LocalConnectionController(connections, access, FileConnectionSettingsStore(), ::accessWorkspace)
    private val connectionSubscription = host.observe { access.workspaceChanged() }
    private var jobs: WorkspaceJobs? = null
    private var stopping = false

    private fun accessWorkspace(): AccessWorkspace? {
        val status = host.status.value
        return connectionWorkspace?.takeIf { it.first == status.workspace && !status.closed }?.let { AccessWorkspace(it.second, status.epoch) }
    }

    init {
        state.workspaceOpener = { path ->
            scope.launch { openWorkspace(path) }
        }
    }

    suspend fun openWorkspace(path: Path): Boolean = withContext(host.owner) {
        if (stopping) return@withContext false
        try {
            host.exclusive("ワークスペースの切替") {
                val root = path.toAbsolutePath().normalize()
                // 候補の準備は現セッションを変えない。失敗したら旧ジョブもそのまま残す。
                val (candidate, workspaceId) = withContext(Dispatchers.IO) {
                    host.documents.prepareReload(root) to ChangeSets.contentHash(root.toRealPath().toString())
                }
                val fresh = candidate.workspace.mirror.fork()
                jobs?.stop()
                host.activateWorkspace(root, candidate, fresh)
                connectionWorkspace = root to workspaceId
                state.afterWorkspaceOpened(root)
                jobs = WorkspaceJobs(root)
            }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            state.status = "ワークスペースを開けません: ${failure.message}"
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
        host.exclusive("終了") { jobs?.stop(); jobs = null }
        state.workspaceOpener = null
        connectionSubscription.close()
        state.close()
        host.close()
        lifetime.cancel()
    }

    /** application 自体が破棄された場合の後始末。通常の終了は shutdown を待つ。 */
    fun cancel() { jobs?.cancel(); lifetime.cancel() }

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
                    // 候補の読込から監視登録までの隙間も、本文比較を含めて取り込む。
                    poll(null)
                    while (isActive) {
                        delay(if (monitor != null) 250L else 1_000L)
                        poll(monitor?.consumePaths())
                    }
                } finally { monitor?.close() }
            }
        }

        fun poll(paths: Set<Path>?) {
            if (host.documents.workspace == root && job.isActive) state.noteFileEvents(paths)
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
