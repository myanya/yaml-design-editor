package dev.toros.yde.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.toros.yde.app.core.ChangeSetResult
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
import dev.toros.yde.workspace.changeset.ChangeSet
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YDocument
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.yaml.model.deepCopy
import dev.toros.yde.app.schema.SchemaLoader
import dev.toros.yde.semantic.RefactoringPlan
import dev.toros.yde.semantic.RefactoringRequest
import dev.toros.yde.workspace.ChangeIntent
import dev.toros.yde.workspace.ChangeRecord
import dev.toros.yde.workspace.FileSystemDocumentStore
import dev.toros.yde.workspace.WorkspaceDerivedState
import dev.toros.yde.workspace.WorkspaceOperation
import dev.toros.yde.workspace.WorkspaceReader
import dev.toros.yde.workspace.WorkspaceSession
import dev.toros.yde.workspace.WorkspaceTransaction
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences

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
     * 編集をツリーへ記録する。coalesceWith（UX-11。ARCHITECTURE §4.34）: 連続入力になり得る編集面が
     * 渡す合体キー（同一スカラーノード・`Pair(map, key)`・ソースの固定キー等）。同じキーの連続編集は
     * 履歴1記録に合体する。構造操作は渡さない＝常に境界。
     * 履歴の記録先はセッション（onEdited 経由。RA-06）。
     */
    fun markEdited(coalesceWith: Any? = null) {
        version++
        onEdited?.invoke(coalesceWith)
        sync()
    }

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
}

/**
 * UIシェルのスペース（ROADMAP P12 / ARCHITECTURE §4.24）。
 * EDIT / LEDGER は文書世界（workspaceView 0 / 1〜4）と同期し、RUNS / DESIGN は文書状態を保ったまま重なる。
 * 同期はナビゲーション系メソッドとレール（showSpace / showWorkspaceView）だけが行い、直接書き換えない。
 */
enum class UiSpace { EDIT, LEDGER, RUNS, DESIGN }

/**
 * アプリケーション状態。
 */
class AppState {
    private companion object {
        /** ナビゲーション履歴（戻る／進む）の保持上限。 */
        const val HISTORY_LIMIT = 50
    }

    private val recentPrefs = Preferences.userRoot().node("yaml-design-editor/recent")
    private val appPrefs = Preferences.userRoot().node("yaml-design-editor/app")

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
    /** エンティティ型レジストリ（R23-01。§4.33）: 型の表示名・アスペクト順序。エンティティページ・レンズが使う。 */
    var entityTypes by mutableStateOf<Map<String, EntityTypeDef>>(emptyMap())
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
     * 計算列など「他文書の未保存編集に追随したい」派生表示が再解決の購読に使う（ARCHITECTURE §4.26）。
     */
    var editVersion by mutableStateOf(0)
        private set

    /** overlayIndex() のキャッシュ（(indexVersion, editVersion) が変わるまで再計算しない）。 */
    private var overlayCache: Pair<Pair<Int, Int>, IdIndex>? = null

    /**
     * 未保存編集を重ねたオーバーレイ索引（UX-03）。dirty な文書キャッシュの編集中ツリーを
     * 重ねた「見たまま」の索引を返す（ARCHITECTURE §4.5）。UI層の検証・カバレッジ・ビュー行集合・
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
    private val docCache = LinkedHashMap<Path, OpenDocument>()

    var openDoc by mutableStateOf<OpenDocument?>(null)
        private set
    var recentFiles by mutableStateOf<List<Path>>(emptyList())
        private set

    /** フォーム上でハイライト＆スクロール対象にするパス。 */
    var highlightPath by mutableStateOf<YPath?>(null)

    /** 同一論理 ID に複数の出現箇所がある場合のジャンプ先候補。 */
    var navigationCandidates by mutableStateOf<List<IdEntry>>(emptyList())
        private set

    /** 0 = フォーム（スキーマ文書ではスキーマ設計）, 1 = ソース, 2 = 差分, 3 = スキーマ文書のメタスキーマフォーム（R11-02） */
    var editorTab by mutableStateOf(0)
    /** 0 = 文書, 1 = 一覧, 2 = 参照グラフ, 3 = ID横ぐし, 4 = ビューグリッド */
    var workspaceView by mutableStateOf(0)

    /** 表示中のスペース（R12-01）。書き換えは showSpace / showWorkspaceView / ナビゲーション系メソッド経由に限る。 */
    var uiSpace by mutableStateOf(UiSpace.EDIT)
        private set

    /** 台帳スペースで最後に見ていた下位ビュー（1〜4）。レールで台帳へ戻ったときに復元する。 */
    private var lastLedgerView = 1

    /** workspaceView の変更後に呼び、スペースを文書世界（編集／台帳）へ同期する。 */
    private fun syncSpaceFromView() {
        uiSpace = if (workspaceView == 0) UiSpace.EDIT else UiSpace.LEDGER
        if (workspaceView in 1..4) lastLedgerView = workspaceView
    }

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
            UiSpace.RUNS, UiSpace.DESIGN -> uiSpace = space
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
        // 横ぐしビューへの到着（他ビュー・他スペースから）と、ビュー内でのID切替の両方を移動として履歴に積む
        val arriving = workspaceView != 3 || uiSpace != UiSpace.LEDGER
        val idChanges = id != null && crossViewId != id
        if (arriving || idChanges) pushBack(currentLocation())
        if (id != null) crossViewId = id
        workspaceView = 3
        syncSpaceFromView()
    }

    /**
     * グラフレンズ（R23-03。ARCHITECTURE §4.33）の起点ID。null = 起点なし（型・relation で絞る全体表示）。
     * レンズ画面側で解除・変更できる（NavLocation には含めない＝復元はビュー位置まで）。
     */
    var lensOriginId by mutableStateOf<String?>(null)

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
     * 「何が戻ったか」とジャンプ導線をステータスバーに常設で出す。次の編集・保存・✕で消える。
     * files はジャンプ可能な影響文書（ディスクに存在するもののみ）。
     */
    data class HistoryNotice(val message: String, val files: List<Path>)

    var historyNotice by mutableStateOf<HistoryNotice?>(null)
        private set

    fun dismissHistoryNotice() { historyNotice = null }

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

    // ---------------------------------------------------------------- workspace

    fun openWorkspace(dir: Path) {
        workspace = dir.toAbsolutePath().normalize()
        coreWs = null
        session = null
        docCache.clear()
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
    }

    /** ファイル一覧・スキーマ・索引をディスクから再読込する（未保存編集は保持）。 */
    fun reload() {
        val ws = workspace ?: return
        val cw = coreWs?.also { it.refresh() } ?: dev.toros.yde.app.core.Workspace.open(ws).also { coreWs = it }
        mirrorCore(cw)
        // A-1: 新経路セッションもディスク再読込と同じタイミングで開き直す（レイアウト変化も追随する）。
        // 開き直し後は編集中文書を再反映する（RA-05）。ディスクを映すセッションを読み直した直後なので、
        // その読み込み済みの状態から起こす（同じワークスペースを2回読まない。P27 段D の D-d・D-e）
        reopenSession(ws, fromMirror = true)
        directories = scanDirectories(ws)
        val existing = yamlFiles.toSet() + editorOnlyFiles
        docCache.keys.filter { it !in existing }.toList().forEach { removed ->
            if (docCache[removed]?.dirty != true) docCache.remove(removed)
        }
        docCache.values.forEach { doc ->
            doc.schema = schemaFor(doc.file)
            reloadDocumentFromDisk(doc, force = false)
        }
        openDoc?.let { if (it.file !in existing && !it.dirty) openDoc = null }
        indexVersion++
        workspaceStamp = snapshotFiles(ws)
        status = "ワークスペース: $ws ／ YAML ${yamlFiles.size} 件・スキーマ ${schemas.size} 件・ID ${index.entries.size} 件"
    }

    /** core.Workspace の状態を Compose 購読用ミラーへ反映する。 */
    private fun mirrorCore(cw: dev.toros.yde.app.core.Workspace) {
        schemas = cw.schemas
        schemaErrors = cw.schemaErrors
        relations = cw.relations
        enforcement = cw.enforcement
        entityTypes = cw.entityTypes
        yamlFiles = cw.files
        // 台帳外のエディタ層ファイル（R13-04）。ledger 宣言が無ければ走査対象と一致するため空になる
        editorOnlyFiles = if (cw.layout.ledger.isEmpty()) emptyList()
        else dev.toros.yde.app.core.WorkspaceScan.editorYamlFiles(cw.root).filter { it !in cw.files.toSet() }
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
     * エージェント・自動実行・CLI の基準（docs/ARCHITECTURE.md §4.5 の二層）——で、読み込みだけを1回にする。
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
        if (!s.hasDocument(id)) return  // 構成ファイル等、セッションが文書として扱わないパス
        runCatching { s.replaceDocument(id, YDocument(id, listOf(root) + doc.extraDocs)) }
    }

    /**
     * ディスク変更をセッションへ同期する（保存・外部変更検出から呼ぶ）。構成変更でレイアウトが
     * 変わったときは開き直し、それ以外は増分取り込み（noteStoreChanges。スキーマ変更の
     * 全再読込への降格はセッション側の責務＝R9-02 の振り分けと同型）。
     */
    private fun syncSessionWithDisk(changed: List<Path>, removed: List<Path> = emptyList()) {
        val ws = workspace ?: return
        val s = session ?: run { reopenSession(ws); return }
        if (coreWs?.layout != s.layout) {
            reopenSession(ws)
            return
        }
        runCatching {
            s.noteStoreChanges(
                changed = changed.map { DocumentId(relPath(it)) },
                removed = removed.map { DocumentId(relPath(it)) },
            )
            // スキーマ変更などで全再読込へ降格した場合に備え、編集中文書を再反映する（RA-05。
            // dirty 文書の変更はセッション側が取り込まないため、通常の増分では no-op に近い）
            docCache.values.filter { it.dirty }.forEach(::pushEditToSession)
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
        directories = scanDirectories(ws)
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
        val files = changedDocs.map { ws.resolve(it.value).normalize() }
        files.forEach { file ->
            docCache[file]?.let { doc ->
                // mtime 不変のため force で取り直す。未保存編集がある場合は上書きせず競合として提示する
                if (doc.dirty) doc.externalConflict = true
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
        val doc = docCache.getOrPut(file) { loadDocument(file) }
        openDoc = doc
        workspaceView = 0
        syncSpaceFromView()
        recentFiles = (listOf(file) + recentFiles.filter { it != file }).take(12)
        recentPrefs.put(workspaceKey(), recentFiles.joinToString("\n") { relPath(it) })
        editorTab = if (doc.root == null) 1 else 0
        highlightPath = focus
        status = "開いています: ${relPath(file)}" + (doc.schema?.let { "（スキーマ: ${it.name}）" } ?: "（スキーマなし・汎用ビュー）")
    }

    private fun workspaceKey(): String = "ws-${workspace?.toString()?.hashCode() ?: 0}"

    /**
     * 未保存編集を優先するツリー取得（docCache → ディスク。§4.5 の二層構造）。
     * core への loader 注入と、UIの検証（整合検証の材料読み）に使う（UX-03）。
     */
    fun unsavedFirstLoader(): (Path) -> YNode? = { file ->
        docCache[file]?.root ?: runCatching { YamlIO.parseFirst(Files.readString(file)) }.getOrNull()
    }

    /** 未保存編集を優先するツリー取得の、文書ID 版（索引・検証・ビューの解決へ渡す loader）。 */
    fun unsavedFirstTree(): (DocumentId) -> YNode? = unsavedFirstLoader().let { load -> { d -> load(fileOf(d)) } }

    fun workspaceRecords(): List<WorkspaceRecord> =
        coreWs?.let { dev.toros.yde.app.core.WorkspaceRecords.collect(it, unsavedFirstLoader()) }.orEmpty()

    /**
     * ワークスペース全体の検証（UX-44）: 索引対象の全文書を「見たまま」（未保存編集込みの
     * オーバーレイ索引＋unsavedFirstLoader）で検証し、指摘のあるファイルだけ返す。
     * 検証タブの「全体」スコープが読む。ヘッドレス経路（CLI `validate`）はディスク基準のまま（§4.5）。
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
        return dev.toros.yde.app.core.WorkspaceRecords.bulkUpdateViaSession(s, ids, key, value)
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
            val docs = YamlIO.parseAll(text)  // `---` 区切りの複数ドキュメントに対応（R8-05）
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
                    historyNotice = null  // 編集を再開したら Undo/Redo 通知は役目を終える（UX-86）
                    recordEditToSession(doc, coalesceWith)
                }
                // dirty の判定はセッション（単一所有）を正とする（RA-06）
                doc.dirtySource = {
                    session?.let { s ->
                        val id = DocumentId(relPath(doc.file))
                        if (s.hasDocument(id)) s.isDirty(id) else null
                    }
                }
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
        if (!s.hasDocument(id)) return
        runCatching {
            s.recordEdit(
                id, YDocument(id, listOf(root) + doc.extraDocs),
                coalesceKey = coalesceWith?.let { listOf(id.value, it) },
            )
        }
    }

    private fun reloadDocumentFromDisk(doc: OpenDocument, force: Boolean) {
        if (!Files.exists(doc.file)) {
            if (doc.dirty) doc.externalConflict = true
            return
        }
        val modified = runCatching { Files.getLastModifiedTime(doc.file).toMillis() }.getOrDefault(0L)
        if (!force && modified == doc.diskModifiedMillis) return
        if (doc.dirty && !force) {
            doc.externalConflict = true
            return
        }
        val text = runCatching { Files.readString(doc.file) }.getOrDefault("")
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
        doc.externalConflict = false
        doc.version++
        doc.resetBaseline()  // 保存基準・dirty を取り直す
    }

    fun acceptDiskVersion() {
        openDoc?.let {
            reloadDocumentFromDisk(it, force = true)
            // 競合解決でツリーがディスク内容に戻ったので、セッションの working も揃える（RA-05）
            pushEditToSession(it)
            editVersion++
        }
    }
    fun keepLocalVersion() {
        openDoc?.let {
            it.externalConflict = false
            it.diskModifiedMillis = runCatching { Files.getLastModifiedTime(it.file).toMillis() }.getOrDefault(0L)
        }
    }

    fun saveCurrent() {
        val doc = openDoc ?: return
        save(doc)
    }

    fun saveAll() {
        docCache.values.filter { it.dirty }.forEach { save(it) }
    }

    /**
     * Undo（A-3・RA-06 / D-07）: 履歴はセッション単一所有＝**ワークスペース横断**の1本の履歴。
     * 直前の変更が他の文書のものなら、その文書が戻る（ツリーはセッションから追随更新される）。
     */
    fun undo() {
        val s = session ?: return
        val record = s.peekUndo() ?: return
        if (s.undo().ok) afterHistoryStep(record, undo = true)
    }

    fun redo() {
        val s = session ?: return
        val record = s.peekRedo() ?: return
        if (s.redo().ok) afterHistoryStep(record, undo = false)
    }

    /** 次に Ctrl+Z で戻る変更の予告（ツールバーのツールチップ。UX-86）。セッション不在時は null。 */
    fun undoPreview(): String? {
        editVersion
        return historySummary(session?.peekUndo())
    }

    fun redoPreview(): String? {
        editVersion
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
        return session?.canUndo() ?: false
    }

    fun canRedo(): Boolean {
        editVersion
        return session?.canRedo() ?: false
    }

    /**
     * Undo/Redo 適用後の追随: 影響文書のツリーをセッションの working から取り直し、派生を進める。
     * 文書の作成・削除を含む履歴は、**ファイルの有無までその場で確定する**（D-18）——内容の編集の Undo は
     * 「未保存●」に留めて保存で確定できるが、作成の取り消し（＝文書が消える）には保存の手段が無く、
     * 削除予約のままではディスクとセッションが食い違うため。
     */
    private fun afterHistoryStep(record: ChangeRecord, undo: Boolean) {
        val ids = historyTargets(record)
        val fileLevel = (record.forward.operations + record.inverse.operations)
            .filter { it is WorkspaceOperation.CreateDocument || it is WorkspaceOperation.DeleteDocument }
            .flatMap(::operationIds)
            .toSet()
        val s = session
        if (s != null && fileLevel.isNotEmpty()) {
            val saved = s.save(fileLevel)
            if (!saved.ok) status = "ファイルの反映に失敗しました: ${saved.failures.entries.first().let { "${it.key.value}: ${it.value}" }}"
            refreshAfterSessionWrite(ids)
        } else {
            refreshDocsFromSession(ids)
            editVersion++
        }
        noteHistoryStep(record, ids, undo)
    }

    /**
     * 「何が戻ったか」の通知を組み立てる（UX-86）。未保存化（●）の明示は、
     * 適用=保存済み・Undo=未保存という非対称（D-12）の説明（UX-93）を兼ねる。
     */
    private fun noteHistoryStep(record: ChangeRecord, ids: List<DocumentId>, undo: Boolean) {
        val ws = workspace ?: return
        val s = session ?: return
        val summary = historySummary(record) ?: return
        val verb = if (undo) "元に戻しました" else "やり直しました"
        val dirtied = ids.any { s.hasDocument(it) && s.isDirty(it) }
        val suffix = if (dirtied) "——未保存（●）の状態です。保存で確定します" else ""
        val files = ids.map { ws.resolve(it.value).normalize() }.distinct().filter { Files.exists(it) }
        historyNotice = HistoryNotice("$verb: $summary$suffix", files)
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
            doc.rawText = doc.canonicalText()
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
        directories = scanDirectories(ws)
        workspaceStamp = snapshotFiles(ws)
        indexVersion++
        editVersion++
    }

    fun hasDirtyDocs(): Boolean = docCache.values.any { it.dirty }

    /** 未保存文書の数（台帳スペースの保存ボタンの件数表示用。UX-34。dirty の読み取りが再コンポーズを購読する）。 */
    fun dirtyDocCount(): Int = docCache.values.count { it.dirty }

    /** 外部変更と競合中の文書（受信箱の表示用。R12-02。データの正は各 OpenDocument）。 */
    fun conflictedDocs(): List<OpenDocument> = docCache.values.filter { it.externalConflict }

    private fun save(doc: OpenDocument) {
        runCatching {
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
                check(saved.ok) { saved.failures[id] ?: "書込に失敗しました" }
            } else {
                Files.writeString(doc.file, doc.canonicalText())
            }
            viaSession
        }.onSuccess { viaSession ->
            doc.markSaved()
            doc.rawText = doc.canonicalText()
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
            status = "保存しました: ${relPath(doc.file)}"
            historyNotice = null  // 保存で確定＝Undo/Redo 通知は役目を終える（UX-86）
        }.onFailure {
            status = "保存に失敗しました: ${it.message}"
        }
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
     * エンティティ改名のプレビュー（R21-02。ARCHITECTURE §4.31）: 全出現・全参照を書き換える計画を
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
     * スキーマのフィールド一括改名のプレビュー（R25-01。ARCHITECTURE §4.35）: データ・スキーマ本体の計画も、ビュー定義の追随と
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
        val applied = s.applyRefactoring(plan)
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
        require(name.isNotBlank() && '/' !in name && '\\' !in name) { "使用できない名前です" }
        Files.createDirectory(parent.resolve(name.trim())).also { reload() }
    }

    fun createYaml(parent: Path, name: String): Result<Path> = runCatching {
        val fileName = name.trim().let { if (it.endsWith(".yaml") || it.endsWith(".yml")) it else "$it.yaml" }
        require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName) { "使用できない名前です" }
        val file = parent.resolve(fileName)
        require(!Files.exists(file)) { "同名のファイルが存在します" }
        val schema = schemaFor(file)
        // スキーマ初期値の生成は core.Views.defaultTree に一本化する（R9-02 で重複実装を解消）
        val initial = schema?.let { dev.toros.yde.app.core.Views.defaultTree(it.root, it) } ?: YNode.Map()
        createDocumentFile(file, initial, "新規作成: ${relPath(file)}")
    }

    /**
     * ビューグリッドの行から詳細文書を新規作成する（P6）。
     * 作成先とスキーマ初期値は core.Views.detailScaffold が決め、作成後はその文書を開く。
     */
    fun createViewDetail(view: ViewDef, id: String): Result<Path> = runCatching {
        createScaffoldFile(dev.toros.yde.app.core.Views.detailScaffold(view, id, schemas))
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
        createScaffoldFile(dev.toros.yde.app.core.Views.scaffold(pattern, trimmed, schemas))
    }

    /**
     * カバレッジ（detailIn）不足の指摘から詳細文書を新規作成する（R28-05a・UX-97 の導線）。
     * 作成先は詳細スキーマの match から導く（単純な glob のみ。導けないスキーマはエラー）。
     */
    fun createDetailForCoverage(schemaId: String, id: String): Result<Path> = runCatching {
        val schema = schemas.firstOrNull { it.id == schemaId } ?: error("スキーマが見つかりません: $schemaId")
        val pattern = dev.toros.yde.app.core.Views.detailPatternFromMatch(schema.match)
            ?: error("スキーマ「$schemaId」の match（${schema.match}）から作成先を決められません")
        createScaffoldFile(dev.toros.yde.app.core.Views.scaffold(pattern, id, schemas))
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
        val s = session
        if (s != null && !s.hasDocument(id) && !WorkspaceReader.hiddenPath(id.value, s.layout)) {
            val applied = s.apply(
                WorkspaceTransaction(
                    baseVersion = s.version,
                    intent = ChangeIntent(intent),
                    operations = listOf(WorkspaceOperation.CreateDocument(id, YDocument(id, listOf(root)))),
                )
            )
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
    fun addViewRow(view: ViewDef): Result<YPath> = runCatching {
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
        val itemDef = schema?.let { dev.toros.yde.app.core.Views.fieldAt(it, root, def.path)?.item }
        seq.items.add(
            if (schema != null && itemDef != null) dev.toros.yde.app.core.Views.defaultTree(itemDef, schema) else YNode.Map()
        )
        doc.markEdited()
        def.path + (seq.items.size - 1)
    }

    /** YAMLファイルとして移動の一級化（R21-03。カーネル MoveDocument）の対象か（台帳内・ディレクトリ以外）。 */
    private fun isMovableYaml(path: Path): Boolean {
        val name = path.fileName.toString()
        return !Files.isDirectory(path) && (name.endsWith(".yaml") || name.endsWith(".yml")) && isIndexed(path)
    }

    /**
     * 改名の影響プレビュー（R21-03。ARCHITECTURE §4.31）: パスID文書参照の書き換え・スキーマ match の
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

    fun renamePath(path: Path, newName: String): Result<Path> = runCatching {
        require(newName.isNotBlank() && '/' !in newName && '\\' !in newName) { "使用できない名前です" }
        require(docCache.filterKeys { it == path || it.startsWith(path) }.values.none { it.dirty }) {
            "未保存の変更があるため名前を変更できません"
        }
        val target = path.resolveSibling(newName.trim())
        require(!Files.exists(target)) { "同名の項目が存在します" }
        val s = session
        val moveId = DocumentId(relPath(path))
        if (s != null && isMovableYaml(path) && s.hasDocument(moveId)) {
            // ファイル移動の一級化（R21-03 → B-1）: パスID文書参照の書き換えと移動を
            // カーネルの1トランザクションで適用する（旧 DocMove の「移動成功→書き換え失敗」の継ぎ目を解消）
            val plan = s.planRefactoring(RefactoringRequest.MoveDocument(moveId, DocumentId(relPath(target))))
            val applied = s.applyRefactoring(plan)
            require(applied.ok) { applied.message ?: "移動を適用できませんでした" }
            val ids = (plan.changes.map { it.document } + listOf(moveId, DocumentId(relPath(target)))).toSet()
            val saved = s.save(ids)
            require(saved.ok) {
                saved.failures.entries.firstOrNull()?.let { "${it.key.value}: ${it.value}" } ?: "移動の保存に失敗しました"
            }
        } else {
            Files.move(path, target)
        }
        recentFiles = recentFiles.map { recent ->
            if (recent == path) target else if (recent.startsWith(path)) target.resolve(path.relativize(recent)) else recent
        }
        recentPrefs.put(workspaceKey(), recentFiles.joinToString("\n") { relPath(it) })
        docCache.keys.filter { it == path || it.startsWith(path) }.toList().forEach(docCache::remove)
        if (openDoc?.file == path || openDoc?.file?.startsWith(path) == true) openDoc = null
        reload()
        target
    }

    fun deletePath(path: Path): Result<Unit> = runCatching {
        require(docCache.filterKeys { it == path || it.startsWith(path) }.values.none { it.dirty }) {
            "未保存の変更があるため削除できません"
        }
        if (Files.isDirectory(path)) {
            Files.walk(path).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        } else Files.delete(path)
        docCache.keys.filter { it == path || it.startsWith(path) }.toList().forEach(docCache::remove)
        recentFiles = recentFiles.filterNot { it == path || it.startsWith(path) }
        recentPrefs.put(workspaceKey(), recentFiles.joinToString("\n") { relPath(it) })
        if (openDoc?.file == path || openDoc?.file?.startsWith(path) == true) openDoc = null
        reload()
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
    )

    private var backStack by mutableStateOf<List<NavLocation>>(emptyList())
    private var forwardStack by mutableStateOf<List<NavLocation>>(emptyList())

    val canGoBack: Boolean get() = backStack.isNotEmpty()
    val canGoForward: Boolean get() = forwardStack.isNotEmpty()

    /** 現在の表示位置。 */
    private fun currentLocation(): NavLocation =
        NavLocation(uiSpace, workspaceView, openDoc?.file, highlightPath, crossViewId, viewGridId)

    /** 何も開いていない編集スペースは履歴に積む価値がない（台帳・実行・設計はそれ自体が場所）。 */
    private fun isEmptyLocation(loc: NavLocation): Boolean = loc.space == UiSpace.EDIT && loc.file == null

    /**
     * 現在位置を「戻る」履歴へ積む。新しい移動が起きた時点で「進む」履歴は破棄する
     * （ブラウザと同じ振る舞い）。
     */
    private fun pushBack(loc: NavLocation) {
        if (isEmptyLocation(loc)) return
        backStack = (backStack + loc).takeLast(HISTORY_LIMIT)
        forwardStack = emptyList()
    }

    /** 履歴地点を画面へ復元する（履歴には積まない）。 */
    private fun restore(loc: NavLocation) {
        when (loc.space) {
            UiSpace.EDIT -> loc.file?.let { showFile(it, loc.path) }
            UiSpace.LEDGER -> {
                loc.crossId?.let { crossViewId = it }
                loc.gridId?.let { viewGridId = it }
                workspaceView = loc.view
                syncSpaceFromView()
            }
            // 実行・設計はスペース自体が場所。下の文書世界（開いている文書・台帳ビュー）はそのまま重なる
            UiSpace.RUNS, UiSpace.DESIGN -> uiSpace = loc.space
        }
    }

    /** 編集スペースの地点で対象ファイルが削除済みなら復元できない。 */
    private fun isStale(loc: NavLocation): Boolean =
        loc.space == UiSpace.EDIT && (loc.file == null || !Files.exists(loc.file))

    /** 参照ジャンプ・ファイル切替・ビュー間ジャンプの履歴を1つ戻る（削除済みファイルは読み飛ばす）。 */
    fun goBack() {
        while (true) {
            val target = backStack.lastOrNull() ?: return
            backStack = backStack.dropLast(1)
            if (isStale(target)) continue
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
        editorTab = 0
        highlightPath = path
    }

    /**
     * ID の定義位置へジャンプする。
     * 索引（保存済みの状態）を優先し、見つからなければ現在のドキュメント内の未保存 ID を探す。
     */
    fun navigateToId(id: String) {
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
        editorTab = 0
    }

    fun dismissNavigationCandidates() { navigationCandidates = emptyList() }
}
