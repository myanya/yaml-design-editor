package dev.toros.yde.desktop

import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.mutableStateOf
import dev.toros.yde.app.core.EditBuffer
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.app.session.DocumentState
import dev.toros.yde.yaml.model.YNode
import java.nio.file.Path

/**
 * 開いている 1 ドキュメントの Compose の殻。論理は session.DocumentState（Compose 非依存）にあり、
 * ここは `version` / `dirty` / `parseError` などを Compose の状態へ写して購読できるようにし、
 * ソースの下書きの文字列とカーソル（`TextFieldState`）を持つ。名前と窓口は論理と同じで、UI はこの殻だけを見る。
 * root の木が正で、フォーム編集は木を直接書き換え、markEdited() で version を進めて再コンポーズを駆動する。
 */
class OpenDocument(
    /** 論理（Compose 非依存）。セッション側の処理はこちらを使う。 */
    val state: DocumentState,
) {
    /** 単体の文書を作る（一時フォーム・プレビュー・テスト用。セッションに載せるものは DocumentSession が作った論理を包む）。 */
    constructor(
        file: Path,
        root: YNode?,
        schema: SchemaDef?,
        initialText: String,
        parseError: String?,
        extraDocs: List<YNode> = emptyList(),
    ) : this(DocumentState(file, root, schema, initialText, parseError, extraDocs))

    val file: Path get() = state.file

    /** 文書の再生成でも古い無key辺の選択を引き継がない。 */
    val flowRevision: Long get() = state.flowRevision

    // ---------------------------------------------------------------- Compose の写し（論理の onChanged で揃える）
    private val schemaState = mutableStateOf(state.schema)
    private val parseErrorState = mutableStateOf(state.parseError)
    private val versionState = mutableStateOf(state.version)
    private val dirtyState = mutableStateOf(state.dirty)
    private val externalConflictState = mutableStateOf(state.externalConflict)
    private val textOnlyState = mutableStateOf(state.textOnly)
    private val sourceAppliedState = mutableStateOf(state.sourceApplied)
    private val sourceEpochState = mutableStateOf(state.sourceEpoch)
    private val sourceErrorState = mutableStateOf(state.sourceError)

    init {
        state.onChanged = ::mirror
        state.draftCaret = { source.selection.start }
        state.onDraftReplaced = { text, caret -> source.edit { replace(0, length, text); placeCursorBeforeCharAt(caret) } }
    }

    private fun mirror() {
        schemaState.value = state.schema
        parseErrorState.value = state.parseError
        versionState.value = state.version
        dirtyState.value = state.dirty
        externalConflictState.value = state.externalConflict
        textOnlyState.value = state.textOnly
        sourceAppliedState.value = state.sourceApplied
        sourceEpochState.value = state.sourceEpoch
        sourceErrorState.value = state.sourceError
    }

    var schema: SchemaDef?
        get() = schemaState.value
        set(value) { state.schema = value }

    var root: YNode?
        get() = state.root
        set(value) { state.root = value }

    /** `---` 区切りの後続ドキュメント。フォーム・索引の編集対象は先頭ドキュメントのみで、後続は保持して保存時に再出力する。 */
    var extraDocs: List<YNode>
        get() = state.extraDocs
        set(value) { state.extraDocs = value }

    var rawText: String
        get() = state.rawText
        set(value) { state.rawText = value }

    /** テキスト文書・構成ファイルの保存検査用。上書き許可で dirty の基準は動かさない。 */
    internal var expectedStoreText: String?
        get() = state.expectedStoreText
        set(value) { state.expectedStoreText = value }

    var parseError: String?
        get() = parseErrorState.value
        set(value) { state.parseError = value }

    /** 編集のたびにインクリメント。木を表示するコンポーザブルはこれを読んで購読する。 */
    var version: Int
        get() = versionState.value
        set(value) { state.version = value }

    val dirty: Boolean get() = dirtyState.value

    var externalConflict: Boolean
        get() = externalConflictState.value
        set(value) { state.externalConflict = value }

    var diskModifiedMillis: Long
        get() = state.diskModifiedMillis
        set(value) { state.diskModifiedMillis = value }

    /** 編集通知（合体キーつき）。AppState が履歴の記録と editVersion に使う。 */
    internal var onEdited: ((Any?) -> Unit)?
        get() = state.onEdited
        set(value) { state.onEdited = value }

    /** dirty の供給元（セッションの判定）。 */
    internal var dirtySource: (() -> Boolean?)?
        get() = state.dirtySource
        set(value) { state.dirtySource = value }

    /** フォームで最後に編集したスカラー（ソースタブへ移ったときに同じ行へ行くため。パスは必要になったときに木から引く）。 */
    var lastEditedNode: YNode? = null

    /** 編集をツリーへ記録する。coalesceWith は合体キー（architecture/editing-history.md「履歴の合体」）。 */
    fun markEdited(coalesceWith: Any? = null) {
        if (coalesceWith is YNode) lastEditedNode = coalesceWith
        state.markEdited(coalesceWith)
    }

    /** ソース編集のテキストをツリーへ取り込む。 */
    fun applySourceText(text: String) = state.applySourceText(text)

    /** ディスク再読込などで内容が差し替わったとき、保存した内容（dirty の判定の基準）を取り直す。 */
    fun resetBaseline() = state.resetBaseline()

    fun markSaved() = state.markSaved()

    /** 所有側（セッション）の状態変化後に dirty 表示を再評価する。 */
    internal fun refreshDirty() = state.refreshDirty()

    /** 保存・ソース表示に使う正規化テキスト。パース不能なら元テキストを返す。 */
    fun canonicalText(): String = state.canonicalText()

    // ================================================================ ソースの下書き（文字列とカーソルは殻が持つ。計算は論理）

    /**
     * ソースタブの文字列とカーソル。画面の部品でなく文書が持つので、タブや文書を移っても残る。
     * 木が正で、下書きは木の表現の 1 つ。解析できたら木へ反映し（applyParsed）、他所で木が変わったら差分だけを差し込む（syncSourceFromTree）。
     */
    val source: TextFieldState = TextFieldState(state.canonicalText())

    /** 下書きを最後に木へ反映した（または反映を試みた）文字列。 */
    val sourceApplied: String get() = sourceAppliedState.value

    /** 解析の世代。 */
    internal val sourceEpoch: Long get() = sourceEpochState.value

    internal val sourceInputOrder: Long get() = state.sourceInputOrder

    val hasPendingSourceInput: Boolean get() = state.hasPendingSourceInput(source.text.toString())

    /** 入力順だけを記録する。入力変換から呼んでも木・履歴・Compose の状態を変更しない。 */
    internal fun noteSourceInput(text: String) = state.noteSourceInput(text)

    /** 補完・置換・行操作も、通常入力と同じ順序で取り込む。選択範囲だけの変更は数えない。 */
    fun editSource(block: TextFieldBuffer.() -> Unit) {
        source.edit(block)
        state.noteSourceInput(source.text.toString())
    }

    internal fun sourceParseRequest(): DocumentState.SourceParseRequest? = state.sourceParseRequest(source.text.toString())

    /** 前回木から作った正規化テキスト（3 者マージの元。「整形」の活性判定にも使う）。 */
    val sourceBase: String get() = state.sourceBase

    /** 最後に解析できた下書きの文字列。 */
    val sourceParsed: String get() = state.sourceParsed

    /** 下書きを木と同期した version。 */
    val sourceSyncedVersion: Int get() = state.sourceSyncedVersion

    /** 下書きが解析できないときの文面。null なら下書きは木と一致している。 */
    val sourceError: String? get() = sourceErrorState.value

    /** 下書きが解析できないか。フォームは閲覧専用になり、保存すると「壊れたまま保存」になる。 */
    val draftBroken: Boolean get() = sourceError != null

    /** 壊れた下書きが、保存した内容と違うか。 */
    val draftDirty: Boolean get() = state.draftDirty(source.text.toString())

    /** 壊れたまま保存した（または開いた時点で解析できなかった）テキスト文書か。 */
    var textOnly: Boolean
        get() = textOnlyState.value
        set(value) { state.textOnly = value }

    /** 最後に解析できた木に戻せるか。 */
    val canRestoreLastParsed: Boolean get() = state.canRestoreLastParsed

    /** 下書きの解析結果を木へ反映する（UI スレッドで呼ぶ）。 */
    internal fun applyParsed(request: DocumentState.SourceParseRequest, parsed: EditBuffer.Parsed): Boolean =
        state.applyParsed(request, parsed, source.text.toString())

    /** まだ反映していない入力をいま解析して反映する（保存・Undo・タブを離れるときに呼ぶ）。 */
    fun flushDraft() {
        val request = sourceParseRequest() ?: return
        applyParsed(request, EditBuffer.parse(request.text))
    }

    /** 他所で木が変わったとき、下書きへ差分だけを差し込む。カーソルは同じ場所に留まる（論理が計算し、聞き手で書き込む）。 */
    fun syncSourceFromTree(preferHistory: Boolean = false): DocumentState.SourceSync = state.syncSourceFromTree(preferHistory)

    /** 下書きを捨てて木の正規化テキストに揃える（「編集を破棄」「整形」）。 */
    fun discardDraft() = state.discardDraft()

    /** 壊れた下書きをそのまま保存した直後の状態にする＝テキスト文書。 */
    fun enterTextOnly(savedText: String) = state.enterTextOnly(savedText)

    /** テキスト文書を、最後に解析できた木に戻す（未保存になる）。 */
    fun restoreLastParsed(): Boolean = state.restoreLastParsed()
}
