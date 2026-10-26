package dev.toros.yde.app

import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.toros.yde.app.core.EditBuffer
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.app.text.LineMerge
import dev.toros.yde.app.text.SourceHistorySync
import dev.toros.yde.yaml.model.YNode
import java.nio.file.Files
import java.nio.file.Path

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
    companion object {
        private val instances = java.util.concurrent.atomic.AtomicLong()
        private val sourceInputs = java.util.concurrent.atomic.AtomicLong()
    }
    private val instance = instances.incrementAndGet()
    /** 文書の再生成でも古い無key辺の選択を引き継がない。 */
    val flowRevision: Long get() = (instance shl 32) + version
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

    /** テキスト文書・構成ファイルの保存検査用。上書き許可で dirty の基準は動かさない。 */
    internal var expectedStoreText: String? = initialText

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
     * 編集をツリーへ記録する。coalesceWith（UX-11。architecture/editing-history.md「履歴の合体」）: 連続入力になり得る編集面が
     * 渡す合体キー（同一スカラーノード・`Pair(map, key)`・ソースの固定キー等）。同じキーの連続編集は
     * 履歴1記録に合体する。構造操作は渡さない＝常に境界。
     * 履歴の記録先はセッション（onEdited 経由。RA-06）。
     */
    fun markEdited(coalesceWith: Any? = null) {
        version++
        if (coalesceWith is YNode) lastEditedNode = coalesceWith
        onEdited?.invoke(coalesceWith)
        sync()
    }

    /** フォームで最後に編集したスカラー（ソースタブへ移ったときに同じ行へ行くため。パスは必要になったときに木から引く）。 */
    var lastEditedNode: YNode? = null

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

    // ================================================================ ソースの下書き（P43 R43-01〜04。architecture/editing-history.md「ソースとの同期」）

    /**
     * ソースタブの文字列とカーソル。画面の部品でなく文書が持つので、タブや文書を移っても残る。
     * 木が正で、下書きは木の表現の 1 つ。解析できたら木へ反映し（applyParsed）、他所で木が変わったら差分だけを差し込む（syncSourceFromTree）。
     */
    val source: TextFieldState = TextFieldState(canonicalText())

    /** 下書きを最後に木へ反映した（または反映を試みた）文字列。これと違えば、まだ反映していない入力がある。 */
    var sourceApplied by mutableStateOf(source.text.toString())
        private set

    /** 解析の世代。木との同期や下書きの破棄をまたいだ解析結果を採用しない。 */
    internal var sourceEpoch by mutableStateOf(0L)
        private set
    private var observedSourceText = sourceApplied
    internal var sourceInputOrder: Long = 0
        private set
    val hasPendingSourceInput: Boolean get() = source.text.toString() != sourceApplied

    /** 入力順だけを記録する。入力変換から呼んでも木・履歴・Compose の状態を変更しない。 */
    internal fun noteSourceInput(text: String) {
        if (text == observedSourceText) return
        observedSourceText = text
        sourceInputOrder = sourceInputs.incrementAndGet()
    }

    /** 補完・置換・行操作も、通常入力と同じ順序で取り込む。選択範囲だけの変更は数えない。 */
    fun editSource(block: TextFieldBuffer.() -> Unit) {
        source.edit(block)
        noteSourceInput(source.text.toString())
    }

    internal class SourceParseRequest(
        val text: String,
        val document: OpenDocument,
        val version: Int,
        val epoch: Long,
        val inputOrder: Long,
    )

    internal fun sourceParseRequest(): SourceParseRequest? {
        if (!hasPendingSourceInput) return null
        val text = source.text.toString()
        noteSourceInput(text)
        return SourceParseRequest(text, this, version, sourceEpoch, sourceInputOrder)
    }

    /** 前回木から作った正規化テキスト（3 者マージの元。「整形」の活性判定にも使う）。 */
    var sourceBase: String = sourceApplied
        private set

    /** 最後に解析できた下書きの文字列（位置の表＝指摘の行・カーソルのパスの元。解析できない文書では空）。 */
    var sourceParsed: String = if (root == null) "" else sourceApplied
        private set

    /** 下書きを木と同期した version。version と違えば他所で木が変わった。 */
    var sourceSyncedVersion: Int = 0
        private set

    /** 下書きが解析できないときの文面（行・列つき）。null なら下書きは木と一致している。 */
    var sourceError by mutableStateOf<String?>(parseError)
        private set

    /** 下書きが解析できないか。フォームは閲覧専用になり、保存すると「壊れたまま保存」になる（R43-02・04）。 */
    val draftBroken: Boolean get() = sourceError != null

    /** 壊れた下書きが、保存した内容と違うか（壊れている間は dirty の代わりにこれが未保存の印）。 */
    val draftDirty: Boolean get() = sourceError != null && source.text.toString() != rawText

    /** 壊れたまま保存した（または開いた時点で解析できなかった）テキスト文書か。索引・検証の対象外で、直して解析できれば戻る（R43-04）。 */
    var textOnly by mutableStateOf(root == null)

    /** 最後に解析できた木（壊れたまま保存したあと「解析できた内容に戻す」ために残す）。 */
    private var lastParsed: Pair<YNode, List<YNode>>? = null

    /** 最後に解析できた木に戻せるか。 */
    val canRestoreLastParsed: Boolean get() = textOnly && lastParsed != null

    /**
     * 下書きの解析結果を木へ反映する（UI スレッドで呼ぶ）。成功なら markEdited＝履歴・dirty・検証・索引が追随し、
     * テキスト文書なら文書集合へ戻る。失敗なら文面を残すだけで木は触らない。
     */
    internal fun applyParsed(request: SourceParseRequest, parsed: EditBuffer.Parsed): Boolean {
        val text = request.text
        if (request.document !== this || request.version != version || request.epoch != sourceEpoch ||
            request.inputOrder != sourceInputOrder || text != source.text.toString() || text == sourceApplied) return false
        sourceEpoch++
        sourceApplied = text
        val newRoot = parsed.root
        if (newRoot == null) {
            sourceError = parsed.error ?: "解析できません"
            return true
        }
        buffer.root = newRoot
        buffer.extraDocs = parsed.extraDocs
        buffer.parseError = null
        sourceError = null
        sourceParsed = text
        // ソースの連続入力は履歴 1 件に合体する（文書単位の固定キー）
        markEdited(coalesceWith = "source")
        textOnly = false
        sourceBase = canonicalText()
        sourceSyncedVersion = version
        return true
    }

    /** まだ反映していない入力をいま解析して反映する（保存・Undo・タブを離れるときに呼ぶ）。 */
    fun flushDraft() {
        val request = sourceParseRequest() ?: return
        applyParsed(request, EditBuffer.parse(request.text))
    }

    data class SourceSync(val conflicts: Int = 0, val normalized: Boolean = false)

    /**
     * 他所（フォーム・Undo・操作・再読込）で木が変わったとき、下書きへ差分だけを差し込む（R43-03）。
     * 元＝前回の正規化テキスト・自分＝下書き・相手＝今回の正規化テキストの 3 者マージで、カーソルは同じ場所に留まる。
     * 履歴の直後は取り込んだ入力の値を残さず、履歴の値と一致する書式だけを保つ。
     */
    fun syncSourceFromTree(preferHistory: Boolean = false): SourceSync {
        if (version == sourceSyncedVersion && !preferHistory) return SourceSync()
        sourceEpoch++
        val theirs = canonicalText()
        val ours = source.text.toString()
        var conflicts = 0
        var normalized = false
        if (preferHistory && !hasPendingSourceInput && !draftBroken) {
            val result = SourceHistorySync.sync(sourceBase, ours, theirs, source.selection.start)
            normalized = result.normalized
            if (ours != result.text) source.edit {
                replace(0, length, result.text)
                placeCursorBeforeCharAt(result.caret)
            }
            sourceApplied = result.text
        } else if (ours != theirs) {
            val m = LineMerge.mergeHunks(LineMerge.splitLines(sourceBase), LineMerge.splitLines(ours), LineMerge.splitLines(theirs))
            conflicts = m.conflicts
            if (m.hunks.isNotEmpty()) {
                val r = LineMerge.applyToText(ours, m.hunks, source.selection.start)
                source.edit { replace(0, length, r.text); placeCursorBeforeCharAt(r.caret) }
                sourceApplied = r.text
            }
        }
        sourceBase = theirs
        observedSourceText = source.text.toString()
        sourceSyncedVersion = version
        if (sourceError == null) sourceParsed = source.text.toString()
        else if (source.text.toString() == theirs) { sourceError = null; sourceParsed = theirs }
        return SourceSync(conflicts, normalized)
    }

    /** 下書きを捨てて木の正規化テキストに揃える（「編集を破棄」「整形」）。テキスト文書では保存したテキストに戻る。 */
    fun discardDraft() {
        sourceEpoch++
        val text = canonicalText()
        val caret = source.selection.start.coerceAtMost(text.length)
        source.edit { replace(0, length, text); placeCursorBeforeCharAt(caret) }
        sourceApplied = text
        observedSourceText = text
        sourceBase = text
        sourceSyncedVersion = version
        sourceError = if (buffer.root == null) buffer.parseError else null
        sourceParsed = if (buffer.root == null) "" else text
    }

    /** 壊れた下書きをそのまま保存した直後の状態にする＝テキスト文書（R43-04）。最後に解析できた木は戻せるように残す。 */
    fun enterTextOnly(savedText: String) {
        sourceEpoch++
        buffer.root?.let { lastParsed = it to buffer.extraDocs }
        buffer.root = null
        buffer.extraDocs = emptyList()
        buffer.rawText = savedText
        buffer.parseError = sourceError
        textOnly = true
        sourceApplied = savedText
        sourceBase = savedText
        sourceParsed = ""
        version++
        sourceSyncedVersion = version
        markSaved()
    }

    /** テキスト文書を、最後に解析できた木に戻す（未保存になる。保存すれば木の内容が書かれる）。 */
    fun restoreLastParsed(): Boolean {
        val (r, extra) = lastParsed ?: return false
        buffer.root = r
        buffer.extraDocs = extra
        buffer.parseError = null
        sourceError = null
        markEdited()
        textOnly = false
        discardDraft()
        return true
    }
}
