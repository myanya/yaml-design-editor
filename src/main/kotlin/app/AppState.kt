package app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import index.DocScanner
import index.IdIndex
import index.IdEntry
import model.YNode
import model.YPath
import model.scalarText
import schema.SchemaDef
import schema.FieldDef
import schema.FieldType
import schema.SchemaLoader
import schema.effectiveFields
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences

/**
 * 開いている 1 ドキュメント。
 * root ツリーが正（source of truth）。フォーム編集はツリーを直接書き換え、
 * markEdited() で version をインクリメントして再コンポーズを駆動する。
 */
class OpenDocument(
    val file: Path,
    root: YNode?,
    schema: SchemaDef?,
    initialText: String,
    parseError: String?,
    extraDocs: List<YNode> = emptyList(),
) {
    var schema by mutableStateOf(schema)
    var root: YNode? = root

    /**
     * `---` 区切りの後続ドキュメント（R8-05: 複数ドキュメントYAML）。
     * フォーム・索引の編集対象は先頭ドキュメントのみで、後続は保持して保存時に再出力する
     * （ソースタブでは全ドキュメントを編集できる）。
     */
    var extraDocs: List<YNode> = extraDocs

    var rawText: String = initialText          // パース不能なときの元テキスト
    var parseError by mutableStateOf(parseError)
    var version by mutableStateOf(0)           // 編集のたびにインクリメント
    var dirty by mutableStateOf(false)
    var externalConflict by mutableStateOf(false)
    var diskModifiedMillis: Long = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)
    private val history = mutableListOf(if (root != null) canonicalText() else initialText)
    private var savedSnapshot = history.first()
    var historyIndex by mutableStateOf(0)
        private set

    fun markEdited() {
        version++
        dirty = canonicalText() != savedSnapshot
        val snapshot = canonicalText()
        if (history.getOrNull(historyIndex) != snapshot) {
            while (history.lastIndex > historyIndex) history.removeAt(history.lastIndex)
            history.add(snapshot)
            if (history.size > 100) history.removeAt(0) else historyIndex++
        }
    }

    fun canUndo(): Boolean = historyIndex > 0
    fun canRedo(): Boolean = historyIndex < history.lastIndex

    fun undo() { if (canUndo()) restoreHistory(historyIndex - 1) }
    fun redo() { if (canRedo()) restoreHistory(historyIndex + 1) }

    /** ソース編集のテキストをツリーへ取り込む。複数ドキュメントは先頭を編集対象にし、後続を保持する。 */
    fun applySourceText(text: String) {
        val docs = YamlIO.parseAll(text)
        root = docs.firstOrNull() ?: YNode.Map()
        extraDocs = docs.drop(1)
        parseError = null
        markEdited()
    }

    private fun restoreHistory(index: Int) {
        val text = history[index]
        val docs = runCatching { YamlIO.parseAll(text) }.getOrNull()
        root = if (docs != null) (docs.firstOrNull() ?: YNode.Map()) else null
        extraDocs = docs?.drop(1).orEmpty()
        rawText = text
        parseError = if (root == null) "履歴の復元に失敗しました" else null
        historyIndex = index
        dirty = text != savedSnapshot
        version++
    }

    fun resetHistory() {
        history.clear()
        history.add(canonicalText())
        historyIndex = 0
        savedSnapshot = canonicalText()
        dirty = false
    }

    fun markSaved() { savedSnapshot = canonicalText(); dirty = false }

    /** 保存・ソース表示に使う正規化テキスト。パース不能なら元テキストを返す。 */
    fun canonicalText(): String = root?.let { YamlIO.dumpAll(listOf(it) + extraDocs) } ?: rawText
}

/**
 * アプリケーション状態。
 */
class AppState {
    private val recentPrefs = Preferences.userRoot().node("yaml-design-editor/recent")

    data class WorkspaceRecord(
        val file: Path, val id: String?, val title: String?, val status: String?,
        val owner: String?, val kind: String?,
    )

    var workspace by mutableStateOf<Path?>(null)
        private set
    var schemas by mutableStateOf<List<SchemaDef>>(emptyList())
        private set
    var schemaErrors by mutableStateOf<List<String>>(emptyList())
        private set
    var yamlFiles by mutableStateOf<List<Path>>(emptyList())
        private set
    var directories by mutableStateOf<List<Path>>(emptyList())
        private set

    val index = IdIndex()
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

    /** 0 = フォーム, 1 = ソース */
    var editorTab by mutableStateOf(0)
    /** 0 = 文書, 1 = 一覧, 2 = 参照グラフ, 3 = ID横ぐし, 4 = ビューグリッド */
    var workspaceView by mutableStateOf(0)

    /** ID横ぐしビューで選択中の論理ID。 */
    var crossViewId by mutableStateOf<String?>(null)

    /** ID横ぐしビューを開く（id 指定時はそのIDを選択）。 */
    fun openCrossView(id: String? = null) {
        if (id != null) crossViewId = id
        workspaceView = 3
    }

    /** ビューグリッドで選択中のビューID。 */
    var viewGridId by mutableStateOf<String?>(null)

    /** ビューグリッドを開く（id 指定時はそのビューを選択）。 */
    fun openViewGrid(id: String? = null) {
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
        docCache.clear()
        openDoc = null
        highlightPath = null
        recentFiles = recentPrefs.get(workspaceKey(), "").lineSequence().filter { it.isNotBlank() }
            .map { workspace!!.resolve(it).normalize() }.filter(Files::exists).take(12).toList()
        reload()
    }

    /** ファイル一覧・スキーマ・索引をディスクから再読込する（未保存編集は保持）。 */
    fun reload() {
        val ws = workspace ?: return
        loadSchemas(ws)
        yamlFiles = scanYamlFiles(ws)
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
        rebuildIndex()
        workspaceStamp = snapshotFiles(ws)
        status = "ワークスペース: $ws ／ YAML ${yamlFiles.size} 件・スキーマ ${schemas.size} 件・ID ${index.entries.size} 件"
    }

    private fun loadSchemas(ws: Path) {
        val result = SchemaLoader.loadAllWithErrors(ws)
        schemas = result.schemas
        schemaErrors = result.errors
    }

    fun rebuildIndex() {
        val ws = workspace ?: return
        index.rebuild(ws, yamlFiles, schemas)
        indexVersion++
    }

    /**
     * 変更・削除のあったファイルだけ索引を差し替える（R8-02: 増分更新）。
     * スキーマが変わった場合は走査規則自体が変わるため rebuildIndex() を使うこと。
     */
    private fun updateIndexFor(changed: Collection<Path>, removed: Collection<Path> = emptyList()) {
        val ws = workspace ?: return
        index.update(ws, changed, removed, schemas)
        indexVersion++
    }

    // ファイル走査は core.WorkspaceScan（ヘッドレス層）と共通の実装を使う。
    private fun scanYamlFiles(ws: Path): List<Path> = core.WorkspaceScan.yamlFiles(ws)

    private fun scanDirectories(ws: Path): List<Path> = core.WorkspaceScan.directories(ws)

    private fun snapshotFiles(ws: Path): Map<Path, Long> = core.WorkspaceScan.stamps(ws)

    /** UI の周期処理・ファイル監視から呼び、外部での作成・変更・削除を検出する。 */
    fun pollExternalChanges() {
        val ws = workspace ?: return
        val next = snapshotFiles(ws)
        if (next == workspaceStamp) return
        val changed = (next.keys + workspaceStamp.keys).filter { next[it] != workspaceStamp[it] }
        yamlFiles = next.keys.sortedBy { it.toString() }
        directories = scanDirectories(ws)
        changed.forEach { file -> docCache[file]?.let { reloadDocumentFromDisk(it, force = false) } }
        // スキーマが変わったときだけ全再構築（走査規則が変わるため）。通常は変更ファイルのみ差し替える（R8-02）
        if (changed.any { relPath(it).startsWith(SchemaLoader.SCHEMA_DIR + "/") }) {
            loadSchemas(ws)
            docCache.values.forEach { it.schema = schemaFor(it.file) }
            rebuildIndex()
        } else {
            updateIndexFor(changed = changed.filter { it in next.keys }, removed = changed.filter { it !in next.keys })
        }
        workspaceStamp = next
        status = "外部での変更を検出しました（${changed.size} 件）"
    }

    fun relPath(file: Path): String {
        val ws = workspace ?: return file.toString()
        return IdIndex.relPathOf(ws, file)
    }

    fun schemaFor(file: Path): SchemaDef? {
        val rel = relPath(file)
        if (rel.startsWith(SchemaLoader.SCHEMA_DIR + "/")) return null
        return schemas.firstOrNull { it.matches(rel) }
    }

    // ---------------------------------------------------------------- documents

    fun docFor(file: Path): OpenDocument? = docCache[file]

    /**
     * 文書をエディタで開かずにキャッシュへ取り込む（ビューグリッドのセル編集用）。
     * 取り込んだ文書は通常の編集と同じく dirty 管理・保存・外部変更検出の対象になる。
     */
    fun ensureDoc(file: Path): OpenDocument = docCache.getOrPut(file) { loadDocument(file) }

    fun openFile(file: Path, focus: YPath? = null) {
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

    fun workspaceRecords(): List<WorkspaceRecord> = yamlFiles.asSequence()
        .filter { !relPath(it).startsWith(SchemaLoader.SCHEMA_DIR + "/") }
        .mapNotNull { file ->
            val root = docCache[file]?.root ?: runCatching { YamlIO.parseFirst(Files.readString(file)) }.getOrNull()
            val map = root as? YNode.Map ?: return@mapNotNull null
            WorkspaceRecord(
                file = file,
                id = scalarText(map.entries["id"]),
                title = scalarText(map.entries["title"]) ?: scalarText(map.entries["name"]),
                status = scalarText(map.entries["status"]),
                owner = scalarText(map.entries["owner"]),
                kind = schemaFor(file)?.name,
            )
        }.sortedBy { relPath(it.file) }.toList()

    /**
     * 一覧ビューで選択したファイルのルート直下スカラーを一括変更する。
     * 対象キーは任意のルートスカラーキー（R8-05: L-07）。ID・予約キー・複合値（配列/オブジェクト）は
     * 壊さないよう対象外とする。
     */
    fun bulkUpdate(files: Set<Path>, key: String, value: String): Result<Int> = runCatching {
        require(key.isNotBlank() && !key.startsWith("_") && key != "id") { "一括変更できない項目です: $key" }
        require(files.none { docCache[it]?.dirty == true }) { "未保存のファイルが含まれています" }
        var count = 0
        val reopen = openDoc?.file?.takeIf { it in files }
        files.forEach { file ->
            val docs = runCatching { YamlIO.parseAll(Files.readString(file)) }.getOrNull() ?: return@forEach
            val root = docs.firstOrNull() as? YNode.Map ?: return@forEach
            val existing = root.entries[key]
            if (existing != null && existing !is YNode.Scalar) return@forEach  // 複合値は上書きしない
            val node = YNode.Scalar(value)
            node.inheritMetaFrom(existing)
            root.entries[key] = node
            Files.writeString(file, YamlIO.dumpAll(docs))
            docCache.remove(file)
            count++
        }
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
    fun bulkKeyCandidates(): List<String> {
        val keys = linkedSetOf("status", "owner")
        yamlFiles.asSequence()
            .filter { !relPath(it).startsWith("_") }
            .forEach { file ->
                val root = docCache[file]?.root ?: runCatching { YamlIO.parseFirst(Files.readString(file)) }.getOrNull()
                (root as? YNode.Map)?.entries?.forEach { (k, v) ->
                    if (v is YNode.Scalar && k != "id" && !k.startsWith("_")) keys.add(k)
                }
            }
        return keys.toList()
    }

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
        doc.dirty = false
        doc.version++
        doc.resetHistory()
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
            if (relPath(doc.file).startsWith(SchemaLoader.SCHEMA_DIR + "/")) {
                workspace?.let(::loadSchemas)
                docCache.values.forEach { it.schema = schemaFor(it.file) }
                rebuildIndex()
            } else {
                updateIndexFor(listOf(doc.file))  // 保存したファイルだけ索引を差し替える（R8-02）
            }
            status = "保存しました: ${relPath(doc.file)}"
        }.onFailure {
            status = "保存に失敗しました: ${it.message}"
        }
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
        val initial = schema?.let { YamlIO.dump(defaultFor(it.root, it)) } ?: "{}\n"
        Files.writeString(file, initial)
        reload()
        openFile(file)
        file
    }

    private fun defaultFor(field: FieldDef, schema: SchemaDef, depth: Int = 0): YNode {
        if (depth > 24) return YNode.Map()
        val f = schema.resolve(field) ?: field
        if (f.default != null) return YamlIO.fromAny(f.default)
        return when (f.type) {
            FieldType.OBJECT -> {
                val entries = LinkedHashMap<String, YNode>().apply {
                    f.fields.filter { it.required && it.key != null }.forEach { put(it.key!!, defaultFor(it, schema, depth + 1)) }
                }
                val map = YNode.Map(entries)
                // 判別 enum の初期値に応じた case フィールドのうち必須のものを補う
                schema.effectiveFields(f.fields, map).fields.forEach { ch ->
                    val key = ch.key ?: return@forEach
                    if (ch.required && key !in entries) entries[key] = defaultFor(ch, schema, depth + 1)
                }
                map
            }
            FieldType.ARRAY -> YNode.Seq()
            FieldType.MAP -> YNode.Map()
            FieldType.BOOLEAN -> YNode.Scalar(false)
            FieldType.INT, FieldType.NUMBER -> YNode.Scalar(0)
            else -> YNode.Scalar("")
        }
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
                highlightPath = local.path
                editorTab = 0
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
