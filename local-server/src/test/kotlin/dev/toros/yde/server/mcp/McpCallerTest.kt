package dev.toros.yde.server.mcp

import dev.toros.yde.app.service.*
import dev.toros.yde.server.*
import dev.toros.yde.server.auth.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class McpCallerTest {
    private suspend fun caller(f: AccessFixture): Pair<McpCaller, LocalClient> {
        f.enable(LocalConnectionSettings(mcpEnabled = true))
        val client = f.client(LocalFeature.MCP, ConnectionPermissions(readScope = ConnectionReadScope.WORKSPACE,
            commitScope = ConnectionWriteScope.LEDGER))
        val token = f.access.issueBearer(client.id)
        return McpCaller(f.connections, f.access, f.access.authenticate(LocalFeature.MCP, token.value)) to client
    }

    @Test fun `MCP停止はSDKの処理も取り消し開始済みの保存の完了を待つ`(): Unit = runBlocking {
        val f = AccessFixture()
        try {
            val (caller, _) = caller(f)
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var saved = false
            val saving = launch {
                caller.run { request -> request.finishWrite { started.complete(Unit); finish.await(); saved = true } }
            }
            withTimeout(5_000) { started.await() }
            val stop = async { f.enable(LocalConnectionSettings(apiEnabled = true)) }
            withTimeout(5_000) { f.connections.status.first { it.features.getValue(LocalFeature.MCP).phase == ConnectionPhase.STOPPING } }
            assertFalse(stop.isCompleted)
            assertFalse(saved)
            finish.complete(Unit)
            withTimeout(5_000) { stop.await(); saving.join() }
            assertTrue(saved)
            assertTrue(saving.isCancelled)
            assertEquals(ConnectionPhase.RUNNING, f.connections.status.value.features.getValue(LocalFeature.API).phase)
            assertFailsWith<LocalAccessFailure> { caller.run { error("実行してはいけない") } }
        } finally { f.close() }
    }

    @Test fun `トークン失効はHTTPから切り離された呼出も取り消し再開させない`(): Unit = runBlocking {
        val f = AccessFixture()
        try {
            val (caller, client) = caller(f)
            val started = CompletableDeferred<Unit>()
            val reading = launch { caller.run { started.complete(Unit); awaitCancellation() } }
            withTimeout(5_000) { started.await() }
            f.access.revokeCredentials(client.id)
            withTimeout(5_000) { reading.join() }
            assertTrue(reading.isCancelled)
            assertFailsWith<LocalAccessFailure> { caller.grant() }
            assertFailsWith<LocalAccessFailure> { caller.run { error("失効後に実行してはいけない") } }
        } finally { f.close() }
    }

    @Test fun `保存前の失効は確定窓口で拒否する`(): Unit = runBlocking {
        val f = AccessFixture()
        try {
            val (caller, client) = caller(f)
            var saved = false
            val attempt = launch {
                assertFails {
                    caller.run { request ->
                        f.access.revokeCredentials(client.id)
                        request.finishWrite { saved = true }
                    }
                }
            }
            withTimeout(5_000) { attempt.join() }
            assertFalse(saved)
        } finally { f.close() }
    }
}
