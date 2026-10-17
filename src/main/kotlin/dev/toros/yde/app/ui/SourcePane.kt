package dev.toros.yde.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
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
 * YAML ソースを直接編集するペイン（P43 段階 0。architecture/editing-history.md「ソースとの同期」）。
 * 文字列・カーソル・解析エラーは文書（`OpenDocument.source` ほか）が持ち、この画面は表示と入力だけを担う＝
 * タブや文書を移っても書きかけは残る（R43-01）。
 * 入力が止まってから背景で解析し、成功したときだけ木へ反映する（R43-06。木が正）。解析できない間は木＝最後に解析できた
 * 状態のままで、フォームはバナー＋閲覧専用になる（R43-02）。他所で木が変わったら差分だけを下書きへ差し込む（R43-03）。
 * 保存は壊れていても通り、その文書はテキスト文書になる（R43-04）。
 * 欄は Compose 標準の `TextFieldState` 版で、色分けは行ごとの覚え、行番号は欄の配置結果に揃える（R43-05）。
 */
@Composable
fun SourcePane(state: AppState, doc: OpenDocument) {
    doc.version
    val readOnly = state.readOnlyMode
    val wrap = state.memory.sourceWrap

    // 他所（フォーム・Undo・操作・再読込）の変更を下書きへ差し込む（R43-03）。自分の反映は syncedVersion を進めているので対象外
    LaunchedEffect(doc, doc.version) {
        if (doc.version != doc.sourceSyncedVersion) {
            val conflicts = doc.syncSourceFromTree()
            if (conflicts > 0) state.status = "下書きと食い違う変更が $conflicts か所あり、下書きを優先しました（「整形」で木に揃えられます）"
        }
    }
    // 入力が止まってから解析して反映（R43-06）: 解析は背景、木の更新は UI 側（スナップショット状態の更新）
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

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val differs = doc.source.text.toString() != doc.sourceBase
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
        // 検索・置換バー（R8-04。閲覧専用では置換を出さない）
        SearchReplaceBar(state, doc, readOnly)
        doc.sourceError?.let {
            Text(it, Modifier.padding(horizontal = 12.dp, vertical = 2.dp), fontSize = 12.sp, color = Palette.ErrorRed)
        }
        SourceEditor(doc, readOnly, wrap, state, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp))
        Spacer(Modifier.height(8.dp))
    }
}

/** 入力が止まってから木へ反映するまでの待ち。 */
private const val APPLY_DELAY_MS = 300L

private val editorTextStyle: TextStyle
    @Composable get() = TextStyle(fontFamily = AppFonts.Mono, fontSize = 13.sp, lineHeight = 19.sp, color = Palette.Text)

/** 本文の欄（行番号・折り返しと横スクロール・色分け・キー操作）。 */
@Composable
private fun SourceEditor(doc: OpenDocument, readOnly: Boolean, wrap: Boolean, state: AppState, modifier: Modifier) {
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    var layoutProvider by remember(doc) { mutableStateOf<(() -> TextLayoutResult?)?>(null) }
    val textStyle = editorTextStyle
    val highlighter = remember(doc) { YamlHighlighter() }
    // 色分け（R8-04 → R43-05）: 表示だけを変え、文字列には触らない。色は描画時に読む（テーマ切替でも追随）
    val keyColor = Palette.Primary
    val stringColor = Palette.OkGreen
    val numberColor = Palette.Secondary
    val keywordColor = Palette.WarnOrange
    val subColor = Palette.SubText
    val transformation = remember(doc, keyColor, stringColor, numberColor, keywordColor, subColor) {
        OutputTransformation {
            val tokens = highlighter.tokens(asCharSequence())
            for (t in tokens) {
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
        }
    }
    val textPadding = 12.dp
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
        Row(Modifier.fillMaxSize()) {
            LineNumberGutter(lineStarts, vScroll, layoutProvider, textPadding, gutterStyle, Modifier.width(gutterWidth).fillMaxHeight())
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
                        .onPreviewKeyEvent { ev -> handleEditorKey(ev, doc, state, readOnly) },
                )
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(vScroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        if (!wrap) HorizontalScrollbar(rememberScrollbarAdapter(hScroll), Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = gutterWidth))
    }
}

/**
 * 欄の中のキー操作。Tab はフォーカス移動でなくスペース 2 個（YAML はタブ文字を許さない。UX-25）。
 * Ctrl+Z / Ctrl+Y / Ctrl+Shift+Z はセッションの履歴へ一本化する（欄の中だけの取り消しを持たない。R43-05）＝
 * フォームと同じ 1 本の履歴で、入力待ちの分は先に取り込む。
 */
private fun handleEditorKey(ev: KeyEvent, doc: OpenDocument, state: AppState, readOnly: Boolean): Boolean {
    if (ev.type != KeyEventType.KeyDown) return false
    if (ev.isCtrlPressed) {
        when {
            ev.key == Key.Z && ev.isShiftPressed -> { state.redo(); return true }
            ev.key == Key.Z -> { state.undo(); return true }
            ev.key == Key.Y -> { state.redo(); return true }
        }
        return false
    }
    if (ev.key == Key.Tab && !ev.isShiftPressed && !readOnly) {
        doc.source.edit {
            val sel = selection
            replace(sel.min, sel.max, "  ")
            placeCursorBeforeCharAt(sel.min + 2)
        }
        return true
    }
    return false
}

/**
 * 行番号（R43-05）。欄の配置結果（`onTextLayout`）から各論理行の先頭の表示行の上端を取って描くので、折り返した行でもずれない。
 * 見えている行だけ描く（1 万行でも軽い）。
 */
@Composable
private fun LineNumberGutter(
    lineStarts: IntArray,
    scroll: ScrollState,
    layoutProvider: (() -> TextLayoutResult?)?,
    textPadding: Dp,
    style: TextStyle,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
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
            val measured = measurer.measure((logical + 1).toString(), style)
            drawText(measured, topLeft = Offset(right - measured.size.width, top))
            logical++
        }
    }
}

/** 検索・置換バー（R8-04）。大文字小文字を無視した一致。置換は下書きの該当箇所だけを書き換える。 */
@Composable
private fun SearchReplaceBar(state: AppState, doc: OpenDocument, readOnly: Boolean) {
    var search by remember(doc) { mutableStateOf("") }
    var replace by remember(doc) { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactTextField(
            value = search,
            onValueChange = { search = it },
            singleLine = true,
            placeholder = { Text("検索") },
            modifier = Modifier.width(180.dp),
        )
        if (search.isNotEmpty()) {
            val text = doc.source.text.toString()
            val count = remember(text, search) { Regex(Regex.escape(search), RegexOption.IGNORE_CASE).findAll(text).count() }
            Text("$count 件", fontSize = 11.sp, color = Palette.SubText, modifier = Modifier.padding(start = 6.dp))
        }
        if (readOnly) return@Row
        Spacer(Modifier.width(8.dp))
        CompactTextField(
            value = replace,
            onValueChange = { replace = it },
            singleLine = true,
            placeholder = { Text("置換後の文字列") },
            modifier = Modifier.width(180.dp),
        )
        TextButton(enabled = search.isNotEmpty(), onClick = {
            val text = doc.source.text.toString()
            val index = text.indexOf(search, ignoreCase = true)
            if (index < 0) state.status = "「$search」は見つかりません"
            else doc.source.edit { replace(index, index + search.length, replace); placeCursorBeforeCharAt(index + replace.length) }
        }) { Text("置換", fontSize = 11.sp) }
        TextButton(enabled = search.isNotEmpty(), onClick = {
            val text = doc.source.text.toString()
            val (replaced, count) = replaceAllIgnoreCase(text, search, replace)
            if (count > 0) {
                val caret = doc.source.selection.start.coerceAtMost(replaced.length)
                doc.source.edit { replace(0, length, replaced); placeCursorBeforeCharAt(caret) }
            }
            state.status = if (count > 0) "$count 件置換しました（自動で反映されます）" else "「$search」は見つかりません"
        }) { Text("すべて置換", fontSize = 11.sp) }
    }
}

// ================================================================ 下書きのバナー（R43-02・04）

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
