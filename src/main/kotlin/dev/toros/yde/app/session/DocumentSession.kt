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
import dev.toros.yde.workspace.WorkspaceDerivedState
import dev.toros.yde.workspace.WorkspaceSession
import dev.toros.yde.workspace.layout.FilePolicy
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YDocument
import dev.toros.yde.yaml.model.YNode
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

    fun closeDocument(file: Path): DocumentState? = documentMap.remove(file)

    fun clearDocuments() = documentMap.clear()

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
}
