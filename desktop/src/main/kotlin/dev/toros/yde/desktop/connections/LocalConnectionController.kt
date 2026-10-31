package dev.toros.yde.desktop.connections

import dev.toros.yde.app.service.ConnectionGrants
import dev.toros.yde.app.service.ConnectionPermissions
import dev.toros.yde.server.*
import dev.toros.yde.server.auth.*
import dev.toros.yde.desktop.lifecycle.ResidentSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ConnectionSettingsState(
    val ready: Boolean = false, val busy: Boolean = false, val blocked: Boolean = false,
    val error: String? = null, val message: String? = null,
    val registrations: List<ClientRegistration> = emptyList(),
    val resident: ResidentSettings = ResidentSettings(),
)

/** 設定の保存と受付変更の順序を所有する。Window を閉じても接続管理は Runtime に残る。 */
internal class LocalConnectionController(
    val connections: LocalConnections,
    val access: LocalAccess,
    private val store: ConnectionSettingsStore,
    private val workspace: () -> AccessWorkspace?,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(ConnectionSettingsState())
    private var closing = false
    val state = mutableState.asStateFlow()
    fun workspaceId(): String? = workspace()?.id
    suspend fun stopAccepting() = mutex.withLock { closing = true }
    fun resumeAccepting() { closing = false }

    suspend fun initialize() = mutex.withLock {
        if (state.value.ready && !state.value.blocked || closing) return@withLock
        mutableState.value = state.value.copy(busy = true)
        try {
            val saved = withContext(Dispatchers.IO) { store.load() }
            access.restoreRegistrations(saved.registrations)
            connections.configure(saved.settings)
            mutableState.value = ConnectionSettingsState(ready = true, registrations = access.registrations(), resident = saved.resident)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            mutableState.value = ConnectionSettingsState(ready = true, blocked = true,
                error = "接続設定を読み込めませんでした。全機能を停止したままにしています。${reason(failure)} 原因を修復して「設定を再読込」を押してください。")
        }
    }

    private suspend fun <T> edit(block: suspend () -> T): T? = mutex.withLock {
        mutableState.value = state.value.copy(busy = true, error = null, message = null)
        try {
            check(state.value.ready) { "接続設定を読み込んでいます" }
            check(!closing) { "アプリを終了しています" }
            check(!state.value.blocked) { "読み込めなかった接続設定を先に確認してください" }
            // 保存を始めたら画面の破棄でも中断せず、永続値と実状態の反映を一緒に終える。
            withContext(NonCancellable) { block() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutableState.value = state.value.copy(error = failure.message ?: "接続設定を変更できませんでした"); null }
        finally { mutableState.value = state.value.copy(busy = false, registrations = access.registrations()) }
    }

    private suspend fun save(settings: LocalConnectionSettings = connections.status.value.desired,
                             registrations: List<ClientRegistration> = access.registrations(),
                             resident: ResidentSettings = state.value.resident) {
        try { withContext(Dispatchers.IO) { store.save(SavedConnections(settings, registrations, resident)) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error("接続設定を保存できませんでした。${reason(failure)}") }
    }

    private fun reason(failure: Exception): String = when (failure) {
        is ConnectionProtectionException -> failure.message.orEmpty()
        is java.nio.file.AccessDeniedException -> "保存先へアクセスする権限がありません。"
        is kotlinx.serialization.SerializationException -> "設定ファイルの JSON を読み取れません。"
        is IllegalArgumentException, is NoSuchElementException -> "設定の形式・値・登録権限が不正です。"
        else -> "保存先の権限・空き容量と設定内容を確認してください（${failure.javaClass.simpleName}）。"
    }

    suspend fun setEnabled(feature: LocalFeature, enabled: Boolean) = edit {
        val old = connections.status.value.desired
        val next = when (feature) {
            LocalFeature.API -> old.copy(apiEnabled = enabled)
            LocalFeature.MCP -> old.copy(mcpEnabled = enabled)
            LocalFeature.WEB -> old.copy(webEnabled = enabled)
        }
        if (!enabled) connections.configure(next)
        try { save(next) }
        catch (failure: Exception) {
            // 停止は保存失敗で妨げない。復元値の不一致を利用者へ伝え、再試行を残す。
            if (failure is CancellationException) throw failure
            error(failure.message + if (!enabled) " 今回は停止しますが、次回起動時は保存済みの設定に戻ります。「設定保存と接続を再試行」を実行してください。" else " 待受は開始していません。")
        }
        if (enabled) connections.configure(next)
    }

    suspend fun setPort(text: String) = edit {
        val status = connections.status.value
        require(status.stopped && !status.desired.requiresListener) { "ポートは全機能の停止完了後に変更してください" }
        val port = requireNotNull(text.toIntOrNull()) { "ポート番号は 1〜65535 で指定してください" }
        val next = status.desired.copy(port = port)
        save(next)
        connections.configure(next)
    }

    suspend fun retry() = edit { save(); connections.configure(connections.status.value.desired) }

    suspend fun setResident(settings: ResidentSettings) = edit {
        save(resident = settings)
        mutableState.value = state.value.copy(resident = settings)
    }

    suspend fun register(feature: LocalFeature, name: String, permissions: ConnectionPermissions) = edit {
        val client = access.register(feature, name, permissions)
        try { save() } catch (failure: Exception) { access.remove(client.id); throw failure }
        client
    }

    suspend fun setPermissions(id: String, permissions: ConnectionPermissions) = edit {
        val current = access.registrations().first { it.client.id == id }
        val checked = ConnectionGrants().put(current.client.caller, permissions).permissions
        val next = access.registrations().map { if (it.client.id == id) it.copy(permissions = checked) else it }
        // 保存できない権限へは切り替えない。既存資格情報は更新成功時に必ず失効する。
        save(registrations = next)
        access.setPermissions(id, checked)
        mutableState.value = state.value.copy(message = "権限を保存し、旧資格情報を失効させました。")
    }

    suspend fun remove(id: String) = edit {
        access.revokeCredentials(id)
        save(registrations = access.registrations().filterNot { it.client.id == id })
        access.remove(id)
    }

    suspend fun revoke(id: String) = edit {
        access.revokeCredentials(id)
        mutableState.value = state.value.copy(message = "資格情報を失効させました。登録した権限と提出済みの作業は残ります。")
    }

    suspend fun issue(id: String): IssuedCredential? = edit {
        val client = access.clients().first { it.id == id }
        require(client.feature != LocalFeature.WEB) { "Web のログインは画面配信の公開後に利用できます" }
        access.issueBearer(id)
    }

    suspend fun reset() = mutex.withLock {
        if (closing) return@withLock
        mutableState.value = state.value.copy(busy = true, error = null)
        try {
            withContext(NonCancellable) {
                require(state.value.blocked && connections.status.value.stopped) { "初期化は設定の読込失敗時だけ行えます" }
                save(LocalConnectionSettings(), emptyList(), ResidentSettings())
                access.clients().forEach { access.remove(it.id) }
                connections.configure(LocalConnectionSettings())
                mutableState.value = ConnectionSettingsState(ready = true, message = "接続設定を初期化しました。")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutableState.value = state.value.copy(busy = false, error = failure.message) }
    }
}
