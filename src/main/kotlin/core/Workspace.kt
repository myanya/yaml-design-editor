package core

import index.IdIndex
import model.YNode
import schema.SchemaDef
import schema.SchemaLoader
import validate.Issue
import validate.Severity
import validate.Validator
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/** ワークスペースのファイル走査（UI・アプリ状態に依存しない共通処理）。 */
object WorkspaceScan {

    /** 隠しファイルを除く *.yaml / *.yml を列挙する。 */
    fun yamlFiles(ws: Path): List<Path> {
        val result = mutableListOf<Path>()
        Files.walk(ws).use { stream ->
            stream.forEach { p ->
                if (!Files.isRegularFile(p)) return@forEach
                if (p.fileName.toString().startsWith(".")) return@forEach
                if (p.extension == "yaml" || p.extension == "yml") result.add(p)
            }
        }
        return result.sortedBy { it.toString() }
    }

    /** 隠しディレクトリを除くサブディレクトリを列挙する。 */
    fun directories(ws: Path): List<Path> {
        val result = mutableListOf<Path>()
        Files.walk(ws).use { stream ->
            stream.filter { it != ws && Files.isDirectory(it) }.forEach { p ->
                if (!p.fileName.toString().startsWith(".")) result.add(p)
            }
        }
        return result.sortedBy { it.toString() }
    }

    /** 外部変更検出に使う「ファイル -> 最終更新時刻」のスナップショット。 */
    fun stamps(ws: Path): Map<Path, Long> = buildMap {
        yamlFiles(ws).forEach { p ->
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
    var files: List<Path> = emptyList()
        private set
    val index = IdIndex()

    companion object {
        fun open(dir: Path): Workspace {
            require(Files.isDirectory(dir)) { "ワークスペースが存在しません: $dir" }
            return Workspace(dir.toAbsolutePath().normalize()).also { it.refresh() }
        }
    }

    /** スキーマ・ファイル一覧・索引をディスクから再構築する。 */
    fun refresh() {
        val loaded = SchemaLoader.loadAllWithErrors(root)
        schemas = loaded.schemas
        schemaErrors = loaded.errors
        files = WorkspaceScan.yamlFiles(root)
        index.rebuild(root, files, schemas)
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
        if (rel.startsWith(SchemaLoader.SCHEMA_DIR + "/")) return null
        return schemas.firstOrNull { it.matches(rel) }
    }

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
        refresh()
    }

    /** 1 ファイルを検証する（スキーマ適合・参照解決・重複・カバレッジ）。 */
    fun validate(rel: String): List<Issue> {
        val doc = load(rel)
        return Validator.validate(doc.root, doc.parseError, doc.file, doc.schema, index)
    }

    /**
     * 変更セットを適用する（R1-02）。
     * - 全ファイルの競合チェックと操作適用を先に行い、1件でも失敗すれば何も書き込まない。
     * - dryRun = true では書き込みを行わず、適用後の検証結果のみ返す。
     * - requireValid = true では、適用後に検証エラーが残る場合も書き込まない。
     */
    fun apply(changeSet: ChangeSet, dryRun: Boolean = false, requireValid: Boolean = false): ChangeSetResult {
        data class Staged(val change: FileChange, val file: Path, val newRoot: YNode)

        val results = mutableListOf<FileChangeResult>()
        val staged = mutableListOf<Staged>()
        var failed = false

        for (change in changeSet.changes) {
            val outcome = runCatching {
                val file = resolve(change.file)
                require(Files.isRegularFile(file)) { "ファイルが存在しません: ${change.file}" }
                val diskText = Files.readString(file)
                if (change.baseHash != null && ChangeSets.contentHash(diskText) != change.baseHash) {
                    error("競合: ディスク内容が変更の前提（base）から変わっています")
                }
                val tree = YamlIO.parse(diskText)
                change.ops.forEach { Patcher.apply(tree, it) }
                Staged(change, file, tree)
            }
            outcome.onSuccess { staged += it }.onFailure {
                failed = true
                results += FileChangeResult(change.file, applied = false, message = it.message, issues = emptyList())
            }
        }

        // 適用後の状態で検証（書き込み前）。索引は保存済み状態のままである点に注意。
        val validated = staged.map { s ->
            val schema = schemaFor(s.file)
            s to Validator.validate(s.newRoot, null, s.file, schema, index)
        }
        if (requireValid && validated.any { (_, issues) -> issues.any { it.severity == Severity.ERROR } }) {
            failed = true
        }

        val write = !dryRun && !failed
        validated.forEach { (s, issues) ->
            if (write) Files.writeString(s.file, YamlIO.dump(s.newRoot))
            results += FileChangeResult(s.change.file, applied = write, message = null, issues = issues)
        }
        if (write) refresh()
        return ChangeSetResult(ok = !failed, dryRun = dryRun, results = results)
    }
}
