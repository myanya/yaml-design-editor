package dev.toros.yde.server.transport

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking

/**
 * ローカル接続専用の TCP 待受。構築だけでは起動しない。
 * 外から host を渡せない形にし、明示したポートを使えないときは失敗させる。
 * 設定・機能の切替は上位の管理窓口が直列化し、この部品は待受一つの寿命だけを担う。
 */
internal class LoopbackEngine(port: Int, module: Application.() -> Unit) : AutoCloseable {
    init { require(port in 0..65535) { "ポート番号が範囲外です" } }

    // 0 は試験時に OS が空きポートを選ぶ指定。利用者設定では許さない。
    private val server = embeddedServer(CIO, host = "127.0.0.1", port = port, module = module)
    private var started = false
    private var closed = false

    @Synchronized
    fun start(): Int {
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
        server.stop(gracePeriodMillis = 0, timeoutMillis = 5_000)
        closed = true
        started = false
    }
}
