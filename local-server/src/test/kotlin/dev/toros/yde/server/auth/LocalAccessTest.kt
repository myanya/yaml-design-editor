package dev.toros.yde.server.auth

import dev.toros.yde.app.service.*
import dev.toros.yde.server.*
import java.time.Duration
import kotlinx.coroutines.*
import kotlin.test.*

class LocalAccessTest {
    @Test fun `保存した登録を復元しても資格情報と別ワークスペースの許可は復活しない`() = runCase {
        enable()
        val id = java.util.UUID.randomUUID().toString()
        val caller = dev.toros.yde.app.service.OperationCaller("workspace", id)
        val registration = ClientRegistration(LocalClient(id, "保存した接続", LocalFeature.API, caller),
            ConnectionPermissions(readable = setOf(dev.toros.yde.yaml.model.DocumentId("a.yaml"))))
        access.restoreRegistrations(listOf(registration))
        assertEquals(listOf(registration), access.registrations())
        assertNull(access.credentialExpiry(id))
        assertNotNull(access.issueBearer(id))
        assertFailsWith<IllegalStateException> { access.restoreRegistrations(listOf(registration)) }
        workspace = AccessWorkspace("other", "epoch-2")
        access.workspaceChanged()
        assertNull(access.credentialExpiry(id))
        assertFailsWith<IllegalArgumentException> { access.issueBearer(id) }
    }

    private fun runCase(block: suspend AccessFixture.() -> Unit): Unit = runBlocking {
        val f = AccessFixture()
        try { withTimeout(15_000) { f.block() } } finally { f.close() }
    }
    private fun denied(status: Int, block: () -> Unit) { assertEquals(status, assertFailsWith<LocalAccessFailure>(block = block).status) }

    @Test fun `未起動では発行せず機能別に資格情報を分離して平文を表示へ漏らさない`() = runCase {
        val api = client(LocalFeature.API)
        assertFailsWith<IllegalStateException> { access.issueBearer(api.id) }
        enable()
        val token = access.issueBearer(api.id)
        assertEquals(43, token.value.length)
        assertFalse(token.toString().contains(token.value))
        denied(401) { access.authenticate(LocalFeature.MCP, token.value) }
        denied(401) { access.authenticate(LocalFeature.WEB, token.value) }
        denied(401) { access.authenticate(LocalFeature.API, "不正") }
        val identity = access.authenticate(LocalFeature.API, token.value)
        assertEquals(api.caller, access.check(identity, ConnectionCapability.READ).caller)
        denied(403) { access.check(identity, ConnectionCapability.PROPOSE) }
        denied(403) { access.check(identity, ConnectionCapability.APPLY) }
        assertFalse(identity.toString().contains(token.value))
        assertFailsWith<IllegalArgumentException> { access.issueWebSession(api.id) }
        val web = client(LocalFeature.WEB)
        assertFailsWith<IllegalArgumentException> { access.issueBearer(web.id) }
        val session = access.issueWebSession(web.id)
        assertNotNull(session.csrfToken)
        assertFalse(session.toString().contains(session.csrfToken))
        val browser = access.authenticate(LocalFeature.WEB, session.value)
        access.checkCsrf(browser, session.csrfToken)
        denied(403) { access.checkCsrf(browser, null) }
        denied(403) { access.checkCsrf(browser, token.value) }
        assertFailsWith<IllegalArgumentException> { access.issueBearer(api.id, Duration.ZERO) }
    }

    @Test fun `一機能のOFFと再ONで旧資格情報を復活させず他機能と提出権限を維持する`() = runCase {
        enable()
        val api = client(LocalFeature.API, ConnectionPermissions(setOf(a), proposable = setOf(a)))
        val mcp = client(LocalFeature.MCP)
        val old = access.issueBearer(api.id)
        val other = access.issueBearer(mcp.id)
        enable(LocalConnectionSettings(mcpEnabled = true))
        denied(401) { access.authenticate(LocalFeature.API, old.value) }
        assertNotNull(access.authenticate(LocalFeature.MCP, other.value))
        assertNotNull(grants.workGrant(api.caller), "提出の承認権限は通信の停止だけでは捨てない")
        enable()
        denied(401) { access.authenticate(LocalFeature.API, old.value) }
        val fresh = access.issueBearer(api.id)
        assertNotEquals(old.value, fresh.value)
        assertEquals(api.caller, access.authenticate(LocalFeature.API, fresh.value).client.caller)
    }

    @Test fun `期限切れと再発行と権限変更と登録削除で以前の認証を拒否する`() = runCase {
        enable()
        val api = client(LocalFeature.API)
        val first = access.issueBearer(api.id, Duration.ofSeconds(10))
        now = now.plusSeconds(10)
        denied(401) { access.authenticate(LocalFeature.API, first.value) }
        val second = access.issueBearer(api.id)
        val third = access.issueBearer(api.id)
        denied(401) { access.authenticate(LocalFeature.API, second.value) }
        val identity = access.authenticate(LocalFeature.API, third.value)
        access.setPermissions(api.id, ConnectionPermissions())
        denied(401) { access.check(identity, ConnectionCapability.READ) }
        val empty = access.issueBearer(api.id)
        denied(403) { access.check(access.authenticate(LocalFeature.API, empty.value), ConnectionCapability.READ) }
        access.remove(api.id)
        denied(401) { access.authenticate(LocalFeature.API, empty.value) }
        assertNull(grants.get(api.caller))
    }

    @Test fun `切替先へ権限を引き継がず同じワークスペースの再読込でも旧セッションを拒否する`() = runCase {
        enable()
        val api = client(LocalFeature.API)
        val old = access.issueBearer(api.id)
        workspace = AccessWorkspace("other", "epoch-2"); access.workspaceChanged()
        denied(401) { access.authenticate(LocalFeature.API, old.value) }
        assertFailsWith<IllegalArgumentException> { access.issueBearer(api.id) }
        workspace = AccessWorkspace("workspace", "epoch-3"); access.workspaceChanged()
        denied(401) { access.authenticate(LocalFeature.API, old.value) }
        val next = access.issueBearer(api.id)
        workspace = AccessWorkspace("workspace", "epoch-4"); access.workspaceChanged()
        denied(401) { access.authenticate(LocalFeature.API, next.value) }
        assertNotNull(access.issueBearer(api.id))
    }

    @Test fun `資格情報の失効は読取を取消し開始した保存だけ結果確定を待つ`() = runCase {
        enable()
        val api = client(LocalFeature.API)
        val identity = access.authenticate(LocalFeature.API, access.issueBearer(api.id).value)
        val readEntered = CompletableDeferred<Unit>()
        val writeEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<Unit>()
        coroutineScope {
            val read = launch { connections.withRequest(LocalFeature.API) {
                access.withIdentity(identity) { readEntered.complete(Unit); awaitCancellation() }
            } }
            val write = launch { connections.withRequest(LocalFeature.API) { request ->
                access.withIdentity(identity) { request.finishWrite { writeEntered.complete(Unit); release.await(); saved.complete(Unit) } }
            } }
            try {
                readEntered.await(); writeEntered.await()
                access.revokeCredentials(api.id)
                read.join()
                assertTrue(read.isCancelled)
                assertFalse(write.isCompleted)
                denied(401) { access.check(identity) }
            } finally { release.complete(Unit) }
            saved.await(); write.join()
        }
    }

    @Test fun `同じ資格情報の同時要求を制限し終了後に枠を戻す`() = runCase {
        enable()
        val api = client(LocalFeature.API)
        val identity = access.authenticate(LocalFeature.API, access.issueBearer(api.id).value)
        coroutineScope {
            val entered = List(16) { CompletableDeferred<Unit>() }
            val jobs = entered.map { ready -> launch { access.withIdentity(identity) { ready.complete(Unit); awaitCancellation() } } }
            try {
                entered.forEach { it.await() }
                assertEquals(429, assertFailsWith<LocalAccessFailure> { access.withIdentity(identity) {} }.status)
            } finally { jobs.forEach { it.cancel() }; jobs.joinAll() }
            access.withIdentity(identity) {}
        }
    }

    @Test fun `有効期限で開いたままの要求も取消される`() = runCase {
        enable()
        val api = client(LocalFeature.API)
        // 認証の時計は固定し、期限の待機だけを短くして取消を確かめる。
        val token = access.issueBearer(api.id, Duration.ofMillis(1))
        val identity = access.authenticate(LocalFeature.API, token.value)
        coroutineScope {
            val request = launch { access.withIdentity(identity) { awaitCancellation() } }
            withTimeout(5_000) { request.join() }
            assertTrue(request.isCancelled)
        }
    }

    @Test fun `失効後も保存の収束を待つ要求は全体の上限から外さない`() = runCase {
        enable()
        val clients = List(4) { client(LocalFeature.API) }
        val identities = clients.map { access.authenticate(LocalFeature.API, access.issueBearer(it.id).value) }
        val release = CompletableDeferred<Unit>()
        coroutineScope {
            val entered = List(64) { CompletableDeferred<Unit>() }
            val jobs = entered.mapIndexed { index, ready -> launch {
                access.withIdentity(identities[index / 16]) {
                    withContext(NonCancellable) { ready.complete(Unit); release.await() }
                }
            } }
            try {
                entered.forEach { it.await() }
                clients.forEach { access.revokeCredentials(it.id) }
                val fresh = access.authenticate(LocalFeature.API, access.issueBearer(clients.first().id).value)
                assertEquals(429, assertFailsWith<LocalAccessFailure> { access.withIdentity(fresh) {} }.status)
                release.complete(Unit); jobs.joinAll()
                access.withIdentity(fresh) {}
            } finally { release.complete(Unit); jobs.forEach { it.cancel() }; jobs.joinAll() }
        }
    }
}
