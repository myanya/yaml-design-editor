package dev.toros.yde.desktop.ui.source

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.toros.yde.desktop.OpenDocument
import dev.toros.yde.desktop.ui.common.AppFonts
import dev.toros.yde.desktop.ui.common.FieldHelpPosition
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PanelShape

/** 構文の状態だけを表示する。検証の指摘・保存状態とは区別し、本文の開始位置を変えない。 */
@Composable
internal fun SourceSyntaxBar(doc: OpenDocument, onGotoLine: (Int) -> Unit, onReturnToEditor: () -> Unit) {
    doc.version
    val pending = doc.source.text.toString() != doc.sourceApplied
    val error = doc.sourceError.takeUnless { pending }
    val location = remember(error) { sourceErrorLocation(error) }
    var detailsOpen by remember { mutableStateOf(false) }
    var returnFocus by remember { mutableStateOf(false) }
    val unsaved = if (pending || doc.draftBroken) doc.source.text.toString() != doc.rawText else doc.dirty
    val color = when {
        pending -> Palette.SubText
        error != null -> Palette.ErrorRed
        else -> Palette.OkGreen
    }
    val summary = when {
        pending -> "… 入力内容を確認中"
        error == null -> "✓ YAMLの構文に問題はありません"
        doc.textOnly -> "⚠ 解析できない状態で保存されています"
        else -> "⚠ YAMLを解析できません"
    } + (location?.let { "（${it.line}行目・${it.column}列）" } ?: "")
    fun closeDetails() { detailsOpen = false; returnFocus = true }
    LaunchedEffect(error) {
        if (error == null && detailsOpen) closeDetails()
    }
    // ポップアップを除いてから本文へ戻す。選択範囲・スクロール位置は変更しない。
    LaunchedEffect(detailsOpen, returnFocus) {
        if (!detailsOpen && returnFocus) { returnFocus = false; onReturnToEditor() }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        val detailWidth = minOf(maxWidth, 520.dp)
        Row(
            Modifier.fillMaxWidth().height(36.dp).background(color.copy(alpha = 0.08f), PanelShape)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(summary, Modifier.weight(1f), color = color, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (unsaved) "未保存" else "保存済み", Modifier.padding(horizontal = 8.dp),
                fontSize = 11.sp, color = Palette.SubText, maxLines = 1)
            if (error != null) {
                TextButton(onClick = { detailsOpen = true }, contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(32.dp)) {
                    Text("詳細", color = Palette.ErrorRed, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        if (detailsOpen && error != null) {
            Popup(
                popupPositionProvider = FieldHelpPosition,
                onDismissRequest = ::closeDetails,
                properties = PopupProperties(focusable = true),
            ) {
                val clipboard = LocalClipboardManager.current
                Surface(
                    modifier = Modifier.width(detailWidth).heightIn(max = 360.dp),
                    shape = PanelShape, color = Palette.Surface, contentColor = Palette.Text,
                    border = BorderStroke(1.dp, Palette.Border), shadowElevation = 6.dp,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("YAMLの解析エラー", Modifier.weight(1f), fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = ::closeDetails) { Text("閉じる", fontSize = 12.sp) }
                        }
                        val scroll = rememberScrollState()
                        Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                            SelectionContainer {
                                Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        if (doc.textOnly) "直して保存するまで、保存済みの文書は索引・検証の対象外です。"
                                        else "フォームは最後に解析できた内容を表示し、閲覧専用になっています。このまま保存すると、解析できないテキストとして保存されます。",
                                        fontSize = 12.sp, color = Palette.SubText,
                                    )
                                    Text(error, fontFamily = AppFonts.Mono, fontSize = 12.sp, color = Palette.ErrorRed)
                                }
                            }
                            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(error)) }) {
                                Text("全文をコピー", fontSize = 12.sp)
                            }
                            if (location != null) {
                                TextButton(onClick = { closeDetails(); onGotoLine(location.line) }) {
                                    Text("該当行へ移動", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal data class SourceErrorLocation(val line: Int, val column: Int)

/** パーサーが出す位置行だけを読む。文脈の開始位置より、最後の問題位置を優先する。 */
internal fun sourceErrorLocation(error: String?): SourceErrorLocation? {
    val match = error?.let { sourceErrorMark.findAll(it).lastOrNull() } ?: return null
    val line = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
    val column = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
    return SourceErrorLocation(line, column)
}

private val sourceErrorMark = Regex("""(?m)^\s*in [^\r\n]+, line (\d+), column (\d+):\s*$""")
