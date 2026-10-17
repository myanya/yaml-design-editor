package dev.toros.yde.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.AppState
import dev.toros.yde.app.OpenDocument
import dev.toros.yde.app.core.EditBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/**
 * YAML ソースを直接編集するペイン（architecture/editing-history.md「ソースとの同期」）。
 * 文字列・カーソル・解析エラーは文書（`OpenDocument.source` ほか）が持ち、この画面は表示と入力だけを担う＝
 * タブや文書を移っても書きかけは残る。
 * 入力が止まってから背景で解析し、成功したときだけ木へ反映する（木が正）。解析できない間は木＝最後に解析できた
 * 状態のままで、フォームはバナー＋閲覧専用になる。他所で木が変わったら差分だけを下書きへ差し込む。
 * 保存は壊れていても通り、その文書はテキスト文書になる。
 * 欄は Compose 標準の `TextFieldState` 版で、色分けは行ごとの覚え、行番号は欄の配置結果に揃える。
 * 基本操作（検索の強調と移動・正規表現・行ジャンプ・自動インデント・インデント・コメント切替・行の移動と複製）の規則は
 * `SourceEditing`（純ロジック）にあり、ここはキーを受けて結果を欄に当てるだけ。
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
            val conflicts = doc.syncSourceFromTree()
            if (conflicts > 0) state.status = "下書きと食い違う変更が $conflicts か所あり、下書きを優先しました（「整形」で木に揃えられます）"
        }
    }
    // 入力が止まってから解析して反映: 解析は背景、木の更新は UI 側（スナップショット状態の更新）
    LaunchedEffect(doc) {
        snapshotFlow { doc.source.text.toString() }.collectLatest { text ->
            if (text == doc.sourceApplied) return@collectLatest
            delay(APPLY_DELAY_MS)
            val parsed = withContext(Dispatchers.Default) { EditBuffer.parse(text) }
            doc.applyParsed(text, parsed)
        }
    }
    // タブや文書を離れるときは、入力待ちの分を取り込んでおく（フォームが古いまま見えないように）
    DisposableEffect(doc) { onDispose { doc.flushDraft() } }

    // 検索の一致（文字列・検索語・条件が変わるたびに引き直す。1 万行でも数 ms）
    val text = doc.source.text.toString()
    val matches = remember(text, ui.query, ui.regex, ui.caseSensitive) {
        SourceEditing.matches(text, ui.query, ui.regex, ui.caseSensitive)
    }
    fun select(index: Int) {
        val m = matches?.getOrNull(index) ?: return
        ui.current = index
        doc.source.edit { selection = TextRange(m.first, m.last + 1) }
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
        doc.source.edit { replace(m.first, m.last + 1, r.text.substring(r.start, r.end)); selection = TextRange(r.end) }
    }
    fun replaceAll() {
        val (replaced, count) = SourceEditing.replaceAll(text, ui.query, ui.replaceWith, ui.regex, ui.caseSensitive)
        if (count > 0) {
            val caret = doc.source.selection.start.coerceAtMost(replaced.length)
            doc.source.edit { replace(0, length, replaced); placeCursorBeforeCharAt(caret) }
        }
        state.status = if (count > 0) "$count 件置換しました（自動で反映されます）" else "「${ui.query}」は見つかりません"
    }
    fun gotoLine(line: Int) {
        val starts = LineMerge.lineStarts(text)
        val index = (line - 1).coerceIn(0, maxOf(starts.size - 2, 0))
        doc.source.edit { selection = TextRange(starts[index]) }
        ui.revealRequest++
        ui.editorFocus.requestFocus()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
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
                )
            }
            Spacer(Modifier.width(12.dp))
            when {
                doc.textOnly -> Text(
                    "壊れたまま保存されています: 直して保存するまで索引・検証の対象外です",
                    fontSize = 12.sp, color = Palette.ErrorRed,
                )
                doc.draftBroken -> Text(
                    "解析できない書きかけ: フォームは最後に解析できた内容のままです（このまま保存すると壊れたまま保存されます）",
                    fontSize = 12.sp, color = Palette.ErrorRed,
                )
                doc.dirty -> Text("反映済み・未保存：Ctrl+Sでファイルへ保存できます", fontSize = 12.sp, color = Palette.WarnOrange)
                else -> Text("保存済み", fontSize = 12.sp, color = Palette.OkGreen)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { state.memory.sourceWrap = !wrap }) {
                Text(if (wrap) "折り返し: あり" else "折り返し: なし", fontSize = 11.sp, color = Palette.SubText)
            }
        }
        SearchReplaceBar(
            ui, matches, readOnly,
            onNext = ::findNext, onPrev = ::findPrev, onReplace = ::replaceCurrent, onReplaceAll = ::replaceAll, onGoto = ::gotoLine,
        )
        doc.sourceError?.let {
            Text(it, Modifier.padding(horizontal = 12.dp, vertical = 2.dp), fontSize = 12.sp, color = Palette.ErrorRed)
        }
        SourceEditor(
            doc, readOnly, wrap, state, ui, vScroll, matches,
            onNext = ::findNext, onPrev = ::findPrev,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}

/** 入力が止まってから木へ反映するまでの待ち。 */
private const val APPLY_DELAY_MS = 300L

/** 画面の中だけの状態（操作の途中）: 検索・置換の入力と現在の一致、焦点、見せたい位置の要求。文書ごとに作り直す。 */
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
    val editorFocus = FocusRequester()
    val searchFocus = FocusRequester()
    val replaceFocus = FocusRequester()
    val gotoFocus = FocusRequester()
}

private val editorTextStyle: TextStyle
    @Composable get() = TextStyle(fontFamily = AppFonts.Mono, fontSize = 13.sp, lineHeight = 19.sp, color = Palette.Text)

/** 本文の欄（行番号・折り返しと横スクロール・色分けと一致の強調・現在行・キー操作）。 */
@Composable
private fun SourceEditor(
    doc: OpenDocument,
    readOnly: Boolean,
    wrap: Boolean,
    state: AppState,
    ui: SourceUi,
    vScroll: ScrollState,
    matches: List<IntRange>?,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    modifier: Modifier,
) {
    val hScroll = remember(doc) { ScrollState(0) }
    var layoutProvider by remember(doc) { mutableStateOf<(() -> TextLayoutResult?)?>(null) }
    val textStyle = editorTextStyle
    val highlighter = remember(doc) { YamlHighlighter() }
    // 色分けと一致の強調: 表示だけを変え、文字列には触らない。色は描画時に読む（テーマ切替でも追随）
    val keyColor = Palette.Primary
    val stringColor = Palette.OkGreen
    val numberColor = Palette.Secondary
    val keywordColor = Palette.WarnOrange
    val subColor = Palette.SubText
    val hitColor = Palette.Highlight
    val currentHitColor = Palette.WarnOrange.copy(alpha = 0.45f)
    val current = ui.current
    val transformation = remember(doc, keyColor, stringColor, numberColor, keywordColor, subColor, matches, current) {
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
            matches?.forEachIndexed { i, m ->
                val end = (m.last + 1).coerceAtMost(length)
                if (m.first < end) addStyle(SpanStyle(background = if (i == current) currentHitColor else hitColor), m.first, end)
            }
        }
    }
    val textPadding = 12.dp
    val currentLineColor = Palette.Selected.copy(alpha = 0.45f)
    BoxWithConstraints(modifier.background(Palette.EditorBg)) {
        val lineStarts = remember(doc.source.text) { LineMerge.lineStarts(doc.source.text.toString()) }
        val lineCount = lineStarts.size - 1
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val gutterStyle = textStyle.copy(color = Palette.SubText)
        val gutterWidth = remember(lineCount, gutterStyle) {
            val digits = lineCount.toString().length.coerceAtLeast(2)
            with(density) { (measurer.measure("0".repeat(digits), gutterStyle).size.width.toDp()) } + 16.dp
        }
        val viewportWidth = maxWidth - gutterWidth
        val viewportHeightPx = with(density) { (maxHeight - textPadding * 2).toPx() }
        val textPaddingPx = with(density) { textPadding.toPx() }
        // 検索の一致・行ジャンプで、選択範囲の行が見えるところまでスクロールする
        LaunchedEffect(ui.revealRequest) {
            if (ui.revealRequest == 0) return@LaunchedEffect
            val layout = layoutProvider?.invoke() ?: return@LaunchedEffect
            val line = layout.getLineForOffset(doc.source.selection.start)
            val top = layout.getLineTop(line)
            val bottom = layout.getLineBottom(line)
            if (top < vScroll.value || bottom > vScroll.value + viewportHeightPx) {
                vScroll.scrollTo((top - viewportHeightPx / 3).coerceAtLeast(0f).toInt())
            }
        }
        val currentLine = SourceEditing.lineOf(lineStarts, doc.source.selection.start)
        Row(Modifier.fillMaxSize()) {
            LineNumberGutter(lineStarts, currentLine, vScroll, layoutProvider, textPadding, gutterStyle, Modifier.width(gutterWidth).fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight().then(if (wrap) Modifier else Modifier.horizontalScroll(hScroll))) {
                BasicTextField(
                    state = doc.source,
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
                            val line = layout.getLineForOffset(sel.start)
                            val top = layout.getLineTop(line) - vScroll.value
                            val height = layout.getLineBottom(line) - layout.getLineTop(line)
                            drawRect(currentLineColor, Offset(-textPaddingPx, top), Size(size.width + textPaddingPx * 2, height))
                        }
                        .focusRequester(ui.editorFocus)
                        .onPreviewKeyEvent { ev -> handleEditorKey(ev, doc, state, readOnly, ui, onNext, onPrev) },
                )
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(vScroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        if (!wrap) HorizontalScrollbar(rememberScrollbarAdapter(hScroll), Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = gutterWidth))
    }
}

/**
 * 欄の中のキー操作。規則は `SourceEditing` にあり、ここは受け取った結果を欄に当てる。
 * - Tab: 複数行の選択ならインデント、それ以外はスペース 2 個（YAML はタブ文字を許さない）。Shift+Tab は戻す
 * - Enter: 前の行に合わせたインデントつきの改行（日本語入力の変換中は触らない＝確定の Enter を奪わない）
 * - Ctrl+/ コメント切替・Ctrl+D 行の複製・Alt+↑↓ 行の移動・Ctrl+F / H / G 検索・置換・行へ移動・F3 次へ（Shift で前へ）
 * - Ctrl+Z / Ctrl+Y / Ctrl+Shift+Z はセッションの履歴へ一本化する（欄の中だけの取り消しを持たない）
 */
private fun handleEditorKey(
    ev: KeyEvent, doc: OpenDocument, state: AppState, readOnly: Boolean, ui: SourceUi,
    onNext: () -> Unit, onPrev: () -> Unit,
): Boolean {
    if (ev.type != KeyEventType.KeyDown) return false
    val source = doc.source
    fun apply(r: SourceEditing.Result) {
        source.edit { replace(0, length, r.text); selection = TextRange(r.start, r.end) }
    }
    val text = { source.text.toString() }
    val sel = source.selection
    if (ev.isCtrlPressed && !ev.isAltPressed) {
        when (ev.key) {
            Key.Z -> { if (ev.isShiftPressed) state.redo() else state.undo(); return true }
            Key.Y -> { state.redo(); return true }
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
                else -> source.edit { replace(sel.min, sel.max, SourceEditing.INDENT); placeCursorBeforeCharAt(sel.min + SourceEditing.INDENT.length) }
            }
            return true
        }
        Key.Enter, Key.NumPadEnter -> {
            if (readOnly || ev.isShiftPressed || source.composition != null) return false
            val insertion = SourceEditing.newlineInsertion(text(), sel.min)
            source.edit { replace(sel.min, sel.max, insertion); placeCursorBeforeCharAt(sel.min + insertion.length) }
            return true
        }
    }
    return false
}

/**
 * 行番号。欄の配置結果（`onTextLayout`）から各論理行の先頭の表示行の位置を取って描くので、折り返した行でもずれない。
 * 揃えるのは上端でなくベースライン: 行の高さ（lineHeight）の余白は先頭行だけ上が削られるので、上端で揃えると
 * 2 行目から数ピクセル浮く。見えている行だけ描く（1 万行でも軽い）。カーソルの行は濃く出す。
 */
@Composable
private fun LineNumberGutter(
    lineStarts: IntArray,
    currentLine: Int,
    scroll: ScrollState,
    layoutProvider: (() -> TextLayoutResult?)?,
    textPadding: Dp,
    style: TextStyle,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    val currentStyle = style.copy(color = Palette.Text, fontWeight = FontWeight.Bold)
    Canvas(modifier.background(Palette.PanelBg)) {
        val layout = layoutProvider?.invoke() ?: return@Canvas
        val padTop = textPadding.toPx()
        val offsetY = scroll.value.toFloat()
        val lineCount = lineStarts.size - 1
        if (lineCount <= 0 || layout.lineCount == 0) return@Canvas
        // 最初に見える表示行 → その先頭オフセットを含む論理行から始める
        val firstVisual = layout.getLineForVerticalPosition((offsetY - padTop).coerceAtLeast(0f))
        val firstOffset = layout.getLineStart(firstVisual)
        var logical = lineStarts.binarySearch(firstOffset, 0, lineCount).let { if (it < 0) -it - 2 else it }.coerceIn(0, lineCount - 1)
        // 同じオフセットの行（空行の連続）は最初のものから
        while (logical > 0 && lineStarts[logical - 1] == lineStarts[logical]) logical--
        val right = size.width - 8.dp.toPx()
        while (logical < lineCount) {
            val visual = layout.getLineForOffset(lineStarts[logical])
            val top = layout.getLineTop(visual) + padTop - offsetY
            if (top > size.height) break
            val measured = measurer.measure((logical + 1).toString(), if (logical == currentLine) currentStyle else style)
            // 本文の行のベースラインに、番号のベースラインを合わせる
            val y = layout.getLineBaseline(visual) + padTop - offsetY - measured.firstBaseline
            drawText(measured, topLeft = Offset(right - measured.size.width, y))
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
        OptionChip("Aa", ui.caseSensitive, "大文字と小文字を区別する") { ui.caseSensitive = !ui.caseSensitive }
        OptionChip(".*", ui.regex, "正規表現で検索する") { ui.regex = !ui.regex }
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
private fun OptionChip(label: String, on: Boolean, description: String, onClick: () -> Unit) {
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
    @Suppress("UNUSED_EXPRESSION") description   // ツールチップは段階 2 で（全アイコンボタンのツールチップと一緒に）
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
