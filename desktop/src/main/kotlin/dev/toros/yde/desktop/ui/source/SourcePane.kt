package dev.toros.yde.desktop.ui.source

import dev.toros.yde.app.text.LineMerge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.insert
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.toros.yde.app.core.EditBuffer
import dev.toros.yde.app.schema.fieldAt
import dev.toros.yde.app.validate.Issue
import dev.toros.yde.app.validate.Validator
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.yaml.SourcePositions
import dev.toros.yde.yaml.YamlPositions
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.pathOfNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.yaml.validate.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.OpenDocument
import dev.toros.yde.desktop.ui.common.AppFonts
import dev.toros.yde.desktop.ui.common.CompactTextField
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PanelShape

/**
 * YAML ソースを直接編集するペイン（architecture/editing-history.md「ソースとの同期」）。
 * 文字列・カーソル・解析エラーは文書（`OpenDocument.source` ほか）が持ち、この画面は表示と入力だけを担う＝
 * タブや文書を移っても書きかけは残る。
 * 入力が止まってから背景で解析し、成功したときだけ木へ反映する（木が正）。解析できない間は木＝最後に解析できた
 * 状態のままで、フォームはバナー＋閲覧専用になる。他所で木が変わったら差分だけを下書きへ差し込む。
 * 保存は壊れていても通り、その文書はテキスト文書になる。
 * 欄は Compose 標準の `TextFieldState` 版で、色分けは行ごとの覚え、行番号は欄の配置結果に揃える。
 * 基本操作の規則は `SourceEditing`、スキーマと索引を使う機能（指摘の印・補完・ホバー・Ctrl+クリック）は位置の表
 * （`YamlPositions`）とカーソルの文脈（`SourceContext`）、構造（折り畳み・保存済みとの差分の印・選択範囲の整形）は
 * `SourceStructure`。折り畳みは表示だけを変える（`OutputTransformation` で隠れた範囲を抜く）ので、文字列は変わらない。
 */
@Composable
fun SourcePane(state: AppState, doc: OpenDocument) {
    doc.version
    val readOnly = state.readOnlyMode
    val wrap = state.memory.sourceWrap
    val ui = remember(doc) { SourceUi() }
    val vScroll = remember(doc) { ScrollState(0) }

    // 他所（フォーム・Undo・操作・再読込）の変更を下書きへ差し込む。自分の反映は syncedVersion を進めているので対象外
    LaunchedEffect(doc, doc.version) {
        if (doc.version != doc.sourceSyncedVersion) {
            val conflicts = doc.syncSourceFromTree().conflicts
            if (conflicts > 0) state.status = "下書きと食い違う変更が $conflicts か所あり、下書きを優先しました（「整形」で木に揃えられます）"
        }
    }
    // 入力が止まってから解析して反映: 解析は背景、木の更新は UI 側（スナップショット状態の更新）
    LaunchedEffect(doc) {
        snapshotFlow { Triple(doc.source.text.toString(), doc.version, doc.sourceEpoch) }.collectLatest {
            val request = doc.sourceParseRequest() ?: return@collectLatest
            delay(APPLY_DELAY_MS)
            val parsed = withContext(Dispatchers.Default) { EditBuffer.parse(request.text) }
            doc.applyParsed(request, parsed)
        }
    }

    // 検索の一致（文字列・検索語・条件が変わるたびに引き直す。1 万行でも数 ms）
    val text = doc.source.text.toString()
    val matches = remember(text, ui.query, ui.regex, ui.caseSensitive) {
        SourceEditing.matches(text, ui.query, ui.regex, ui.caseSensitive)
    }
    // 位置の表（最後に解析できた文字列から。指摘の行・ホバーのパス・Ctrl+クリック・折り畳みの鍵に使う）
    val positions = remember(doc.sourceParsed) { if (doc.sourceParsed.isEmpty()) SourcePositions.EMPTY else YamlPositions.of(doc.sourceParsed) }
    val latestPositions by rememberUpdatedState(positions)

    // 折り畳み（覚えは見出しのキーのパス＝行がずれても追随。行は位置の表から引く）
    val documentId = state.documentOf(doc.file)
    val foldedPaths: Set<YPath> = state.memory.sourceFolds[documentId].orEmpty()
    val foldedHeaders = remember(foldedPaths, positions) { foldedPaths.mapNotNull { positions.lineOf(it) }.toSet() }
    val foldView = remember(text, foldedHeaders) { if (foldedHeaders.isEmpty()) null else SourceStructure.foldView(text, foldedHeaders) }
    fun setFolds(paths: Set<YPath>) {
        if (paths.isEmpty()) state.memory.sourceFolds.remove(documentId) else state.memory.sourceFolds[documentId] = paths
    }
    fun toggleFold(line: Int) {
        val path = positions.keyAtLine(line) ?: run { state.status = "この行は畳めません（解析できた見出しの行だけ畳めます）"; return }
        // パスはそれ自体がリストなので、要素として足し引きするには集合に包む
        setFolds(if (path in foldedPaths) foldedPaths - setOf(path) else foldedPaths + setOf(path))
    }
    /** 行が畳まれて隠れていれば、その区画を広げる（検索・ジャンプで行を見せる前に）。 */
    fun unfoldAt(line: Int) {
        val view = foldView ?: return
        val starts = LineMerge.lineStarts(text)
        val offset = starts.getOrElse(line) { text.length }
        val hidden = view.hidden.firstOrNull { offset > it.headerEnd && offset < it.to } ?: return
        val header = SourceEditing.lineOf(starts, hidden.headerEnd)
        positions.keyAtLine(header)?.let { setFolds(foldedPaths - setOf(it)) }
    }
    // 畳んだ区画の中にカーソルが入ってしまったら、見出しの末尾へ寄せる
    LaunchedEffect(foldView) {
        val view = foldView ?: return@LaunchedEffect
        val caret = doc.source.selection.start
        view.hidden.firstOrNull { caret > it.headerEnd && caret < it.to }?.let { h ->
            doc.editSource { selection = TextRange(h.headerEnd) }
        }
    }

    fun select(index: Int) {
        val m = matches?.getOrNull(index) ?: return
        ui.current = index
        unfoldAt(SourceEditing.lineOf(LineMerge.lineStarts(text), m.first))
        doc.editSource { selection = TextRange(m.first, m.last + 1) }
        ui.revealRequest++
    }
    fun findNext() {
        val ms = matches ?: return
        val from = ms.getOrNull(ui.current)?.let { it.last + 1 } ?: doc.source.selection.min
        select(SourceEditing.nextMatch(ms, from))
    }
    fun findPrev() {
        val ms = matches ?: return
        val before = ms.getOrNull(ui.current)?.first ?: doc.source.selection.min
        select(SourceEditing.prevMatch(ms, before))
    }
    // 検索語や条件が変わったら、カーソル以降の最初の一致へ。文字列の変化（置換・入力）では現在の位置から取り直す
    LaunchedEffect(ui.query, ui.regex, ui.caseSensitive) {
        val ms = matches
        if (ms != null && ms.isNotEmpty() && ui.query.isNotEmpty()) select(SourceEditing.nextMatch(ms, doc.source.selection.min))
        else ui.current = -1
    }
    LaunchedEffect(matches) {
        val ms = matches ?: run { ui.current = -1; return@LaunchedEffect }
        val next = SourceEditing.nextMatch(ms, doc.source.selection.min)
        if (ui.followNext) { ui.followNext = false; select(next) } else ui.current = next
    }
    fun replaceCurrent() {
        val ms = matches ?: return
        val m = ms.getOrNull(ui.current) ?: return
        val r = SourceEditing.replaceMatch(text, m, ui.query, ui.replaceWith, ui.regex, ui.caseSensitive)
        ui.followNext = true
        doc.editSource { replace(m.first, m.last + 1, r.text.substring(r.start, r.end)); selection = TextRange(r.end) }
    }
    fun replaceAll() {
        val (replaced, count) = SourceEditing.replaceAll(text, ui.query, ui.replaceWith, ui.regex, ui.caseSensitive)
        if (count > 0) {
            val caret = doc.source.selection.start.coerceAtMost(replaced.length)
            doc.editSource { replace(0, length, replaced); placeCursorBeforeCharAt(caret) }
        }
        state.status = if (count > 0) "$count 件置換しました（自動で反映されます）" else "「${ui.query}」は見つかりません"
    }
    fun gotoLine(line: Int) {
        val starts = LineMerge.lineStarts(text)
        val index = (line - 1).coerceIn(0, maxOf(starts.size - 2, 0))
        unfoldAt(index)
        doc.editSource { selection = TextRange(starts[index]) }
        ui.revealRequest++
        ui.editorFocus.requestFocus()
    }
    fun formatSelection() {
        val sel = doc.source.selection
        val r = SourceStructure.formatSelection(text, sel.min, sel.max)
        if (r == null) { state.status = "選択範囲が YAML の塊になっていません（途中で切れているか、解析できません）"; return }
        doc.editSource { replace(0, length, r.text); selection = TextRange(r.start, r.end) }
    }

    // この文書の検証の指摘（右パネルと同じ材料。パスのある指摘だけ）
    val issues = remember(doc, doc.version, state.indexVersion, state.editVersion) {
        val root = doc.root ?: return@remember emptyList<Issue>()
        Validator.validate(
            root, doc.parseError, state.documentOf(doc.file), doc.schema, state.overlayIndex(),
            indexed = state.isIndexed(doc.file), allSchemas = state.schemas,
            loader = state.unsavedFirstTree(), relations = state.relations, enforcement = state.enforcement,
            files = state.filePolicy, fileExists = state::fileExists,
        ).filter { it.path.isNotEmpty() && it.severity != Severity.INFO }
    }
    val issueLines = remember(issues, positions) {
        issues.groupBy { positions.lineOf(it.path) ?: -1 }.filterKeys { it >= 0 }
    }
    // 保存済み（ディスクの内容）との差分の印
    val diffMarks = remember(text, doc.rawText) { SourceStructure.diffMarks(doc.rawText, text) }
    // 指摘のジャンプ（右パネルの指摘・参照のクリック）: ソースを見ているときは行へ
    LaunchedEffect(state.highlightPath, positions) {
        val path = state.highlightPath ?: return@LaunchedEffect
        ui.formSyncPending = false
        val line = positions.lineOf(path) ?: return@LaunchedEffect
        val starts = LineMerge.lineStarts(text)
        if (line < starts.size - 1) {
            unfoldAt(line)
            doc.editSource { selection = TextRange(starts[line]) }
            ui.revealRequest++
        }
        state.highlightPath = null
    }
    // フォームで最後に編集した項目の行へ（タブを移ってきたとき 1 回。木と下書きが揃ってから）
    LaunchedEffect(positions, doc.version) {
        if (!ui.formSyncPending || doc.version != doc.sourceSyncedVersion || positions.isEmpty) return@LaunchedEffect
        ui.formSyncPending = false
        val node = doc.lastEditedNode ?: return@LaunchedEffect
        val root = doc.root ?: return@LaunchedEffect
        val path = pathOfNode(root, node) ?: return@LaunchedEffect
        val line = positions.lineOf(path) ?: return@LaunchedEffect
        val starts = LineMerge.lineStarts(text)
        if (line < starts.size - 1) {
            unfoldAt(line)
            doc.editSource { selection = TextRange(starts[line]) }
            ui.revealRequest++
        }
        doc.lastEditedNode = null
    }
    // タブや文書を離れるときは、入力待ちの分を取り込み、フォームへ移るならカーソルの項目を伝える（フォームがそこへ寄る）
    DisposableEffect(doc) {
        onDispose {
            doc.flushDraft()
            if (state.openDoc === doc && state.editorTab != 1 && state.highlightPath == null) {
                val parsed = doc.sourceParsed
                val caret = doc.source.selection.start
                if (parsed.isNotEmpty() && caret <= parsed.length) {
                    val starts = LineMerge.lineStarts(parsed)
                    val line = SourceEditing.lineOf(starts, caret)
                    latestPositions.pathAt(line, caret - starts[line])?.let { state.highlightPath = it }
                }
                doc.lastEditedNode = null
            }
        }
    }
    // 補完の候補（スキーマ・索引・ワークスペースのファイル）
    val workspaceFiles = remember(state.workspace, state.indexVersion) { state.workspace?.let(SourceCompletion::listWorkspaceFiles).orEmpty() }
    fun openCompletion() {
        val ctx = SourceContext.at(text, doc.source.selection.min)
        val candidates = SourceCompletion.candidates(state, doc, ctx, workspaceFiles)
        if (candidates.isEmpty()) {
            state.status = when (ctx.mode) {
                SourceContext.Mode.NONE -> "ここでは補完できません"
                else -> "候補がありません" + (if (doc.schema == null) "（スキーマの無い文書）" else "")
            }
            ui.completion = null
        } else {
            ui.completion = CompletionState(ctx.replaceStart, candidates)
        }
    }
    // 入力が進んだら候補を絞る。打ちかけの語の外へ出たら閉じる
    LaunchedEffect(text, doc.source.selection) {
        val c = ui.completion ?: return@LaunchedEffect
        val caret = doc.source.selection.min
        if (caret < c.replaceStart || !doc.source.selection.collapsed) { ui.completion = null; return@LaunchedEffect }
        val prefix = text.substring(c.replaceStart, caret)
        if (prefix.any { it == '\n' || it == ' ' }) { ui.completion = null; return@LaunchedEffect }
        c.prefix = prefix
        if (c.visible.isEmpty()) ui.completion = null
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp)
                .horizontalScroll(remember(doc) { ScrollState(0) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val differs = text != doc.sourceBase
            TextButton(enabled = !readOnly && differs, onClick = { doc.discardDraft() }) {
                Text(
                    when {
                        doc.textOnly -> "編集を破棄（保存した内容に戻す）"
                        doc.draftBroken -> "編集を破棄（解析できた状態に戻す）"
                        else -> "整形（正規化テキストに揃える）"
                    },
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            if (!readOnly && !doc.source.selection.collapsed) {
                TextButton(onClick = ::formatSelection) { Text("選択範囲を整形", fontSize = 12.sp) }
            }
            Spacer(Modifier.width(12.dp))
            if (issues.isNotEmpty()) {
                val errors = issues.count { it.severity == Severity.ERROR }
                Text(
                    "指摘: エラー $errors・警告 ${issues.size - errors}（左端の印で確認）",
                    fontSize = 11.sp, color = Palette.SubText, maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            if (foldedPaths.isNotEmpty()) {
                TextButton(onClick = { setFolds(emptySet()) }) { Text("すべて広げる", fontSize = 11.sp, color = Palette.SubText) }
            }
            TextButton(onClick = { state.memory.sourceWrap = !wrap }) {
                Text(if (wrap) "折り返し: あり" else "折り返し: なし", fontSize = 11.sp, color = Palette.SubText)
            }
        }
        SearchReplaceBar(
            ui, matches, readOnly,
            onNext = ::findNext, onPrev = ::findPrev, onReplace = ::replaceCurrent, onReplaceAll = ::replaceAll, onGoto = ::gotoLine,
        )
        key(doc) {
            SourceSyntaxBar(doc, onGotoLine = ::gotoLine, onReturnToEditor = { ui.editorFocus.requestFocus() })
        }
        SourceEditor(
            doc, readOnly, wrap, state, ui, vScroll, matches, positions, issueLines, diffMarks, foldView, foldedHeaders,
            onNext = ::findNext, onPrev = ::findPrev, onComplete = ::openCompletion, onToggleFold = ::toggleFold,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}

/** 入力が止まってから木へ反映するまでの待ち。 */
private const val APPLY_DELAY_MS = 300L

/** 画面の中だけの状態（操作の途中）: 検索・置換の入力と現在の一致、焦点、見せたい位置の要求、補完。文書ごとに作り直す。 */
private class SourceUi {
    var query by mutableStateOf("")
    var replaceWith by mutableStateOf("")
    var caseSensitive by mutableStateOf(false)
    var regex by mutableStateOf(false)
    /** 現在の一致（matches の添字。無ければ -1）。 */
    var current by mutableStateOf(-1)
    var gotoLine by mutableStateOf("")
    /** 増やすと、欄が選択範囲の行を見える位置へスクロールする。 */
    var revealRequest by mutableStateOf(0)
    /** 置換の直後: 文字列が変わって一致を引き直したら、次の一致を選ぶ。 */
    var followNext = false
    /** フォームから移ってきた直後: 最後に編集した項目の行へ寄せる（1 回だけ）。 */
    var formSyncPending = true
    /** 開いている補完（null = 閉じている）。 */
    var completion by mutableStateOf<CompletionState?>(null)
    val editorFocus = FocusRequester()
    val searchFocus = FocusRequester()
    val replaceFocus = FocusRequester()
    val gotoFocus = FocusRequester()
}

/** 開いている補完: 置き換え始める位置と候補の全体。打ちかけの語（prefix）で絞り、selected が選択。 */
private class CompletionState(val replaceStart: Int, val all: List<Candidate>) {
    var prefix by mutableStateOf("")
    var selected by mutableStateOf(0)
    val visible: List<Candidate>
        get() = all.filter { it.label.contains(prefix, ignoreCase = true) }
            .sortedBy { if (it.label.startsWith(prefix, ignoreCase = true)) 0 else 1 }
}

private val editorTextStyle: TextStyle
    @Composable get() = TextStyle(fontFamily = AppFonts.Mono, fontSize = 13.sp, lineHeight = 19.sp, color = Palette.Text)

/** ホバーで出す項目の説明。 */
private data class HoverInfo(val path: String, val title: String, val detail: String, val x: Int, val y: Int)

/** 本文の欄（行番号と印・折り返しと横スクロール・色分けと強調・現在行・折り畳み・キー操作・補完・ホバー・Ctrl+クリック）。 */
@Composable
private fun SourceEditor(
    doc: OpenDocument,
    readOnly: Boolean,
    wrap: Boolean,
    state: AppState,
    ui: SourceUi,
    vScroll: ScrollState,
    matches: List<IntRange>?,
    positions: SourcePositions,
    issueLines: Map<Int, List<Issue>>,
    diffMarks: Map<Int, SourceStructure.DiffMark>,
    foldView: SourceStructure.FoldView?,
    foldedHeaders: Set<Int>,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onComplete: () -> Unit,
    onToggleFold: (Int) -> Unit,
    modifier: Modifier,
) {
    val hScroll = remember(doc) { ScrollState(0) }
    var layoutProvider by remember(doc) { mutableStateOf<(() -> TextLayoutResult?)?>(null) }
    val textStyle = editorTextStyle
    val highlighter = remember(doc) { YamlHighlighter() }
    val text = doc.source.text.toString()
    // 折り畳みがあると、欄の配置は「表示の文字列」のもの。元の位置と表示の位置を行き来する
    val toDisplay: (Int) -> Int = { foldView?.displayOffset(it) ?: it }
    val toOriginal: (Int) -> Int = { foldView?.originalOffset(it) ?: it }
    // 色分けと一致・指摘の強調: 表示だけを変え、文字列には触らない。色は描画時に読む（テーマ切替でも追随）
    val keyColor = Palette.Primary
    val stringColor = Palette.OkGreen
    val numberColor = Palette.Secondary
    val keywordColor = Palette.WarnOrange
    val subColor = Palette.SubText
    val hitColor = Palette.Highlight
    val currentHitColor = Palette.WarnOrange.copy(alpha = 0.45f)
    val errorTint = Palette.ErrorRed.copy(alpha = 0.16f)
    val warnTint = Palette.WarnOrange.copy(alpha = 0.18f)
    val current = ui.current
    // 指摘の範囲（文字位置）。位置の表は最後に解析できた文字列のものなので、今の文字列が同じときだけ当てる
    val issueSpans = remember(issueLines, positions, text, doc.sourceParsed) {
        if (text != doc.sourceParsed) emptyList() else {
            val starts = LineMerge.lineStarts(text)
            issueLines.values.flatten().mapNotNull { issue ->
                val span = positions.values[issue.path] ?: return@mapNotNull null
                if (span.endLine >= starts.size - 1) return@mapNotNull null
                val from = starts[span.startLine] + span.startColumn
                val to = (starts[span.endLine] + span.endColumn).coerceAtMost(text.length)
                if (from < to) (from until to) to issue.severity else null
            }
        }
    }
    val transformation = remember(doc, keyColor, stringColor, numberColor, keywordColor, subColor, matches, current, issueSpans, foldView) {
        OutputTransformation {
            for (t in highlighter.tokens(asCharSequence())) {
                val end = t.end.coerceAtMost(length)
                if (t.start >= end) continue
                val style = when (t.type) {
                    YamlTokenType.KEY -> SpanStyle(color = keyColor)
                    YamlTokenType.STRING -> SpanStyle(color = stringColor)
                    YamlTokenType.NUMBER -> SpanStyle(color = numberColor)
                    YamlTokenType.KEYWORD -> SpanStyle(color = keywordColor)
                    YamlTokenType.COMMENT -> SpanStyle(color = subColor, fontStyle = FontStyle.Italic)
                    YamlTokenType.INDICATOR -> SpanStyle(color = subColor)
                }
                addStyle(style, t.start, end)
            }
            for ((range, severity) in issueSpans) {
                val end = (range.last + 1).coerceAtMost(length)
                if (range.first < end) addStyle(SpanStyle(background = if (severity == Severity.ERROR) errorTint else warnTint), range.first, end)
            }
            matches?.forEachIndexed { i, m ->
                val end = (m.last + 1).coerceAtMost(length)
                if (m.first < end) addStyle(SpanStyle(background = if (i == current) currentHitColor else hitColor), m.first, end)
            }
            // 折り畳み: 隠れた範囲を抜き、見出しの末尾に印を添える（後ろから当てると前の位置がずれない）
            foldView?.hidden?.asReversed()?.forEach { h ->
                val to = h.to.coerceAtMost(length)
                val from = h.from.coerceAtMost(to)
                replace(from, to, "")
                insert(h.headerEnd.coerceIn(0, length), h.placeholder)
            }
        }
    }
    val textPadding = 12.dp
    val currentLineColor = Palette.Selected.copy(alpha = 0.45f)
    BoxWithConstraints(modifier.background(Palette.EditorBg)) {
        val lineStarts = remember(doc.source.text) { LineMerge.lineStarts(text) }
        val lineCount = lineStarts.size - 1
        val displayLineStarts = foldView?.displayLineStarts ?: lineStarts
        val originalLineOf: (Int) -> Int = { d -> foldView?.originalLine(d) ?: d }
        val foldable = remember(text) { SourceStructure.foldableRegions(text.split('\n')).map { it.header }.toSet() }
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val gutterStyle = textStyle.copy(color = Palette.SubText)
        val gutterWidth = remember(lineCount, gutterStyle) {
            val digits = lineCount.toString().length.coerceAtLeast(2)
            with(density) { (measurer.measure("0".repeat(digits), gutterStyle).size.width.toDp()) } + 34.dp
        }
        val viewportWidth = maxWidth - gutterWidth
        val viewportHeightPx = with(density) { (maxHeight - textPadding * 2).toPx() }
        val textPaddingPx = with(density) { textPadding.toPx() }
        // 検索の一致・行ジャンプで、選択範囲の行が見えるところまでスクロールする
        LaunchedEffect(ui.revealRequest) {
            if (ui.revealRequest == 0) return@LaunchedEffect
            val layout = layoutProvider?.invoke() ?: return@LaunchedEffect
            val line = layout.getLineForOffset(toDisplay(doc.source.selection.start).coerceAtMost(layout.layoutInput.text.length))
            val top = layout.getLineTop(line)
            val bottom = layout.getLineBottom(line)
            if (top < vScroll.value || bottom > vScroll.value + viewportHeightPx) {
                vScroll.scrollTo((top - viewportHeightPx / 3).coerceAtLeast(0f).toInt())
            }
        }
        val currentLine = SourceEditing.lineOf(lineStarts, doc.source.selection.start)
        // 行番号の上のホバー（指摘の内容・折り畳みの印）とクリック（折り畳み）
        var gutterHoverY by remember { mutableStateOf<Float?>(null) }
        fun originalLineAtY(y: Float): Int? {
            val layout = layoutProvider?.invoke() ?: return null
            if (layout.lineCount == 0) return null
            val visual = layout.getLineForVerticalPosition(y - textPaddingPx + vScroll.value)
            return originalLineOf(SourceEditing.lineOf(displayLineStarts, layout.getLineStart(visual)))
        }
        val hoveredIssueLine = remember(gutterHoverY, issueLines) {
            gutterHoverY?.let(::originalLineAtY)?.takeIf { issueLines.containsKey(it) }
        }
        // 本文の上のホバー（項目の説明）と Ctrl+クリック（参照先へ）
        var hoverPos by remember { mutableStateOf<Offset?>(null) }
        var hoverInfo by remember { mutableStateOf<HoverInfo?>(null) }
        fun pathAtPointer(pos: Offset): YPath? {
            val layout = layoutProvider?.invoke() ?: return null
            if (text != doc.sourceParsed || positions.isEmpty) return null
            val local = Offset(pos.x - textPaddingPx + hScroll.value, pos.y - textPaddingPx + vScroll.value)
            if (local.y < 0 || local.y > layout.size.height) return null
            val offset = toOriginal(layout.getOffsetForPosition(local)).coerceIn(0, text.length)
            val line = SourceEditing.lineOf(lineStarts, offset)
            return positions.pathAt(line, offset - lineStarts[line])
        }
        LaunchedEffect(hoverPos) {
            hoverInfo = null
            val pos = hoverPos ?: return@LaunchedEffect
            delay(500)
            val path = pathAtPointer(pos) ?: return@LaunchedEffect
            val root = doc.root ?: return@LaunchedEffect
            val field = doc.schema?.fieldAt(root, path) ?: return@LaunchedEffect
            val title = (field.label ?: field.key ?: path.lastOrNull()?.toString() ?: "") + "（${field.typeName ?: field.type.id}）"
            val detail = buildString {
                field.hints.description?.let { append(it) }
                if (field.type == FieldType.ENUM && field.values.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append("選択肢: " + field.values.joinToString("・"))
                }
                if (field.type == FieldType.REF) {
                    if (isNotEmpty()) append("\n")
                    append("Ctrl+クリックで参照先へ")
                }
            }
            hoverInfo = HoverInfo(path.pretty(), title, detail, pos.x.toInt(), pos.y.toInt() + 18)
        }
        Row(Modifier.fillMaxSize()) {
            LineNumberGutter(
                displayLineStarts, originalLineOf, currentLine, vScroll, layoutProvider, textPadding, gutterStyle,
                issueLines.mapValues { (_, list) -> if (list.any { it.severity == Severity.ERROR }) Severity.ERROR else Severity.WARNING },
                diffMarks, foldable, foldedHeaders, hovering = gutterHoverY != null,
                onHover = { gutterHoverY = it },
                onClick = { y -> originalLineAtY(y)?.let { line -> if (line in foldable || line in foldedHeaders) onToggleFold(line) } },
                Modifier.width(gutterWidth).fillMaxHeight(),
            )
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .then(if (wrap) Modifier else Modifier.horizontalScroll(hScroll))
                    // ホバーと Ctrl+クリックは欄より先に受ける（消費はしない＝欄のカーソル移動はそのまま）
                    .pointerInput(doc) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull()
                                when (event.type) {
                                    PointerEventType.Move, PointerEventType.Enter -> hoverPos = change?.position
                                    PointerEventType.Exit -> hoverPos = null
                                    PointerEventType.Press -> {
                                        hoverPos = null
                                        if (event.keyboardModifiers.isCtrlPressed && change != null) {
                                            followReference(state, doc, pathAtPointer(change.position))
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    },
            ) {
                BasicTextField(
                    state = doc.source,
                    // 本文や履歴は変更せず、貼付・IMEを含む入力の順序だけを採番する。
                    inputTransformation = InputTransformation { doc.noteSourceInput(asCharSequence().toString()) },
                    readOnly = readOnly,
                    textStyle = textStyle,
                    lineLimits = TextFieldLineLimits.MultiLine(),
                    scrollState = vScroll,
                    outputTransformation = transformation,
                    onTextLayout = { getResult -> layoutProvider = getResult },
                    cursorBrush = SolidColor(Palette.Text),
                    modifier = Modifier.fillMaxHeight()
                        // 折り返しなし＝横に無限の幅で配置する（欄は最長の行の幅になる）。欄の外を押しても焦点が入るよう最低幅は表示幅
                        .then(if (wrap) Modifier.fillMaxWidth() else Modifier.widthIn(min = viewportWidth))
                        .padding(textPadding)
                        // 現在行の強調（カーソルの行の背景）。欄の中身はスクロールぶんずれて描かれるので合わせる
                        .drawBehind {
                            val layout = layoutProvider?.invoke() ?: return@drawBehind
                            val sel = doc.source.selection
                            if (!sel.collapsed || layout.lineCount == 0) return@drawBehind
                            val line = layout.getLineForOffset(toDisplay(sel.start).coerceAtMost(layout.layoutInput.text.length))
                            val top = layout.getLineTop(line) - vScroll.value
                            val height = layout.getLineBottom(line) - layout.getLineTop(line)
                            drawRect(currentLineColor, Offset(-textPaddingPx, top), Size(size.width + textPaddingPx * 2, height))
                        }
                        .focusRequester(ui.editorFocus)
                        .onPreviewKeyEvent { ev ->
                            handleEditorKey(ev, doc, state, readOnly, ui, onNext, onPrev, onComplete) {
                                onToggleFold(SourceEditing.lineOf(lineStarts, doc.source.selection.start))
                            }
                        },
                )
                // 補完の一覧（カーソルの下。焦点は欄に残す）
                ui.completion?.let { completion ->
                    val layout = layoutProvider?.invoke()
                    val caret = toDisplay(doc.source.selection.min)
                    val rect = layout?.let { runCatching { it.getCursorRect(caret.coerceAtMost(it.layoutInput.text.length)) }.getOrNull() }
                    val x = ((rect?.left ?: 0f) + textPaddingPx - hScroll.value).toInt()
                    val y = ((rect?.bottom ?: 0f) + textPaddingPx - vScroll.value).toInt()
                    CompletionPopup(completion, IntOffset(x, y)) { candidate -> acceptCompletion(doc, ui, candidate) }
                }
                hoverInfo?.let { info ->
                    Popup(offset = IntOffset(info.x, info.y), properties = PopupProperties(focusable = false)) {
                        Column(
                            Modifier.background(Palette.PanelBg, PanelShape).border(1.dp, Palette.Border, PanelShape)
                                .padding(horizontal = 10.dp, vertical = 6.dp).widthIn(max = 480.dp),
                        ) {
                            Text(info.title, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(info.path, fontSize = 10.sp, color = Palette.SubText)
                            if (info.detail.isNotEmpty()) Text(info.detail, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        // 行番号の上の指摘の内容
        hoveredIssueLine?.let { line ->
            val y = gutterHoverY?.toInt() ?: 0
            Popup(offset = IntOffset(with(density) { gutterWidth.toPx() }.toInt(), y), properties = PopupProperties(focusable = false)) {
                Column(
                    Modifier.background(Palette.PanelBg, PanelShape).border(1.dp, Palette.Border, PanelShape)
                        .padding(horizontal = 10.dp, vertical = 6.dp).widthIn(max = 520.dp),
                ) {
                    issueLines[line].orEmpty().forEach { issue ->
                        Text(
                            (if (issue.severity == Severity.ERROR) "エラー: " else "警告: ") + issue.message,
                            fontSize = 11.sp, color = if (issue.severity == Severity.ERROR) Palette.ErrorRed else Palette.WarnOrange,
                        )
                        Text(issue.path.pretty(), fontSize = 10.sp, color = Palette.SubText)
                    }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(vScroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        if (!wrap) HorizontalScrollbar(rememberScrollbarAdapter(hScroll), Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = gutterWidth))
    }
}

// ================================================================ 補完・参照先

/** 候補を欄に入れて補完を閉じる。 */
private fun acceptCompletion(doc: OpenDocument, ui: SourceUi, candidate: Candidate) {
    val c = ui.completion ?: return
    val caret = doc.source.selection.min
    if (caret < c.replaceStart) { ui.completion = null; return }
    doc.editSource {
        replace(c.replaceStart, caret, candidate.insert)
        placeCursorBeforeCharAt(c.replaceStart + candidate.insert.length)
    }
    ui.completion = null
}

/** Ctrl+クリック: その場所が参照（ref）の値なら参照先へ。 */
private fun followReference(state: AppState, doc: OpenDocument, path: YPath?) {
    path ?: return
    val root = doc.root ?: return
    val field = doc.schema?.fieldAt(root, path)
    if (field?.type != FieldType.REF) return
    val id = (getAt(root, path) as? YNode.Scalar)?.value?.toString()?.takeIf { it.isNotBlank() } ?: return
    state.navigateToId(id)
}

/** 補完の一覧。↑↓ で選び、Enter / Tab で入れる（キーは欄が受けて selected を動かす）。クリックでも入る。 */
@Composable
private fun CompletionPopup(completion: CompletionState, offset: IntOffset, onAccept: (Candidate) -> Unit) {
    val visible = completion.visible
    if (visible.isEmpty()) return
    val selected = completion.selected.coerceIn(0, visible.lastIndex)
    val listState = rememberLazyListState()
    LaunchedEffect(selected, visible.size) { listState.scrollToItem(selected) }
    Popup(offset = offset, properties = PopupProperties(focusable = false)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.background(Palette.PanelBg, PanelShape).border(1.dp, Palette.Border, PanelShape)
                .widthIn(min = 220.dp, max = 520.dp).heightIn(max = 240.dp).padding(vertical = 4.dp),
        ) {
            itemsIndexed(visible) { i, candidate ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (i == selected) Palette.Selected else Color.Transparent)
                        .clickable { onAccept(candidate) }
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(candidate.label, fontSize = 12.sp, fontFamily = AppFonts.Mono)
                    candidate.detail?.let {
                        Spacer(Modifier.width(10.dp))
                        Text(it, fontSize = 11.sp, color = Palette.SubText, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * 欄の中のキー操作。規則は `SourceEditing` にあり、ここは受け取った結果を欄に当てる。
 * - 補完が開いていれば ↑↓ Enter Tab Esc は補完のもの
 * - Ctrl+Space で補完を開く。Ctrl+Shift+[ でカーソルの行の区画を畳む／広げる
 * - Tab: 複数行の選択ならインデント、それ以外はスペース 2 個（YAML はタブ文字を許さない）。Shift+Tab は戻す
 * - Enter: 前の行に合わせたインデントつきの改行（日本語入力の変換中は触らない＝確定の Enter を奪わない）
 * - Ctrl+/ コメント切替・Ctrl+D 行の複製・Alt+↑↓ 行の移動・Ctrl+F / H / G 検索・置換・行へ移動・F3 次へ（Shift で前へ）
 * - Ctrl+Z / Ctrl+Y / Ctrl+Shift+Z はセッションの履歴へ一本化する（欄の中だけの取り消しを持たない）
 */
private fun handleEditorKey(
    ev: KeyEvent, doc: OpenDocument, state: AppState, readOnly: Boolean, ui: SourceUi,
    onNext: () -> Unit, onPrev: () -> Unit, onComplete: () -> Unit, onToggleFold: () -> Unit,
): Boolean {
    if (ev.type != KeyEventType.KeyDown) return false
    val source = doc.source
    fun apply(r: SourceEditing.Result) {
        doc.editSource { replace(0, length, r.text); selection = TextRange(r.start, r.end) }
    }
    val text = { source.text.toString() }
    val sel = source.selection
    ui.completion?.let { c ->
        val visible = c.visible
        when (ev.key) {
            Key.DirectionDown -> { c.selected = (c.selected + 1).coerceAtMost(visible.lastIndex.coerceAtLeast(0)); return true }
            Key.DirectionUp -> { c.selected = (c.selected - 1).coerceAtLeast(0); return true }
            Key.Enter, Key.NumPadEnter, Key.Tab -> {
                if (source.composition != null) return false
                visible.getOrNull(c.selected.coerceIn(0, visible.lastIndex.coerceAtLeast(0)))?.let { acceptCompletion(doc, ui, it) }
                    ?: run { ui.completion = null }
                return true
            }
            Key.Escape -> { ui.completion = null; return true }
            else -> Unit
        }
    }
    if (ev.isCtrlPressed && !ev.isAltPressed) {
        when (ev.key) {
            Key.Spacebar -> { if (!readOnly) onComplete(); return true }
            Key.LeftBracket -> { if (ev.isShiftPressed) { onToggleFold(); return true } }
            Key.Z -> { if (ev.isShiftPressed) state.redoFromUi() else state.undoFromUi(); return true }
            Key.Y -> { state.redoFromUi(); return true }
            Key.F -> {
                // 1 行の選択があればそれを検索語にする
                if (!sel.collapsed) {
                    val selected = text().substring(sel.min, sel.max)
                    if ('\n' !in selected) ui.query = selected
                }
                ui.searchFocus.requestFocus()
                return true
            }
            Key.H -> { if (!readOnly) ui.replaceFocus.requestFocus() else ui.searchFocus.requestFocus(); return true }
            Key.G -> { ui.gotoFocus.requestFocus(); return true }
            Key.D -> { if (!readOnly) apply(SourceEditing.duplicateLines(text(), sel.min, sel.max)); return true }
            Key.Slash -> { if (!readOnly) apply(SourceEditing.toggleComment(text(), sel.min, sel.max)); return true }
        }
        return false
    }
    if (ev.isAltPressed && !ev.isCtrlPressed) {
        when (ev.key) {
            Key.DirectionUp -> { if (!readOnly) apply(SourceEditing.moveLines(text(), sel.min, sel.max, up = true)); return true }
            Key.DirectionDown -> { if (!readOnly) apply(SourceEditing.moveLines(text(), sel.min, sel.max, up = false)); return true }
        }
        return false
    }
    when (ev.key) {
        Key.F3 -> { if (ev.isShiftPressed) onPrev() else onNext(); return true }
        Key.Tab -> {
            if (readOnly || source.composition != null) return false
            val t = text()
            val lines = SourceEditing.touchedLines(t, sel.min, sel.max)
            when {
                ev.isShiftPressed -> apply(SourceEditing.indent(t, sel.min, sel.max, outdent = true))
                lines.first != lines.last -> apply(SourceEditing.indent(t, sel.min, sel.max, outdent = false))
                else -> doc.editSource { replace(sel.min, sel.max, SourceEditing.INDENT); placeCursorBeforeCharAt(sel.min + SourceEditing.INDENT.length) }
            }
            return true
        }
        Key.Enter, Key.NumPadEnter -> {
            if (readOnly || ev.isShiftPressed || source.composition != null) return false
            val insertion = SourceEditing.newlineInsertion(text(), sel.min)
            doc.editSource { replace(sel.min, sel.max, insertion); placeCursorBeforeCharAt(sel.min + insertion.length) }
            return true
        }
    }
    return false
}

/**
 * 行番号と印（指摘・保存済みとの差分・折り畳み）。欄の配置結果（`onTextLayout`）から各表示行の先頭の表示行の位置を取って描くので、
 * 折り返した行でもずれない。揃えるのは上端でなくベースライン: 行の高さ（lineHeight）の余白は先頭行だけ上が削られるので、
 * 上端で揃えると 2 行目から数ピクセル浮く。見えている行だけ描く（1 万行でも軽い）。カーソルの行は濃く出す。
 * 折り畳みがあるときは「表示の行」を描き、番号は元の行で出す。
 */
@Composable
private fun LineNumberGutter(
    displayLineStarts: IntArray,
    originalLineOf: (Int) -> Int,
    currentLine: Int,
    scroll: ScrollState,
    layoutProvider: (() -> TextLayoutResult?)?,
    textPadding: Dp,
    style: TextStyle,
    issueLines: Map<Int, Severity>,
    diffMarks: Map<Int, SourceStructure.DiffMark>,
    foldable: Set<Int>,
    folded: Set<Int>,
    hovering: Boolean,
    onHover: (Float?) -> Unit,
    onClick: (Float) -> Unit,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    val currentStyle = style.copy(color = Palette.Text, fontWeight = FontWeight.Bold)
    val markStyle = style.copy(color = Palette.SubText, fontSize = 11.sp)
    val errorColor = Palette.ErrorRed
    val warnColor = Palette.WarnOrange
    val addedColor = Palette.OkGreen
    val changedColor = Palette.Primary
    Canvas(
        modifier.background(Palette.PanelBg).pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val y = event.changes.firstOrNull()?.position?.y
                    when (event.type) {
                        PointerEventType.Move, PointerEventType.Enter -> onHover(y)
                        PointerEventType.Exit -> onHover(null)
                        PointerEventType.Release -> y?.let(onClick)
                        else -> Unit
                    }
                }
            }
        },
    ) {
        val layout = layoutProvider?.invoke() ?: return@Canvas
        val padTop = textPadding.toPx()
        val offsetY = scroll.value.toFloat()
        val lineCount = displayLineStarts.size - 1
        if (lineCount <= 0 || layout.lineCount == 0) return@Canvas
        // 最初に見える表示行 → その先頭オフセットを含む論理行から始める
        val firstVisual = layout.getLineForVerticalPosition((offsetY - padTop).coerceAtLeast(0f))
        val firstOffset = layout.getLineStart(firstVisual)
        var logical = displayLineStarts.binarySearch(firstOffset, 0, lineCount).let { if (it < 0) -it - 2 else it }.coerceIn(0, lineCount - 1)
        // 同じオフセットの行（空行の連続）は最初のものから
        while (logical > 0 && displayLineStarts[logical - 1] == displayLineStarts[logical]) logical--
        val right = size.width - 18.dp.toPx()
        val markX = 7.dp.toPx()
        val markRadius = 3.dp.toPx()
        val barWidth = 3.dp.toPx()
        while (logical < lineCount) {
            val visual = layout.getLineForOffset(displayLineStarts[logical])
            val top = layout.getLineTop(visual) + padTop - offsetY
            if (top > size.height) break
            val bottom = layout.getLineBottom(visual) + padTop - offsetY
            val original = originalLineOf(logical)
            val measured = measurer.measure((original + 1).toString(), if (original == currentLine) currentStyle else style)
            // 本文の行のベースラインに、番号のベースラインを合わせる
            val baseline = layout.getLineBaseline(visual) + padTop - offsetY
            drawText(measured, topLeft = Offset(right - measured.size.width, baseline - measured.firstBaseline))
            issueLines[original]?.let { severity ->
                drawCircle(if (severity == Severity.ERROR) errorColor else warnColor, markRadius, Offset(markX, (top + bottom) / 2))
            }
            diffMarks[original]?.let { mark ->
                when (mark) {
                    SourceStructure.DiffMark.ADDED -> drawRect(addedColor, Offset(0f, top), Size(barWidth, bottom - top))
                    SourceStructure.DiffMark.CHANGED -> drawRect(changedColor, Offset(0f, top), Size(barWidth, bottom - top))
                    SourceStructure.DiffMark.DELETED_AFTER -> drawRect(errorColor, Offset(0f, bottom - barWidth), Size(barWidth * 3, barWidth))
                }
            }
            val foldLabel = when {
                original in folded -> "▸"
                hovering && original in foldable -> "▾"
                else -> null
            }
            foldLabel?.let {
                val m = measurer.measure(it, markStyle)
                drawText(m, topLeft = Offset(size.width - m.size.width - 4.dp.toPx(), baseline - m.firstBaseline))
            }
            logical++
        }
    }
}

// ================================================================ 検索・置換バー

/**
 * 検索・置換・行へ移動のバー。Enter で次へ（Shift+Enter で前へ）、Esc で欄へ戻る。
 * 条件は「Aa」（大文字小文字を区別）と「.*」（正規表現）。置換は現在の一致だけか、すべて。閲覧専用では置換を出さない。
 */
@Composable
private fun SearchReplaceBar(
    ui: SourceUi,
    matches: List<IntRange>?,
    readOnly: Boolean,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onGoto: (Int) -> Unit,
) {
    fun navigationKeys(ev: KeyEvent): Boolean {
        if (ev.type != KeyEventType.KeyDown) return false
        return when (ev.key) {
            Key.Enter, Key.NumPadEnter -> { if (ev.isShiftPressed) onPrev() else onNext(); true }
            Key.Escape -> { ui.editorFocus.requestFocus(); true }
            Key.F3 -> { if (ev.isShiftPressed) onPrev() else onNext(); true }
            else -> false
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactTextField(
            value = ui.query,
            onValueChange = { ui.query = it },
            singleLine = true,
            isError = matches == null,
            placeholder = { Text("検索 (Ctrl+F)") },
            modifier = Modifier.width(200.dp).focusRequester(ui.searchFocus).onPreviewKeyEvent(::navigationKeys),
        )
        Spacer(Modifier.width(4.dp))
        OptionChip("Aa", ui.caseSensitive) { ui.caseSensitive = !ui.caseSensitive }
        OptionChip(".*", ui.regex) { ui.regex = !ui.regex }
        val label = when {
            matches == null -> "正規表現が不正です"
            ui.query.isEmpty() -> ""
            matches.isEmpty() -> "0 件"
            ui.current in matches.indices -> "${ui.current + 1} / ${matches.size} 件"
            else -> "${matches.size} 件"
        }
        Text(
            label, fontSize = 11.sp, color = if (matches == null) Palette.ErrorRed else Palette.SubText,
            modifier = Modifier.padding(start = 6.dp).widthIn(min = 72.dp),
        )
        val canMove = !matches.isNullOrEmpty()
        TextButton(enabled = canMove, onClick = onPrev, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("↑ 前へ", fontSize = 11.sp) }
        TextButton(enabled = canMove, onClick = onNext, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("↓ 次へ", fontSize = 11.sp) }
        if (!readOnly) {
            Spacer(Modifier.width(8.dp))
            CompactTextField(
                value = ui.replaceWith,
                onValueChange = { ui.replaceWith = it },
                singleLine = true,
                placeholder = { Text("置換後 (Ctrl+H)") },
                modifier = Modifier.width(180.dp).focusRequester(ui.replaceFocus).onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && (ev.key == Key.Enter || ev.key == Key.NumPadEnter)) { onReplace(); true }
                    else navigationKeys(ev)
                },
            )
            TextButton(enabled = canMove && ui.current >= 0, onClick = onReplace, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("置換", fontSize = 11.sp) }
            TextButton(enabled = canMove, onClick = onReplaceAll, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("すべて置換", fontSize = 11.sp) }
        }
        Spacer(Modifier.weight(1f))
        CompactTextField(
            value = ui.gotoLine,
            onValueChange = { ui.gotoLine = it.filter(Char::isDigit) },
            singleLine = true,
            placeholder = { Text("行へ (Ctrl+G)") },
            modifier = Modifier.width(110.dp).focusRequester(ui.gotoFocus).onPreviewKeyEvent { ev ->
                when {
                    ev.type != KeyEventType.KeyDown -> false
                    ev.key == Key.Enter || ev.key == Key.NumPadEnter -> { ui.gotoLine.toIntOrNull()?.let(onGoto); true }
                    ev.key == Key.Escape -> { ui.editorFocus.requestFocus(); true }
                    else -> false
                }
            },
        )
    }
}

/** 検索の条件の切替（押すと色が付く）。 */
@Composable
private fun OptionChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 11.sp,
        fontFamily = AppFonts.Mono,
        color = if (on) Palette.Primary else Palette.SubText,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .background(if (on) Palette.Selected else Color.Transparent, PanelShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

// ================================================================ 下書きのバナー

/**
 * ソース以外のタブに出す案内。解析できない書きかけがあるとき（フォームは閲覧専用）と、壊れたまま保存されているとき
 * （索引・検証の対象外）に、状態と行き先を示す。
 */
@Composable
fun SourceDraftBanner(state: AppState, doc: OpenDocument) {
    doc.version
    val textOnly = doc.textOnly
    val broken = doc.draftBroken
    if (!textOnly && !broken) return
    val line = doc.sourceError?.let { Regex("""（(\d+) 行""").find(it)?.groupValues?.get(1) }
    val where = line?.let { "（$it 行目）" } ?: ""
    val schemaNote = if (state.isSchemaDocument(doc.file)) "このスキーマを使う文書の検証も止まっています。" else ""
    Row(
        Modifier.fillMaxWidth().background(Palette.ConflictBg).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (textOnly) "壊れたまま保存されています$where。直して保存するまで索引・検証の対象外です。$schemaNote"
            else "ソースに解析できない書きかけがあります$where。フォームは最後に解析できた内容で、閲覧専用です。",
            fontSize = 12.sp, color = Palette.ErrorRed, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { state.editorTab = 1 }) { Text("ソースで直す", fontSize = 11.sp) }
        if (textOnly && doc.canRestoreLastParsed) {
            TextButton(onClick = { doc.restoreLastParsed(); state.status = "最後に解析できた内容に戻しました（未保存）" }) {
                Text("解析できた内容に戻す", fontSize = 11.sp)
            }
        } else if (!textOnly) {
            TextButton(onClick = { doc.discardDraft() }) { Text("書きかけを捨てる", fontSize = 11.sp, color = Palette.ErrorRed) }
        }
    }
}
