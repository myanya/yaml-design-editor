package ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.AppState
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun WorkspaceTableView(state: AppState) {
    state.indexVersion
    val records = remember(state.indexVersion, state.yamlFiles) { state.workspaceRecords() }
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val selected = remember { mutableStateMapOf<Path, Boolean>() }
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
            CompactTextField(query, { query = it }, singleLine = true,
                placeholder = { Text("ID・タイトル・パス", fontSize = 11.sp, color = Palette.SubText) }, modifier = Modifier.width(220.dp))
            Spacer(Modifier.width(6.dp))
            FilterMenu("種別", kind, listOf("") + records.mapNotNull { it.kind }.distinct().sorted()) { kind = it }
            FilterMenu("状態", status, listOf("") + records.mapNotNull { it.status }.distinct().sorted()) { status = it }
            Spacer(Modifier.weight(1f))
            Text("${filtered.size} 件", fontSize = 11.sp, color = Palette.SubText)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("選択: ${selected.count { it.value }}", fontSize = 11.sp, color = Palette.SubText)
            Spacer(Modifier.width(10.dp))
            FilterMenu("変更項目", bulkKey, listOf("status", "owner")) { bulkKey = it }
            CompactTextField(bulkValue, { bulkValue = it }, singleLine = true,
                placeholder = { Text("一括設定する値", fontSize = 11.sp, color = Palette.SubText) }, modifier = Modifier.width(180.dp))
            Spacer(Modifier.width(6.dp))
            Button(enabled = selected.any { it.value } && bulkValue.isNotBlank(), onClick = { confirmBulk = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("一括変更", fontSize = 11.sp) }
        }
        TableHeader()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val listState = rememberLazyListState()
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 8.dp)) {
                items(filtered, key = { it.file.toString() }) { record ->
                    Row(Modifier.fillMaxWidth().clickable { state.openFile(record.file) }
                        .background(if (selected[record.file] == true) Palette.Selected else Color.Transparent)
                        .padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(selected[record.file] == true, { selected[record.file] = it }, modifier = Modifier.size(30.dp))
                        Cell(record.id ?: "—", 120)
                        Cell(record.title ?: "—", 220)
                        Cell(record.status ?: "—", 100)
                        Cell(record.owner ?: "—", 120)
                        Cell(record.kind ?: "—", 110)
                        Text(state.relPath(record.file), fontSize = 11.sp, color = Palette.SubText, modifier = Modifier.weight(1f))
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
    if (confirmBulk) AlertDialog(
        onDismissRequest = { confirmBulk = false },
        title = { Text("${selected.count { it.value }} ファイルを一括変更") },
        text = { Text("$bulkKey を「$bulkValue」に変更して保存します。") },
        confirmButton = { TextButton(onClick = {
            state.bulkUpdate(selected.filterValues { it }.keys, bulkKey, bulkValue)
                .onSuccess { state.status = "$it ファイルを一括変更しました" }
                .onFailure { state.status = it.message ?: "一括変更に失敗しました" }
            selected.clear(); confirmBulk = false
        }) { Text("変更") } },
        dismissButton = { TextButton(onClick = { confirmBulk = false }) { Text("キャンセル") } },
    )
}

@Composable private fun TableHeader() = Row(Modifier.fillMaxWidth().background(Palette.PanelBg).padding(horizontal = 8.dp, vertical = 5.dp)) {
    Spacer(Modifier.width(30.dp)); HeaderCell("ID", 120); HeaderCell("タイトル", 220); HeaderCell("状態", 100)
    HeaderCell("担当", 120); HeaderCell("種別", 110); Text("ファイル", fontSize = 10.sp, fontWeight = FontWeight.Bold)
}
@Composable private fun RowScope.Cell(text: String, width: Int) = Text(text, fontSize = 11.sp, maxLines = 1, modifier = Modifier.width(width.dp))
@Composable private fun RowScope.HeaderCell(text: String, width: Int) = Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(width.dp))

@Composable
private fun FilterMenu(label: String, value: String, values: List<String>, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("$label: ${value.ifBlank { "すべて" }}", fontSize = 11.sp) }
        DropdownMenu(open, { open = false }) { values.forEach { option ->
            DropdownMenuItem(text = { Text(option.ifBlank { "すべて" }) }, onClick = { onChange(option); open = false })
        } }
    }
}

@Composable
fun ReferenceGraphView(state: AppState) {
    state.indexVersion
    var query by remember { mutableStateOf("") }
    val edges = remember(state.indexVersion, query) {
        state.index.refs.filter { query.isBlank() || it.targetId.contains(query, true) || state.relPath(it.file).contains(query, true) }
    }
    val nodes = remember(edges) {
        edges.flatMap { listOf(state.relPath(it.file), it.targetId) }.distinct().take(40)
    }
    Column(Modifier.fillMaxSize().background(Palette.EditorBg)) {
        Row(Modifier.fillMaxWidth().background(Palette.Surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("参照グラフ", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
            CompactTextField(query, { query = it }, singleLine = true,
                placeholder = { Text("ID・参照元を検索", fontSize = 11.sp, color = Palette.SubText) }, modifier = Modifier.width(220.dp))
            Spacer(Modifier.weight(1f)); Text("${nodes.size} ノード / ${edges.size} 参照", fontSize = 11.sp, color = Palette.SubText)
        }
        Canvas(Modifier.fillMaxWidth().height(260.dp).padding(12.dp)) {
            if (nodes.isEmpty()) return@Canvas
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = min(size.width, size.height) * .38f
            val positions = nodes.mapIndexed { i, _ ->
                val angle = 2.0 * PI * i / nodes.size - PI / 2
                Offset(center.x + (cos(angle) * radius).toFloat(), center.y + (sin(angle) * radius).toFloat())
            }
            edges.forEach { edge ->
                val source = nodes.indexOf(state.relPath(edge.file))
                val target = nodes.indexOf(edge.targetId)
                if (source >= 0 && target >= 0) drawLine(Palette.Border, positions[source], positions[target], 1.2f)
            }
            positions.forEachIndexed { i, pos -> drawCircle(if (i % 2 == 0) Palette.Primary else Palette.Secondary, 7.dp.toPx(), pos) }
        }
        Text("ノード凡例", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
        val scroll = rememberLazyListState()
        LazyColumn(state = scroll, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(edges.take(500)) { edge ->
                Row(Modifier.fillMaxWidth().clickable { state.navigateToId(edge.targetId) }.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(edge.targetId, fontSize = 11.sp, color = Palette.Primary, modifier = Modifier.width(150.dp))
                    Text("← ${state.relPath(edge.file)} · ${edge.viaKey}", fontSize = 11.sp, color = Palette.SubText)
                }
            }
        }
    }
}
