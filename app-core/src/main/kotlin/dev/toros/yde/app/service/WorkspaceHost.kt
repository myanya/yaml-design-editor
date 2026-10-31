package dev.toros.yde.app.service

import dev.toros.yde.app.session.DocumentSession
import dev.toros.yde.workspace.WorkspaceSession
import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext

/** 画面・接続の寿命によらない状態。本文や可変の木は通知へ含めない。 */
data class WorkspaceStatus(
    val epoch: String,
    val revision: Long,
    val workspace: Path?,
    val dirty: Set<DocumentId>,
    val pendingSource: Set<DocumentId>,
    val conflicts: Set<DocumentId>,
    val busy: String?,
    val closed: Boolean,
)

/**
 * 開いている状態の所有者。DocumentSession のディスク版と編集版は分けたまま保持する。
 * 通常の呼出しは owner 上。IO へ渡すのは独立した作業だけで、共有セッションは渡さない。
 * 既定の実行環境は同期のテスト・CLI 用。desktop は EDT と所有スレッド検査を注入する。
 */
class WorkspaceHost(
    editClock: () -> Long = System::currentTimeMillis,
    val owner: CoroutineDispatcher = Dispatchers.Unconfined,
    private val checkOwner: () -> Unit = {},
) : AutoCloseable {
    val documents = DocumentSession(editClock)
    private var lastKernel: WorkspaceSession? = null
    private var generation = UUID.randomUUID().toString()
    private var revision = 0L
    private var changing = false
    private var closed = false
    private var busy: String? = null
    private var notificationPending = false
    private val mutableStatus = MutableStateFlow(snapshot())
    val status: StateFlow<WorkspaceStatus> = mutableStatus.asStateFlow()
    private val observers = linkedSetOf<() -> Unit>()
    private val subscription = documents.observeChanges { revision++; scheduleNotification() }
    // 入力変換内では通知先の状態を書き換えず、前提の版だけ直ちに進める。
    private val inputSubscription = documents.observeSourceInput { revision++ }

    fun currentStatus(): WorkspaceStatus { checkOwner(); refreshGeneration(); return snapshot() }

    private fun scheduleNotification() {
        if (closed || changing || notificationPending) return
        if (owner === Dispatchers.Unconfined) { publish(); return }
        notificationPending = true
        // 文書の複数プロパティが更新される途中で、購読先から処理を再入させない。
        owner.dispatch(EmptyCoroutineContext, Runnable {
            notificationPending = false
            publish()
        })
    }

    private fun refreshGeneration() {
        if (lastKernel !== documents.kernel) {
            lastKernel = documents.kernel
            generation = UUID.randomUUID().toString()
        }
    }

    fun observe(listener: () -> Unit): AutoCloseable {
        observers += listener
        return AutoCloseable { observers -= listener }
    }

    fun assertAvailable(expectedEpoch: String? = null) {
        checkOwner()
        refreshGeneration()
        check(!closed) { "ワークスペースは終了しています" }
        check(busy == null) { "処理中です: $busy" }
        check(expectedEpoch == null || expectedEpoch == generation) { "ワークスペースが切り替わりました" }
    }

    /** 接続・背景処理は所有スレッドへ戻してから状態を読む。可変の状態を呼出し元へ持ち出さない。 */
    suspend fun <T> access(expectedEpoch: String? = null, block: (DocumentSession) -> T): T = withContext(owner) {
        assertAvailable(expectedEpoch)
        block(documents)
    }

    /** 候補の読込失敗では現在値を変更しない。画面の覚えは呼出し成功後に更新する。 */
    fun openWorkspace(path: Path) {
        assertAvailable()
        changing = true
        try { documents.openWorkspace(path) }
        finally { changing = false; publish() }
    }

    /** 切替の排他区間でのみ使う。場所と文書集合を半分だけ通知しない。 */
    fun activateWorkspace(path: Path, candidate: DocumentSession.ReloadCandidate, fresh: WorkspaceSession) {
        checkOwner()
        check(busy != null && !closed) { "ワークスペース切替の排他区間が必要です" }
        changing = true
        try { documents.activateWorkspace(path, candidate, fresh) }
        finally { changing = false; publish() }
    }

    /** 通知を一つにまとめる同期操作。例外時にも実際に残った状態を配る。 */
    fun <T> change(block: () -> T): T {
        assertAvailable()
        changing = true
        try { return block() }
        finally { changing = false; publish() }
    }

    /**
     * 独立した作業を IO へ渡す間、更新の受付を閉じる。書込開始後は反映までを完了させる。
     * UI の購読は確定済みのセッションを読み続けられる。finish は必ず所有スレッドで実行する。
     */
    suspend fun <T> exclusive(label: String, block: suspend () -> T): T = withContext(owner) {
        assertAvailable()
        busy = label
        publish()
        try {
            withContext(NonCancellable) { block() }
        } finally { busy = null; publish() }
    }

    suspend fun <T, R> exclusiveIo(label: String, work: () -> T, finish: (T) -> R): R = exclusive(label) {
        val result = withContext(Dispatchers.IO) { work() }
        finish(result)
    }

    /** 入力変換の外から呼ぶ。未解析の下書きも共通状態へ通知する。 */
    fun publish() {
        checkOwner()
        if (closed || changing) return
        refreshGeneration()
        mutableStatus.value = snapshot()
        observers.toList().forEach { it() }
    }

    private fun snapshot(): WorkspaceStatus {
        val docs = documents.documents.values
        fun ids(paths: Collection<Path>): Set<DocumentId> = paths.map { documents.documentOf(it) }.toSet()
        return WorkspaceStatus(
            generation, revision, documents.workspace,
            ids(documents.dirtyFiles()),
            ids(docs.filter { it.hasPendingSourceInput }.map { it.file }),
            ids(docs.filter { it.externalConflict }.map { it.file }), busy, closed,
        )
    }

    override fun close() {
        assertAvailable()
        closed = true
        subscription.close()
        inputSubscription.close()
        mutableStatus.value = snapshot()
        observers.toList().forEach { it() }
        observers.clear()
    }
}
