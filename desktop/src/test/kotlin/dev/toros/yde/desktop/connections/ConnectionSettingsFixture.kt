package dev.toros.yde.desktop.connections

import dev.toros.yde.app.service.ConnectionGrants
import dev.toros.yde.server.LocalServer
import dev.toros.yde.server.auth.AccessWorkspace
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.*

internal class MemoryConnectionSettingsStore(var saved: SavedConnections = SavedConnections()) : ConnectionSettingsStore {
    var failRead = false
    var readFailure: Exception = IllegalStateException("読込の試験エラー")
    var failWrite = false
    var beforeSave: (() -> Unit)? = null
    override fun load(): SavedConnections { if (failRead) throw readFailure; return saved }
    override fun save(value: SavedConnections) { check(!failWrite); beforeSave?.invoke(); saved = value }
}

internal class ConnectionSettingsFixture(val store: MemoryConnectionSettingsStore = MemoryConnectionSettingsStore()) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile var workspace: AccessWorkspace? = AccessWorkspace("workspace", "epoch")
    val grants = ConnectionGrants()
    val server = LocalServer(scope, grants) { workspace }
    val controller = LocalConnectionController(server.connections, server.access, store) { workspace }
    suspend fun settle() = withTimeout(10_000) { server.connections.awaitSettled(server.connections.status.value.revision) }
    suspend fun close() { try { server.connections.shutdown() } finally { scope.cancel() } }
    fun port() = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
}
