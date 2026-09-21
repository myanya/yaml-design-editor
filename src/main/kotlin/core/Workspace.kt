package core

import index.IdIndex
import index.derivedIdIndex
import index.populateFromScans
import model.YNode
import schema.SchemaDef
import schema.SchemaLoader
import schema.WorkspaceLayout
import validate.Issue
import validate.Severity
import validate.Validator
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/** ワークスペースのファイル走査（UI・アプリ状態に依存しない共通処理）。 */
object WorkspaceScan {

    /**
     * 走査対象の *.yaml / *.yml を列挙する（隠しファイル・隠しディレクトリを除く。構成ディレクトリ `.yde` だけ例外）。
     * `ledger` 宣言があるレイアウトでは台帳＋構成ディレクトリのみを対象にする（ARCHITECTURE §4.25）。
     */
    fun yamlFiles(ws: Path, layout: WorkspaceLayout = WorkspaceLayout.of(ws)): List<Path> {
        val result = mutableListOf<Path>()
        walk(ws, layout) { p ->
            if (!Files.isRegularFile(p)) return@walk
            if (p.fileName.toString().startsWith(".")) return@walk
            if (p.extension != "yaml" && p.extension != "yml") return@walk
            val rel = IdIndex.relPathOf(ws, p)
            if (layout.inLedger(rel) || layout.isReserved(rel)) result.add(p)
        }
        return result.sortedBy { it.toString() }
    }

    /**
     * 台帳外を含む全YAMLを列挙する（エディタ層の表示用。R13-04）。
     * 隠しディレクトリは除く（`.yde` を含む——構成・定義は yamlFiles / 設計スペースが受け持つ）。
     */
    fun editorYamlFiles(ws: Path): List<Path> {
        val result = mutableListOf<Path>()
        walk(ws, WorkspaceLayout.LEGACY) { p ->
            if (!Files.isRegularFile(p)) return@walk
            if (p.fileName.toString().startsWith(".")) return@walk
            if (p.extension == "yaml" || p.extension == "yml") result.add(p)
        }
        return result.sortedBy { it.toString() }
    }

    /** 隠しディレクトリの配下を枝刈りしながら歩く（構成ディレクトリ `.yde` だけ降りる）。 */
    private fun walk(ws: Path, layout: WorkspaceLayout, visit: (Path) -> Unit) {
        Files.walkFileTree(ws, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                if (dir != ws && dir.fileName.toString().startsWith(".")) {
                    val rel = IdIndex.relPathOf(ws, dir)
                    if (rel != layout.configDir) return java.nio.file.FileVisitResult.SKIP_SUBTREE
                }
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                visit(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): java.nio.file.FileVisitResult =
                java.nio.file.FileVisitResult.CONTINUE
        })
    }

    /** 隠しディレクトリを除くサブディレクトリを列挙する。 */
    fun directories(ws: Path): List<Path> {
        val result = mutableListOf<Path>()
        Files.walk(ws).use { stream ->
            stream.filter { it != ws && Files.isDirectory(it) }.forEach { p ->
                val rel = IdIndex.relPathOf(ws, p)
                if (rel.split('/').none { it.startsWith(".") }) result.add(p)
            }
        }
        return result.sortedBy { it.toString() }
    }

    /** 外部変更検出に使う「ファイル -> 最終更新時刻」のスナップショット。 */
    fun stamps(ws: Path, layout: WorkspaceLayout = WorkspaceLayout.of(ws)): Map<Path, Long> = buildMap {
        yamlFiles(ws, layout).forEach { p ->
            put(p, runCatching { Files.getLastModifiedTime(p).toMillis() }.getOrDefault(0L))
        }
    }
}

/** 読み込んだ 1 文書。root が null のときは parseError にパース失敗理由が入る。 */
data class LoadedDocument(
    val rel: String,
    val file: Path,
    val root: YNode?,
    val text: String,
    val parseError: String?,
    val schema: SchemaDef?,
)

/** 変更セット適用の 1 ファイル分の結果。 */
data class FileChangeResult(
    val file: String,
    val applied: Boolean,
    val message: String?,           // 競合・操作エラーの内容（成功時は null）
    val issues: List<Issue>,        // 適用後（またはドライラン後）の検証結果
)

data class ChangeSetResult(
    val ok: Boolean,                // 全ファイルに適用（またはドライラン成功）できたか
    val dryRun: Boolean,
    val results: List<FileChangeResult>,
) {
    val errorIssueCount: Int get() = results.sumOf { r -> r.issues.count { it.severity == Severity.ERROR } }
}

/**
 * UI に依存しないワークスペース操作の一式（R1-01）。
 * CLI・エージェント・既存 UI（AppState）が共通で使う。索引はディスク上の保存済み状態に基づく。
 */
class Workspace private constructor(val root: Path) {

    var schemas: List<SchemaDef> = emptyList()
        private set
    var schemaErrors: List<String> = emptyList()
        private set
    /** 関係レジストリ（R20-02 / R21-01）: `kind: relations` 文書から集約した name → RelationDef。表示とグラフ整合検証に使う。 */
    var relations: Map<String, schema.RelationDef> = emptyMap()
        private set
    /** 強制スイッチ（R21-01。ARCHITECTURE §4.31）: レジストリ文書の enforcement 節の集約。 */
    var enforcement: schema.EnforcementDef = schema.EnforcementDef()
        private set
    /** エンティティ型レジストリ（R23-01。§4.33）: `entities:` 節の集約。表示名・アスペクト順序の解決に使う。 */
    var entityTypes: Map<String, schema.EntityTypeDef> = emptyMap()
        private set
    var files: List<Path> = emptyList()
        private set
    /** ワークスペースの配置ポリシー（R13-02）。refresh のたびに再評価する。 */
    var layout: WorkspaceLayout = WorkspaceLayout.LEGACY
        private set
    val index = IdIndex()

    /**
     * 索引の供給元セッション（B-2・RB-07）: ディスクミラーのカーネルセッションを内部に持ち、
     * `index` はその派生（derived.semantic.scans）を写した IdIndex ファサードになる。
     * 走査・集約の実体はカーネル（RA-02 の overlayIndex 切替と同じ供給路＝SessionReadParityTest で一致証明済み）。
     * RB-09 でディスク走査のフォールバックを撤去＝セッションが唯一の供給（refresh で必ず開き直す）。
     */
    private var session: workspace.WorkspaceSession? = null

    companion object {
        fun open(dir: Path): Workspace {
            require(Files.isDirectory(dir)) { "ワークスペースが存在しません: $dir" }
            return Workspace(dir.toAbsolutePath().normalize()).also { it.refresh() }
        }
    }

    /** スキーマ・ファイル一覧・索引をディスクから再構築する。 */
    fun refresh() {
        layout = WorkspaceLayout.of(root)
        // スキーマを読むのは、カーネルの経路（セッション）だけ。ここは、その結果からアプリの形（SchemaDef）を作り、
        // アプリだけに残る点検を足す——以前は root の SchemaLoader でもう一度読んでいた（P27 段C の C-3b・意図的差分 D-32）
        val opened = workspace.WorkspaceSession(
            workspace.FileSystemDocumentStore(root),
            builtins = schema.BuiltinSchemas.kernelSchemas(layout),
        )
        session = opened
        val loaded = SchemaLoader.fromKernel(root, layout, opened.schemas, opened.declarations, opened.schemaErrors)
        schemas = loaded.schemas
        schemaErrors = loaded.errors
        relations = loaded.relations
        enforcement = loaded.enforcement
        entityTypes = loaded.entityTypes
        files = WorkspaceScan.yamlFiles(root, layout)
        repopulateIndex()
    }

    /** index ファサードをセッション派生から写す。 */
    private fun repopulateIndex() {
        val s = session ?: error("セッションが開かれていません")
        index.populateFromScans(s.derivedWith(emptyMap()).semantic.scans, root)
    }

    fun relPath(file: Path): String = IdIndex.relPathOf(root, file)

    /** 相対パスを検証付きで解決する（ワークスペース外への脱出を拒否）。 */
    fun resolve(rel: String): Path {
        val resolved = root.resolve(rel).normalize()
        require(resolved.startsWith(root)) { "ワークスペース外のパスは扱えません: $rel" }
        return resolved
    }

    fun schemaFor(file: Path): SchemaDef? {
        val rel = relPath(file)
        if (layout.zoneOf(rel) == WorkspaceLayout.Zone.SCHEMAS) {
            // schemas ゾーンにはメタスキーマ（R11-02）だけを適用する。一般スキーマの match は及ばない
            // （catch-all スキーマの誤適用を防ぐ。利用者は同IDのスキーマで上書きできる）。
            // kind: relations の文書には関係レジストリのメタスキーマ（R20-02。§4.30）を振り分ける
            val metaId = if (isRelationsDoc(file)) schema.BuiltinSchemas.RELATIONS_SCHEMA_ID
            else schema.BuiltinSchemas.META_SCHEMA_ID
            return schemas.firstOrNull { it.id == metaId && it.matches(rel) }
        }
        // ゾーン外の構成ファイル（.yde/workspace.yaml 等）にはスキーマを当てない（§4.25）
        if (layout.isReserved(rel) && layout.zoneOf(rel) == null) return null
        return schemas.firstOrNull { it.matches(rel) }
    }

    /** schemas ゾーンの文書が関係レジストリ（kind: relations）か（ディスク基準の軽量判定）。 */
    private fun isRelationsDoc(file: Path): Boolean = runCatching {
        val root = YamlIO.parseFirst(Files.readString(file)) as? YNode.Map
        model.scalarText(root?.entries?.get("kind")) == schema.SchemaKind.RELATIONS.id
    }.getOrDefault(false)

    fun load(rel: String): LoadedDocument {
        val file = resolve(rel)
        require(Files.isRegularFile(file)) { "ファイルが存在しません: $rel" }
        val text = Files.readString(file)
        var error: String? = null
        val docRoot = try {
            YamlIO.parse(text)
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            null
        }
        return LoadedDocument(relPath(file), file, docRoot, text, error, schemaFor(file))
    }

    /** ツリーを正規化して保存し、索引を更新する。 */
    fun save(rel: String, node: YNode) {
        val file = resolve(rel)
        Files.writeString(file, YamlIO.dump(node))
        refreshAfterWrite(listOf(file))
    }

    /**
     * 書き込んだファイルの分だけ索引を更新する（R8-02: 増分更新）。
     * スキーマ（`_schemas/`）を書いた場合は走査規則が変わるため全再構築する。
     */
    private fun refreshAfterWrite(written: List<Path>) {
        noteDiskChanges(written)
    }

    /**
     * ディスク上のファイル変更（保存・外部変更・削除）をコアへ通知し、索引を更新する（R9-02）。
     * 増分か全再構築かの振り分け（スキーマ変更時のみ全再構築。§4.5）はここに一元化されており、
     * UI（AppState）の保存・外部変更検出もこの経路を通る。
     */
    fun noteDiskChanges(changed: Collection<Path>, removed: Collection<Path> = emptyList()) {
        val rels = (changed + removed).map { relPath(it) }
        // スキーマ本体・派生スキーマの導出元文書（R11-03）に加え、構成ファイル（.yde/workspace.yaml。R13-02）の
        // 変更も走査規則・検証ルールを変えるため全再構築する
        if (rels.any { rel ->
                layout.zoneOf(rel) == WorkspaceLayout.Zone.SCHEMAS ||
                    (layout.isReserved(rel) && layout.zoneOf(rel) == null) ||
                    schemas.any { s -> s.derive?.matchesSource(rel) == true }
            }
        ) {
            refresh()
            return
        }
        if (changed.any { it !in files } || removed.isNotEmpty()) files = WorkspaceScan.yamlFiles(root, layout)
        // 増分取り込み（RA-01 の noteStoreChanges）→ 派生を写す。セッション側が全再読込へ降格しても結果は同じ。
        // 取り込みに失敗した場合は refresh（セッション開き直し）で回復する
        val s = session ?: run { refresh(); return }
        runCatching {
            s.noteStoreChanges(
                changed = changed.map { model.DocumentId(relPath(it)) },
                removed = removed.map { model.DocumentId(relPath(it)) },
            )
        }.onFailure { refresh(); return }
        repopulateIndex()
    }

    /**
     * 未保存編集を重ねたオーバーレイ索引（UX-03）。overlays（ファイル → 編集中ツリー）の文書だけを
     * 走査し直した新しい索引を返す。ディスク索引（index）は変更しない。
     * UIが「見たままの状態」で検証・カバレッジ・ビュー行集合を判定するために使う（ARCHITECTURE §4.5）。
     */
    fun overlayIndex(overlays: Map<Path, YNode?>): IdIndex {
        if (overlays.isEmpty()) return index
        val s = session ?: error("セッションが開かれていません")
        // オーバーレイはカーネル派生（RA-01 derivedWith）で組む（null = 文書集合から外す＝従来と同じ）
        val kernelOverlays = overlays.entries.associate { (f, tree) ->
            val id = model.DocumentId(relPath(f))
            id to tree?.let { model.YDocument(id, listOf(it)) }
        }
        return derivedIdIndex(s.derivedWith(kernelOverlays).semantic.scans, root)
    }

    /** 1 ファイルを検証する（スキーマ適合・参照解決・重複・カバレッジ。台帳外は索引前提の判定を降格。R13-03）。 */
    fun validate(rel: String): List<Issue> = validateDetailed(rel).issues

    /** 1 ファイルの検証を、判定を保留した検査つきで返す（P27 段A。準備完了レポートと CLI が使う）。 */
    fun validateDetailed(rel: String): validate.ValidationResult {
        val doc = load(rel)
        // 走査対象（台帳＋予約領域）に載っていない文書＝台帳外のエディタ層。索引前提の判定は成立しない
        val indexed = doc.file in files
        return Validator.validateDetailed(
            doc.root, doc.parseError, doc.file, doc.schema, index,
            // （このヘッドレス経路はディスク索引・ディスク loader のまま。UIは overlayIndex を渡す。§4.5）
            indexed = indexed, workspace = root, allSchemas = schemas,
            relations = relations, enforcement = enforcement,
            // ディスクの索引とディスクの内容: 検査の評価を、他の文書の検証と共有する（全体の検証が文書数に比例する。P27 段B）
            shareEvaluations = true,
        )
    }

    /**
     * 変更セットを適用する（R1-02 / R2-05）。
     * - 全ファイルの競合チェックと操作適用を先に行い、1件でも失敗すれば何も書き込まない。
     * - create = true の変更は新規ファイル作成（既存ファイルがあれば競合）。
     * - 検証は「適用後の全ファイルを重ねたオーバーレイ索引」に対して行うため、
     *   ID改名と参照元修正のような複数ファイル連動の変更も正しく判定される。
     * - dryRun = true では書き込みを行わず、適用後の検証結果のみ返す。
     * - requireValid = true では、適用後に検証エラーが残る場合も書き込まない。
     */
    fun apply(changeSet: ChangeSet, dryRun: Boolean = false, requireValid: Boolean = false): ChangeSetResult =
        applyInternal(changeSet, dryRun, requireValid) { staged ->
            staged.forEach { s ->
                Files.createDirectories(s.file.parent)
                Files.writeString(s.file, YamlIO.dump(s.newRoot))
            }
            null
        }

    /**
     * 変更セット適用のセッション書込版（A-2b・D-04）: 競合判定・オーバーレイ検証（coverage 等の
     * 全Validator）は apply と完全に同一のまま、書込だけを WorkspaceSession（DocumentStore ポート＝
     * atomic 書込）へ集約する。適用は1トランザクション（CreateDocument / ReplaceDocument）で、
     * 履歴一元化（A-3）後は Undo 1回で全ファイルが戻る。
     */
    fun applyViaSession(
        session: workspace.WorkspaceSession,
        changeSet: ChangeSet,
        dryRun: Boolean = false,
        requireValid: Boolean = false,
    ): ChangeSetResult = applyInternal(changeSet, dryRun, requireValid) { staged ->
        val ops = staged.map { s ->
            val id = model.DocumentId(relPath(s.file))
            val doc = model.YDocument(id, listOf(s.newRoot))
            if (s.change.create) workspace.WorkspaceOperation.CreateDocument(id, doc)
            else workspace.WorkspaceOperation.ReplaceDocument(id, doc)
        }
        val applied = session.apply(
            workspace.WorkspaceTransaction(
                baseVersion = session.version,
                intent = workspace.ChangeIntent("変更セットの適用（${staged.size} ファイル）"),
                operations = ops,
            )
        )
        if (!applied.ok) applied.message ?: "セッションへの適用に失敗しました"
        else {
            val saved = session.save(staged.map { model.DocumentId(relPath(it.file)) }.toSet())
            if (saved.ok) null
            else saved.failures.entries.firstOrNull()?.let { (id, m) -> "${id.value}: $m" } ?: "書込に失敗しました"
        }
    }

    private data class Staged(val change: FileChange, val file: Path, val newRoot: YNode)

    /** 適用の本体（競合判定→オーバーレイ検証→writer による書込）。writer は失敗理由を返す（null = 成功）。 */
    private fun applyInternal(
        changeSet: ChangeSet,
        dryRun: Boolean,
        requireValid: Boolean,
        writer: (List<Staged>) -> String?,
    ): ChangeSetResult {
        val results = mutableListOf<FileChangeResult>()
        val staged = mutableListOf<Staged>()
        var failed = false

        for (change in changeSet.changes) {
            val outcome = runCatching {
                val file = resolve(change.file)
                if (change.create) {
                    require(!Files.exists(file)) { "競合: 既に同名のファイルが存在します: ${change.file}" }
                    Staged(change, file, change.content ?: error("createにはcontentが必要です"))
                } else {
                    require(Files.isRegularFile(file)) { "ファイルが存在しません: ${change.file}" }
                    val diskText = Files.readString(file)
                    if (change.baseHash != null && ChangeSets.contentHash(diskText) != change.baseHash) {
                        error("競合: ディスク内容が変更の前提（base）から変わっています")
                    }
                    val tree = YamlIO.parse(diskText)
                    change.ops.forEach { Patcher.apply(tree, it) }
                    Staged(change, file, tree)
                }
            }
            outcome.onSuccess { staged += it }.onFailure {
                failed = true
                results += FileChangeResult(change.file, applied = false, message = it.message, issues = emptyList())
            }
        }

        // 適用後の状態を重ねたオーバーレイ索引で検証する（書き込み前。供給はセッション派生＝RB-07 で一本化）
        val overlayRoots: Map<Path, YNode?> = staged.associate { it.file to it.newRoot }
        val applyOverlay = overlayIndex(overlayRoots)
        val validated = staged.map { s ->
            // 整合検証の材料もオーバーレイ（適用後の姿）で読む（索引と同じ基準を保つ。R16-03a）
            s to Validator.validate(
                s.newRoot, null, s.file, schemaFor(s.file), applyOverlay,
                workspace = root, allSchemas = schemas,
                loader = { f -> overlayRoots[f] ?: runCatching { YamlIO.parse(Files.readString(f)) }.getOrNull() },
                relations = relations, enforcement = enforcement,
                shareEvaluations = true,  // 適用後の姿を重ねた索引と、同じ姿を読む loader
            )
        }
        if (requireValid && validated.any { (_, issues) -> issues.any { it.severity == Severity.ERROR } }) {
            failed = true
        }

        val write = !dryRun && !failed
        val writeError = if (write) writer(validated.map { it.first }) else null
        validated.forEach { (s, issues) ->
            results += FileChangeResult(
                s.change.file,
                applied = write && writeError == null,
                message = writeError,
                issues = issues,
            )
        }
        if (write && writeError == null) refreshAfterWrite(validated.map { it.first.file })
        return ChangeSetResult(ok = !failed && writeError == null, dryRun = dryRun, results = results)
    }
}
