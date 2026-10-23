package dev.toros.yde.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.AppState
import dev.toros.yde.app.core.FileCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一般ファイルの閲覧。YAML のキャッシュ・索引・履歴には入れない。 */
@Composable
fun FilePreviewPane(state: AppState) {
    val file = state.openPreview ?: return
    var preview by remember(file) { mutableStateOf<FileCatalog.Preview?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var revision by remember(file) { mutableIntStateOf(0) }
    LaunchedEffect(file, revision, state.fileTreeRevision) {
        val result = withContext(Dispatchers.IO) { runCatching { FileCatalog.preview(file) } }
        preview = result.getOrNull(); error = result.exceptionOrNull()?.message
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(state.relPath(file), fontSize = 16.sp)
        Row {
            TextButton(onClick = { revision++ }) { Text("再読込") }
            TextButton(onClick = { openExternally(file.toUri().toString()) }) { Text("外部で開く") }
        }
        Text(error ?: preview?.let { "${it.message}（${it.size} bytes）" } ?: "読み込み中…", color = Palette.SubText)
        val lines = remember(preview) { preview?.text?.lines().orEmpty() }
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(lines) { index, line ->
                    Row(Modifier.fillMaxWidth()) {
                        Text("${index + 1}", Modifier.width(56.dp), color = Palette.SubText, fontSize = 12.sp)
                        Text(line, fontFamily = AppFonts.Mono, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
