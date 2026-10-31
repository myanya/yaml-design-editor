package dev.toros.yde.app.service

import dev.toros.yde.workspace.WorkspacePaths
import dev.toros.yde.yaml.model.DocumentId
import java.util.Collections

enum class ConnectionCapability { READ, PROPOSE, APPLY }

/** WORKSPACE は今後の追加も含む。保存済み YAML の採取範囲とワークスペースの境界は読取サービスが守る。 */
enum class ConnectionReadScope { SELECTED, WORKSPACE }

/** 指定文書の空集合は許可なし。全体閲覧は明示した範囲で表し、提案・適用を暗黙に許可しない。 */
data class ConnectionPermissions(
    val readable: Set<DocumentId> = emptySet(),
    val proposable: Set<DocumentId> = emptySet(),
    val actionable: Set<DocumentId> = emptySet(),
    val actions: Set<String> = emptySet(),
    val readScope: ConnectionReadScope = ConnectionReadScope.SELECTED,
) {
    internal fun frozen(): ConnectionPermissions {
        require(listOf(readable, proposable, actionable).all { it.size <= 10_000 } && actions.size <= 1_000) { "接続権限の対象が多すぎます" }
        require(readScope != ConnectionReadScope.WORKSPACE || readable.isEmpty()) { "全体閲覧と指定文書を同時に保存できません" }
        require(proposable.all(::canRead) && actionable.all(::canRead)) { "変更対象には閲覧権限も必要です" }
        require((readable + proposable + actionable).all(::canonicalDocument)) {
            "文書はワークスペース相対の識別子で指定してください"
        }
        require(actions.all { it.isNotBlank() && it.length <= 200 }) { "操作名が不正です" }
        fun <T> Set<T>.freeze(): Set<T> = Collections.unmodifiableSet(LinkedHashSet(this))
        return copy(readable = readable.freeze(), proposable = proposable.freeze(), actionable = actionable.freeze(), actions = actions.freeze())
    }

    fun canRead(document: DocumentId): Boolean = when (readScope) {
        ConnectionReadScope.SELECTED -> document in readable
        ConnectionReadScope.WORKSPACE -> canonicalDocument(document)
    }

    fun documents(capability: ConnectionCapability): Set<DocumentId> = when (capability) {
        ConnectionCapability.READ -> readable
        ConnectionCapability.PROPOSE -> proposable
        ConnectionCapability.APPLY -> actionable
    }

    fun allows(capability: ConnectionCapability): Boolean = when (capability) {
        ConnectionCapability.READ -> readScope == ConnectionReadScope.WORKSPACE || readable.isNotEmpty()
        ConnectionCapability.PROPOSE -> proposable.isNotEmpty()
        ConnectionCapability.APPLY -> actionable.isNotEmpty() && actions.isNotEmpty()
    }
}

private fun canonicalDocument(id: DocumentId): Boolean = id.value.length <= 1024 && runCatching {
    WorkspacePaths.relative(id.value).toString().replace('\\', '/') == id.value
}.getOrDefault(false)

data class ConnectionGrant(val caller: OperationCaller, val revision: Long, val permissions: ConnectionPermissions) {
    fun allows(capability: ConnectionCapability, documents: Set<DocumentId> = emptySet(), action: String? = null): Boolean =
        permissions.allows(capability) &&
            (if (capability == ConnectionCapability.READ) documents.all(permissions::canRead)
             else permissions.documents(capability).containsAll(documents)) &&
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
