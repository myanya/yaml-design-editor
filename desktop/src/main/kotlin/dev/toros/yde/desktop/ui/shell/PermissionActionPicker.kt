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
import androidx.compose.ui.unit.dp
import dev.toros.yde.desktop.ui.common.CompactTextField
import kotlinx.coroutines.CancellationException

/** 定義から得た名前を明示選択する。定義から消えた既存の許可も利用者が外すまで保持する。 */
@Composable
internal fun PermissionActionPicker(initial: Set<String>, load: suspend () -> List<String>, dismiss: () -> Unit, save: (Set<String>) -> Unit) {
    var names by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try { names = load() }
        catch (failure: CancellationException) { throw failure }
        catch (_: Exception) { error = "操作一覧を読み込めませんでした。ワークスペースと保存済みの定義を確認してください。" }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("許可する業務操作") }, text = {
        Column(Modifier.width(500.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("操作名で絞り込み") })
            error?.let { Text(it) }
            if (names == null && error == null) Text("読込中…")
            if (names?.isEmpty() == true && initial.isEmpty()) Text("保存済みの定義に操作がありません。")
            LazyColumn(Modifier.heightIn(max = 350.dp)) {
                items((names.orEmpty() + initial).distinct().sorted().filter { it.contains(query, ignoreCase = true) }, key = { it }) { name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(name in selected, onCheckedChange = { selected = if (it) selected + name else selected - name },
                            modifier = Modifier.semantics { contentDescription = "操作 $name を許可" })
                        Text(name + if (names != null && name !in names.orEmpty()) "（現在の定義にありません）" else "")
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { save(selected) }, enabled = names != null) { Text("選択を反映") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("キャンセル") } })
}
