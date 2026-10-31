package dev.toros.yde.desktop.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.desktop.ui.common.CompactTextField
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.yaml.model.DocumentId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** フォルダーも具体的な文書集合へ展開する。検索・畳み込みは選択済みの集合を書き換えない。 */
internal data class PermissionDocumentRow(val path: String, val documents: Set<DocumentId>, val folder: Boolean) {
    val depth: Int get() = path.trimEnd('/').count { it == '/' }
}

internal fun permissionDocumentRows(documents: Set<DocumentId>, query: String, expanded: Set<String>): List<PermissionDocumentRow> {
    val rows = linkedMapOf<String, MutableSet<DocumentId>>()
    documents.filter { it.value.contains(query.trim(), ignoreCase = true) }.forEach { id ->
        rows.getOrPut(id.value) { linkedSetOf() }.add(id)
        id.value.indices.filter { id.value[it] == '/' }.forEach { position ->
            rows.getOrPut(id.value.substring(0, position + 1)) { linkedSetOf() }.add(id)
        }
    }
    return rows.toSortedMap().mapNotNull { (path, ids) ->
        val parents = path.trimEnd('/').indices.filter { path[it] == '/' }.map { path.substring(0, it + 1) }
        if (query.isNotBlank() || parents.all { it in expanded }) PermissionDocumentRow(path, ids.toSet(), path.endsWith('/')) else null
    }
}

@Composable
internal fun PermissionDocumentPicker(
    label: String, initial: Set<DocumentId>, loadDocuments: suspend () -> List<DocumentId>,
    onDismiss: () -> Unit, onSelect: (Set<DocumentId>) -> Unit,
) {
    var selected by remember { mutableStateOf(initial) }
    var available by remember { mutableStateOf(emptySet<DocumentId>()) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        loading = true; failure = null
        try { available = loadDocuments().toSet() }
        catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            failure = "文書一覧の読込が時間内に完了しませんでした。再読込してください。"
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure = "文書一覧を読み込めませんでした。ワークスペースを確認し、再読込してください。" }
        finally { loading = false }
    }
    val candidates = remember(available, initial) { available + initial }
    val rows = remember(candidates, query, expanded) { permissionDocumentRows(candidates, query, expanded) }
    val matches = remember(candidates, query) { candidates.filter { it.value.contains(query.trim(), ignoreCase = true) }.toSet() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(label) }, text = {
        Column(Modifier.widthIn(min = 320.dp, max = 640.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("保存済みの文書と入力済みの指定を表示します。フォルダーの選択も、現在の文書だけを許可します。", fontSize = 12.sp)
            CompactTextField(query, { query = it }, Modifier.fillMaxWidth().semantics { contentDescription = "許可文書の検索" },
                singleLine = true, placeholder = { Text("相対パスで検索") })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !loading && failure == null, onClick = { selected = selected + matches }) { Text("検索結果をすべて選択") }
                TextButton(enabled = !loading && failure == null, onClick = { selected = selected - matches }) { Text("検索結果の選択を解除") }
                TextButton(enabled = !loading, onClick = { refresh++ }) { Text("一覧を再読込") }
            }
            if (loading) Text("文書一覧を読み込んでいます…", fontSize = 12.sp)
            failure?.let { Text(it, fontSize = 12.sp, color = Palette.ErrorRed) }
            if (!loading && failure == null && rows.isEmpty()) Text("該当する文書はありません。", fontSize = 12.sp)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).semantics { contentDescription = "許可文書の一覧" }) {
                items(rows, key = { it.path }) { row ->
                    val count = row.documents.count { it in selected }
                    Row(Modifier.fillMaxWidth().padding(start = (row.depth.coerceAtMost(6) * 12).dp), verticalAlignment = Alignment.CenterVertically) {
                        if (row.folder) TextButton(onClick = {
                            expanded = if (row.path in expanded) expanded - row.path else expanded + row.path
                        }, modifier = Modifier.width(40.dp).semantics { contentDescription = "フォルダー ${row.path} の展開" }) {
                            Text(if (query.isNotBlank() || row.path in expanded) "−" else "+")
                        } else Spacer(Modifier.width(40.dp))
                        TriStateCheckbox(state = when (count) {
                            0 -> ToggleableState.Off
                            row.documents.size -> ToggleableState.On
                            else -> ToggleableState.Indeterminate
                        }, enabled = !loading && failure == null, onClick = {
                            selected = if (count == row.documents.size) selected - row.documents else selected + row.documents
                        }, modifier = Modifier.semantics { contentDescription = "許可${if (row.folder) "フォルダー" else "文書"} ${row.path}" })
                        Text(if (row.folder) "${row.path}（${row.documents.size} 文書）" else row.path,
                            fontSize = 12.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
            Text("選択中: ${selected.size} 文書。今後追加する文書は含みません。", fontSize = 12.sp)
        }
    }, confirmButton = {
        TextButton(enabled = !loading && failure == null, onClick = { onSelect(selected) }) { Text("選択を反映") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } })
}
