package dev.toros.yde.server.auth

import dev.toros.yde.app.service.ConnectionGrants
import dev.toros.yde.app.service.ConnectionPermissions
import dev.toros.yde.server.*
import dev.toros.yde.server.transport.ConnectionListener
import dev.toros.yde.yaml.model.DocumentId
import java.time.Instant
import kotlinx.coroutines.*

internal class AccessFixture {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val connections = LocalConnections(scope, { _, _ -> object : ConnectionListener {
        override fun start() = 18765
        override fun close() {}
    } })
    val grants = ConnectionGrants()
    @Volatile var workspace: AccessWorkspace? = AccessWorkspace("workspace", "epoch-1")
    @Volatile var now: Instant = Instant.parse("2026-10-31T00:00:00Z")
    val access = LocalAccess(scope, connections, grants, { workspace }, { now })
    val a = DocumentId("a.yaml")
    val readable = ConnectionPermissions(readable = setOf(a))
    suspend fun enable(settings: LocalConnectionSettings = LocalConnectionSettings(true, true, true)) {
        withTimeout(5_000) { connections.awaitSettled(connections.configure(settings)) }
    }
    fun client(feature: LocalFeature, permissions: ConnectionPermissions = readable) = access.register(feature, "試験", permissions)
    suspend fun close() { try { connections.shutdown() } finally { scope.cancel() } }
}
