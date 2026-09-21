package workspace

import edit.EditBatch
import edit.YamlEdit
import edit.YamlOperation
import model.DocumentId
import model.YDocument
import model.deepCopy
import validate.StructuralIssue
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
     * 文書全体の置換（全ドキュメント列。逆操作は旧内容の ReplaceDocument）。
     * 承認済みエージェント変更の適用（A-2b）・インポートなど、文書単位で取り込むトランザクションが使う。
     */
    data class ReplaceDocument(val documentId: DocumentId, val document: YDocument) : WorkspaceOperation

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
    /** baseVersion 不一致による拒否か（CLI の exit code 4 の型付き判定。計画書 Phase 10）。 */
    val staleBase: Boolean = false,
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
    /**
     * 増分導出を使うか（Phase 11・PF-002）。false で全トランザクションが全再構築になる
     * （性能最適化を無効化しても正しく動くことの保証。計画書 Phase 11 完了条件）。
     */
    private val incrementalDerive: Boolean = true,
    /** 組込スキーマの注入口（C-2。メタスキーマ等。reload・スキーマ再構築でも維持される）。 */
    private val builtins: List<WorkspaceSchema> = emptyList(),
    /** 時刻は注入し、テストで固定する（計画書§5）。末尾引数を維持（既存の末尾ラムダ呼び出し互換）。 */
    private val clock: () -> Instant = { Instant.now() },
) {
    val layout: schema.WorkspaceLayout

    /** スキーマ束（スキーマ文書の変更を含むトランザクションで再構築される。WS-006）。 */
    var schemas: List<WorkspaceSchema>
        private set

    /** スキーマ文書のパース失敗（再構築のたびに更新）。 */
    var schemaErrors: List<String>
        private set

    /** 関係レジストリの集約（関係・エンティティ型・強制スイッチ。スキーマの再構築のたびに更新）。 */
    var declarations: semantic.SemanticDeclarations
        private set

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

    // 編集記録の合体（UX-11 のセッション版。RA-06）: 直前の記録と同じキー・時間窓内なら記録を置き換える
    private var lastCoalesceKey: Any? = null
    private var lastCoalesceDoc: DocumentId? = null
    private var lastCoalesceAtMillis: Long = 0L
    private var lastCoalesceRecordId: Long = -1L

    private companion object {
        /** 連続入力を1記録へ合体する時間窓（ms。EditBuffer.COALESCE_WINDOW_MS と同値）。 */
        const val COALESCE_WINDOW_MS = 1000L
    }

    /** 合体の連鎖を断ち切る（Undo/Redo・取り込み・適用など、編集の連続性が切れる操作で呼ぶ）。 */
    private fun resetCoalesce() {
        lastCoalesceKey = null
        lastCoalesceDoc = null
        lastCoalesceRecordId = -1L
    }

    init {
        val loaded = WorkspaceReader.load(store, version = 0, builtins = builtins)
        layout = loaded.layout
        schemas = loaded.schemas
        schemaErrors = loaded.schemaErrors
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

    /** 文書としてセッションに載っているか（snapshot の全コピーを避ける軽量版。編集ごとの直結＝RA-05 が使う）。 */
    fun hasDocument(id: DocumentId): Boolean = id in workingDocuments

    /** 変更履歴（古い順）。 */
    val history: List<ChangeRecord> get() = undoStack.toList()

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    /** 次に undo される記録（あれば）。UIが影響文書を先に把握してツリーを追随させるために使う（RA-06）。 */
    fun peekUndo(): ChangeRecord? = undoStack.lastOrNull()

    /** 次に redo される記録（あれば）。 */
    fun peekRedo(): ChangeRecord? = redoStack.lastOrNull()

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
        resetCoalesce()
        if (tx.baseVersion != version) {
            return TransactionResult(
                false, "baseVersion が古いため適用できません（現在 $version・指定 ${tx.baseVersion}）", version, staleBase = true,
            )
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
        resetCoalesce()
        val record = undoStack.removeLastOrNull()
            ?: return TransactionResult(false, "戻す変更がありません", version)
        val result = applyOperations(record.inverse.operations, requireValid = false) { }
        if (result.ok) redoStack.addLast(record) else undoStack.addLast(record)
        return result
    }

    /** 1手進める（forward トランザクションを再適用する）。 */
    fun redo(): TransactionResult {
        resetCoalesce()
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

    /** スキーマの意味束（リファクタリング計画などカーネルAPIの入力。再構築に追随するため都度組む）。 */
    private fun schemaSemantics(): Map<String, semantic.SchemaSemantics> =
        schemas.associate {
            it.id to semantic.SchemaSemantics(
                id = it.id,
                tree = it.tree,
                entities = it.entities,
                source = if (it.isTemplate) null else it.source,
                kind = it.kind,
            )
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
            val plan = semantic.SemanticRefactor.plan(derived.semantic, schemaSemantics(), request) { id ->
                workingDocuments[id]?.document?.documents?.firstOrNull()
            }
            val oldSchema = schemas.firstOrNull { it.matches(request.from.value) }
            val newSchema = schemas.firstOrNull { it.matches(request.to.value) }
            val warning = if (oldSchema?.id != newSchema?.id) {
                listOf("スキーマの適用が変わります: ${oldSchema?.id ?: "（なし）"} → ${newSchema?.id ?: "（なし）"}")
            } else emptyList()
            return plan.copy(warnings = plan.warnings + warning)
        }
        return semantic.SemanticRefactor.plan(derived.semantic, schemaSemantics(), request, refactorDocuments()) { id ->
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

    /**
     * 文書1件を新しい内容で置き換える（Phase A-2a の併存期経路: UI（EditBuffer）の編集内容の反映）。
     * - working だけを更新し、ストアには書かない（保存は `save`）。**履歴には積まない**——
     *   Undo / dirty は A-3（履歴一元化）まで UI 層（EditBuffer）が所有し、以後 apply 経由に置き換える
     * - 受け取ったツリーは deep copy して取り込む（UI 側の可変ツリーと共有しない。ADR-0003）
     * - スキーマ文書・derive 導出元の置換はスキーマ束の再構築へ振り分ける（WS-006 / C-2 と同型）
     */
    fun replaceDocument(id: DocumentId, document: YDocument): TransactionResult {
        resetCoalesce()
        val rel = id.value
        val copied = document.copy(documents = document.documents.map { it.deepCopy() })
        val existing = workingDocuments[id]
        val schemaTouched = layout.zoneOf(rel) == schema.WorkspaceLayout.Zone.SCHEMAS ||
            schemas.any { s -> s.derive?.matchesSource(rel) == true }
        val candidate = LinkedHashMap(workingDocuments)
        candidate[id] = WorkspaceDocument(
            document = copied,
            persistedText = existing?.persistedText,
            schemaId = existing?.schemaId ?: WorkspaceReader.assignSchemaId(rel, copied, schemas, layout),
            inLedger = existing?.inLedger ?: layout.inLedger(rel),
        )
        if (!incrementalDerive || schemaTouched) {
            if (schemaTouched) {
                val sources = candidate.entries
                    .filter { layout.zoneOf(it.key.value) == schema.WorkspaceLayout.Zone.SCHEMAS }
                    .sortedBy { it.key.value }
                    .map { (docId, d) -> docId to DefaultYamlCodec.dump(d.document) }
                val built = WorkspaceReader.buildSchemas(sources, builtins, candidate.map { (docId, d) -> docId to d.document }, layout)
                schemas = built.schemas
                declarations = built.declarations
                schemaErrors = built.errors
                for ((docId, d) in candidate) {
                    val newId = WorkspaceReader.assignSchemaId(docId.value, d.document, schemas, layout)
                    if (newId != d.schemaId) candidate[docId] = d.copy(schemaId = newId)
                }
            }
            workingDocuments = candidate
            version += 1
            derived = WorkspaceReader.derive(workingDocuments, schemas, declarations, layout, version)
        } else {
            derived = deriveIncremental(candidate, setOf(id), emptySet(), version + 1)
            workingDocuments = candidate
            version += 1
        }
        return TransactionResult(true, null, version)
    }

    /**
     * UI編集1回の記録（Phase A-3・RA-06。ADR-0006: 履歴の単一所有）: 文書全体の置換を**履歴つき**で
     * 適用する。フォーム・ソース編集の「1編集=1記録」の入口で、粒度は EditBuffer のスナップショット履歴と
     * 同じ文書単位（細粒度の型付き操作化は将来の改善——ReplaceDocument も型付き・逆操作つきの操作である）。
     * coalesceKey（UX-11）: 直前の記録と同じキー・同じ文書・時間窓内・redo なしのとき、新しい記録を
     * 積まず直前の記録の forward を置き換える（inverse は据え置き＝グループ先頭の前状態へ戻る）。
     * nowMillis はテストからの時刻注入用（タイマー・sleep を使わない規約）。
     */
    fun recordEdit(
        id: DocumentId,
        document: YDocument,
        coalesceKey: Any? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): TransactionResult {
        // 履歴に UI の可変ツリーを共有しない（ADR-0003）: 記録時点の deep copy で操作を作る
        val op = WorkspaceOperation.ReplaceDocument(
            id, document.copy(documents = document.documents.map { it.deepCopy() }),
        )
        val coalesce = coalesceKey != null && coalesceKey == lastCoalesceKey && id == lastCoalesceDoc &&
            nowMillis - lastCoalesceAtMillis <= COALESCE_WINDOW_MS && redoStack.isEmpty() &&
            undoStack.lastOrNull()?.id == lastCoalesceRecordId
        val result = applyOperations(listOf(op), requireValid = false) { inverseOps ->
            if (coalesce && undoStack.isNotEmpty()) {
                val top = undoStack.removeLast()
                undoStack.addLast(
                    top.copy(
                        forward = top.forward.copy(operations = listOf(op)),
                        afterVersion = version,
                    )
                )
                lastCoalesceRecordId = top.id
            } else {
                val intent = ChangeIntent("編集: ${id.value}")
                val record = ChangeRecord(
                    id = ++recordCounter,
                    intent = intent,
                    beforeVersion = version - 1,
                    afterVersion = version,
                    forward = WorkspaceTransaction(version - 1, intent, listOf(op)),
                    inverse = WorkspaceTransaction(version, intent, inverseOps),
                    occurredAt = clock(),
                )
                undoStack.addLast(record)
                lastCoalesceRecordId = record.id
            }
            redoStack.clear()
        }
        if (result.ok) {
            lastCoalesceKey = coalesceKey
            lastCoalesceDoc = id
            lastCoalesceAtMillis = nowMillis
        }
        return result
    }

    /**
     * ストア側のファイル変更（保存・外部変更・削除）を増分で取り込む（root `Workspace.noteDiskChanges` の
     * セッション版＝Phase A-1・RA-01。イベント駆動 watch（A-4）もこの入口を使う予定）。
     * R9-02 と同じ振り分けで、走査規則が変わる変更だけ全再読込へ降格する:
     * - スキーマゾーン・構成ディレクトリに触れる変更 → reload()（スキーマ束・割当・レイアウトの作り直し。
     *   未保存のworking変更は失われるため、呼び出し側（UI）が編集中文書を再反映する＝RA-05）
     * - 履歴・削除予約が残っている場合 → reload()（取り込みで巻き戻せない状態を作らない）
     * - 未保存のworking変更がある文書（dirty）の変更・削除 → **取り込まない**（working を保持＝UIの
     *   「見たまま」を守る。競合の検出・解決は UI 層（externalConflict）の責務。RA-05）
     * - それ以外 → 変更文書だけストアから読み直し、増分導出で派生状態を更新する（結果は全再構築と一致）
     * パース不能になった文書・ストアから消えた文書は文書集合から外れる（load と同じ扱い）。
     */
    fun noteStoreChanges(changed: Collection<DocumentId>, removed: Collection<DocumentId> = emptyList()): TransactionResult {
        resetCoalesce()
        // 隠しパスは文書集合の対象外（load と同じ規則）——変更として渡されても無視する
        val changedDocs = changed.filterNot { WorkspaceReader.hiddenPath(it.value, layout) }
        val removedDocs = removed.filterNot { WorkspaceReader.hiddenPath(it.value, layout) }
        val needsFullReload = (changedDocs + removedDocs).any { id ->
            layout.zoneOf(id.value) == schema.WorkspaceLayout.Zone.SCHEMAS ||
                (layout.configDir != null && id.value.startsWith("${layout.configDir}/")) ||
                // derive の導出元文書の変更はスキーマ変更と同じ扱い（R11-03 / C-2。root noteDiskChanges と同型）
                schemas.any { s -> s.derive?.matchesSource(id.value) == true }
        } || undoStack.isNotEmpty() || redoStack.isNotEmpty() || pendingDeletes.isNotEmpty()
        if (needsFullReload) return reload()

        val touched = mutableSetOf<DocumentId>()
        val gone = mutableSetOf<DocumentId>()
        for (id in removedDocs) {
            if (isDirty(id)) continue  // 未保存編集を保持（削除の競合解決は UI 層）
            if (workingDocuments.remove(id) != null) gone += id
            storeTexts.remove(id)
        }
        for (id in changedDocs.filterNot { isDirty(it) }) {
            val text = store.read(id)?.text
            if (text == null) {
                if (workingDocuments.remove(id) != null) gone += id
                storeTexts.remove(id)
                continue
            }
            storeTexts[id] = text
            val parsed = runCatching { DefaultYamlCodec.parse(id, text) }.getOrNull()
            if (parsed == null) {
                if (workingDocuments.remove(id) != null) gone += id
                continue
            }
            val rel = id.value
            workingDocuments[id] = WorkspaceDocument(
                document = parsed,
                persistedText = DefaultYamlCodec.dump(parsed),
                schemaId = WorkspaceReader.assignSchemaId(rel, parsed, schemas, layout),
                inLedger = layout.inLedger(rel),
            )
            touched += id
        }
        version += 1
        derived = deriveIncremental(workingDocuments, touched, gone, version)
        return TransactionResult(true, null, version)
    }

    /**
     * 未保存編集（UI層のオーバーレイ）を重ねた派生状態を導出する読み出しモデル（Phase A-1・RA-01。
     * UX-03「見たまま判定」のカーネル供給版）。セッションの状態（working・履歴・version）は変えない。
     * - overlays: 文書ID → 編集中の内容（null = パース不能。文書集合から外して判定する）
     * - 構造検証は overlay 文書だけ再検証し、意味解析は SemanticEngine.update（増分導出と同じ正しさ経路）
     * - スキーマ文書の overlay はスキーマ束へ反映しない（索引と同じ「保存で反映」の二層規律。§4.5）
     */
    fun derivedWith(overlays: Map<DocumentId, YDocument?>): WorkspaceDerivedState {
        if (overlays.isEmpty()) return derived
        val candidate = LinkedHashMap(workingDocuments)
        val touched = mutableSetOf<DocumentId>()
        val removedIds = mutableSetOf<DocumentId>()
        for ((id, doc) in overlays) {
            if (doc == null) {
                if (candidate.remove(id) != null) removedIds += id
                continue
            }
            val rel = id.value
            val existing = candidate[id]
            candidate[id] = WorkspaceDocument(
                document = doc,
                persistedText = existing?.persistedText,
                schemaId = existing?.schemaId ?: WorkspaceReader.assignSchemaId(rel, doc, schemas, layout),
                inLedger = existing?.inLedger ?: layout.inLedger(rel),
            )
            touched += id
        }
        return deriveIncremental(candidate, touched, removedIds, version)
    }

    /** ストアから読み直す（外部変更の取り込み。履歴は破棄される）。 */
    fun reload(): TransactionResult {
        resetCoalesce()
        val loaded = WorkspaceReader.load(store, version = version + 1, builtins = builtins)
        schemas = loaded.schemas
        schemaErrors = loaded.schemaErrors
        declarations = loaded.declarations
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
                            schemaId = WorkspaceReader.assignSchemaId(rel, op.document, schemas, layout),
                            inLedger = layout.inLedger(rel),
                        )
                        // 削除の巻き戻し（persistedText 付き）なら削除予約も取り消す
                        if (op.persistedText != null) deleteCancellations += op.documentId
                        inverseOps.add(0, WorkspaceOperation.DeleteDocument(op.documentId))
                    }
                    is WorkspaceOperation.ReplaceDocument -> {
                        val d = candidate[op.documentId]
                            ?: error("文書が存在しません: ${op.documentId.value}")
                        inverseOps.add(
                            0,
                            WorkspaceOperation.ReplaceDocument(
                                op.documentId,
                                d.document.copy(documents = d.document.documents.map { it.deepCopy() }),
                            ),
                        )
                        candidate[op.documentId] = d.copy(
                            document = op.document.copy(documents = op.document.documents.map { it.deepCopy() }),
                        )
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
                            schemaId = WorkspaceReader.assignSchemaId(rel, d.document, schemas, layout),
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

        // スキーマ文書（または derive の導出元文書＝C-2）に触れた場合は候補の working 内容から
        // スキーマ束を再構築し、全文書の割当を再評価する（WS-006。「スキーマが変わったら必ず rebuild」の
        // セッション版。undo / redo も同経路で対称に戻る）
        val schemaTouched = operations.any { op ->
            val ids = when (op) {
                is WorkspaceOperation.EditDocument -> listOf(op.documentId)
                is WorkspaceOperation.CreateDocument -> listOf(op.documentId)
                is WorkspaceOperation.ReplaceDocument -> listOf(op.documentId)
                is WorkspaceOperation.DeleteDocument -> listOf(op.documentId)
                is WorkspaceOperation.MoveDocument -> listOf(op.from, op.to)
            }
            ids.any { id ->
                layout.zoneOf(id.value) == schema.WorkspaceLayout.Zone.SCHEMAS ||
                    schemas.any { s -> s.derive?.matchesSource(id.value) == true }
            }
        }
        var newSchemas = schemas
        var newDeclarations = declarations
        var newSchemaErrors = schemaErrors
        if (schemaTouched) {
            val sources = candidate.entries
                .filter { layout.zoneOf(it.key.value) == schema.WorkspaceLayout.Zone.SCHEMAS }
                .sortedBy { it.key.value }
                .map { (id, d) -> id to DefaultYamlCodec.dump(d.document) }
            // 組込＋derive 展開込みの再構築（導出元はセッションの working 状態＝候補。C-2）
            val built = WorkspaceReader.buildSchemas(sources, builtins, candidate.map { (id, d) -> id to d.document }, layout)
            newSchemas = built.schemas
            newDeclarations = built.declarations
            newSchemaErrors = built.errors
            for ((id, d) in candidate) {
                val newId = WorkspaceReader.assignSchemaId(id.value, d.document, newSchemas, layout)
                if (newId != d.schemaId) candidate[id] = d.copy(schemaId = newId)
            }
        }

        // 候補の上で検証してから交換する（計画書§4.3 適用順序）。
        // スキーマに触れた場合は影響範囲が不明なため必ず全再構築（Phase 11 作業5）。
        // それ以外は触れた文書だけ再検証・再走査する増分導出（結果は全再構築と一致＝oracle テストで固定）
        val touched = mutableSetOf<DocumentId>()
        val removed = mutableSetOf<DocumentId>()
        for (op in operations) {
            when (op) {
                is WorkspaceOperation.EditDocument -> touched += op.documentId
                is WorkspaceOperation.CreateDocument -> touched += op.documentId
                is WorkspaceOperation.ReplaceDocument -> touched += op.documentId
                is WorkspaceOperation.DeleteDocument -> removed += op.documentId
                is WorkspaceOperation.MoveDocument -> {
                    removed += op.from
                    touched += op.to
                }
            }
        }
        val candidateDerived = if (!incrementalDerive || schemaTouched) {
            WorkspaceReader.derive(candidate, newSchemas, newDeclarations, layout, version + 1)
        } else {
            deriveIncremental(candidate, touched, removed, version + 1)
        }
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
        schemas = newSchemas
        declarations = newDeclarations
        schemaErrors = newSchemaErrors
        version += 1
        derived = candidateDerived
        onCommit(inverseOps)
        return TransactionResult(true, null, version)
    }

    /**
     * 増分導出（Phase 11・PF-002）: 構造検証は触れた文書だけ再検証（他は前回結果を再利用・キー順は
     * 文書順を維持）、意味解析は触れた文書だけ再走査して集約し直す（SemanticEngine.update）。
     * スキーマ束・宣言・レイアウトが変わらないトランザクション専用（呼び分けは applyOperations）。
     */
    private fun deriveIncremental(
        candidate: Map<DocumentId, WorkspaceDocument>,
        touched: Set<DocumentId>,
        removed: Set<DocumentId>,
        newVersion: Long,
    ): WorkspaceDerivedState {
        val byId = schemas.associateBy { it.id }
        val structural = linkedMapOf<DocumentId, List<StructuralIssue>>()
        for ((id, d) in candidate) {
            val issues = if (id in touched) WorkspaceReader.validateOne(d, byId) else derived.structuralIssues[id]
            if (issues != null) structural[id] = issues
        }
        val changedSemanticDocs = touched.mapNotNull { id ->
            candidate[id]?.let { WorkspaceReader.semanticDocument(id, it, byId, layout) }
        }
        val semanticSnapshot = semantic.SemanticEngine.update(
            derived.semantic,
            semantic.SemanticDelta(
                workspaceVersion = newVersion,
                changed = changedSemanticDocs,
                removed = removed + touched,
                declarations = declarations,
            ),
        )
        return WorkspaceDerivedState(newVersion, structural, semanticSnapshot)
    }

    /**
     * working 状態からの全再構築（oracle。Phase 11 完了条件「増分更新と全再構築が同一結果を返す」の
     * 突合先。テスト・診断用で、セッションの状態は変えない）。
     */
    fun fullDerived(): WorkspaceDerivedState =
        WorkspaceReader.derive(workingDocuments, schemas, declarations, layout, version)
}
