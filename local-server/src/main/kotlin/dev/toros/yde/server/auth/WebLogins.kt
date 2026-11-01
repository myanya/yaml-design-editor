package dev.toros.yde.server.auth

import dev.toros.yde.app.service.ConnectionCapability
import dev.toros.yde.server.LocalFeature
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/** デスクトップに一度だけ返す入力用コード。URL・保存設定・ログには置かない。 */
class WebLoginCode internal constructor(val value: String, val expiresAt: Instant) {
    override fun toString() = "WebLoginCode(非表示)"
}

/** 一回限りのコードから Cookie へ交換する。接続の許可と失効は LocalAccess が引き続き所有する。 */
class WebLogins internal constructor(private val access: LocalAccess, private val clock: () -> Instant) {
    private data class Pending(val credential: IssuedCredential, val identity: LocalIdentity, val expires: Instant)
    private data class Session(val identity: LocalIdentity, val csrf: String, val expires: Instant)
    private val pending = linkedMapOf<String, Pending>()
    private val sessions = linkedMapOf<String, Session>()
    private val random = SecureRandom()

    @Synchronized fun issue(clientId: String): WebLoginCode {
        purge()
        val credential = access.issueWebSession(clientId)
        val identity = access.authenticate(LocalFeature.WEB, credential.value)
        access.check(identity, ConnectionCapability.READ)
        val code = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val expiry = clock().plusSeconds(120)
        pending[hash(code)] = Pending(credential, identity, expiry)
        return WebLoginCode(code, expiry)
    }

    @Synchronized internal fun exchange(code: String): IssuedCredential {
        purge()
        val value = pending.remove(hash(code)) ?: throw LocalAccessFailure(401, "接続コードが無効、使用済み、または期限切れです。デスクトップで再発行してください")
        access.check(value.identity, ConnectionCapability.READ)
        sessions[value.identity.key] = Session(value.identity, requireNotNull(value.credential.csrfToken), value.credential.expiresAt)
        return value.credential
    }

    @Synchronized internal fun session(identity: LocalIdentity): Map<String, Any?> {
        purge()
        access.check(identity, ConnectionCapability.READ)
        val session = sessions[identity.key] ?: throw LocalAccessFailure(401, "デスクトップで接続コードを発行し、接続してください")
        return mapOf("clientId" to identity.client.id, "clientName" to identity.client.name,
            "workspaceId" to identity.client.caller.workspaceId, "expiresAt" to session.expires.toString(), "csrfToken" to session.csrf)
    }

    @Synchronized internal fun logout(identity: LocalIdentity) {
        access.check(identity)
        sessions.remove(identity.key)
        access.endWebSession(identity)
    }

    private fun purge() {
        pending.entries.removeIf { !clock().isBefore(it.value.expires) || !valid(it.value.identity) }
        sessions.entries.removeIf { !clock().isBefore(it.value.expires) || !valid(it.value.identity) }
    }
    private fun valid(identity: LocalIdentity) = runCatching { access.check(identity) }.isSuccess
    private fun hash(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
}
