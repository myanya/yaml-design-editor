package dev.toros.yde.app.core

import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.app.schema.BuiltinSchemas
import dev.toros.yde.app.validate.ValidationResult
import dev.toros.yde.semantic.schema.EnforcementDef
import dev.toros.yde.semantic.schema.EntityTypeDef
import dev.toros.yde.semantic.schema.SchemaKind
import dev.toros.yde.workspace.FileSystemDocumentStore
import dev.toros.yde.workspace.WorkspaceSession
import dev.toros.yde.workspace.changeset.ChangeSet
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YDocument
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.app.schema.SchemaLoader
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.app.validate.Issue
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.yaml.validate.Severity
import dev.toros.yde.app.validate.Validator
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/** ワークスペースのファイル走査（UI・アプリ状態に依存しない共通処理）。 */
object WorkspaceScan {

    /**
     * 走査対象の *.yaml / *.yml を列挙する（隠しファイル・隠しディレクトリを除く。構成ディレクトリ `.yde` だけ例外）。
     * `ledger` 宣言があるレイアウトでは台帳＋構成ディレクトリのみを対象にする（architecture/workspace.md「ワークスペース構造と layout」）。
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

    /**
     * ワークスペース内の Markdown（*.md）を列挙する（表示用。索引・検証・エージェントの対象ではない）。
     * 隠しディレクトリは除く。README や設計メモを YAML と並べてツリーに出すため。
     */
    fun markdownFiles(ws: Path): List<Path> {
        val result = mutableListOf<Path>()
        walk(ws, WorkspaceLayout.LEGACY) { p ->
            if (!Files.isRegularFile(p)) return@walk
            if (p.fileName.toString().startsWith(".")) return@walk
            if (p.extension.lowercase() == "md") result.add(p)
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
    var relations: Map<String, dev.toros.yde.semantic.schema.RelationDef> = emptyMap()
        private set
    /** 強制スイッチ（R21-01。architecture/graph-query.md「エンティティ操作」）: レジストリ文書の enforcement 節の集約。 */
    var enforcement: EnforcementDef = EnforcementDef()
        private set
    /** エンティティ型レジストリ（R23-01。architecture/graph-query.md「エンティティページ」）: `entities:` 節の集約。表示名・アスペクト順序の解決に使う。 */
    var entityTypes: Map<String, EntityTypeDef> = emptyMap()
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
    private var session: WorkspaceSession? = null

    /** ディスクを映したカーネルのセッション（索引・変更セットの適用・リファクタリングの影響の解決の供給元）。 */
    internal val mirror: WorkspaceSession get() = session ?: error("セッションが開かれていません")

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
        val opened = WorkspaceSession(
            FileSystemDocumentStore(root),
            builtins = BuiltinSchemas.kernelSchemas(layout),
        )
        session = opened
        val loaded = SchemaLoader.fromKernel(root, opened.schemas, opened.declarations, opened.schemaErrors)
        schemas = loaded.schemas
        schemaErrors = loaded.errors
        relations = loaded.relations
        enforcement = loaded.enforcement
        entityTypes = loaded.entityTypes
        files = WorkspaceScan.yamlFiles(root, layout)
        repopulateIndex()
    }

    /** 索引を、セッションの意味解析の結果にする（写しを持たない。P27 段D の D-d・D-e）。 */
    private fun repopulateIndex() {
        val s = session ?: error("セッションが開かれていません")
        index.populate(s.derivedWith(emptyMap()).semantic)
    }

    fun relPath(file: Path): String = IdIndex.relPathOf(root, file)

    /** ファイルの文書ID（ワークスペース相対パス）。索引・検証・ビューの解決は、この座標で場所を表す。 */
    fun documentOf(file: Path): DocumentId = DocumentId(relPath(file))

    /** 文書ID のファイル。ファイルを開く・読むなど、Path が要る所でだけ使う。 */
    fun fileOf(document: DocumentId): Path = root.resolve(document.value).normalize()

    /** 文書ID の文書の中身（ディスクの内容。読めなければ null）。索引と同じ基準で文書を読む loader として渡す。 */
    fun treeOf(document: DocumentId): YNode? =
        runCatching { YamlIO.parseFirst(Files.readString(fileOf(document))) }.getOrNull()

    /** 相対パスを検証付きで解決する（ワークスペース外への脱出を拒否）。 */
    fun resolve(rel: String): Path {
        val resolved = root.resolve(rel).normalize()
        require(resolved.startsWith(root)) { "ワークスペース外のパスは扱えません: $rel" }
        return resolved
    }

    /** 文書ID の文書に当てるスキーマ。 */
    fun schemaFor(document: DocumentId): SchemaDef? = schemaFor(fileOf(document))

    fun schemaFor(file: Path): SchemaDef? {
        val rel = relPath(file)
        if (layout.zoneOf(rel) == WorkspaceLayout.Zone.SCHEMAS) {
            // schemas ゾーンにはメタスキーマ（R11-02）だけを適用する。一般スキーマの match は及ばない
            // （catch-all スキーマの誤適用を防ぐ。利用者は同IDのスキーマで上書きできる）。
            // kind: relations の文書には関係レジストリのメタスキーマ（R20-02。architecture/graph-query.md「エンティティ型と関係レジストリ」）を振り分ける
            val metaId = if (isRelationsDoc(file)) BuiltinSchemas.RELATIONS_SCHEMA_ID
            else BuiltinSchemas.META_SCHEMA_ID
            return schemas.firstOrNull { it.id == metaId && it.matches(rel) }
        }
        // ゾーン外の構成ファイル（.yde/workspace.yaml 等）にはスキーマを当てない（architecture/workspace.md「ワークスペース構造と layout」）
        if (layout.isReserved(rel) && layout.zoneOf(rel) == null) return null
        return schemas.firstOrNull { it.matches(rel) }
    }

    /** schemas ゾーンの文書が関係レジストリ（kind: relations）か（ディスク基準の軽量判定）。 */
    private fun isRelationsDoc(file: Path): Boolean = runCatching {
        val root = YamlIO.parseFirst(Files.readString(file)) as? YNode.Map
        scalarText(root?.entries?.get("kind")) == SchemaKind.RELATIONS.id
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
     * 増分か全再構築かの振り分け（スキーマ変更時のみ全再構築。architecture/index-references.md「索引の二層」）はここに一元化されており、
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
                changed = changed.map { DocumentId(relPath(it)) },
                removed = removed.map { DocumentId(relPath(it)) },
            )
        }.onFailure { refresh(); return }
        repopulateIndex()
    }

    /**
     * 未保存編集を重ねたオーバーレイ索引（UX-03）。overlays（ファイル → 編集中ツリー）の文書だけを
     * 走査し直した新しい索引を返す。ディスク索引（index）は変更しない。
     * UIが「見たままの状態」で検証・カバレッジ・ビュー行集合を判定するために使う（architecture/index-references.md「索引の二層」）。
     */
    fun overlayIndex(overlays: Map<Path, YNode?>): IdIndex {
        if (overlays.isEmpty()) return index
        val s = session ?: error("セッションが開かれていません")
        // オーバーレイはカーネル派生（RA-01 derivedWith）で組む（null = 文書集合から外す＝従来と同じ）
        val kernelOverlays = overlays.entries.associate { (f, tree) ->
            val id = DocumentId(relPath(f))
            id to tree?.let { YDocument(id, listOf(it)) }
        }
        return IdIndex.of(s.derivedWith(kernelOverlays).semantic)
    }

    /** 1 ファイルを検証する（スキーマ適合・参照解決・重複・カバレッジ。台帳外は索引前提の判定を降格。R13-03）。 */
    fun validate(rel: String): List<Issue> = validateDetailed(rel).issues

    /** 1 ファイルの検証を、判定を保留した検査つきで返す（P27 段A。準備完了レポートと CLI が使う）。 */
    fun validateDetailed(rel: String): ValidationResult {
        val doc = load(rel)
        // 走査対象（台帳＋予約領域）に載っていない文書＝台帳外のエディタ層。索引前提の判定は成立しない
        val indexed = doc.file in files
        return Validator.validateDetailed(
            doc.root, doc.parseError, documentOf(doc.file), doc.schema, index,
            // （このヘッドレス経路はディスク索引・ディスク loader のまま。UIは overlayIndex を渡す。architecture/index-references.md「索引の二層」）
            indexed = indexed, loader = ::treeOf, allSchemas = schemas,
            relations = relations, enforcement = enforcement,
            // ディスクの索引とディスクの内容: 検査の評価を、他の文書の検証と共有する（全体の検証が文書数に比例する。P27 段B）
            shareEvaluations = true,
        )
    }

    /**
     * 変更セットを適用する（R1-02 / R2-05）。適用の本体は、カーネルの workspace.ChangeSetApplier（P27 段D の D-b・意図的差分 D-35。
     * カーネル側の CLI `workspace apply` も同じ部品）で、ここは、結果をファイルの座標（Path）の形へ写すだけ。
     * - 全ファイルの競合チェックと操作適用を先に行い、1件でも失敗すれば何も書き込まない。
     * - create = true の変更は新規ファイル作成（既存ファイルがあれば競合）。
     * - 検証は「適用後の全ファイルを重ねた走査結果」に対して行うため、
     *   ID改名と参照元修正のような複数ファイル連動の変更も正しく判定される。
     * - dryRun = true では書き込みを行わず、適用後の検証結果のみ返す。
     * - requireValid = true では、適用後に検証エラーが残る場合も書き込まない。
     * - 書き込みは、ディスクを映した内部のセッションの1トランザクション＋保存（ストアの atomic な書き込み）。
     */
    fun apply(changeSet: ChangeSet, dryRun: Boolean = false, requireValid: Boolean = false): ChangeSetResult =
        applyVia(null, changeSet, dryRun, requireValid)

    /**
     * 変更セット適用のセッション書込版（A-2b・D-04）: 競合判定・適用後の姿の検証は apply と完全に同一のまま（基準はディスクの内容）、
     * 書込だけを、渡されたセッション（アプリの編集用のセッション）へ行う。適用は1トランザクション（CreateDocument / ReplaceDocument）で、
     * Undo 1回で全ファイルが戻る。
     */
    fun applyViaSession(
        session: WorkspaceSession,
        changeSet: ChangeSet,
        dryRun: Boolean = false,
        requireValid: Boolean = false,
    ): ChangeSetResult = applyVia(session, changeSet, dryRun, requireValid)

    private fun applyVia(
        target: WorkspaceSession?,
        changeSet: ChangeSet,
        dryRun: Boolean,
        requireValid: Boolean,
    ): ChangeSetResult {
        val base = session ?: error("セッションが開かれていません")
        val applied = dev.toros.yde.workspace.ChangeSetApplier.apply(base, changeSet, dryRun, requireValid, target = target ?: base)
        val results = applied.results.map { r ->
            FileChangeResult(r.file, r.applied, r.message, r.issues)
        }
        val written = applied.results.filter { it.applied }.map { resolve(it.file) }
        if (written.isNotEmpty()) refreshAfterWrite(written)
        return ChangeSetResult(applied.ok, applied.dryRun, results)
    }
}
