package dev.toros.yde.server.transport

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.*

/** 状態機械の待受ポート。単体試験ではソケットを持たない実装を注入する。 */
internal interface ConnectionListener : AutoCloseable {
    fun start(): Int
}

/**
 * ローカル接続専用の TCP 待受。構築だけでは起動しない。
 * 外から host を渡せない形にし、明示したポートを使えないときは失敗させる。
 * 設定・機能の切替は上位の管理窓口が直列化し、この部品は待受一つの寿命だけを担う。
 */
internal class LoopbackEngine(port: Int, module: Application.() -> Unit) : ConnectionListener {
    init { require(port in 0..65535) { "ポート番号が範囲外です" } }

    private val lifetime = SupervisorJob()
    private val log = KtorSimpleLogger("dev.toros.yde.server.transport.LoopbackEngine")
    // CIO は bind 失敗を start と内部ジョブの両方へ通知する。内部の通知もこの所有者で受ける。
    private val scope = CoroutineScope(lifetime + Dispatchers.IO + CoroutineExceptionHandler { _, failure ->
        log.error("ローカル待受の処理に失敗しました", failure)
    })
    // 0 は試験時に OS が空きポートを選ぶ指定。利用者設定では許さない。
    private val server = scope.embeddedServer(CIO, host = "127.0.0.1", port = port, module = module)
    private var started = false
    private var closed = false

    @Synchronized
    override fun start(): Int {
        check(!closed) { "終了した待受は再利用できません" }
        try {
            if (!started) server.start(wait = false)
            val boundPort = runBlocking { server.engine.resolvedConnectors().single().port }
            started = true
            return boundPort
        } catch (failure: Exception) {
            runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        // EmbeddedServer.stop は engine の例外をログだけにするため、先に停止の成功を確認する。
        server.engine.stop(gracePeriodMillis = 0, timeoutMillis = 5_000)
        server.stop(gracePeriodMillis = 0, timeoutMillis = 5_000)
        lifetime.cancel()
        runBlocking { withTimeout(5_000) { lifetime.join() } }
        closed = true
        started = false
    }
}
