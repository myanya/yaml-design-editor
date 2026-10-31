package dev.toros.yde.server

import dev.toros.yde.app.service.ConnectionGrants
import dev.toros.yde.app.service.WorkspaceReads
import dev.toros.yde.server.api.readApi
import dev.toros.yde.server.auth.AccessWorkspace
import dev.toros.yde.server.auth.LocalAccess
import dev.toros.yde.server.transport.LoopbackEngine
import dev.toros.yde.server.transport.localAuthentication
import dev.toros.yde.server.transport.localConnectionGate
import kotlinx.coroutines.CoroutineScope

/** 製品の通信入口の組立て。機能ゲート・認証の内側へ業務経路を登録する。 */
class LocalServer(scope: CoroutineScope, grants: ConnectionGrants, reads: WorkspaceReads? = null, workspace: () -> AccessWorkspace?) {
    val connections: LocalConnections = LocalConnections(scope, { port, connections ->
        LoopbackEngine(port) {
            localConnectionGate(connections)
            localAuthentication(connections, access)
            reads?.let { readApi(it) }
        }
    }, onDisabled = { access.featureStopped(it) })
    val access: LocalAccess = LocalAccess(scope, connections, grants, workspace)
}
