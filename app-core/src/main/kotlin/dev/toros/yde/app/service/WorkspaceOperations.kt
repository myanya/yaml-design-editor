package dev.toros.yde.app.service

import dev.toros.yde.app.core.Workspace
import dev.toros.yde.workspace.*
import dev.toros.yde.workspace.changeset.ChangeSet
import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.workspace.validate.CheckIssue
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YDocument
import dev.toros.yde.yaml.validate.Severity
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** 接続者は認証済みの入口が設定する。要求本文から権限を作らない。 */
data class OperationCaller(val workspaceId: String, val callerId: String)

data class OperationGrant(
    val revision: Long,
    val documents: Set<DocumentId>,
    val actions: Set<String> = emptySet(),
    /** 外部提案の承認など、信頼できる入口にだけ与える。 */
    val applyChanges: Boolean = false,
    val recover: Boolean = false,
)

data class OperationPreview(
    val id: String, val expiresAt: Instant, val documents: List<PreparedDocumentChange>,
    val issues: Map<DocumentId, List<CheckIssue>>, val failures: Map<String, String>, val canCommit: Boolean,
)

/** 通信非依存の外部確定窓口。全入口を Host の所有スレッドと排他へ集める。 */
class WorkspaceOperations(
    private val host: WorkspaceHost,
    private val grants: (OperationCaller) -> OperationGrant? = { null },
    private val workspaceId: (Path) -> String = { ChangeSets.contentHash(it.toRealPath().toString()) },
    private val journals: (Path, WorkspaceLayout) -> OperationJournal = { path, layout -> FileOperationJournal(path, layout) },
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val planLifetime: Duration = Duration.ofMinutes(10),
    private val load: (Path) -> WorkspaceSession = { Workspace.open(it).mirror },
) {
    private data class Plan(
        val id: String, val caller: OperationCaller, val epoch: String, val revision: Long,
        val grant: OperationGrant, val expires: Instant, val before: Map<DocumentId, String>,
        val prepared: PreparedChangeSet, val action: ActionRequest?,
    )
    private val plans = linkedMapOf<String, Plan>()

    /** 後続の接続登録が別の識別子を使う場合も、この解決だけを差し替える。 */
    suspend fun currentWorkspaceId(): String = host.access { workspaceId(requireNotNull(it.workspace)) }

    suspend fun planAction(caller: OperationCaller, request: ActionRequest): OperationPreview {
        @Suppress("UNCHECKED_CAST")
        val frozen = request.copy(params = YamlIO.toAny(YamlIO.parse(YamlIO.dumpPlain(YamlIO.fromAny(request.params)))) as Map<String, Any?>)
        return plan(caller, frozen) { base, now -> ActionPlanner.plan(base, frozen, now).changeSet }
    }

    /** 確認済み提案用。権限を持たない外部接続から直接呼んでも確定権限は得られない。 */
    suspend fun planChanges(caller: OperationCaller, changes: ChangeSet): OperationPreview {
        val frozen = ChangeSets.copyOf(changes)
        return plan(caller, null) { _, _ -> frozen }
    }

    private suspend fun plan(caller: OperationCaller, action: ActionRequest?, make: (WorkspaceSession, LocalDateTime) -> ChangeSet): OperationPreview =
        host.exclusive("操作の計画") {
            val (root, grant) = authorize(caller)
            checkKind(grant, action)
            val status = host.currentStatus()
            val now = clock()
            plans.entries.removeIf { !now.isBefore(it.value.expires) || it.value.epoch != status.epoch }
            require(plans.size < 256) { "計画が多すぎます。期限切れを待って再計画してください" }
            val (before, prepared, layout) = withContext(Dispatchers.IO) {
                val base = load(root)
                require(base.schemaErrors.isEmpty()) { "スキーマの読込エラーがあるため計画できません" }
                val before = base.storedState()
                require(before == base.readStoreState()) { "読込中に変更されました。再計画してください" }
                val prepared = ChangeSetApplier.prepare(base, make(base, LocalDateTime.ofInstant(now, zone)), LocalDateTime.ofInstant(now, zone))
                require(before == base.readStoreState()) { "計画中に変更されました。再計画してください" }
                Triple(before, prepared, base.layout)
            }
            checkSnapshot(status.epoch, status.revision)
            require(authorize(caller).second == grant) { "権限が変更されました。再計画してください" }
            checkTargets(grant, prepared, layout)
            host.documents.checkExternalTargets(prepared.documents.map { it.id }.toSet())
            val value = Plan(UUID.randomUUID().toString(), caller, status.epoch, status.revision,
                grant, now.plus(planLifetime), before, prepared, action)
            plans[value.id] = value
            OperationPreview(value.id, value.expires, prepared.documents.toList(), prepared.issues.toMap(), prepared.failures.toMap(), prepared.valid)
        }

    suspend fun commit(caller: OperationCaller, planId: String, requestId: String): OperationRecord = host.exclusive("操作の確定") {
        require(requestId.isNotBlank() && requestId.length <= 200) { "要求 ID は 1〜200 文字で指定してください" }
        val (root, grant) = authorize(caller)
        val journal = journals(root, requireNotNull(host.documents.core).layout)
        val hash = ChangeSets.contentHash(buildJsonArray { add(caller.workspaceId); add(caller.callerId); add(planId) }.toString())
        val records = withContext(Dispatchers.IO) { readRecords(journal) }
        records.firstOrNull { it.workspaceId == caller.workspaceId && it.callerId == caller.callerId && it.requestId == requestId }?.let {
            require(it.after.keys.all { file -> DocumentId(file) in grant.documents }) { "結果の対象文書は現在許可されていません" }
            require(it.requestHash == hash) { "同じ要求 ID が異なる内容に使われています" }
            return@exclusive it
        }
        val plan = plans[planId] ?: error("計画がありません。再計画してください")
        require(plan.caller == caller) { "別の接続者の計画です" }
        val ids = plan.prepared.documents.map { it.id }.toSet()
        var record = OperationRecord(caller.workspaceId, caller.callerId, requestId, planId, hash, OperationState.RECEIVED,
            plan.prepared.documents.associate { it.id.value to it.before?.let(ChangeSets::contentHash) },
            plan.prepared.documents.associate { it.id.value to ChangeSets.contentHash(it.after) })
        var started = false
        try {
            require(clock().isBefore(plan.expires)) { "計画の期限が切れました。再計画してください" }
            checkSnapshot(plan.epoch, plan.revision)
            require(plan.grant == grant) { "権限が変更されました。再計画してください" }
            checkKind(grant, plan.action)
            checkTargets(grant, plan.prepared, requireNotNull(host.documents.core).layout)
            host.documents.checkExternalTargets(ids)
            require(plan.prepared.valid) { "検証エラーが残るため確定できません" }
            require(records.none { it.workspaceId == caller.workspaceId && it.state == OperationState.UNKNOWN && it.after.keys.any { key -> DocumentId(key) in ids } }) {
                "対象に結果不明の操作があります。先に照合・復旧してください"
            }
            val base = withContext(Dispatchers.IO) {
                load(root).also { fresh ->
                    require(fresh.schemaErrors.isEmpty()) { "スキーマの読込エラーがあるため確定できません" }
                    checkTargets(grant, plan.prepared, fresh.layout)
                    require(fresh.storedState() == plan.before && fresh.readStoreState() == plan.before) { "確認後にデータや定義が変わりました。再計画してください" }
                    plan.action?.let { ActionPlanner.checkPreconditions(fresh, it) }
                    val overlays = plan.prepared.documents.associate { it.id to YDocument(it.id, listOf(YamlIO.parse(it.after))) }
                    require(ChangeSetValidation.validate(fresh, overlays).values.flatten().none { it.severity == Severity.ERROR }) { "検証エラーが残るため確定できません" }
                }
            }
            // IO の間に権限を失効させた場合も、書込開始前に止める。
            require(authorize(caller).second == grant) { "権限が変更されました" }
            checkSnapshot(plan.epoch, plan.revision)
            host.documents.checkExternalTargets(ids)
            withContext(Dispatchers.IO) { journal.write(record) }
            record = record.copy(state = OperationState.APPLYING)
            withContext(Dispatchers.IO) { journal.write(record) }
            // 記録の永続化にも IO の待ちがある。保存を始める直前の権限と編集状態をもう一度確認する。
            require(authorize(caller).second == grant) { "権限が変更されました" }
            checkSnapshot(plan.epoch, plan.revision)
            host.documents.checkExternalTargets(ids)
            started = true
            val result = withContext(Dispatchers.IO) {
                require(base.readStoreState() == plan.before) { "書込前にデータが変わりました" }
                ChangeSetApplier.applyPrepared(base, plan.prepared)
            }
            val changed = result.persistence.filterValues { it == ChangePersistence.WRITTEN || it == ChangePersistence.UNKNOWN }.keys
            // 保存が一部でも進んだら、失敗結果であっても実際の内容と履歴を同期する。
            host.documents.acceptExternalCommit(changed)
            record = record.copy(state = when {
                result.persistence.values.any { it == ChangePersistence.UNKNOWN } -> OperationState.UNKNOWN
                result.ok -> OperationState.COMMITTED
                result.persistence.values.any { it == ChangePersistence.WRITTEN } -> OperationState.PARTIAL
                else -> OperationState.REJECTED
            }, persistence = result.persistence.mapKeys { it.key.value }.mapValues { it.value.name },
                message = result.results.mapNotNull { it.message }.distinct().joinToString(" ／ ").ifEmpty { null })
            withContext(Dispatchers.IO) { journal.write(record) }
            record
        } catch (failure: Exception) {
            record = record.copy(state = if (started) OperationState.UNKNOWN else OperationState.REJECTED,
                message = failure.message ?: "操作に失敗しました")
            if (started) runCatching { host.documents.acceptExternalCommit(ids) }
            // 最終記録が失敗しても、永続化した APPLYING が再起動後の自動再実行を止める。
            withContext(Dispatchers.IO) { runCatching { journal.write(record) } }
            record
        } finally { plans.remove(planId) }
    }

    suspend fun result(caller: OperationCaller, requestId: String): OperationRecord? {
        val (journal, epoch) = host.access {
            val (root, _) = authorize(caller)
            journals(root, requireNotNull(it.core).layout) to host.currentStatus().epoch
        }
        val found = withContext(Dispatchers.IO) { readRecords(journal) }
            .firstOrNull { it.workspaceId == caller.workspaceId && it.callerId == caller.callerId && it.requestId == requestId }
        return host.access(epoch) {
            val (_, grant) = authorize(caller)
            found?.also { require(it.after.keys.all { file -> DocumentId(file) in grant.documents }) { "結果の対象文書は現在許可されていません" } }
        }
    }

    /** 明示的な復旧用。画面で確認した現在のハッシュとの一致を要求し、元の操作は再実行しない。 */
    suspend fun reconcile(caller: OperationCaller, requestId: String, observed: Map<String, String?>): OperationRecord = host.exclusive("操作結果の照合") {
        val (root, grant) = authorize(caller)
        require(grant.recover) { "結果の復旧は許可されていません" }
        val journal = journals(root, requireNotNull(host.documents.core).layout)
        val record = withContext(Dispatchers.IO) { readRecords(journal) }.first {
            it.workspaceId == caller.workspaceId && it.callerId == caller.callerId && it.requestId == requestId
        }
        require(record.state == OperationState.UNKNOWN) { "結果不明の操作ではありません" }
        require(record.after.keys.all { DocumentId(it) in grant.documents }) { "復旧の対象文書は現在許可されていません" }
        require(observed.keys == record.after.keys) { "全ての対象の確認が必要です" }
        withContext(Dispatchers.IO) {
            val store = FileSystemDocumentStore(root)
            require(observed.all { (file, hash) -> store.read(DocumentId(file))?.text?.let(ChangeSets::contentHash) == hash }) { "照合後に内容が変わりました" }
        }
        host.documents.acceptExternalCommit(observed.keys.map(::DocumentId).toSet())
        record.copy(state = OperationState.RECONCILED, message = "現在の内容を明示的に確認しました。元の要求は再実行しません").also {
            withContext(Dispatchers.IO) { journal.write(it) }
        }
    }

    private fun readRecords(journal: OperationJournal): List<OperationRecord> = journal.readAll().map { it.recovered() }

    private fun authorize(caller: OperationCaller): Pair<Path, OperationGrant> {
        require(caller.callerId.isNotBlank() && caller.callerId.length <= 200) { "接続者の識別子が不正です" }
        val root = requireNotNull(host.documents.workspace) { "ワークスペースが開かれていません" }
        require(caller.workspaceId == workspaceId(root)) { "ワークスペースが切り替わりました" }
        val grant = requireNotNull(grants(caller)) { "この接続者は許可されていません" }
        return root to grant.copy(documents = grant.documents.toSet(), actions = grant.actions.toSet())
    }

    private fun checkKind(grant: OperationGrant, action: ActionRequest?) {
        require(if (action == null) grant.applyChanges else action.name in grant.actions) { "この操作は許可されていません" }
    }

    private fun checkTargets(grant: OperationGrant, prepared: PreparedChangeSet, layout: WorkspaceLayout) {
        require(prepared.documents.all { it.id in grant.documents && layout.inLedger(it.id.value) }) { "許可していない文書・定義文書への変更です" }
    }

    private fun checkSnapshot(epoch: String, revision: Long) {
        val current = host.currentStatus()
        require(current.epoch == epoch && current.revision == revision) { "確認後に編集状態が変わりました。再計画してください" }
    }
}
