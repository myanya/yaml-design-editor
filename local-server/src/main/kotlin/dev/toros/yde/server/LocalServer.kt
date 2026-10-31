package dev.toros.yde.server

import dev.toros.yde.app.service.ConnectionGrants
import dev.toros.yde.server.auth.AccessWorkspace
import dev.toros.yde.server.auth.LocalAccess
import dev.toros.yde.server.transport.LoopbackEngine
import dev.toros.yde.server.transport.localAuthentication
import dev.toros.yde.server.transport.localConnectionGate
import kotlinx.coroutines.CoroutineScope

/** 製品の通信入口の組立て。認証を組み込んでから起動可能にし、業務経路は後続の各アダプターが担う。 */
class LocalServer(scope: CoroutineScope, grants: ConnectionGrants, workspace: () -> AccessWorkspace?) {
    val connections: LocalConnections = LocalConnections(scope, { port, connections ->
        LoopbackEngine(port) {
            localConnectionGate(connections)
            localAuthentication(connections, access)
        }
    }, onDisabled = { access.featureStopped(it) })
    val access: LocalAccess = LocalAccess(scope, connections, grants, workspace)
}
