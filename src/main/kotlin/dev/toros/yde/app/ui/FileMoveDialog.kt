package dev.toros.yde.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.toros.yde.app.AppState
import dev.toros.yde.workspace.FileArrangementPlan
import dev.toros.yde.yaml.model.pretty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** 移動先と追随する定義を確認して一回で適用する短い作業。 */
@Composable
internal fun FileMoveDialog(state: AppState, paths: List<Path>, rename: Boolean = false, initialDestination: Path? = null, onDismiss: () -> Unit) {
    var destination by remember(paths) { mutableStateOf(if (rename) paths.first().fileName.toString() else state.relPath(initialDestination ?: paths.first().parent)) }
    var definitions by remember { mutableStateOf(true) }
    var permissions by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<FileArrangementPlan?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun changed() { plan = null; error = null }
    val chooseDirectory = rememberDirectoryChooser(FilePickers.Purpose.FILE_FIELD, "移動先", state.workspace,
        onError = { error = it }) { chosen ->
        if (state.workspace?.let { chosen.startsWith(it) } == true) { destination = state.relPath(chosen); changed() }
        else error = "ワークスペース内を選んでください"
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (rename) "名前を変更" else "ファイル・フォルダーを移動") },
        text = {
            Column(Modifier.width(680.dp)) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 100.dp)) {
                    items(paths) { Text(state.relPath(it)) }
                }
                CompactTextField(destination, { if (!busy) { destination = it; changed() } }, singleLine = true,
                    placeholder = { Text(if (rename) "新しい名前" else "移動先フォルダー（相対パス・空欄は直下）") }, modifier = Modifier.fillMaxWidth())
                if (!rename) TextButton(enabled = !busy, onClick = chooseDirectory) { Text("移動先を選ぶ…") }
                Row {
                    Checkbox(definitions, { definitions = it; changed() }, enabled = !busy)
                    Text("スキーマ・ビューなどのパス指定も追随する")
                }
                Row {
                    Checkbox(permissions, { permissions = it; changed() }, enabled = !busy && definitions)
                    Text("台帳範囲・書込許可の修正も含める")
                }
                TextButton(enabled = !busy, onClick = {
                    busy = true; state.fileOperationBusy = true; error = null
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) { runCatching {
                                val root = state.workspace ?: error("ワークスペース未選択")
                                if (rename) state.planFileRename(paths.single(), destination, definitions, permissions)
                                else state.planFileMoves(paths, root.resolve(destination.trim()).normalize(), definitions, permissions)
                            } }
                            plan = result.getOrNull(); error = result.exceptionOrNull()?.message
                        } finally { busy = false; state.fileOperationBusy = false }
                    }
                }) { Text(if (busy) "確認中…" else "影響を確認") }
                plan?.let { preview ->
                    Text("移動 ${preview.moves.size} 件・設定修正 ${preview.edits.size} 件・テキスト修正 ${preview.texts.size} 件")
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(preview.edits) { edit ->
                            Text("${edit.document.value} : ${edit.path.pretty()}")
                            Text("− ${edit.before}", color = Palette.SubText)
                            Text("＋ ${edit.after}", color = Palette.Primary)
                        }
                        items(preview.warnings) { Text(it, color = Palette.WarnOrange) }
                        preview.texts.forEach { edit ->
                            item { Text("${edit.document.value} の修正") }
                            val before = edit.before.lines(); val after = edit.after.lines()
                            items((0 until maxOf(before.size, after.size)).filter { before.getOrNull(it) != after.getOrNull(it) }) { i ->
                                before.getOrNull(i)?.let { Text("${i + 1} − $it", color = Palette.SubText) }
                                after.getOrNull(i)?.let { Text("${i + 1} ＋ $it", color = Palette.Primary) }
                            }
                        }
                    }
                }
                error?.let { Text(it, color = Palette.ErrorRed) }
            }
        },
        confirmButton = {
            TextButton(enabled = plan != null && !busy, onClick = {
                val confirmed = plan ?: return@TextButton
                busy = true; state.fileOperationBusy = true
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { state.applyFileArrangement(confirmed) }
                        if (result.isSuccess) onDismiss() else { error = result.exceptionOrNull()?.message; plan = null }
                    } finally { busy = false; state.fileOperationBusy = false }
                }
            }) { Text("移動して設定を適用") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("キャンセル") } },
    )
}
