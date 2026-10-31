package dev.toros.yde.app.service

import dev.toros.yde.workspace.WorkspacePaths
import dev.toros.yde.yaml.model.DocumentId
import java.util.Collections

enum class ConnectionCapability { READ, PROPOSE, APPLY }

/** 空集合は許可なし。提案・適用は読取可能な文書の範囲へ狭める。ワイルドカードは扱わない。 */
data class ConnectionPermissions(
    val readable: Set<DocumentId> = emptySet(),
    val proposable: Set<DocumentId> = emptySet(),
    val actionable: Set<DocumentId> = emptySet(),
    val actions: Set<String> = emptySet(),
) {
    internal fun frozen(): ConnectionPermissions {
        require(readable.size <= 10_000 && actions.size <= 1_000) { "接続権限の対象が多すぎます" }
        require(readable.containsAll(proposable) && readable.containsAll(actionable)) { "変更対象には閲覧権限も必要です" }
        require(readable.all { it.value.length <= 1024 && WorkspacePaths.relative(it.value).toString().replace('\\', '/') == it.value }) {
            "文書はワークスペース相対の識別子で指定してください"
        }
        require(actions.all { it.isNotBlank() && it.length <= 200 }) { "操作名が不正です" }
        fun <T> Set<T>.freeze(): Set<T> = Collections.unmodifiableSet(LinkedHashSet(this))
        return copy(readable = readable.freeze(), proposable = proposable.freeze(), actionable = actionable.freeze(), actions = actions.freeze())
    }

    fun documents(capability: ConnectionCapability): Set<DocumentId> = when (capability) {
        ConnectionCapability.READ -> readable
        ConnectionCapability.PROPOSE -> proposable
        ConnectionCapability.APPLY -> actionable
    }

    fun allows(capability: ConnectionCapability): Boolean = documents(capability).isNotEmpty() &&
        (capability != ConnectionCapability.APPLY || actions.isNotEmpty())
}

data class ConnectionGrant(val caller: OperationCaller, val revision: Long, val permissions: ConnectionPermissions) {
    fun allows(capability: ConnectionCapability, documents: Set<DocumentId> = emptySet(), action: String? = null): Boolean =
        permissions.allows(capability) && permissions.documents(capability).containsAll(documents) &&
            (action == null || capability == ConnectionCapability.APPLY && action in permissions.actions)
}

/** デスクトップが登録する権限の正。通信資格情報は持たず、変更版を既存の計画・提出の再確認に渡す。 */
class ConnectionGrants {
    private val entries = linkedMapOf<OperationCaller, ConnectionGrant>()
    private var revision = 0L

    @Synchronized fun put(caller: OperationCaller, permissions: ConnectionPermissions): ConnectionGrant {
        require(caller.workspaceId.isNotBlank() && caller.workspaceId.length <= 200 && caller.callerId.isNotBlank() && caller.callerId.length <= 200) {
            "ワークスペースまたは接続者の識別子が不正です"
        }
        require(caller in entries || entries.size < 256) { "接続登録の上限に達しました" }
        return ConnectionGrant(caller, ++revision, permissions.frozen()).also { entries[caller] = it }
    }

    @Synchronized fun get(caller: OperationCaller): ConnectionGrant? = entries[caller]
    @Synchronized fun remove(caller: OperationCaller) { entries.remove(caller) }

    fun operationGrant(caller: OperationCaller): OperationGrant? = get(caller)?.takeIf {
        it.permissions.allows(ConnectionCapability.APPLY)
    }?.let { OperationGrant(it.revision, it.permissions.actionable, it.permissions.actions) }

    fun workGrant(caller: OperationCaller): WorkGrant? = get(caller)?.takeIf {
        it.permissions.allows(ConnectionCapability.PROPOSE)
    }?.let { WorkGrant(it.revision, it.permissions.proposable) }
}
