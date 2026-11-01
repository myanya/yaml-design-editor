package dev.toros.yde.server.mcp

import dev.toros.yde.app.service.*
import dev.toros.yde.server.*
import dev.toros.yde.server.auth.*
import dev.toros.yde.server.transport.McpSessionHooks
import dev.toros.yde.server.transport.McpSessionHooksKey
import dev.toros.yde.server.transport.authenticatedIdentity
import io.ktor.server.application.Application
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlinx.coroutines.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** API の ON/OFF を経由せず、同じ共通サービスを MCP 専用の証明で呼ぶ。 */
internal fun Application.workspaceMcp(connections: LocalConnections, access: LocalAccess,
    reads: WorkspaceReads, work: WorkspaceWork, operations: WorkspaceOperations,
    sessionIdleTimeout: Duration = 15.minutes,
) {
    mcpStreamableHttp(path = "/mcp", maxSessions = 64, sessionIdleTimeout = sessionIdleTimeout) {
        val identity = call.authenticatedIdentity()
        val caller = McpCaller(connections, access, identity)
        val server = workspaceMcpServer(caller, reads, work, operations)
        val lifetime = McpSessionLifetime(access, identity)
        call.attributes.put(McpSessionHooksKey, McpSessionHooks(lifetime::bound) { completed ->
            if (!completed || !lifetime.isBound()) server.close()
        })
        server.onConnect {
            val session = server.sessions.values.single()
            val job = this@workspaceMcp.launch(start = CoroutineStart.LAZY) {
                try { caller.run { awaitCancellation() } }
                catch (_: LocalAccessFailure) { /* 受付後に失効したセッションも下の終了処理へ進む。 */ }
                finally { withContext(NonCancellable) { server.close() } }
            }
            session.onClose { lifetime.closed(); job.cancel() }
            job.start()
        }
        server
    }
}

/** 初期 HTTP の寿命と独立した SDK のツール実行も、停止・失効・保存完了待ちの対象にする。 */
internal class McpCaller(private val connections: LocalConnections, private val access: LocalAccess, private val identity: LocalIdentity) {
    fun grant(capability: ConnectionCapability = ConnectionCapability.READ, action: String? = null): ConnectionGrant =
        access.check(identity, capability, action = action)

    suspend fun <T : Any> run(block: suspend (LocalRequest) -> T): T {
        var result: T? = null
        val accepted = connections.withRequest(LocalFeature.MCP) { request ->
            request.authorizeWith { grant() }
            access.withIdentity(identity) {
                result = block(request)
                currentCoroutineContext().ensureActive()
                grant()
            }
        }
        if (!accepted) throw LocalAccessFailure(404, "MCP は停止しています")
        return checkNotNull(result)
    }
}

/** SDK の内部 ID と HTTP セッション ID は異なる。応答時に結び、DELETE・期限切れでも認証側の枠を返す。 */
private class McpSessionLifetime(private val access: LocalAccess, private val identity: LocalIdentity) {
    private var id: String? = null
    private var ended = false
    @Synchronized fun isBound(): Boolean = id != null && !ended
    @Synchronized fun bound(value: String) {
        if (ended) {
            access.endMcpSession(identity, value)
            throw LocalAccessFailure(404, "MCP セッションを再初期化してください")
        }
        id = value
    }
    @Synchronized fun closed() {
        ended = true
        id?.let { access.endMcpSession(identity, it) }
    }
}
