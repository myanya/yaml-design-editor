package dev.toros.yde.server

import dev.toros.yde.server.transport.ConnectionListener
import dev.toros.yde.server.transport.LoopbackEngine
import dev.toros.yde.server.transport.localConnectionGate
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * 三機能で共有する待受の所有者。構築時は全て OFF で、ソケットを作らない。
 * 希望値の受付と経路の遮断は同期し、起動・要求の収束・終了は一つの worker が直列に進める。
 * 設定保存と認証は上位が担い、定義文書やクライアント要求から configure を呼ばない。
 */
class LocalConnections internal constructor(
    scope: CoroutineScope,
    private val createListener: (Int, LocalConnections) -> ConnectionListener,
    private val onDisabled: suspend (LocalFeature) -> Unit = {},
) {
    constructor(scope: CoroutineScope) : this(scope, { port, connections ->
        LoopbackEngine(port) { localConnectionGate(connections) }
    })

    private val monitor = Any()
    private var desired = LocalConnectionSettings()
    private var revision = 0L
    private var settled = 0L
    private var phase = ConnectionPhase.STOPPED
    private var port: Int? = null
    private var error: String? = null
    private var listener: ConnectionListener? = null
    private var startFailedAt = -1L
    private var closeFailedAt = -1L
    private var closing = false
    private var terminated = false
    private val features = LocalFeature.entries.associateWith {
        FeatureConnectionStatus(ConnectionPhase.STOPPED, UUID.randomUUID().toString())
    }.toMutableMap()
    private val stops = linkedSetOf<LocalFeature>()
    private val stopFailedAt = mutableMapOf<LocalFeature, Long>()
    private val requests = LocalFeature.entries.associateWith { linkedSetOf<LocalRequest>() }
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val mutableStatus = MutableStateFlow(snapshot())
    val status = mutableStatus.asStateFlow()
    private val worker = scope.launch {
        try { for (signal in signals) reconcile() }
        finally {
            // 親の寿命が終わる経路でも、保存を待ってから所有するソケットを閉じる。
            withContext(NonCancellable) { finishLifetime() }
        }
    }

    init {
        // 既に取消済みの scope では launch の本体自体が始まらない。
        worker.invokeOnCompletion {
            synchronized(monitor) {
                if (!terminated) {
                    closing = true; terminated = true; settled = revision
                    publish()
                }
            }
        }
    }

    /** 同じ値の再指定は明示的な再試行にも使う。戻り値の版で処理完了を待てる。 */
    fun configure(settings: LocalConnectionSettings): Long {
        val requested = synchronized(monitor) {
            check(!closing && !terminated && worker.isActive) { "接続管理は終了しています" }
            require(settings.port == desired.port || (snapshot().stopped && !desired.requiresListener)) {
                "ポート番号は全機能の停止が完了してから変更してください"
            }
            changeDesired(settings)
        }
        cancelDisabledRequests()
        signals.trySend(Unit)
        return requested
    }

    private fun changeDesired(settings: LocalConnectionSettings): Long {
        revision++
        LocalFeature.entries.forEach { feature ->
            if (desired.enabled(feature) && !settings.enabled(feature)) {
                // 起動途中の OFF も失効させる。後の ON で古いセッションを再利用しない。
                val old = features.getValue(feature)
                features[feature] = old.copy(phase = ConnectionPhase.STOPPING, generation = UUID.randomUUID().toString(), error = null)
                stops += feature
            }
        }
        desired = settings
        publish()
        return revision
    }

    private fun cancelDisabledRequests() {
        // 完了コールバックは認証側の管理にも戻るため、接続のロックを持ったまま取消しない。
        val jobs = synchronized(monitor) { stops.flatMap { requests.getValue(it).map { request -> request.job } } }
        jobs.forEach { it.cancel(CancellationException("接続機能が停止されました")) }
    }

    suspend fun awaitSettled(atLeast: Long): LocalConnectionStatus = status.first { it.settledRevision >= atLeast }

    /** 完全終了。失敗時は状態とハンドルを残し、呼出し元が再試行できるようにする。 */
    suspend fun shutdown() {
        val requested = synchronized(monitor) {
            if (terminated) { check(snapshot().stopped) { error ?: "待受を停止できませんでした" }; return }
            closing = true
            changeDesired(desired.copy(apiEnabled = false, mcpEnabled = false, webEnabled = false))
        }
        cancelDisabledRequests()
        signals.trySend(Unit)
        val final = awaitSettled(requested)
        check(final.stopped) { final.error ?: final.features.values.firstNotNullOfOrNull { it.error } ?: "接続を停止できませんでした" }
        signals.close()
        worker.join()
    }

    /** 世代を持つ認可・MCP セッションが現在も有効かを受付側が再確認する窓口。 */
    fun accepts(feature: LocalFeature, generation: String): Boolean = synchronized(monitor) {
        accepting(feature) && features.getValue(feature).generation == generation
    }

    private fun accepting(feature: LocalFeature) = !closing && !terminated && desired.enabled(feature) &&
        phase == ConnectionPhase.RUNNING && features.getValue(feature).phase == ConnectionPhase.RUNNING

    /** HTTP 要求と同じ寿命の子ジョブ。OFF はこのジョブだけを取り消し、他機能の要求へ波及させない。 */
    internal suspend fun withRequest(feature: LocalFeature, block: suspend (LocalRequest) -> Unit): Boolean = coroutineScope {
        val requestJob = currentCoroutineContext().job
        val request = synchronized(monitor) {
            if (!accepting(feature)) null else LocalRequest(feature, features.getValue(feature).generation,
                requestJob, this@LocalConnections).also { requests.getValue(feature).add(it) }
        } ?: return@coroutineScope false
        // 本体が返った後も子のストリームが残り得る。Job 全体の完了まで登録を維持する。
        requestJob.invokeOnCompletion { synchronized(monitor) { requests.getValue(feature).remove(request) } }
        block(request)
        true
    }

    internal fun checkRequest(request: LocalRequest) = synchronized(monitor) {
        check(request in requests.getValue(request.feature) && accepts(request.feature, request.generation) && request.job.isActive) {
            "接続が停止または失効しました"
        }
    }

    private sealed interface Step {
        data class Start(val port: Int) : Step
        data class StopFeature(val feature: LocalFeature, val jobs: List<Job>) : Step
        data class Close(val listener: ConnectionListener) : Step
    }

    /** 停止要求を最新の ON で取り消さない。一度収束して世代を更新した後に再開する。 */
    private fun next(): Step? = synchronized(monitor) {
        stops.firstOrNull { stopFailedAt[it] != revision }?.let { feature ->
            features[feature] = features.getValue(feature).copy(phase = ConnectionPhase.STOPPING, error = null)
            publish()
            return@synchronized Step.StopFeature(feature, requests.getValue(feature).map { it.job })
        }
        val current = listener
        if (current != null) {
            if ((!desired.requiresListener && stops.isEmpty()) || phase == ConnectionPhase.ERROR) {
                if (closeFailedAt != revision) {
                    phase = ConnectionPhase.STOPPING; publish()
                    return@synchronized Step.Close(current)
                }
            } else if (phase == ConnectionPhase.RUNNING) {
                LocalFeature.entries.filter { desired.enabled(it) && it !in stops }.forEach {
                    features[it] = features.getValue(it).copy(phase = ConnectionPhase.RUNNING, error = null)
                }
            }
        } else if (desired.requiresListener && stops.isEmpty() && startFailedAt != revision) {
            phase = ConnectionPhase.STARTING; error = null
            LocalFeature.entries.filter(desired::enabled).forEach {
                features[it] = features.getValue(it).copy(phase = ConnectionPhase.STARTING, error = null)
            }
            publish()
            return@synchronized Step.Start(desired.port)
        } else if (!desired.requiresListener && stops.isEmpty()) {
            phase = ConnectionPhase.STOPPED; error = null
        }
        settled = revision
        publish()
        null
    }

    private suspend fun reconcile() {
        while (currentCoroutineContext().isActive) {
            val step = next() ?: return
            withContext(NonCancellable) {
                when (step) {
                    is Step.Start -> start(step.port)
                    is Step.StopFeature -> stopFeature(step.feature, step.jobs)
                    is Step.Close -> close(step.listener)
                }
            }
        }
    }

    private suspend fun start(requestedPort: Int) {
        try {
            val candidate = createListener(requestedPort, this)
            synchronized(monitor) { listener = candidate }
            val bound = withContext(Dispatchers.IO) { candidate.start() }
            synchronized(monitor) { port = bound; phase = ConnectionPhase.RUNNING; error = null; publish() }
        } catch (failure: Exception) {
            val candidate = synchronized(monitor) { listener }
            val cleanup = withContext(Dispatchers.IO) { runCatching { candidate?.close() } }
            synchronized(monitor) {
                if (cleanup.isSuccess) { listener = null; port = null } else closeFailedAt = revision
                phase = ConnectionPhase.ERROR; startFailedAt = revision
                error = "待受を開始できません: ${failure.message}" + (cleanup.exceptionOrNull()?.let { " ／ 停止にも失敗しました: ${it.message}" } ?: "")
                LocalFeature.entries.filter { desired.enabled(it) && it !in stops }.forEach {
                    features[it] = features.getValue(it).copy(phase = ConnectionPhase.ERROR, error = error)
                }
                publish()
            }
        }
    }

    private suspend fun stopFeature(feature: LocalFeature, jobs: List<Job>) {
        try {
            jobs.forEach { it.cancel(CancellationException("接続機能が停止されました")) }
            jobs.joinAll()
            onDisabled(feature)
            synchronized(monitor) {
                stops.remove(feature); stopFailedAt.remove(feature)
                features[feature] = features.getValue(feature).copy(phase = ConnectionPhase.STOPPED, error = null)
                publish()
            }
        } catch (failure: Exception) {
            synchronized(monitor) {
                stopFailedAt[feature] = revision
                features[feature] = features.getValue(feature).copy(phase = ConnectionPhase.ERROR, error = "接続を停止できません: ${failure.message}")
                publish()
            }
        }
    }

    private suspend fun close(current: ConnectionListener) {
        try {
            withContext(Dispatchers.IO) { current.close() }
            synchronized(monitor) { listener = null; port = null; phase = ConnectionPhase.STOPPED; error = null; publish() }
        } catch (failure: Exception) {
            synchronized(monitor) {
                phase = ConnectionPhase.ERROR; closeFailedAt = revision
                error = "待受を停止できません: ${failure.message}"
                publish()
            }
        }
    }

    private suspend fun finishLifetime() {
        synchronized(monitor) {
            closing = true
            changeDesired(desired.copy(apiEnabled = false, mcpEnabled = false, webEnabled = false))
        }
        cancelDisabledRequests()
        val pending = synchronized(monitor) { stops.toList() }
        pending.forEach { feature -> stopFeature(feature, synchronized(monitor) { requests.getValue(feature).map { it.job } }) }
        synchronized(monitor) { listener }?.let { close(it) }
        synchronized(monitor) { terminated = true; settled = revision; publish() }
        signals.close()
    }

    private fun snapshot() = LocalConnectionStatus(desired, phase, port, features.toMap(), revision, settled, error)
    private fun publish() { mutableStatus.value = snapshot() }
}

/** 適用開始前に世代を照合し、開始した保存だけは取消後も結果確定まで終える。外部要求の一括免除には使わない。 */
internal class LocalRequest(
    val feature: LocalFeature, val generation: String, val job: Job, private val connections: LocalConnections,
) {
    private var authorization: (() -> Unit)? = null
    internal fun authorizeWith(check: () -> Unit) { authorization = check }

    suspend fun <T> finishWrite(block: suspend () -> T): T {
        connections.checkRequest(this)
        authorization?.invoke()
        return withContext(NonCancellable) { block() }
    }
}
