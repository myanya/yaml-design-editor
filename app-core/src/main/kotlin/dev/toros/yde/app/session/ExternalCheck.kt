package dev.toros.yde.app.session

import dev.toros.yde.app.core.Workspace
import dev.toros.yde.app.core.WorkspaceScan
import dev.toros.yde.workspace.FileSystemDocumentStore
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Files
import java.nio.file.Path

/**
 * 外部変更の確認の材料。所有スレッドで `DocumentSession.prepareExternalCheck` が作り、
 * 一覧の走査・本文の照合・取り込みの候補の読込（[scan]）は IO 側で行う。結果は所有スレッドの `applyExternalCheck` が取り込む。
 * 背景へ渡すのは場所・更新時刻・不変の原文だけで、共有セッションや可変の文書は持たない。
 */
class ExternalCheck internal constructor(
    internal val root: Path,
    /** 確認を始めたときの更新時刻の印。 */
    internal val stamp: Map<Path, Long>,
    /** イベント起点のとき、更新時刻が変わらなくても本文を比べる文書の既知の原文。周期の確認では null。 */
    private val known: Map<DocumentId, String>?,
    /** 編集や履歴を持つか。持つなら、変化があったときに取り込みの検査に使う全体の候補も読む。 */
    private val retained: Boolean,
    /** 確認を始めたときの基準の世代と保存済みの原文。間に保存・取り込み・交換があれば結果を取り込まない。 */
    internal val generation: Long,
    internal val baseline: Map<DocumentId, String>,
) {
    /** IO 側で呼ぶ。読めない文書は変化として扱い、照合の失敗は「変化なし」にして次の知らせに任せる。 */
    fun scan(): ExternalScan {
        val layout = WorkspaceLayout.of(root)
        val next = WorkspaceScan.stamps(root, layout)
        val listed = next != stamp
        val expected = known.orEmpty()
        val contentChanged = if (listed || expected.isEmpty()) emptySet() else runCatching {
            val current = FileSystemDocumentStore(root).readAll(expected.keys)
            expected.keys.filterTo(linkedSetOf()) { current[it]?.text != expected[it] }
        }.getOrDefault(emptySet())
        val changed = listed || contentChanged.isNotEmpty()
        return ExternalScan(
            check = this,
            layout = layout,
            stamps = next,
            contentChanged = contentChanged,
            markdown = if (changed) WorkspaceScan.markdownFiles(root) else null,
            editorYaml = if (changed && layout.ledger.isNotEmpty()) WorkspaceScan.editorYamlFiles(root) else null,
            candidate = if (retained && changed) runCatching { loadReloadCandidate(root) } else null,
        )
    }
}

/** 再読込の候補を読む（ディスクだけを読み、セッションの状態に触れない）。 */
internal fun loadReloadCandidate(ws: Path): DocumentSession.ReloadCandidate {
    require(Files.isDirectory(ws)) { "ワークスペースのフォルダーがありません: $ws" }
    val cw = Workspace.open(ws)
    val listing = cw.loadedFiles
    val indexed = cw.files.toSet()
    val editorOnly = if (cw.layout.ledger.isEmpty()) emptyList() else listing.yamlTimes.keys
        .filter { id -> id.value.split('/').none { it.startsWith(".") } }
        .map { cw.root.resolve(it.value) }.filter { it !in indexed }
        .sortedBy { it.toString() }
    val stamps = cw.files.associateWith { listing.yamlTimes.getValue(cw.documentOf(it)) }
    val markdown = listing.markdown.map { cw.root.resolve(it.value) }.sortedBy { it.toString() }
    return DocumentSession.ReloadCandidate(cw, markdown, editorOnly, stamps)
}

/** 背景の確認の結果。所有スレッドで基準が変わっていないときだけ取り込む。 */
class ExternalScan internal constructor(
    internal val check: ExternalCheck,
    internal val layout: WorkspaceLayout,
    internal val stamps: Map<Path, Long>,
    /** 更新時刻が同じで本文が変わった文書（取り込む前に、いまの基準でもう一度比べる）。 */
    internal val contentChanged: Set<DocumentId>,
    /** 一覧が変わったときの Markdown と台帳外を含む YAML（所有スレッドで歩き直さないため）。 */
    internal val markdown: List<Path>?,
    internal val editorYaml: List<Path>?,
    /** 編集や履歴を持つときの再読込の候補（読込の失敗も結果として持つ）。 */
    internal val candidate: Result<DocumentSession.ReloadCandidate>?,
)
