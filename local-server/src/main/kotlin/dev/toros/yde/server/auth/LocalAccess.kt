package dev.toros.yde.server.auth

import dev.toros.yde.app.service.*
import dev.toros.yde.server.LocalConnections
import dev.toros.yde.server.LocalFeature
import dev.toros.yde.server.ConnectionPhase
import dev.toros.yde.yaml.model.DocumentId
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*

data class AccessWorkspace(val id: String, val epoch: String)
data class LocalClient(val id: String, val name: String, val feature: LocalFeature, val caller: OperationCaller)

/** 平文は発行の戻り値だけに出す。ログや状態一覧へ自動展開しない。永続化は設定側の専用保管庫が担う。 */
class IssuedCredential internal constructor(val value: String, val csrfToken: String?, val expiresAt: Instant) {
    override fun toString() = "IssuedCredential(非表示)"
}

class LocalIdentity internal constructor(internal val key: String, val client: LocalClient) {
    override fun toString() = "LocalIdentity(${client.id}, ${client.feature})"
}

internal class LocalAccessFailure(val status: Int, message: String) : IllegalStateException(message)

/** アプリ内の信頼する設定操作だけが登録・発行・失効を呼ぶ。要求本文から権限を登録しない。 */
class LocalAccess(
    private val scope: CoroutineScope,
    private val connections: LocalConnections,
    private val grants: ConnectionGrants,
    private val workspace: () -> AccessWorkspace?,
    private val clock: () -> Instant = Instant::now,
) {
    private class Credential(
        val identity: LocalIdentity, val epoch: String, val generation: String, val revision: Long,
        val expires: Instant, val csrfHash: ByteArray?, val jobs: MutableSet<Job> = linkedSetOf(),
    )
    private val monitor = Any()
    private val random = SecureRandom()
    private val clients = linkedMapOf<String, LocalClient>()
    private val credentials = linkedMapOf<String, Credential>()
    private val mcpSessions = linkedMapOf<String, String>()
    private val activeRequests = linkedSetOf<Job>()

    fun register(feature: LocalFeature, name: String, permissions: ConnectionPermissions): LocalClient = synchronized(monitor) {
        val current = requireNotNull(workspace()) { "ワークスペースが開かれていません" }
        require(name.isNotBlank() && name.length <= 100) { "接続名は 1〜100 文字で指定してください" }
        require(clients.size < 256) { "接続登録の上限に達しました" }
        val id = UUID.randomUUID().toString()
        LocalClient(id, name, feature, OperationCaller(current.id, id)).also {
            grants.put(it.caller, permissions); clients[id] = it
        }
    }

    fun clients(): List<LocalClient> = synchronized(monitor) { clients.values.toList() }
    fun grant(clientId: String): ConnectionGrant? = synchronized(monitor) { clients[clientId]?.let { grants.get(it.caller) } }

    fun setPermissions(clientId: String, permissions: ConnectionPermissions) = synchronized(monitor) {
        val client = requireNotNull(clients[clientId]) { "接続登録が見つかりません" }
        grants.put(client.caller, permissions)
        invalidate { it.identity.client.id == clientId }
    }

    fun revokeCredentials(clientId: String) = synchronized(monitor) { invalidate { it.identity.client.id == clientId } }

    fun remove(clientId: String) = synchronized(monitor) {
        clients.remove(clientId)?.let { grants.remove(it.caller) }
        invalidate { it.identity.client.id == clientId }
    }

    /** 再発行は同じ登録の旧資格情報と要求を失効させる。API/MCP と Web Cookie を取り違えない。 */
    fun issueBearer(clientId: String, lifetime: Duration = Duration.ofHours(8)): IssuedCredential = issue(clientId, false, lifetime)
    fun issueWebSession(clientId: String, lifetime: Duration = Duration.ofMinutes(30)): IssuedCredential = issue(clientId, true, lifetime)

    private fun issue(clientId: String, web: Boolean, lifetime: Duration): IssuedCredential = synchronized(monitor) {
        val client = requireNotNull(clients[clientId]) { "接続登録が見つかりません" }
        require((client.feature == LocalFeature.WEB) == web) { "機能に対応する資格情報を発行してください" }
        require(!lifetime.isNegative && !lifetime.isZero && lifetime <= if (web) Duration.ofHours(8) else Duration.ofDays(30)) {
            "資格情報の有効期間が範囲外です"
        }
        val current = requireNotNull(workspace()) { "ワークスペースが開かれていません" }
        require(current.id == client.caller.workspaceId) { "登録したワークスペースを開いてください" }
        val generation = connections.status.value.features.getValue(client.feature).generation
        check(accepting(client.feature, generation)) { "接続機能の起動が完了していません" }
        val grant = requireNotNull(grants.get(client.caller)) { "接続権限がありません" }
        invalidate { it.identity.client.id == clientId || !clock().isBefore(it.expires) }
        val value = secret()
        val key = digest(value)
        val csrf = if (web) secret() else null
        val expires = clock().plus(lifetime)
        credentials[key] = Credential(LocalIdentity(key, client), current.epoch, generation, grant.revision, expires, csrf?.let(::hash))
        IssuedCredential(value, csrf, expires)
    }

    internal fun authenticate(feature: LocalFeature, value: String): LocalIdentity = synchronized(monitor) {
        if (!value.matches(Regex("[A-Za-z0-9_-]{43}"))) unauthorized()
        val entry = credentials[digest(value)] ?: unauthorized()
        if (entry.identity.client.feature != feature) unauthorized()
        current(entry.identity)
        entry.identity
    }

    /** 本文を読む前、業務処理へ入る前、応答直前に同じ証明を再確認する。 */
    internal fun check(identity: LocalIdentity, capability: ConnectionCapability? = null,
                       documents: Set<DocumentId> = emptySet(), action: String? = null): ConnectionGrant = synchronized(monitor) {
        val grant = current(identity).second
        if (capability != null && !grant.allows(capability, documents, action)) forbidden()
        grant
    }

    internal fun checkCsrf(identity: LocalIdentity, value: String?) = synchronized(monitor) {
        val expected = current(identity).first.csrfHash ?: forbidden()
        if (value == null || value.length != 43 || !MessageDigest.isEqual(expected, hash(value))) forbidden()
    }

    internal fun checkMcpSession(identity: LocalIdentity, id: String) = synchronized(monitor) {
        current(identity)
        if (identity.client.feature != LocalFeature.MCP || mcpSessions[id] != identity.key) {
            throw LocalAccessFailure(404, "MCP セッションを再初期化してください")
        }
    }

    internal fun bindMcpSession(identity: LocalIdentity, id: String) = synchronized(monitor) {
        current(identity)
        if (identity.client.feature != LocalFeature.MCP || !id.matches(Regex("[!-~]{1,128}"))) forbidden()
        val owner = mcpSessions[id]
        if (owner != null && owner != identity.key) forbidden()
        if (owner == null && (mcpSessions.size >= 256 || mcpSessions.values.count { it == identity.key } >= 8)) {
            throw LocalAccessFailure(429, "MCP セッションの上限に達しました")
        }
        mcpSessions[id] = identity.key
    }

    internal fun endMcpSession(identity: LocalIdentity, id: String) = synchronized(monitor) {
        if (mcpSessions[id] == identity.key) mcpSessions.remove(id)
    }

    private fun current(identity: LocalIdentity): Pair<Credential, ConnectionGrant> {
        val entry = credentials[identity.key]?.takeIf { it.identity === identity } ?: unauthorized()
        val client = entry.identity.client
        val current = workspace()
        val grant = grants.get(client.caller)
        if (current?.id != client.caller.workspaceId || current.epoch != entry.epoch ||
            !accepting(client.feature, entry.generation) || !clock().isBefore(entry.expires) || grant?.revision != entry.revision) unauthorized()
        return entry to grant
    }

    /** 失効と期限切れで子ストリームも止める。保存済みの作業や登録した権限は削除しない。 */
    internal suspend fun withIdentity(identity: LocalIdentity, block: suspend () -> Unit) = coroutineScope {
        val job = currentCoroutineContext().job
        val entry = synchronized(monitor) {
            val entry = current(identity).first
            if (activeRequests.size >= 64 || entry.jobs.size >= 16) {
                throw LocalAccessFailure(429, "同時要求の上限に達しました")
            }
            entry.jobs += job
            activeRequests += job
            entry
        }
        val deadline = scope.launch(start = CoroutineStart.LAZY) {
            delay(Duration.between(clock(), entry.expires).toMillis().coerceAtLeast(1))
            job.cancel(CancellationException("接続の有効期限が切れました"))
        }
        job.invokeOnCompletion {
            deadline.cancel()
            synchronized(monitor) { activeRequests.remove(job); entry.jobs.remove(job) }
        }
        deadline.start()
        block()
    }

    internal fun featureStopped(feature: LocalFeature) = synchronized(monitor) { invalidate { it.identity.client.feature == feature } }
    fun workspaceChanged() = synchronized(monitor) {
        val current = workspace()
        invalidate { it.identity.client.caller.workspaceId != current?.id || it.epoch != current.epoch }
    }

    private fun invalidate(predicate: (Credential) -> Boolean) {
        val removed = credentials.values.filter(predicate)
        removed.forEach { credentials.remove(it.identity.key) }
        val keys = removed.map { it.identity.key }.toSet()
        mcpSessions.entries.removeIf { it.value in keys }
        removed.flatMap { it.jobs.toList() }.forEach { it.cancel(CancellationException("接続の認証が失効しました")) }
    }

    private fun secret(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    // 認証のロックから接続管理のロックへ入らない。停止時の要求取消と逆順にならないよう、公開済みの不変値を読む。
    private fun accepting(feature: LocalFeature, generation: String): Boolean = connections.status.value.let {
        it.desired.enabled(feature) && it.listener == ConnectionPhase.RUNNING &&
            it.features.getValue(feature).let { value -> value.phase == ConnectionPhase.RUNNING && value.generation == generation }
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    private fun digest(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(hash(value))
    private fun unauthorized(): Nothing = throw LocalAccessFailure(401, "接続の認証が必要です。資格情報を確認してください")
    private fun forbidden(): Nothing = throw LocalAccessFailure(403, "この要求は許可されていません")
}
