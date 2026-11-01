package dev.toros.yde.app.service

import dev.toros.yde.app.core.Workspace
import dev.toros.yde.app.tools.WorkingCopy
import dev.toros.yde.app.tools.SubmissionChecks
import dev.toros.yde.workspace.ChangeSetApplier
import dev.toros.yde.workspace.PreparedChangeSet
import dev.toros.yde.workspace.PreparedDocumentChange
import dev.toros.yde.workspace.WorkspacePaths
import dev.toros.yde.workspace.changeset.ChangeSet
import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.workspace.validate.CheckIssue
import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 提案と直接確定は別々の供給口で受ける。指定文書の空集合は許可なし。 */
data class WorkGrant(val revision: Long, val documents: Set<DocumentId>,
                     val scope: ConnectionWriteScope = ConnectionWriteScope.SELECTED,
                     val readPermission: ConnectionPermissions? = null)
data class WorkValidation(val issues: Map<DocumentId, List<CheckIssue>>, val failures: Map<String, String>, val valid: Boolean,
                          val documents: List<PreparedDocumentChange> = emptyList())

/**
 * 通信接続から独立した外部作業。未提出・未確定はメモリ、提出・確定後は不変の変更セットと結果を保管する。
 * review/approve/reject/refresh は信頼するデスクトップ専用。外部入口は caller を取るメソッドだけを公開する。
 * 承認で確定権限を一時的に作るが、元の接続者の現在の提案権限も確定直前まで再確認する。
 */
class WorkspaceWork(
    private val host: WorkspaceHost,
    private val grants: (OperationCaller) -> WorkGrant? = { null },
    private val workspaceId: (Path) -> String = { ChangeSets.contentHash(it.toRealPath().toString()) },
    private val journals: (Path, WorkspaceLayout) -> WorkJournal = { root, layout -> FileWorkJournal(root, layout) },
    private val operationJournals: (Path, WorkspaceLayout) -> OperationJournal = { root, layout -> FileOperationJournal(root, layout) },
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val directGrants: (OperationCaller) -> WorkGrant? = { null },
) {
    private data class Draft(val record: WorkRecord, val copy: WorkingCopy, val grant: WorkGrant, val before: Map<DocumentId, String>)
    private data class Review(val work: WorkRecord, val preview: OperationPreview, val grant: WorkGrant)
    private data class ApprovalPermission(val caller: OperationCaller, val grant: WorkGrant, val mode: WorkMode)
    private val lock = Mutex()
    private var epoch: String? = null
    private val drafts = linkedMapOf<String, Draft>()
    private val reviews = linkedMapOf<String, Review>()
    private val mutableInbox = MutableStateFlow<List<WorkRecord>>(emptyList())
    /** デスクトップ用の射影。外部への一覧は caller によって絞る。 */
    val inbox = mutableInbox.asStateFlow()
    private var authorization: ApprovalPermission? = null
    private val operations = WorkspaceOperations(host, grants = { caller ->
        authorization?.takeIf { it.caller == caller }?.let { review ->
            grantFor(caller, review.mode)?.takeIf { it == review.grant }?.let { grant ->
                OperationGrant(grant.revision, grant.documents, applyChanges = true, scope = grant.scope, readPermission = grant.readPermission)
            }
        }
    }, workspaceId = workspaceId, journals = operationJournals, clock = clock, zone = zone)

    private suspend fun <T> serial(block: suspend () -> T): T = withContext(host.owner) { lock.withLock { block() } }

    private fun root(): Path {
        val current = host.currentStatus()
        if (epoch != current.epoch) {
            epoch = current.epoch; drafts.clear(); reviews.clear(); mutableInbox.value = emptyList()
        }
        return requireNotNull(current.workspace) { "ワークスペースが開かれていません" }
    }

    private fun grantFor(caller: OperationCaller, mode: WorkMode) = if (mode == WorkMode.DIRECT) directGrants(caller) else grants(caller)

    private fun authorize(caller: OperationCaller, mode: WorkMode = WorkMode.PROPOSAL): WorkGrant {
        require(caller.callerId.isNotBlank() && caller.callerId.length <= 200) { "接続者の識別子が不正です" }
        require(caller.workspaceId == workspaceId(root())) { "ワークスペースが切り替わりました" }
        return requireNotNull(grantFor(caller, mode)) { "この作業方式は許可されていません" }.let { it.copy(documents = it.documents.toSet()) }
    }

    private fun targets(grant: WorkGrant, changes: ChangeSet, layout: WorkspaceLayout) {
        require(changes.changes.all { c ->
            val id = DocumentId(WorkspacePaths.relative(c.file).toString().replace('\\', '/'))
            id.value == c.file && allowsWrite(grant.documents, grant.scope, id, layout)
        }) { "許可していない文書・定義文書への提案です" }
    }

    private fun draft(caller: OperationCaller, id: String): Draft {
        val draft = drafts[id] ?: error("未提出の作業がありません。再起動後は新しく開始してください")
        val grant = authorize(caller, draft.record.mode)
        require(draft.record.caller == caller) { "別の接続者の作業です" }
        require(draft.record.state in setOf(WorkState.WORKING, WorkState.VALIDATED)) { "編集できる作業ではありません" }
        require(grant == draft.grant) { "権限が変更されました。作業を新しく開始してください" }
        return draft
    }

    suspend fun begin(caller: OperationCaller, title: String, mode: WorkMode = WorkMode.PROPOSAL): WorkRecord = serial { host.exclusive("外部作業の開始") {
        val grant = authorize(caller, mode)
        require(title.isNotBlank() && title.length <= 200) { "作業名は 1〜200 文字で指定してください" }
        require(grant.scope == ConnectionWriteScope.LEDGER || grant.documents.isNotEmpty()) { "変更を許可した文書がありません" }
        if (drafts.size >= 64) drafts.entries.removeIf { it.value.record.state == WorkState.DISCARDED }
        require(drafts.size < 64) { "未提出の作業が多すぎます。不要な作業を破棄してください" }
        val root = root()
        val (copy, before) = withContext(Dispatchers.IO) {
            val ws = Workspace.open(root)
            require(ws.mirror.schemaErrors.isEmpty()) { "スキーマの読込エラーがあります" }
            val before = ws.mirror.storedState()
            checkReadInputs(grant.readPermission, before.keys)
            require(before == ws.mirror.readStoreState()) { "読込中に変更されました。再開始してください" }
            WorkingCopy(ws) to before
        }
        require(authorize(caller, mode) == grant) { "権限が変更されました" }
        val record = WorkRecord(UUID.randomUUID().toString(), caller, title, WorkState.WORKING, clock().toString(), mode = mode)
        drafts[record.id] = Draft(record, copy, grant, before)
        record
    } }

    /** 一回の編集要求は全体を採用するか破棄する。失敗した途中のパッチを次の提出へ混ぜない。 */
    suspend fun stage(caller: OperationCaller, id: String, changes: ChangeSet): WorkRecord {
        val frozen = ChangeSets.copyOf(changes)
        return serial { host.exclusive("外部作業の編集") {
            val draft = draft(caller, id)
            targets(draft.grant, frozen, draft.copy.ws.layout)
            val next = withContext(Dispatchers.IO) {
                checkBase(draft)
                checkExternalWritePaths(draft.copy.ws.root, frozen.changes.map { DocumentId(it.file) }, draft.copy.ws.layout)
                val next = WorkingCopy(draft.copy.ws)
                fun apply(changes: ChangeSet) { changes.changes.forEach { c ->
                    if (c.create) next.createDocument(c.file, requireNotNull(c.content) { "新規文書の内容が必要です" })
                    else {
                        require(DocumentId(c.file) in draft.before || next.touchedFiles().any { entry -> entry.rel == c.file }) {
                            "保存済み文書または作業内で作成した文書を指定してください"
                        }
                        require(c.baseHash == null || draft.before[DocumentId(c.file)]?.let(ChangeSets::contentHash) == c.baseHash) { "変更の前提が一致しません" }
                        next.applyOps(c.file, c.ops)
                    }
                } }
                apply(ChangeSets.copyOf(draft.copy.toChangeSet())); apply(frozen)
                checkBase(draft)
                require(WorkChanges.encode(next.toChangeSet()).toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "作業内容が大きすぎます（上限 2 MiB）" }
                next
            }
            require(authorize(caller, draft.record.mode) == draft.grant) { "権限が変更されました" }
            val record = draft.record.copy(state = WorkState.WORKING, documents = emptyList())
            reviews.remove(id)
            drafts[id] = draft.copy(record = record, copy = next)
            record
        } }
    }

    suspend fun validate(caller: OperationCaller, id: String): WorkValidation = serial { host.exclusive("外部作業の検証") {
        val draft = draft(caller, id)
        val prepared = prepare(draft)
        require(authorize(caller, draft.record.mode) == draft.grant) { "権限が変更されました" }
        reviews.remove(id)
        drafts[id] = draft.copy(record = draft.record.copy(state = if (prepared.valid) WorkState.VALIDATED else WorkState.WORKING,
            documents = prepared.documents))
        WorkValidation(prepared.issues, prepared.failures, prepared.valid, prepared.documents)
    } }

    /** 再送は既存の提出を返す。提出後は変更できず、修正は新しい作業として開始する。 */
    suspend fun submit(caller: OperationCaller, id: String): WorkRecord = serial { host.exclusive("外部作業の提出") {
        authorize(caller)
        readRecords().firstOrNull { it.id == id }?.let {
            require(it.mode == WorkMode.PROPOSAL) { "直接確定の作業は承認待ちへ提出できません" }
            return@exclusive owned(caller, it)
        }
        val draft = draft(caller, id)
        require(draft.record.mode == WorkMode.PROPOSAL) { "直接確定の作業は承認待ちへ提出できません" }
        require(!draft.copy.isEmpty()) { "提出する変更がありません" }
        val prepared = prepare(draft)
        require(prepared.failures.isEmpty()) { prepared.failures.values.joinToString(" ／ ") }
        SubmissionChecks.rejection(prepared.issues.mapKeys { it.key.value })?.let { error(it) }
        require(authorize(caller) == draft.grant) { "権限が変更されました" }
        val record = draft.record.copy(state = WorkState.AWAITING_APPROVAL, submittedAt = clock().toString(),
            changes = WorkChanges.encode(draft.copy.toChangeSet()), documents = prepared.documents)
        persist(record)
        drafts.remove(id)
        record
    } }

    suspend fun discard(caller: OperationCaller, id: String): WorkRecord = serial { host.exclusive("外部作業の破棄") {
        val submitted = readRecords().firstOrNull { it.id == id }
        val record = owned(caller, submitted ?: drafts[id]?.record ?: error("作業 ID が見つかりません"))
        if (record.state == WorkState.DISCARDED) return@exclusive record
        require(record.state in setOf(WorkState.WORKING, WorkState.VALIDATED, WorkState.AWAITING_APPROVAL)) { "この作業は破棄できません" }
        record.copy(state = WorkState.DISCARDED).also {
            if (submitted != null) persist(it) else drafts[id] = drafts.getValue(id).copy(record = it)
            reviews.remove(id)
        }
    } }

    suspend fun result(caller: OperationCaller, id: String): WorkRecord = serial {
        host.access {
            require(caller.workspaceId == workspaceId(root())) { "ワークスペースが切り替わりました" }
            require(grants(caller) != null || directGrants(caller) != null) { "作業の参照が許可されていません" }
        }
        val records = readOnlyRecords()
        host.access { owned(caller, records.firstOrNull { it.id == id } ?: drafts[id]?.record ?: error("作業 ID が見つかりません")) }
    }

    /** 再起動で承認を再開しない。保存済みの要求記録から結果だけを復元する。 */
    suspend fun refresh(): List<WorkRecord> = serial { readOnlyRecords().filter { it.mode == WorkMode.PROPOSAL } }

    private suspend fun readOnlyRecords(): List<WorkRecord> {
        val (root, layout, generation) = host.access {
            Triple(root(), requireNotNull(it.core).layout, host.currentStatus().epoch)
        }
        val records = loadRecords(root, layout)
        return host.access(generation) { mutableInbox.value = records.filter { it.mode == WorkMode.PROPOSAL }; records }
    }

    /** 人間が確認する確定時の差分。自動値もここで凍結し、これを見た後に approve する。 */
    suspend fun review(id: String): OperationPreview = serial {
        val (record, grant) = host.exclusive("提出の確認") {
            val record = readRecords().firstOrNull { it.id == id } ?: error("提出が見つかりません")
            require(record.mode == WorkMode.PROPOSAL && record.state == WorkState.AWAITING_APPROVAL) { "承認待ちの提出ではありません" }
            record to authorize(record.caller)
        }
        authorization = ApprovalPermission(record.caller, grant, WorkMode.PROPOSAL)
        try {
            val preview = operations.planChanges(record.caller, WorkChanges.decode(requireNotNull(record.changes)))
            reviews[id] = Review(record, preview, grant)
            preview
        } finally { authorization = null }
    }

    /** デスクトップ専用。提出 ID と直前に確認した計画 ID の両方を照合する。 */
    suspend fun approve(id: String, planId: String): WorkRecord = serial { withContext(NonCancellable) {
        val review = host.exclusive("承認の受付") {
            val review = reviews[id] ?: error("先に差分を再確認してください")
            require(review.work.mode == WorkMode.PROPOSAL) { "直接確定の作業は受信箱で承認できません" }
            require(review.preview.id == planId && review.preview.canCommit) { "確定できる計画ではありません" }
            require(authorize(review.work.caller) == review.grant) { "権限が変更されました。再確認してください" }
            val current = readRecords().first { it.id == id }
            require(current == review.work) { "提出の状態が変わりました。再確認してください" }
            val intent = current.copy(state = WorkState.APPLYING, requestId = "work/$id", planId = planId,
                documents = review.preview.documents)
            persist(intent)
            review.copy(work = intent)
        }
        applyWork(review.work, review.grant)
    } }

    /** 外部から保存できるのは直接確定として開始した作業だけ。提案の承認窓口には接続しない。 */
    suspend fun planCommit(caller: OperationCaller, id: String): OperationPreview = serial {
        val draft = host.access { draft(caller, id).also {
            require(it.record.mode == WorkMode.DIRECT) { "提案の作業はデスクトップで承認してください" }
            require(!it.copy.isEmpty()) { "確定する変更がありません" }
        } }
        withContext(Dispatchers.IO) { checkBase(draft) }
        authorization = ApprovalPermission(caller, draft.grant, WorkMode.DIRECT)
        try {
            val preview = operations.planChanges(caller, draft.copy.toChangeSet())
            withContext(Dispatchers.IO) { checkBase(draft) }
            host.access {
                require(authorize(caller, WorkMode.DIRECT) == draft.grant) { "権限が変更されました" }
                reviews[id] = Review(draft.record, preview, draft.grant)
            }
            preview
        } finally { authorization = null }
    }

    /** 作業 ID が保存要求の識別子になる。同じ作業の再送では保存を繰り返さない。 */
    suspend fun commit(caller: OperationCaller, id: String, planId: String): WorkRecord = serial { withContext(NonCancellable) {
        val pending = host.exclusive("直接確定の受付") {
            authorize(caller, WorkMode.DIRECT)
            readRecords().firstOrNull { it.id == id }?.let { existing ->
                owned(caller, existing)
                require(existing.mode == WorkMode.DIRECT && existing.planId == planId && existing.requestId == "work/$id") {
                    "同じ作業へ異なる確定を再送できません"
                }
                return@exclusive existing to null
            }
            val draft = draft(caller, id)
            require(draft.record.mode == WorkMode.DIRECT) { "提案の作業はデスクトップで承認してください" }
            val review = requireNotNull(reviews[id]) { "先に確定時の差分を取得してください" }
            require(review.preview.id == planId && review.preview.canCommit && review.work == draft.record && review.grant == draft.grant) {
                "確定できる計画ではありません。差分を再取得してください"
            }
            val intent = draft.record.copy(state = WorkState.APPLYING, changes = WorkChanges.encode(draft.copy.toChangeSet()),
                documents = review.preview.documents, requestId = "work/$id", planId = planId)
            persist(intent)
            drafts.remove(id)
            intent to draft.grant
        }
        pending.second?.let { applyWork(pending.first, it) } ?: pending.first
    } }

    private suspend fun applyWork(record: WorkRecord, grant: WorkGrant): WorkRecord {
        authorization = ApprovalPermission(record.caller, grant, record.mode)
        try {
            val result = try { operations.commit(record.caller, requireNotNull(record.planId), requireNotNull(record.requestId)) }
            catch (failure: Exception) {
                return host.exclusive("確定の中断記録") {
                    val unknown = record.copy(state = WorkState.UNKNOWN, message = "確定結果を再読込してください: ${failure.message}")
                    runCatching { persist(unknown) }
                    publish(unknown)
                    unknown
                }
            }
            return host.exclusive("確定結果の記録") {
                val completed = fromOperation(record, result)
                try { persist(completed); completed }
                catch (failure: Exception) {
                    // 要求記録が正。提出記録だけが古い場合も、次の読込で結果を照合する。
                    val unknown = record.copy(state = WorkState.UNKNOWN, message = "結果の記録を再読込してください: ${failure.message}")
                    publish(unknown); unknown
                }
            }
        } finally { authorization = null; reviews.remove(record.id) }
    }

    suspend fun reject(id: String): WorkRecord = serial { host.exclusive("提出の却下") {
        val record = readRecords().firstOrNull { it.id == id } ?: error("提出が見つかりません")
        require(record.state == WorkState.AWAITING_APPROVAL) { "承認待ちの提出ではありません" }
        record.copy(state = WorkState.REJECTED).also { persist(it); reviews.remove(id) }
    } }

    private fun checkBase(draft: Draft) {
        require(draft.copy.ws.mirror.readStoreState() == draft.before) { "作業開始後にデータや定義が変わりました。新しく開始してください" }
    }

    private suspend fun prepare(draft: Draft): PreparedChangeSet {
        require(draft.record.state in setOf(WorkState.WORKING, WorkState.VALIDATED)) { "編集できる作業ではありません" }
        val now = LocalDateTime.ofInstant(clock(), zone)
        return withContext(Dispatchers.IO) {
            checkBase(draft)
            checkExternalWritePaths(draft.copy.ws.root, draft.copy.touchedFiles().map { DocumentId(it.rel) }, draft.copy.ws.layout)
            ChangeSetApplier.prepare(draft.copy.ws.mirror, draft.copy.toChangeSet(), now).also { checkBase(draft) }
        }
    }

    private fun owned(caller: OperationCaller, record: WorkRecord): WorkRecord {
        val grant = authorize(caller, record.mode)
        require(record.caller == caller) { "別の接続者の作業です" }
        require(record.documents.all { allowsWrite(grant.documents, grant.scope, it.id, requireNotNull(host.documents.core).layout) }) {
            "作業の対象文書は現在許可されていません"
        }
        return record
    }

    private suspend fun readRecords(): List<WorkRecord> {
        val root = root()
        val layout = requireNotNull(host.documents.core).layout
        return loadRecords(root, layout).also { mutableInbox.value = it.filter { record -> record.mode == WorkMode.PROPOSAL } }
    }

    private suspend fun loadRecords(root: Path, layout: WorkspaceLayout): List<WorkRecord> {
        val currentId = workspaceId(root)
        return withContext(Dispatchers.IO) {
            val works = journals(root, layout).readAll().filter { it.caller.workspaceId == currentId }
            require(works.map { it.id }.distinct().size == works.size) { "提出記録の作業 ID が重複しています" }
            val results = if (works.any { it.requestId != null }) operationJournals(root, layout).readAll() else emptyList()
            works.map { work ->
                if (work.requestId == null) work else {
                    val result = results.firstOrNull { it.workspaceId == work.caller.workspaceId && it.callerId == work.caller.callerId && it.requestId == work.requestId }
                    if (result == null) work.copy(state = WorkState.UNKNOWN, message = "確定の要求記録が見つかりません。手動で内容を照合してください")
                    else fromOperation(work, result.recovered())
                }
            }
        }
    }

    private suspend fun persist(record: WorkRecord) {
        val journal = journals(root(), requireNotNull(host.documents.core).layout)
        withContext(Dispatchers.IO) { journal.write(record) }
        publish(record)
    }

    private fun publish(record: WorkRecord) {
        mutableInbox.value = mutableInbox.value.filterNot { it.id == record.id } + listOf(record).filter { it.mode == WorkMode.PROPOSAL }
    }

    private fun fromOperation(work: WorkRecord, result: OperationRecord): WorkRecord {
        require(result.planId == work.planId) { "提出と確定計画の識別子が一致しません" }
        return work.copy(state = when (result.state) {
            OperationState.COMMITTED -> WorkState.APPLIED
            OperationState.REJECTED -> WorkState.FAILED
            OperationState.PARTIAL -> WorkState.PARTIAL
            OperationState.RECONCILED -> WorkState.RECONCILED
            else -> WorkState.UNKNOWN
        }, message = result.message, persistence = result.persistence)
    }
}
