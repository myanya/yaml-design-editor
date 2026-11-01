package dev.toros.yde.server.web

import dev.toros.yde.app.service.*
import dev.toros.yde.server.*
import dev.toros.yde.server.auth.*
import dev.toros.yde.server.transport.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlin.test.assertNull

/** 配布する資源を実際の CIO から配信し、一時ワークスペースだけを公開する。 */
internal class WebHttpFixture {
    val root = Files.createTempDirectory("yde-web-http")
    private val owner = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val host = WorkspaceHost(owner = owner)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val grants = ConnectionGrants()
    val reads = WorkspaceReads(host, grants::get, { "web-test" })
    val operations = WorkspaceOperations(host, grants::operationGrant, { "web-test" })
    val connections: LocalConnections = LocalConnections(scope, { _, connections ->
        LoopbackEngine(0) {
            localConnectionGate(connections)
            localAuthentication(connections, access)
            webAssets()
            webApi(access, reads, operations)
        }
    }, onDisabled = { access.featureStopped(it) })
    val access = LocalAccess(scope, connections, grants, {
        host.status.value.let { if (it.workspace != null) AccessWorkspace("web-test", it.epoch) else null }
    })
    val http = HttpClient(CIO)
    val url: String get() = "http://127.0.0.1:${connections.status.value.boundPort}"
    suspend fun start(sample: Path? = null, prepare: (Path) -> Unit = {}) {
        if (sample == null) Files.writeString(root.resolve("a.yaml"), "title: 保存済み\n")
        else Files.walk(sample).use { paths -> paths.forEach { source ->
            val target = root.resolve(sample.relativize(source))
            if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
        } }
        prepare(root)
        withContext(owner) { host.openWorkspace(root) }
        configure(LocalConnectionSettings(webEnabled = true))
    }
    suspend fun configure(settings: LocalConnectionSettings) {
        assertNull(withTimeout(10_000) { connections.awaitSettled(connections.configure(settings)) }.error)
    }
    fun register(actions: Set<String> = emptySet()) = access.register(LocalFeature.WEB, "ブラウザ試験", ConnectionPermissions(readScope = ConnectionReadScope.WORKSPACE,
        actionScope = if (actions.isEmpty()) ConnectionWriteScope.SELECTED else ConnectionWriteScope.LEDGER, actions = actions))
    suspend fun close() {
        try { connections.shutdown() }
        finally { http.close(); withContext(owner) { host.close() }; scope.cancel(); owner.close(); root.toFile().deleteRecursively() }
    }
}
