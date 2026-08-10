package ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.AppState
import java.nio.file.Path

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
                placeholder = { Text("ID・タイトル・パス") }, modifier = Modifier.width(220.dp))
            Spacer(Modifier.width(6.dp))
            FilterMenu("種別", kind, listOf("") + records.mapNotNull { it.kind }.distinct().sorted()) { kind = it }
            FilterMenu("状態", status, listOf("") + records.mapNotNull { it.status }.distinct().sorted()) { status = it }
            Spacer(Modifier.weight(1f))
            Text("${filtered.size} 件", fontSize = 11.sp, color = Palette.SubText)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("選択: ${selected.count { it.value }}", fontSize = 11.sp, color = Palette.SubText)
            Spacer(Modifier.width(10.dp))
            // 対象キーはワークスペースのルート直下スカラーキーから選ぶ（R8-05: L-07）
            val bulkKeys = remember(records) { state.bulkKeyCandidates() }
            FilterMenu("変更項目", bulkKey, bulkKeys) { bulkKey = it }
            CompactTextField(bulkValue, { bulkValue = it }, singleLine = true,
                placeholder = { Text("一括設定する値") }, modifier = Modifier.width(180.dp))
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
        state.overlayIndex().refs.filter { query.isBlank() || it.targetId.contains(query, true) || state.relPath(it.file).contains(query, true) }
    }
    // 全ノードを対象にする（R8-05: L-06。従来の40ノード制限を撤廃）。前半=参照元ファイル、後半=参照先ID
    val fileNodes = remember(edges) { edges.map { state.relPath(it.file) }.distinct().sorted() }
    val idNodes = remember(edges) { edges.map { it.targetId }.distinct().sorted() }
    val nodes = remember(fileNodes, idNodes) { fileNodes + idNodes }
    val edgeIndices = remember(nodes, edges) {
        val indexOf = nodes.withIndex().associate { (i, n) -> n to i }
        edges.mapNotNull { e ->
            val s = indexOf[state.relPath(e.file)] ?: return@mapNotNull null
            val t = indexOf[e.targetId] ?: return@mapNotNull null
            s to t
        }.distinct()
    }
    // パン・ズーム（ドラッグで移動、＋/−ボタンで拡縮）
    var pan by remember(nodes) { mutableStateOf(Offset.Zero) }
    var zoom by remember(nodes) { mutableStateOf(1f) }
    Column(Modifier.fillMaxSize().background(Palette.EditorBg)) {
        Row(Modifier.fillMaxWidth().background(Palette.Surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("参照グラフ", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
            CompactTextField(query, { query = it }, singleLine = true,
                placeholder = { Text("ID・参照元を検索") }, modifier = Modifier.width(220.dp))
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(4f) }) { Text("＋", fontSize = 13.sp) }
            TextButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(0.25f) }) { Text("－", fontSize = 13.sp) }
            TextButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text("全体", fontSize = 11.sp) }
            Spacer(Modifier.weight(1f))
            Text("${nodes.size} ノード / ${edges.size} 参照", fontSize = 11.sp, color = Palette.SubText)
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(Palette.Secondary, PanelShape))
            Text(" 参照元ファイル", fontSize = 10.sp, color = Palette.SubText)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(9.dp).background(Palette.Primary, PanelShape))
            Text(" 参照先ID（クリックで定義へ）", fontSize = 10.sp, color = Palette.SubText)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(0.58f).padding(horizontal = 12.dp)) {
            val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            // 力学配置（純ロジック・seed固定で決定的）。ノード集合が変わったときだけ再計算する
            val layout = remember(nodes, edgeIndices, widthPx, heightPx) {
                forceLayout(nodes.size, edgeIndices, widthPx, heightPx)
            }
            fun toScreen(p: Pair<Float, Float>) = Offset(p.first * zoom + pan.x, p.second * zoom + pan.y)
            val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
            val labelStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, color = Palette.SubText)
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(nodes) {
                        detectDragGestures { change, amount ->
                            change.consume()
                            pan += amount
                        }
                    }
                    .pointerInput(nodes, layout) {
                        detectTapGestures { tap ->
                            // ズーム・パンを逆変換して最寄りノードを拾い、IDノードなら定義へジャンプする
                            val hit = layout.withIndex().minByOrNull { (_, p) ->
                                (toScreen(p) - tap).getDistanceSquared()
                            } ?: return@detectTapGestures
                            if ((toScreen(hit.value) - tap).getDistance() <= 14.dp.toPx() && hit.index >= fileNodes.size) {
                                state.navigateToId(nodes[hit.index])
                            }
                        }
                    }
            ) {
                if (nodes.isEmpty()) return@Canvas
                edgeIndices.forEach { (s, t) ->
                    drawLine(Palette.Border, toScreen(layout[s]), toScreen(layout[t]), 1.0f)
                }
                val r = 5.dp.toPx() * zoom.coerceIn(0.6f, 1.6f)
                layout.forEachIndexed { i, p ->
                    drawCircle(if (i < fileNodes.size) Palette.Secondary else Palette.Primary, r, toScreen(p))
                }
                // ラベルは描画が破綻しない規模のときだけ表示する
                if (nodes.size <= 120) {
                    layout.forEachIndexed { i, p ->
                        val label = nodes[i].substringAfterLast('/').take(24)
                        val pos = toScreen(p)
                        drawText(
                            textMeasurer, label, topLeft = pos + Offset(r + 2.dp.toPx(), -6.dp.toPx()),
                            style = labelStyle,
                        )
                    }
                }
            }
        }
        Text("参照一覧（クリックで定義へ）", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
        Box(Modifier.weight(0.42f).fillMaxWidth()) {
            val scroll = rememberLazyListState()
            LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().padding(end = 8.dp)) {
                items(edges) { edge ->
                    Row(Modifier.fillMaxWidth().clickable { state.navigateToId(edge.targetId) }.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(edge.targetId, fontSize = 11.sp, color = Palette.Primary, modifier = Modifier.width(150.dp))
                        Text(
                            // 被参照方向は inverseLabel（R20-02。未宣言 relation は名前のまま）
                            "← ${state.relPath(edge.file)} · ${edge.viaKey}" +
                                (schema.Relations.inverseLabelOf(state.relations, edge.relation)?.let { "（関係: $it）" } ?: ""),
                            fontSize = 11.sp, color = Palette.SubText,
                        )
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}
