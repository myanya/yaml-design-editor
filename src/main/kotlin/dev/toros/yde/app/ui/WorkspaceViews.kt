package dev.toros.yde.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import dev.toros.yde.app.AppState
import dev.toros.yde.semantic.schema.RefTarget
import dev.toros.yde.semantic.schema.Relations
import java.nio.file.Path

@Composable
fun WorkspaceTableView(state: AppState) {
    state.indexVersion
    // 未保存編集（タイトル・状態変更）も一覧に反映する（UX-18。workspaceRecords は unsavedFirstLoader 済み）
    val records = remember(state.indexVersion, state.editVersion, state.yamlFiles) { state.workspaceRecords() }
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

// ================================================================ グラフレンズ（R23-03。architecture/graph-query.md「エンティティページ」）

/** レンズの1エッジ（エンティティ → エンティティの型付き参照）。 */
private data class LensEdge(val from: String, val to: String, val relation: String?)

/**
 * グラフレンズ: 参照グラフ（旧X-03）の刷新。全件力学配置の一枚絵をやめ、
 * ①エンティティ起点の近傍（深さ1〜2。エンティティページの「グラフで見る」から遷移）
 * ②エンティティ型・relation のチェックボックスで絞った部分グラフ、を力学配置で描く。
 * ノードは EntityGraph のエンティティ（型で色分け）・エッジは relation の label・
 * クリックはエンティティページへ着地する（ナビゲーション履歴 I-08 に乗る）。
 */
@Composable
fun GraphLensView(state: AppState) {
    state.indexVersion
    val overlay = state.overlayIndex()
    val graph = overlay.graph
    // 全エッジ: 解決済みのエンティティ参照（参照を書いた最内のID要素 → 参照先）。自己参照は除く
    val allEdges = remember(state.indexVersion, state.editVersion) {
        overlay.refs.asSequence()
            .filter { it.targetKind == RefTarget.ENTITY }
            .mapNotNull { r ->
                val src = graph.sourceEntryOf(r)?.id ?: return@mapNotNull null
                if (src == r.targetId || !graph.entities.containsKey(r.targetId)) return@mapNotNull null
                LensEdge(src, r.targetId, r.relation)
            }
            .distinct()
            .toList()
    }
    fun typeOf(id: String): String = graph.entities[id]?.entityType ?: "（型なし）"
    val types = remember(allEdges) {
        allEdges.flatMap { listOf(typeOf(it.from), typeOf(it.to)) }.distinct().sorted()
    }
    val relations = remember(allEdges) { allEdges.map { it.relation ?: "（未宣言）" }.distinct().sorted() }
    val typeChecked = remember(types) { mutableStateMapOf<String, Boolean>().apply { types.forEach { put(it, true) } } }
    val relationChecked = remember(relations) { mutableStateMapOf<String, Boolean>().apply { relations.forEach { put(it, true) } } }
    var depth by remember { mutableStateOf(2) }
    val origin = state.lensOriginId?.takeIf { graph.entities.containsKey(it) }

    // 絞り込み: relation → 型 → （起点があれば）近傍のBFS
    val visibleEdges = remember(allEdges, typeChecked.toMap(), relationChecked.toMap(), origin, depth) {
        val byRelation = allEdges.filter { relationChecked[it.relation ?: "（未宣言）"] != false }
        val byType = byRelation.filter { e ->
            // 起点は型フィルタの対象外（起点近傍を見たいのに起点自身が消えると意味を失う）
            (e.from == origin || typeChecked[typeOf(e.from)] != false) &&
                (e.to == origin || typeChecked[typeOf(e.to)] != false)
        }
        if (origin == null) byType
        else {
            val adjacency = mutableMapOf<String, MutableList<String>>()
            byType.forEach { e ->
                adjacency.getOrPut(e.from) { mutableListOf() }.add(e.to)
                adjacency.getOrPut(e.to) { mutableListOf() }.add(e.from)
            }
            val reached = mutableSetOf(origin)
            var frontier = listOf(origin)
            repeat(depth) {
                frontier = frontier.flatMap { adjacency[it].orEmpty() }.filter(reached::add).sorted()
            }
            byType.filter { it.from in reached && it.to in reached }
        }
    }
    val nodes = remember(visibleEdges, origin) {
        (listOfNotNull(origin) + visibleEdges.flatMap { listOf(it.from, it.to) }).distinct().sorted()
    }
    val edgeIndices = remember(nodes, visibleEdges) {
        val indexOf = nodes.withIndex().associate { (i, n) -> n to i }
        visibleEdges.mapNotNull { e ->
            val s = indexOf[e.from] ?: return@mapNotNull null
            val t = indexOf[e.to] ?: return@mapNotNull null
            Triple(s, t, e.relation)
        }
    }
    // 型 → 色（凡例つき）。起点は強調色
    val lensColors = listOf(
        Palette.Primary, Palette.OkGreen, Palette.WarnOrange, Palette.Secondary,
        Color(0xFF9C27B0), Color(0xFF00838F), Color(0xFF6D4C41), Color(0xFF546E7A),
    )
    val typeColors = remember(types) { types.mapIndexed { i, t -> t to lensColors[i % lensColors.size] }.toMap() }

    // パン・ズーム（ドラッグで移動、＋/−ボタンで拡縮）
    var pan by remember(nodes) { mutableStateOf(Offset.Zero) }
    var zoom by remember(nodes) { mutableStateOf(1f) }
    Column(Modifier.fillMaxSize().background(Palette.EditorBg)) {
        Row(Modifier.fillMaxWidth().background(Palette.Surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("グラフレンズ", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
            if (origin != null) {
                Text("起点: $origin", fontSize = 11.sp, color = Palette.Primary, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { depth = if (depth == 1) 2 else 1 },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text("深さ $depth", fontSize = 11.sp)
                }
                TextButton(onClick = { state.lensOriginId = null },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text("起点を解除", fontSize = 11.sp)
                }
            } else {
                Text("起点なし（全体をフィルタ表示。エンティティページの「グラフで見る」で近傍に絞れます）",
                    fontSize = 11.sp, color = Palette.SubText)
            }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(4f) }) { Text("＋", fontSize = 13.sp) }
            TextButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(0.25f) }) { Text("－", fontSize = 13.sp) }
            TextButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text("全体", fontSize = 11.sp) }
            Spacer(Modifier.weight(1f))
            Text("${nodes.size} ノード / ${visibleEdges.size} エッジ", fontSize = 11.sp, color = Palette.SubText)
        }
        // 型・relation のチェックボックス（アドホックなレンズ。宣言的レンズ定義は需要待ち。architecture/graph-query.md「エンティティページ」）
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("型:", fontSize = 10.sp, color = Palette.SubText)
            types.forEach { t ->
                Checkbox(typeChecked[t] != false, { typeChecked[t] = it }, modifier = Modifier.size(26.dp))
                Box(Modifier.size(8.dp).background(typeColors[t] ?: Palette.Primary, PanelShape))
                Text(" " + (state.entityTypes[t]?.displayLabel ?: t), fontSize = 10.sp, color = Palette.SubText)
                Spacer(Modifier.width(6.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text("関係:", fontSize = 10.sp, color = Palette.SubText)
            relations.forEach { rel ->
                Checkbox(relationChecked[rel] != false, { relationChecked[rel] = it }, modifier = Modifier.size(26.dp))
                Text(
                    Relations.labelOf(state.relations, rel.takeIf { it != "（未宣言）" }) ?: rel,
                    fontSize = 10.sp, color = Palette.SubText,
                )
                Spacer(Modifier.width(6.dp))
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(0.58f).padding(horizontal = 12.dp)) {
            val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            // 力学配置（純ロジック・seed固定で決定的）。ノード集合が変わったときだけ再計算する
            val layout = remember(nodes, edgeIndices, widthPx, heightPx) {
                forceLayout(nodes.size, edgeIndices.map { it.first to it.second }, widthPx, heightPx)
            }
            fun toScreen(p: Pair<Float, Float>) = Offset(p.first * zoom + pan.x, p.second * zoom + pan.y)
            val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
            val labelStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, color = Palette.SubText)
            val edgeLabelStyle = androidx.compose.ui.text.TextStyle(fontSize = 8.sp, color = Palette.SubText)
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
                            // ズーム・パンを逆変換して最寄りノードを拾い、エンティティページへ着地する
                            val hit = layout.withIndex().minByOrNull { (_, p) ->
                                (toScreen(p) - tap).getDistanceSquared()
                            } ?: return@detectTapGestures
                            if ((toScreen(hit.value) - tap).getDistance() <= 14.dp.toPx()) {
                                state.openCrossView(nodes[hit.index])
                            }
                        }
                    }
            ) {
                if (nodes.isEmpty()) return@Canvas
                edgeIndices.forEach { (s, t, relation) ->
                    val a = toScreen(layout[s])
                    val b = toScreen(layout[t])
                    drawLine(Palette.Border, a, b, 1.0f)
                    // エッジのラベル（関係レジストリの label。破綻しない規模のときだけ）
                    if (edgeIndices.size <= 60) {
                        Relations.labelOf(state.relations, relation)?.let { label ->
                            drawText(textMeasurer, label, topLeft = (a + b) * 0.5f, style = edgeLabelStyle)
                        }
                    }
                }
                val r = 5.dp.toPx() * zoom.coerceIn(0.6f, 1.6f)
                layout.forEachIndexed { i, p ->
                    val id = nodes[i]
                    val color = typeColors[typeOf(id)] ?: Palette.Primary
                    drawCircle(color, if (id == origin) r * 1.6f else r, toScreen(p))
                }
                // ラベルは描画が破綻しない規模のときだけ表示する
                if (nodes.size <= 120) {
                    layout.forEachIndexed { i, p ->
                        val pos = toScreen(p)
                        drawText(
                            textMeasurer, nodes[i].take(24), topLeft = pos + Offset(r + 2.dp.toPx(), -6.dp.toPx()),
                            style = labelStyle,
                        )
                    }
                }
            }
        }
        Text("エッジ一覧（クリックでエンティティページへ）", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
        Box(Modifier.weight(0.42f).fillMaxWidth()) {
            val scroll = rememberLazyListState()
            LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().padding(end = 8.dp)) {
                items(visibleEdges) { edge ->
                    Row(Modifier.fillMaxWidth().clickable { state.openCrossView(edge.to) }.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(edge.from, fontSize = 11.sp, color = Palette.Primary, modifier = Modifier.width(150.dp))
                        Text(
                            "→ ${edge.to}" +
                                (Relations.labelOf(state.relations, edge.relation)?.let { "（関係: $it）" } ?: ""),
                            fontSize = 11.sp, color = Palette.SubText,
                        )
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}
