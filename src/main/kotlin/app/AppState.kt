package app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import index.DocScanner
import index.IdIndex
import index.IdEntry
import model.YNode
import model.YPath
import schema.SchemaDef
import schema.FieldDef
import schema.SchemaLoader
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences

/**
 * 開いている 1 ドキュメント。
 * root ツリーが正（source of truth）。フォーム編集はツリーを直接書き換え、
 * markEdited() で version をインクリメントして再コンポーズを駆動する。
 * 履歴・dirty判定・複数ドキュメント再出力の中核は core.EditBuffer（UI非依存。R9-02）へ委譲し、
 * このクラスは Compose 購読（version / dirty / parseError）のシェルに徹する。
 */
class OpenDocument(
    val file: Path,
    root: YNode?,
    schema: SchemaDef?,
    initialText: String,
    parseError: String?,
    extraDocs: List<YNode> = emptyList(),
) {
    private val buffer = core.EditBuffer(file, root, extraDocs, initialText, parseError)

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
    val historyIndex: Int get() = buffer.historyIndex

    private fun sync() {
        dirtyState.value = buffer.dirty
        parseErrorState.value = buffer.parseError
    }

    fun markEdited() {
        version++
        buffer.recordEdit()
        sync()
    }

    fun canUndo(): Boolean = buffer.canUndo()
    fun canRedo(): Boolean = buffer.canRedo()

    fun undo() { if (buffer.undo()) { version++; sync() } }
    fun redo() { if (buffer.redo()) { version++; sync() } }

    /** ソース編集のテキストをツリーへ取り込む。複数ドキュメントは先頭を編集対象にし、後続を保持する。 */
    fun applySourceText(text: String) {
        buffer.applySourceText(text)
        markEdited()
    }

    fun resetHistory() {
        buffer.resetHistory()
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
    private var coreWs: core.Workspace? = null

    var schemas by mutableStateOf<List<SchemaDef>>(emptyList())
        private set
    var schemaErrors by mutableStateOf<List<String>>(emptyList())
        private set
    var yamlFiles by mutableStateOf<List<Path>>(emptyList())
        private set
    var directories by mutableStateOf<List<Path>>(emptyList())
        private set

    private val emptyIndex = IdIndex()
    val index: IdIndex get() = coreWs?.index ?: emptyIndex
    var indexVersion by mutableStateOf(0)
        private set

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

    /** ID横ぐしビューで選択中の論理ID。 */
    var crossViewId by mutableStateOf<String?>(null)

    /** ID横ぐしビューを開く（id 指定時はそのIDを選択し、ジャンプとして履歴に積む）。 */
    fun openCrossView(id: String? = null) {
        if (id != null && !(workspaceView == 3 && crossViewId == id)) pushBack(currentLocation())
        if (id != null) crossViewId = id
        workspaceView = 3
    }

    /** ビューグリッドで選択中のビューID。 */
    var viewGridId by mutableStateOf<String?>(null)

    /** ビューグリッドを開く（id 指定時はそのビューを選択し、ジャンプとして履歴に積む）。 */
    fun openViewGrid(id: String? = null) {
        if (id != null && !(workspaceView == 4 && viewGridId == id)) pushBack(currentLocation())
        if (id != null) viewGridId = id
        workspaceView = 4
    }

    var status by mutableStateOf("「開く」からワークスペース（ディレクトリ）を選択してください")
    var aiRequest by mutableStateOf<AiRequest?>(null)
        private set
    private var workspaceStamp: Map<Path, Long> = emptyMap()

    data class AiRequest(val field: FieldDef, val path: YPath, val wholeDocument: Boolean = false)

    fun requestAi(field: FieldDef, path: YPath, wholeDocument: Boolean = false) {
        aiRequest = AiRequest(field, path, wholeDocument)
    }

    fun dismissAi() { aiRequest = null }

    // ---------------------------------------------------------------- workspace

    fun openWorkspace(dir: Path) {
        workspace = dir.toAbsolutePath().normalize()
        coreWs = null
        docCache.clear()
        openDoc = null
        highlightPath = null
        backStack = emptyList()
        forwardStack = emptyList()
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
        val cw = coreWs?.also { it.refresh() } ?: core.Workspace.open(ws).also { coreWs = it }
        mirrorCore(cw)
        directories = scanDirectories(ws)
        val existing = yamlFiles.toSet()
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
    private fun mirrorCore(cw: core.Workspace) {
        schemas = cw.schemas
        schemaErrors = cw.schemaErrors
        yamlFiles = cw.files
    }

    fun rebuildIndex() {
        val cw = coreWs ?: return
        cw.index.rebuild(cw.root, cw.files, cw.schemas)
        indexVersion++
    }

    // ファイル走査は core.WorkspaceScan（ヘッドレス層）と共通の実装を使う。
    private fun scanDirectories(ws: Path): List<Path> = core.WorkspaceScan.directories(ws)

    private fun snapshotFiles(ws: Path): Map<Path, Long> = core.WorkspaceScan.stamps(ws)

    /** UI の周期処理・ファイル監視から呼び、外部での作成・変更・削除を検出する。 */
    fun pollExternalChanges() {
        val ws = workspace ?: return
        val cw = coreWs ?: return
        val next = snapshotFiles(ws)
        if (next == workspaceStamp) return
        val changed = (next.keys + workspaceStamp.keys).filter { next[it] != workspaceStamp[it] }
        changed.forEach { file -> docCache[file]?.let { reloadDocumentFromDisk(it, force = false) } }
        // 増分か全再構築か（スキーマ変更時のみ全再構築）の振り分けは core に一元化されている（R9-02）
        cw.noteDiskChanges(
            changed = changed.filter { it in next.keys },
            removed = changed.filter { it !in next.keys },
        )
        mirrorCore(cw)
        directories = scanDirectories(ws)
        docCache.values.forEach { it.schema = schemaFor(it.file) }
        indexVersion++
        workspaceStamp = next
        status = "外部での変更を検出しました（${changed.size} 件）"
    }

    fun relPath(file: Path): String {
        val ws = workspace ?: return file.toString()
        return IdIndex.relPathOf(ws, file)
    }

    fun schemaFor(file: Path): SchemaDef? = coreWs?.schemaFor(file)

    // ---------------------------------------------------------------- documents

    fun docFor(file: Path): OpenDocument? = docCache[file]

    /**
     * 文書をエディタで開かずにキャッシュへ取り込む（ビューグリッドのセル編集用）。
     * 取り込んだ文書は通常の編集と同じく dirty 管理・保存・外部変更検出の対象になる。
     */
    fun ensureDoc(file: Path): OpenDocument = docCache.getOrPut(file) { loadDocument(file) }

    fun openFile(file: Path, focus: YPath? = null) {
        val cur = currentLocation()
        val samePlace = cur.view == 0 && cur.file == file && (focus == null || focus == cur.path)
        if (!samePlace) pushBack(cur)
        showFile(file, focus)
    }

    /** 履歴に積まずに文書を表示する（openFile の本体。「戻る」「進む」の復元にも使う）。 */
    private fun showFile(file: Path, focus: YPath?) {
        val doc = docCache.getOrPut(file) { loadDocument(file) }
        openDoc = doc
        workspaceView = 0
        recentFiles = (listOf(file) + recentFiles.filter { it != file }).take(12)
        recentPrefs.put(workspaceKey(), recentFiles.joinToString("\n") { relPath(it) })
        editorTab = if (doc.root == null) 1 else 0
        highlightPath = focus
        status = "開いています: ${relPath(file)}" + (doc.schema?.let { "（スキーマ: ${it.name}）" } ?: "（スキーマなし・汎用ビュー）")
    }

    private fun workspaceKey(): String = "ws-${workspace?.toString()?.hashCode() ?: 0}"

    /** 未保存編集を優先するツリー取得（docCache → ディスク。§4.5 の二層構造）。core への loader 注入に使う。 */
    private fun unsavedFirstLoader(): (Path) -> YNode? = { file ->
        docCache[file]?.root ?: runCatching { YamlIO.parseFirst(Files.readString(file)) }.getOrNull()
    }

    fun workspaceRecords(): List<core.WorkspaceRecord> =
        coreWs?.let { core.WorkspaceRecords.collect(it, unsavedFirstLoader()) }.orEmpty()

    /**
     * 一覧ビューで選択したファイルのルート直下スカラーを一括変更する。
     * 対象キーは任意のルートスカラーキー（R8-05: L-07）。ID・予約キー・複合値（配列/オブジェクト）は
     * 壊さないよう対象外とする。書換ロジックは core.WorkspaceRecords（R9-02）。
     */
    fun bulkUpdate(files: Set<Path>, key: String, value: String): Result<Int> = runCatching {
        require(key.isNotBlank() && !key.startsWith("_") && key != "id") { "一括変更できない項目です: $key" }
        require(files.none { docCache[it]?.dirty == true }) { "未保存のファイルが含まれています" }
        val reopen = openDoc?.file?.takeIf { it in files }
        val count = core.WorkspaceRecords.updateRootScalar(files, key, value)
        files.forEach(docCache::remove)
        reload()
        reopen?.let { file ->
            val refreshed = loadDocument(file)
            docCache[file] = refreshed
            openDoc = refreshed
        }
        count
    }

    /**
     * 一括編集の対象にできるキーの候補（R8-05: L-07）。
     * ワークスペース文書のルート直下に現れるスカラーキーを集める（id・予約キーは除く）。
     */
    fun bulkKeyCandidates(): List<String> =
        coreWs?.let { core.WorkspaceRecords.bulkKeyCandidates(it, unsavedFirstLoader()) }.orEmpty()

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
        doc.resetHistory()  // 履歴・保存基準・dirty を取り直す
    }

    fun acceptDiskVersion() { openDoc?.let { reloadDocumentFromDisk(it, force = true) } }
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

    fun undo() { openDoc?.undo() }
    fun redo() { openDoc?.redo() }

    fun hasDirtyDocs(): Boolean = docCache.values.any { it.dirty }

    private fun save(doc: OpenDocument) {
        runCatching {
            Files.writeString(doc.file, doc.canonicalText())
        }.onSuccess {
            doc.markSaved()
            doc.rawText = doc.canonicalText()
            doc.diskModifiedMillis = Files.getLastModifiedTime(doc.file).toMillis()
            doc.externalConflict = false
            workspace?.let { workspaceStamp = snapshotFiles(it) }
            // 保存したファイルだけ索引を差し替える。スキーマ保存時の全再構築への振り分けは core が行う（R8-02 / R9-02）
            coreWs?.let { cw ->
                cw.noteDiskChanges(listOf(doc.file))
                mirrorCore(cw)
                // スキーマ本体だけでなく派生スキーマの導出元文書（R11-03）の保存でもスキーマ集合が変わるため、常に割り当て直す
                docCache.values.forEach { it.schema = schemaFor(it.file) }
                indexVersion++
            }
            status = "保存しました: ${relPath(doc.file)}"
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
        val regex = schema.globToRegex(glob)
        val loader = unsavedFirstLoader()
        val samples = yamlFiles
            .map { relPath(it) to it }
            .filter { (rel, _) -> !rel.startsWith("_") && regex.matches(rel) }
            .map { (rel, file) -> schema.SchemaInference.Sample(rel, loader(file)) }
        val draft = schema.SchemaInference.infer(samples, schemaId = id, match = glob)
        val file = ws.resolve(SchemaLoader.SCHEMA_DIR).resolve("$id.yaml")
        require(!Files.exists(file)) { "既にスキーマファイルが存在します: ${relPath(file)}" }
        Files.createDirectories(file.parent)
        Files.writeString(file, YamlIO.dump(draft))
        reload()
        openFile(file)
        file
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
        val initial = schema?.let { YamlIO.dump(core.Views.defaultTree(it.root, it)) } ?: "{}\n"
        Files.writeString(file, initial)
        reload()
        openFile(file)
        file
    }

    /**
     * ビューグリッドの行から詳細文書を新規作成する（P6）。
     * 作成先とスキーマ初期値は core.Views.detailScaffold が決め、作成後はその文書を開く。
     */
    fun createViewDetail(view: core.ViewDef, id: String): Result<Path> = runCatching {
        val ws = workspace ?: error("ワークスペースが開かれていません")
        val scaffold = core.Views.detailScaffold(view, id, schemas)
        val file = ws.resolve(scaffold.rel).normalize()
        require(file.startsWith(ws)) { "ワークスペース外には作成できません: ${scaffold.rel}" }
        require(!Files.exists(file)) { "既に同名のファイルが存在します: ${scaffold.rel}" }
        Files.createDirectories(file.parent)
        Files.writeString(file, YamlIO.dump(scaffold.root))
        reload()
        openFile(file)
        file
    }

    /**
     * ビューの newRow 定義に従い、一覧文書の配列へ新しい行（要素）を追加する（P6）。
     * 追加は未保存編集としてキャッシュ上の文書に積まれる（保存は通常の操作で行う）。
     */
    fun addViewRow(view: core.ViewDef): Result<YPath> = runCatching {
        val def = view.newRow ?: error("このビューには newRow が定義されていません")
        val ws = workspace ?: error("ワークスペースが開かれていません")
        val file = ws.resolve(def.file).normalize()
        require(Files.isRegularFile(file)) { "一覧文書が存在しません: ${def.file}" }
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
        val itemDef = schema?.let { core.Views.fieldAt(it, root, def.path)?.item }
        seq.items.add(
            if (schema != null && itemDef != null) core.Views.defaultTree(itemDef, schema) else YNode.Map()
        )
        doc.markEdited()
        def.path + (seq.items.size - 1)
    }

    fun renamePath(path: Path, newName: String): Result<Path> = runCatching {
        require(newName.isNotBlank() && '/' !in newName && '\\' !in newName) { "使用できない名前です" }
        require(docCache.filterKeys { it == path || it.startsWith(path) }.values.none { it.dirty }) {
            "未保存の変更があるため名前を変更できません"
        }
        val target = path.resolveSibling(newName.trim())
        require(!Files.exists(target)) { "同名の項目が存在します" }
        Files.move(path, target)
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
     * ナビゲーション履歴の1地点。文書ビュー（view=0）はファイルとハイライト位置、
     * それ以外のワークスペースビューはビュー番号と選択中ID（横ぐし／ビューグリッド）で位置を表す。
     */
    data class NavLocation(
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
        NavLocation(workspaceView, openDoc?.file, highlightPath, crossViewId, viewGridId)

    /** 何も開いていない文書ビューは履歴に積む価値がない。 */
    private fun isEmptyLocation(loc: NavLocation): Boolean = loc.view == 0 && loc.file == null

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
        if (loc.view == 0) {
            loc.file?.let { showFile(it, loc.path) }
        } else {
            loc.crossId?.let { crossViewId = it }
            loc.gridId?.let { viewGridId = it }
            workspaceView = loc.view
        }
    }

    /** 文書ビューの地点で対象ファイルが削除済みなら復元できない。 */
    private fun isStale(loc: NavLocation): Boolean =
        loc.view == 0 && (loc.file == null || !Files.exists(loc.file))

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
        if (cur.view != 0 || cur.path != path) pushBack(cur)
        workspaceView = 0
        editorTab = 0
        highlightPath = path
    }

    /**
     * ID の定義位置へジャンプする。
     * 索引（保存済みの状態）を優先し、見つからなければ現在のドキュメント内の未保存 ID を探す。
     */
    fun navigateToId(id: String) {
        val entries = index.occurrences(id)
        if (entries.size > 1) {
            navigationCandidates = entries
            return
        }
        entries.firstOrNull()?.let { navigateToOccurrence(it); return }
        // 未保存ドキュメント内の ID を探す
        val doc = openDoc
        if (doc?.root != null) {
            val documentId = IdIndex.documentIdOf(doc.root!!, relPath(doc.file))
            val (ids, _) = DocScanner.scan(doc.root, doc.file, doc.schema, documentId)
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
        openFile(entry.file, entry.path)
        editorTab = 0
    }

    fun dismissNavigationCandidates() { navigationCandidates = emptyList() }
}
