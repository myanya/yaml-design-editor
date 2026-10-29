package dev.toros.yde.desktop.ui.file

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.core.FileCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.ui.common.AppFonts
import dev.toros.yde.desktop.ui.common.CompactTextField
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.openExternally

/** 一般ファイルの閲覧。YAML のキャッシュ・索引・履歴には入れない。 */
@Composable
fun FilePreviewPane(state: AppState) {
    val file = state.openPreview ?: return
    var preview by remember(file) { mutableStateOf<FileCatalog.Preview?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var revision by remember(file) { mutableIntStateOf(0) }
    var structure by remember(file) { mutableStateOf(false) }
    var export by remember(file) { mutableStateOf(false) }
    var destination by remember(file) { mutableStateOf("references/${file.fileName}.symbols.yaml") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var outline by remember(file) { mutableStateOf(dev.toros.yde.app.core.SourceOutline.Outline("未対応", emptyList())) }
    LaunchedEffect(file, revision, state.fileTreeRevision) {
        val result = withContext(Dispatchers.IO) { runCatching {
            state.workspace?.let { dev.toros.yde.workspace.WorkspacePaths.resolve(it, state.relPath(file)) }
            FileCatalog.preview(file)
        } }
        preview = result.getOrNull(); error = result.exceptionOrNull()?.message
        outline = withContext(Dispatchers.Default) {
            dev.toros.yde.app.core.SourceOutline.extract(file.fileName.toString(), preview?.text.orEmpty())
        }
    }
    val lines = remember(preview) { preview?.text?.lines().orEmpty() }
    LaunchedEffect(file, lines, state.previewLine) {
        if (lines.isNotEmpty()) listState.scrollToItem((state.previewLine - 1).coerceIn(0, lines.lastIndex))
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(state.relPath(file), fontSize = 16.sp)
        Row {
            TextButton(onClick = { revision++ }) { Text("再読込") }
            TextButton(onClick = { openExternally(file.toUri().toString()) }) { Text("外部で開く") }
            TextButton(enabled = outline.language != "未対応", onClick = { structure = !structure }) { Text("構造 ${outline.symbols.size}") }
            TextButton(enabled = outline.symbols.isNotEmpty(), onClick = { export = true }) { Text("構造を保存…") }
        }
        Text(error ?: preview?.let { "${it.message}（${it.size} bytes）" } ?: "読み込み中…", color = Palette.SubText)
        if (structure) {
            Text("${outline.language} の宣言候補（型解決・呼出関係は含みません）", color = Palette.SubText)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                itemsIndexed(outline.symbols) { _, symbol ->
                    TextButton(onClick = { state.previewLine = symbol.line; scope.launch { listState.scrollToItem(symbol.line - 1) } }) {
                        Text("${symbol.line}: ${symbol.kind} ${symbol.owner?.let { "$it." }.orEmpty()}${symbol.name}")
                    }
                }
            }
        }
        Box(Modifier.weight(1f)) {
        SelectionContainer(Modifier.fillMaxSize().padding(end = 12.dp)) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                itemsIndexed(lines) { index, line ->
                    Row(Modifier.fillMaxWidth().background(if (index + 1 == state.previewLine) Palette.Selected else Color.Transparent)) {
                        Text("${index + 1}", Modifier.width(56.dp), color = Palette.SubText, fontSize = 12.sp)
                        Text(line, fontFamily = AppFonts.Mono, fontSize = 13.sp)
                    }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
    if (export) AlertDialog(onDismissRequest = { export = false }, title = { Text("構造情報を YAML に保存") },
        text = { Column {
            Text("元のソースは変更しません。保存先のスキーマに従って構造情報を利用できます。")
            CompactTextField(destination, { destination = it }, singleLine = true)
            error?.let { Text(it, color = Palette.ErrorRed) }
        } }, confirmButton = { TextButton(onClick = {
            state.saveSourceOutline(file, outline, destination).onSuccess { export = false }.onFailure { error = it.message }
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { export = false }) { Text("キャンセル") } })
}
