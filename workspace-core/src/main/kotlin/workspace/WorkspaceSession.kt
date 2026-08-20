package workspace

import edit.EditBatch
import edit.YamlEdit
import edit.YamlOperation
import model.DocumentId
import model.YDocument
import model.deepCopy
import yaml.DefaultYamlCodec
import java.time.Instant

/**
 * ワークスペースのworking状態とトランザクション（3層コア移行 Phase 8・WS-004。計画書§4.3）。
 * - `apply` は working snapshot だけを更新し、ファイルへの書込は `save` だけが行う（ADR-0005。D-01）
 * - 変更は「対象文書の deep copy → 適用 → 検証 → 交換」（copy-on-write。ADR-0003）。
 *   途中失敗・検証拒否は候補を捨てるだけで、working 状態に触れない
 * - Undo/Redo・dirty・履歴はこのクラスだけが所有する（ADR-0006。D-07）。
 *   dirty 判定は保存済みテキストと現在の dump 結果の比較（ADR-0007）
 * - バージョンは単一の連番。apply / undo / redo / reload のたびに +1（ADR-0007）
 * v1 の既知の未対応: MoveDocument（参照更新＝DocMove の移行と併せて後続）・外部変更の検出（watch）。
 */

/** ワークスペースへの操作1件。 */
sealed interface WorkspaceOperation {
    /** 1文書への型付き編集（先頭ドキュメントが対象。R8-05）。 */
    data class EditDocument(val documentId: DocumentId, val operations: List<YamlOperation>) : WorkspaceOperation

    /**
     * 文書の新規作成。persistedText は undo（削除の巻き戻し）専用で、
     * 新規作成では null のまま使う（保存済みリンクの復元に使う内部情報）。
     */
    data class CreateDocument(
        val documentId: DocumentId,
        val document: YDocument,
        val persistedText: String? = null,
    ) : WorkspaceOperation

    /** 文書の削除（ストアからの削除は save 時。それまでは削除予約）。 */
    data class DeleteDocument(val documentId: DocumentId) : WorkspaceOperation

    /**
     * 文書の移動。スキーマ割当・台帳判定は移動先で再評価する。
     * ストア上は save 時に「新パスへ書き、旧パスを削除」する（参照の書き換えは Phase 9 の DocMove 系）。
     * toPersistedText は undo（移動の巻き戻し）専用で、保存済みリンクの復元に使う内部情報。
     */
    data class MoveDocument(
        val from: DocumentId,
        val to: DocumentId,
        val toPersistedText: String? = null,
    ) : WorkspaceOperation
}

/** 変更の意図（履歴・表示用の1行）。 */
data class ChangeIntent(val description: String = "")

/** 複数文書にまたがる原子的な変更。baseVersion が現在の version と一致するときだけ適用される。 */
data class WorkspaceTransaction(
    val baseVersion: Long,
    val intent: ChangeIntent,
    val operations: List<WorkspaceOperation>,
)

/** 意味のある変更履歴の1件（計画書§5）。保存は履歴項目ではない。 */
data class ChangeRecord(
    val id: Long,
    val intent: ChangeIntent,
    val beforeVersion: Long,
    val afterVersion: Long,
    val forward: WorkspaceTransaction,
    val inverse: WorkspaceTransaction,
    val occurredAt: Instant,
)

/** apply / undo / redo の結果。 */
data class TransactionResult(
    val ok: Boolean,
    val message: String? = null,
    val version: Long,
)

/** save の結果。一部失敗時も成功済みを明示する（計画書 Phase 8 保存の安全性）。 */
data class SaveResult(
    val written: List<DocumentId>,
    val deleted: List<DocumentId>,
    val failures: Map<DocumentId, String>,
) {
    val ok: Boolean get() = failures.isEmpty()
}

class WorkspaceSession(
    private val store: DocumentStore,
    /** 時刻は注入し、テストで固定する（計画書§5）。 */
    private val clock: () -> Instant = { Instant.now() },
) {
    val layout: schema.WorkspaceLayout
    val schemas: List<WorkspaceSchema>
    private val declarations: semantic.SemanticDeclarations

    private var workingDocuments: MutableMap<DocumentId, WorkspaceDocument>

    /** 読取・保存時点のストア生テキスト（外部変更検出用。dirty 判定＝persistedText とは別の基準）。 */
    private val storeTexts = mutableMapOf<DocumentId, String>()

    /** 削除予約（apply 済み・save でストアから消す）。 */
    private val pendingDeletes = mutableSetOf<DocumentId>()

    var version: Long = 0
        private set

    var derived: WorkspaceDerivedState
        private set

    private val undoStack = ArrayDeque<ChangeRecord>()
    private val redoStack = ArrayDeque<ChangeRecord>()
    private var recordCounter = 0L

    init {
        val loaded = WorkspaceReader.load(store, version = 0)
        layout = loaded.layout
        schemas = loaded.schemas
        declarations = loaded.declarations
        workingDocuments = LinkedHashMap(loaded.snapshot.documents)
        derived = loaded.derived
        refreshStoreTexts()
    }

    /** ストア生テキストの追跡を読み直す（外部変更検出の基準を今のストア内容に合わせる）。 */
    private fun refreshStoreTexts() {
        storeTexts.clear()
        for (id in workingDocuments.keys) {
            store.read(id)?.let { storeTexts[id] = it.text }
        }
    }

    /** working snapshot（読み取り専用ビュー）。 */
    val snapshot: WorkspaceSnapshot get() = WorkspaceSnapshot(version, workingDocuments.toMap())

    /** 変更履歴（古い順）。 */
    val history: List<ChangeRecord> get() = undoStack.toList()

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    /** dirty か（保存済みテキストと現在の dump 結果の比較。ADR-0007）。 */
    fun isDirty(id: DocumentId): Boolean {
        val d = workingDocuments[id] ?: return false
        return d.persistedText != DefaultYamlCodec.dump(d.document)
    }

    /** dirty な文書と削除予約の一覧。 */
    fun dirtyDocuments(): Set<DocumentId> =
        workingDocuments.keys.filterTo(linkedSetOf()) { isDirty(it) } + pendingDeletes

    /**
     * トランザクションを適用する（working snapshot のみ更新。ストアには書かない）。
     * requireValid = true のときは、候補の検証で structural / semantic の ERROR があれば適用しない。
     */
    fun apply(tx: WorkspaceTransaction, requireValid: Boolean = false): TransactionResult {
        if (tx.baseVersion != version) {
            return TransactionResult(false, "baseVersion が古いため適用できません（現在 $version・指定 ${tx.baseVersion}）", version)
        }
        return applyOperations(tx.operations, requireValid) { inverseOps ->
            val record = ChangeRecord(
                id = ++recordCounter,
                intent = tx.intent,
                beforeVersion = version - 1,
                afterVersion = version,
                forward = tx,
                inverse = WorkspaceTransaction(version, tx.intent, inverseOps),
                occurredAt = clock(),
            )
            undoStack.addLast(record)
            redoStack.clear()
        }
    }

    /** 1手戻す（履歴の逆トランザクションを適用する）。 */
    fun undo(): TransactionResult {
        val record = undoStack.removeLastOrNull()
            ?: return TransactionResult(false, "戻す変更がありません", version)
        val result = applyOperations(record.inverse.operations, requireValid = false) { }
        if (result.ok) redoStack.addLast(record) else undoStack.addLast(record)
        return result
    }

    /** 1手進める（forward トランザクションを再適用する）。 */
    fun redo(): TransactionResult {
        val record = redoStack.removeLastOrNull()
            ?: return TransactionResult(false, "やり直す変更がありません", version)
        val result = applyOperations(record.forward.operations, requireValid = false) { }
        if (result.ok) undoStack.addLast(record) else redoStack.addLast(record)
        return result
    }

    /**
     * dirty な文書と削除予約をストアへ書く。履歴・version には触れない（保存は履歴項目ではない。§5）。
     * selection を渡すとその文書だけ保存する。
     */
    fun save(selection: Set<DocumentId>? = null): SaveResult {
        val written = mutableListOf<DocumentId>()
        val deleted = mutableListOf<DocumentId>()
        val failures = linkedMapOf<DocumentId, String>()
        for (id in pendingDeletes.toList()) {
            if (selection != null && id !in selection) continue
            runCatching { store.delete(id) }
                .onSuccess {
                    pendingDeletes.remove(id)
                    storeTexts.remove(id)
                    deleted += id
                }
                .onFailure { failures[id] = it.message ?: it.toString() }
        }
        for ((id, d) in workingDocuments) {
            if (selection != null && id !in selection) continue
            val text = DefaultYamlCodec.dump(d.document)
            if (text == d.persistedText) continue
            runCatching { store.write(id, text) }
                .onSuccess {
                    workingDocuments[id] = d.copy(persistedText = text)
                    storeTexts[id] = text
                    written += id
                }
                .onFailure { failures[id] = it.message ?: it.toString() }
        }
        return SaveResult(written, deleted, failures)
    }

    // ---------------------------------------------------------------- リファクタリング（Phase 9: plan は semantic-core・apply はここ）

    /** スキーマの意味束（リファクタリング計画などカーネルAPIの入力）。 */
    private val schemaSemantics: Map<String, semantic.SchemaSemantics> by lazy {
        schemas.associate {
            it.id to semantic.SchemaSemantics(
                id = it.id,
                structural = it.structural,
                entities = it.entities,
                source = if (it.isTemplate) null else it.source,
                kind = it.kind,
            )
        }
    }

    /** リファクタリング対象の文書一覧（スキーマ割当済みの working 文書）。 */
    private fun refactorDocuments(): List<semantic.RefactorDocument> =
        workingDocuments.mapNotNull { (id, d) ->
            d.document.documents.firstOrNull()?.let { semantic.RefactorDocument(id, d.schemaId, it) }
        }

    /**
     * リファクタリング計画を生成する（workspace は変化しない）。
     * 計画の sourceVersion は現在の version で、適用（applyRefactoring）時に古い計画は拒否される。
     */
    fun planRefactoring(request: semantic.RefactoringRequest): semantic.RefactoringPlan {
        // 文書移動の前提検査と、スキーマ適用の変化警告（DocMove.plan と同じ内容）は working 状態を知るここで行う
        if (request is semantic.RefactoringRequest.MoveDocument) {
            require(request.from in workingDocuments) { "ファイルが存在しません: ${request.from.value}" }
            require(request.to !in workingDocuments) { "移動先に同名のファイルが存在します: ${request.to.value}" }
            val plan = semantic.SemanticRefactor.plan(derived.semantic, schemaSemantics, request) { id ->
                workingDocuments[id]?.document?.documents?.firstOrNull()
            }
            val oldSchema = schemas.firstOrNull { it.matches(request.from.value) }
            val newSchema = schemas.firstOrNull { it.matches(request.to.value) }
            val warning = if (oldSchema?.id != newSchema?.id) {
                listOf("スキーマの適用が変わります: ${oldSchema?.id ?: "（なし）"} → ${newSchema?.id ?: "（なし）"}")
            } else emptyList()
            return plan.copy(warnings = plan.warnings + warning)
        }
        return semantic.SemanticRefactor.plan(derived.semantic, schemaSemantics, request, refactorDocuments()) { id ->
            workingDocuments[id]?.document?.documents?.firstOrNull()
        }
    }

    /**
     * リファクタリング計画を1つのトランザクションとして適用する（1回の Undo で全変更が戻る）。
     * 物理移動（moves）は参照書き換え（changes）より先に適用する。
     */
    fun applyRefactoring(plan: semantic.RefactoringPlan, requireValid: Boolean = false): TransactionResult =
        apply(
            WorkspaceTransaction(
                baseVersion = plan.sourceVersion,
                intent = ChangeIntent(plan.summary.firstOrNull() ?: "リファクタリング"),
                operations = plan.moves.map { (from, to) -> WorkspaceOperation.MoveDocument(from, to) } +
                    plan.changes.map { WorkspaceOperation.EditDocument(it.document, it.operations) },
            ),
            requireValid = requireValid,
        )

    /**
     * ストア側の外部変更を検出する（保存済みテキストとストア内容の比較。ADR-0007: 検出・通知・reload まで。
     * イベント駆動の watch＝FileWatcher の移行は計画書 Phase 7 のとおり最終段階）。
     * 変更・削除された既知の文書を返す（新規ファイルは reload で取り込まれる）。
     */
    fun externalChanges(): List<DocumentId> =
        workingDocuments.keys.filter { id ->
            val known = storeTexts[id]
            known != null && store.read(id)?.text != known
        }

    /** ストアから読み直す（外部変更の取り込み。履歴は破棄される）。 */
    fun reload(): TransactionResult {
        val loaded = WorkspaceReader.load(store, version = version + 1)
        workingDocuments = LinkedHashMap(loaded.snapshot.documents)
        pendingDeletes.clear()
        undoStack.clear()
        redoStack.clear()
        version += 1
        derived = loaded.derived
        refreshStoreTexts()
        return TransactionResult(true, null, version)
    }

    // ---------------------------------------------------------------- 適用の本体（CoW）

    private fun applyOperations(
        operations: List<WorkspaceOperation>,
        requireValid: Boolean,
        onCommit: (inverseOps: List<WorkspaceOperation>) -> Unit,
    ): TransactionResult {
        // 候補の構築: 対象文書だけを deep copy し、成功時に参照を交換する（ADR-0003）
        val candidate = LinkedHashMap(workingDocuments)
        val deleteReservations = mutableListOf<DocumentId>()
        val deleteCancellations = mutableListOf<DocumentId>()
        val inverseOps = mutableListOf<WorkspaceOperation>()
        try {
            for (op in operations) {
                when (op) {
                    is WorkspaceOperation.EditDocument -> {
                        val d = candidate[op.documentId]
                            ?: error("文書が存在しません: ${op.documentId.value}")
                        val roots = d.document.documents.map { it.deepCopy() }
                        val root = roots.firstOrNull() ?: error("空の文書は編集できません: ${op.documentId.value}")
                        val inverse = YamlEdit.applyBatch(root, EditBatch(op.operations))
                        candidate[op.documentId] = d.copy(document = d.document.copy(documents = roots))
                        inverseOps.add(0, WorkspaceOperation.EditDocument(op.documentId, inverse))
                    }
                    is WorkspaceOperation.CreateDocument -> {
                        require(op.documentId !in candidate) { "既に存在する文書です: ${op.documentId.value}" }
                        val rel = op.documentId.value
                        candidate[op.documentId] = WorkspaceDocument(
                            document = op.document.copy(documents = op.document.documents.map { it.deepCopy() }),
                            persistedText = op.persistedText,
                            schemaId = schemas.firstOrNull { it.matches(rel) }?.id,
                            inLedger = layout.inLedger(rel),
                        )
                        // 削除の巻き戻し（persistedText 付き）なら削除予約も取り消す
                        if (op.persistedText != null) deleteCancellations += op.documentId
                        inverseOps.add(0, WorkspaceOperation.DeleteDocument(op.documentId))
                    }
                    is WorkspaceOperation.DeleteDocument -> {
                        val d = candidate.remove(op.documentId)
                            ?: error("文書が存在しません: ${op.documentId.value}")
                        if (d.persistedText != null) deleteReservations += op.documentId
                        inverseOps.add(
                            0,
                            WorkspaceOperation.CreateDocument(
                                op.documentId,
                                d.document.copy(documents = d.document.documents.map { it.deepCopy() }),
                                persistedText = d.persistedText,
                            ),
                        )
                    }
                    is WorkspaceOperation.MoveDocument -> {
                        val d = candidate.remove(op.from) ?: error("移動元が存在しません: ${op.from.value}")
                        require(op.to !in candidate) { "移動先が既に存在します: ${op.to.value}" }
                        val rel = op.to.value
                        candidate[op.to] = d.copy(
                            persistedText = op.toPersistedText,
                            schemaId = schemas.firstOrNull { it.matches(rel) }?.id,
                            inLedger = layout.inLedger(rel),
                        )
                        // 移動元が保存済みなら削除予約（save で旧パスを消す）。巻き戻し（toPersistedText 付き）は予約取消
                        if (d.persistedText != null) deleteReservations += op.from
                        if (op.toPersistedText != null) deleteCancellations += op.to
                        inverseOps.add(0, WorkspaceOperation.MoveDocument(op.to, op.from, toPersistedText = d.persistedText))
                    }
                }
            }
        } catch (e: Exception) {
            // 候補を捨てるだけ。working 状態は変わっていない（部分変更を公開しない）
            return TransactionResult(false, e.message ?: e.toString(), version)
        }

        // 候補の上で検証してから交換する（計画書§4.3 適用順序）
        val candidateDerived = WorkspaceReader.derive(candidate, schemas, declarations, layout, version + 1)
        if (requireValid) {
            val structuralError = candidateDerived.structuralIssues.values.flatten()
                .firstOrNull { it.severity == validate.Severity.ERROR }
            val semanticError = candidateDerived.semantic.issues.firstOrNull { it.severity == validate.Severity.ERROR }
            if (structuralError != null || semanticError != null) {
                val reason = structuralError?.message ?: semanticError?.message
                return TransactionResult(false, "検証エラーのため適用しません: $reason", version)
            }
        }

        workingDocuments = candidate
        pendingDeletes += deleteReservations
        pendingDeletes -= deleteCancellations.toSet()
        version += 1
        derived = candidateDerived
        onCommit(inverseOps)
        return TransactionResult(true, null, version)
    }
}
