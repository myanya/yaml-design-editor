package dev.toros.yde.app.service

import dev.toros.yde.workspace.FileSystemDocumentStore
import dev.toros.yde.workspace.DocumentStore
import dev.toros.yde.workspace.StoredContent
import dev.toros.yde.workspace.WorkspaceReader
import dev.toros.yde.workspace.WorkspacePaths
import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore

enum class ReadResource { WORKSPACE, DOCUMENTS, DOCUMENT, SEARCH, ENTITY, VIEWS, VIEW, VALIDATION }

/** 通信方式に依存しない読み取り条件。通常の読取は保存済みの内容に限定する。 */
data class ReadQuery(
    val resource: ReadResource,
    val document: DocumentId? = null,
    val id: String? = null,
    val text: String? = null,
    val offset: Int = 0,
    val limit: Int = 50,
    val date: LocalDate? = null,
    val revision: String? = null,
) {
    internal fun check() {
        readRequire(offset in 0..10_000 && limit in 1..200, ReadFailureCode.INVALID_REQUEST, "取得範囲が不正です")
        readRequire(id == null || id.isNotBlank() && id.length <= 200, ReadFailureCode.INVALID_REQUEST, "ID は 1〜200 文字で指定してください")
        readRequire(text == null || text.isNotBlank() && text.length <= 500, ReadFailureCode.INVALID_REQUEST, "検索語は 1〜500 文字で指定してください")
        readRequire(revision == null || revision.matches(Regex("[a-f0-9]{64}")), ReadFailureCode.INVALID_REQUEST, "版の指定が不正です")
        document?.let {
            readRequire(it.value.length <= 1024 && runCatching {
                WorkspacePaths.relative(it.value).toString().replace('\\', '/') == it.value
            }.getOrDefault(false), ReadFailureCode.INVALID_REQUEST, "文書はワークスペース相対の識別子で指定してください")
        }
        readRequire(resource != ReadResource.DOCUMENT || document != null, ReadFailureCode.INVALID_REQUEST, "文書を指定してください")
        readRequire(resource !in setOf(ReadResource.ENTITY, ReadResource.VIEW) || id != null, ReadFailureCode.INVALID_REQUEST, "ID を指定してください")
        readRequire(resource != ReadResource.SEARCH || text != null, ReadFailureCode.INVALID_REQUEST, "検索語を指定してください")
    }
}

/** data は値だけの公開レポート。可変の木・セッション・絶対パスを外へ渡さない。 */
data class ReadResult(val workspaceId: String, val epoch: String, val revision: String, val data: Map<String, Any?>)

enum class ReadFailureCode { INVALID_REQUEST, FORBIDDEN, NOT_FOUND, BUSY, STALE_REVISION, CHANGED_DURING_READ, LIMIT_EXCEEDED, INVALID_DEFINITIONS, UNSUPPORTED_VIEW }
class ReadFailure(val code: ReadFailureCode, message: String) : RuntimeException(message)
internal fun readRequire(condition: Boolean, code: ReadFailureCode, message: String) {
    if (!condition) throw ReadFailure(code, message)
}

data class ReadLimits(val documents: Int = 10_000, val bytes: Int = 32 * 1024 * 1024, val timeoutMillis: Long = 15_000)

/**
 * 共通状態から持ち出すのは場所・世代・権限だけ。IO と既存の意味計算は独立した保存済みスナップショット上で行う。
 * 派生結果は全入力への閲覧権限を要求する。隠した入力を計算に含めてから結果だけを濾過しない。
 */
class WorkspaceReads(
    private val host: WorkspaceHost,
    private val grants: (OperationCaller) -> ConnectionGrant?,
    private val workspaceId: (Path) -> String = { ChangeSets.contentHash(it.toRealPath().toString()) },
    private val limits: ReadLimits = ReadLimits(),
    private val today: () -> LocalDate = LocalDate::now,
    /** IO と所有側の競合を決定的に検査するための差し込み口。 */
    internal val afterCapture: suspend () -> Unit = {},
) {
    private val permits = Semaphore(2)
    private data class Access(val root: Path, val epoch: String, val grant: ConnectionGrant)

    private suspend fun authorize(caller: OperationCaller): Access = withContext(host.owner) {
        val status = host.currentStatus()
        readRequire(!status.closed && status.workspace != null, ReadFailureCode.NOT_FOUND, "ワークスペースを開いてください")
        readRequire(status.busy == null, ReadFailureCode.BUSY, "ワークスペースは処理中です")
        val grant = grants(caller)
        readRequire(grant != null && grant.allows(ConnectionCapability.READ), ReadFailureCode.FORBIDDEN, "閲覧が許可されていません")
        Access(requireNotNull(status.workspace), status.epoch, requireNotNull(grant))
    }

    suspend fun read(caller: OperationCaller, query: ReadQuery): ReadResult {
        query.check()
        readRequire(permits.tryAcquire(), ReadFailureCode.BUSY, "読み取りが混み合っています。再試行してください")
        try {
            return withTimeout(limits.timeoutMillis) {
                val access = authorize(caller)
                query.document?.let {
                    readRequire(access.grant.permissions.canRead(it), ReadFailureCode.FORBIDDEN, "文書の閲覧が許可されていません")
                }
                val result = withContext(Dispatchers.IO) {
                    readRequire(workspaceId(access.root) == caller.workspaceId, ReadFailureCode.FORBIDDEN, "ワークスペースが一致しません")
                    val captured = capture(access.root)
                    afterCapture()
                    currentCoroutineContext().ensureActive()
                    val visible = captured.texts.filterKeys(access.grant.permissions::canRead)
                    val revision = ChangeSets.contentHash(visible.entries.joinToString("") { (id, text) ->
                        "${id.value.length}:${id.value}${text.length}:$text"
                    })
                    readRequire(query.revision == null || query.revision == revision, ReadFailureCode.STALE_REVISION, "保存済みの版が変わりました。最初から取得してください")
                    if (query.resource in setOf(ReadResource.ENTITY, ReadResource.VIEWS, ReadResource.VIEW, ReadResource.VALIDATION)) {
                        readRequire(captured.texts.keys.all(access.grant.permissions::canRead), ReadFailureCode.FORBIDDEN,
                            "派生結果に必要な文書の閲覧権限がありません")
                    }
                    val data = SavedReadReports(access.root, captured.texts, captured.layout).read(query, visible, today())
                    currentCoroutineContext().ensureActive()
                    readRequire(capture(access.root) == captured, ReadFailureCode.CHANGED_DURING_READ, "読み取り中に保存内容が変わりました。再試行してください")
                    ReadResult(caller.workspaceId, access.epoch, revision, data)
                }
                val current = authorize(caller)
                readRequire(current == access, ReadFailureCode.CHANGED_DURING_READ, "読み取り中にワークスペースまたは権限が変わりました")
                result
            }
        } catch (failure: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw ReadFailure(ReadFailureCode.BUSY, "読み取りが時間内に完了しませんでした。対象を絞って再試行してください")
        } finally { permits.release() }
    }

    /** 信頼するデスクトップの設定専用。外部の一覧とは別で、登録前にも保存済み文書を選べる。 */
    suspend fun documentsForSettings(): List<DocumentId> = withTimeout(limits.timeoutMillis) {
        val before = withContext(host.owner) { host.currentStatus() }
        require(!before.closed && before.workspace != null && before.busy == null) { "ワークスペースを開き、処理の完了を待ってください" }
        val documents = withContext(Dispatchers.IO) { capture(requireNotNull(before.workspace)).texts.keys.toList() }
        val after = withContext(host.owner) { host.currentStatus() }
        require(!after.closed && after.workspace == before.workspace && after.epoch == before.epoch && after.busy == null) {
            "ワークスペースが変わりました。文書一覧を再読込してください"
        }
        documents
    }

    private data class Capture(val texts: Map<DocumentId, String>, val layout: WorkspaceLayout)

    private suspend fun capture(root: Path): Capture {
        // 定義の解析より先に本文量を制限する。readNBytes で走査後のファイル増大も制限する。
        val store = FileSystemDocumentStore(root)
        val listed = store.list()
        var remaining = limits.bytes
        val texts = linkedMapOf<DocumentId, String>()
        suspend fun readText(id: DocumentId) {
            currentCoroutineContext().ensureActive()
            val bytes = Files.newInputStream(WorkspacePaths.resolve(root, id.value)).use { it.readNBytes(remaining + 1) }
            readRequire(bytes.size <= remaining, ReadFailureCode.LIMIT_EXCEEDED, "文書の合計サイズが読み取り上限を超えています")
            remaining -= bytes.size
            texts[id] = bytes.decodeToString(throwOnInvalidSequence = true)
        }
        val config = DocumentId("${WorkspaceLayout.CONFIG_DIR}/${WorkspaceLayout.CONFIG_FILE}")
        if (listed.any { it.id == config }) readText(config)
        // 配置だけを先にカーネルで解釈する。対象外の実行記録の量や内容で業務の読取を妨げない。
        val layout = WorkspaceReader.detectLayout(object : DocumentStore by store {
            override fun list() = listed
            override fun read(id: DocumentId): StoredContent? = texts[id]?.let(::StoredContent)
        })
        val ids = listed.map { it.id }.filter { layout.zoneOf(it.value) != WorkspaceLayout.Zone.RUNS }
        readRequire(ids.size <= limits.documents, ReadFailureCode.LIMIT_EXCEEDED, "文書数が読み取り上限を超えています")
        for (id in ids) if (id !in texts) readText(id)
        // config の先読みで挿入順が変わっても版は同じ順序から求める。
        return Capture(texts.toSortedMap(compareBy { it.value }), layout)
    }
}
