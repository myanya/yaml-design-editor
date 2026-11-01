package dev.toros.yde.server

import dev.toros.yde.app.service.ConnectionGrants
import dev.toros.yde.app.service.WorkspaceReads
import dev.toros.yde.app.service.WorkspaceWork
import dev.toros.yde.app.service.WorkspaceOperations
import dev.toros.yde.server.api.readApi
import dev.toros.yde.server.api.writeApi
import dev.toros.yde.server.auth.AccessWorkspace
import dev.toros.yde.server.auth.LocalAccess
import dev.toros.yde.server.mcp.workspaceMcp
import dev.toros.yde.server.web.webApi
import dev.toros.yde.server.web.webAssets
import dev.toros.yde.server.transport.LoopbackEngine
import dev.toros.yde.server.transport.localAuthentication
import dev.toros.yde.server.transport.localConnectionGate
import kotlinx.coroutines.CoroutineScope

/** 製品の通信入口の組立て。機能ゲート・認証の内側へ業務経路を登録する。 */
class LocalServer(scope: CoroutineScope, grants: ConnectionGrants, reads: WorkspaceReads? = null,
                  work: WorkspaceWork? = null, operations: WorkspaceOperations? = null, workspace: () -> AccessWorkspace?) {
    val connections: LocalConnections = LocalConnections(scope, { port, connections ->
        LoopbackEngine(port) {
            localConnectionGate(connections)
            localAuthentication(connections, access)
            webAssets()
            reads?.let { webApi(access, it, operations) }
            reads?.let { readApi(it) }
            if (work != null && operations != null) writeApi(work, operations)
            if (reads != null && work != null && operations != null) workspaceMcp(connections, access, reads, work, operations)
        }
    }, onDisabled = { access.featureStopped(it) })
    val access: LocalAccess = LocalAccess(scope, connections, grants, workspace)
}
