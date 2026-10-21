package dev.toros.yde.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.AppState
import java.nio.file.Path

@Composable
fun WorkspaceTableView(state: AppState) {
    state.indexVersion
    // 未保存編集（タイトル・状態変更）も一覧に反映する（UX-18。workspaceRecords は unsavedFirstLoader 済み）
    val records = remember(state.indexVersion, state.editVersion, state.yamlFiles) { state.workspaceRecords() }
    // 絞り込みと一括変更の対象は画面の覚え（ScreenMemory）。候補に無くなった種別・状態は「すべて」として扱い、無くなったファイルは数えない
    val memory = state.memory
    val query = memory.listQuery
    val kinds = remember(records) { listOf("") + records.mapNotNull { it.kind }.distinct().sorted() }
    val statuses = remember(records) { listOf("") + records.mapNotNull { it.status }.distinct().sorted() }
    val kind = memory.listKind.takeIf { it in kinds } ?: ""
    val status = memory.listStatus.takeIf { it in statuses } ?: ""
    val selected = memory.listSelected
    val selectedFiles = remember(records, selected.toMap()) { records.map { it.file }.filter { selected[it] == true } }
    var bulkKey by remember { mutableStateOf("status") }
    var bulkValue by remember { mutableStateOf("") }
    var confirmBulk by remember { mutableStateOf(false) }
    val filtered = remember(records, query, kind, status) {
        records.filter { r ->
            (query.isBlank() || listOf(r.id, r.title, state.relPath(r.file)).any { it?.contains(query, true) == true }) &&
                    (kind.isBlank() || r.kind == kind) && (status.isBlank() || r.status == status)
        }
    }
    Column(Modifier.fillMaxSize().background(Palette.EditorBg)) {
        Row(Modifier.fillMaxWidth().background(Palette.Surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ワークスペース一覧", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
            CompactTextField(query, { memory.listQuery = it }, singleLine = true,
                placeholder = { Text("ID・タイトル・パス") }, modifier = Modifier.width(220.dp))
            Spacer(Modifier.width(6.dp))
            FilterMenu("種別", kind, kinds) { memory.listKind = it }
            FilterMenu("状態", status, statuses) { memory.listStatus = it }
            Spacer(Modifier.weight(1f))
            Text("${filtered.size} 件", fontSize = 11.sp, color = Palette.SubText)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("選択: ${selectedFiles.size}", fontSize = 11.sp, color = Palette.SubText)
            Spacer(Modifier.width(10.dp))
            // 対象キーはワークスペースのルート直下スカラーキーから選ぶ（R8-05: L-07）
            val bulkKeys = remember(records) { state.bulkKeyCandidates() }
            FilterMenu("変更項目", bulkKey, bulkKeys) { bulkKey = it }
            CompactTextField(bulkValue, { bulkValue = it }, singleLine = true,
                placeholder = { Text("一括設定する値") }, modifier = Modifier.width(180.dp))
            Spacer(Modifier.width(6.dp))
            Button(enabled = selectedFiles.isNotEmpty() && bulkValue.isNotBlank(), onClick = { confirmBulk = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("一括変更", fontSize = 11.sp) }
        }
        // 表は共通部品（VirtualTable）: チェックと ID の列を左に固定し、横に長くても ID が見える
        VirtualTable(
            columns = LIST_COLUMNS, rows = filtered, rowKey = { it.file.toString() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            rowPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            rowModifier = { record ->
                Modifier.clickable { state.openFile(record.file) }
                    .background(if (selected[record.file] == true) Palette.Selected else Color.Transparent)
            },
            header = { col -> HeaderCell(col.key.label) },
            cell = { record, col ->
                when (col.key) {
                    ListColumn.CHECK -> Checkbox(selected[record.file] == true, { selected[record.file] = it }, modifier = Modifier.size(30.dp))
                    ListColumn.ID -> Cell(record.id ?: "—")
                    ListColumn.TITLE -> Cell(record.title ?: "—")
                    ListColumn.STATUS -> Cell(record.status ?: "—")
                    ListColumn.OWNER -> Cell(record.owner ?: "—")
                    ListColumn.KIND -> Cell(record.kind ?: "—")
                    ListColumn.FILE -> Text(state.relPath(record.file), fontSize = 11.sp, color = Palette.SubText, maxLines = 1,
                        modifier = Modifier.padding(top = 7.dp))
                }
            },
        )
    }
    if (confirmBulk) AlertDialog(
        onDismissRequest = { confirmBulk = false },
        title = { Text("${selectedFiles.size} ファイルを一括変更") },
        text = { Text("$bulkKey を「$bulkValue」に変更して保存します。") },
        confirmButton = { TextButton(onClick = {
            state.bulkUpdate(selectedFiles.toSet(), bulkKey, bulkValue)
                .onSuccess { state.status = "$it ファイルを一括変更しました" }
                .onFailure { state.status = it.message ?: "一括変更に失敗しました" }
            selected.clear(); confirmBulk = false
        }) { Text("変更") } },
        dismissButton = { TextButton(onClick = { confirmBulk = false }) { Text("キャンセル") } },
    )
}

/** 一覧の列（VirtualTable の列キー）。チェックと ID は左に固定。 */
private enum class ListColumn(val label: String) {
    CHECK(""), ID("ID"), TITLE("タイトル"), STATUS("状態"), OWNER("担当"), KIND("種別"), FILE("ファイル")
}
private val LIST_COLUMNS = listOf(
    TableColumn(ListColumn.CHECK, 30.dp, pinned = true), TableColumn(ListColumn.ID, 120.dp, pinned = true),
    TableColumn(ListColumn.TITLE, 220.dp), TableColumn(ListColumn.STATUS, 100.dp), TableColumn(ListColumn.OWNER, 120.dp),
    TableColumn(ListColumn.KIND, 110.dp), TableColumn(ListColumn.FILE, 420.dp),
)
@Composable private fun Cell(text: String) =
    Text(text, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(end = 6.dp, top = 7.dp))
@Composable private fun HeaderCell(text: String) = Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold)

@Composable
private fun FilterMenu(label: String, value: String, values: List<String>, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("$label: ${value.ifBlank { "すべて" }}", fontSize = 11.sp) }
        DropdownMenu(open, { open = false }) { values.forEach { option ->
            ChoiceMenuItem(selected = option == value, text = { Text(option.ifBlank { "すべて" }) },
                onClick = { onChange(option); open = false })
        } }
    }
}
