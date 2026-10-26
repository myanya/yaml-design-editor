package dev.toros.yde.app.session

import dev.toros.yde.app.core.WorkspaceRecord
import dev.toros.yde.app.core.WorkspaceRecords
import dev.toros.yde.app.core.WorkspaceScan
import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.app.schema.BuiltinSchemas
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.app.validate.Readiness
import dev.toros.yde.semantic.SemanticSchemaGraph
import dev.toros.yde.semantic.schema.EnforcementDef
import dev.toros.yde.semantic.schema.EntityTypeDef
import dev.toros.yde.semantic.schema.RelationDef
import dev.toros.yde.workspace.FileSystemDocumentStore
import dev.toros.yde.workspace.StoreConflictException
import dev.toros.yde.workspace.WorkspaceDerivedState
import dev.toros.yde.workspace.WorkspaceSession
import dev.toros.yde.workspace.checkUnchanged
import dev.toros.yde.workspace.layout.FilePolicy
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YDocument
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.deepCopy
import java.nio.file.Files
import java.nio.file.Path

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
}
