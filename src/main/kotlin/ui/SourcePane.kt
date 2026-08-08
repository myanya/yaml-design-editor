package ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.AppState
import app.OpenDocument
import yaml.YamlIO
import org.yaml.snakeyaml.error.MarkedYAMLException
import androidx.compose.ui.input.key.*

/**
 * YAML ソースを直接編集するペイン。
 * 「反映」でパースしてツリー（source of truth）へ取り込む。
 * 反映後のテキストは正規化される（コメント・表記スタイルは保持。R8-01）点に注意。
 * 構文ハイライト・置換は R8-04。
 */
@Composable
fun SourcePane(state: AppState, doc: OpenDocument) {
    val canonical = remember(doc, doc.version) { doc.canonicalText() }
    // カーソル位置を扱う（Tab のスペース挿入 UX-25）ため TextFieldValue で保持する
    var fieldValue by remember(doc, doc.version) { mutableStateOf(TextFieldValue(canonical)) }
    val text = fieldValue.text
    var error by remember(doc) { mutableStateOf<String?>(null) }
    var search by remember(doc) { mutableStateOf("") }
    var replace by remember(doc) { mutableStateOf("") }
    val edited = text != canonical

    fun applySource(): Boolean {
        return try {
            doc.applySourceText(text)
            error = null
            true
        } catch (e: Exception) {
            val mark = (e as? MarkedYAMLException)?.problemMark
            error = "パースエラー" + (mark?.let { "（${it.line + 1} 行 ${it.column + 1} 列）" } ?: "") + ": ${e.message}"
            false
        }
    }

    Column(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.S) {
            if (edited) {
                if (applySource()) { if (event.isShiftPressed) state.saveAll() else state.saveCurrent() }
            } else if (event.isShiftPressed) state.saveAll() else state.saveCurrent()
            true
        } else false
    }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                enabled = edited,
                onClick = { applySource() },
            ) { Text("変更を反映", fontSize = 12.sp) }
            Spacer(Modifier.width(8.dp))
            TextButton(enabled = edited, onClick = { fieldValue = TextFieldValue(canonical); error = null }) {
                Text("破棄", fontSize = 13.sp)
            }
            Spacer(Modifier.width(12.dp))
            if (doc.root == null) {
                Text(
                    "パースエラーのためフォーム表示不可。ここで修正して「反映」してください。",
                    fontSize = 12.sp, color = Palette.ErrorRed,
                )
            } else if (edited) {
                Text("未反映：反映後、文書ヘッダーの「保存」で書き出します", fontSize = 12.sp, color = Palette.WarnOrange)
            } else if (doc.dirty) {
                Text("反映済み・未保存：Ctrl+Sでファイルへ保存できます", fontSize = 12.sp, color = Palette.WarnOrange)
            } else {
                Text("保存済み", fontSize = 12.sp, color = Palette.OkGreen)
            }
            Spacer(Modifier.weight(1f))
        }
        // 検索・置換バー（R8-04）
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
                val count = remember(text, search) { Regex(Regex.escape(search), RegexOption.IGNORE_CASE).findAll(text).count() }
                Text("$count 件", fontSize = 11.sp, color = Palette.SubText, modifier = Modifier.padding(start = 6.dp))
            }
            Spacer(Modifier.width(8.dp))
            CompactTextField(
                value = replace,
                onValueChange = { replace = it },
                singleLine = true,
                placeholder = { Text("置換後の文字列") },
                modifier = Modifier.width(180.dp),
            )
            TextButton(enabled = search.isNotEmpty(), onClick = {
                replaceFirstIgnoreCase(text, search, replace)?.let { fieldValue = fieldValue.copy(text = it) }
                    ?: run { state.status = "「$search」は見つかりません" }
            }) { Text("置換", fontSize = 11.sp) }
            TextButton(enabled = search.isNotEmpty(), onClick = {
                val (replaced, count) = replaceAllIgnoreCase(text, search, replace)
                fieldValue = fieldValue.copy(text = replaced)
                state.status = if (count > 0) "$count 件置換しました（「変更を反映」で取り込まれます）" else "「$search」は見つかりません"
            }) { Text("すべて置換", fontSize = 11.sp) }
        }
        error?.let {
            Text(
                it,
                Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                fontSize = 12.sp, color = Palette.ErrorRed,
            )
        }
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .padding(horizontal = 12.dp)
                .background(Palette.EditorBg)
        ) {
            val scroll = rememberScrollState()
            Row(Modifier.fillMaxSize().verticalScroll(scroll)) {
                val lineCount = text.count { it == '\n' } + 1
                BasicTextField(
                    value = (1..lineCount).joinToString("\n"),
                    onValueChange = {},
                    readOnly = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        textAlign = TextAlign.End,
                        color = Palette.SubText,
                    ),
                    modifier = Modifier.widthIn(min = 42.dp)
                        .background(Palette.PanelBg)
                        .padding(start = 6.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                )
                BasicTextField(
                    value = fieldValue,
                    onValueChange = { fieldValue = it },
                    visualTransformation = YamlSyntaxTransformation,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = Palette.Text,
                    ),
                    modifier = Modifier.weight(1f).padding(12.dp)
                        // Tab はフォーカス移動でなくスペース2個の挿入にする（YAMLはタブ文字を許さない。UX-25）
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown && ev.key == Key.Tab && !ev.isShiftPressed) {
                                val sel = fieldValue.selection
                                val inserted = fieldValue.text.replaceRange(sel.min, sel.max, "  ")
                                fieldValue = TextFieldValue(inserted, selection = TextRange(sel.min + 2))
                                true
                            } else false
                        },
                )
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ================================================================ 構文ハイライト（R8-04）

/** YAML の簡易構文ハイライト。トークン化は ui/SourceHighlight.kt の純ロジックが担う。 */
private object YamlSyntaxTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val spans = yamlHighlightTokens(text.text).mapNotNull { token ->
            val end = token.end.coerceAtMost(text.length)
            if (token.start >= end) return@mapNotNull null
            AnnotatedString.Range(styleFor(token.type), token.start, end)
        }
        return TransformedText(AnnotatedString(text.text, spans), OffsetMapping.Identity)
    }

    private fun styleFor(type: YamlTokenType): SpanStyle = when (type) {
        YamlTokenType.KEY -> SpanStyle(color = Palette.Primary)
        YamlTokenType.STRING -> SpanStyle(color = Palette.OkGreen)
        YamlTokenType.NUMBER -> SpanStyle(color = Palette.Secondary)
        YamlTokenType.KEYWORD -> SpanStyle(color = Palette.WarnOrange)
        YamlTokenType.COMMENT -> SpanStyle(color = Palette.SubText, fontStyle = FontStyle.Italic)
        YamlTokenType.INDICATOR -> SpanStyle(color = Palette.SubText)
    }
}
