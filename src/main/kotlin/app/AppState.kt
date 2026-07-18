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
) {
    var schema by mutableStateOf(schema)
    var root: YNode? = root
    var rawText: String = initialText          // パース不能なときの元テキスト
    var parseError by mutableStateOf(parseError)
    var version by mutableStateOf(0)           // 編集のたびにインクリメント
    var dirty by mutableStateOf(false)
    var externalConflict by mutableStateOf(false)
    var diskModifiedMillis: Long = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)
    private val history = mutableListOf(initialTextForHistory(root, initialText))
    private var savedSnapshot = history.first()
    var historyIndex by mutableStateOf(0)
        private set

    private fun initialTextForHistory(node: YNode?, fallback: String) = node?.let(YamlIO::dump) ?: fallback

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

    private fun restoreHistory(index: Int) {
        val text = history[index]
        root = runCatching { YamlIO.parse(text) }.getOrNull()
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
    fun canonicalText(): String = root?.let { YamlIO.dump(it) } ?: rawText
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
    /** 0 = 文書, 1 = 一覧, 2 = 参照グラフ, 3 = ID横ぐし */
    var workspaceView by mutableStateOf(0)

    /** ID横ぐしビューで選択中の論理ID。 */
    var crossViewId by mutableStateOf<String?>(null)

    /** ID横ぐしビューを開く（id 指定時はそのIDを選択）。 */
    fun openCrossView(id: String? = null) {
        if (id != null) crossViewId = id
        workspaceView = 3
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

    // ファイル走査は core.WorkspaceScan（ヘッドレス層）と共通の実装を使う。
    private fun scanYamlFiles(ws: Path): List<Path> = core.WorkspaceScan.yamlFiles(ws)

    private fun scanDirectories(ws: Path): List<Path> = core.WorkspaceScan.directories(ws)

    private fun snapshotFiles(ws: Path): Map<Path, Long> = core.WorkspaceScan.stamps(ws)

    /** UI の周期処理から呼び、外部での作成・変更・削除を検出する。 */
    fun pollExternalChanges() {
        val ws = workspace ?: return
        val next = snapshotFiles(ws)
        if (next == workspaceStamp) return
        val changed = (next.keys + workspaceStamp.keys).filter { next[it] != workspaceStamp[it] }
        yamlFiles = next.keys.sortedBy { it.toString() }
        directories = scanDirectories(ws)
        changed.forEach { file -> docCache[file]?.let { reloadDocumentFromDisk(it, force = false) } }
        loadSchemas(ws)
        docCache.values.forEach { it.schema = schemaFor(it.file) }
        rebuildIndex()
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
            val root = docCache[file]?.root ?: runCatching { YamlIO.parse(Files.readString(file)) }.getOrNull()
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

    /** 一覧ビューで選択したファイルのルートスカラーを一括変更する。 */
    fun bulkUpdate(files: Set<Path>, key: String, value: String): Result<Int> = runCatching {
        require(key in setOf("status", "owner")) { "一括変更できない項目です" }
        require(files.none { docCache[it]?.dirty == true }) { "未保存のファイルが含まれています" }
        var count = 0
        val reopen = openDoc?.file?.takeIf { it in files }
        files.forEach { file ->
            val root = YamlIO.parse(Files.readString(file)) as? YNode.Map ?: return@forEach
            root.entries[key] = YNode.Scalar(value)
            Files.writeString(file, YamlIO.dump(root))
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

    private fun loadDocument(file: Path): OpenDocument {
        val text = runCatching { Files.readString(file) }.getOrDefault("")
        var error: String? = null
        val root = try {
            YamlIO.parse(text)
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            null
        }
        return OpenDocument(file, root, schemaFor(file), text, error)
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
        runCatching { YamlIO.parse(text) }.onSuccess {
            doc.root = it
            doc.rawText = text
            doc.parseError = null
        }.onFailure {
            doc.root = null
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
            }
            rebuildIndex()
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
            FieldType.OBJECT -> YNode.Map(LinkedHashMap<String, YNode>().apply {
                f.fields.filter { it.required && it.key != null }.forEach { put(it.key!!, defaultFor(it, schema, depth + 1)) }
            })
            FieldType.ARRAY -> YNode.Seq()
            FieldType.MAP -> YNode.Map()
            FieldType.BOOLEAN -> YNode.Scalar(false)
            FieldType.INT, FieldType.NUMBER -> YNode.Scalar(0)
            else -> YNode.Scalar("")
        }
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
