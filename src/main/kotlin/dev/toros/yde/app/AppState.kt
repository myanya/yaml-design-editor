package dev.toros.yde.app

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import dev.toros.yde.app.core.ChangeSetResult
import dev.toros.yde.app.core.EditBuffer
import dev.toros.yde.app.ui.LineMerge
import dev.toros.yde.app.ui.DesktopUiDispatcher
import dev.toros.yde.app.ui.SourceHistorySync
import dev.toros.yde.app.core.Search
import dev.toros.yde.app.core.SearchHit
import dev.toros.yde.app.core.SearchQuery
import dev.toros.yde.app.core.ViewDef
import dev.toros.yde.app.core.WorkspaceRecord
import dev.toros.yde.app.index.DocScanner
import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.app.index.IdEntry
import dev.toros.yde.app.index.idMatchesPattern
import dev.toros.yde.app.schema.BuiltinSchemas
import dev.toros.yde.app.schema.SchemaInference
import dev.toros.yde.app.validate.Issue
import dev.toros.yde.app.validate.Validator
import dev.toros.yde.semantic.schema.EnforcementDef
import dev.toros.yde.semantic.schema.EntityTypeDef
import dev.toros.yde.semantic.schema.RefTarget
import dev.toros.yde.semantic.schema.RelationDef
import dev.toros.yde.semantic.schema.globToRegex
import dev.toros.yde.workspace.DocumentScaffold
import dev.toros.yde.workspace.DocumentScaffolds
import dev.toros.yde.workspace.ScaffoldSource
import dev.toros.yde.workspace.WorkspaceInit
import dev.toros.yde.workspace.changeset.ChangeSet
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YDocument
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.yaml.model.deepCopy
import dev.toros.yde.app.schema.SchemaLoader
import dev.toros.yde.semantic.RefactoringPlan
import dev.toros.yde.semantic.RefactoringRequest
import dev.toros.yde.semantic.SemanticAutoValues
import dev.toros.yde.app.agent.Staging
import dev.toros.yde.workspace.ActionEntry
import dev.toros.yde.workspace.ActionPlan
import dev.toros.yde.workspace.ActionPlanner
import dev.toros.yde.workspace.ActionRequest
import dev.toros.yde.workspace.ChangeIntent
import dev.toros.yde.workspace.ChangeRecord
import dev.toros.yde.workspace.FileSystemDocumentStore
import dev.toros.yde.workspace.StoreConflictException
import dev.toros.yde.workspace.checkUnchanged
import dev.toros.yde.workspace.WorkspaceDerivedState
import dev.toros.yde.workspace.WorkspaceOperation
import dev.toros.yde.workspace.WorkspaceReader
import dev.toros.yde.workspace.WorkspaceSession
import dev.toros.yde.workspace.WorkspaceTransaction
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.prefs.Preferences
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

/**
 * 開いている 1 ドキュメント。
 * root ツリーが正（source of truth）。フォーム編集はツリーを直接書き換え、
 * markEdited() で version をインクリメントして再コンポーズを駆動する。
 * 複数ドキュメントの再出力と、保存した内容との比較は core.EditBuffer（UI非依存。R9-02）へ委譲し、
 * このクラスは Compose 購読（version / dirty / parseError）のシェルに徹する。履歴（Undo / Redo）と dirty の正は、
 * カーネルのセッション（ADR-0006）——文書ごとの履歴は持たない（P27 段D の D-d・意図的差分 D-37）。
 */
class OpenDocument(
    val file: Path,
    root: YNode?,
    schema: SchemaDef?,
    initialText: String,
    parseError: String?,
    extraDocs: List<YNode> = emptyList(),
) {
    companion object {
        private val instances = java.util.concurrent.atomic.AtomicLong()
        private val sourceInputs = java.util.concurrent.atomic.AtomicLong()
    }
    private val instance = instances.incrementAndGet()
    /** 文書の再生成でも古い無key辺の選択を引き継がない。 */
    val flowRevision: Long get() = (instance shl 32) + version
    private val buffer = dev.toros.yde.app.core.EditBuffer(file, root, extraDocs, initialText, parseError)

    var schema by mutableStateOf(schema)

    var root: YNode?
        get() = buffer.root
        set(value) { buffer.root = value }

    /**
     * `---` 区切りの後続ドキュメント（R8-05: 複数ドキュメントYAML）。
     * フォーム・索引の編集対象は先頭ドキュメントのみで、後続は保持して保存時に再出力する。
     */
    var extraDocs: List<YNode>
        get() = buffer.extraDocs
        set(value) { buffer.extraDocs = value }

    var rawText: String
        get() = buffer.rawText
        set(value) { buffer.rawText = value }

    /** テキスト文書・構成ファイルの保存検査用。上書き許可で dirty の基準は動かさない。 */
    internal var expectedStoreText: String? = initialText

    // parseError / dirty は buffer が正で、Compose ミラーを同期する（購読の必要があるため）
    private val parseErrorState = mutableStateOf(parseError)
    var parseError: String?
        get() = parseErrorState.value
        set(value) {
            parseErrorState.value = value
            buffer.parseError = value
        }

    var version by mutableStateOf(0)           // 編集のたびにインクリメント
    private val dirtyState = mutableStateOf(false)
    val dirty: Boolean get() = dirtyState.value
    var externalConflict by mutableStateOf(false)
    var diskModifiedMillis: Long = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)

    /**
     * 編集通知（R16-04 / UX-02 / RA-06）: AppState が全文書横断の編集カウンタ（editVersion）を進め、
     * セッション履歴へ記録するのに使う。引数は合体キー（UX-11。null = 常に境界）。
     */
    internal var onEdited: ((Any?) -> Unit)? = null

    /**
     * dirty の供給元（RA-06）: セッション（履歴・dirty の単一所有。ADR-0006）が判定を返す。
     * null（セッションが文書として扱わないパス＝構成ファイル・開いた時点でパースできなかった文書など）のときだけ、
     * 保存した内容との比較（EditBuffer）で判定する。
     */
    internal var dirtySource: (() -> Boolean?)? = null

    private fun sync() {
        dirtyState.value = dirtySource?.invoke() ?: buffer.dirty
        parseErrorState.value = buffer.parseError
    }

    /** 所有側（セッション）の状態変化後に dirty 表示を再評価する（RA-06。Undo・保存・履歴適用の後）。 */
    internal fun refreshDirty() = sync()

    /**
     * 編集をツリーへ記録する。coalesceWith（UX-11。architecture/editing-history.md「履歴の合体」）: 連続入力になり得る編集面が
     * 渡す合体キー（同一スカラーノード・`Pair(map, key)`・ソースの固定キー等）。同じキーの連続編集は
     * 履歴1記録に合体する。構造操作は渡さない＝常に境界。
     * 履歴の記録先はセッション（onEdited 経由。RA-06）。
     */
    fun markEdited(coalesceWith: Any? = null) {
        version++
        if (coalesceWith is YNode) lastEditedNode = coalesceWith
        onEdited?.invoke(coalesceWith)
        sync()
    }

    /** フォームで最後に編集したスカラー（ソースタブへ移ったときに同じ行へ行くため。パスは必要になったときに木から引く）。 */
    var lastEditedNode: YNode? = null

    /** ソース編集のテキストをツリーへ取り込む。複数ドキュメントは先頭を編集対象にし、後続を保持する。 */
    fun applySourceText(text: String) {
        buffer.applySourceText(text)
        // ソースの連続入力は履歴1エントリへ合体する（UX-11 / UX-23。文書単位の固定キー）
        markEdited(coalesceWith = "source")
    }

    /** ディスク再読込などで内容が差し替わったとき、保存した内容（dirty の判定の基準）を取り直す。 */
    fun resetBaseline() {
        buffer.markSaved()
        sync()
    }

    fun markSaved() {
        buffer.markSaved()
        sync()
    }

    /** 保存・ソース表示に使う正規化テキスト。パース不能なら元テキストを返す。 */
    fun canonicalText(): String = buffer.canonicalText()

    // ================================================================ ソースの下書き（P43 R43-01〜04。architecture/editing-history.md「ソースとの同期」）

    /**
     * ソースタブの文字列とカーソル。画面の部品でなく文書が持つので、タブや文書を移っても残る。
     * 木が正で、下書きは木の表現の 1 つ。解析できたら木へ反映し（applyParsed）、他所で木が変わったら差分だけを差し込む（syncSourceFromTree）。
     */
    val source: TextFieldState = TextFieldState(canonicalText())

    /** 下書きを最後に木へ反映した（または反映を試みた）文字列。これと違えば、まだ反映していない入力がある。 */
    var sourceApplied by mutableStateOf(source.text.toString())
        private set

    /** 解析の世代。木との同期や下書きの破棄をまたいだ解析結果を採用しない。 */
    internal var sourceEpoch by mutableStateOf(0L)
        private set
    private var observedSourceText = sourceApplied
    internal var sourceInputOrder: Long = 0
        private set
    val hasPendingSourceInput: Boolean get() = source.text.toString() != sourceApplied

    /** 入力順だけを記録する。入力変換から呼んでも木・履歴・Compose の状態を変更しない。 */
    internal fun noteSourceInput(text: String) {
        if (text == observedSourceText) return
        observedSourceText = text
        sourceInputOrder = sourceInputs.incrementAndGet()
    }

    /** 補完・置換・行操作も、通常入力と同じ順序で取り込む。選択範囲だけの変更は数えない。 */
    fun editSource(block: TextFieldBuffer.() -> Unit) {
        source.edit(block)
        noteSourceInput(source.text.toString())
    }

    internal class SourceParseRequest(
        val text: String,
        val document: OpenDocument,
        val version: Int,
        val epoch: Long,
        val inputOrder: Long,
    )

    internal fun sourceParseRequest(): SourceParseRequest? {
        if (!hasPendingSourceInput) return null
        val text = source.text.toString()
        noteSourceInput(text)
        return SourceParseRequest(text, this, version, sourceEpoch, sourceInputOrder)
    }

    /** 前回木から作った正規化テキスト（3 者マージの元。「整形」の活性判定にも使う）。 */
    var sourceBase: String = sourceApplied
        private set

    /** 最後に解析できた下書きの文字列（位置の表＝指摘の行・カーソルのパスの元。解析できない文書では空）。 */
    var sourceParsed: String = if (root == null) "" else sourceApplied
        private set

    /** 下書きを木と同期した version。version と違えば他所で木が変わった。 */
    var sourceSyncedVersion: Int = 0
        private set

    /** 下書きが解析できないときの文面（行・列つき）。null なら下書きは木と一致している。 */
    var sourceError by mutableStateOf<String?>(parseError)
        private set

    /** 下書きが解析できないか。フォームは閲覧専用になり、保存すると「壊れたまま保存」になる（R43-02・04）。 */
    val draftBroken: Boolean get() = sourceError != null

    /** 壊れた下書きが、保存した内容と違うか（壊れている間は dirty の代わりにこれが未保存の印）。 */
    val draftDirty: Boolean get() = sourceError != null && source.text.toString() != rawText

    /** 壊れたまま保存した（または開いた時点で解析できなかった）テキスト文書か。索引・検証の対象外で、直して解析できれば戻る（R43-04）。 */
    var textOnly by mutableStateOf(root == null)

    /** 最後に解析できた木（壊れたまま保存したあと「解析できた内容に戻す」ために残す）。 */
    private var lastParsed: Pair<YNode, List<YNode>>? = null

    /** 最後に解析できた木に戻せるか。 */
    val canRestoreLastParsed: Boolean get() = textOnly && lastParsed != null

    /**
     * 下書きの解析結果を木へ反映する（UI スレッドで呼ぶ）。成功なら markEdited＝履歴・dirty・検証・索引が追随し、
     * テキスト文書なら文書集合へ戻る。失敗なら文面を残すだけで木は触らない。
     */
    internal fun applyParsed(request: SourceParseRequest, parsed: EditBuffer.Parsed): Boolean {
        val text = request.text
        if (request.document !== this || request.version != version || request.epoch != sourceEpoch ||
            request.inputOrder != sourceInputOrder || text != source.text.toString() || text == sourceApplied) return false
        sourceEpoch++
        sourceApplied = text
        val newRoot = parsed.root
        if (newRoot == null) {
            sourceError = parsed.error ?: "解析できません"
            return true
        }
        buffer.root = newRoot
        buffer.extraDocs = parsed.extraDocs
        buffer.parseError = null
        sourceError = null
        sourceParsed = text
        // ソースの連続入力は履歴 1 件に合体する（文書単位の固定キー）
        markEdited(coalesceWith = "source")
        textOnly = false
        sourceBase = canonicalText()
        sourceSyncedVersion = version
        return true
    }

    /** まだ反映していない入力をいま解析して反映する（保存・Undo・タブを離れるときに呼ぶ）。 */
    fun flushDraft() {
        val request = sourceParseRequest() ?: return
        applyParsed(request, EditBuffer.parse(request.text))
    }

    data class SourceSync(val conflicts: Int = 0, val normalized: Boolean = false)

    /**
     * 他所（フォーム・Undo・操作・再読込）で木が変わったとき、下書きへ差分だけを差し込む（R43-03）。
     * 元＝前回の正規化テキスト・自分＝下書き・相手＝今回の正規化テキストの 3 者マージで、カーソルは同じ場所に留まる。
     * 履歴の直後は取り込んだ入力の値を残さず、履歴の値と一致する書式だけを保つ。
     */
    fun syncSourceFromTree(preferHistory: Boolean = false): SourceSync {
        if (version == sourceSyncedVersion && !preferHistory) return SourceSync()
        sourceEpoch++
        val theirs = canonicalText()
        val ours = source.text.toString()
        var conflicts = 0
        var normalized = false
        if (preferHistory && !hasPendingSourceInput && !draftBroken) {
            val result = SourceHistorySync.sync(sourceBase, ours, theirs, source.selection.start)
            normalized = result.normalized
            if (ours != result.text) source.edit {
                replace(0, length, result.text)
                placeCursorBeforeCharAt(result.caret)
            }
            sourceApplied = result.text
        } else if (ours != theirs) {
            val m = LineMerge.mergeHunks(LineMerge.splitLines(sourceBase), LineMerge.splitLines(ours), LineMerge.splitLines(theirs))
            conflicts = m.conflicts
            if (m.hunks.isNotEmpty()) {
                val r = LineMerge.applyToText(ours, m.hunks, source.selection.start)
                source.edit { replace(0, length, r.text); placeCursorBeforeCharAt(r.caret) }
                sourceApplied = r.text
            }
        }
        sourceBase = theirs
        observedSourceText = source.text.toString()
        sourceSyncedVersion = version
        if (sourceError == null) sourceParsed = source.text.toString()
        else if (source.text.toString() == theirs) { sourceError = null; sourceParsed = theirs }
        return SourceSync(conflicts, normalized)
    }

    /** 下書きを捨てて木の正規化テキストに揃える（「編集を破棄」「整形」）。テキスト文書では保存したテキストに戻る。 */
    fun discardDraft() {
        sourceEpoch++
        val text = canonicalText()
        val caret = source.selection.start.coerceAtMost(text.length)
        source.edit { replace(0, length, text); placeCursorBeforeCharAt(caret) }
        sourceApplied = text
        observedSourceText = text
        sourceBase = text
        sourceSyncedVersion = version
        sourceError = if (buffer.root == null) buffer.parseError else null
        sourceParsed = if (buffer.root == null) "" else text
    }

    /** 壊れた下書きをそのまま保存した直後の状態にする＝テキスト文書（R43-04）。最後に解析できた木は戻せるように残す。 */
    fun enterTextOnly(savedText: String) {
        sourceEpoch++
        buffer.root?.let { lastParsed = it to buffer.extraDocs }
        buffer.root = null
        buffer.extraDocs = emptyList()
        buffer.rawText = savedText
        buffer.parseError = sourceError
        textOnly = true
        sourceApplied = savedText
        sourceBase = savedText
        sourceParsed = ""
        version++
        sourceSyncedVersion = version
        markSaved()
    }

    /** テキスト文書を、最後に解析できた木に戻す（未保存になる。保存すれば木の内容が書かれる）。 */
    fun restoreLastParsed(): Boolean {
        val (r, extra) = lastParsed ?: return false
        buffer.root = r
        buffer.extraDocs = extra
        buffer.parseError = null
        sourceError = null
        markEdited()
        textOnly = false
        discardDraft()
        return true
    }
}

/**
 * UIシェルのスペース（ROADMAP P12 / architecture/ui.md「UI シェル」）。
 * EDIT / LEDGER は文書世界（workspaceView 0 / 1〜6）と同期し、RUNS / DESIGN は文書状態を保ったまま重なる。
 * 同期はナビゲーション系メソッドとレール（showSpace / showWorkspaceView）だけが行い、直接書き換えない。
 */
enum class UiSpace { DASHBOARD, EDIT, LEDGER, RUNS, DESIGN }

/**
 * アプリケーション状態。
 */
class AppState(private val editClock: () -> Long = System::currentTimeMillis) {
    private companion object {
        /** ナビゲーション履歴（戻る／進む）の保持上限。 */
        const val HISTORY_LIMIT = 50
    }

    private val recentPrefs = Preferences.userRoot().node("yaml-design-editor/recent")
    private val appPrefs = Preferences.userRoot().node("yaml-design-editor/app")

    var openPreview by mutableStateOf<Path?>(null)
    var previewLine by mutableStateOf(1)
    fun openReference(file: Path, line: Int = 1) {
        pushBack(currentLocation())
        showReference(file, line)
    }
    private fun showReference(file: Path, line: Int = 1) {
        openDoc = null; openMarkdown = null; openHelp = null; openImage = null
        openPreview = file; previewLine = line
        workspaceView = 0; syncSpaceFromView()
    }
    fun saveSourceOutline(source: Path, outline: dev.toros.yde.app.core.SourceOutline.Outline, destination: String): Result<Path> = runCatching {
        val ws = workspace ?: error("ワークスペース未選択")
        val target = dev.toros.yde.workspace.WorkspacePaths.resolve(ws, destination)
        require(target.fileName.toString().let { it.endsWith(".yaml") || it.endsWith(".yml") }) { "保存先は YAML にしてください" }
        val root = YNode.Map(linkedMapOf(
            "source" to YNode.Scalar(relPath(source)), "language" to YNode.Scalar(outline.language),
            "symbols" to YNode.Seq(outline.symbols.map { symbol -> YNode.Map(linkedMapOf(
                "kind" to YNode.Scalar(symbol.kind), "name" to YNode.Scalar(symbol.name),
                "line" to YNode.Scalar(symbol.line), "owner" to YNode.Scalar(symbol.owner),
            )) }.toMutableList()),
        ))
        createDocumentFile(target, root, "ソースの構造情報を保存: ${relPath(source)}")
    }
    var fileOperationBusy by mutableStateOf(false)
    var fileHistoryBusy by mutableStateOf(false)
    fun undoFromUi() { historyFromUi(true) }
    fun redoFromUi() { historyFromUi(false) }
    internal fun historyFromUi(undoing: Boolean, confirmation: PendingHistory? = null): Job? {
        if (fileOperationBusy) return null
        val step = prepareHistoryStep(undoing, confirmation) ?: return null
        if (step.session.fileArrangement(step.record.id) == null) { executeHistoryStep(step); return null }
        fileOperationBusy = true; fileHistoryBusy = true
        return kotlinx.coroutines.CoroutineScope(DesktopUiDispatcher).launch {
            try {
                val result = withContext(Dispatchers.IO) { applyHistoryStep(step) }
                completeHistoryStep(step, result)
            }
            finally { fileOperationBusy = false; fileHistoryBusy = false }
        }
    }
    private var deferredFileEvents = false
    var fileTreeRevision by mutableStateOf(0)
    val activeFile: Path? get() = openDoc?.file ?: openMarkdown ?: openImage ?: openPreview
    fun refreshFileTree() { fileTreeRevision++ }
    fun noteFileEvents(paths: Set<Path>?) {
        if (fileOperationBusy) { if (paths == null || paths.isNotEmpty()) deferredFileEvents = true; return }
        if (deferredFileEvents) { deferredFileEvents = false; noteFileEvents(null); return }
        if (paths != null && paths.isEmpty()) return
        fileTreeRevision++
        val relevant = paths == null || paths.any { p ->
            Files.isDirectory(p) || (yamlFiles + editorOnlyFiles).any { it.startsWith(p) } ||
                p.fileName.toString().let { it.endsWith(".yaml") || it.endsWith(".yml") }
        }
        if (relevant) pollExternalChanges(eventHint = true)
    }

    var workspace by mutableStateOf<Path?>(null)
        private set

    /**
     * 最近開いたワークスペース履歴（R10-05）。ワークスペース単位の recentFiles（W-05）と同じ
     * Preferences 機構のアプリグローバル版。スタート画面・コマンドパレットの切替候補に使う。
     */
    var recentWorkspaces by mutableStateOf<List<Path>>(emptyList())
        private set

    init {
        recentWorkspaces = appPrefs.get("recentWorkspaces", "")
            .lineSequence().filter { it.isNotBlank() }
            .map { Path.of(it) }.filter(Files::isDirectory).take(10).toList()
    }

    /**
     * 閲覧専用モード（R10-04）。フォーム・グリッドの編集UIを表示専用に切り替える
     * （FormCtx がこのフラグを既定値として拾う。永続化は Main の uiPreferences＝W-06 の流儀）。
     */
    var readOnlyMode by mutableStateOf(false)

    /**
     * ヘッドレスコア（R9-02）。ファイル走査・スキーマ・索引の実体と更新方針（増分／全再構築の
     * 振り分け）は core.Workspace が持ち、AppState は Compose 購読用のミラーと文書キャッシュを担う。
     */
    private var coreWs: dev.toros.yde.app.core.Workspace? = null

    /**
     * 新経路のワークスペースセッション（移行後リファクタリング Phase A-1・RA-01 併設）。
     * ディスク同期（reload・保存・外部変更）で旧経路と同じタイミングで追随する読み経路の供給元で、
     * 書込には使わない（A-2 以降）。開けない場合も null のままアプリは旧経路で動き続ける。
     */
    private var session: WorkspaceSession? = null

    var schemas by mutableStateOf<List<SchemaDef>>(emptyList())
        private set
    var schemaErrors by mutableStateOf<List<String>>(emptyList())
        private set
    /** 関係レジストリ（R20-02）: relation 名 → 宣言。バックリンク表示面が label / inverseLabel の解決に使う。 */
    var relations by mutableStateOf<Map<String, RelationDef>>(emptyMap())
        private set
    /** 強制スイッチ（R21-01）: グラフ整合検証の enforcement。検証呼び出しが relations と対で渡す。 */
    var enforcement by mutableStateOf(EnforcementDef())
        private set
    /** エンティティ型レジストリ（R23-01。architecture/graph-query.md「エンティティページ」）: 型の表示名・アスペクト順序。エンティティページ・レンズが使う。 */
    var entityTypes by mutableStateOf<Map<String, EntityTypeDef>>(emptyMap())
        private set
    /** スキーマの図（型の段のレンズ。P42）: スキーマと関係レジストリから導いた型・関係・保有の宣言。スキーマの再読込で作り直す。 */
    var schemaGraph by mutableStateOf(dev.toros.yde.semantic.SemanticSchemaGraph.EMPTY)
        private set
    var yamlFiles by mutableStateOf<List<Path>>(emptyList())
        private set
    var directories by mutableStateOf<List<Path>>(emptyList())
        private set
    /**
     * 台帳外のYAML（走査対象外＝索引・検証集計・エージェントに載らない、エディタ層のファイル。R13-04）。
     * `ledger` 宣言のあるワークスペースでのみ非空になる。ツリーでは視覚的に区別して表示する。
     */
    var editorOnlyFiles by mutableStateOf<List<Path>>(emptyList())
        private set

    /** ワークスペースの配置ポリシー（R13-02）。コア未オープン時はレガシー相当。 */
    val layout: WorkspaceLayout get() = coreWs?.layout ?: WorkspaceLayout.LEGACY

    /** 走査対象（台帳＋予約領域）に載っているファイルか。台帳外は索引前提の検証を降格する（R13-03）。 */
    fun isIndexed(file: Path): Boolean = coreWs?.let { file in it.files } ?: true

    private val emptyIndex = IdIndex()
    val index: IdIndex get() = coreWs?.index ?: emptyIndex
    var indexVersion by mutableStateOf(0)
        private set

    /**
     * 全文書横断の編集カウンタ（R16-04 / UX-02）: docCache のどの文書かの未保存編集・Undo/Redo で進む。
     * 計算列など「他文書の未保存編集に追随したい」派生表示が再解決の購読に使う（architecture/transform.md「計算列と決定論的射影」）。
     */
    var editVersion by mutableStateOf(0)
        private set

    /** overlayIndex() のキャッシュ（(indexVersion, editVersion) が変わるまで再計算しない）。 */
    private var overlayCache: Pair<Pair<Int, Int>, IdIndex>? = null

    /**
     * 未保存編集を重ねたオーバーレイ索引（UX-03）。dirty な文書キャッシュの編集中ツリーを
     * 重ねた「見たまま」の索引を返す（architecture/index-references.md「索引の二層」）。UI層の検証・カバレッジ・ビュー行集合・
     * 参照解決はディスク索引（index）ではなく**こちら**を読む＝「見たままの状態で判定する」。
     * indexVersion / editVersion（Compose状態）を読むため、コンポジション中の呼び出しは自動で購読される。
     * `remember` の計算内で使う場合はキーに editVersion を含めること。
     *
     * 供給元はセッション派生（derivedState＝カーネルの増分導出。RA-02）で、索引は、その意味解析の結果そのもの
     * （写しを持たない。P27 段D の D-d・D-e）。セッションを開けていない場合だけ、ディスクを映した索引へ重ねる。
     */
    fun overlayIndex(): IdIndex {
        val cw = coreWs ?: return index
        val key = indexVersion to editVersion
        overlayCache?.let { (k, cached) -> if (k == key) return cached }
        val result = derivedState()?.let { derived -> IdIndex.of(derived.semantic) }
            ?: legacyOverlayIndex(cw)
        overlayCache = key to result
        return result
    }

    /**
     * まだ文書に入れていない内容（AI 生成の提案など）を重ねた索引（BACKLOG UX-109）。定義体の検査は索引にある要素だけを
     * 判定するため、提案の内容で新しく書かれた ID にも検査を効かせるには、提案を重ねた索引が要る。未保存の編集も重ねる。
     * 台帳の外の文書・セッションを開けない場合は、通常の overlayIndex を返す。
     */
    fun overlayIndexWith(file: Path, root: YNode): IdIndex {
        val cw = coreWs ?: return index
        if (!isIndexed(file)) return overlayIndex()
        val overlays: Map<Path, YNode?> = docCache.values
            .filter { it.dirty && isIndexed(it.file) }
            .associate { it.file to it.root as YNode? } + (file to root)
        return runCatching { cw.overlayIndex(overlays) }.getOrElse { overlayIndex() }
    }

    /** 従来の二層オーバーレイ（RA-02 のフォールバック。セッションを開けない場合のみ）。 */
    private fun legacyOverlayIndex(cw: dev.toros.yde.app.core.Workspace): IdIndex {
        val overlays = docCache.values
            .filter { it.dirty && isIndexed(it.file) }
            .associate { it.file to it.root }
        return if (overlays.isEmpty()) cw.index else cw.overlayIndex(overlays)
    }

    /** 開いたことのあるドキュメントのキャッシュ（未保存編集をファイル切替時にも保持する）。 */
    // 文書と、その中の State を同じ描画世代で公開する。通常の Map では古い描画にも新しい文書が漏れる。
    private val docCache = mutableStateMapOf<Path, OpenDocument>()

    /** 編集欄をセッションの履歴へ接続できる文書か。一時フォームは含めない。 */
    internal fun ownsDocument(doc: OpenDocument): Boolean =
        docCache[doc.file] === doc && session?.hasDocument(documentOf(doc.file)) == true

    /**
     * 画面の覚え（畳み・タブ・絞り込みなど。architecture/ui.md「UI シェル」）。
     * 画面を離れても消えず、ワークスペース切替で消える。「戻る・進む」が戻すのは場所（NavLocation）で、見え方はここが持つ。
     */
    val memory = ScreenMemory()

    var openDoc by mutableStateOf<OpenDocument?>(null)
        private set
    /** 開いている Markdown 文書（ワークスペース内の .md。表示のみ）。openDoc・openHelp と排他。 */
    var openMarkdown by mutableStateOf<Path?>(null)
        private set
    /** 開いている内蔵ヘルプの題目（help/ の資源名）。openDoc・openMarkdown と排他。 */
    var openHelp by mutableStateOf<String?>(null)
        private set
    /** 開いている画像（file 型の値の拡大表示。表示のみ）。openDoc・openMarkdown・openHelp と排他。 */
    var openImage by mutableStateOf<Path?>(null)
        private set
    /** ワークスペース内の Markdown（表示用。索引・検証の対象ではない）。 */
    var markdownFiles by mutableStateOf<List<Path>>(emptyList())
        private set
    var recentFiles by mutableStateOf<List<Path>>(emptyList())
        private set

    /** フォーム上でハイライト＆スクロール対象にするパス。 */
    var highlightPath by mutableStateOf<YPath?>(null)

    /** 同一論理 ID に複数の出現箇所がある場合のジャンプ先候補。 */
    var navigationCandidates by mutableStateOf<List<IdEntry>>(emptyList())
        private set

    private val editorTabState = mutableStateOf(0)

    /**
     * 0 = フォーム（スキーマ文書ではスキーマ設計）, 1 = ソース, 2 = 差分, 3 = スキーマ文書のメタスキーマフォーム（R11-02）。
     * 文書ごとに最後に見ていたタブを覚え（`memory.editorTab`）、覚えの無い文書は直前のタブで開く（`showFile`）＝
     * ソースで書いている最中に別の文書へ移ってもソースのまま、戻れば戻った文書も最後に見ていたタブ。
     */
    var editorTab: Int
        get() = editorTabState.value
        set(value) {
            if (editorTabState.value != value) finishEditGroup()
            editorTabState.value = value
            openDoc?.let { memory.editorTab[documentOf(it.file)] = value }
        }
    /** 0 = 文書, 1 = 一覧, 2 = 参照グラフ, 3 = ID横ぐし, 4 = ビューグリッド */
    var workspaceView by mutableStateOf(0)

    /** 表示中のスペース（R12-01）。書き換えは showSpace / showWorkspaceView / ナビゲーション系メソッド経由に限る。 */
    var uiSpace by mutableStateOf(UiSpace.EDIT)
        private set

    /** 台帳スペースで最後に見ていた下位ビュー（1〜6）。レールで台帳へ戻ったときに復元する。 */
    private var lastLedgerView = 1

    /** workspaceView の変更後に呼び、スペースを文書世界（編集／台帳）へ同期する。 */
    private fun syncSpaceFromView() {
        uiSpace = if (workspaceView == 0) UiSpace.EDIT else UiSpace.LEDGER
        if (workspaceView in 1..6) lastLedgerView = workspaceView
    }

    /** file 型の値の許可（`.yde/workspace.yaml` の `files:`）。検証の呼び出しに渡す。 */
    val filePolicy: dev.toros.yde.workspace.layout.FilePolicy
        get() = coreWs?.layout?.files ?: dev.toros.yde.workspace.layout.FilePolicy.DEFAULT

    /** ファイル（YAML 以外も）があるか（file 型の値の存在の検査。相対はワークスペースのルートから）。 */
    fun fileExists(path: String): Boolean = coreWs?.fileExists(path) ?: false

    /** 準備完了レポート（CLI `readiness` と同じ計算＝保存済みの内容で判定）。台帳の「準備」タブが読む。 */
    fun readinessReport(): dev.toros.yde.app.validate.Readiness.Report? =
        coreWs?.let { dev.toros.yde.app.validate.Readiness.report(it) }

    /**
     * レールからのスペース切替。文書世界の位置（workspaceView）は保存・復元する。
     * スペースの移動もユーザーにとっては「画面の遷移」のため、履歴に積んで戻る・進むの対象にする（R12-01）。
     */
    fun showSpace(space: UiSpace) {
        if (space == uiSpace) return
        pushBack(currentLocation())
        when (space) {
            UiSpace.EDIT -> { workspaceView = 0; uiSpace = UiSpace.EDIT }
            UiSpace.LEDGER -> { workspaceView = lastLedgerView; uiSpace = UiSpace.LEDGER }
            UiSpace.DASHBOARD, UiSpace.RUNS, UiSpace.DESIGN -> uiSpace = space
        }
    }

    /** 文書世界の下位ビュー（0〜4）を表示する（台帳スペースのタブ・パレットから）。表示が変わるときは履歴に積む。 */
    fun showWorkspaceView(view: Int) {
        val targetSpace = if (view == 0) UiSpace.EDIT else UiSpace.LEDGER
        if (view == workspaceView && uiSpace == targetSpace) return
        pushBack(currentLocation())
        workspaceView = view
        syncSpaceFromView()
    }

    /** ID横ぐしビューで選択中の論理ID。 */
    var crossViewId by mutableStateOf<String?>(null)

    /** ID横ぐしビューを開く（id 指定時はそのIDを選択し、ジャンプとして履歴に積む）。 */
    fun openCrossView(id: String? = null) {
        if (uiSpace == UiSpace.DASHBOARD && id != null) { openDashboardEntity(id); return }
        // 横ぐしビューへの到着（他ビュー・他スペースから）と、ビュー内でのID切替の両方を移動として履歴に積む
        val arriving = workspaceView != 3 || uiSpace != UiSpace.LEDGER
        val idChanges = id != null && crossViewId != id
        if (arriving || idChanges) pushBack(currentLocation())
        if (id != null) crossViewId = id
        workspaceView = 3
        syncSpaceFromView()
    }

    /**
     * グラフレンズ（R23-03。architecture/graph-query.md「エンティティページ」）の起点ID。null = 起点なし（型・relation で絞る全体表示）。
     * レンズ画面側で解除・変更できる（NavLocation には含めない＝復元はビュー位置まで）。
     */
    var lensOriginId by mutableStateOf<String?>(null)

    /** 右ペイン（検証パネル・レンズの型と関係と辺）が開いているか。起動を跨ぐ記憶は Main が設定に写す。 */
    var rightPaneOpen by mutableStateOf(true)

    /** グラフレンズを開く（id 指定時はそのIDの近傍モード。移動として履歴に積む）。 */
    fun openGraphLens(id: String? = null) {
        val arriving = workspaceView != 2 || uiSpace != UiSpace.LEDGER
        val idChanges = id != null && lensOriginId != id
        if (arriving || idChanges) pushBack(currentLocation())
        if (id != null) lensOriginId = id
        workspaceView = 2
        syncSpaceFromView()
    }

    /** ビューグリッドで選択中のビューID。 */
    var viewGridId by mutableStateOf<String?>(null)

    var dashboardId by mutableStateOf<String?>(null)
    /** 一覧条件とは独立した場所。完了で行が消えても同じ対象を表示する。 */
    var dashboardEntityId by mutableStateOf<String?>(null)

    fun openDashboard(id: String? = null) {
        if (uiSpace != UiSpace.DASHBOARD || (id != null && dashboardId != id)) {
            pushBack(currentLocation())
        }
        if (id != null && dashboardId != id) { dashboardId = id; dashboardEntityId = null }
        uiSpace = UiSpace.DASHBOARD
    }

    fun openDashboardEntity(id: String) {
        if (overlayIndex().occurrences(id).isEmpty()) {
            status = "対象「$id」が見つかりません"
            return
        }
        if (uiSpace == UiSpace.DASHBOARD && dashboardEntityId == id) return
        pushBack(currentLocation())
        dashboardEntityId = id
        uiSpace = UiSpace.DASHBOARD
    }

    fun showDashboardOverview() {
        if (dashboardEntityId == null && uiSpace == UiSpace.DASHBOARD) return
        pushBack(currentLocation())
        dashboardEntityId = null
        uiSpace = UiSpace.DASHBOARD
    }

    var flowViewId by mutableStateOf<String?>(null)
    var flowDocument by mutableStateOf<DocumentId?>(null)
    var flowSelections by mutableStateOf<Map<Pair<String, DocumentId>, dev.toros.yde.semantic.FlowSelection>>(emptyMap())
    /** ビューと文書ごとに保持し、単独表示と文書埋め込みで共有する。履歴・Undoには含めない。 */
    var flowDisplayOptions by mutableStateOf<Map<Pair<String, DocumentId>, dev.toros.yde.app.core.FlowDisplayOptions>>(emptyMap())

    fun selectFlow(view: String, document: DocumentId, selection: dev.toros.yde.semantic.FlowSelection?) {
        val key = view to document
        flowSelections = if (selection == null) flowSelections - key else flowSelections + (key to selection)
    }

    fun openFlowView(id: String? = null, document: DocumentId? = null) {
        if (uiSpace != UiSpace.LEDGER || workspaceView != 5 || flowViewId != id || flowDocument != document) {
            pushBack(currentLocation())
        }
        if (id != null) {
            if (flowViewId != id) flowDocument = null
            flowViewId = id
        }
        if (document != null) flowDocument = document
        workspaceView = 5
        syncSpaceFromView()
    }

    /** ビューグリッドを開く（id 指定時はそのビューを選択し、ジャンプとして履歴に積む）。 */
    fun openViewGrid(id: String? = null) {
        // ビューグリッドへの到着とビュー切替の両方を移動として履歴に積む（openCrossView と同じ規約）
        val arriving = workspaceView != 4 || uiSpace != UiSpace.LEDGER
        val idChanges = id != null && viewGridId != id
        if (arriving || idChanges) pushBack(currentLocation())
        if (id != null) viewGridId = id
        workspaceView = 4
        syncSpaceFromView()
    }

    var status by mutableStateOf("「開く」からワークスペース（ディレクトリ）を選択してください")

    /**
     * Undo/Redo 実行後の通知（UX-86）: 横断履歴（D-07）は開いていない文書も戻すため、
     * 別文書を含む操作は上部にも表示し、編集・保存では消さない。
     * files はジャンプ可能な影響文書（ディスクに存在するもののみ）。
     */
    data class HistoryNotice(
        val message: String,
        val files: List<Path>,
        val summary: String,
        val targets: List<DocumentId>,
        val crossDocument: Boolean,
        val undo: Boolean,
        val returnedToOrigin: Boolean = false,
    )

    data class HistoryNoticeTarget(val document: DocumentId, val canOpen: Boolean, val dirty: Boolean)

    var historyNotice by mutableStateOf<HistoryNotice?>(null)
        private set

    fun dismissHistoryNotice() { historyNotice = null }

    private fun dismissLocalHistoryNotice() {
        if (historyNotice?.crossDocument != true) historyNotice = null
    }

    /** 通知の操作対象は固定し、保存状態と開けるかは現在の状態から読む。 */
    fun historyNoticeTargets(): List<HistoryNoticeTarget> {
        editVersion
        indexVersion
        return historyNotice?.targets.orEmpty().map { id ->
            val doc = docCache[fileOf(id)]
            HistoryNoticeTarget(id, session?.hasDocument(id) == true || Files.isRegularFile(fileOf(id)),
                session?.isDirty(id) == true || doc?.dirty == true || doc?.draftDirty == true || doc?.hasPendingSourceInput == true)
        }
    }

    fun openHistoryTarget(id: DocumentId) {
        val target = historyNoticeTargets().find { it.document == id } ?: return
        if (target.canOpen) openFile(id) else status = "現在このファイルはありません: ${id.value}"
    }

    var aiRequest by mutableStateOf<AiRequest?>(null)
        private set
    private var workspaceStamp: Map<Path, Long> = emptyMap()

    /**
     * AI生成の要求（R24-01）: 対象文書を明示的に持つ。ホストはシェル直下でスペース非依存のため、
     * エンティティページ等どの画面のフォームからでも、その FormCtx の文書を対象に生成できる。
     * relatedFiles は呼び出し元が注入する関連文書の候補（内容の文脈。R24-02）、
     * mergeObjectKeys は出現単位生成のキー単位マージ適用（非生成キーを保持。R24-02）。
     */
    data class AiRequest(
        val doc: OpenDocument,
        val field: FieldDef,
        val path: YPath,
        val wholeDocument: Boolean = false,
        val relatedFiles: List<Path> = emptyList(),
        val mergeObjectKeys: Boolean = false,
    )

    fun requestAi(
        doc: OpenDocument,
        field: FieldDef,
        path: YPath,
        wholeDocument: Boolean = false,
        relatedFiles: List<Path> = emptyList(),
        mergeObjectKeys: Boolean = false,
    ) {
        aiRequest = AiRequest(doc, field, path, wholeDocument, relatedFiles, mergeObjectKeys)
    }

    /**
     * エンティティの関連文書（R24-02）: 出現ファイル→参照先→参照元の順で重複排除して返す。
     * AI生成の「関連文書」候補（内容の文脈）に使う。exclude は生成対象の文書自身。
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

    fun dismissAi() { aiRequest = null }

    // ---------------------------------------------------------------- 操作（型の操作とアクション）

    /**
     * 操作の作業（対象と受け取る値の入力 → 計画 → 適用）。浮いたパネル（ui/ActionPane.kt の ActionPanelHost）に出す。入力の途中・
     * 計画・結果はコンポーザブルの外（ここ）に置き、パネルを隠しても画面を離れても消えない。受け取る値はフォームの部品（FormCtx）で
     * 編集するため、値の木を持つ仮の文書を 1 つ持つ（どこにも保存しない。編集の記録先が無いので、セッションの履歴や dirty には載らない）。
     */
    class ActionWork(val entry: ActionEntry, target: String?, val form: OpenDocument, val insertInto: InsertTarget? = null) {
        var preparedAt: LocalDateTime = LocalDateTime.now()
        var baseline: Map<String, Any?> = emptyMap()
        var sourceStamp: String = ""
        var explicit: Map<String, Any?> = emptyMap()
        var preparedTarget: String? = target
        var targetDraft by mutableStateOf(target.orEmpty())
        var targetEditing by mutableStateOf(false)
        var planSignature: String? = null
        var planWorkspaceVersion: Long? = null
        var planEditVersion: Long? = null
        var plannedAt: LocalDateTime? = null
        fun invalidate() {
            plan = null; preview = null; error = null; applied = null
            planSignature = null; planWorkspaceVersion = null
            planEditVersion = null
        }
        val values: YNode.Map get() = form.root as YNode.Map
        /**
         * 対象（受け手）の欄の木。要素を受け手にする操作ではパネルの「対象」欄（その型の要素だけが候補）で選び、入口が受け手を
         * 知っていれば（エンティティページ・行のメニュー・検査の指摘）先に入る。文書を受け手にする操作では相対パスが入り、欄は出ない。
         */
        val targetHolder: YNode.Map = YNode.Map().also { m -> target?.let { m.entries[TARGET_KEY] = YNode.Scalar(it) } }
        /** 受け手（要素の ID か文書の相対パス。空なら未選択）。 */
        val target: String?
            get() = (targetHolder.entries[TARGET_KEY] as? YNode.Scalar)?.value?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        var plan by mutableStateOf<ActionPlan?>(null)
        /** 計画の変更セットを適用した後の姿の検証（ドライラン）。 */
        var preview by mutableStateOf<ChangeSetResult?>(null)
        var error by mutableStateOf<String?>(null)
        var applied by mutableStateOf<String?>(null)
        val title: String get() = entry.label ?: entry.name

        companion object {
            const val TARGET_KEY = "target"
        }
    }

    /**
     * 配列への追加の作業の受け手（配列エディタの追加ボタン・ビューの新規行から。要素の型の create に受け取る値があるとき）。
     * alternatives はその配列へ足すアクション（橋渡し。パネルの「操作に切り替える」）。
     */
    class InsertTarget(val doc: OpenDocument, val arrayPath: YPath, val arrayDef: FieldDef, val alternatives: List<ActionEntry> = emptyList())

    /** 操作の適用で積んだ履歴の id（閲覧専用のときの取り消し・やり直しは、これらだけ保存まで行う）。 */
    private val actionRecordIds = mutableSetOf<Long>()

    // ---------------------------------------------------------------- 初期化ウィザード（新しいワークスペースを作る。architecture/workspace.md「ワークスペースの初期化」）

    /** 初期化ウィザードの作業。`fixedRoot` は、開いている未初期化のワークスペースから起動したときの場所（変えられない）。 */
    class InitWizardWork(val fixedRoot: Path?)

    /** 開いているウィザード。null = 閉じている。浮いたパネル（ui/InitWizard.kt）が Main からスペースに関係なく載せる。 */
    var initWizard by mutableStateOf<InitWizardWork?>(null)

    fun startInitWizard(root: Path? = null) { initWizard = InitWizardWork(root) }
    fun closeInitWizard() { initWizard = null }

    /** 後入れの直後に設計スペースで「既存文書から推測」を開く印。設計スペースが読んで消す。 */
    var pendingDesignInfer by mutableStateOf(false)

    /** 初期化したワークスペースを開く。後入れなら設計スペースの推測へ、それ以外は openWorkspace の着地（ダッシュボードか編集）。 */
    fun openInitialized(root: Path, adopted: Boolean) {
        initWizard = null
        openWorkspace(root)
        if (adopted) {
            memory.designZone = WorkspaceLayout.Zone.SCHEMAS
            pendingDesignInfer = true
            showSpace(UiSpace.DESIGN)
        }
    }

    /** 未初期化（`.yde/` も `_schemas/` も無い）ワークスペースか。編集スペースの案内に使う（組込みのスキーマは数えない）。 */
    fun isUninitializedWorkspace(): Boolean = workspace?.let { !WorkspaceInit.isInitialized(it) } ?: false

    var actionWork by mutableStateOf<ActionWork?>(null)

    /** 操作のパネル（浮いたパネル）を出しているか。隠しても作業（actionWork）は残り、右ペインの「操作」タブから開き直せる。 */
    var actionPanelOpen by mutableStateOf(false)

    private fun newActionWork(entry: ActionEntry, target: String?, insertInto: InsertTarget? = null,
                              now: LocalDateTime = LocalDateTime.now()): ActionWork? {
        val ws = workspace ?: return null
        val schema = schemas.firstOrNull { it.id == entry.definedIn }
        val values = YNode.Map()
        entry.params.forEach { p -> p.key?.let { key -> p.default?.let { values.entries[key] = YamlIO.fromAny(it) } } }
        val form = OpenDocument(ws.resolve(".yde/action-params.yaml"), values, schema, "", null)
        return ActionWork(entry, target, form, insertInto).also {
            it.preparedAt = now
            it.baseline = values.entries.mapValues { (_, node) -> YamlIO.toAny(node) }
            form.onEdited = { _ -> it.invalidate() }
            actionWork = it
            actionPanelOpen = true
            if (entry.input != null) resetActionInput(it, target, emptyMap()).onFailure { e -> it.error = e.message }
        }
    }

    /** パネルを隠す（作業は残る）／開き直す。 */
    fun hideActionPanel() { actionPanelOpen = false }
    fun showActionPanel() { if (actionWork != null) actionPanelOpen = true }

    /** 型の要素の候補（パネルの「対象」欄）: 上書き索引のうちその型の出現を持つ ID と、その 1 行要約。 */
    fun entityCandidates(type: String): List<Pair<String, String?>> =
        overlayIndex().entries.values.asSequence()
            .filter { occurrences -> occurrences.any { it.entityType == type } }
            .map { occurrences -> occurrences.first().id to occurrences.firstNotNullOfOrNull { it.title } }
            .sortedBy { it.first }
            .toList()

    /**
     * 配列へ要素を足す（配列エディタの追加ボタン・ビューの新規行。architecture/actions.md「作成経路の寄せ」）: 要素の型の create に
     * 受け取る値があれば右ペインのカードで入力し（戻り値 null）、無ければその場で足す（配列の insert の決まり＝採番つき。戻り値は番号）。
     * 足した要素は未保存の編集（Undo 可）。
     */
    fun addArrayElement(doc: OpenDocument, arrayPath: YPath, field: FieldDef): Result<Int?> = runCatching {
        val schema = doc.schema
        val arrayDef = schema?.resolve(field) ?: field
        val itemDef = arrayDef.item?.let { schema?.resolve(it) ?: it }
        val create = itemDef?.methods?.firstOrNull { it.isCreate }
        if (create != null && create.params.isNotEmpty() && !readOnlyMode) {
            val entry = ActionEntry("insert", "insert", "${arrayDef.displayLabel} に行を追加", create.description, null, create.params, schema?.id ?: "")
            // 橋渡し: この配列へ足すアクションがあれば、パネルで「操作に切り替える」を案内する
            val alternatives = coreWs?.let { cw -> schema?.id?.let { sid -> ActionPlanner.insertingInto(cw.mirror.schemas, sid, arrayPath) } }.orEmpty()
            newActionWork(entry, null, InsertTarget(doc, arrayPath, arrayDef, alternatives))
            return@runCatching null
        }
        insertArrayElement(doc, arrayPath, arrayDef, emptyMap())
    }

    /** 追加の作業（カード）の値で、配列へ要素を足す。 */
    fun applyInsert(): Result<Int> = runCatching {
        val work = actionWork ?: error("操作が選ばれていません")
        val into = work.insertInto ?: error("追加の作業ではありません")
        val index = insertArrayElement(into.doc, into.arrayPath, into.arrayDef, actionParams(work))
        dismissAction()
        status = "${into.arrayDef.displayLabel} に要素を足しました（未保存。Ctrl+Z で戻せます）"
        index
    }

    /** 配列の末尾へ要素を足す: 要素の中身は型の create と配列の insert の決まり（カーネルの 1 実装）。配列が無ければ作る。 */
    private fun insertArrayElement(doc: OpenDocument, arrayPath: YPath, arrayDef: FieldDef, params: Map<String, Any?>): Int {
        check(!readOnlyMode) { "閲覧専用モードでは編集できません" }
        val root = doc.root as? YNode.Map ?: error("文書をパースできません: ${doc.parseError}")
        val seq = ensureSeq(root, arrayPath)
        val tree = doc.schema?.tree
        val element = if (tree == null) YNode.Map() else {
            val made = ActionPlanner.newElement(tree, arrayDef, seq, params, overlayIndex().entries.keys, LocalDateTime.now(), dedupe = params.isNotEmpty())
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

    /** 要素に使える操作（エンティティページの操作メニュー）。 */
    fun actionsForEntity(id: String): List<ActionEntry> =
        coreWs?.let { cw -> runCatching { ActionPlanner.forEntity(cw.mirror, id) }.getOrDefault(emptyList()) }.orEmpty()

    /** 型に使える操作（台帳の「要素の操作」＝受け手をパネルで選ぶ入口と、未保存の行のメニュー）。 */
    fun actionsForEntityType(type: String): List<ActionEntry> =
        coreWs?.let { cw -> runCatching { ActionPlanner.forEntityType(cw.mirror, type) }.getOrDefault(emptyList()) }.orEmpty()

    /** 文書に使える操作（文書ヘッダのメニュー）。 */
    fun actionsForDocument(file: Path): List<ActionEntry> =
        coreWs?.let { cw -> runCatching { ActionPlanner.forDocument(cw.mirror, relPath(file)) }.getOrDefault(emptyList()) }.orEmpty()

    /** 受け手の無い操作（コマンドパレット）。 */
    fun actionsForWorkspace(): List<ActionEntry> = coreWs?.let { ActionPlanner.forWorkspace(it.mirror.schemas) }.orEmpty()

    fun actionAvailability(entry: ActionEntry, target: String?): dev.toros.yde.workspace.ActionAvailability =
        (session ?: coreWs?.mirror)?.let { ActionPlanner.availability(it, ActionRequest(entry.name, target)) }
            ?: dev.toros.yde.workspace.ActionAvailability(false, "ワークスペースが開かれていません")

    /**
     * 操作を始める: 対象と受け取る値を入れるパネルを出す。target は受け手（要素の ID か文書の相対パス。null ならパネルで選ぶ）。
     * 閲覧専用モードでも使える（閲覧専用が封じるのは編集＝未保存の木の変更。操作は確認してから書いて保存する）。
     */
    fun startAction(entry: ActionEntry, target: String?, now: LocalDateTime = LocalDateTime.now()) {
        newActionWork(entry, target, now = now)
    }

    private fun resetActionInput(work: ActionWork, target: String?, explicit: Map<String, Any?>): Result<Unit> = runCatching {
        val current = session ?: coreWs?.mirror ?: error("ワークスペースが開かれていません")
        val prepared = ActionPlanner.prepareInput(current, ActionRequest(work.entry.name, target, explicit), work.preparedAt)
        work.values.entries.clear()
        prepared.values.forEach { (key, value) -> work.values.entries[key] = YamlIO.fromAny(value) }
        work.baseline = prepared.values
        work.sourceStamp = prepared.sourceStamp
        work.preparedTarget = target
        work.explicit = explicit
        work.targetHolder.entries.clear()
        target?.let { work.targetHolder.entries[ActionWork.TARGET_KEY] = YNode.Scalar(it) }
        work.targetDraft = target.orEmpty(); work.targetEditing = false
        work.invalidate(); work.form.version++
    }

    fun actionInputModified(): Boolean = actionWork?.let { work ->
        YamlIO.dumpPlain(work.values) != YamlIO.dumpPlain(YamlIO.fromAny(work.baseline))
    } ?: false

    fun changeActionTarget(target: String?): Result<Unit> {
        val work = actionWork ?: return Result.failure(IllegalStateException("操作が選ばれていません"))
        return resetActionInput(work, target?.trim()?.takeIf { it.isNotEmpty() }, emptyMap())
    }

    fun reloadActionInput(): Result<Unit> {
        val work = actionWork ?: return Result.failure(IllegalStateException("操作が選ばれていません"))
        return resetActionInput(work, work.target, work.explicit)
    }

    private fun checkActionSource(work: ActionWork) {
        require(!work.targetEditing) { "対象の変更を確定してください" }
        if (work.entry.input == null) return
        require(work.preparedTarget == work.target) { "対象を変更したため、初期値を読み直してください" }
        val current = session ?: coreWs?.mirror ?: error("ワークスペースが開かれていません")
        val prepared = ActionPlanner.prepareInput(current, ActionRequest(work.entry.name, work.target, work.explicit), work.preparedAt)
        require(prepared.sourceStamp == work.sourceStamp) { "入力元が変更されています。初期値を読み直してください" }
    }

    private fun actionSignature(work: ActionWork): String = "${work.target}\n${YamlIO.dumpPlain(work.values)}"

    /** 追加の作業から、その配列へ足すアクションへ切り替える（橋渡し）。同じキーの値は持ち越し、受け手が文書ならその文書にする。 */
    fun switchInsertToAction(entry: ActionEntry) {
        val work = actionWork ?: return
        val into = work.insertInto ?: return
        val carried = actionParams(work)
        val target = entry.documentSchema?.let { relPath(into.doc.file) }
        startAction(entry, target)
        val next = actionWork?.takeIf { it.entry === entry } ?: return
        if (entry.input != null) {
            resetActionInput(next, target, carried.filterKeys { key -> entry.params.any { it.key == key } })
                .onFailure { next.error = it.message }
            return
        }
        carried.forEach { (k, v) -> if (next.entry.params.any { it.key == k }) next.values.entries[k] = YamlIO.fromAny(v) }
        next.form.version++
    }

    /**
     * 名前（アクションの id か 型名.操作名）で操作を始める（検査の対応「操作を起動する」から）。prefill は先に入れる値で、
     * 受け取る値に無い名前は捨てる。見つからなければ status に案内する。
     */
    fun startAction(name: String, target: String?, prefill: Map<String, Any?> = emptyMap()) {
        val cw = coreWs ?: return
        val entry = ActionPlanner.catalog(cw.mirror.schemas).firstOrNull { it.name == name && it.kind != "create" }
            ?: run { status = "操作「$name」が見つかりません（スキーマの actions / methods に定義します）"; return }
        startAction(entry, target)
        val work = actionWork?.takeIf { it.entry === entry } ?: return
        if (entry.input != null) {
            resetActionInput(work, target, prefill.filterKeys { key -> entry.params.any { it.key == key } })
                .onFailure { work.error = it.message }
            return
        }
        prefill.forEach { (k, v) -> if (work.entry.params.any { it.key == k }) work.values.entries[k] = YamlIO.fromAny(v) }
        work.form.version++
    }

    /** input のない従来の操作は空欄を省略する。changed は初期値との差分と必須・明示指定を渡す。 */
    private fun actionParams(work: ActionWork): Map<String, Any?> {
        val tree = work.form.schema?.tree
        val required = work.entry.params.filter { p ->
            tree?.requiredAt(tree.resolve(p) ?: p, null) ?: p.required
        }.mapNotNull { it.key }.toSet()
        return work.values.entries.entries
            .filterNot { (_, v) -> work.entry.input == null && v is YNode.Scalar && v.value?.toString().isNullOrBlank() }
            .filter { (key, value) -> work.entry.input?.submit != "changed" || key in work.explicit || key !in work.baseline ||
                key in required || YamlIO.dumpPlain(value) != YamlIO.dumpPlain(YamlIO.fromAny(work.baseline[key])) }
            .associate { (k, v) -> k to YamlIO.toAny(v) }
    }

    /** 計画を作り、適用後の姿を検証する（原本は変えない）。計画できない理由は作業の error に出す。 */
    fun planAction() {
        val work = actionWork ?: return
        val cw = coreWs ?: return
        work.error = null
        work.plan = null
        work.preview = null
        work.applied = null
        if (work.entry.entityType != null && work.target == null) {
            work.error = "対象を選んでください（${work.entry.entityType} の要素）"
            return
        }
        val planTime = LocalDateTime.now()
        runCatching {
            checkActionSource(work)
            val request = ActionRequest(work.entry.name, work.target, actionParams(work))
            ActionPlanner.checkPreconditions(session ?: cw.mirror, request)
            ActionPlanner.plan(cw.mirror, request, planTime)
        }
            .onSuccess { plan ->
                work.plan = plan
                work.planSignature = actionSignature(work)
                work.planWorkspaceVersion = cw.mirror.version
                work.planEditVersion = session?.version
                work.plannedAt = planTime
                work.preview = if (plan.isEmpty) null else cw.apply(plan.changeSet, dryRun = true)
            }
            .onFailure { work.error = it.message ?: "計画できません" }
    }

    /** 確認後の入力・ワークスペース変更を検知し、表示中の古い差分を取り下げる。 */
    fun refreshActionPlan() {
        val work = actionWork ?: return
        if (work.plan == null || work.applied != null) return
        if (work.planSignature != actionSignature(work) || work.planWorkspaceVersion != coreWs?.mirror?.version ||
            work.planEditVersion != session?.version) {
            work.invalidate()
            work.error = "入力またはワークスペースが変更されています。確認し直してください"
        }
    }

    /** 計画した変更セットの文書ごとの、いまの内容と適用後の内容（差分の表示用）。 */
    fun actionDiffTexts(plan: ActionPlan): Map<String, Pair<String, String>> = runCatching {
        val cw = coreWs ?: return emptyMap()
        val staging = Staging(cw)
        plan.changeSet.changes.forEach { c -> if (c.create) staging.createDocument(c.file, c.content!!) else staging.applyOps(c.file, c.ops) }
        plan.changeSet.changes.associate { c ->
            c.file to ((staging.touchedFiles().firstOrNull { it.rel == c.file }?.baseText ?: "") to (staging.proposedText(c.file) ?: ""))
        }
    }.getOrDefault(emptyMap())

    /**
     * 計画を適用して保存する（変更セットの承認と同じ経路。検証・競合の基準はディスクの内容、書込は編集用のセッションの
     * 1 トランザクション＋保存＝Undo 1 回で全部戻る）。検証エラーが残るなら書かない（allowErrors で書く）。
     * 対象に未保存の編集があれば拒否する（適用→保存が未保存の編集まで書いてしまうのを防ぐ）。
     */
    fun applyAction(allowErrors: Boolean = false): Result<String> = runCatching {
        val work = actionWork ?: error("操作が選ばれていません")
        val plan = work.plan ?: error("先に「確認」で計画を作ってください")
        require(!plan.isEmpty) { "変更はありません" }
        val cw = coreWs ?: error("ワークスペースが開かれていません")
        val ws = workspace ?: error("ワークスペースが開かれていません")
        val s = session ?: error("セッションが開かれていません")
        val files = plan.changeSet.changes.map { ws.resolve(it.file).normalize() }
        files.filter { docCache[it]?.dirty == true }.takeIf { it.isNotEmpty() }
            ?.let { error("エディタに未保存の変更があるため適用できません: ${it.joinToString(", ") { f -> relPath(f) }}") }
        checkActionSource(work)
        require(work.planSignature == actionSignature(work) && work.planWorkspaceVersion == cw.mirror.version &&
            work.planEditVersion == s.version) {
            work.invalidate()
            "入力またはワークスペースが変更されています。確認し直してください"
        }
        // 読み取りだけの前提も、適用の直前に同じ計画器で再検査する。
        val request = ActionRequest(work.entry.name, work.target, actionParams(work))
        ActionPlanner.checkPreconditions(s, request)
        ActionPlanner.plan(cw.mirror, request, work.plannedAt!!)
        val result = withHistoryLocation(s) { cw.applyViaSession(s, plan.changeSet, requireValid = !allowErrors) }
        if (!result.ok) {
            val reasons = result.results.filter { it.message != null }.joinToString("\n") { "${it.file}: ${it.message}" }
            error(
                reasons.ifBlank {
                    if (result.errorIssueCount > 0) "検証エラーが残るため適用しませんでした（エラー ${result.errorIssueCount} 件）" else "適用に失敗しました"
                }
            )
        }
        refreshAfterSessionWrite(plan.changeSet.changes.map { DocumentId(it.file) })
        s.peekUndo()?.let { actionRecordIds += it.id }
        val message = "${work.title} を適用して保存しました（${plan.changeSet.changes.size} ファイル。Ctrl+Z で 1 回で戻せます）"
        work.applied = message
        status = message
        message
    }

    fun dismissAction() { actionWork = null; actionPanelOpen = false }

    // ---------------------------------------------------------------- workspace

    fun openWorkspace(dir: Path) {
        dismissHistoryNotice()
        pendingReload = null
        workspace = dir.toAbsolutePath().normalize()
        coreWs = null
        session = null
        docCache.clear()
        memory.clear()
        openPreview = null
        openMarkdown = null
        openImage = null
        val treePrefs = dev.toros.yde.app.core.FileTreePreferences.forWorkspace(workspace!!)
        runCatching {
            treePrefs.expanded().forEach { (path, expanded) -> memory.treeExpanded[workspace!!.resolve(path)] = expanded }
            memory.treeOptions = treePrefs.options()
        }
        fileTreeRevision++
        flowSelections = emptyMap()
        flowDisplayOptions = emptyMap()
        flowViewId = null
        dashboardId = null
        dashboardEntityId = null
        flowDocument = null
        openDoc = null
        highlightPath = null
        backStack = emptyList()
        forwardStack = emptyList()
        workspaceView = 0
        uiSpace = UiSpace.EDIT
        lastLedgerView = 1
        // ワークスペース履歴（R10-05）: アプリグローバルに永続化し、スタート画面・パレットの切替候補にする
        recentWorkspaces = (listOf(workspace!!) + recentWorkspaces.filter { it != workspace }).take(10)
        appPrefs.put("recentWorkspaces", recentWorkspaces.joinToString("\n"))
        recentFiles = recentPrefs.get(workspaceKey(), "").lineSequence().filter { it.isNotBlank() }
            .map { workspace!!.resolve(it).normalize() }.filter(Files::exists).take(12).toList()
        reload()
        historyViewCatalog()?.let { catalog ->
            catalog.defaultDashboard()?.let { dashboard ->
                dashboardId = dashboard.id
                uiSpace = UiSpace.DASHBOARD
            }
        }
    }

    /** ディスク版を候補として読み、編集・下書き・履歴を保持できる場合だけ取り込む。 */
    fun reload() {
        if (fileOperationBusy) return
        val ws = workspace ?: return
        val candidate = runCatching { prepareReload(ws) }.getOrElse {
            status = "再読込に失敗しました: ${it.message}"
            return
        }
        val cw = candidate.workspace
        val current = session
        val protected = protectedDocumentIds() + current?.dirtyDocuments().orEmpty()
        var notice: String? = null
        if (current != null) {
            val result = current.refreshFrom(cw.mirror, protected)
            if (!result.ok) {
                docCache.values.filter { it.dirty || it.draftDirty || it.hasPendingSourceInput }.forEach { doc ->
                    if (runCatching { readStoreText(doc) != doc.expectedStoreText }.getOrDefault(false)) doc.externalConflict = true
                }
                status = result.message ?: "再読込に失敗しました"
                return
            }
            notice = result.message
        }
        coreWs = cw
        mirrorCore(cw, candidate.markdown, candidate.editorOnly)
        if (current == null) reopenSession(ws, fromMirror = true)
        directories = emptyList()
        fileTreeRevision++
        val existing = yamlFiles.toSet() + editorOnlyFiles
        docCache.keys.filter { it !in existing }.toList().forEach { removed ->
            if (documentOf(removed) !in protected) docCache.remove(removed)
        }
        docCache.values.forEach { doc ->
            doc.schema = schemaFor(doc.file)
            // 時刻だけでなく本文を比較する。変更のない下書き・カーソルはそのまま残す。
            val text = cw.mirror.expectedStoreText(documentOf(doc.file))
            if (text != doc.expectedStoreText) {
                if (documentOf(doc.file) in protected) doc.externalConflict = true
                else reloadDocumentFromDisk(doc, force = true, knownText = text)
            }
        }
        openDoc?.let { if (it.file !in existing && documentOf(it.file) !in protected) openDoc = null }
        openMarkdown?.let { if (!Files.exists(it)) openMarkdown = null }
        openImage?.let { if (!Files.exists(it)) openImage = null }
        indexVersion++
        workspaceStamp = candidate.stamps
        status = notice ?: "ワークスペース: $ws ／ YAML ${yamlFiles.size} 件・スキーマ ${schemas.size} 件・ID ${index.entries.size} 件"
    }

    class PendingReload internal constructor(
        val files: List<String>, val historyCount: Int, internal val stamp: List<Any?>,
    )
    var pendingReload by mutableStateOf<PendingReload?>(null)
        private set

    private fun reloadConfirmationStamp(): List<Any?> = listOf(
        workspace, session, session?.version, session?.historySize, dirtyFiles().toSet(),
        docCache.mapValues { (_, doc) -> listOf(doc.version, doc.source.text.toString(), doc.sourceEpoch, doc.dirty, doc.expectedStoreText) },
    )

    fun requestDiscardAndReload() {
        if (workspace == null || fileOperationBusy) return
        val files = dirtyFiles() + docCache.values.filter { it.hasPendingSourceInput }.map { it.file }
        pendingReload = PendingReload(files.map(::relPath).sorted(), session?.historySize ?: 0, reloadConfirmationStamp())
    }

    fun cancelDiscardAndReload() { pendingReload = null }

    /** 確認対象が変わっていない場合だけ、読込済みの候補へ交換する。ディスクへの書込は行わない。 */
    fun confirmDiscardAndReload() {
        val pending = pendingReload ?: return
        pendingReload = null
        if (fileOperationBusy || pending.stamp != reloadConfirmationStamp()) {
            status = "確認中に編集や履歴が変わったため中止しました。もう一度操作してください"
            return
        }
        val ws = workspace ?: return
        val prepared = runCatching {
            val candidate = prepareReload(ws)
            candidate to candidate.workspace.mirror.fork()
        }.getOrElse {
            status = "再読込に失敗したため、編集と履歴を残しました: ${it.message}"
            return
        }
        if (pending.stamp != reloadConfirmationStamp()) {
            status = "読込中に編集や履歴が変わったため中止しました"
            return
        }
        val location = currentLocation()
        coreWs = prepared.first.workspace
        session = prepared.second
        docCache.clear()
        historyLocations.clear()
        dismissHistoryNotice()
        pendingHistory = null
        pendingTextSave = null
        openDoc = null
        openMarkdown = null
        openImage = null
        openPreview = null
        derivedCache = null
        mirrorCore(prepared.first.workspace, prepared.first.markdown, prepared.first.editorOnly)
        directories = emptyList()
        fileTreeRevision++
        editVersion++
        indexVersion++
        workspaceStamp = prepared.first.stamps
        if (!isStale(location)) restore(location)
        status = "未保存編集と Undo/Redo 履歴を破棄し、ディスクから開き直しました（ファイルは変更していません）"
    }

    private fun protectedDocumentIds(): Set<DocumentId> = docCache.values
        .filter { it.dirty || it.draftDirty || it.hasPendingSourceInput || it.externalConflict }
        .map { documentOf(it.file) }.toSet()

    private fun hasRetainedEdits(): Boolean = session?.let {
        it.canUndo() || it.canRedo() || it.dirtyDocuments().isNotEmpty()
    } == true || protectedDocumentIds().isNotEmpty()

    private class ReloadCandidate(
        val workspace: dev.toros.yde.app.core.Workspace,
        val markdown: List<Path>, val editorOnly: List<Path>, val stamps: Map<Path, Long>,
    )

    /** 一覧の走査も交換前に済ませ、読込失敗で編集や履歴を半分だけ破棄しない。 */
    private fun prepareReload(ws: Path): ReloadCandidate {
        require(Files.isDirectory(ws)) { "ワークスペースのフォルダーがありません: $ws" }
        val cw = dev.toros.yde.app.core.Workspace.open(ws)
        return ReloadCandidate(cw, dev.toros.yde.app.core.WorkspaceScan.markdownFiles(ws),
            editorOnlyFiles(cw), snapshotFiles(ws))
    }

    private fun editorOnlyFiles(cw: dev.toros.yde.app.core.Workspace): List<Path> =
        if (cw.layout.ledger.isEmpty()) emptyList()
        else dev.toros.yde.app.core.WorkspaceScan.editorYamlFiles(cw.root).filter { it !in cw.files.toSet() }

    /** core.Workspace の状態を Compose 購読用ミラーへ反映する。走査は状態反映より先に行う。 */
    private fun mirrorCore(
        cw: dev.toros.yde.app.core.Workspace,
        markdown: List<Path> = dev.toros.yde.app.core.WorkspaceScan.markdownFiles(cw.root),
        editorOnly: List<Path> = editorOnlyFiles(cw),
    ) {
        schemas = cw.schemas
        schemaErrors = cw.schemaErrors
        relations = cw.relations
        enforcement = cw.enforcement
        entityTypes = cw.entityTypes
        schemaGraph = cw.schemaGraph
        yamlFiles = cw.files
        markdownFiles = markdown
        // 台帳外のエディタ層ファイル（R13-04）。ledger 宣言が無ければ走査対象と一致するため空になる
        editorOnlyFiles = editorOnly
    }

    fun rebuildIndex() {
        val cw = coreWs ?: return
        // 索引の供給はセッション派生に一本化された（RB-07）ため、全再構築は refresh 経由で行う
        cw.refresh()
        mirrorCore(cw)
        indexVersion++
    }

    // ---------------------------------------------------------------- 新経路セッション（A-1・RA-01）

    /**
     * 新経路セッションを開く（失敗してもアプリは旧経路で動き続ける）。
     * 組込スキーマ（メタスキーマ等）を注入し、`_schemas`・定義ゾーンの検証を旧経路と揃える（C-2）。
     *
     * [fromMirror]: ディスクを映すセッション（core.Workspace の内部）を読み直した直後なら、その読み込み済みの状態から
     * 起こす（`fork`）。2つのセッションは別物のまま——編集用は未保存の編集と作業中のスキーマを持ち、ディスクを映す側は
     * エージェント・自動実行・CLI の基準（docs/architecture/index-references.md「索引の二層」 の二層）——で、読み込みだけを1回にする。
     */
    private fun openSession(ws: Path, fromMirror: Boolean = false): WorkspaceSession? = runCatching {
        val forked = if (fromMirror) runCatching { coreWs?.mirror?.fork() }.getOrNull() else null
        forked ?: run {
            val wsLayout = coreWs?.layout ?: WorkspaceLayout.of(ws)
            WorkspaceSession(FileSystemDocumentStore(ws), builtins = BuiltinSchemas.kernelSchemas(wsLayout))
        }
    }.getOrNull()

    /**
     * セッションを開き直し、編集中（dirty）の文書を working 状態へ再反映する（RA-05）。
     * 開き直しはディスク基準になるため、UIの「見たまま」を編集直結で回復する。
     */
    private fun reopenSession(ws: Path, fromMirror: Boolean = false) {
        session = openSession(ws, fromMirror)
        historyLocations.clear()
        docCache.values.filter { it.dirty }.forEach(::pushEditToSession)
    }

    /**
     * 編集内容をセッションの working 状態へ直結する（A-3 前半・RA-05）。markEdited（Undo/Redo 含む）の
     * たびに呼ばれ、セッションが常に「見たまま」を所有する（UX-03 の構造保証の最終形）。
     * パース不能（root=null）の編集は反映せず、derivedState() が null オーバーレイで文書集合から外す。
     * ここでは履歴は積まない（replaceDocument。履歴の記録は recordEditToSession）。
     */
    private fun pushEditToSession(doc: OpenDocument) {
        val s = session ?: return
        val root = doc.root ?: return
        val id = DocumentId(relPath(doc.file))
        if (!ensureAttached(doc, s, id, root)) return  // 構成ファイル等、セッションが文書として扱わないパス
        runCatching { s.replaceDocument(id, YDocument(id, listOf(root) + doc.extraDocs)) }
    }

    /**
     * セッションに載っていない文書を載せる（R43-04）: テキスト文書（壊れたまま保存／開いた時点で解析不能）が解析できるように
     * なったとき、文書集合へ戻す（保存済みの内容との差で dirty になる）。セッションが文書として扱わない場所なら false。
     */
    private fun ensureAttached(doc: OpenDocument, s: WorkspaceSession, id: DocumentId, root: YNode): Boolean {
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

    /** 編集や履歴があれば候補から保護して取り込み、状態のないセッションだけ従来の増分経路を使う。 */
    private fun syncSessionWithDisk(changed: List<Path>, removed: List<Path> = emptyList()) {
        val ws = workspace ?: return
        val s = session ?: run { reopenSession(ws); return }
        if (hasRetainedEdits()) {
            val disk = coreWs?.mirror ?: return
            val result = s.refreshFrom(disk, protectedDocumentIds())
            if (!result.ok) status = result.message ?: "再読込に失敗しました"
            return
        }
        if (coreWs?.layout != s.layout) { reopenSession(ws); return }
        runCatching {
            val changedIds = changed.map(::documentOf)
            val removedIds = removed.map(::documentOf)
            if (changedIds.isEmpty() && removedIds.isEmpty()) return
            s.noteStoreChanges(
                changed = changedIds,
                removed = removedIds,
            )
        }.onFailure { reopenSession(ws) }
    }

    /** derivedState() のキャッシュ（(indexVersion, editVersion) が変わるまで再計算しない。overlayCache と同じ規律）。 */
    private var derivedCache: Pair<Pair<Int, Int>, WorkspaceDerivedState>? = null

    /**
     * 新経路の派生状態（A-1・RA-01）: セッションのworking状態＝「見たまま」の構造・意味派生
     * （UX-03 のカーネル供給版）。編集はセッション直結（RA-05・pushEditToSession）のため、
     * オーバーレイはパース不能の編集中文書（文書集合から外す）だけになった。
     * indexVersion / editVersion を読むため、コンポジション中の呼び出しは自動で購読される。
     */
    fun derivedState(): WorkspaceDerivedState? {
        val s = session ?: return null
        val key = indexVersion to editVersion
        derivedCache?.let { (k, cached) -> if (k == key) return cached }
        // パース不能（root=null）の編集中文書は判定から外す（旧オーバーレイと同じ扱い）
        val overlays = docCache.values
            .filter { it.dirty && isIndexed(it.file) && it.root == null }
            .associate { DocumentId(relPath(it.file)) to null as YDocument? }
        val result = s.derivedWith(overlays)
        derivedCache = key to result
        return result
    }

    // ファイル走査は core.WorkspaceScan（ヘッドレス層）と共通の実装を使う。
    private fun scanDirectories(ws: Path): List<Path> = dev.toros.yde.app.core.WorkspaceScan.directories(ws)

    private fun snapshotFiles(ws: Path): Map<Path, Long> = dev.toros.yde.app.core.WorkspaceScan.stamps(ws)

    /**
     * UI の周期処理・ファイル監視から呼び、外部での作成・変更・削除を検出する。
     * eventHint = true（FileWatcher のイベント通知起点）では、スタンプ（mtime）が不変でも
     * セッションの content diff（`externalChanges`。A-4・RA-07）で既知文書の変更を確認する——
     * 同一 mtime での上書き等、スタンプ比較の盲点をカーネル検出で塞ぐ。
     */
    fun pollExternalChanges(eventHint: Boolean = false) {
        val ws = workspace ?: return
        val cw = coreWs ?: return
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
        changed.forEach { file -> docCache[file]?.let { reloadDocumentFromDisk(it, force = false) } }
        // 増分か全再構築か（スキーマ変更時のみ全再構築）の振り分けは core に一元化されている（R9-02）
        cw.noteDiskChanges(
            changed = changed.filter { it in next.keys },
            removed = changed.filter { it !in next.keys },
        )
        mirrorCore(cw)
        syncSessionWithDisk(
            changed = changed.filter { it in next.keys },
            removed = changed.filter { it !in next.keys },
        )
        directories = emptyList()
        fileTreeRevision++
        docCache.values.forEach { it.schema = schemaFor(it.file) }
        indexVersion++
        workspaceStamp = next
        status = "外部での変更を検出しました（${changed.size} 件）"
    }

    /**
     * スタンプ不変時のカーネル検出（A-4・RA-07）: 既知文書の content diff（session.externalChanges）で
     * 変更を取り込む。新規作成・削除はスタンプ比較の領分のままなのでここでは扱わない。
     */
    private fun applyContentDetectedChanges(ws: Path, cw: dev.toros.yde.app.core.Workspace) {
        val s = session ?: return
        val changedDocs = runCatching { s.externalChanges() }.getOrNull().orEmpty()
        if (changedDocs.isEmpty()) return
        if (hasRetainedEdits()) { reload(); return }
        val files = changedDocs.map { ws.resolve(it.value).normalize() }
        files.forEach { file ->
            docCache[file]?.let { doc ->
                // mtime 不変のため force で取り直す。未保存編集がある場合は上書きせず競合として提示する
                if (doc.dirty || doc.draftDirty || doc.hasPendingSourceInput) doc.externalConflict = true
                else reloadDocumentFromDisk(doc, force = true)
            }
        }
        cw.noteDiskChanges(changed = files)
        mirrorCore(cw)
        syncSessionWithDisk(changed = files)
        docCache.values.forEach { it.schema = schemaFor(it.file) }
        indexVersion++
        status = "外部での変更を検出しました（${files.size} 件）"
    }

    fun relPath(file: Path): String {
        val ws = workspace ?: return file.toString()
        return IdIndex.relPathOf(ws, file)
    }

    /**
     * ファイルの文書ID（ワークスペース相対パス）。索引・検証・ビューの解決は、場所をこの座標で表す（P27 段D の D-d・D-e）。
     * 画面は、開いている文書をファイル（Path）で持つので、索引へ聞くときに、ここで文書ID へ直す。
     */
    fun documentOf(file: Path): DocumentId = DocumentId(relPath(file))

    /** 文書ID のファイル。ファイルを開く・文書キャッシュを引くなど、Path が要る所でだけ使う。 */
    fun fileOf(document: DocumentId): Path =
        (workspace?.resolve(document.value) ?: Path.of(document.value)).normalize()

    // 文書ID で呼べる入口（索引・検証・ビューの解決の結果は、場所を文書ID で持つ。ファイルへ直すのは、ここの1か所）
    fun openFile(document: DocumentId, focus: YPath? = null) = openFile(fileOf(document), focus)
    fun ensureDoc(document: DocumentId): OpenDocument = ensureDoc(fileOf(document))
    fun schemaFor(document: DocumentId): SchemaDef? = schemaFor(fileOf(document))
    fun isIndexed(document: DocumentId): Boolean = isIndexed(fileOf(document))

    fun schemaFor(file: Path): SchemaDef? = coreWs?.schemaFor(file)

    /**
     * AI生成の参考文書候補: 同じスキーマが適用される他のYAML文書（更新日時の新しい順）。
     * ダイアログ側はこの並び順の先頭から既定選択する（新しい文書ほど現在の書き方を反映しているとみなす）。
     */
    fun sameSchemaFiles(exclude: Path, schemaId: String): List<Path> =
        coreWs?.files
            ?.filter { it != exclude && schemaFor(it)?.id == schemaId }
            ?.sortedByDescending { file -> runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L) }
            .orEmpty()

    // ---------------------------------------------------------------- documents

    fun docFor(file: Path): OpenDocument? = docCache[file]

    /**
     * 文書をエディタで開かずにキャッシュへ取り込む（ビューグリッドのセル編集用）。
     * 取り込んだ文書は通常の編集と同じく dirty 管理・保存・外部変更検出の対象になる。
     */
    fun ensureDoc(file: Path): OpenDocument = docCache.getOrPut(file) { loadDocument(file) }

    fun openFile(file: Path, focus: YPath? = null) {
        val cur = currentLocation()
        // 実行・設計スペースから同じファイルを開き直す場合も「スペースからの移動」として履歴に積む
        val samePlace = cur.space == UiSpace.EDIT && cur.file == file && (focus == null || focus == cur.path)
        if (!samePlace) pushBack(cur)
        showFile(file, focus)
    }

    /** 履歴に積まずに文書を表示する（openFile の本体。「戻る」「進む」の復元にも使う）。 */
    private fun showFile(file: Path, focus: YPath?) {
        previewLine = 1
        openPreview = null
        val ext = file.fileName.toString().substringAfterLast('.', "").lowercase()
        if (ext !in setOf("yaml", "yml", "md") && !isImage(file)) {
            openDoc = null; openMarkdown = null; openHelp = null; openImage = null
            openPreview = file
            workspaceView = 0
            syncSpaceFromView()
            status = "閲覧: ${relPath(file)}"
            return
        }
        if (isMarkdown(file)) { showMarkdown(file); return }
        if (isImage(file)) { showImage(file); return }
        openMarkdown = null
        openHelp = null
        openImage = null
        val doc = docCache.getOrPut(file) { loadDocument(file) }
        openDoc = doc
        workspaceView = 0
        syncSpaceFromView()
        recentFiles = (listOf(file) + recentFiles.filter { it != file }).take(12)
        recentPrefs.put(workspaceKey(), recentFiles.joinToString("\n") { relPath(it) })
        // 解析できない文書はソース。それ以外は、この文書で最後に見ていたタブ → 無ければ直前の文書のタブ（メタスキーマのフォームは
        // スキーマ文書にしか無いので、そうでなければフォーム）
        val previous = if (editorTab == 3 && !isSchemaDocument(file)) 0 else editorTab
        editorTab = if (doc.root == null) 1 else (memory.editorTab[documentOf(file)] ?: previous)
        highlightPath = focus
        status = "開いています: ${relPath(file)}" + (doc.schema?.let { "（スキーマ: ${it.name}）" } ?: "（スキーマなし・汎用ビュー）")
    }

    private fun isMarkdown(file: Path): Boolean = file.fileName.toString().endsWith(".md", ignoreCase = true)
    private fun isImage(file: Path): Boolean = dev.toros.yde.semantic.schema.FilePath.isImage(file.fileName.toString())

    /** 画像を表示する（file 型の値の拡大表示。表示のみ）。 */
    private fun showImage(file: Path) {
        openDoc = null
        openHelp = null
        openMarkdown = null
        openImage = file
        workspaceView = 0
        syncSpaceFromView()
        highlightPath = null
        status = "画像: ${relPath(file)}"
    }

    /** Markdown 文書を表示する（表示のみ。YAML の文書キャッシュには入れない）。 */
    private fun showMarkdown(file: Path) {
        openDoc = null
        openHelp = null
        openImage = null
        openMarkdown = file
        workspaceView = 0
        syncSpaceFromView()
        recentFiles = (listOf(file) + recentFiles.filter { it != file }).take(12)
        recentPrefs.put(workspaceKey(), recentFiles.joinToString("\n") { relPath(it) })
        highlightPath = null
        status = "開いています: ${relPath(file)}（Markdown）"
    }

    /** 内蔵ヘルプの題目を開く（編集スペースの文書として表示。移動として履歴に積む）。 */
    fun openHelp(topic: String) {
        val cur = currentLocation()
        if (!(cur.space == UiSpace.EDIT && cur.help == topic)) pushBack(cur)
        showHelp(topic)
    }

    private fun showHelp(topic: String) {
        openPreview = null
        openDoc = null
        openMarkdown = null
        openImage = null
        openHelp = topic
        workspaceView = 0
        syncSpaceFromView()
        highlightPath = null
        status = "ヘルプ: ${Help.titleOf(topic)}"
    }

    private fun workspaceKey(): String = "ws-${workspace?.toString()?.hashCode() ?: 0}"

    /**
     * 未保存編集を優先するツリー取得（文書キャッシュ → セッション → ディスク）。
     * core への loader 注入と、UIの検証（整合検証の材料読み）に使う（UX-03）。
     */
    fun unsavedFirstLoader(): (Path) -> YNode? = { file ->
        val cached = docCache[file]
        if (cached != null) cached.root
        else session?.copyDocument(documentOf(file))?.documents?.firstOrNull()
            ?: runCatching { YamlIO.parseFirst(Files.readString(file)) }.getOrNull()
    }

    /** 未保存編集を優先するツリー取得の、文書ID 版（索引・検証・ビューの解決へ渡す loader）。 */
    fun unsavedFirstTree(): (DocumentId) -> YNode? = unsavedFirstLoader().let { load -> { d -> load(fileOf(d)) } }

    fun workspaceRecords(): List<WorkspaceRecord> =
        coreWs?.let { dev.toros.yde.app.core.WorkspaceRecords.collect(it, unsavedFirstLoader()) }.orEmpty()

    /**
     * ワークスペース全体の検証（UX-44）: 索引対象の全文書を「見たまま」（未保存編集込みの
     * オーバーレイ索引＋unsavedFirstLoader）で検証し、指摘のあるファイルだけ返す。
     * 検証タブの「全体」スコープが読む。ヘッドレス経路（CLI `validate`）はディスク基準のまま（architecture/index-references.md「索引の二層」）。
     */
    fun validateWorkspace(): List<Pair<Path, List<Issue>>> {
        val cw = coreWs ?: return emptyList()
        val loader = unsavedFirstLoader()
        val overlay = overlayIndex()
        return cw.files.mapNotNull { file ->
            val cached = docCache[file]
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

    /**
     * コマンドパレットの本文検索（R17-04）: 構造検索API（C-05）を未保存編集込みの
     * 「見たまま」で実行する（loader = unsavedFirstLoader、索引 = オーバーレイ）。
     * ヒットの座標ジャンプは openFile(file, parseYPath(hit.path)) で受ける。
     */
    fun searchContent(text: String, limit: Int = 10): List<SearchHit> {
        val cw = coreWs ?: return emptyList()
        if (text.isBlank()) return emptyList()
        return Search.run(
            cw,
            SearchQuery(text = text, limit = limit),
            index = overlayIndex(),
            loader = unsavedFirstLoader(),
        )
    }

    /**
     * 一覧ビューで選択したファイルのルート直下スカラーを一括変更する。
     * 対象キーは任意のルートスカラーキー（R8-05: L-07）。ID・予約キー・複合値（配列/オブジェクト）は
     * 壊さないよう対象外とする。書換ロジックは core.WorkspaceRecords（R9-02）。
     */
    fun bulkUpdate(files: Set<Path>, key: String, value: String): Result<Int> = runCatching {
        require(key.isNotBlank() && !key.startsWith("_") && key != "id") { "一括変更できない項目です: $key" }
        require(files.none { docCache[it]?.dirty == true }) { "未保存のファイルが含まれています" }
        val viaSession = sessionBulkUpdate(files, key, value)
        if (viaSession != null) {
            // セッション経路（D-11）: reload しない＝履歴を保持し、一括変更全体が Undo 1回で戻る
            refreshAfterSessionWrite(files.map { DocumentId(relPath(it)) })
            viaSession
        } else {
            // セッション不在時の従来経路（ファイル単位の直接書換→全再読込）
            val reopen = openDoc?.file?.takeIf { it in files }
            val count = dev.toros.yde.app.core.WorkspaceRecords.updateRootScalar(files, key, value)
            files.forEach(docCache::remove)
            reload()
            reopen?.let { file ->
                val refreshed = loadDocument(file)
                docCache[file] = refreshed
                openDoc = refreshed
            }
            count
        }
    }

    /**
     * 一括変更のセッション経路（A-2a・D-11）: 本体は core.WorkspaceRecords.bulkUpdateViaSession
     * （1トランザクション適用→保存＝部分適用なし）。セッション不在時は null（呼び出し側で従来経路へ）。
     */
    private fun sessionBulkUpdate(files: Set<Path>, key: String, value: String): Int? {
        val s = session ?: return null
        val ids = files.sortedBy { it.toString() }.map { DocumentId(relPath(it)) }
        return withHistoryLocation(s) { dev.toros.yde.app.core.WorkspaceRecords.bulkUpdateViaSession(s, ids, key, value) }
    }

    /**
     * 一括編集の対象にできるキーの候補（R8-05: L-07）。
     * ワークスペース文書のルート直下に現れるスカラーキーを集める（id・予約キーは除く）。
     */
    fun bulkKeyCandidates(): List<String> =
        coreWs?.let { dev.toros.yde.app.core.WorkspaceRecords.bulkKeyCandidates(it, unsavedFirstLoader()) }.orEmpty()

    private fun loadDocument(file: Path): OpenDocument {
        val text = runCatching { Files.readString(file) }.getOrDefault("")
        var error: String? = null
        var extra: List<YNode> = emptyList()
        val root = try {
            // 横断Undoは未表示の文書も変える。本文はworking、保存済みの比較用テキストはディスクから取る。
            val docs = session?.copyDocument(documentOf(file))?.documents ?: YamlIO.parseAll(text)
            extra = docs.drop(1)
            docs.firstOrNull() ?: YNode.Map()
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            null
        }
        return OpenDocument(file, root, schemaFor(file), text, error, extra)
            .also { doc ->
                // 編集通知: 全文書横断カウンタを進め、セッション履歴へ記録する（RA-05 → RA-06 で記録つきに）
                doc.onEdited = { coalesceWith ->
                    editVersion++
                    dismissLocalHistoryNotice()
                    recordEditToSession(doc, coalesceWith)
                }
                // dirty の判定はセッション（単一所有）を正とする（RA-06）
                doc.dirtySource = {
                    session?.let { s ->
                        val id = DocumentId(relPath(doc.file))
                        if (s.hasDocument(id)) s.isDirty(id) else null
                    }
                }
                doc.refreshDirty()
            }
    }

    /**
     * 編集1回をセッション履歴へ記録する（A-3・RA-06）。合体キーは文書スコープに閉じる（他文書の
     * 連続編集と混ざらない）。パース不能（root=null）の編集は記録しない（D-08:
     * 生テキストの不正状態は core の履歴に持たない。derivedState が null オーバーレイで判定から外す）。
     */
    private fun recordEditToSession(doc: OpenDocument, coalesceWith: Any?) {
        val s = session ?: return
        val root = doc.root ?: return
        val id = DocumentId(relPath(doc.file))
        if (!ensureAttached(doc, s, id, root)) return
        // ソースの解析完了が画面切替より遅れても、編集元はその文書のソースタブ。
        val location = if (coalesceWith == "source") documentHistoryLocation(doc.file, tab = 1)
            else if (uiSpace == UiSpace.LEDGER || uiSpace == UiSpace.DASHBOARD) historyLocation()
            else documentHistoryLocation(doc.file, if (openDoc === doc) editorTab else memory.editorTab[id] ?: 0)
        runCatching {
            val result = s.recordEdit(
                id, YDocument(id, listOf(root) + doc.extraDocs),
                coalesceKey = coalesceWith?.let { listOf(id.value, location, it) },
                nowMillis = editClock(),
            )
            if (result.ok) s.peekUndo()?.let { historyLocations.putIfAbsent(it.id, location) }
        }
    }

    private fun reloadDocumentFromDisk(doc: OpenDocument, force: Boolean, knownText: String? = null) {
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
            status = "読み込みに失敗しました: ${it.message}"
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

    fun acceptDiskVersion() {
        val doc = openDoc ?: return
        runCatching {
            val text = readStoreText(doc)
            session?.takeIf { it.acceptsDocument(documentOf(doc.file)) }?.acceptStoreText(documentOf(doc.file), text)
            if (text == null) {
                docCache.remove(doc.file)
                openDoc = null
            } else reloadDocumentFromDisk(doc, force = true, knownText = text)
            editVersion++
            indexVersion++
            status = "ディスク版を読み込みました: ${relPath(doc.file)}"
        }.onFailure { status = "読み込みに失敗しました: ${it.message}" }
    }
    fun keepLocalVersion() {
        val doc = openDoc ?: return
        runCatching {
            val text = readStoreText(doc)
            session?.acknowledgeStoreText(documentOf(doc.file), text)
            doc.expectedStoreText = text
            doc.externalConflict = false
            doc.diskModifiedMillis = runCatching { Files.getLastModifiedTime(doc.file).toMillis() }.getOrDefault(0L)
            status = "次回保存で編集内容を上書きします: ${relPath(doc.file)}（再度外部で変更された場合は停止します）"
        }.onFailure { status = "上書きの許可に失敗しました: ${it.message}" }
    }

    private fun readStoreText(doc: OpenDocument): String? =
        FileSystemDocumentStore(workspace ?: error("ワークスペース未選択")).read(documentOf(doc.file))?.text

    private fun checkSave(doc: OpenDocument) {
        val id = documentOf(doc.file)
        val s = session
        if (s != null && s.hasDocument(id)) s.checkSave(id)
        else FileSystemDocumentStore(workspace ?: error("ワークスペース未選択")).checkUnchanged(id, doc.expectedStoreText)
    }

    private fun prepareSave(doc: OpenDocument): Boolean = runCatching { checkSave(doc) }.fold(
        onSuccess = { true },
        onFailure = {
            if (it is StoreConflictException) doc.externalConflict = true
            status = "保存に失敗しました: ${it.message}"
            false
        },
    )

    fun saveCurrent() {
        if (uiSpace == UiSpace.DASHBOARD) { saveAll(); return }
        val doc = openDoc ?: return
        save(doc)
    }

    fun saveAll() {
        finishEditGroup()
        // 未表示のままUndoされた文書も、通常の保存経路（自動値・表示追随）を通す。
        val s = session
        val pendingDeletes = s?.let { it.dirtyDocuments().filterNot(it::hasDocument).toSet() }.orEmpty()
        val dirty = dirtyFiles().filterNot { documentOf(it) in pendingDeletes }.map(::ensureDoc)
        // 自動の値（更新日・版）は全部の文書で 1 つのトランザクション＝Undo 1 回で戻る。その後に 1 文書ずつ書き出す
        // （壊れた下書きは木が無いので対象外）
        val failures = mutableListOf<String>()
        val ready = dirty.filter { doc -> prepareSave(doc).also { if (!it) failures += status.orEmpty() } }
        val stamped = stampAutoValues(ready.filter { !it.draftBroken })
        ready.forEach { if (!save(it, stampedBefore = stamped)) failures += status.orEmpty() }
        if (s != null && pendingDeletes.isNotEmpty()) {
            val saved = s.save(pendingDeletes)
            refreshAfterSessionWrite(saved.deleted)
            if (!saved.ok) failures += saved.failures.map { (id, message) -> "${id.value}: $message" }
        }
        if (failures.isNotEmpty()) status = "保存できなかった文書があります: ${failures.joinToString(" / ")}"
    }

    /** 壊れたまま保存の確認待ち（スキーマ文書だけ。波及が大きいので Main.kt が確認を出す。R43-04）。 */
    var pendingTextSave by mutableStateOf<OpenDocument?>(null)

    fun confirmTextSave() {
        val doc = pendingTextSave ?: return
        pendingTextSave = null
        save(doc, confirmedTextSave = true)
    }

    /** `_schemas` の文書か（壊れたまま保存すると、そのスキーマを使う全文書に波及する）。 */
    fun isSchemaDocument(file: Path): Boolean = layout.zoneOf(relPath(file)) == WorkspaceLayout.Zone.SCHEMAS

    /**
     * 壊れた下書きをそのまま書く（R43-04「壊れたまま保存」）。文書はテキスト文書になり、ディスク上で解析できないファイルと同じ扱い
     * （索引・検証・ビュー・エージェントの対象外）。セッションの履歴は消さない。直して保存すれば文書集合へ戻る。
     */
    private fun saveDraftAsText(doc: OpenDocument): Boolean {
        val text = doc.source.text.toString()
        val id = DocumentId(relPath(doc.file))
        return runCatching {
            val s = session
            if (s != null && s.acceptsDocument(id)) {
                val r = if (s.hasDocument(id)) s.saveAsText(id, text) else s.saveAsText(id, text, doc.expectedStoreText)
                if (r.externalConflict) throw StoreConflictException(id)
                check(r.ok) { r.message ?: "書込に失敗しました" }
            } else {
                checkSave(doc)
                Files.writeString(doc.file, text)
            }
        }.onSuccess {
            doc.enterTextOnly(text)
            doc.expectedStoreText = text
            doc.diskModifiedMillis = runCatching { Files.getLastModifiedTime(doc.file).toMillis() }.getOrDefault(0L)
            doc.externalConflict = false
            workspace?.let { workspaceStamp = snapshotFiles(it) }
            coreWs?.let { cw ->
                cw.noteDiskChanges(listOf(doc.file))
                mirrorCore(cw)
                docCache.values.forEach { it.schema = schemaFor(it.file) }
            }
            editVersion++
            indexVersion++
            status = "壊れたまま保存しました: ${relPath(doc.file)}（直して保存するまで索引・検証の対象外です）"
            dismissLocalHistoryNotice()
        }.onFailure {
            if (it is StoreConflictException) doc.externalConflict = true
            status = "保存に失敗しました: ${it.message}"
        }.isSuccess
    }

    /**
     * 保存時に自動で入る値（architecture/schema.md「保存時に自動で入る値」）: UI の編集内容をセッションへ写してから、
     * 保存済みの内容との違いで更新日・版を入れる（セッションの 1 トランザクション）。入った文書は UI の木をセッションから写し直す。
     * 戻り値は文書ごとの入れた値の説明（空＝何も入らなかった）。
     */
    private fun stampAutoValues(docs: List<OpenDocument>): Map<DocumentId, String> {
        val s = session ?: return emptyMap()
        val ids = docs.mapNotNull { doc ->
            val root = doc.root ?: return@mapNotNull null
            val id = DocumentId(relPath(doc.file))
            if (!s.hasDocument(id)) return@mapNotNull null
            val replaced = s.replaceDocument(id, YDocument(id, listOf(root) + doc.extraDocs))
            if (!replaced.ok) return@mapNotNull null
            id
        }
        if (ids.isEmpty()) return emptyMap()
        val applied = withHistoryLocation(s) { s.applyAutoValues(ids, LocalDateTime.now()) }
        if (!applied.result.ok || applied.isEmpty) return emptyMap()
        refreshDocsFromSession(applied.plans.keys)
        return applied.plans.mapValues { (_, plan) -> plan.describe() }
    }

    /**
     * Undo（A-3・RA-06 / D-07）: 履歴はセッション単一所有＝**ワークスペース横断**の1本の履歴。
     * 直前の変更が他の文書のものなら、その文書が戻る（ツリーはセッションから追随更新される）。
     */
    fun undo() { prepareHistoryStep(undo = true)?.let(::executeHistoryStep) }

    fun redo() { prepareHistoryStep(undo = false)?.let(::executeHistoryStep) }

    /** 解析できない下書きを捨てて戻すかの確認待ち（Main.kt が確認を出す。R43-02）。 */
    var pendingHistory by mutableStateOf<PendingHistory?>(null)

    class PendingHistory internal constructor(
        val undo: Boolean,
        val docs: List<OpenDocument>,
        internal val session: WorkspaceSession,
        internal val recordId: Long,
        internal val version: Long,
    ) {
        private val drafts = docs.associateWith { Triple(it.source.text.toString(), it.sourceEpoch, it.sourceInputOrder) }
        internal fun matchesDrafts(): Boolean = drafts.all { (doc, stamp) ->
            stamp == Triple(doc.source.text.toString(), doc.sourceEpoch, doc.sourceInputOrder)
        }
    }

    private class HistoryStep(val session: WorkspaceSession, val record: ChangeRecord, val undo: Boolean, val visibleDocument: DocumentId?) {
        val version = session.version
    }

    /** キャッシュ順ではなく入力順に反映する。解析待ちの背景処理は採用時の世代検査で無効になる。 */
    private fun flushSourceDrafts() {
        docCache.values.mapNotNull { it.sourceParseRequest() }
            .sortedBy { it.inputOrder }
            .forEach { it.document.applyParsed(it, EditBuffer.parse(it.text)) }
    }

    /** 解析待ちを移動前の入力へ含めてから区切る。遅れて届く解析結果は採用検査で無効になる。 */
    private fun finishEditGroup() {
        flushSourceDrafts()
        session?.endEditGroup()
    }

    /**
     * Undo / Redo の 1 手。対象の文書に解析できない下書きがあれば「捨てて戻すか」を確認し（R43-02）、
     * 壊れたまま保存された文書が含まれていれば案内して止める（文書集合に無いので戻せない。R43-04）。
     */
    private fun prepareHistoryStep(undo: Boolean, confirmation: PendingHistory? = null): HistoryStep? {
        if (fileOperationBusy) return null
        val s = session ?: return null
        flushSourceDrafts()
        val record = if (undo) s.peekUndo() else s.peekRedo()
        if (confirmation != null && (confirmation.session !== s || confirmation.version != s.version ||
                confirmation.recordId != record?.id || !confirmation.matchesDrafts())) {
            status = "履歴または下書きが変わったため中止しました。もう一度操作してください"
            return null
        }
        record ?: return null
        val checked = s.checkHistory(undo)
        if (!checked.ok) {
            status = checked.message ?: "履歴を適用できません"
            return null
        }
        val targets = historyTargets(record).map(DocumentId::value).toSet()
        val docs = docCache.values.filter { relPath(it.file) in targets }
        val textOnly = docs.filter { it.textOnly }
        if (textOnly.isNotEmpty()) {
            status = textOnly.joinToString("・") { relPath(it.file) } +
                " は壊れたまま保存されているため、この操作は戻せません（ソースで直して保存すると戻せます）"
            return null
        }
        val broken = docs.filter { it.draftBroken }
        if (broken.isNotEmpty() && confirmation == null) {
            pendingHistory = PendingHistory(undo, broken, s, record.id, s.version)
            return null
        }
        pendingHistory = null
        broken.forEach { it.discardDraft() }
        return HistoryStep(s, record, undo, openDoc?.takeIf { uiSpace == UiSpace.EDIT }?.let { documentOf(it.file) })
    }

    private fun executeHistoryStep(step: HistoryStep) {
        completeHistoryStep(step, applyHistoryStep(step))
    }

    /** カーネルの操作だけを行う。ファイル配置の履歴では IO スレッドから呼び、UI の状態は変更しない。 */
    private fun applyHistoryStep(step: HistoryStep): dev.toros.yde.workspace.TransactionResult {
        val s = step.session
        val top = if (step.undo) s.peekUndo() else s.peekRedo()
        if (session !== s || step.version != s.version || step.record.id != top?.id) {
            return dev.toros.yde.workspace.TransactionResult(false,
                "履歴が変わったため中止しました。もう一度操作してください", s.version)
        }
        return runCatching { if (step.undo) s.undo() else s.redo() }.getOrElse {
            dev.toros.yde.workspace.TransactionResult(false, it.message ?: "履歴を適用できません", s.version)
        }
    }

    /** 文書の再生成・ソース同期・画面移動は、背景の IO が完了してから UI スレッドへ戻って行う。 */
    private fun completeHistoryStep(step: HistoryStep, result: dev.toros.yde.workspace.TransactionResult) {
        if (session !== step.session) {
            return
        }
        if (result.ok) {
            afterHistoryStep(step.record, step.undo, step.visibleDocument)
            // 次のキー入力や反映待ちの解析が来る前に、戻した木とソースを揃える。
            val synced = historyTargets(step.record).mapNotNull { id ->
                docCache[fileOf(id)]?.let { id to it.syncSourceFromTree(preferHistory = true) }
            }
            val conflicts = synced.sumOf { it.second.conflicts }
            val normalized = synced.filter { it.second.normalized }.map { it.first.value }
            val notices = mutableListOf<String>()
            if (conflicts > 0) notices += "下書きと食い違う変更が $conflicts か所あり、下書きを優先しました（「整形」で木に揃えられます）"
            if (normalized.isNotEmpty()) notices += "履歴の内容に揃えるためソースを書式調整しました: ${normalized.joinToString("・")}"
            if (notices.isNotEmpty()) status = notices.joinToString(" / ")
            val returned = returnToHistoryLocation(step.record)
            historyNotice = historyNotice?.copy(returnedToOrigin = returned)
        } else status = result.message ?: "履歴を適用できません"
    }

    fun confirmPendingHistory() {
        val pending = pendingHistory ?: return
        pendingHistory = null
        historyFromUi(pending.undo, pending)
    }

    /** 次に Ctrl+Z で戻る変更の予告（ツールバーのツールチップ。UX-86）。セッション不在時は null。 */
    fun undoPreview(): String? {
        editVersion
        pendingSourceDocuments().maxByOrNull { it.sourceInputOrder }?.let {
            return "ソース入力を反映して元に戻す: ${relPath(it.file)}"
        }
        return historySummary(session?.peekUndo())
    }

    fun redoPreview(): String? {
        editVersion
        if (pendingSourceDocuments().isNotEmpty()) return "ソース入力の反映待ちです（新しい編集でやり直しは破棄されます）"
        return historySummary(session?.peekRedo())
    }

    /** 履歴1件の1行要約: 変更の意図＋対象ファイル（意図に含まれていれば列挙を省く）。 */
    private fun historySummary(record: ChangeRecord?): String? {
        record ?: return null
        val names = historyTargets(record).map(DocumentId::value)
        val desc = record.intent.description.ifBlank { "${names.size} ファイルの変更" }
        val listed = names.take(3).joinToString("・") + (if (names.size > 3) " ほか${names.size - 3}件" else "")
        return if (names.all { it in desc }) desc else "$desc（$listed）"
    }

    private fun historyTargets(record: ChangeRecord): List<DocumentId> =
        (record.forward.operations + record.inverse.operations).flatMap(::operationIds).distinct()

    /** Undo可能か（ツールバー活性用。editVersion を読むことで再コンポーズを購読する）。 */
    fun canUndo(): Boolean {
        editVersion
        return session?.canUndo() == true || pendingSourceDocuments().isNotEmpty()
    }

    fun canRedo(): Boolean {
        editVersion
        return session?.canRedo() == true && pendingSourceDocuments().isEmpty()
    }

    private fun pendingSourceDocuments(): List<OpenDocument> = docCache.values.filter {
        it.hasPendingSourceInput && session?.acceptsDocument(documentOf(it.file)) == true
    }

    /**
     * Undo/Redo 適用後の追随: 影響文書のツリーをセッションの working から取り直し、派生を進める。
     * 文書の作成・削除を含む履歴は、**ファイルの有無までその場で確定する**（D-18）——内容の編集の Undo は
     * 「未保存●」に留めて保存で確定できるが、作成の取り消し（＝文書が消える）には保存の手段が無く、
     * 削除予約のままではディスクとセッションが食い違うため。
     */
    private fun afterHistoryStep(record: ChangeRecord, undo: Boolean, visibleDocument: DocumentId?) {
        session?.fileArrangement(record.id)?.let { plan ->
            followFileArrangement(plan, reverse = undo)
            noteHistoryStep(record, historyTargets(record), undo, visibleDocument)
            return
        }
        val ids = historyTargets(record)
        val fileLevel = (record.forward.operations + record.inverse.operations)
            .filter { it is WorkspaceOperation.CreateDocument || it is WorkspaceOperation.DeleteDocument }
            .flatMap(::operationIds)
            .toSet()
        val s = session
        // 閲覧専用のときの操作の取り消し・やり直しは、戻した内容を保存まで行う（未保存の編集を残さない。architecture/actions.md）
        val writeThrough = readOnlyMode && record.id in actionRecordIds
        if (s != null && (fileLevel.isNotEmpty() || writeThrough)) {
            val saved = s.save(if (writeThrough) ids.toSet() else fileLevel)
            if (!saved.ok) status = "ファイルの反映に失敗しました: ${saved.failures.entries.first().let { "${it.key.value}: ${it.value}" }}"
            refreshDocsFromSession(ids)
            refreshAfterSessionWrite(saved.written + saved.deleted)
            saved.conflicts.forEach { id -> workspace?.resolve(id.value)?.let { docCache[it]?.externalConflict = true } }
        } else {
            refreshDocsFromSession(ids)
            editVersion++
        }
        noteHistoryStep(record, ids, undo, visibleDocument)
    }

    /**
     * 「何が戻ったか」の通知を組み立てる（UX-86）。未保存化（●）の明示は、
     * 適用=保存済み・Undo=未保存という非対称（D-12）の説明（UX-93）を兼ねる。
     */
    private fun noteHistoryStep(record: ChangeRecord, ids: List<DocumentId>, undo: Boolean, visibleDocument: DocumentId?) {
        val ws = workspace ?: return
        val s = session ?: return
        val summary = historySummary(record) ?: return
        val verb = if (undo) "元に戻しました" else "やり直しました"
        val dirtied = ids.any { s.hasDocument(it) && s.isDirty(it) }
        val suffix = if (dirtied) "——未保存（●）の状態です。保存で確定します" else ""
        val files = ids.map { ws.resolve(it.value).normalize() }.distinct().filter { Files.exists(it) }
        historyNotice = HistoryNotice("$verb: $summary$suffix", files, summary, ids,
            ids.any { it != visibleDocument }, undo)
    }

    private fun operationIds(op: WorkspaceOperation): List<DocumentId> = when (op) {
        is WorkspaceOperation.EditDocument -> listOf(op.documentId)
        is WorkspaceOperation.CreateDocument -> listOf(op.documentId)
        is WorkspaceOperation.ReplaceDocument -> listOf(op.documentId)
        is WorkspaceOperation.DeleteDocument -> listOf(op.documentId)
        is WorkspaceOperation.MoveDocument -> listOf(op.from, op.to)
    }

    /**
     * セッションの working 状態から docCache のツリーを取り直す（RA-06。Undo/Redo の追随）。
     * セッションのツリーは共有せず deep copy で写す（ADR-0003。UI側の編集はセッションを汚さない）。
     */
    private fun refreshDocsFromSession(ids: Collection<DocumentId>) {
        val s = session ?: return
        val ws = workspace ?: return
        val documents = s.snapshot.documents
        ids.distinct().forEach { id ->
            val file = ws.resolve(id.value).normalize()
            val doc = docCache[file] ?: return@forEach
            val working = documents[id]
            if (working == null) {
                // undo で作成が巻き戻された等、文書が消えた場合はキャッシュも閉じる
                docCache.remove(file)
                if (openDoc?.file == file) openDoc = null
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
     * セッション書込（リファクタリング適用・一括更新）後の追随（B-1・RB-01）: reload しない＝履歴を保持したまま、
     * docCache のツリー・ディスクスタンプ・旧経路の索引を更新する。save() の複数ファイル版に相当する。
     */
    private fun refreshAfterSessionWrite(ids: Collection<DocumentId>) {
        val ws = workspace ?: return
        refreshDocsFromSession(ids)
        val files = ids.map { ws.resolve(it.value).normalize() }
        files.forEach { f ->
            docCache[f]?.let { d ->
                // 保存を伴う経路だけで比較基準を更新する。通常のUndoは保存済み内容を変えない。
                session?.storedText(documentOf(f))?.let { d.rawText = it }
                d.expectedStoreText = session?.storedText(documentOf(f))
                d.diskModifiedMillis = runCatching { Files.getLastModifiedTime(f).toMillis() }.getOrDefault(0L)
                d.externalConflict = false
            }
        }
        coreWs?.let { cw ->
            // スキーマ変更時の全再構築への振り分けは core に一元化されている（R9-02）
            cw.noteDiskChanges(
                changed = files.filter { Files.exists(it) },
                removed = files.filterNot { Files.exists(it) },
            )
            mirrorCore(cw)
            docCache.values.forEach { it.schema = schemaFor(it.file) }
        }
        // 文書の作成・削除（D-18）でディレクトリ構成が変わり得るため、ツリーの供給も更新する
        directories = emptyList()
        fileTreeRevision++
        workspaceStamp = snapshotFiles(ws)
        indexVersion++
        editVersion++
    }

    private fun dirtyFiles(): Set<Path> {
        editVersion
        indexVersion
        return session?.dirtyDocuments().orEmpty().mapTo(linkedSetOf(), ::fileOf).also { files ->
            docCache.values.filter { it.dirty || it.draftDirty }.forEach { files.add(it.file) }
        }
    }

    fun hasDirtyDocs(): Boolean = dirtyFiles().isNotEmpty()

    /** 未保存文書の数（台帳スペースの保存ボタンの件数表示用。UX-34。dirty の読み取りが再コンポーズを購読する）。 */
    fun dirtyDocCount(): Int = dirtyFiles().size

    /** 外部変更と競合中の文書（受信箱の表示用。R12-02。データの正は各 OpenDocument）。 */
    fun conflictedDocs(): List<OpenDocument> = docCache.values.filter { it.externalConflict }

    /**
     * 1 文書の保存。stampedBefore は「すべて保存」が先にまとめて入れた自動の値の説明（null なら、ここで 1 文書分を入れる）。
     * 自動の値が入るのは保存の直前で、履歴 1 件（Undo で日付と版が戻る）。入った項目は状態表示で知らせる。
     */
    private fun save(doc: OpenDocument, stampedBefore: Map<DocumentId, String>? = null, confirmedTextSave: Boolean = false): Boolean {
        doc.flushDraft()   // 入力待ちのソースを取り込んでから
        session?.endEditGroup()
        if (!prepareSave(doc)) return false
        if (doc.draftBroken) {
            // 壊れたまま保存（R43-04）。保存で止めず、文書をテキスト文書にして知らせる。スキーマ文書は波及が大きいので確認する
            if (doc.textOnly && !doc.draftDirty) return true
            if (!confirmedTextSave && isSchemaDocument(doc.file)) { pendingTextSave = doc; return true }
            return saveDraftAsText(doc)
        }
        val stamped = stampedBefore ?: stampAutoValues(listOf(doc))
        return runCatching {
            val s = session
            val root = doc.root
            val id = DocumentId(relPath(doc.file))
            // A-2a: 書込はセッション（DocumentStore ポート）へ集約する（ADR-0005）。
            // working を UI の編集内容で置き換えて保存（replaceDocument は履歴を積まない）。
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
        }.onSuccess { viaSession ->
            doc.markSaved()
            doc.rawText = doc.canonicalText()
            doc.expectedStoreText = doc.rawText
            doc.diskModifiedMillis = Files.getLastModifiedTime(doc.file).toMillis()
            doc.externalConflict = false
            workspace?.let { workspaceStamp = snapshotFiles(it) }
            // 保存したファイルだけ索引を差し替える。スキーマ保存時の全再構築への振り分けは core が行う（R8-02 / R9-02）
            coreWs?.let { cw ->
                cw.noteDiskChanges(listOf(doc.file))
                mirrorCore(cw)
                // セッション経由の保存では replaceDocument / save が既に反映済み（二重同期しない）
                if (!viaSession) syncSessionWithDisk(changed = listOf(doc.file))
                // スキーマ本体だけでなく派生スキーマの導出元文書（R11-03）の保存でもスキーマ集合が変わるため、常に割り当て直す
                docCache.values.forEach { it.schema = schemaFor(it.file) }
                indexVersion++
            }
            val auto = stamped[DocumentId(relPath(doc.file))]
            status = if (auto == null) "保存しました: ${relPath(doc.file)}"
            else "保存しました: ${relPath(doc.file)}（自動の値を設定 → $auto。Ctrl+Z で戻せます）"
            dismissLocalHistoryNotice()
        }.onFailure {
            if (it is StoreConflictException) doc.externalConflict = true
            status = "保存に失敗しました: ${it.message}"
        }.isSuccess
    }

    /**
     * スキーマ推測（R11-01）: glob に一致する文書（未保存編集を優先）からドラフトを推測し、
     * `_schemas/<id>.yaml` として保存して開く。仕上げはスキーマ設計画面（S-05）で行う。
     */
    fun inferSchema(glob: String, id: String): Result<Path> = runCatching {
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
        createDocumentFile(file, draft, "スキーマの推測: $id")
    }

    /** リファクタリング計画を生成するセッション（B-1・RB-01: 計画・適用はカーネルへ一本化）。 */
    private fun refactorSession(): WorkspaceSession =
        session ?: error("ワークスペースが開かれていません")

    /**
     * エンティティ改名のプレビュー（R21-02。architecture/graph-query.md「エンティティ操作」）: 全出現・全参照を書き換える計画を
     * 生成する（適用しない）。計画はセッションの working 状態＝「見たまま」が基準（B-1）。
     */
    fun planEntityRename(oldId: String, newId: String): Result<RefactoringPlan> = runCatching {
        refactorSession().planRefactoring(RefactoringRequest.RenameEntity(oldId, newId))
    }

    /** エンティティ統合のプレビュー（R21-02。入口はエンティティページの操作メニュー。R23-02）。 */
    fun planEntityMerge(fromId: String, intoId: String, deleteOccurrences: Boolean): Result<RefactoringPlan> =
        runCatching {
            refactorSession().planRefactoring(RefactoringRequest.MergeEntity(fromId, intoId, deleteOccurrences))
        }

    /** エンティティ削除のプレビュー（R21-02。入次数が残っていれば生成段階で拒否される）。 */
    fun planEntityDelete(id: String): Result<RefactoringPlan> = runCatching {
        refactorSession().planRefactoring(RefactoringRequest.DeleteEntity(id))
    }

    /**
     * スキーマのフィールド一括改名のプレビュー（R25-01。architecture/schema.md「リファクタリング」）: データ・スキーマ本体の計画も、ビュー定義の追随と
     * 言及の警告も、カーネルのセッションが1つの計画にして返す（P27 段D の D-c・意図的差分 D-36。以前は、アプリ層が補強していた）。
     */
    fun planSchemaFieldRename(schemaId: String, fieldPath: String, newName: String): Result<RefactoringPlan> =
        runCatching { refactorSession().planRefactoring(RefactoringRequest.RenameField(schemaId, fieldPath, newName)) }

    /** スキーマのフィールド一括削除のプレビュー（R25-03。ビュー・宣言の言及の警告も、カーネルの計画に入っている）。 */
    fun planSchemaFieldDelete(schemaId: String, fieldPath: String): Result<RefactoringPlan> = runCatching {
        refactorSession().planRefactoring(RefactoringRequest.DeleteField(schemaId, fieldPath))
    }

    /** 文書キー順のスキーマ順整列のプレビュー（R25-02。スキーマは変更しない正規化）。 */
    fun planSchemaAlignOrder(schemaId: String): Result<RefactoringPlan> = runCatching {
        refactorSession().planRefactoring(RefactoringRequest.AlignOrder(schemaId))
    }

    /**
     * エージェント変更セットの適用（A-2b）: 検証・競合判定は従来（Workspace.apply）と同一のまま、
     * 書込はセッション（DocumentStore ポート＝atomic 書込・1トランザクション）へ集約する（D-04）。
     * セッション不在時は従来の直接書込へフォールバック。実行スペースの承認（RunsSpace）が使う。
     */
    fun applyAgentChanges(changeSet: ChangeSet, requireValid: Boolean = false): ChangeSetResult {
        val cw = coreWs ?: error("ワークスペースが開かれていません")
        val s = session
        return if (s != null) cw.applyViaSession(s, changeSet, requireValid = requireValid)
        else cw.apply(changeSet, requireValid = requireValid)
    }

    /**
     * リファクタリング計画の適用（B-1・RB-01）: セッションの1トランザクションとして適用→保存する。
     * reload しない（履歴を保持する）ため、**リファクタリング全体が Undo 1回で戻る**（D-12）。
     * 対象に未保存編集があれば拒否する（適用→保存が未保存編集まで書いてしまうのを防ぐ。従来と同じ流儀）。
     */
    fun applyEntityPlan(plan: RefactoringPlan): Result<Unit> = runCatching {
        val s = refactorSession()
        val ids = (plan.changes.map { it.document } + plan.moves.flatMap { listOf(it.first, it.second) }).distinct()
        val ws = workspace ?: error("ワークスペースが開かれていません")
        val files = ids.map { ws.resolve(it.value).normalize() }
        require(files.none { docCache[it]?.dirty == true }) { "対象に未保存の変更があります。先に保存してください" }
        val applied = withHistoryLocation(s) { s.applyRefactoring(plan) }
        require(applied.ok) { applied.message ?: "適用に失敗しました" }
        val saved = s.save(ids.toSet())
        require(saved.ok) {
            saved.failures.entries.firstOrNull()?.let { "${it.key.value}: ${it.value}" } ?: "保存に失敗しました"
        }
        refreshAfterSessionWrite(ids)
        // 適用は1トランザクションで**保存まで**完了する（●が付かない理由の明示。UX-93 / D-12）
        status = "リファクタリングを適用して保存しました（${ids.size} ファイル。Ctrl+Z で1回で戻せます）"
    }

    fun createDirectory(parent: Path, name: String): Result<Path> = runCatching {
        require(name.isNotBlank() && '/' !in name && '\\' !in name && name.trim() !in setOf(".", "..")) { "使用できない名前です" }
        val target = parent.resolve(name.trim()).normalize()
        val ws = workspace ?: error("ワークスペース未選択")
        val s = session ?: error("セッション未選択")
        val plan = dev.toros.yde.workspace.FileArrangementPlanner.plan(s, ws, emptyList(), createDirectory = relPath(target))
        applyFileArrangement(plan).getOrThrow()
        target
    }

    fun createYaml(parent: Path, name: String): Result<Path> = runCatching {
        val fileName = name.trim().let { if (it.endsWith(".yaml") || it.endsWith(".yml")) it else "$it.yaml" }
        require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName) { "使用できない名前です" }
        val file = parent.resolve(fileName)
        require(!Files.exists(file)) { "同名のファイルが存在します" }
        val schema = schemaFor(file)
        // スキーマ初期値の生成は core.Views.defaultTree に一本化する（R9-02 で重複実装を解消）。自動の値は今の日付・版 1
        val initial = schema?.let { dev.toros.yde.app.core.Views.defaultTree(it.root, it, LocalDateTime.now()) } ?: YNode.Map()
        createDocumentFile(file, initial, "新規作成: ${relPath(file)}")
    }

    /**
     * ビューグリッドの行から詳細文書を新規作成する（P6）。
     * 作成先とスキーマ初期値は core.Views.detailScaffold が決め、作成後はその文書を開く。
     */
    fun createViewDetail(view: ViewDef, id: String): Result<Path> = runCatching {
        createScaffoldFile(dev.toros.yde.app.core.Views.detailScaffold(view, scaffoldSource(id), schemas))
    }

    /**
     * 新しい文書の元になる要素（P31）: ID の定義出現（未保存の編集を優先した「見たまま」の索引から）と、そのタイトル・木の読み手・グラフ。
     * filePattern の穴（`{name}` など）と scaffold のひな型は、この要素の値で埋まる。
     */
    private fun scaffoldSource(id: String): ScaffoldSource {
        val index = overlayIndex()
        return ScaffoldSource.of(id, index.occurrences(id), unsavedFirstTree(), index.graph)
    }

    /**
     * ビューの newRow（ファイルモード）: 入力されたIDで詳細文書そのものを新規作成して行を作る（R15-01）。
     * 行の集合はディスク基準の索引から解決されるため、書き込んだ時点でグリッドに行が現れる。
     */
    fun addViewRowFile(view: ViewDef, id: String): Result<Path> = runCatching {
        val pattern = view.newRow?.filePattern ?: error("このビューの newRow はファイルモードではありません")
        val trimmed = id.trim()
        // クエリ行（rows: match。R22-02）では行の成立がパターン照合に依存するため、ID形式の事前検査は行わない
        view.rows?.let { rowsDef ->
            require(idMatchesPattern(trimmed, rowsDef.idPattern)) {
                "ID「$trimmed」はこのビューの行になりません（idPattern: ${rowsDef.idPattern}）"
            }
        }
        index.entries[trimmed]?.firstOrNull()?.let {
            error("ID「$trimmed」は既に存在します: ${it.document.value}")
        }
        // 行がまだ無いので、元は ID だけ（newRow の filePattern に書ける穴は {id} だけ＝定義の段階で保証）
        createScaffoldFile(dev.toros.yde.app.core.Views.scaffold(pattern, ScaffoldSource.idOnly(trimmed), schemas))
    }

    /**
     * カバレッジ（detailIn）不足の指摘から詳細文書を新規作成する（R28-05a・UX-97 の導線）。
     * 作成先は詳細スキーマの filePattern（値の式。P31）、無ければ match から導く（単純な glob のみ。導けないスキーマはエラー）。
     * 穴は、その ID の定義出現（一覧側の要素）の値で埋まる。
     */
    fun createDetailForCoverage(schemaId: String, id: String): Result<Path> = runCatching {
        val schema = schemas.firstOrNull { it.id == schemaId } ?: error("スキーマが見つかりません: $schemaId")
        val pattern = DocumentScaffolds.filePatternOf(schema.kernel)
            ?: error("スキーマ「$schemaId」の match（${schema.match}）から作成先を決められません（スキーマに filePattern を書くと決められます）")
        createScaffoldFile(dev.toros.yde.app.core.Views.scaffold(pattern, scaffoldSource(id), schemas))
    }

    /** スキャフォールドを新規文書として作成し、その文書を開く（詳細作成・新規行ファイルモード共用）。 */
    private fun createScaffoldFile(scaffold: DocumentScaffold): Path {
        val ws = workspace ?: error("ワークスペースが開かれていません")
        val file = ws.resolve(scaffold.rel).normalize()
        require(!Files.exists(file)) { "既に同名のファイルが存在します: ${scaffold.rel}" }
        return createDocumentFile(file, scaffold.root, "文書の作成: ${scaffold.rel}")
    }

    /**
     * 文書の新規作成の単一経路（P29 Phase 4・RP-03／意図的差分 D-18）: セッションの1トランザクション
     * （CreateDocument）として適用→保存し、全体の再読込をしない＝**Ctrl+Z で作成を取り消せる**うえ、
     * それまでの Undo 履歴も保たれる（従来の直接書込＋reload はセッションを開き直して履歴を失っていた）。
     * セッションが文書として扱わないパス（隠しパス等）・セッション不在時だけ、従来の直接書込＋再読込へ
     * フォールバックする。作成後はその文書を開く。
     */
    private fun createDocumentFile(file: Path, root: YNode, intent: String): Path {
        val ws = workspace ?: error("ワークスペースが開かれていません")
        val target = file.normalize()
        require(target.startsWith(ws)) { "ワークスペース外には作成できません: $target" }
        require(!Files.exists(target)) { "既に同名のファイルが存在します: ${relPath(target)}" }
        val id = DocumentId(relPath(target))
        // 保存時に自動で入る値（auto）: 新しい文書は全部が新しい要素＝空の作成日・更新日・版を今の値で埋める（作成と同じ 1 件の履歴）
        schemaFor(target)?.let { SemanticAutoValues.stamp(it.tree, null, root, LocalDateTime.now()) }
        val s = session
        if (s != null && !s.hasDocument(id) && !WorkspaceReader.hiddenPath(id.value, s.layout)) {
            val applied = withHistoryLocation(s) { s.apply(
                WorkspaceTransaction(
                    baseVersion = s.version,
                    intent = ChangeIntent(intent),
                    operations = listOf(WorkspaceOperation.CreateDocument(id, YDocument(id, listOf(root)))),
                )
            ) }
            require(applied.ok) { applied.message ?: "文書を作成できませんでした" }
            val saved = s.save(setOf(id))
            require(saved.ok) { saved.failures[id] ?: "書込に失敗しました" }
            refreshAfterSessionWrite(listOf(id))
            status = "作成しました: ${id.value}（Ctrl+Z で取り消せます）"
        } else {
            Files.createDirectories(target.parent)
            Files.writeString(target, YamlIO.dump(root))
            reload()
        }
        openFile(target)
        return target
    }

    /**
     * ビューの newRow 定義に従い、一覧文書の配列へ新しい行（要素）を追加する（P6）。
     * 追加は未保存編集としてキャッシュ上の文書に積まれる（保存は通常の操作で行う）。
     */
    fun addViewRow(view: ViewDef): Result<YPath?> = runCatching {
        val def = view.newRow ?: error("このビューには newRow が定義されていません")
        val fileRel = def.file ?: error("このビューの newRow はファイルモードです（addViewRowFile を使う）")
        val ws = workspace ?: error("ワークスペースが開かれていません")
        val file = ws.resolve(fileRel).normalize()
        require(Files.isRegularFile(file)) { "一覧文書が存在しません: $fileRel" }
        val doc = ensureDoc(file)
        val root = doc.root ?: error("一覧文書をパースできません: ${doc.parseError}")
        // 追加先の配列を解決する（末端の配列だけは無ければ作成する）
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
        val schema = schemaFor(file)
        val arrayDef = schema?.let { dev.toros.yde.app.core.Views.fieldAt(it, root, def.path) }
        if (schema == null || arrayDef == null) {
            seq.items.add(YNode.Map())
            doc.markEdited()
            return@runCatching def.path + (seq.items.size - 1)
        }
        // 要素の作り方は配列エディタの追加ボタンと同じ（型の create に受け取る値があればカード＝戻り値 null）
        val index = addArrayElement(doc, def.path, arrayDef).getOrThrow() ?: return@runCatching null
        def.path + index
    }

    /** YAMLファイルとして移動の一級化（R21-03。カーネル MoveDocument）の対象か（台帳内・ディレクトリ以外）。 */
    private fun isMovableYaml(path: Path): Boolean {
        val name = path.fileName.toString()
        return !Files.isDirectory(path) && (name.endsWith(".yaml") || name.endsWith(".yml")) && isIndexed(path)
    }

    /**
     * 改名の影響プレビュー（R21-03。architecture/graph-query.md「エンティティ操作」）: パスID文書参照の書き換え・スキーマ match の
     * 変化・ビュー定義の対象外れを、実行前にダイアログへ表示するための1行リスト。
     */
    fun renameImpact(path: Path, newName: String): List<String> {
        val cw = coreWs ?: return emptyList()
        val s = session ?: return emptyList()
        val trimmed = newName.trim()
        if (trimmed.isBlank() || '/' in trimmed || '\\' in trimmed || !isMovableYaml(path)) return emptyList()
        val target = path.resolveSibling(trimmed)
        if (Files.exists(target)) return emptyList()
        return runCatching {
            // 移動計画はカーネル（summary 先頭は見出し・以降が補足。スキーマ適用の変化と、ビュー定義が対象外れになる警告は warnings に入る）
            val plan = s.planRefactoring(
                RefactoringRequest.MoveDocument(DocumentId(relPath(path)), DocumentId(relPath(target)))
            )
            plan.summary.drop(1) + plan.warnings.map { "注意: $it" }
        }.getOrDefault(emptyList())
    }

    fun planFileMoves(paths: List<Path>, destination: Path, definitions: Boolean = true, permissions: Boolean = false): dev.toros.yde.workspace.FileArrangementPlan {
        val ws = workspace ?: error("ワークスペース未選択")
        val s = session ?: error("セッション未選択")
        require(Files.isDirectory(destination) && destination.normalize().startsWith(ws)) { "移動先フォルダーがありません" }
        val moves = paths.map { dev.toros.yde.workspace.PathMove(relPath(it), relPath(destination.resolve(it.fileName))) }
        return dev.toros.yde.workspace.FileArrangementPlanner.plan(s, ws, moves, definitions, permissions)
    }

    fun planFileRename(path: Path, name: String, definitions: Boolean = true, permissions: Boolean = false): dev.toros.yde.workspace.FileArrangementPlan {
        require(name.isNotBlank() && name.trim() !in setOf(".", "..") && '/' !in name && '\\' !in name) { "使用できない名前です" }
        val ws = workspace ?: error("ワークスペース未選択")
        val s = session ?: error("セッション未選択")
        return dev.toros.yde.workspace.FileArrangementPlanner.plan(s, ws,
            listOf(dev.toros.yde.workspace.PathMove(relPath(path), relPath(path.resolveSibling(name.trim())))), definitions, permissions)
    }

    fun applyFileArrangement(plan: dev.toros.yde.workspace.FileArrangementPlan): Result<Unit> =
        applyFileArrangement(plan) { it.applyFileArrangement(plan) }

    /** UI の準備と反映は EDT、カーネルのファイル操作だけを IO へ送る。 */
    suspend fun applyFileArrangementFromUi(plan: dev.toros.yde.workspace.FileArrangementPlan): Result<Unit> =
        withContext(DesktopUiDispatcher) {
            applyFileArrangement(plan) { s -> withContext(Dispatchers.IO) { s.applyFileArrangement(plan) } }
        }

    private inline fun applyFileArrangement(
        plan: dev.toros.yde.workspace.FileArrangementPlan,
        apply: (WorkspaceSession) -> dev.toros.yde.workspace.TransactionResult,
    ): Result<Unit> = runCatching {
        val s = session ?: error("セッション未選択")
        require(plan.root == workspace) { "ワークスペースが変わりました" }
        val roots = plan.moves.flatMap { listOf(plan.root.resolve(it.from), plan.root.resolve(it.to)) } +
            plan.documentIds.map { plan.root.resolve(it.value) } + plan.texts.map { plan.root.resolve(it.document.value) }
        val docs = docCache.filterKeys { file -> roots.any { file == it || file.startsWith(it) } }.values
        docs.forEach { it.flushDraft() }
        require(docs.none { it.dirty || it.draftDirty }) { "対象または参照元に未保存の変更があります。先に保存してください" }
        val result = withHistoryLocation(s) { apply(s) }
        require(result.ok) { result.message ?: "ファイル操作に失敗しました" }
        require(session === s) { "ワークスペースが変わったため画面への反映を中止しました" }
        followFileArrangement(plan, reverse = false)
        status = plan.transaction.intent.description + "（Ctrl+Z で戻せます）"
    }

    private fun followFileArrangement(plan: dev.toros.yde.workspace.FileArrangementPlan, reverse: Boolean) {
        val ws = workspace ?: return
        val moves = if (reverse) plan.moves.map { dev.toros.yde.workspace.PathMove(it.to, it.from) } else plan.moves
        fun moved(path: Path): Path {
            val move = moves.firstOrNull { path == ws.resolve(it.from) || path.startsWith(ws.resolve(it.from)) } ?: return path
            return ws.resolve(move.to).resolve(ws.resolve(move.from).relativize(path))
        }
        val active = activeFile?.let(::moved)?.takeUnless { relPath(it).startsWith(".yde-trash/") }
        val wasReference = openPreview != null
        val referenceLine = previewLine
        val expansion = memory.treeExpanded.toMap()
        memory.treeSearchScope = memory.treeSearchScope?.let(::moved)?.takeUnless { relPath(it).startsWith(".yde-trash/") }
        memory.treeSelectedPaths = memory.treeSelectedPaths.map(::moved).filterNot { relPath(it).startsWith(".yde-trash/") }.toSet()
        memory.treeAnchor = memory.treeAnchor?.let(::moved)
        moves.forEach { memory.renameDocument(DocumentId(it.from), DocumentId(it.to)) }
        memory.treeExpanded.clear()
        expansion.forEach { (path, value) -> memory.treeExpanded[moved(path)] = value }
        memory.treeSelection = memory.treeSelection?.let(::moved)?.takeUnless { relPath(it).startsWith(".yde-trash/") }
        val prefs = dev.toros.yde.app.core.FileTreePreferences.forWorkspace(ws)
        prefs.clearExpansion()
        memory.treeExpanded.forEach { (path, value) -> prefs.expand(relPath(path), value) }
        recentFiles = recentFiles.map(::moved).filterNot { relPath(it).startsWith(".yde-trash/") }
        recentPrefs.put(workspaceKey(), recentFiles.joinToString("\n") { relPath(it) })
        backStack = backStack.map { it.copy(file = it.file?.let(::moved)) }
        forwardStack = forwardStack.map { it.copy(file = it.file?.let(::moved)) }
        docCache.keys.filter { moved(it) != it }.toList().forEach(docCache::remove)
        openDoc = null; openPreview = null; openMarkdown = null; openImage = null
        refreshAfterSessionWrite(plan.documentIds + plan.texts.filter { it.document.value.endsWith(".yaml") || it.document.value.endsWith(".yml") }.map { it.document })
        if (active != null && Files.isRegularFile(active)) {
            if (wasReference) showReference(active, referenceLine) else showFile(active, highlightPath)
        }
        fileTreeRevision++
    }

    fun renamePath(path: Path, newName: String): Result<Path> = runCatching {
        require(newName.isNotBlank() && '/' !in newName && '\\' !in newName && newName.trim() !in setOf(".", "..")) { "使用できない名前です" }
        val target = path.resolveSibling(newName.trim())
        val ws = workspace ?: error("ワークスペース未選択")
        val s = session ?: error("セッション未選択")
        val plan = dev.toros.yde.workspace.FileArrangementPlanner.plan(s, ws,
            listOf(dev.toros.yde.workspace.PathMove(relPath(path), relPath(target))))
        applyFileArrangement(plan).getOrThrow()
        target
    }

    fun deletePath(path: Path): Result<Unit> = deletePaths(listOf(path))

    fun deletePaths(paths: List<Path>): Result<Unit> = runCatching {
        val ws = workspace ?: error("ワークスペース未選択")
        val s = session ?: error("セッション未選択")
        val trash = ".yde-trash/" + java.util.UUID.randomUUID().toString()
        val moves = paths.mapIndexed { index, path -> dev.toros.yde.workspace.PathMove(relPath(path), "$trash/$index/" + path.fileName) }
        val plan = dev.toros.yde.workspace.FileArrangementPlanner.plan(s, ws, moves, deleting = true)
        applyFileArrangement(plan).getOrThrow()
        paths.forEach { path -> memory.forgetDocument(DocumentId(relPath(path))); memory.forgetPath(path) }
        memory.treeSelectedPaths = emptySet()
    }

    // ---------------------------------------------------------------- navigation

    /**
     * ナビゲーション履歴の1地点。スペース（R12-01）＋文書世界の位置で表す。
     * 編集スペースはファイルとハイライト位置、台帳スペースはビュー番号と選択中ID（横ぐし／ビューグリッド）、
     * 実行・設計スペースはスペース自体が位置（下の文書世界には触れずに復元する）。
     */
    data class NavLocation(
        val space: UiSpace,
        val view: Int,
        val file: Path?,
        val path: YPath?,
        val crossId: String?,
        val gridId: String?,
        val flowId: String? = null,
        val flowDocument: DocumentId? = null,
        val flowSelections: Map<Pair<String, DocumentId>, dev.toros.yde.semantic.FlowSelection> = emptyMap(),
        /** 内蔵ヘルプの題目（編集スペースにヘルプを出しているとき。file は null）。 */
        val help: String? = null,
        /** エディタのタブ（0=フォーム・1=ソース・2=差分・3=スキーマ文書のフォーム）。下位ビューと同じく場所の一部。 */
        val tab: Int = 0,
        val reference: Boolean = false,
        val referenceLine: Int = 1,
        val dashboardId: String? = null,
        val dashboardEntityId: String? = null,
    )

    private var backStack by mutableStateOf<List<NavLocation>>(emptyList())
    private var forwardStack by mutableStateOf<List<NavLocation>>(emptyList())

    /** 履歴の所有者はセッションのまま。記録 ID に編集元の画面だけを添える。 */
    private val historyLocations = mutableMapOf<Long, NavLocation>()

    private fun documentHistoryLocation(file: Path, tab: Int = 0) =
        NavLocation(UiSpace.EDIT, 0, file, null, null, null, tab = tab)

    private fun historyLocation(): NavLocation {
        val loc = currentLocation()
        return when (loc.space) {
            UiSpace.EDIT -> loc.copy(crossId = null, gridId = null, flowId = null, flowDocument = null, flowSelections = emptyMap(), dashboardId = null, dashboardEntityId = null)
            UiSpace.DASHBOARD -> NavLocation(UiSpace.DASHBOARD, 0, null, null, null, null,
                dashboardId = loc.dashboardId ?: historyViewCatalog()?.defaultDashboard()?.id,
                dashboardEntityId = loc.dashboardEntityId)
            UiSpace.LEDGER -> {
                val catalog = if ((loc.view == 4 && loc.gridId == null) || (loc.view == 5 && loc.flowId == null)) historyViewCatalog() else null
                val grid = if (loc.view == 4) loc.gridId ?: catalog?.views?.firstOrNull()?.id else null
                loc.copy(file = null, path = null, help = null, tab = 0, reference = false, referenceLine = 1,
                    crossId = loc.crossId.takeIf { loc.view == 3 }, gridId = grid,
                    flowId = if (loc.view == 5) loc.flowId ?: catalog?.flowViews?.firstOrNull()?.id else null,
                    flowDocument = loc.flowDocument.takeIf { loc.view == 5 },
                    flowSelections = if (loc.view == 5) loc.flowSelections.toMap() else emptyMap(),
                    dashboardId = null, dashboardEntityId = null)
            }
            else -> NavLocation(loc.space, 0, null, null, null, null)
        }
    }

    /** 一括変更や保存時の自動値で増えた記録にも同じ編集元を添える。保存失敗でも残った履歴には添える。 */
    private inline fun <T> withHistoryLocation(s: WorkspaceSession, block: () -> T): T {
        val before = s.peekUndo()?.id ?: -1L
        val location = historyLocation()
        try { return block() } finally {
            if (session === s) s.history.filter { it.id > before }.forEach { historyLocations.putIfAbsent(it.id, location) }
        }
    }

    private fun historyViewCatalog() = workspace?.let { ws -> runCatching { dev.toros.yde.app.core.Views.load(ws, schemas) }.getOrNull() }

    private fun canRestoreHistoryLocation(loc: NavLocation): Boolean = when {
        loc.space == UiSpace.EDIT -> loc.help == null && !loc.reference && loc.file != null &&
            (session?.hasDocument(documentOf(loc.file)) == true || Files.isRegularFile(loc.file))
        loc.space == UiSpace.LEDGER && loc.view == 4 -> historyViewCatalog()?.view(loc.gridId.orEmpty()) != null
        loc.space == UiSpace.LEDGER && loc.view == 3 -> loc.crossId != null && overlayIndex().occurrences(loc.crossId).isNotEmpty()
        loc.space == UiSpace.LEDGER && loc.view == 5 -> historyViewCatalog()?.flowViews?.any { it.id == loc.flowId } == true
        loc.space == UiSpace.DASHBOARD -> historyViewCatalog()?.dashboard(loc.dashboardId.orEmpty()) != null
        else -> true
    }

    /** 成功した履歴の編集元へ戻る。元のビューが消えていれば、開ける対象文書を使う。 */
    private fun returnToHistoryLocation(record: ChangeRecord): Boolean {
        val origin = historyLocations[record.id]?.takeIf(::canRestoreHistoryLocation)
        val target = origin ?: historyTargets(record).singleOrNull()?.let { documentHistoryLocation(fileOf(it)) }
            ?.takeIf(::canRestoreHistoryLocation) ?: return false
        val current = historyLocation()
        // 同じ画面では再表示しない。入力欄の焦点や選択を余計に動かさない。
        if (current.copy(path = null, flowSelections = emptyMap()) == target.copy(path = null, flowSelections = emptyMap())) return false
        val previousStatus = status
        pushBack(currentLocation())
        restore(target)
        status = previousStatus
        return true
    }

    val canGoBack: Boolean get() = backStack.isNotEmpty()
    val canGoForward: Boolean get() = forwardStack.isNotEmpty()

    /** 現在の表示位置。 */
    private fun currentLocation(): NavLocation =
        NavLocation(uiSpace, workspaceView, activeFile, highlightPath, crossViewId, viewGridId,
            flowViewId, flowDocument, flowSelections, help = openHelp, tab = editorTab,
            reference = openPreview != null, referenceLine = previewLine, dashboardId = dashboardId,
            dashboardEntityId = dashboardEntityId)

    /** 何も開いていない編集スペースは履歴に積む価値がない（台帳・実行・設計はそれ自体が場所）。 */
    private fun isEmptyLocation(loc: NavLocation): Boolean =
        loc.space == UiSpace.EDIT && loc.file == null && loc.help == null

    /**
     * 現在位置を「戻る」履歴へ積む。新しい移動が起きた時点で「進む」履歴は破棄する
     * （ブラウザと同じ振る舞い）。
     */
    private fun pushBack(loc: NavLocation) {
        finishEditGroup()
        if (isEmptyLocation(loc)) return
        backStack = (backStack + loc).takeLast(HISTORY_LIMIT)
        forwardStack = emptyList()
    }

    /** 履歴地点を画面へ復元する（履歴には積まない）。 */
    private fun restore(loc: NavLocation) {
        flowViewId = loc.flowId
        flowDocument = loc.flowDocument
        flowSelections = loc.flowSelections
        when (loc.space) {
            UiSpace.EDIT -> {
                loc.help?.let { showHelp(it) } ?: loc.file?.let { if (loc.reference) showReference(it, loc.referenceLine) else showFile(it, loc.path) }
                // エディタのタブも場所の一部（パースできない文書は showFile が選んだソースのまま）
                if (openDoc?.root != null) editorTab = loc.tab
            }
            UiSpace.LEDGER -> {
                loc.crossId?.let { crossViewId = it }
                loc.gridId?.let { viewGridId = it }
                workspaceView = loc.view
                syncSpaceFromView()
            }
            // 実行・設計はスペース自体が場所。下の文書世界（開いている文書・台帳ビュー）はそのまま重なる
            UiSpace.DASHBOARD -> {
                dashboardId = loc.dashboardId
                dashboardEntityId = loc.dashboardEntityId
                uiSpace = UiSpace.DASHBOARD
            }
            UiSpace.RUNS, UiSpace.DESIGN -> uiSpace = loc.space
        }
    }

    /** 編集スペースの地点で対象ファイルが削除済みなら復元できない。 */
    private fun isStale(loc: NavLocation): Boolean =
        loc.space == UiSpace.EDIT && loc.help == null && (loc.file == null || !Files.exists(loc.file))

    /** 参照ジャンプ・ファイル切替・ビュー間ジャンプの履歴を1つ戻る（削除済みファイルは読み飛ばす）。 */
    fun goBack() {
        while (true) {
            val target = backStack.lastOrNull() ?: return
            backStack = backStack.dropLast(1)
            if (isStale(target)) continue
            finishEditGroup()
            currentLocation().takeUnless(::isEmptyLocation)?.let {
                forwardStack = (forwardStack + it).takeLast(HISTORY_LIMIT)
            }
            restore(target)
            return
        }
    }

    /** 「戻る」で戻った移動を1つやり直す。 */
    fun goForward() {
        while (true) {
            val target = forwardStack.lastOrNull() ?: return
            forwardStack = forwardStack.dropLast(1)
            if (isStale(target)) continue
            finishEditGroup()
            currentLocation().takeUnless(::isEmptyLocation)?.let {
                backStack = (backStack + it).takeLast(HISTORY_LIMIT)
            }
            restore(target)
            return
        }
    }

    /** 開いている文書内の位置へジャンプする（検証パネル・文書内リンク）。移動として履歴に積む。 */
    fun navigateToPath(path: YPath) {
        val cur = currentLocation()
        if (cur.space != UiSpace.EDIT || cur.path != path) pushBack(cur)
        workspaceView = 0
        syncSpaceFromView()
        if (editorTab != 1) editorTab = 0   // ソースで見ている最中の指摘のジャンプは、ソースの行へ（SourcePane が highlightPath を行に写す）
        highlightPath = path
    }

    /**
     * ID の定義位置へジャンプする。
     * 索引（保存済みの状態）を優先し、見つからなければ現在のドキュメント内の未保存 ID を探す。
     */
    fun navigateToId(id: String) {
        if (uiSpace == UiSpace.DASHBOARD) { openDashboardEntity(id); return }
        // 未保存の出現へもジャンプできるようオーバーレイ索引で解決する（UX-03。座標は docCache ツリーに対して有効）
        val entries = overlayIndex().occurrences(id)
        if (entries.size > 1) {
            navigationCandidates = entries
            return
        }
        entries.firstOrNull()?.let { navigateToOccurrence(it); return }
        // 未保存ドキュメント内の ID を探す
        val doc = openDoc
        if (doc?.root != null) {
            val documentId = IdIndex.documentIdOf(doc.root!!, relPath(doc.file))
            val (ids, _) = DocScanner.scan(doc.root, documentOf(doc.file), doc.schema, documentId)
            val local = ids.firstOrNull { it.id == id }
            if (local != null) {
                navigateToPath(local.path)
                return
            }
        }
        status = "ID「$id」の定義が見つかりません（保存または再読込で索引が更新されます）"
    }

    fun navigateToOccurrence(entry: IdEntry) {
        navigationCandidates = emptyList()
        openFile(entry.document, entry.path)
        if (editorTab != 1) editorTab = 0   // ソースから飛んだらソースの行へ
    }

    fun dismissNavigationCandidates() { navigationCandidates = emptyList() }
}
