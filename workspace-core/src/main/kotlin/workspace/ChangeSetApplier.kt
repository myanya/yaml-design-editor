package workspace

import core.ChangeSet
import core.ChangeSets
import core.FileChange
import core.Patcher
import model.DocumentId
import model.YDocument
import model.YNode
import schema.WorkspaceLayout
import validate.CheckIssue
import validate.Severity
import yaml.YamlIO

/** 変更セットの適用の、文書1つ分の結果。 */
data class DocumentChangeResult(
    /** ワークスペース相対パス（変更セットに書かれたとおり）。 */
    val file: String,
    val applied: Boolean,
    /** 競合・操作のエラー・書き込みの失敗の内容（成功時は null）。 */
    val message: String?,
    /** 適用後（またはドライラン後）の姿の検証の結果。 */
    val issues: List<CheckIssue>,
)

/** 変更セットの適用の結果。 */
data class ChangeSetApplication(
    /** 全部の文書に適用（またはドライランに成功）できたか。 */
    val ok: Boolean,
    val dryRun: Boolean,
    val results: List<DocumentChangeResult>,
) {
    val errorIssueCount: Int get() = results.sumOf { r -> r.issues.count { it.severity == Severity.ERROR } }
}

/**
 * 変更セットの適用（R1-02 / R2-05。P27 段D の D-b で、root の Workspace.apply から移した。意図的差分 D-35）。
 * アプリ（root の Workspace は、結果をファイルの座標へ写すだけ）と、カーネル側の CLI（`workspace apply`）が共有する。
 * - 全部の文書の競合の判定と操作の適用を先に行い、1件でも失敗すれば何も書き込まない。
 * - create = true の変更は新しい文書の作成（既にあれば競合）。
 * - 検証は「適用後の全部の文書を重ねた走査結果」に対して行うため、ID の改名と参照元の修正のような、複数の文書が連動する変更も
 *   正しく判定される。検証の組み立ては、アプリの検証と同じ（validate.DocumentValidator）。
 * - dryRun = true では書き込まず、適用後の検証の結果だけを返す。
 * - requireValid = true では、適用後に検証のエラーが残る場合も書き込まない。
 * - 書き込みは、セッションの1つのトランザクション（CreateDocument / ReplaceDocument）＋保存（ストアの atomic な書き込み）。
 *   Undo 1回で全部の文書が戻る。
 */
object ChangeSetApplier {

    private class Staged(val change: FileChange, val id: DocumentId, val newRoot: YNode)

    /**
     * base は、競合の判定と検証の基準にするセッション（ストアの内容を映したもの）。target は、書き込み先のセッション
     * （省略時は base。アプリは、未保存の編集を持つ編集用のセッションを渡す——検証の基準は、今までどおりストアの内容）。
     */
    fun apply(
        base: WorkspaceSession,
        changeSet: ChangeSet,
        dryRun: Boolean = false,
        requireValid: Boolean = false,
        target: WorkspaceSession = base,
    ): ChangeSetApplication {
        val results = mutableListOf<DocumentChangeResult>()
        val staged = mutableListOf<Staged>()
        var failed = false

        for (change in changeSet.changes) {
            val outcome = runCatching {
                val id = DocumentId(normalize(change.file))
                if (change.create) {
                    require(base.storedText(id) == null) { "競合: 既に同名のファイルが存在します: ${change.file}" }
                    Staged(change, id, change.content ?: error("createにはcontentが必要です"))
                } else {
                    val stored = base.storedText(id) ?: error("ファイルが存在しません: ${change.file}")
                    if (change.baseHash != null && ChangeSets.contentHash(stored) != change.baseHash) {
                        error("競合: ディスク内容が変更の前提（base）から変わっています")
                    }
                    val tree = YamlIO.parse(stored)
                    change.ops.forEach { Patcher.apply(tree, it) }
                    Staged(change, id, tree)
                }
            }
            outcome.onSuccess { staged += it }.onFailure {
                failed = true
                results += DocumentChangeResult(change.file, applied = false, message = it.message, issues = emptyList())
            }
        }

        // 適用後の姿を重ねた走査結果の上で検証する（書き込みの前）。他の文書の中身も、適用後の姿で読む（索引と同じ基準を保つ。R16-03a）
        val overlays = staged.associate { it.id to YDocument(it.id, listOf(it.newRoot)) }
        val derived = base.derivedWith(overlays)
        val layout = base.layout
        val documents = LinkedHashMap(base.snapshot.documents)
        for ((id, document) in overlays) {
            documents[id] = WorkspaceDocument(
                document = document, persistedText = null,
                schemaId = schemaIdFor(id.value, document, base.schemas, layout), inLedger = layout.inLedger(id.value),
            )
        }
        val validations = WorkspaceCheckRunner.validateAll(
            base.schemas, base.declarations, layout, documents, derived,
            only = overlays.keys,
            // 変更セットの適用の検証は、今までどおり、どの文書も索引に載る文書として扱う
            indexedOf = { true },
        )
        val validated = staged.map { it to validations[it.id]?.issues.orEmpty() }
        if (requireValid && validated.any { (_, issues) -> issues.any { it.severity == Severity.ERROR } }) failed = true

        val write = !dryRun && !failed
        val writeError = if (write) write(target, staged) else null
        validated.forEach { (s, issues) ->
            results += DocumentChangeResult(s.change.file, applied = write && writeError == null, message = writeError, issues = issues)
        }
        return ChangeSetApplication(ok = !failed && writeError == null, dryRun = dryRun, results = results)
    }

    /** 1つのトランザクションとして適用して、保存する。失敗の理由を返す（null = 成功）。 */
    private fun write(target: WorkspaceSession, staged: List<Staged>): String? {
        if (staged.isEmpty()) return null
        val operations = staged.map { s ->
            val document = YDocument(s.id, listOf(s.newRoot))
            if (s.change.create) WorkspaceOperation.CreateDocument(s.id, document)
            else WorkspaceOperation.ReplaceDocument(s.id, document)
        }
        val applied = target.apply(
            WorkspaceTransaction(
                baseVersion = target.version,
                intent = ChangeIntent("変更セットの適用（${staged.size} ファイル）"),
                operations = operations,
            )
        )
        if (!applied.ok) return applied.message ?: "セッションへの適用に失敗しました"
        val saved = target.save(staged.map { it.id }.toSet())
        return if (saved.ok) null
        else saved.failures.entries.firstOrNull()?.let { (id, m) -> "${id.value}: $m" } ?: "書込に失敗しました"
    }

    /** 文書に当てるスキーマ。ゾーンの外の構成ファイル（`.yde/workspace.yaml` など）には当てない（ARCHITECTURE §4.25）。 */
    private fun schemaIdFor(rel: String, document: YDocument, schemas: List<WorkspaceSchema>, layout: WorkspaceLayout): String? =
        if (layout.isReserved(rel) && layout.zoneOf(rel) == null) null
        else WorkspaceReader.assignSchemaId(rel, document, schemas, layout)

    /** 相対パスを、区切り `/` の形に整える。ワークスペースの外へ出るパスは拒否する。 */
    private fun normalize(rel: String): String {
        val segments = ArrayList<String>()
        val text = rel.replace('\\', '/')
        require(!text.startsWith("/") && !Regex("^[A-Za-z]:").containsMatchIn(text)) { "ワークスペース外のパスは扱えません: $rel" }
        for (segment in text.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> require(segments.isNotEmpty()) { "ワークスペース外のパスは扱えません: $rel" }.also { segments.removeAt(segments.size - 1) }
                else -> segments += segment
            }
        }
        require(segments.isNotEmpty()) { "ワークスペース外のパスは扱えません: $rel" }
        return segments.joinToString("/")
    }
}
