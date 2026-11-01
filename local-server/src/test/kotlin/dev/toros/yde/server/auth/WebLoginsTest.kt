package dev.toros.yde.server.auth

import dev.toros.yde.server.*
import dev.toros.yde.app.service.ConnectionPermissions
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class WebLoginsTest {
    @Test fun `接続コードは一度だけ使え境界時刻で期限が切れる`() = runBlocking<Unit> {
        val f = AccessFixture()
        try {
            f.enable()
            val registration = f.client(LocalFeature.WEB)
            val code = f.access.webLogins.issue(registration.id)
            assertFalse(code.toString().contains(code.value))
            val credential = f.access.webLogins.exchange(code.value)
            val identity = f.access.authenticate(LocalFeature.WEB, credential.value)
            assertEquals(credential.csrfToken, f.access.webLogins.session(identity)["csrfToken"])
            assertEquals(401, assertFailsWith<LocalAccessFailure> { f.access.webLogins.exchange(code.value) }.status)
            val next = f.access.webLogins.issue(registration.id)
            assertFailsWith<LocalAccessFailure> { f.access.webLogins.session(identity) }
            f.now = next.expiresAt
            assertFailsWith<LocalAccessFailure> { f.access.webLogins.exchange(next.value) }
        } finally { f.close() }
    }

    @Test fun `権限変更と機能停止とワークスペース切替で未使用コードも失効する`() = runBlocking<Unit> {
        val f = AccessFixture()
        try {
            f.enable()
            val registration = f.client(LocalFeature.WEB)
            var code = f.access.webLogins.issue(registration.id)
            f.access.setPermissions(registration.id, f.readable)
            assertFailsWith<LocalAccessFailure> { f.access.webLogins.exchange(code.value) }
            code = f.access.webLogins.issue(registration.id)
            f.enable(LocalConnectionSettings(apiEnabled = true))
            f.enable()
            assertFailsWith<LocalAccessFailure> { f.access.webLogins.exchange(code.value) }
            code = f.access.webLogins.issue(registration.id)
            f.workspace = AccessWorkspace("workspace", "epoch-2")
            assertFailsWith<LocalAccessFailure> { f.access.webLogins.exchange(code.value) }
            val denied = f.client(LocalFeature.WEB, ConnectionPermissions())
            assertFailsWith<LocalAccessFailure> { f.access.webLogins.issue(denied.id) }
        } finally { f.close() }
    }
}
