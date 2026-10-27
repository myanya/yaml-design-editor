package dev.toros.yde.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import dev.toros.yde.app.core.ChangeSetResult
import dev.toros.yde.app.ui.DesktopUiDispatcher
import dev.toros.yde.app.core.SearchHit
import dev.toros.yde.app.core.ViewDef
import dev.toros.yde.app.core.WorkspaceRecord
import dev.toros.yde.app.index.DocScanner
import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.app.index.IdEntry
import dev.toros.yde.app.validate.Issue
import dev.toros.yde.semantic.schema.EnforcementDef
import dev.toros.yde.semantic.schema.EntityTypeDef
import dev.toros.yde.semantic.schema.RelationDef
import dev.toros.yde.workspace.WorkspaceInit
import dev.toros.yde.workspace.changeset.ChangeSet
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.app.session.DocumentSession
import dev.toros.yde.semantic.RefactoringPlan
import dev.toros.yde.semantic.RefactoringRequest
import dev.toros.yde.app.agent.Staging
import dev.toros.yde.workspace.ActionEntry
import dev.toros.yde.workspace.ActionPlan
import dev.toros.yde.workspace.ActionRequest
import dev.toros.yde.workspace.ChangeRecord
import dev.toros.yde.workspace.WorkspaceDerivedState
import dev.toros.yde.workspace.WorkspaceSession
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
 * UIシェルのスペース（ROADMAP P12 / architecture/ui.md「UI シェル」）。
 * EDIT / LEDGER は文書世界（workspaceView 0 / 1〜6）と同期し、RUNS / DESIGN は文書状態を保ったまま重なる。
 * 同期はナビゲーション系メソッドとレール（showSpace / showWorkspaceView）だけが行い、直接書き換えない。
 */
enum class UiSpace { DASHBOARD, EDIT, LEDGER, RUNS, DESIGN }

/**
 * アプリケーション状態。
 */
class AppState(private val editClock: () -> Long = System::currentTimeMillis) {
    /** セッション側の状態（Compose 非依存。session.DocumentSession）。画面側はここから Compose の状態へ写し、画面の場所・通知・確認を担う。 */
    internal val documents = DocumentSession(editClock)
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
    /** 外部ソースの構造情報を YAML 文書として保存して開く（本体はセッション側）。 */
    fun saveSourceOutline(source: Path, outline: dev.toros.yde.app.core.SourceOutline.Outline, destination: String): Result<Path> =
        runCatching { documents.saveSourceOutline(source, outline, destination).also { openFile(it) } }
    var fileOperationBusy by mutableStateOf(false)
    var fileHistoryBusy by mutableStateOf(false)
    fun undoFromUi() { historyFromUi(true) }
    fun redoFromUi() { historyFromUi(false) }
    internal fun historyFromUi(undoing: Boolean, confirmation: DocumentSession.PendingHistory? = null): Job? {
        if (fileOperationBusy) return null
        val step = prepareHistoryStep(undoing, confirmation) ?: return null
        if (step.kernel.fileArrangement(step.record.id) == null) { executeHistoryStep(step); return null }
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

    private val workspaceState = mutableStateOf<Path?>(null)
    /** 開いているワークスペース（セッション側の写し。変えるのは openWorkspace）。 */
    val workspace: Path? get() = workspaceState.value

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

    private val readOnlyState = mutableStateOf(false)
    /**
     * 閲覧専用モード。フォーム・グリッドの編集 UI を表示専用に切り替える（FormCtx がこのフラグを既定値として拾う。永続化は Main の uiPreferences）。
     * セッション側（操作の記録を戻すときに保存まで行う判定）にも写す。
     */
    var readOnlyMode: Boolean
        get() = readOnlyState.value
        set(value) { documents.readOnlyMode = value; readOnlyState.value = value }

    /** ヘッドレスコア（セッション側の所有。ファイル走査・スキーマ・索引の実体と更新方針は core.Workspace が持つ）。 */
    private var coreWs: dev.toros.yde.app.core.Workspace?
        get() = documents.core
        set(value) { documents.core = value }

    /** カーネルのセッション（セッション側の所有。編集・履歴・dirty の正）。開けない場合も null のままアプリは旧経路で動き続ける。 */
    private var session: WorkspaceSession?
        get() = documents.kernel
        set(value) { documents.kernel = value }

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

    /** ワークスペースの配置ポリシー。コア未オープン時はレガシー相当。 */
    val layout: WorkspaceLayout get() = documents.layout

    /** 走査対象（台帳＋予約領域）に載っているファイルか。台帳外は索引前提の検証を降格する。 */
    fun isIndexed(file: Path): Boolean = documents.isIndexed(file)

    val index: IdIndex get() = documents.index
    private val indexVersionState = mutableStateOf(0)
    /** 索引の版（セッション側の写し。Compose の購読用）。 */
    var indexVersion: Int
        get() = indexVersionState.value
        private set(value) { documents.indexVersion = value }

    private val editVersionState = mutableStateOf(0)
    /**
     * 全文書横断の編集カウンタ（セッション側の写し）: docCache のどの文書かの未保存編集・Undo/Redo で進む。
     * 計算列など「他文書の未保存編集に追随したい」派生表示が再解決の購読に使う（architecture/transform.md「計算列と決定論的射影」）。
     */
    var editVersion: Int
        get() = editVersionState.value
        private set(value) { documents.editVersion = value }

    /**
     * 未保存編集を重ねたオーバーレイ索引（architecture/index-references.md「索引の二層」）。UI 層の検証・カバレッジ・ビュー行集合・
     * 参照解決はディスク索引（index）ではなく**こちら**を読む＝「見たままの状態で判定する」。
     * indexVersion / editVersion（Compose 状態）を読むため、コンポジション中の呼び出しは自動で購読される。
     * `remember` の計算内で使う場合はキーに editVersion を含めること。計算はセッション側（DocumentSession）。
     */
    fun overlayIndex(): IdIndex {
        indexVersion; editVersion
        return documents.overlayIndex()
    }

    /** まだ文書に入れていない内容（AI 生成の提案など）を重ねた索引。計算はセッション側。 */
    fun overlayIndexWith(file: Path, root: YNode): IdIndex {
        indexVersion; editVersion
        return documents.overlayIndexWith(file, root)
    }
    /** 開いたことのあるドキュメントのキャッシュ（未保存編集をファイル切替時にも保持する）。 */
    // 文書と、その中の State を同じ描画世代で公開する。通常の Map では古い描画にも新しい文書が漏れる。
    private val docCache = mutableStateMapOf<Path, OpenDocument>()

    /** 編集欄をセッションの履歴へ接続できる文書か。一時フォームは含めない。 */
    internal fun ownsDocument(doc: OpenDocument): Boolean = docCache[doc.file] === doc && documents.ownsDocument(doc.state)

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
    val filePolicy: dev.toros.yde.workspace.layout.FilePolicy get() = documents.filePolicy

    /** ファイル（YAML 以外も）があるか（file 型の値の存在の検査。相対はワークスペースのルートから）。 */
    fun fileExists(path: String): Boolean = documents.fileExists(path)

    /** 準備完了レポート（CLI `readiness` と同じ計算＝保存済みの内容で判定）。台帳の「準備」タブが読む。 */
    fun readinessReport(): dev.toros.yde.app.validate.Readiness.Report? = documents.readinessReport()

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

    /** エンティティの関連文書（AI 生成の「関連文書」候補。セッション側）。 */
    fun entityRelatedFiles(id: String, exclude: Path): List<Path> = documents.entityRelatedFiles(id, exclude)

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

    /** 型の要素の候補（パネルの「対象」欄。セッション側）。 */
    fun entityCandidates(type: String): List<Pair<String, String?>> = documents.entityCandidates(type)

    /**
     * 配列へ要素を足す（配列エディタの追加ボタン・ビューの新規行。architecture/actions.md「作成経路の寄せ」）: 要素の型の create に
     * 受け取る値があれば右ペインのカードで入力し（戻り値 null）、無ければその場で足す（セッション側。戻り値は番号）。
     * 足した要素は未保存の編集（Undo 可）。
     */
    fun addArrayElement(doc: OpenDocument, arrayPath: YPath, field: FieldDef): Result<Int?> = runCatching {
        val insert = documents.arrayInsert(doc.state, arrayPath, field)
        val create = insert.create
        if (create != null && !readOnlyMode) {
            val entry = ActionEntry("insert", "insert", "${insert.arrayDef.displayLabel} に行を追加", create.description, null, create.params, doc.schema?.id ?: "")
            // 橋渡し: この配列へ足すアクションがあれば、パネルで「操作に切り替える」を案内する
            newActionWork(entry, null, InsertTarget(doc, arrayPath, insert.arrayDef, insert.alternatives))
            return@runCatching null
        }
        documents.insertArrayElement(doc.state, arrayPath, insert.arrayDef, emptyMap())
    }

    /** 追加の作業（カード）の値で、配列へ要素を足す。 */
    fun applyInsert(): Result<Int> = runCatching {
        val work = actionWork ?: error("操作が選ばれていません")
        val into = work.insertInto ?: error("追加の作業ではありません")
        val index = documents.insertArrayElement(into.doc.state, into.arrayPath, into.arrayDef, actionParams(work))
        dismissAction()
        status = "${into.arrayDef.displayLabel} に要素を足しました（未保存。Ctrl+Z で戻せます）"
        index
    }

    /** 要素・型・文書・ワークスペースに使える操作（セッション側）。 */
    fun actionsForEntity(id: String): List<ActionEntry> = documents.actionsForEntity(id)

    fun actionsForEntityType(type: String): List<ActionEntry> = documents.actionsForEntityType(type)

    fun actionsForDocument(file: Path): List<ActionEntry> = documents.actionsForDocument(file)

    fun actionsForWorkspace(): List<ActionEntry> = documents.actionsForWorkspace()

    fun actionAvailability(entry: ActionEntry, target: String?): dev.toros.yde.workspace.ActionAvailability =
        documents.actionAvailability(entry, target)

    /**
     * 操作を始める: 対象と受け取る値を入れるパネルを出す。target は受け手（要素の ID か文書の相対パス。null ならパネルで選ぶ）。
     * 閲覧専用モードでも使える（閲覧専用が封じるのは編集＝未保存の木の変更。操作は確認してから書いて保存する）。
     */
    fun startAction(entry: ActionEntry, target: String?, now: LocalDateTime = LocalDateTime.now()) {
        newActionWork(entry, target, now = now)
    }

    private fun resetActionInput(work: ActionWork, target: String?, explicit: Map<String, Any?>): Result<Unit> = runCatching {
        val prepared = documents.prepareActionInput(work.entry, target, explicit, work.preparedAt)
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
        val prepared = documents.prepareActionInput(work.entry, work.target, work.explicit, work.preparedAt)
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
        if (coreWs == null) return
        val entry = documents.actionByName(name)
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

    /** 受け取る値のうち渡すもの（セッション側の決まり: input のない操作は空欄を省く・changed は差分と必須・明示指定）。 */
    private fun actionParams(work: ActionWork): Map<String, Any?> =
        documents.actionParams(work.entry, work.values, work.form.schema?.tree, work.explicit, work.baseline)

    /** 計画を作り、適用後の姿を検証する（原本は変えない。本体はセッション側）。計画できない理由は作業の error に出す。 */
    fun planAction() {
        val work = actionWork ?: return
        if (coreWs == null) return
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
            documents.planAction(ActionRequest(work.entry.name, work.target, actionParams(work)), planTime)
        }
            .onSuccess { plan ->
                work.plan = plan
                work.planSignature = actionSignature(work)
                work.planWorkspaceVersion = coreWs?.mirror?.version
                work.planEditVersion = session?.version
                work.plannedAt = planTime
                work.preview = documents.previewAction(plan)
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
     * 計画を適用して保存する（本体はセッション側。Undo 1 回で全部戻る）。対象に未保存の編集があれば拒み、確認後に入力やワークスペースが
     * 変わっていれば確認し直しを求める。
     */
    fun applyAction(allowErrors: Boolean = false): Result<String> = runCatching {
        val work = actionWork ?: error("操作が選ばれていません")
        val plan = work.plan ?: error("先に「確認」で計画を作ってください")
        require(!plan.isEmpty) { "変更はありません" }
        val cw = coreWs ?: error("ワークスペースが開かれていません")
        val s = session ?: error("セッションが開かれていません")
        documents.checkActionTargetsSaved(plan)
        checkActionSource(work)
        require(work.planSignature == actionSignature(work) && work.planWorkspaceVersion == cw.mirror.version &&
            work.planEditVersion == s.version) {
            work.invalidate()
            "入力またはワークスペースが変更されています。確認し直してください"
        }
        val count = documents.applyAction(ActionRequest(work.entry.name, work.target, actionParams(work)), plan, work.plannedAt!!, allowErrors)
        val message = "${work.title} を適用して保存しました（$count ファイル。Ctrl+Z で 1 回で戻せます）"
        work.applied = message
        status = message
        message
    }

    fun dismissAction() { actionWork = null; actionPanelOpen = false }

    // ---------------------------------------------------------------- workspace

    /**
     * ワークスペースを開く。画面の状態（記憶・ツリー・場所・最近）を戻してから、セッション側が場所・コア・カーネル・文書の集合を作り直して
     * ディスクから読む。既定のダッシュボードがあればそこへ着地する。
     */
    fun openWorkspace(dir: Path) {
        val ws = dir.toAbsolutePath().normalize()
        dismissHistoryNotice()
        pendingReload = null
        memory.clear()
        openPreview = null
        openMarkdown = null
        openImage = null
        val treePrefs = dev.toros.yde.app.core.FileTreePreferences.forWorkspace(ws)
        runCatching {
            treePrefs.expanded().forEach { (path, expanded) -> memory.treeExpanded[ws.resolve(path)] = expanded }
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
        recentWorkspaces = (listOf(ws) + recentWorkspaces.filter { it != ws }).take(10)
        appPrefs.put("recentWorkspaces", recentWorkspaces.joinToString("\n"))
        documents.openWorkspace(ws)
        recentFiles = recentPrefs.get(workspaceKey(), "").lineSequence().filter { it.isNotBlank() }
            .map { ws.resolve(it).normalize() }.filter(Files::exists).take(12).toList()
        historyViewCatalog()?.let { catalog ->
            catalog.defaultDashboard()?.let { dashboard ->
                dashboardId = dashboard.id
                uiSpace = UiSpace.DASHBOARD
            }
        }
    }

    /** ディスク版を候補として読み、編集・下書き・履歴を保持できる場合だけ取り込む（セッション側。表示の見直しは onReloaded）。 */
    fun reload() { documents.reload() }

    class PendingReload internal constructor(
        val files: List<String>, val historyCount: Int, internal val stamp: List<Any?>,
    )
    var pendingReload by mutableStateOf<PendingReload?>(null)
        private set

    private fun reloadConfirmationStamp(): List<Any?> = documents.reloadConfirmationStamp()

    fun requestDiscardAndReload() {
        if (workspace == null || fileOperationBusy) return
        val files = dirtyFiles() + documents.documents.values.filter { it.hasPendingSourceInput }.map { it.file }
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
            val candidate = documents.prepareReload(ws)
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
        dismissHistoryNotice()
        pendingHistory = null
        pendingTextSave = null
        openDoc = null
        openMarkdown = null
        openImage = null
        openPreview = null
        documents.adoptCandidate(prepared.first, prepared.second)
        if (!isStale(location)) restore(location)
        status = "未保存編集と Undo/Redo 履歴を破棄し、ディスクから開き直しました（ファイルは変更していません）"
    }

    fun rebuildIndex() = documents.rebuildIndex()

    // ---------------------------------------------------------------- セッション側への委譲（派生・外部変更・場所）

    /**
     * 派生状態: セッションの working 状態＝「見たまま」の構造・意味派生。
     * indexVersion / editVersion を読むため、コンポジション中の呼び出しは自動で購読される。計算はセッション側。
     */
    fun derivedState(): WorkspaceDerivedState? {
        indexVersion; editVersion
        return documents.derivedState()
    }

    /** 周期処理・ファイル監視から呼び、外部での作成・変更・削除を検出する（セッション側）。 */
    fun pollExternalChanges(eventHint: Boolean = false) = documents.pollExternalChanges(eventHint)

    fun relPath(file: Path): String = documents.relPath(file)

    /** ファイルの文書ID（ワークスペース相対パス）。索引・検証・ビューの解決は、場所をこの座標で表す。 */
    fun documentOf(file: Path): DocumentId = documents.documentOf(file)

    /** 文書ID のファイル。ファイルを開く・文書キャッシュを引くなど、Path が要る所でだけ使う。 */
    fun fileOf(document: DocumentId): Path = documents.fileOf(document)

    // 文書ID で呼べる入口（索引・検証・ビューの解決の結果は、場所を文書ID で持つ。ファイルへ直すのは、ここの1か所）
    fun openFile(document: DocumentId, focus: YPath? = null) = openFile(fileOf(document), focus)
    fun ensureDoc(document: DocumentId): OpenDocument = ensureDoc(fileOf(document))
    fun schemaFor(document: DocumentId): SchemaDef? = schemaFor(fileOf(document))
    fun isIndexed(document: DocumentId): Boolean = isIndexed(fileOf(document))

    fun schemaFor(file: Path): SchemaDef? = documents.schemaFor(file)

    /** AI生成の参考文書候補: 同じスキーマが適用される他のYAML文書（更新日時の新しい順）。 */
    fun sameSchemaFiles(exclude: Path, schemaId: String): List<Path> = documents.sameSchemaFiles(exclude, schemaId)

    // ---------------------------------------------------------------- documents

    fun docFor(file: Path): OpenDocument? = docCache[file]

    /**
     * 文書をエディタで開かずにキャッシュへ取り込む（ビューグリッドのセル編集用）。
     * 取り込んだ文書は通常の編集と同じく dirty 管理・保存・外部変更検出の対象になる。
     */
    fun ensureDoc(file: Path): OpenDocument =
        docCache[file] ?: OpenDocument(documents.ensureDocument(file)).also { docCache[file] = it }

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
        val doc = ensureDoc(file)
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
    fun unsavedFirstLoader(): (Path) -> YNode? = documents.unsavedFirstLoader()

    /** 未保存編集を優先するツリー取得の、文書ID 版（索引・検証・ビューの解決へ渡す loader）。 */
    fun unsavedFirstTree(): (DocumentId) -> YNode? = documents.unsavedFirstTree()

    fun workspaceRecords(): List<WorkspaceRecord> = documents.workspaceRecords()

    /** ワークスペース全体の検証（未保存編集込みの「見たまま」。セッション側）。検証タブの「全体」スコープが読む。 */
    fun validateWorkspace(): List<Pair<Path, List<Issue>>> = documents.validateWorkspace()

    /** コマンドパレットの本文検索（セッション側）。ヒットの座標ジャンプは openFile(file, parseYPath(hit.path)) で受ける。 */
    fun searchContent(text: String, limit: Int = 10): List<SearchHit> = documents.searchContent(text, limit)

    /** 一覧で選んだファイルの一括変更（セッション側）。セッション不在の経路で閉じられた表示中の文書は開き直す。 */
    fun bulkUpdate(files: Set<Path>, key: String, value: String): Result<Int> = runCatching {
        val reopen = openDoc?.file?.takeIf { it in files }
        val count = documents.bulkUpdate(files, key, value)
        if (reopen != null && openDoc == null) openDoc = ensureDoc(reopen)
        count
    }

    /** 一括編集の対象にできるキーの候補（セッション側）。 */
    fun bulkKeyCandidates(): List<String> = documents.bulkKeyCandidates()

    fun acceptDiskVersion() { openDoc?.let { documents.acceptDiskVersion(it.state) } }
    fun keepLocalVersion() { openDoc?.let { documents.keepLocalVersion(it.state) } }

    fun saveCurrent() {
        if (uiSpace == UiSpace.DASHBOARD) { saveAll(); return }
        val doc = openDoc ?: return
        save(doc)
    }

    fun saveAll() {
        val outcome = documents.saveAll()
        outcome.messages.lastOrNull()?.let { status = it; dismissLocalHistoryNotice() }
        outcome.textSaveConfirmations.lastOrNull()?.let { pendingTextSave = ensureDoc(it.file) }
        if (outcome.failures.isNotEmpty()) status = "保存できなかった文書があります: ${outcome.failures.joinToString(" / ")}"
    }

    /** 壊れたまま保存の確認待ち（スキーマ文書だけ。波及が大きいので Main.kt が確認を出す。R43-04）。 */
    var pendingTextSave by mutableStateOf<OpenDocument?>(null)

    fun confirmTextSave() {
        val doc = pendingTextSave ?: return
        pendingTextSave = null
        save(doc, confirmedTextSave = true)
    }

    /** `_schemas` の文書か（壊れたまま保存すると、そのスキーマを使う全文書に波及する）。 */
    fun isSchemaDocument(file: Path): Boolean = documents.isSchemaDocument(file)

    /**
     * Undo（A-3・RA-06 / D-07）: 履歴はセッション単一所有＝**ワークスペース横断**の1本の履歴。
     * 直前の変更が他の文書のものなら、その文書が戻る（ツリーはセッションから追随更新される）。
     */
    fun undo() { prepareHistoryStep(undo = true)?.let(::executeHistoryStep) }

    fun redo() { prepareHistoryStep(undo = false)?.let(::executeHistoryStep) }

    /** 解析できない下書きを捨てて戻すかの確認待ち（Main.kt が確認を出す。R43-02）。 */
    var pendingHistory by mutableStateOf<DocumentSession.PendingHistory?>(null)

    /** 解析待ちを移動前の入力へ含めてから区切る（セッション側）。 */
    private fun finishEditGroup() = documents.finishEditGroup()

    /**
     * Undo / Redo の 1 手の準備（判断はセッション側）。拒まれたら理由を状態表示に出し、確認が要れば pendingHistory に積む。
     */
    private fun prepareHistoryStep(undo: Boolean, confirmation: DocumentSession.PendingHistory? = null): DocumentSession.HistoryStep? {
        if (fileOperationBusy) return null
        val visible = openDoc?.takeIf { uiSpace == UiSpace.EDIT }?.let { documentOf(it.file) }
        return when (val prepared = documents.prepareHistoryStep(undo, confirmation, visible)) {
            is DocumentSession.HistoryPreparation.None -> null
            is DocumentSession.HistoryPreparation.Refused -> { status = prepared.message; null }
            is DocumentSession.HistoryPreparation.Confirm -> { pendingHistory = prepared.pending; null }
            is DocumentSession.HistoryPreparation.Ready -> { pendingHistory = null; prepared.step }
        }
    }

    private fun executeHistoryStep(step: DocumentSession.HistoryStep) {
        completeHistoryStep(step, applyHistoryStep(step))
    }

    /** カーネルの操作だけを行う（セッション側）。ファイル配置の履歴では IO スレッドから呼び、UI の状態は変更しない。 */
    private fun applyHistoryStep(step: DocumentSession.HistoryStep): dev.toros.yde.workspace.TransactionResult = documents.applyHistoryStep(step)

    /** 文書の再生成・ソース同期（セッション側）と、ツリーの追随・通知・画面移動（ここ）は、背景の IO が完了してから UI スレッドへ戻って行う。 */
    private fun completeHistoryStep(step: DocumentSession.HistoryStep, result: dev.toros.yde.workspace.TransactionResult) {
        if (session !== step.kernel) return
        if (result.ok) {
            val follow = documents.afterHistoryStep(step.record, step.undo)
            follow.arrangement?.let { followFileArrangement(it, reverse = step.undo) }
            follow.message?.let { status = it }
            noteHistoryStep(step.record, follow.ids, step.undo, step.visibleDocument)
            // 次のキー入力や反映待ちの解析が来る前に、戻した木とソースを揃える。
            val notices = documents.syncSourcesAfterHistory(step.record)
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

    /** 次に Ctrl+Z で戻る変更の予告（ツールバーのツールチップ。editVersion を読んで購読する）。 */
    fun undoPreview(): String? {
        editVersion
        return documents.undoPreview()
    }

    fun redoPreview(): String? {
        editVersion
        return documents.redoPreview()
    }

    /** Undo 可能か（ツールバー活性用。editVersion を読むことで再コンポーズを購読する）。 */
    fun canUndo(): Boolean {
        editVersion
        return documents.canUndo()
    }

    fun canRedo(): Boolean {
        editVersion
        return documents.canRedo()
    }

    /**
     * 「何が戻ったか」の通知を組み立てる（UX-86）。未保存化（●）の明示は、
     * 適用=保存済み・Undo=未保存という非対称（D-12）の説明（UX-93）を兼ねる。
     */
    private fun noteHistoryStep(record: ChangeRecord, ids: List<DocumentId>, undo: Boolean, visibleDocument: DocumentId?) {
        val ws = workspace ?: return
        val s = session ?: return
        val summary = documents.historySummary(record) ?: return
        val verb = if (undo) "元に戻しました" else "やり直しました"
        val dirtied = ids.any { s.hasDocument(it) && s.isDirty(it) }
        val suffix = if (dirtied) "——未保存（●）の状態です。保存で確定します" else ""
        val files = ids.map { ws.resolve(it.value).normalize() }.distinct().filter { Files.exists(it) }
        historyNotice = HistoryNotice("$verb: $summary$suffix", files, summary, ids,
            ids.any { it != visibleDocument }, undo)
    }

    private fun refreshAfterSessionWrite(ids: Collection<DocumentId>) = documents.refreshAfterSessionWrite(ids)

    private fun dirtyFiles(): Set<Path> {
        editVersion
        indexVersion
        return documents.dirtyFiles()
    }

    fun hasDirtyDocs(): Boolean = dirtyFiles().isNotEmpty()

    /** 未保存文書の数（台帳スペースの保存ボタンの件数表示用。UX-34。dirty の読み取りが再コンポーズを購読する）。 */
    fun dirtyDocCount(): Int = dirtyFiles().size

    /** 外部変更と競合中の文書（受信箱の表示用。R12-02。データの正は各 OpenDocument）。 */
    fun conflictedDocs(): List<OpenDocument> = docCache.values.filter { it.externalConflict }

    /** 1 文書の保存（セッション側）。壊れたまま保存の確認が要れば pendingTextSave に積む。 */
    private fun save(doc: OpenDocument, stampedBefore: Map<DocumentId, String>? = null, confirmedTextSave: Boolean = false): Boolean {
        val outcome = documents.save(doc.state, stampedBefore, confirmedTextSave)
        if (outcome.needsTextSaveConfirmation) pendingTextSave = doc
        outcome.message?.let { status = it }
        if (outcome.saved) dismissLocalHistoryNotice()
        return outcome.ok
    }

    /** スキーマ推測（セッション側）: `_schemas/<id>.yaml` を作って開く。仕上げはスキーマ設計画面（S-05）。 */
    fun inferSchema(glob: String, id: String): Result<Path> = runCatching { documents.inferSchema(glob, id).also { openFile(it) } }

    /**
     * エンティティ改名のプレビュー（R21-02。architecture/graph-query.md「エンティティ操作」）: 全出現・全参照を書き換える計画を
     * 生成する（適用しない）。計画はセッションの working 状態＝「見たまま」が基準（B-1）。
     */
    fun planEntityRename(oldId: String, newId: String): Result<RefactoringPlan> = runCatching {
        documents.planRefactoring(RefactoringRequest.RenameEntity(oldId, newId))
    }

    /** エンティティ統合のプレビュー（R21-02。入口はエンティティページの操作メニュー。R23-02）。 */
    fun planEntityMerge(fromId: String, intoId: String, deleteOccurrences: Boolean): Result<RefactoringPlan> =
        runCatching {
            documents.planRefactoring(RefactoringRequest.MergeEntity(fromId, intoId, deleteOccurrences))
        }

    /** エンティティ削除のプレビュー（R21-02。入次数が残っていれば生成段階で拒否される）。 */
    fun planEntityDelete(id: String): Result<RefactoringPlan> = runCatching {
        documents.planRefactoring(RefactoringRequest.DeleteEntity(id))
    }

    /**
     * スキーマのフィールド一括改名のプレビュー（R25-01。architecture/schema.md「リファクタリング」）: データ・スキーマ本体の計画も、ビュー定義の追随と
     * 言及の警告も、カーネルのセッションが1つの計画にして返す（P27 段D の D-c・意図的差分 D-36。以前は、アプリ層が補強していた）。
     */
    fun planSchemaFieldRename(schemaId: String, fieldPath: String, newName: String): Result<RefactoringPlan> =
        runCatching { documents.planRefactoring(RefactoringRequest.RenameField(schemaId, fieldPath, newName)) }

    /** スキーマのフィールド一括削除のプレビュー（R25-03。ビュー・宣言の言及の警告も、カーネルの計画に入っている）。 */
    fun planSchemaFieldDelete(schemaId: String, fieldPath: String): Result<RefactoringPlan> = runCatching {
        documents.planRefactoring(RefactoringRequest.DeleteField(schemaId, fieldPath))
    }

    /** 文書キー順のスキーマ順整列のプレビュー（R25-02。スキーマは変更しない正規化）。 */
    fun planSchemaAlignOrder(schemaId: String): Result<RefactoringPlan> = runCatching {
        documents.planRefactoring(RefactoringRequest.AlignOrder(schemaId))
    }

    /** エージェント変更セットの適用（セッション側。実行スペースの承認が使う）。 */
    fun applyAgentChanges(changeSet: ChangeSet, requireValid: Boolean = false): ChangeSetResult = documents.applyAgentChanges(changeSet, requireValid)

    /** リファクタリング計画の適用（セッション側。1 トランザクションで保存まで＝Undo 1 回で戻る）。 */
    fun applyEntityPlan(plan: RefactoringPlan): Result<Unit> = runCatching { documents.applyRefactoring(plan) }.map { }

    fun createDirectory(parent: Path, name: String): Result<Path> = runCatching {
        val (target, plan) = documents.planCreateDirectory(parent, name)
        applyFileArrangement(plan).getOrThrow()
        target
    }

    /** 新規 YAML を作って開く（セッション側）。 */
    fun createYaml(parent: Path, name: String): Result<Path> = runCatching { documents.createYaml(parent, name).also { openFile(it) } }

    /** ビューグリッドの行から詳細文書を作って開く（P6。セッション側）。 */
    fun createViewDetail(view: ViewDef, id: String): Result<Path> = runCatching { documents.createViewDetail(view, id).also { openFile(it) } }

    /** ビューの newRow（ファイルモード）: 詳細文書を作って開く（R15-01。セッション側）。 */
    fun addViewRowFile(view: ViewDef, id: String): Result<Path> = runCatching { documents.addViewRowFile(view, id).also { openFile(it) } }

    /** カバレッジ不足の指摘から詳細文書を作って開く（R28-05a。セッション側）。 */
    fun createDetailForCoverage(schemaId: String, id: String): Result<Path> =
        runCatching { documents.createDetailForCoverage(schemaId, id).also { openFile(it) } }

    /**
     * ビューの newRow 定義に従い、一覧文書の配列へ新しい行（要素）を追加する（P6）。追加先はセッション側が解決し、要素の作り方は
     * 配列エディタの追加ボタンと同じ（型の create に受け取る値があればカード＝戻り値 null）。追加は未保存編集として積まれる。
     */
    fun addViewRow(view: ViewDef): Result<YPath?> = runCatching {
        val target = documents.viewRowTarget(view)
        val arrayDef = target.arrayDef ?: return@runCatching documents.appendEmptyRow(target)
        val index = addArrayElement(ensureDoc(target.doc.file), target.path, arrayDef).getOrThrow() ?: return@runCatching null
        target.path + index
    }

    /** 改名の影響プレビュー（セッション側）。 */
    fun renameImpact(path: Path, newName: String): List<String> = documents.renameImpact(path, newName)

    fun planFileMoves(paths: List<Path>, destination: Path, definitions: Boolean = true, permissions: Boolean = false): dev.toros.yde.workspace.FileArrangementPlan =
        documents.planFileMoves(paths, destination, definitions, permissions)

    fun planFileRename(path: Path, name: String, definitions: Boolean = true, permissions: Boolean = false): dev.toros.yde.workspace.FileArrangementPlan =
        documents.planFileRename(path, name, definitions, permissions)

    fun applyFileArrangement(plan: dev.toros.yde.workspace.FileArrangementPlan): Result<Unit> =
        applyFileArrangement(plan) { it.applyFileArrangement(plan) }

    /** UI の準備と反映は EDT、カーネルのファイル操作だけを IO へ送る。 */
    suspend fun applyFileArrangementFromUi(plan: dev.toros.yde.workspace.FileArrangementPlan): Result<Unit> =
        withContext(DesktopUiDispatcher) {
            applyFileArrangement(plan) { s -> withContext(Dispatchers.IO) { s.applyFileArrangement(plan) } }
        }

    /** 準備（下書きの反映・未保存の拒否）と文書側の追随はセッション側。ここは履歴の編集元・画面の追随・通知。 */
    private inline fun applyFileArrangement(
        plan: dev.toros.yde.workspace.FileArrangementPlan,
        apply: (WorkspaceSession) -> dev.toros.yde.workspace.TransactionResult,
    ): Result<Unit> = runCatching {
        val s = documents.prepareFileArrangement(plan)
        val result = documents.withHistoryOrigin(s) { apply(s) }
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
        documents.afterFileArrangement(plan, reverse)
        openDoc = null; openPreview = null; openMarkdown = null; openImage = null
        if (active != null && Files.isRegularFile(active)) {
            if (wasReference) showReference(active, referenceLine) else showFile(active, highlightPath)
        }
        fileTreeRevision++
    }

    fun renamePath(path: Path, newName: String): Result<Path> = runCatching {
        applyFileArrangement(documents.planFileRename(path, newName)).getOrThrow()
        path.resolveSibling(newName.trim())
    }

    fun deletePath(path: Path): Result<Unit> = deletePaths(listOf(path))

    fun deletePaths(paths: List<Path>): Result<Unit> = runCatching {
        applyFileArrangement(documents.planDelete(paths)).getOrThrow()
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
        val target = origin ?: documents.historyTargets(record).singleOrNull()?.let { documentHistoryLocation(fileOf(it)) }
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

    // ---------------------------------------------------------------- セッション側からの写しと聞き手

    /** セッション側の値を Compose の状態へ写す（DocumentSession.onChanged）。 */
    private fun mirrorSession() {
        workspaceState.value = documents.workspace
        schemas = documents.schemas
        schemaErrors = documents.schemaErrors
        relations = documents.relations
        enforcement = documents.enforcement
        entityTypes = documents.entityTypes
        schemaGraph = documents.schemaGraph
        yamlFiles = documents.yamlFiles
        markdownFiles = documents.markdownFiles
        editorOnlyFiles = documents.editorOnlyFiles
        indexVersionState.value = documents.indexVersion
        editVersionState.value = documents.editVersion
    }

    init {
        documents.onChanged = ::mirrorSession
        documents.onNotice = { status = it }
        documents.onEdited = { dismissLocalHistoryNotice() }
        documents.editOrigin = { doc, coalesceWith ->
            // ソースの解析完了が画面切替より遅れても、編集元はその文書のソースタブ。
            if (coalesceWith == "source") documentHistoryLocation(doc.file, tab = 1)
            else if (uiSpace == UiSpace.LEDGER || uiSpace == UiSpace.DASHBOARD) historyLocation()
            else documentHistoryLocation(doc.file, if (openDoc?.state === doc) editorTab else memory.editorTab[documentOf(doc.file)] ?: 0)
        }
        documents.onEditRecorded = { id, origin -> (origin as? NavLocation)?.let { historyLocations.putIfAbsent(id, it) } }
        documents.onKernelReplaced = { historyLocations.clear() }
        documents.onFilesChanged = { directories = emptyList(); fileTreeRevision++ }
        documents.onDocumentClosed = { file -> docCache.remove(file); if (openDoc?.file == file) openDoc = null }
        documents.reloadAllowed = { !fileOperationBusy }
        documents.historyOrigin = { historyLocation() }
        documents.onReloaded = { outcome ->
            if (outcome.ok) {
                openDoc?.let { if (it.file !in outcome.existing && documentOf(it.file) !in outcome.protected) openDoc = null }
                openMarkdown?.let { if (!Files.exists(it)) openMarkdown = null }
                openImage?.let { if (!Files.exists(it)) openImage = null }
            }
            outcome.message?.let { status = it }
        }
    }
}
