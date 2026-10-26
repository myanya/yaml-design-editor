package dev.toros.yde.app.session

import dev.toros.yde.app.core.EditBuffer
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.app.text.LineMerge
import dev.toros.yde.app.text.SourceHistorySync
import dev.toros.yde.yaml.model.YNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * 開いている 1 文書の論理（Compose を使わない。architecture/editing-history.md「部品」）。
 * root の木が正で、ソースの下書きは木の表現の 1 つ。下書きの文字列とカーソルは殻（app.OpenDocument の `TextFieldState`）が持ち、
 * ここは渡された文字列に対して解析・3 者併合・dirty の判定を行い、状態の変化を `onChanged` で殻へ知らせる（殻が Compose の状態へ写す）。
 * 複数ドキュメントの再出力と保存した内容との比較は core.EditBuffer へ委譲する。履歴（Undo / Redo）と dirty の正はカーネルのセッション（ADR-0006）。
 */
class DocumentState(
    val file: Path,
    root: YNode?,
    schema: SchemaDef?,
    initialText: String,
    parseError: String?,
    extraDocs: List<YNode> = emptyList(),
) {
    private companion object {
        val instances = AtomicLong()
        val sourceInputs = AtomicLong()
    }
    private val instance = instances.incrementAndGet()

    /** 文書の再生成でも古い無key辺の選択を引き継がない。 */
    val flowRevision: Long get() = (instance shl 32) + version
    private val buffer = EditBuffer(file, root, extraDocs, initialText, parseError)

    /** 状態の変化の聞き手（殻が Compose の状態へ写す）。 */
    var onChanged: (() -> Unit)? = null
    private fun changed() { onChanged?.invoke() }

    var schema: SchemaDef? = schema
        set(value) { field = value; changed() }

    var root: YNode?
        get() = buffer.root
        set(value) { buffer.root = value }

    /**
     * `---` 区切りの後続ドキュメント（複数ドキュメントYAML）。
     * フォーム・索引の編集対象は先頭ドキュメントのみで、後続は保持して保存時に再出力する。
     */
    var extraDocs: List<YNode>
        get() = buffer.extraDocs
        set(value) { buffer.extraDocs = value }

    var rawText: String
        get() = buffer.rawText
        set(value) { buffer.rawText = value }

    /** テキスト文書・構成ファイルの保存検査用。上書き許可で dirty の基準は動かさない。 */
    var expectedStoreText: String? = initialText

    /** 解析エラー（buffer が正）。 */
    var parseError: String?
        get() = buffer.parseError
        set(value) { buffer.parseError = value; changed() }

    /** 編集のたびにインクリメント。殻はこれを読んで再コンポーズを購読する。 */
    var version: Int = 0
        set(value) { field = value; changed() }

    /** 保存した内容から変わっているか。所有側（セッション）の判定を `dirtySource` で受け、`sync` のときだけ計算する。 */
    var dirty: Boolean = false
        private set

    var externalConflict: Boolean = false
        set(value) { field = value; changed() }

    var diskModifiedMillis: Long = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)

    /**
     * 編集通知: AppState が全文書横断の編集カウンタ（editVersion）を進め、セッション履歴へ記録するのに使う。
     * 引数は合体キー（null = 常に境界）。
     */
    var onEdited: ((Any?) -> Unit)? = null

    /**
     * dirty の供給元: セッション（履歴・dirty の単一所有。ADR-0006）が判定を返す。
     * null（セッションが文書として扱わないパス＝構成ファイル・開いた時点でパースできなかった文書など）のときだけ、
     * 保存した内容との比較（EditBuffer）で判定する。
     */
    var dirtySource: (() -> Boolean?)? = null

    private fun sync() {
        dirty = dirtySource?.invoke() ?: buffer.dirty
        changed()
    }

    /** 所有側（セッション）の状態変化後に dirty を再評価する（Undo・保存・履歴適用の後）。 */
    fun refreshDirty() = sync()

    /**
     * 編集を木へ記録する。coalesceWith（architecture/editing-history.md「履歴の合体」）: 連続入力になり得る編集面が
     * 渡す合体キー（同一スカラーノード・`Pair(map, key)`・ソースの固定キー等）。構造操作は渡さない＝常に境界。
     * 履歴の記録先はセッション（onEdited 経由）。
     */
    fun markEdited(coalesceWith: Any? = null) {
        version++
        onEdited?.invoke(coalesceWith)
        sync()
    }

    /** ソース編集のテキストをツリーへ取り込む。複数ドキュメントは先頭を編集対象にし、後続を保持する。 */
    fun applySourceText(text: String) {
        buffer.applySourceText(text)
        // ソースの連続入力は履歴1エントリへ合体する（文書単位の固定キー）
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

    // ================================================================ ソースの下書き（architecture/editing-history.md「ソースとの同期」）
    // 下書きの文字列（draft）とカーソルは殻が持ち、ここの関数は引数で受け取る。

    /** 下書きを最後に木へ反映した（または反映を試みた）文字列。これと違えば、まだ反映していない入力がある。 */
    var sourceApplied: String = buffer.canonicalText()
        private set(value) { field = value; changed() }

    /** 解析の世代。木との同期や下書きの破棄をまたいだ解析結果を採用しない。 */
    var sourceEpoch: Long = 0L
        private set(value) { field = value; changed() }

    private var observedSourceText = sourceApplied

    var sourceInputOrder: Long = 0
        private set

    fun hasPendingSourceInput(draft: String): Boolean = draft != sourceApplied

    /**
     * 今の下書きの写し。殻が入力・補完・差し込み・破棄のたびに知らせる文字列で、殻の TextFieldState と同じ内容になる
     * （殻は TextFieldState を直接書き換えず、必ず editSource か noteSourceInput を通す）。セッション側の判定はこれを使う。
     */
    val draft: String get() = observedSourceText

    /** まだ反映していない入力があるか（セッション側の判定用。殻は TextFieldState の文字列を渡す版を使う）。 */
    val hasPendingSourceInput: Boolean get() = hasPendingSourceInput(draft)

    /** 壊れた下書きが保存した内容と違うか（セッション側の判定用）。 */
    val draftDirty: Boolean get() = draftDirty(draft)

    /** 入力順だけを記録する。入力変換から呼んでも木・履歴・状態を変更しない。 */
    fun noteSourceInput(text: String) {
        if (text == observedSourceText) return
        observedSourceText = text
        sourceInputOrder = sourceInputs.incrementAndGet()
    }

    class SourceParseRequest(
        val text: String,
        val document: DocumentState,
        val version: Int,
        val epoch: Long,
        val inputOrder: Long,
    )

    fun sourceParseRequest(draft: String): SourceParseRequest? {
        if (!hasPendingSourceInput(draft)) return null
        noteSourceInput(draft)
        return SourceParseRequest(draft, this, version, sourceEpoch, sourceInputOrder)
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
    var sourceError: String? = parseError
        private set(value) { field = value; changed() }

    /** 下書きが解析できないか。フォームは閲覧専用になり、保存すると「壊れたまま保存」になる。 */
    val draftBroken: Boolean get() = sourceError != null

    /** 壊れた下書きが、保存した内容と違うか（壊れている間は dirty の代わりにこれが未保存の印）。 */
    fun draftDirty(draft: String): Boolean = sourceError != null && draft != rawText

    /** 壊れたまま保存した（または開いた時点で解析できなかった）テキスト文書か。索引・検証の対象外で、直して解析できれば戻る。 */
    var textOnly: Boolean = root == null
        set(value) { field = value; changed() }

    /** 最後に解析できた木（壊れたまま保存したあと「解析できた内容に戻す」ために残す）。 */
    private var lastParsed: Pair<YNode, List<YNode>>? = null

    /** 最後に解析できた木に戻せるか。 */
    val canRestoreLastParsed: Boolean get() = textOnly && lastParsed != null

    /**
     * 下書きの解析結果を木へ反映する。成功なら markEdited＝履歴・dirty・検証・索引が追随し、テキスト文書なら文書集合へ戻る。
     * 失敗なら文面を残すだけで木は触らない。draft は今の下書き（依頼のときから変わっていれば採用しない）。
     */
    fun applyParsed(request: SourceParseRequest, parsed: EditBuffer.Parsed, draft: String): Boolean {
        val text = request.text
        if (request.document !== this || request.version != version || request.epoch != sourceEpoch ||
            request.inputOrder != sourceInputOrder || text != draft || text == sourceApplied) return false
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
    fun flushDraft(draft: String) {
        val request = sourceParseRequest(draft) ?: return
        applyParsed(request, EditBuffer.parse(request.text), draft)
    }

    data class SourceSync(val conflicts: Int = 0, val normalized: Boolean = false)

    /** 同期の結果: 下書きへ書き込む文字列とカーソル（text が null なら書き込み不要）と、件数。 */
    class SyncOutcome(val text: String?, val caret: Int, val sync: SourceSync)

    /**
     * 他所（フォーム・Undo・操作・再読込）で木が変わったとき、下書きへ差し込む差分を決める。
     * 元＝前回の正規化テキスト・自分＝下書き・相手＝今回の正規化テキストの 3 者マージで、カーソルは同じ場所に留まる。
     * 履歴の直後（preferHistory）は取り込んだ入力の値を残さず、履歴の値と一致する書式だけを保つ。
     */
    fun syncSourceFromTree(draft: String, caret: Int, preferHistory: Boolean): SyncOutcome {
        if (version == sourceSyncedVersion && !preferHistory) return SyncOutcome(null, caret, SourceSync())
        sourceEpoch++
        val theirs = canonicalText()
        val ours = draft
        var conflicts = 0
        var normalized = false
        var newText: String? = null
        var newCaret = caret
        if (preferHistory && !hasPendingSourceInput(draft) && !draftBroken) {
            val result = SourceHistorySync.sync(sourceBase, ours, theirs, caret)
            normalized = result.normalized
            if (ours != result.text) { newText = result.text; newCaret = result.caret }
            sourceApplied = result.text
        } else if (ours != theirs) {
            val m = LineMerge.mergeHunks(LineMerge.splitLines(sourceBase), LineMerge.splitLines(ours), LineMerge.splitLines(theirs))
            conflicts = m.conflicts
            if (m.hunks.isNotEmpty()) {
                val r = LineMerge.applyToText(ours, m.hunks, caret)
                newText = r.text
                newCaret = r.caret
                sourceApplied = r.text
            }
        }
        sourceBase = theirs
        val current = newText ?: ours
        observedSourceText = current
        sourceSyncedVersion = version
        if (sourceError == null) sourceParsed = current
        else if (current == theirs) { sourceError = null; sourceParsed = theirs }
        return SyncOutcome(newText, newCaret, SourceSync(conflicts, normalized))
    }

    /** 下書きを捨てて木の正規化テキストに揃える（「編集を破棄」「整形」）。戻り値は下書きへ書く文字列とカーソル。テキスト文書では保存したテキストに戻る。 */
    fun discardDraft(caret: Int): Pair<String, Int> {
        sourceEpoch++
        val text = canonicalText()
        val newCaret = caret.coerceAtMost(text.length)
        sourceApplied = text
        observedSourceText = text
        sourceBase = text
        sourceSyncedVersion = version
        sourceError = if (buffer.root == null) buffer.parseError else null
        sourceParsed = if (buffer.root == null) "" else text
        return text to newCaret
    }

    /** 壊れた下書きをそのまま保存した直後の状態にする＝テキスト文書。最後に解析できた木は戻せるように残す。 */
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

    /** テキスト文書を、最後に解析できた木に戻す（未保存になる。保存すれば木の内容が書かれる）。殻は続けて下書きを木に揃える。 */
    fun restoreLastParsed(): Boolean {
        val (r, extra) = lastParsed ?: return false
        buffer.root = r
        buffer.extraDocs = extra
        buffer.parseError = null
        sourceError = null
        markEdited()
        textOnly = false
        return true
    }
}
