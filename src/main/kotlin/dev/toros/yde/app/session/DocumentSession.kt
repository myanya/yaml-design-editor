package dev.toros.yde.app.session

import dev.toros.yde.app.core.ChangeSetResult
import dev.toros.yde.app.core.Search
import dev.toros.yde.app.core.SearchHit
import dev.toros.yde.app.core.SearchQuery
import dev.toros.yde.app.core.SourceOutline
import dev.toros.yde.app.core.ViewDef
import dev.toros.yde.app.core.Views
import dev.toros.yde.app.core.WorkspaceRecord
import dev.toros.yde.app.core.WorkspaceRecords
import dev.toros.yde.app.core.WorkspaceScan
import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.app.index.idMatchesPattern
import dev.toros.yde.app.schema.BuiltinSchemas
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.app.schema.SchemaInference
import dev.toros.yde.app.validate.Issue
import dev.toros.yde.app.validate.Readiness
import dev.toros.yde.app.validate.Validator
import dev.toros.yde.semantic.RefactoringPlan
import dev.toros.yde.semantic.RefactoringRequest
import dev.toros.yde.semantic.SemanticAutoValues
import dev.toros.yde.semantic.SemanticSchemaGraph
import dev.toros.yde.semantic.schema.EnforcementDef
import dev.toros.yde.semantic.schema.EntityTypeDef
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.MethodDef
import dev.toros.yde.semantic.schema.RefTarget
import dev.toros.yde.semantic.schema.RelationDef
import dev.toros.yde.semantic.schema.SchemaTree
import dev.toros.yde.semantic.schema.globToRegex
import dev.toros.yde.workspace.ActionAvailability
import dev.toros.yde.workspace.ActionEntry
import dev.toros.yde.workspace.ActionPlan
import dev.toros.yde.workspace.ActionPlanner
import dev.toros.yde.workspace.ActionRequest
import dev.toros.yde.workspace.ChangeIntent
import dev.toros.yde.workspace.ChangeRecord
import dev.toros.yde.workspace.DocumentScaffold
import dev.toros.yde.workspace.DocumentScaffolds
import dev.toros.yde.workspace.FileArrangementPlan
import dev.toros.yde.workspace.FileArrangementPlanner
import dev.toros.yde.workspace.FileSystemDocumentStore
import dev.toros.yde.workspace.PathMove
import dev.toros.yde.workspace.PreparedActionInput
import dev.toros.yde.workspace.ScaffoldSource
import dev.toros.yde.workspace.StoreConflictException
import dev.toros.yde.workspace.WorkspaceDerivedState
import dev.toros.yde.workspace.WorkspaceOperation
import dev.toros.yde.workspace.WorkspacePaths
import dev.toros.yde.workspace.WorkspaceReader
import dev.toros.yde.workspace.WorkspaceSession
import dev.toros.yde.workspace.WorkspaceTransaction
import dev.toros.yde.workspace.changeset.ChangeSet
import dev.toros.yde.workspace.checkUnchanged
import dev.toros.yde.workspace.layout.FilePolicy
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YDocument
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.deepCopy
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.pretty
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.UUID

/**
 * 開いているワークスペースのセッション側の状態（Compose を使わない。architecture/workspace.md）。
 * ヘッドレスコア（core.Workspace）とカーネルのセッション（WorkspaceSession）を持ち、開いている文書の集合（DocumentState）、
 * 索引の写し、未保存編集を重ねた索引と派生、編集の記録、セッションの開き直しとディスクとの同期を担う。
 * 画面側（app.AppState）は、ここの値を Compose の状態へ写し（`onChanged`）、画面の場所・通知・確認を担う。
 * 変更はカーネルのトランザクションで、保存するまでディスクに出ない。呼び出しは同時に 1 スレッドから行う。
 */
class DocumentSession(private val editClock: () -> Long = System::currentTimeMillis) {
    /** 状態の変化の聞き手（殻が Compose の状態へ写す）。 */
    var onChanged: (() -> Unit)? = null
    private fun changed() { onChanged?.invoke() }

    /** 利用者への短い知らせ（殻が status に出す）。 */
    var onNotice: ((String) -> Unit)? = null

    /** 編集の記録に添える編集元の印（殻が画面の場所を返す）。合体キーに含めるので、同じ項目でも画面が違えば別の記録になる。 */
    var editOrigin: ((DocumentState, Any?) -> Any?)? = null

    /** 編集を記録した後の聞き手（記録 ID と編集元の印）。殻が記録 ID と画面の対応を持つ。 */
    var onEditRecorded: ((recordId: Long, origin: Any?) -> Unit)? = null

    /** 文書が編集されたときの聞き手（editVersion を進めた後、記録の前）。 */
    var onEdited: ((DocumentState) -> Unit)? = null

    /** カーネルのセッションを開き直したときの聞き手（殻が記録 ID と画面の対応を捨てる）。 */
    var onKernelReplaced: (() -> Unit)? = null

    var workspace: Path? = null
        set(value) { field = value; changed() }

    /**
     * ヘッドレスコア。ファイル走査・スキーマ・索引の実体と更新方針（増分／全再構築の振り分け）は core.Workspace が持つ。
     * 置き換えは殻の再読込が行う（移行の途中。段階が進めば再読込もここへ移る）。
     */
    var core: dev.toros.yde.app.core.Workspace? = null

    /** カーネルのセッション。読み経路の供給元で、編集・履歴・dirty の正。開けない場合は null のまま旧経路で動く。 */
    var kernel: WorkspaceSession? = null

    // ---------------------------------------------------------------- コアからの写し

    var schemas: List<SchemaDef> = emptyList(); private set
    var schemaErrors: List<String> = emptyList(); private set
    /** 関係レジストリ: relation 名 → 宣言。 */
    var relations: Map<String, RelationDef> = emptyMap(); private set
    /** 強制スイッチ: グラフ整合検証の enforcement。 */
    var enforcement: EnforcementDef = EnforcementDef(); private set
    /** エンティティ型レジストリ: 型の表示名・アスペクト順序。 */
    var entityTypes: Map<String, EntityTypeDef> = emptyMap(); private set
    /** スキーマの図（型の段のレンズ）。 */
    var schemaGraph: SemanticSchemaGraph = SemanticSchemaGraph.EMPTY; private set
    var yamlFiles: List<Path> = emptyList(); private set
    /** ワークスペース内の Markdown（表示用。索引・検証の対象ではない）。 */
    var markdownFiles: List<Path> = emptyList(); private set
    /** 台帳外の YAML（走査対象外＝索引・検証集計・エージェントに載らない、エディタ層のファイル）。 */
    var editorOnlyFiles: List<Path> = emptyList(); private set

    /** ワークスペースの配置ポリシー。コア未オープン時はレガシー相当。 */
    val layout: WorkspaceLayout get() = core?.layout ?: WorkspaceLayout.LEGACY

    /** 走査対象（台帳＋予約領域）に載っているファイルか。台帳外は索引前提の検証を降格する。 */
    fun isIndexed(file: Path): Boolean = core?.let { file in it.files } ?: true
    fun isIndexed(document: DocumentId): Boolean = isIndexed(fileOf(document))

    private val emptyIndex = IdIndex()
    val index: IdIndex get() = core?.index ?: emptyIndex

    /** 索引の版。ディスクの取り込み・保存・適用で進む。 */
    var indexVersion: Int = 0
        set(value) { field = value; changed() }

    /** 全文書横断の編集カウンタ: 開いているどの文書かの未保存編集・Undo/Redo で進む。 */
    var editVersion: Int = 0
        set(value) { field = value; changed() }

    fun editorOnlyFilesOf(cw: dev.toros.yde.app.core.Workspace): List<Path> =
        if (cw.layout.ledger.isEmpty()) emptyList()
        else WorkspaceScan.editorYamlFiles(cw.root).filter { it !in cw.files.toSet() }

    /** core.Workspace の状態を写しへ反映する。走査は状態反映より先に行う。 */
    fun mirrorCore(
        cw: dev.toros.yde.app.core.Workspace,
        markdown: List<Path> = WorkspaceScan.markdownFiles(cw.root),
        editorOnly: List<Path> = editorOnlyFilesOf(cw),
    ) {
        schemas = cw.schemas
        schemaErrors = cw.schemaErrors
        relations = cw.relations
        enforcement = cw.enforcement
        entityTypes = cw.entityTypes
        schemaGraph = cw.schemaGraph
        yamlFiles = cw.files
        markdownFiles = markdown
        // 台帳外のエディタ層ファイル。ledger 宣言が無ければ走査対象と一致するため空になる
        editorOnlyFiles = editorOnly
        changed()
    }

    fun rebuildIndex() {
        val cw = core ?: return
        // 索引の供給はセッション派生に一本化されたため、全再構築は refresh 経由で行う
        cw.refresh()
        mirrorCore(cw)
        indexVersion++
    }

    // ---------------------------------------------------------------- 未保存編集を重ねた索引と派生

    private var overlayCache: Pair<Pair<Int, Int>, IdIndex>? = null
    private var derivedCache: Pair<Pair<Int, Int>, WorkspaceDerivedState>? = null

    /** 覚えを捨てる（カーネルを差し替えたときなど）。 */
    fun invalidateCaches() { overlayCache = null; derivedCache = null }

    /**
     * 未保存編集を重ねたオーバーレイ索引。dirty な文書の編集中の木を重ねた「見たまま」の索引（architecture/index-references.md「索引の二層」）。
     * 供給元はセッション派生（カーネルの増分導出）で、索引はその意味解析の結果そのもの。セッションを開けていない場合だけディスクを映した索引へ重ねる。
     */
    fun overlayIndex(): IdIndex {
        val cw = core ?: return index
        val key = indexVersion to editVersion
        overlayCache?.let { (k, cached) -> if (k == key) return cached }
        val result = derivedState()?.let { derived -> IdIndex.of(derived.semantic) } ?: legacyOverlayIndex(cw)
        overlayCache = key to result
        return result
    }

    /**
     * まだ文書に入れていない内容（AI 生成の提案など）を重ねた索引。定義体の検査は索引にある要素だけを判定するため、
     * 提案の内容で新しく書かれた ID にも検査を効かせるには、提案を重ねた索引が要る。台帳の外の文書・セッションを開けない場合は通常の索引。
     */
    fun overlayIndexWith(file: Path, root: YNode): IdIndex {
        val cw = core ?: return index
        if (!isIndexed(file)) return overlayIndex()
        val overlays: Map<Path, YNode?> = documents.values
            .filter { it.dirty && isIndexed(it.file) }
            .associate { it.file to it.root as YNode? } + (file to root)
        return runCatching { cw.overlayIndex(overlays) }.getOrElse { overlayIndex() }
    }

    /** 従来の二層オーバーレイ（セッションを開けない場合のみ）。 */
    private fun legacyOverlayIndex(cw: dev.toros.yde.app.core.Workspace): IdIndex {
        val overlays = documents.values
            .filter { it.dirty && isIndexed(it.file) }
            .associate { it.file to it.root }
        return if (overlays.isEmpty()) cw.index else cw.overlayIndex(overlays)
    }

    /**
     * 派生状態: セッションの working 状態＝「見たまま」の構造・意味派生。編集はセッション直結のため、
     * オーバーレイはパース不能の編集中文書（文書集合から外す）だけ。
     */
    fun derivedState(): WorkspaceDerivedState? {
        val s = kernel ?: return null
        val key = indexVersion to editVersion
        derivedCache?.let { (k, cached) -> if (k == key) return cached }
        val overlays = documents.values
            .filter { it.dirty && isIndexed(it.file) && it.root == null }
            .associate { DocumentId(relPath(it.file)) to null as YDocument? }
        val result = s.derivedWith(overlays)
        derivedCache = key to result
        return result
    }

    // ---------------------------------------------------------------- 場所

    fun relPath(file: Path): String {
        val ws = workspace ?: return file.toString()
        return IdIndex.relPathOf(ws, file)
    }

    /** ファイルの文書ID（ワークスペース相対パス）。索引・検証・ビューの解決は、場所をこの座標で表す。 */
    fun documentOf(file: Path): DocumentId = DocumentId(relPath(file))

    /** 文書ID のファイル。ファイルを開く・文書を引くなど、Path が要る所でだけ使う。 */
    fun fileOf(document: DocumentId): Path = (workspace?.resolve(document.value) ?: Path.of(document.value)).normalize()

    fun schemaFor(file: Path): SchemaDef? = core?.schemaFor(file)
    fun schemaFor(document: DocumentId): SchemaDef? = schemaFor(fileOf(document))

    /** AI 生成の参考文書候補: 同じスキーマが適用される他の YAML 文書（更新日時の新しい順）。 */
    fun sameSchemaFiles(exclude: Path, schemaId: String): List<Path> =
        core?.files
            ?.filter { it != exclude && schemaFor(it)?.id == schemaId }
            ?.sortedByDescending { file -> runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L) }
            .orEmpty()

    /** file 型の値の許可（`.yde/workspace.yaml` の `files:`）。検証の呼び出しに渡す。 */
    val filePolicy: FilePolicy get() = core?.layout?.files ?: FilePolicy.DEFAULT

    /** ファイル（YAML 以外も）があるか（file 型の値の存在の検査。相対はワークスペースのルートから）。 */
    fun fileExists(path: String): Boolean = core?.fileExists(path) ?: false

    /** 準備完了レポート（CLI `readiness` と同じ計算＝保存済みの内容で判定）。 */
    fun readinessReport(): Readiness.Report? = core?.let { Readiness.report(it) }

    // ---------------------------------------------------------------- 開いている文書

    private val documentMap = LinkedHashMap<Path, DocumentState>()

    /** 開いたことのある文書（未保存編集をファイル切替時にも保持する）。 */
    val documents: Map<Path, DocumentState> get() = documentMap

    fun document(file: Path): DocumentState? = documentMap[file]

    /** 文書を開く（開いていれば同じもの）。取り込んだ文書は dirty 管理・保存・外部変更検出の対象になる。 */
    fun ensureDocument(file: Path): DocumentState = documentMap.getOrPut(file) { loadDocument(file) }

    /** 文書を閉じる。殻には onDocumentClosed で知らせる。 */
    fun closeDocument(file: Path): DocumentState? = documentMap.remove(file)?.also { onDocumentClosed?.invoke(file) }

    fun clearDocuments() { documentMap.keys.toList().forEach(::closeDocument) }

    /** 編集欄をセッションの履歴へ接続できる文書か。一時フォームは含めない。 */
    fun ownsDocument(doc: DocumentState): Boolean =
        documentMap[doc.file] === doc && kernel?.hasDocument(documentOf(doc.file)) == true

    private fun loadDocument(file: Path): DocumentState {
        val text = runCatching { Files.readString(file) }.getOrDefault("")
        var error: String? = null
        var extra: List<YNode> = emptyList()
        val root = try {
            // 横断 Undo は未表示の文書も変える。本文は working、保存済みの比較用テキストはディスクから取る。
            val docs = kernel?.copyDocument(documentOf(file))?.documents ?: YamlIO.parseAll(text)
            extra = docs.drop(1)
            docs.firstOrNull() ?: YNode.Map()
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            null
        }
        return DocumentState(file, root, schemaFor(file), text, error, extra).also { doc ->
            // 編集通知: 全文書横断カウンタを進め、セッション履歴へ記録する
            doc.onEdited = { coalesceWith ->
                editVersion++
                onEdited?.invoke(doc)
                recordEdit(doc, coalesceWith)
            }
            // dirty の判定はセッション（単一所有）を正とする
            doc.dirtySource = {
                kernel?.let { s ->
                    val id = documentOf(doc.file)
                    if (s.hasDocument(id)) s.isDirty(id) else null
                }
            }
            doc.refreshDirty()
        }
    }

    /**
     * 編集 1 回をセッション履歴へ記録する。合体キーは文書と編集元の印に閉じる（他文書・他画面の連続編集と混ざらない）。
     * パース不能（root=null）の編集は記録しない（生テキストの不正状態はカーネルの履歴に持たない）。
     */
    fun recordEdit(doc: DocumentState, coalesceWith: Any?) {
        val s = kernel ?: return
        val root = doc.root ?: return
        val id = documentOf(doc.file)
        if (!ensureAttached(doc, s, id, root)) return
        val origin = editOrigin?.invoke(doc, coalesceWith)
        runCatching {
            val result = s.recordEdit(
                id, YDocument(id, listOf(root) + doc.extraDocs),
                coalesceKey = coalesceWith?.let { listOf(id.value, origin, it) },
                nowMillis = editClock(),
            )
            if (result.ok) s.peekUndo()?.let { onEditRecorded?.invoke(it.id, origin) }
        }
    }

    /**
     * 編集内容をセッションの working 状態へ直結する（履歴は積まない。履歴の記録は recordEdit）。
     * パース不能（root=null）の編集は反映せず、derivedState() が null オーバーレイで文書集合から外す。
     */
    fun pushEdit(doc: DocumentState) {
        val s = kernel ?: return
        val root = doc.root ?: return
        val id = documentOf(doc.file)
        if (!ensureAttached(doc, s, id, root)) return  // 構成ファイル等、セッションが文書として扱わないパス
        runCatching { s.replaceDocument(id, YDocument(id, listOf(root) + doc.extraDocs)) }
    }

    /**
     * セッションに載っていない文書を載せる: テキスト文書（壊れたまま保存／開いた時点で解析不能）が解析できるようになったとき、
     * 文書集合へ戻す（保存済みの内容との差で dirty になる）。セッションが文書として扱わない場所なら false。
     */
    fun ensureAttached(doc: DocumentState, s: WorkspaceSession, id: DocumentId, root: YNode): Boolean {
        if (s.hasDocument(id)) return true
        if (!doc.textOnly || !s.acceptsDocument(id)) return false
        val attached = runCatching {
            s.attachDocument(id, YDocument(id, listOf(root) + doc.extraDocs), persistedText = doc.rawText)
                .also { if (it.ok) s.acknowledgeStoreText(id, doc.expectedStoreText) }
        }.getOrNull()
        if (attached?.ok != true) return false
        doc.textOnly = false
        return true
    }

    /** 未保存編集を優先する木の取得（開いている文書 → セッション → ディスク）。索引・検証・ビューの解決へ渡す loader。 */
    fun unsavedFirstLoader(): (Path) -> YNode? = { file ->
        val cached = documentMap[file]
        if (cached != null) cached.root
        else kernel?.copyDocument(documentOf(file))?.documents?.firstOrNull()
            ?: runCatching { YamlIO.parseFirst(Files.readString(file)) }.getOrNull()
    }

    fun unsavedFirstTree(): (DocumentId) -> YNode? = unsavedFirstLoader().let { load -> { d -> load(fileOf(d)) } }

    fun workspaceRecords(): List<WorkspaceRecord> =
        core?.let { WorkspaceRecords.collect(it, unsavedFirstLoader()) }.orEmpty()

    /** 未保存の編集・下書き・外部変更との競合を持つ文書（再読込で保護する）。 */
    fun protectedDocumentIds(): Set<DocumentId> = documentMap.values
        .filter { it.dirty || it.draftDirty || it.hasPendingSourceInput || it.externalConflict }
        .map { documentOf(it.file) }.toSet()

    /** 編集や履歴を持っているか（持っていれば、ディスクの取り込みは候補から保護して行う）。 */
    fun hasRetainedEdits(): Boolean = kernel?.let {
        it.canUndo() || it.canRedo() || it.dirtyDocuments().isNotEmpty()
    } == true || protectedDocumentIds().isNotEmpty()

    // ---------------------------------------------------------------- カーネルのセッションの開閉と同期

    /** ワークスペースを開く: 場所を変え、コア・カーネル・開いている文書を捨ててから、ディスクから読む。画面の状態を戻すのは殻（先に行う）。 */
    fun openWorkspace(dir: Path) {
        workspace = dir.toAbsolutePath().normalize()
        core = null
        kernel = null
        clearDocuments()
        onChanged?.invoke()
        reload()
    }

    /**
     * カーネルのセッションを開く（失敗しても null のまま旧経路で動き続ける）。
     * 組込スキーマ（メタスキーマ等）を注入し、`_schemas`・定義ゾーンの検証を旧経路と揃える。
     * fromMirror: ディスクを映すセッション（core.Workspace の内部）を読み直した直後なら、その読み込み済みの状態から起こす（fork）。
     */
    fun openSession(ws: Path, fromMirror: Boolean = false): WorkspaceSession? = runCatching {
        val forked = if (fromMirror) runCatching { core?.mirror?.fork() }.getOrNull() else null
        forked ?: run {
            val wsLayout = core?.layout ?: WorkspaceLayout.of(ws)
            WorkspaceSession(FileSystemDocumentStore(ws), builtins = BuiltinSchemas.kernelSchemas(wsLayout))
        }
    }.getOrNull()

    /** セッションを開き直し、編集中（dirty）の文書を working 状態へ再反映する。開き直しはディスク基準になるため、「見たまま」を編集直結で回復する。 */
    fun reopenSession(ws: Path, fromMirror: Boolean = false) {
        kernel = openSession(ws, fromMirror)
        onKernelReplaced?.invoke()
        documentMap.values.filter { it.dirty }.forEach(::pushEdit)
    }

    /** 編集や履歴があれば候補から保護して取り込み、状態のないセッションだけ従来の増分経路を使う。 */
    fun syncSessionWithDisk(changed: List<Path>, removed: List<Path> = emptyList()) {
        val ws = workspace ?: return
        val s = kernel ?: run { reopenSession(ws); return }
        if (hasRetainedEdits()) {
            val disk = core?.mirror ?: return
            val result = s.refreshFrom(disk, protectedDocumentIds())
            if (!result.ok) onNotice?.invoke(result.message ?: "再読込に失敗しました")
            return
        }
        if (core?.layout != s.layout) { reopenSession(ws); return }
        runCatching {
            val changedIds = changed.map(::documentOf)
            val removedIds = removed.map(::documentOf)
            if (changedIds.isEmpty() && removedIds.isEmpty()) return
            s.noteStoreChanges(changed = changedIds, removed = removedIds)
        }.onFailure { reopenSession(ws) }
    }

    // ---------------------------------------------------------------- 殻への聞き手（再読込・保存）

    /** 再読込や外部変更の取り込みでファイル一覧が変わったときの聞き手（殻がツリーを描き直す）。 */
    var onFilesChanged: (() -> Unit)? = null

    /** 文書をセッション側が閉じたときの聞き手（殻が文書の殻を外し、表示中なら表示をやめる）。 */
    var onDocumentClosed: ((Path) -> Unit)? = null

    /** 再読込を終えたときの聞き手（殻が表示中の文書を見直し、知らせを出す）。 */
    var onReloaded: ((ReloadOutcome) -> Unit)? = null

    /** 再読込してよいか（殻がファイル操作中なら false を返す）。 */
    var reloadAllowed: (() -> Boolean)? = null

    /** 一括変更や保存時の自動値で増えた記録に添える編集元の印（殻が今の画面の場所を返す）。 */
    var historyOrigin: (() -> Any?)? = null

    /** 外部変更の検出に使うファイルの印（更新時刻）。 */
    var workspaceStamp: Map<Path, Long> = emptyMap()
        private set

    fun snapshotFiles(ws: Path): Map<Path, Long> = WorkspaceScan.stamps(ws)

    /** 一括変更や保存時の自動値で増えた記録にも同じ編集元を添える。保存失敗でも残った履歴には添える。 */
    inline fun <T> withHistoryOrigin(s: WorkspaceSession, block: () -> T): T {
        val before = s.peekUndo()?.id ?: -1L
        val origin = historyOrigin?.invoke()
        try { return block() } finally {
            if (kernel === s) s.history.filter { it.id > before }.forEach { onEditRecorded?.invoke(it.id, origin) }
        }
    }

    // ---------------------------------------------------------------- 再読込と競合

    class ReloadCandidate(
        val workspace: dev.toros.yde.app.core.Workspace,
        val markdown: List<Path>, val editorOnly: List<Path>, val stamps: Map<Path, Long>,
    )

    /** 再読込の結果。existing は今あるファイル、protected は未保存の編集などで保護した文書。 */
    class ReloadOutcome(val ok: Boolean, val message: String?, val existing: Set<Path>, val protected: Set<DocumentId>)

    /** 一覧の走査も交換前に済ませ、読込失敗で編集や履歴を半分だけ破棄しない。 */
    fun prepareReload(ws: Path): ReloadCandidate {
        require(Files.isDirectory(ws)) { "ワークスペースのフォルダーがありません: $ws" }
        val cw = dev.toros.yde.app.core.Workspace.open(ws)
        return ReloadCandidate(cw, WorkspaceScan.markdownFiles(ws), editorOnlyFilesOf(cw), snapshotFiles(ws))
    }

    /** ディスク版を候補として読み、編集・下書き・履歴を保持できる場合だけ取り込む。 */
    fun reload(): ReloadOutcome? {
        if (reloadAllowed?.invoke() == false) return null
        val ws = workspace ?: return null
        val candidate = runCatching { prepareReload(ws) }.getOrElse {
            return ReloadOutcome(false, "再読込に失敗しました: ${it.message}", emptySet(), emptySet()).also { o -> onReloaded?.invoke(o) }
        }
        val cw = candidate.workspace
        val current = kernel
        val protected = protectedDocumentIds() + current?.dirtyDocuments().orEmpty()
        var notice: String? = null
        if (current != null) {
            val result = current.refreshFrom(cw.mirror, protected)
            if (!result.ok) {
                documentMap.values.filter { it.dirty || it.draftDirty || it.hasPendingSourceInput }.forEach { doc ->
                    if (runCatching { readStoreText(doc) != doc.expectedStoreText }.getOrDefault(false)) doc.externalConflict = true
                }
                return ReloadOutcome(false, result.message ?: "再読込に失敗しました", emptySet(), protected).also { o -> onReloaded?.invoke(o) }
            }
            notice = result.message
        }
        core = cw
        mirrorCore(cw, candidate.markdown, candidate.editorOnly)
        if (current == null) reopenSession(ws, fromMirror = true)
        onFilesChanged?.invoke()
        val existing = yamlFiles.toSet() + editorOnlyFiles
        documentMap.keys.filter { it !in existing }.toList().forEach { removed ->
            if (documentOf(removed) !in protected) closeDocument(removed)
        }
        documentMap.values.forEach { doc ->
            doc.schema = schemaFor(doc.file)
            // 時刻だけでなく本文を比較する。変更のない下書き・カーソルはそのまま残す。
            val text = cw.mirror.expectedStoreText(documentOf(doc.file))
            if (text != doc.expectedStoreText) {
                if (documentOf(doc.file) in protected) doc.externalConflict = true
                else reloadDocumentFromDisk(doc, force = true, knownText = text)
            }
        }
        indexVersion++
        workspaceStamp = candidate.stamps
        val message = notice ?: "ワークスペース: $ws ／ YAML ${yamlFiles.size} 件・スキーマ ${schemas.size} 件・ID ${index.entries.size} 件"
        return ReloadOutcome(true, message, existing, protected).also { o -> onReloaded?.invoke(o) }
    }

    /** 読込済みの候補とそこから起こしたカーネルへ置き換える（未保存編集と履歴を破棄する再読込）。ディスクへの書込は行わない。 */
    fun adoptCandidate(candidate: ReloadCandidate, freshKernel: WorkspaceSession) {
        core = candidate.workspace
        kernel = freshKernel
        clearDocuments()
        onKernelReplaced?.invoke()
        invalidateCaches()
        mirrorCore(candidate.workspace, candidate.markdown, candidate.editorOnly)
        onFilesChanged?.invoke()
        editVersion++
        indexVersion++
        workspaceStamp = candidate.stamps
    }

    /** 再読込の確認中に編集や履歴が変わっていないかを見る印。 */
    fun reloadConfirmationStamp(): List<Any?> = listOf(
        workspace, kernel, kernel?.version, kernel?.historySize, dirtyFiles().toSet(),
        documentMap.mapValues { (_, doc) -> listOf(doc.version, doc.draft, doc.sourceEpoch, doc.dirty, doc.expectedStoreText) },
    )

    fun reloadDocumentFromDisk(doc: DocumentState, force: Boolean, knownText: String? = null) {
        if (knownText == null && !Files.exists(doc.file)) {
            if (doc.dirty) doc.externalConflict = true
            return
        }
        val modified = runCatching { Files.getLastModifiedTime(doc.file).toMillis() }.getOrDefault(0L)
        if (!force && modified == doc.diskModifiedMillis) return
        if ((doc.dirty || doc.draftDirty || doc.hasPendingSourceInput) && !force) {
            doc.externalConflict = true
            return
        }
        val text = knownText ?: runCatching { Files.readString(doc.file) }.getOrElse {
            onNotice?.invoke("読み込みに失敗しました: ${it.message}")
            return
        }
        runCatching { YamlIO.parseAll(text) }.onSuccess { docs ->
            doc.root = docs.firstOrNull() ?: YNode.Map()
            doc.extraDocs = docs.drop(1)
            doc.rawText = text
            doc.parseError = null
        }.onFailure {
            doc.root = null
            doc.extraDocs = emptyList()
            doc.rawText = text
            doc.parseError = it.message
        }
        doc.diskModifiedMillis = modified
        doc.expectedStoreText = text
        doc.externalConflict = false
        doc.version++
        doc.resetBaseline()  // 保存基準・dirty を取り直す
        doc.textOnly = doc.root == null
        doc.discardDraft()   // 下書きはディスクの内容に揃える（未保存の下書きは上で競合にしている）
    }

    fun acceptDiskVersion(doc: DocumentState) {
        runCatching {
            val text = readStoreText(doc)
            kernel?.takeIf { it.acceptsDocument(documentOf(doc.file)) }?.acceptStoreText(documentOf(doc.file), text)
            if (text == null) closeDocument(doc.file)
            else reloadDocumentFromDisk(doc, force = true, knownText = text)
            editVersion++
            indexVersion++
            onNotice?.invoke("ディスク版を読み込みました: ${relPath(doc.file)}")
        }.onFailure { onNotice?.invoke("読み込みに失敗しました: ${it.message}") }
    }

    fun keepLocalVersion(doc: DocumentState) {
        runCatching {
            val text = readStoreText(doc)
            kernel?.acknowledgeStoreText(documentOf(doc.file), text)
            doc.expectedStoreText = text
            doc.externalConflict = false
            doc.diskModifiedMillis = runCatching { Files.getLastModifiedTime(doc.file).toMillis() }.getOrDefault(0L)
            onNotice?.invoke("次回保存で編集内容を上書きします: ${relPath(doc.file)}（再度外部で変更された場合は停止します）")
        }.onFailure { onNotice?.invoke("上書きの許可に失敗しました: ${it.message}") }
    }

    fun readStoreText(doc: DocumentState): String? =
        FileSystemDocumentStore(workspace ?: error("ワークスペース未選択")).read(documentOf(doc.file))?.text

    /**
     * 周期処理・ファイル監視から呼び、外部での作成・変更・削除を検出する。
     * eventHint = true（ファイル監視のイベント起点）では、更新時刻が不変でもセッションの content diff で既知文書の変更を確認する
     * （同一時刻での上書き等、時刻比較の盲点をカーネル検出で塞ぐ）。
     */
    fun pollExternalChanges(eventHint: Boolean = false) {
        val ws = workspace ?: return
        val cw = core ?: return
        val next = snapshotFiles(ws)
        if (next == workspaceStamp) {
            if (eventHint) applyContentDetectedChanges(ws, cw)
            return
        }
        val changed = (next.keys + workspaceStamp.keys).filter { next[it] != workspaceStamp[it] }
        if (hasRetainedEdits()) {
            reload()
            workspaceStamp = next
            return
        }
        changed.forEach { file -> documentMap[file]?.let { reloadDocumentFromDisk(it, force = false) } }
        // 増分か全再構築か（スキーマ変更時のみ全再構築）の振り分けは core に一元化されている
        cw.noteDiskChanges(
            changed = changed.filter { it in next.keys },
            removed = changed.filter { it !in next.keys },
        )
        mirrorCore(cw)
        syncSessionWithDisk(
            changed = changed.filter { it in next.keys },
            removed = changed.filter { it !in next.keys },
        )
        onFilesChanged?.invoke()
        documentMap.values.forEach { it.schema = schemaFor(it.file) }
        indexVersion++
        workspaceStamp = next
        onNotice?.invoke("外部での変更を検出しました（${changed.size} 件）")
    }

    /** 時刻が不変のときのカーネル検出: 既知文書の content diff で変更を取り込む。新規作成・削除は時刻比較の領分のまま。 */
    private fun applyContentDetectedChanges(ws: Path, cw: dev.toros.yde.app.core.Workspace) {
        val s = kernel ?: return
        val changedDocs = runCatching { s.externalChanges() }.getOrNull().orEmpty()
        if (changedDocs.isEmpty()) return
        if (hasRetainedEdits()) { reload(); return }
        val files = changedDocs.map { ws.resolve(it.value).normalize() }
        files.forEach { file ->
            documentMap[file]?.let { doc ->
                // 時刻不変のため force で取り直す。未保存編集がある場合は上書きせず競合として提示する
                if (doc.dirty || doc.draftDirty || doc.hasPendingSourceInput) doc.externalConflict = true
                else reloadDocumentFromDisk(doc, force = true)
            }
        }
        cw.noteDiskChanges(changed = files)
        mirrorCore(cw)
        syncSessionWithDisk(changed = files)
        documentMap.values.forEach { it.schema = schemaFor(it.file) }
        indexVersion++
        onNotice?.invoke("外部での変更を検出しました（${files.size} 件）")
    }

    // ---------------------------------------------------------------- カーネルの書き込み後の作り直し

    /** 入力待ちの下書きを、キャッシュ順でなく入力順に反映する。解析待ちの背景処理は採用時の世代検査で無効になる。 */
    fun flushSourceDrafts() {
        documentMap.values.mapNotNull { doc -> doc.sourceParseRequest()?.let { doc to it } }
            .sortedBy { (_, request) -> request.inputOrder }
            .forEach { (doc, request) -> doc.applyParsed(request, dev.toros.yde.app.core.EditBuffer.parse(request.text)) }
    }

    /** 解析待ちを移動前の入力へ含めてから、連続入力のまとまりを区切る。 */
    fun finishEditGroup() {
        flushSourceDrafts()
        kernel?.endEditGroup()
    }

    /**
     * セッションの working 状態から開いている文書の木を取り直す（Undo/Redo・適用の追随）。
     * セッションの木は共有せず deep copy で写す（ADR-0003。殻側の編集はセッションを汚さない）。
     */
    fun refreshDocsFromSession(ids: Collection<DocumentId>) {
        val s = kernel ?: return
        val ws = workspace ?: return
        val documents = s.snapshot.documents
        ids.distinct().forEach { id ->
            val file = ws.resolve(id.value).normalize()
            val doc = documentMap[file] ?: return@forEach
            val working = documents[id]
            if (working == null) {
                // undo で作成が巻き戻された等、文書が消えた場合は閉じる
                closeDocument(file)
                return@forEach
            }
            val roots = working.document.documents.map { it.deepCopy() }
            doc.root = roots.firstOrNull() ?: YNode.Map()
            doc.extraDocs = roots.drop(1)
            doc.parseError = null
            doc.version++
            doc.refreshDirty()
        }
    }

    /**
     * セッション書込（リファクタリング適用・一括更新）後の追随: reload しない＝履歴を保持したまま、
     * 開いている文書の木・ディスクの印・索引を更新する。
     */
    fun refreshAfterSessionWrite(ids: Collection<DocumentId>) {
        val ws = workspace ?: return
        refreshDocsFromSession(ids)
        val files = ids.map { ws.resolve(it.value).normalize() }
        files.forEach { f ->
            documentMap[f]?.let { d ->
                // 保存を伴う経路だけで比較基準を更新する。通常の Undo は保存済み内容を変えない。
                kernel?.storedText(documentOf(f))?.let { d.rawText = it }
                d.expectedStoreText = kernel?.storedText(documentOf(f))
                d.diskModifiedMillis = runCatching { Files.getLastModifiedTime(f).toMillis() }.getOrDefault(0L)
                d.externalConflict = false
            }
        }
        core?.let { cw ->
            // スキーマ変更時の全再構築への振り分けは core に一元化されている
            cw.noteDiskChanges(
                changed = files.filter { Files.exists(it) },
                removed = files.filterNot { Files.exists(it) },
            )
            mirrorCore(cw)
            documentMap.values.forEach { it.schema = schemaFor(it.file) }
        }
        // 文書の作成・削除でディレクトリ構成が変わり得るため、ツリーの供給も更新する
        onFilesChanged?.invoke()
        workspaceStamp = snapshotFiles(ws)
        indexVersion++
        editVersion++
    }

    // ---------------------------------------------------------------- 保存

    /** 保存の結果。needsTextSaveConfirmation は「壊れたまま保存」の確認が要る（スキーマ文書）。saved は実際に書いた。 */
    class SaveOutcome(val ok: Boolean, val message: String? = null, val needsTextSaveConfirmation: Boolean = false, val saved: Boolean = false)

    /** すべて保存の結果。messages は文書ごとの知らせ、textSaveConfirmations は確認待ちになった文書。 */
    class SaveAllOutcome(val messages: List<String>, val failures: List<String>, val textSaveConfirmations: List<DocumentState>)

    fun checkSave(doc: DocumentState) {
        val id = documentOf(doc.file)
        val s = kernel
        if (s != null && s.hasDocument(id)) s.checkSave(id)
        else FileSystemDocumentStore(workspace ?: error("ワークスペース未選択")).checkUnchanged(id, doc.expectedStoreText)
    }

    /** 保存前の競合検査。失敗なら理由を返す（競合なら文書に印を付ける）。 */
    fun prepareSave(doc: DocumentState): String? = runCatching { checkSave(doc) }.fold(
        onSuccess = { null },
        onFailure = {
            if (it is StoreConflictException) doc.externalConflict = true
            "保存に失敗しました: ${it.message}"
        },
    )

    /** `_schemas` の文書か（壊れたまま保存すると、そのスキーマを使う全文書に波及する）。 */
    fun isSchemaDocument(file: Path): Boolean = layout.zoneOf(relPath(file)) == WorkspaceLayout.Zone.SCHEMAS

    /** 未保存の文書（カーネルの dirty と削除予約、開いている文書の dirty と壊れた下書き）。 */
    fun dirtyFiles(): Set<Path> =
        kernel?.dirtyDocuments().orEmpty().mapTo(linkedSetOf(), ::fileOf).also { files ->
            documentMap.values.filter { it.dirty || it.draftDirty }.forEach { files.add(it.file) }
        }

    /**
     * 1 文書の保存。stampedBefore は「すべて保存」が先にまとめて入れた自動の値の説明（null なら、ここで 1 文書分を入れる）。
     * 自動の値が入るのは保存の直前で、履歴 1 件（Undo で日付と版が戻る）。入った項目は知らせに含める。
     */
    fun save(doc: DocumentState, stampedBefore: Map<DocumentId, String>? = null, confirmedTextSave: Boolean = false): SaveOutcome {
        doc.flushDraft()   // 入力待ちのソースを取り込んでから
        kernel?.endEditGroup()
        prepareSave(doc)?.let { return SaveOutcome(false, it) }
        if (doc.draftBroken) {
            // 壊れたまま保存。保存で止めず、文書をテキスト文書にして知らせる。スキーマ文書は波及が大きいので確認する
            if (doc.textOnly && !doc.draftDirty) return SaveOutcome(true)
            if (!confirmedTextSave && isSchemaDocument(doc.file)) return SaveOutcome(true, needsTextSaveConfirmation = true)
            return saveDraftAsText(doc)
        }
        val stamped = stampedBefore ?: stampAutoValues(listOf(doc))
        return runCatching {
            val s = kernel
            val root = doc.root
            val id = documentOf(doc.file)
            // 書込はセッション（DocumentStore ポート）へ集約する（ADR-0005）。working を編集内容で置き換えて保存（replaceDocument は履歴を積まない）。
            // セッションが文書として扱わないパス（構成ファイル等）・パース不能文書・セッション不在は従来の直接書込。
            val viaSession = s != null && root != null && s.hasDocument(id)
            if (viaSession) {
                val replaced = s!!.replaceDocument(id, YDocument(id, listOf(root!!) + doc.extraDocs))
                check(replaced.ok) { replaced.message ?: "セッションへの反映に失敗しました" }
                val saved = s.save(setOf(id))
                if (id in saved.conflicts) throw StoreConflictException(id)
                check(saved.ok) { saved.failures[id] ?: "書込に失敗しました" }
            } else {
                checkSave(doc)
                Files.writeString(doc.file, doc.canonicalText())
            }
            viaSession
        }.fold(
            onSuccess = { viaSession ->
                doc.markSaved()
                doc.rawText = doc.canonicalText()
                doc.expectedStoreText = doc.rawText
                doc.diskModifiedMillis = Files.getLastModifiedTime(doc.file).toMillis()
                doc.externalConflict = false
                workspace?.let { workspaceStamp = snapshotFiles(it) }
                // 保存したファイルだけ索引を差し替える。スキーマ保存時の全再構築への振り分けは core が行う
                core?.let { cw ->
                    cw.noteDiskChanges(listOf(doc.file))
                    mirrorCore(cw)
                    // セッション経由の保存では replaceDocument / save が既に反映済み（二重同期しない）
                    if (!viaSession) syncSessionWithDisk(changed = listOf(doc.file))
                    // スキーマ本体だけでなく派生スキーマの導出元文書の保存でもスキーマ集合が変わるため、常に割り当て直す
                    documentMap.values.forEach { it.schema = schemaFor(it.file) }
                    indexVersion++
                }
                val auto = stamped[documentOf(doc.file)]
                val message = if (auto == null) "保存しました: ${relPath(doc.file)}"
                else "保存しました: ${relPath(doc.file)}（自動の値を設定 → $auto。Ctrl+Z で戻せます）"
                SaveOutcome(true, message, saved = true)
            },
            onFailure = {
                if (it is StoreConflictException) doc.externalConflict = true
                SaveOutcome(false, "保存に失敗しました: ${it.message}")
            },
        )
    }

    /**
     * 壊れた下書きをそのまま書く（「壊れたまま保存」）。文書はテキスト文書になり、ディスク上で解析できないファイルと同じ扱い
     * （索引・検証・ビュー・エージェントの対象外）。セッションの履歴は消さない。直して保存すれば文書集合へ戻る。
     */
    fun saveDraftAsText(doc: DocumentState): SaveOutcome {
        val text = doc.draft
        val id = documentOf(doc.file)
        return runCatching {
            val s = kernel
            if (s != null && s.acceptsDocument(id)) {
                val r = if (s.hasDocument(id)) s.saveAsText(id, text) else s.saveAsText(id, text, doc.expectedStoreText)
                if (r.externalConflict) throw StoreConflictException(id)
                check(r.ok) { r.message ?: "書込に失敗しました" }
            } else {
                checkSave(doc)
                Files.writeString(doc.file, text)
            }
        }.fold(
            onSuccess = {
                doc.enterTextOnly(text)
                doc.expectedStoreText = text
                doc.diskModifiedMillis = runCatching { Files.getLastModifiedTime(doc.file).toMillis() }.getOrDefault(0L)
                doc.externalConflict = false
                workspace?.let { workspaceStamp = snapshotFiles(it) }
                core?.let { cw ->
                    cw.noteDiskChanges(listOf(doc.file))
                    mirrorCore(cw)
                    documentMap.values.forEach { it.schema = schemaFor(it.file) }
                }
                editVersion++
                indexVersion++
                SaveOutcome(true, "壊れたまま保存しました: ${relPath(doc.file)}（直して保存するまで索引・検証の対象外です）", saved = true)
            },
            onFailure = {
                if (it is StoreConflictException) doc.externalConflict = true
                SaveOutcome(false, "保存に失敗しました: ${it.message}")
            },
        )
    }

    /**
     * 保存時に自動で入る値（architecture/auto-values.md）: 編集内容をセッションへ写してから、保存済みの内容との違いで更新日・版を入れる
     * （セッションの 1 トランザクション）。入った文書は木をセッションから写し直す。戻り値は文書ごとの入れた値の説明（空＝何も入らなかった）。
     */
    fun stampAutoValues(docs: List<DocumentState>): Map<DocumentId, String> {
        val s = kernel ?: return emptyMap()
        val ids = docs.mapNotNull { doc ->
            val root = doc.root ?: return@mapNotNull null
            val id = documentOf(doc.file)
            if (!s.hasDocument(id)) return@mapNotNull null
            val replaced = s.replaceDocument(id, YDocument(id, listOf(root) + doc.extraDocs))
            if (!replaced.ok) return@mapNotNull null
            id
        }
        if (ids.isEmpty()) return emptyMap()
        val applied = withHistoryOrigin(s) { s.applyAutoValues(ids, java.time.LocalDateTime.now()) }
        if (!applied.result.ok || applied.isEmpty) return emptyMap()
        refreshDocsFromSession(applied.plans.keys)
        return applied.plans.mapValues { (_, plan) -> plan.describe() }
    }

    /** すべて保存。未表示のまま Undo された文書も通常の保存経路（自動値・表示追随）を通す。 */
    fun saveAll(): SaveAllOutcome {
        finishEditGroup()
        val s = kernel
        val pendingDeletes = s?.let { it.dirtyDocuments().filterNot(it::hasDocument).toSet() }.orEmpty()
        val dirty = dirtyFiles().filterNot { documentOf(it) in pendingDeletes }.map(::ensureDocument)
        // 自動の値（更新日・版）は全部の文書で 1 つのトランザクション＝Undo 1 回で戻る。その後に 1 文書ずつ書き出す（壊れた下書きは木が無いので対象外）
        val messages = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val confirmations = mutableListOf<DocumentState>()
        val ready = dirty.filter { doc -> prepareSave(doc)?.let { failures += it; false } ?: true }
        val stamped = stampAutoValues(ready.filter { !it.draftBroken })
        ready.forEach { doc ->
            val o = save(doc, stampedBefore = stamped)
            o.message?.let { messages += it }
            if (o.needsTextSaveConfirmation) confirmations += doc
            if (!o.ok) failures += o.message.orEmpty()
        }
        if (s != null && pendingDeletes.isNotEmpty()) {
            val saved = s.save(pendingDeletes)
            refreshAfterSessionWrite(saved.deleted)
            if (!saved.ok) failures += saved.failures.map { (id, message) -> "${id.value}: $message" }
        }
        return SaveAllOutcome(messages, failures, confirmations)
    }

    // ---------------------------------------------------------------- 履歴の一手の論理（段取りの表示・スレッド・画面移動は殻）

    /** 閲覧専用モード（編集の UI を封じるのは殻。ここでは、操作の記録を戻すときに保存まで行う判定に使う）。 */
    var readOnlyMode: Boolean = false

    /** 操作の適用で積んだ履歴の id（閲覧専用のときの取り消し・やり直しは、これらだけ保存まで行う）。 */
    val actionRecordIds = mutableSetOf<Long>()

    /** 解析できない下書きを捨てて戻すかの確認待ち（殻が確認を出す）。確認の間に変わっていないかは下書きの印で見る。 */
    class PendingHistory internal constructor(
        val undo: Boolean,
        val docs: List<DocumentState>,
        internal val kernel: WorkspaceSession,
        internal val recordId: Long,
        internal val version: Long,
    ) {
        private val drafts = docs.associateWith { Triple(it.draft, it.sourceEpoch, it.sourceInputOrder) }
        internal fun matchesDrafts(): Boolean = drafts.all { (doc, stamp) -> stamp == Triple(doc.draft, doc.sourceEpoch, doc.sourceInputOrder) }
    }

    /** 準備できた一手。visibleDocument は実行前に表示していた文書（通知の判断に使う）。 */
    class HistoryStep internal constructor(val kernel: WorkspaceSession, val record: ChangeRecord, val undo: Boolean, val visibleDocument: DocumentId?) {
        val version: Long = kernel.version
    }

    /** 一手の準備の結果。 */
    sealed class HistoryPreparation {
        /** 戻す記録が無い。 */
        object None : HistoryPreparation()
        /** 拒んだ（理由を利用者へ）。 */
        class Refused(val message: String) : HistoryPreparation()
        /** 解析できない下書きを捨ててよいかの確認が要る。 */
        class Confirm(val pending: PendingHistory) : HistoryPreparation()
        class Ready(val step: HistoryStep) : HistoryPreparation()
    }

    /**
     * Undo / Redo の 1 手の準備。入力待ちの下書きを反映し、対象の文書に解析できない下書きがあれば「捨てて戻すか」の確認を求め、
     * 壊れたまま保存された文書が含まれていれば拒む（文書集合に無いので戻せない）。確認済みなら壊れた下書きを捨てて進む。
     */
    fun prepareHistoryStep(undo: Boolean, confirmation: PendingHistory?, visibleDocument: DocumentId?): HistoryPreparation {
        val s = kernel ?: return HistoryPreparation.None
        flushSourceDrafts()
        val record = if (undo) s.peekUndo() else s.peekRedo()
        if (confirmation != null && (confirmation.kernel !== s || confirmation.version != s.version ||
                confirmation.recordId != record?.id || !confirmation.matchesDrafts())) {
            return HistoryPreparation.Refused("履歴または下書きが変わったため中止しました。もう一度操作してください")
        }
        record ?: return HistoryPreparation.None
        val checked = s.checkHistory(undo)
        if (!checked.ok) return HistoryPreparation.Refused(checked.message ?: "履歴を適用できません")
        val targets = historyTargets(record).map(DocumentId::value).toSet()
        val docs = documentMap.values.filter { relPath(it.file) in targets }
        val textOnly = docs.filter { it.textOnly }
        if (textOnly.isNotEmpty()) {
            return HistoryPreparation.Refused(textOnly.joinToString("・") { relPath(it.file) } +
                " は壊れたまま保存されているため、この操作は戻せません（ソースで直して保存すると戻せます）")
        }
        val broken = docs.filter { it.draftBroken }
        if (broken.isNotEmpty() && confirmation == null) return HistoryPreparation.Confirm(PendingHistory(undo, broken, s, record.id, s.version))
        broken.forEach { it.discardDraft() }
        return HistoryPreparation.Ready(HistoryStep(s, record, undo, visibleDocument))
    }

    /** カーネルの操作だけを行う（殻はファイル配置の履歴を IO スレッドから呼ぶ）。鮮度が変わっていれば中止する。 */
    fun applyHistoryStep(step: HistoryStep): dev.toros.yde.workspace.TransactionResult {
        val s = step.kernel
        val top = if (step.undo) s.peekUndo() else s.peekRedo()
        if (kernel !== s || step.version != s.version || step.record.id != top?.id) {
            return dev.toros.yde.workspace.TransactionResult(false, "履歴が変わったため中止しました。もう一度操作してください", s.version)
        }
        return runCatching { if (step.undo) s.undo() else s.redo() }.getOrElse {
            dev.toros.yde.workspace.TransactionResult(false, it.message ?: "履歴を適用できません", s.version)
        }
    }

    /** 一手の適用後の文書側の追随の結果。arrangement があれば殻がツリーと表示の追随を行う。message は保存の失敗。 */
    class HistoryFollowUp(val ids: List<DocumentId>, val arrangement: dev.toros.yde.workspace.FileArrangementPlan?, val message: String?)

    /**
     * Undo/Redo 適用後の文書側の追随: 影響文書の木をセッションの working から取り直し、派生を進める。
     * 文書の作成・削除を含む履歴は、ファイルの有無までその場で確定する（作成の取り消しには保存の手段が無く、
     * 削除予約のままではディスクとセッションが食い違うため）。ファイル配置の履歴は殻の追随に任せる。
     */
    fun afterHistoryStep(record: ChangeRecord, undo: Boolean): HistoryFollowUp {
        val s = kernel
        s?.fileArrangement(record.id)?.let { plan -> return HistoryFollowUp(historyTargets(record), plan, null) }
        val ids = historyTargets(record)
        val fileLevel = (record.forward.operations + record.inverse.operations)
            .filter { it is WorkspaceOperation.CreateDocument || it is WorkspaceOperation.DeleteDocument }
            .flatMap(::operationIds)
            .toSet()
        // 閲覧専用のときの操作の取り消し・やり直しは、戻した内容を保存まで行う（未保存の編集を残さない。architecture/actions.md）
        val writeThrough = readOnlyMode && record.id in actionRecordIds
        var message: String? = null
        if (s != null && (fileLevel.isNotEmpty() || writeThrough)) {
            val saved = s.save(if (writeThrough) ids.toSet() else fileLevel)
            if (!saved.ok) message = "ファイルの反映に失敗しました: ${saved.failures.entries.first().let { "${it.key.value}: ${it.value}" }}"
            refreshDocsFromSession(ids)
            refreshAfterSessionWrite(saved.written + saved.deleted)
            saved.conflicts.forEach { id -> documentMap[fileOf(id)]?.externalConflict = true }
        } else {
            refreshDocsFromSession(ids)
            editVersion++
        }
        return HistoryFollowUp(ids, null, message)
    }

    /** 戻した木とソースを揃える（次のキー入力や反映待ちの解析が来る前に）。戻り値は利用者への知らせ。 */
    fun syncSourcesAfterHistory(record: ChangeRecord): List<String> {
        val synced = historyTargets(record).mapNotNull { id ->
            documentMap[fileOf(id)]?.let { id to it.syncSourceFromTree(preferHistory = true) }
        }
        val conflicts = synced.sumOf { it.second.conflicts }
        val normalized = synced.filter { it.second.normalized }.map { it.first.value }
        val notices = mutableListOf<String>()
        if (conflicts > 0) notices += "下書きと食い違う変更が $conflicts か所あり、下書きを優先しました（「整形」で木に揃えられます）"
        if (normalized.isNotEmpty()) notices += "履歴の内容に揃えるためソースを書式調整しました: ${normalized.joinToString("・")}"
        return notices
    }

    /** 履歴 1 件の 1 行要約: 変更の意図＋対象ファイル（意図に含まれていれば列挙を省く）。 */
    fun historySummary(record: ChangeRecord?): String? {
        record ?: return null
        val names = historyTargets(record).map(DocumentId::value)
        val desc = record.intent.description.ifBlank { "${names.size} ファイルの変更" }
        val listed = names.take(3).joinToString("・") + (if (names.size > 3) " ほか${names.size - 3}件" else "")
        return if (names.all { it in desc }) desc else "$desc（$listed）"
    }

    fun historyTargets(record: ChangeRecord): List<DocumentId> =
        (record.forward.operations + record.inverse.operations).flatMap(::operationIds).distinct()

    fun operationIds(op: WorkspaceOperation): List<DocumentId> = when (op) {
        is WorkspaceOperation.EditDocument -> listOf(op.documentId)
        is WorkspaceOperation.CreateDocument -> listOf(op.documentId)
        is WorkspaceOperation.ReplaceDocument -> listOf(op.documentId)
        is WorkspaceOperation.DeleteDocument -> listOf(op.documentId)
        is WorkspaceOperation.MoveDocument -> listOf(op.from, op.to)
    }

    /** 入力の反映待ちがある文書（セッションが文書として扱うものだけ）。 */
    fun pendingSourceDocuments(): List<DocumentState> = documentMap.values.filter {
        it.hasPendingSourceInput && kernel?.acceptsDocument(documentOf(it.file)) == true
    }

    fun canUndo(): Boolean = kernel?.canUndo() == true || pendingSourceDocuments().isNotEmpty()

    fun canRedo(): Boolean = kernel?.canRedo() == true && pendingSourceDocuments().isEmpty()

    /** 次に Ctrl+Z で戻る変更の予告。セッション不在時は null。 */
    fun undoPreview(): String? {
        pendingSourceDocuments().maxByOrNull { it.sourceInputOrder }?.let { return "ソース入力を反映して元に戻す: ${relPath(it.file)}" }
        return historySummary(kernel?.peekUndo())
    }

    fun redoPreview(): String? {
        if (pendingSourceDocuments().isNotEmpty()) return "ソース入力の反映待ちです（新しい編集でやり直しは破棄されます）"
        return historySummary(kernel?.peekRedo())
    }

    // ---------------------------------------------------------------- 検証・検索・一括変更（未保存の編集を重ねた「見たまま」）

    /**
     * ワークスペース全体の検証（UX-44）: 索引対象の全文書を「見たまま」（未保存編集込みのオーバーレイ索引＋unsavedFirstLoader）で検証し、
     * 指摘のあるファイルだけ返す。ヘッドレス経路（CLI `validate`）はディスク基準のまま（architecture/index-references.md「索引の二層」）。
     */
    fun validateWorkspace(): List<Pair<Path, List<Issue>>> {
        val cw = core ?: return emptyList()
        val loader = unsavedFirstLoader()
        val overlay = overlayIndex()
        return cw.files.mapNotNull { file ->
            val cached = documentMap[file]
            val issues = Validator.validate(
                cached?.root ?: loader(file), cached?.parseError, documentOf(file),
                cached?.schema ?: cw.schemaFor(file), overlay,
                indexed = true, allSchemas = schemas,
                loader = unsavedFirstTree(), relations = relations, enforcement = enforcement,
                shareEvaluations = true,  // 未保存の編集を重ねた索引と、未保存を優先する loader
                files = filePolicy, fileExists = ::fileExists,
            )
            if (issues.isEmpty()) null else file to issues
        }
    }

    /** コマンドパレットの本文検索（R17-04）: 構造検索を未保存編集込みの「見たまま」で実行する。 */
    fun searchContent(text: String, limit: Int = 10): List<SearchHit> {
        val cw = core ?: return emptyList()
        if (text.isBlank()) return emptyList()
        return Search.run(cw, SearchQuery(text = text, limit = limit), index = overlayIndex(), loader = unsavedFirstLoader())
    }

    /**
     * 一覧で選んだファイルのルート直下スカラーを一括変更する（R8-05: L-07）。ID・予約キー・複合値は対象外。書換は core.WorkspaceRecords。
     * セッション経路（D-11）は 1 トランザクション適用→保存（部分適用なし）で、reload しない＝一括変更全体が Undo 1 回で戻る。
     * セッション不在時はファイル単位の直接書換→全再読込（閉じた文書は onDocumentClosed で殻へ知らせる）。
     */
    fun bulkUpdate(files: Set<Path>, key: String, value: String): Int {
        require(key.isNotBlank() && !key.startsWith("_") && key != "id") { "一括変更できない項目です: $key" }
        require(files.none { documentMap[it]?.dirty == true }) { "未保存のファイルが含まれています" }
        val s = kernel
        if (s != null) {
            val ids = files.sortedBy { it.toString() }.map(::documentOf)
            val count = withHistoryOrigin(s) { WorkspaceRecords.bulkUpdateViaSession(s, ids, key, value) }
            refreshAfterSessionWrite(ids)
            return count
        }
        val count = WorkspaceRecords.updateRootScalar(files, key, value)
        files.forEach(::closeDocument)
        reload()
        return count
    }

    /** 一括編集の対象にできるキーの候補（ルート直下のスカラーキー。id・予約キーは除く）。 */
    fun bulkKeyCandidates(): List<String> = core?.let { WorkspaceRecords.bulkKeyCandidates(it, unsavedFirstLoader()) }.orEmpty()

    /**
     * スキーマ推測（R11-01）: glob に一致する文書（未保存編集を優先）からドラフトを推測し、`_schemas/<id>.yaml` として作る。
     * 仕上げはスキーマ設計画面。作った文書を開くのは殻。
     */
    fun inferSchema(glob: String, id: String): Path {
        val ws = workspace ?: error("ワークスペースが開かれていません")
        require(id.isNotBlank() && '/' !in id && '\\' !in id) { "使用できないスキーマIDです: $id" }
        val regex = globToRegex(glob)
        val loader = unsavedFirstLoader()
        val samples = yamlFiles
            .map { relPath(it) to it }
            .filter { (rel, _) -> layout.inLedger(rel) && regex.matches(rel) }
            .map { (rel, file) -> SchemaInference.Sample(rel, loader(file)) }
        val draft = SchemaInference.infer(samples, schemaId = id, match = glob)
        val file = ws.resolve(layout.schemasDir).resolve("$id.yaml")
        require(!Files.exists(file)) { "既にスキーマファイルが存在します: ${relPath(file)}" }
        return createDocument(file, draft, "スキーマの推測: $id")
    }

    /**
     * エンティティの関連文書（R24-02）: 出現ファイル→参照先→参照元の順で重複排除して返す。
     * AI 生成の「関連文書」候補（内容の文脈）に使う。exclude は生成対象の文書自身。
     */
    fun entityRelatedFiles(id: String, exclude: Path): List<Path> {
        val overlay = overlayIndex()
        val occurrenceFiles = overlay.occurrences(id).map { it.document }
        val refTargets = overlay.outgoingRefs(id).mapNotNull { r ->
            if (r.targetKind == RefTarget.DOCUMENT) overlay.graph.documents[r.targetId]?.document
            else overlay.occurrences(r.targetId).firstOrNull()?.document
        }
        val backlinkSources = overlay.backlinksTo(setOf(id)).map { it.document }
        return (occurrenceFiles + refTargets + backlinkSources).distinct().map(::fileOf).filter { it != exclude }
    }

    // ---------------------------------------------------------------- リファクタリングと変更セット（計画も適用もカーネル。1 トランザクション）

    private fun refactorKernel(): WorkspaceSession = kernel ?: error("ワークスペースが開かれていません")

    /** リファクタリングの計画（適用しない）。計画はセッションの working 状態＝「見たまま」が基準（B-1）。 */
    fun planRefactoring(request: RefactoringRequest): RefactoringPlan = refactorKernel().planRefactoring(request)

    /**
     * エージェント変更セットの適用（A-2b）: 検証・競合判定は従来（Workspace.apply）と同一で、書込はセッション（1 トランザクション・atomic 書込）へ
     * 集約する（D-04）。セッション不在時は直接書込へフォールバック。
     */
    fun applyAgentChanges(changeSet: ChangeSet, requireValid: Boolean = false): ChangeSetResult {
        val cw = core ?: error("ワークスペースが開かれていません")
        val s = kernel
        return if (s != null) cw.applyViaSession(s, changeSet, requireValid = requireValid) else cw.apply(changeSet, requireValid = requireValid)
    }

    /**
     * リファクタリング計画の適用（B-1・RB-01）: 1 トランザクションとして適用→保存する。reload しない＝全体が Undo 1 回で戻る（D-12）。
     * 対象に未保存編集があれば拒む（適用→保存が未保存編集まで書いてしまうのを防ぐ）。戻り値は書き込んだ文書。
     */
    fun applyRefactoring(plan: RefactoringPlan): List<DocumentId> {
        val s = refactorKernel()
        val ids = (plan.changes.map { it.document } + plan.moves.flatMap { listOf(it.first, it.second) }).distinct()
        require(ids.map(::fileOf).none { documentMap[it]?.dirty == true }) { "対象に未保存の変更があります。先に保存してください" }
        val applied = withHistoryOrigin(s) { s.applyRefactoring(plan) }
        require(applied.ok) { applied.message ?: "適用に失敗しました" }
        val saved = s.save(ids.toSet())
        require(saved.ok) { saved.failures.entries.firstOrNull()?.let { "${it.key.value}: ${it.value}" } ?: "保存に失敗しました" }
        refreshAfterSessionWrite(ids)
        // 適用は 1 トランザクションで保存まで完了する（● が付かない理由の明示。UX-93 / D-12）
        onNotice?.invoke("リファクタリングを適用して保存しました（${ids.size} ファイル。Ctrl+Z で1回で戻せます）")
        return ids
    }

    // ---------------------------------------------------------------- 文書の作成（単一経路。作った文書を開くのは殻）

    /**
     * 文書の新規作成の単一経路（P29 Phase 4・RP-03／意図的差分 D-18）: セッションの 1 トランザクション（CreateDocument）として適用→保存し、
     * 全体の再読込をしない＝Ctrl+Z で作成を取り消せるうえ、それまでの Undo 履歴も保たれる。
     * セッションが文書として扱わないパス（隠しパス等）・セッション不在時だけ、直接書込＋再読込へフォールバックする。
     */
    fun createDocument(file: Path, root: YNode, intent: String, now: LocalDateTime = LocalDateTime.now()): Path {
        val ws = workspace ?: error("ワークスペースが開かれていません")
        val target = file.normalize()
        require(target.startsWith(ws)) { "ワークスペース外には作成できません: $target" }
        require(!Files.exists(target)) { "既に同名のファイルが存在します: ${relPath(target)}" }
        val id = documentOf(target)
        // 保存時に自動で入る値（auto）: 新しい文書は全部が新しい要素＝空の作成日・更新日・版を今の値で埋める（作成と同じ 1 件の履歴）
        schemaFor(target)?.let { SemanticAutoValues.stamp(it.tree, null, root, now) }
        val s = kernel
        if (s != null && !s.hasDocument(id) && !WorkspaceReader.hiddenPath(id.value, s.layout)) {
            val applied = withHistoryOrigin(s) {
                s.apply(WorkspaceTransaction(
                    baseVersion = s.version,
                    intent = ChangeIntent(intent),
                    operations = listOf(WorkspaceOperation.CreateDocument(id, YDocument(id, listOf(root)))),
                ))
            }
            require(applied.ok) { applied.message ?: "文書を作成できませんでした" }
            val saved = s.save(setOf(id))
            require(saved.ok) { saved.failures[id] ?: "書込に失敗しました" }
            refreshAfterSessionWrite(listOf(id))
            onNotice?.invoke("作成しました: ${id.value}（Ctrl+Z で取り消せます）")
        } else {
            Files.createDirectories(target.parent)
            Files.writeString(target, YamlIO.dump(root))
            reload()
        }
        return target
    }

    /** 新規 YAML: スキーマの初期値（core.Views.defaultTree。自動の値は今の日付・版 1）で作る。 */
    fun createYaml(parent: Path, name: String, now: LocalDateTime = LocalDateTime.now()): Path {
        val fileName = name.trim().let { if (it.endsWith(".yaml") || it.endsWith(".yml")) it else "$it.yaml" }
        require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName) { "使用できない名前です" }
        val file = parent.resolve(fileName)
        require(!Files.exists(file)) { "同名のファイルが存在します" }
        val initial = schemaFor(file)?.let { Views.defaultTree(it.root, it, now) } ?: YNode.Map()
        return createDocument(file, initial, "新規作成: ${relPath(file)}", now)
    }

    /** ビューグリッドの行から詳細文書を作る（P6）。作成先とスキーマ初期値は core.Views.detailScaffold が決める。 */
    fun createViewDetail(view: ViewDef, id: String): Path = createScaffold(Views.detailScaffold(view, scaffoldSource(id), schemas))

    /**
     * 新しい文書の元になる要素（P31）: ID の定義出現（未保存の編集を優先した「見たまま」の索引から）と、そのタイトル・木の読み手・グラフ。
     * filePattern の穴（`{name}` など）と scaffold のひな型は、この要素の値で埋まる。
     */
    private fun scaffoldSource(id: String): ScaffoldSource {
        val overlay = overlayIndex()
        return ScaffoldSource.of(id, overlay.occurrences(id), unsavedFirstTree(), overlay.graph)
    }

    /**
     * ビューの newRow（ファイルモード）: 入力された ID で詳細文書そのものを作って行にする（R15-01）。
     * 行の集合はディスク基準の索引から解決されるため、書き込んだ時点でグリッドに行が現れる。
     */
    fun addViewRowFile(view: ViewDef, id: String): Path {
        val pattern = view.newRow?.filePattern ?: error("このビューの newRow はファイルモードではありません")
        val trimmed = id.trim()
        // クエリ行（rows: match。R22-02）では行の成立がパターン照合に依存するため、ID 形式の事前検査は行わない
        view.rows?.let { rowsDef ->
            require(idMatchesPattern(trimmed, rowsDef.idPattern)) { "ID「$trimmed」はこのビューの行になりません（idPattern: ${rowsDef.idPattern}）" }
        }
        index.entries[trimmed]?.firstOrNull()?.let { error("ID「$trimmed」は既に存在します: ${it.document.value}") }
        // 行がまだ無いので、元は ID だけ（newRow の filePattern に書ける穴は {id} だけ＝定義の段階で保証）
        return createScaffold(Views.scaffold(pattern, ScaffoldSource.idOnly(trimmed), schemas))
    }

    /**
     * カバレッジ（detailIn）不足の指摘から詳細文書を作る（R28-05a・UX-97 の導線）。作成先は詳細スキーマの filePattern（値の式。P31）、
     * 無ければ match から導く（単純な glob のみ。導けないスキーマはエラー）。穴は、その ID の定義出現（一覧側の要素）の値で埋まる。
     */
    fun createDetailForCoverage(schemaId: String, id: String): Path {
        val schema = schemas.firstOrNull { it.id == schemaId } ?: error("スキーマが見つかりません: $schemaId")
        val pattern = DocumentScaffolds.filePatternOf(schema.kernel)
            ?: error("スキーマ「$schemaId」の match（${schema.match}）から作成先を決められません（スキーマに filePattern を書くと決められます）")
        return createScaffold(Views.scaffold(pattern, scaffoldSource(id), schemas))
    }

    /** スキャフォールドを新規文書として作る（詳細作成・新規行ファイルモード共用）。 */
    private fun createScaffold(scaffold: DocumentScaffold): Path {
        val ws = workspace ?: error("ワークスペースが開かれていません")
        val file = ws.resolve(scaffold.rel).normalize()
        require(!Files.exists(file)) { "既に同名のファイルが存在します: ${scaffold.rel}" }
        return createDocument(file, scaffold.root, "文書の作成: ${scaffold.rel}")
    }

    /** 外部ソースの構造情報（core.SourceOutline）を YAML 文書として保存する。保存先はワークスペース相対。 */
    fun saveSourceOutline(source: Path, outline: SourceOutline.Outline, destination: String): Path {
        val ws = workspace ?: error("ワークスペース未選択")
        val target = WorkspacePaths.resolve(ws, destination)
        require(target.fileName.toString().let { it.endsWith(".yaml") || it.endsWith(".yml") }) { "保存先は YAML にしてください" }
        val root = YNode.Map(linkedMapOf(
            "source" to YNode.Scalar(relPath(source)), "language" to YNode.Scalar(outline.language),
            "symbols" to YNode.Seq(outline.symbols.map { symbol -> YNode.Map(linkedMapOf(
                "kind" to YNode.Scalar(symbol.kind), "name" to YNode.Scalar(symbol.name),
                "line" to YNode.Scalar(symbol.line), "owner" to YNode.Scalar(symbol.owner),
            )) }.toMutableList()),
        ))
        return createDocument(target, root, "ソースの構造情報を保存: ${relPath(source)}")
    }

    // ---------------------------------------------------------------- ファイルの配置（計画はカーネル。適用の前後の文書側。スレッドと画面の追随は殻）

    /** YAML ファイルとして移動の一級化（R21-03。カーネル MoveDocument）の対象か（台帳内・ディレクトリ以外）。 */
    private fun isMovableYaml(path: Path): Boolean {
        val name = path.fileName.toString()
        return !Files.isDirectory(path) && (name.endsWith(".yaml") || name.endsWith(".yml")) && isIndexed(path)
    }

    /**
     * 改名の影響プレビュー（R21-03。architecture/graph-query.md「エンティティ操作」）: パス ID 文書参照の書き換え・スキーマ match の変化・
     * ビュー定義の対象外れを、実行前にダイアログへ出すための 1 行リスト。
     */
    fun renameImpact(path: Path, newName: String): List<String> {
        if (core == null) return emptyList()
        val s = kernel ?: return emptyList()
        val trimmed = newName.trim()
        if (trimmed.isBlank() || '/' in trimmed || '\\' in trimmed || !isMovableYaml(path)) return emptyList()
        val target = path.resolveSibling(trimmed)
        if (Files.exists(target)) return emptyList()
        return runCatching {
            // 移動計画はカーネル（summary 先頭は見出し・以降が補足。スキーマ適用の変化と、ビュー定義が対象外れになる警告は warnings に入る）
            val plan = s.planRefactoring(RefactoringRequest.MoveDocument(documentOf(path), documentOf(target)))
            plan.summary.drop(1) + plan.warnings.map { "注意: $it" }
        }.getOrDefault(emptyList())
    }

    fun planFileMoves(paths: List<Path>, destination: Path, definitions: Boolean = true, permissions: Boolean = false): FileArrangementPlan {
        val ws = workspace ?: error("ワークスペース未選択")
        val s = kernel ?: error("セッション未選択")
        require(Files.isDirectory(destination) && destination.normalize().startsWith(ws)) { "移動先フォルダーがありません" }
        val moves = paths.map { PathMove(relPath(it), relPath(destination.resolve(it.fileName))) }
        return FileArrangementPlanner.plan(s, ws, moves, definitions, permissions)
    }

    fun planFileRename(path: Path, name: String, definitions: Boolean = true, permissions: Boolean = false): FileArrangementPlan {
        requireFileName(name)
        val ws = workspace ?: error("ワークスペース未選択")
        val s = kernel ?: error("セッション未選択")
        return FileArrangementPlanner.plan(s, ws, listOf(PathMove(relPath(path), relPath(path.resolveSibling(name.trim())))), definitions, permissions)
    }

    /** フォルダー作成の計画。戻り値は作るフォルダーと計画。 */
    fun planCreateDirectory(parent: Path, name: String): Pair<Path, FileArrangementPlan> {
        requireFileName(name)
        val target = parent.resolve(name.trim()).normalize()
        val ws = workspace ?: error("ワークスペース未選択")
        val s = kernel ?: error("セッション未選択")
        return target to FileArrangementPlanner.plan(s, ws, emptyList(), createDirectory = relPath(target))
    }

    /** 削除の計画（ごみ箱 `.yde-trash/` への移動。Undo で戻る）。 */
    fun planDelete(paths: List<Path>): FileArrangementPlan {
        val ws = workspace ?: error("ワークスペース未選択")
        val s = kernel ?: error("セッション未選択")
        val trash = ".yde-trash/" + UUID.randomUUID().toString()
        val moves = paths.mapIndexed { index, path -> PathMove(relPath(path), "$trash/$index/" + path.fileName) }
        return FileArrangementPlanner.plan(s, ws, moves, deleting = true)
    }

    private fun requireFileName(name: String) {
        require(name.isNotBlank() && name.trim() !in setOf(".", "..") && '/' !in name && '\\' !in name) { "使用できない名前です" }
    }

    /**
     * 配置変更の適用の前: ワークスペースが変わっていないことを確かめ、対象と参照元の下書きを反映し、未保存があれば拒む。
     * 戻り値は適用するカーネル（殻は withHistoryOrigin の中で、IO スレッドからでも applyFileArrangement を呼ぶ）。
     */
    fun prepareFileArrangement(plan: FileArrangementPlan): WorkspaceSession {
        val s = kernel ?: error("セッション未選択")
        require(plan.root == workspace) { "ワークスペースが変わりました" }
        val roots = plan.moves.flatMap { listOf(plan.root.resolve(it.from), plan.root.resolve(it.to)) } +
            plan.documentIds.map { plan.root.resolve(it.value) } + plan.texts.map { plan.root.resolve(it.document.value) }
        val docs = documentMap.filterKeys { file -> roots.any { file == it || file.startsWith(it) } }.values
        docs.forEach { it.flushDraft() }
        require(docs.none { it.dirty || it.draftDirty }) { "対象または参照元に未保存の変更があります。先に保存してください" }
        return s
    }

    /** 配置変更（適用・Undo・Redo）の後の文書側: 動いた文書を閉じ（殻へ知らせる）、書き込まれた文書と索引を作り直す。 */
    fun afterFileArrangement(plan: FileArrangementPlan, reverse: Boolean) {
        val ws = workspace ?: return
        val moves = if (reverse) plan.moves.map { PathMove(it.to, it.from) } else plan.moves
        documentMap.keys.filter { file -> moves.any { file == ws.resolve(it.from) || file.startsWith(ws.resolve(it.from)) } }
            .toList().forEach(::closeDocument)
        refreshAfterSessionWrite(plan.documentIds + plan.texts.filter { it.document.value.endsWith(".yaml") || it.document.value.endsWith(".yml") }.map { it.document })
    }

    // ---------------------------------------------------------------- 操作（型の操作とアクション）の論理（パネルの作業と表示は殻）

    /** 型の要素の候補（パネルの「対象」欄）: 上書き索引のうちその型の出現を持つ ID と、その 1 行要約。 */
    fun entityCandidates(type: String): List<Pair<String, String?>> =
        overlayIndex().entries.values.asSequence()
            .filter { occurrences -> occurrences.any { it.entityType == type } }
            .map { occurrences -> occurrences.first().id to occurrences.firstNotNullOfOrNull { it.title } }
            .sortedBy { it.first }
            .toList()

    /** 要素に使える操作（エンティティページの操作メニュー）。 */
    fun actionsForEntity(id: String): List<ActionEntry> =
        core?.let { cw -> runCatching { ActionPlanner.forEntity(cw.mirror, id) }.getOrDefault(emptyList()) }.orEmpty()

    /** 型に使える操作（台帳の「要素の操作」＝受け手をパネルで選ぶ入口と、未保存の行のメニュー）。 */
    fun actionsForEntityType(type: String): List<ActionEntry> =
        core?.let { cw -> runCatching { ActionPlanner.forEntityType(cw.mirror, type) }.getOrDefault(emptyList()) }.orEmpty()

    /** 文書に使える操作（文書ヘッダのメニュー）。 */
    fun actionsForDocument(file: Path): List<ActionEntry> =
        core?.let { cw -> runCatching { ActionPlanner.forDocument(cw.mirror, relPath(file)) }.getOrDefault(emptyList()) }.orEmpty()

    /** 受け手の無い操作（コマンドパレット）。 */
    fun actionsForWorkspace(): List<ActionEntry> = core?.let { ActionPlanner.forWorkspace(it.mirror.schemas) }.orEmpty()

    /** 名前（アクションの id か 型名.操作名）で操作を引く（create は除く）。 */
    fun actionByName(name: String): ActionEntry? =
        core?.let { cw -> ActionPlanner.catalog(cw.mirror.schemas).firstOrNull { it.name == name && it.kind != "create" } }

    /** 今の状態（編集用のセッション。無ければディスクの写し）で使えるか。 */
    fun actionAvailability(entry: ActionEntry, target: String?): ActionAvailability =
        (kernel ?: core?.mirror)?.let { ActionPlanner.availability(it, ActionRequest(entry.name, target)) }
            ?: ActionAvailability(false, "ワークスペースが開かれていません")

    /** 受け取る値の初期値と入力元の印（input のある操作）。 */
    fun prepareActionInput(entry: ActionEntry, target: String?, explicit: Map<String, Any?>, preparedAt: LocalDateTime): PreparedActionInput {
        val current = kernel ?: core?.mirror ?: error("ワークスペースが開かれていません")
        return ActionPlanner.prepareInput(current, ActionRequest(entry.name, target, explicit), preparedAt)
    }

    /** 受け取る値のうち渡すもの: input のない従来の操作は空欄を省略する。submit が changed なら初期値との差分と必須・明示指定だけ。 */
    fun actionParams(entry: ActionEntry, values: YNode.Map, tree: SchemaTree?, explicit: Map<String, Any?>, baseline: Map<String, Any?>): Map<String, Any?> {
        val required = entry.params.filter { p -> tree?.requiredAt(tree.resolve(p) ?: p, null) ?: p.required }.mapNotNull { it.key }.toSet()
        return values.entries.entries
            .filterNot { (_, v) -> entry.input == null && v is YNode.Scalar && v.value?.toString().isNullOrBlank() }
            .filter { (key, value) -> entry.input?.submit != "changed" || key in explicit || key !in baseline ||
                key in required || YamlIO.dumpPlain(value) != YamlIO.dumpPlain(YamlIO.fromAny(baseline[key])) }
            .associate { (k, v) -> k to YamlIO.toAny(v) }
    }

    /** 計画を作る（原本は変えない）。読み取りだけの前提は今の状態（編集用のセッション）で検査し、計画はディスクの写しが基準。 */
    fun planAction(request: ActionRequest, planTime: LocalDateTime): ActionPlan {
        val cw = core ?: error("ワークスペースが開かれていません")
        ActionPlanner.checkPreconditions(kernel ?: cw.mirror, request)
        return ActionPlanner.plan(cw.mirror, request, planTime)
    }

    /** 計画の変更セットを適用した後の姿の検証（ドライラン）。変更が無ければ null。 */
    fun previewAction(plan: ActionPlan): ChangeSetResult? = if (plan.isEmpty) null else core?.apply(plan.changeSet, dryRun = true)

    /** 計画の対象に未保存の編集があれば拒む（適用→保存が未保存の編集まで書いてしまうのを防ぐ）。 */
    fun checkActionTargetsSaved(plan: ActionPlan) {
        val dirty = plan.changeSet.changes.map { fileOf(DocumentId(it.file)) }.filter { documentMap[it]?.dirty == true }
        require(dirty.isEmpty()) { "エディタに未保存の変更があるため適用できません: ${dirty.joinToString(", ") { relPath(it) }}" }
    }

    /**
     * 計画を適用して保存する（変更セットの承認と同じ経路。検証・競合の基準はディスクの内容、書込は編集用のセッションの
     * 1 トランザクション＋保存＝Undo 1 回で全部戻る）。検証エラーが残るなら書かない（allowErrors で書く）。
     * 適用の直前に、読み取りだけの前提も同じ計画器で再検査する。戻り値は書いたファイルの数。
     */
    fun applyAction(request: ActionRequest, plan: ActionPlan, plannedAt: LocalDateTime, allowErrors: Boolean): Int {
        require(!plan.isEmpty) { "変更はありません" }
        val cw = core ?: error("ワークスペースが開かれていません")
        val s = kernel ?: error("セッションが開かれていません")
        checkActionTargetsSaved(plan)
        ActionPlanner.checkPreconditions(s, request)
        ActionPlanner.plan(cw.mirror, request, plannedAt)
        val result = withHistoryOrigin(s) { cw.applyViaSession(s, plan.changeSet, requireValid = !allowErrors) }
        if (!result.ok) {
            val reasons = result.results.filter { it.message != null }.joinToString("\n") { "${it.file}: ${it.message}" }
            error(reasons.ifBlank {
                if (result.errorIssueCount > 0) "検証エラーが残るため適用しませんでした（エラー ${result.errorIssueCount} 件）" else "適用に失敗しました"
            })
        }
        refreshAfterSessionWrite(plan.changeSet.changes.map { DocumentId(it.file) })
        s.peekUndo()?.let { actionRecordIds += it.id }
        return plan.changeSet.changes.size
    }

    // ---------------------------------------------------------------- 配列への追加と一覧の新しい行（要素の作り方はカーネルの 1 実装）

    /** 配列の追加の下調べ: 実効の配列定義・要素の型の create（受け取る値があるものだけ）・その配列へ足すアクション（橋渡し）。 */
    class ArrayInsert(val arrayDef: FieldDef, val create: MethodDef?, val alternatives: List<ActionEntry>)

    fun arrayInsert(doc: DocumentState, arrayPath: YPath, field: FieldDef): ArrayInsert {
        val schema = doc.schema
        val arrayDef = schema?.resolve(field) ?: field
        val itemDef = arrayDef.item?.let { schema?.resolve(it) ?: it }
        val create = itemDef?.methods?.firstOrNull { it.isCreate }?.takeIf { it.params.isNotEmpty() }
        val alternatives = if (create == null) emptyList()
            else core?.let { cw -> schema?.id?.let { sid -> ActionPlanner.insertingInto(cw.mirror.schemas, sid, arrayPath) } }.orEmpty()
        return ArrayInsert(arrayDef, create, alternatives)
    }

    /**
     * 配列の末尾へ要素を足す: 要素の中身は型の create と配列の insert の決まり（カーネルの 1 実装）。配列が無ければ作る。
     * 足した要素は未保存の編集（Undo 可）。戻り値は番号。
     */
    fun insertArrayElement(doc: DocumentState, arrayPath: YPath, arrayDef: FieldDef, params: Map<String, Any?>, now: LocalDateTime = LocalDateTime.now()): Int {
        check(!readOnlyMode) { "閲覧専用モードでは編集できません" }
        val root = doc.root as? YNode.Map ?: error("文書をパースできません: ${doc.parseError}")
        val seq = ensureSeq(root, arrayPath)
        val tree = doc.schema?.tree
        val element = if (tree == null) YNode.Map() else {
            // 配列を持つ要素（親）を渡す: 採番の接頭辞が `$of: self` で親の値（注文番号など）を読める
            val holder = if (arrayPath.isEmpty()) root else getAt(root, arrayPath.subList(0, arrayPath.size - 1))
            val made = ActionPlanner.newElement(tree, arrayDef, seq, params, overlayIndex().entries.keys, now, dedupe = params.isNotEmpty(), holder = holder)
            made.node ?: error(made.skipped ?: "追加できません")
        }
        seq.items.add(element)
        doc.markEdited()
        return seq.items.size - 1
    }

    /** パスの配列を解決する（末端の配列と、途中の無いマップは作る）。 */
    private fun ensureSeq(root: YNode.Map, path: YPath): YNode.Seq {
        var container: YNode = root
        path.forEachIndexed { i, seg ->
            val current = container
            container = when {
                current is YNode.Map && seg is String ->
                    current.entries.getOrPut(seg) { if (i == path.lastIndex) YNode.Seq() else YNode.Map() }
                current is YNode.Seq && seg is Int ->
                    current.items.getOrNull(seg) ?: error("追加先パスが存在しません: ${path.pretty()}")
                else -> error("追加先パスを解決できません: ${path.pretty()}")
            }
        }
        return container as? YNode.Seq ?: error("追加先が配列ではありません: ${path.pretty()}")
    }

    /** ビューの newRow 定義の追加先: 一覧文書・配列（末端だけは無ければ作る）・その配列の定義（スキーマが無ければ null）。 */
    class ViewRowTarget(val doc: DocumentState, val path: YPath, val seq: YNode.Seq, val arrayDef: FieldDef?)

    fun viewRowTarget(view: ViewDef): ViewRowTarget {
        val def = view.newRow ?: error("このビューには newRow が定義されていません")
        val fileRel = def.file ?: error("このビューの newRow はファイルモードです（addViewRowFile を使う）")
        val ws = workspace ?: error("ワークスペースが開かれていません")
        val file = ws.resolve(fileRel).normalize()
        require(Files.isRegularFile(file)) { "一覧文書が存在しません: $fileRel" }
        val doc = ensureDocument(file)
        val root = doc.root ?: error("一覧文書をパースできません: ${doc.parseError}")
        var container: YNode = root
        def.path.forEachIndexed { i, seg ->
            val current = container
            container = when {
                current is YNode.Map && seg is String ->
                    current.entries.getOrPut(seg) { if (i == def.path.lastIndex) YNode.Seq() else YNode.Map() }
                current is YNode.Seq && seg is Int ->
                    current.items.getOrNull(seg) ?: error("追加先パスが存在しません: ${def.path}")
                else -> error("追加先パスを解決できません: ${def.path}")
            }
        }
        val seq = container as? YNode.Seq ?: error("追加先が配列ではありません: ${def.path}")
        val arrayDef = schemaFor(file)?.let { Views.fieldAt(it, root, def.path) }
        return ViewRowTarget(doc, def.path, seq, arrayDef)
    }

    /** スキーマの無い一覧へ空の要素を足す（未保存の編集）。戻り値は足した要素のパス。 */
    fun appendEmptyRow(target: ViewRowTarget): YPath {
        target.seq.items.add(YNode.Map())
        target.doc.markEdited()
        return target.path + (target.seq.items.size - 1)
    }
}
