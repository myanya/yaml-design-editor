package dev.toros.yde.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.AppState
import dev.toros.yde.semantic.schema.RefTarget
import dev.toros.yde.semantic.schema.Relations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path as FilePath
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ================================================================ グラフレンズ（architecture/graph-query.md「エンティティページ」）

/** レンズの 1 エッジ（エンティティ → エンティティの型付き参照）。 */
private data class LensEdge(val from: String, val to: String, val relation: String?)

/** 絞り込みの結果（表示する節点の ID と、その間の辺）。 */
private data class LensSelection(val nodes: List<String>, val edges: List<LensEdge>)

/** 配置に渡した節点・辺と、その結果。節点の集合が変わって計算し直している間も、前の絵を描き続けるために一緒に持つ。 */
private data class LensArranged(val nodes: List<String>, val edges: List<Triple<Int, Int, String?>>, val result: LensArrangement)

/** 配置の入力（文字を計測した大きさ込み）。変わったときだけ配置を計算し直す。 */
private data class LensInput(val nodes: List<LensNodeSize>, val edges: List<LensEdgeSpec>, val origin: Int?)

/** 節点ごとの隣（節点の添字）とつながる辺（辺の添字）。ホバーの強調に使う。 */
private data class LensAdjacency(val neighbors: List<Set<Int>>, val incident: List<Set<Int>>)

/** 描画に使う色（Palette から 1 度だけ読む。DrawScope の中で Palette を読まない）。 */
private data class LensPaint(val border: Color, val surface: Color, val primary: Color, val subText: Color)

/** 描く材料の一式（画面の Canvas と PNG の書き出しで同じ描き方をする）。 */
private class LensScene(
    val arranged: LensArranged,
    val origin: String?,
    val typeColorOf: (String) -> Color,
    val idTexts: Map<String, TextLayoutResult>,
    val titleTexts: Map<String, TextLayoutResult>,
    val labelTexts: Map<String, TextLayoutResult>,
    val paint: LensPaint,
)

/**
 * グラフレンズ: エンティティグラフの導出表示。
 * ①エンティティ起点の近傍（深さ1〜2。エンティティページの「グラフで見る」から遷移）
 * ②チェックした型のエンティティ（ID 一覧から。参照の有無を問わない）と、その間の参照（relation のチェックで絞る）、
 * を配置（`GraphLayouts`＝放射・階層・応力・力学）して描く。
 * 節点は「ID＋タイトル」の箱（型で色分け）・辺は矢印と関係のラベル。クリックはエンティティページへ着地する（ナビゲーション履歴に乗る）。
 * 操作: ホイールでカーソル中心の拡縮・節点のドラッグ（位置を固定）・ホバーで隣接の強調・右クリックのメニュー・PNG の書き出し・ミニマップ。
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
    // 型の一覧は ID 一覧から（参照を持たない型も出す）。関係の一覧は辺から
    val types = remember(state.indexVersion, state.editVersion) {
        graph.entities.keys.map(::typeOf).distinct().sorted()
    }
    val relations = remember(allEdges) { allEdges.map { it.relation ?: "（未宣言）" }.distinct().sorted() }
    // 型・関係のチェックと深さは画面の覚え（ScreenMemory）。隠した名前だけ持つので、再読込で増えた型は見える側で始まり、無くなった型は読まれない
    val hiddenTypes = state.memory.lensHiddenTypes
    val hiddenRelations = state.memory.lensHiddenRelations
    val depth = state.memory.lensDepth
    val origin = state.lensOriginId?.takeIf { graph.entities.containsKey(it) }

    // 絞り込み: 節点は型で（起点が無ければ、チェックした型のエンティティを ID 一覧から全部。参照を持たない節点も出す）、
    // 辺は relation と両端の型で。起点があれば、そこから辿れる近傍（幅優先）だけに絞る
    val selection = remember(state.indexVersion, state.editVersion, hiddenTypes.toMap(), hiddenRelations.toMap(), origin, depth) {
        // 起点は型フィルタの対象外（起点近傍を見たいのに起点自身が消えると意味を失う）
        fun shown(id: String) = id == origin || typeOf(id) !in hiddenTypes
        val byRelation = allEdges.filter { (it.relation ?: "（未宣言）") !in hiddenRelations }
        val byType = byRelation.filter { shown(it.from) && shown(it.to) }
        if (origin == null) LensSelection(graph.entities.keys.filter(::shown).sorted(), byType)
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
            LensSelection(reached.sorted(), byType.filter { it.from in reached && it.to in reached })
        }
    }
    val nodes = selection.nodes
    val visibleEdges = selection.edges
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
    val paint = LensPaint(Palette.Border, Palette.Surface, Palette.Primary, Palette.SubText)

    // 配置の種類: 既定は起点があれば放射、無ければ応力（節点が多ければ GraphLayouts が階層に代替する）。
    // 利用者が選んだ配置は画面の覚えに起点の有無ごとに名前で持つ（覚えが無い・知らない名前・使えない放射は既定に戻す）
    val layoutKind = state.memory.lensLayoutKind[origin != null]
        ?.let { name -> LensLayoutKind.entries.firstOrNull { it.name == name } }
        ?.takeIf { it != LensLayoutKind.RADIAL || origin != null }
        ?: if (origin != null) LensLayoutKind.RADIAL else LensLayoutKind.STRESS

    // 節点の文字（ID とタイトル）と辺のラベルを計測し、箱の大きさを配置に渡す
    val textMeasurer = rememberTextMeasurer()
    val idStyle = TextStyle(fontSize = 11.sp, color = Palette.Text, fontWeight = FontWeight.SemiBold)
    val titleStyle = TextStyle(fontSize = 10.sp, color = Palette.SubText)
    val edgeLabelStyle = TextStyle(fontSize = 9.sp, color = Palette.SubText)
    val idTexts: Map<String, TextLayoutResult> = remember(nodes, idStyle) { nodes.associateWith { textMeasurer.measure(it, idStyle) } }
    val titleTexts: Map<String, TextLayoutResult> = remember(nodes, titleStyle, state.indexVersion, state.editVersion) {
        nodes.mapNotNull { id ->
            graph.entities[id]?.title?.takeIf { it.isNotBlank() && it != id }?.let { id to textMeasurer.measure(it.take(30), titleStyle) }
        }.toMap()
    }
    val labelTexts: Map<String, TextLayoutResult> = remember(edgeIndices, edgeLabelStyle, state.relations) {
        edgeIndices.mapNotNull { it.third }.distinct()
            .mapNotNull { rel -> Relations.labelOf(state.relations, rel)?.let { rel to textMeasurer.measure(it, edgeLabelStyle) } }
            .toMap()
    }
    val input = remember(nodes, edgeIndices, idTexts, titleTexts, labelTexts, origin) {
        val sizes = nodes.map { id ->
            val idText = idTexts.getValue(id)
            val title = titleTexts[id]
            LensNodeSize(
                id,
                maxOf(idText.size.width, title?.size?.width ?: 0) + 20f,
                idText.size.height + (title?.size?.height ?: 0) + 12f,
            )
        }
        val specs = edgeIndices.map { (s, t, rel) ->
            val label = rel?.let { labelTexts[it] }
            LensEdgeSpec(s, t, label?.let { it.size.width + 6f } ?: 0f, label?.size?.height?.toFloat() ?: 0f)
        }
        LensInput(sizes, specs, origin?.let { nodes.indexOf(it) }?.takeIf { it >= 0 })
    }
    // 配置はバックグラウンドで計算し、できるまでは前の絵を出す（スナップショット状態の直接更新。Dispatchers.Main は使わない）
    var arranged by remember { mutableStateOf<LensArranged?>(null) }
    var arrangedFor by remember { mutableStateOf<Pair<LensInput, LensLayoutKind>?>(null) }
    val computing = nodes.isNotEmpty() && arrangedFor != (input to layoutKind)
    LaunchedEffect(input, layoutKind) {
        val result = withContext(Dispatchers.Default) { GraphLayouts.arrange(input.nodes, input.edges, layoutKind, input.origin) }
        arranged = LensArranged(nodes, edgeIndices, result)
        arrangedFor = input to layoutKind
    }
    // 利用者が動かした節点（添字 → 左上）。配置が変わったら忘れる
    val pins = remember(arranged) { mutableStateMapOf<Int, LensPoint>() }
    val effective: LensArranged? = remember(arranged, pins.toMap()) {
        arranged?.let { a ->
            if (pins.isEmpty()) a
            else a.copy(result = GraphLayouts.withMovedBoxes(a.result, a.edges.map { LensEdgeSpec(it.first, it.second) }, pins.toMap()))
        }
    }
    val adjacency = remember(effective) {
        val a = effective ?: return@remember LensAdjacency(emptyList(), emptyList())
        val neighbors = List(a.nodes.size) { mutableSetOf<Int>() }
        val incident = List(a.nodes.size) { mutableSetOf<Int>() }
        a.edges.forEachIndexed { i, (s, t, _) ->
            if (s in neighbors.indices && t in neighbors.indices) {
                neighbors[s] += t; neighbors[t] += s; incident[s] += i; incident[t] += i
            }
        }
        LensAdjacency(neighbors, incident)
    }
    val scene = remember(effective, origin, typeColors, idTexts, titleTexts, labelTexts, paint) {
        effective?.let { LensScene(it, origin, { id -> typeColors[typeOf(id)] ?: paint.primary }, idTexts, titleTexts, labelTexts, paint) }
    }

    // パン・ズーム（ドラッグで移動、ホイールと＋/−で拡縮、「全体」で収める。新しい配置は全体を収める）
    var pan by remember { mutableStateOf(Offset.Zero) }
    var zoom by remember { mutableStateOf(1f) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    fun zoomAt(pivot: Offset, factor: Float) {
        val next = (zoom * factor).coerceIn(0.05f, 4f)
        pan = pivot - (pivot - pan) * (next / zoom)
        zoom = next
    }
    fun fitAll() {
        val a = effective ?: return
        val fit = fitViewport(a.result.width, a.result.height, canvasSize.width, canvasSize.height)
        zoom = fit.zoom
        pan = Offset(fit.panX, fit.panY)
    }
    var hovered by remember { mutableStateOf<Int?>(null) }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var menu by remember { mutableStateOf<Pair<Int, Offset>?>(null) }
    val latest by rememberUpdatedState(effective)
    fun hitNode(p: Offset): Int? {
        val a = latest ?: return null
        val x = (p.x - pan.x) / zoom
        val y = (p.y - pan.y) / zoom
        return a.result.boxes.indexOfLast { it.contains(x, y) }.takeIf { it >= 0 && it < a.nodes.size }
    }

    // PNG の書き出し（配置の全体を等倍で描く。保存先は OS のダイアログで選ぶ）
    val density = LocalDensity.current
    val saveImage = rememberFileSaver(
        FilePickers.Purpose.EXPORT, "レンズの画像を保存", "png", fallback = state.workspace,
        onError = { state.status = it },
    ) { path ->
        val s = scene
        if (s == null) state.status = "書き出す図がありません"
        else runCatching { exportLensPng(path, s, Palette.EditorBg, density) }
            .onSuccess { state.status = "画像を保存しました: $path" }
            .onFailure { state.status = "画像の保存に失敗しました: ${it.message ?: it::class.simpleName}" }
    }

    Column(Modifier.fillMaxSize().background(Palette.EditorBg)) {
        Row(Modifier.fillMaxWidth().background(Palette.Surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("グラフレンズ", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
            if (origin != null) {
                Text("起点: $origin", fontSize = 11.sp, color = Palette.Primary, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { state.memory.lensDepth = if (depth == 1) 2 else 1 },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text("深さ $depth", fontSize = 11.sp)
                }
                TextButton(onClick = { state.lensOriginId = null },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text("起点を解除", fontSize = 11.sp)
                }
            } else {
                Text("起点なし（全体をフィルタ表示。エンティティページの「グラフで見る」か右クリックで起点に）",
                    fontSize = 11.sp, color = Palette.SubText)
            }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = { zoomAt(Offset(canvasSize.width / 2, canvasSize.height / 2), 1.25f) }) { Text("＋", fontSize = 13.sp) }
            TextButton(onClick = { zoomAt(Offset(canvasSize.width / 2, canvasSize.height / 2), 1 / 1.25f) }) { Text("－", fontSize = 13.sp) }
            TextButton(onClick = { fitAll() }) { Text("全体", fontSize = 11.sp) }
            if (pins.isNotEmpty()) {
                TextButton(onClick = { pins.clear() }) { Text("動かした ${pins.size} 件を戻す", fontSize = 11.sp) }
            }
            TextButton(onClick = { saveImage("lens-" + (origin ?: "all")) }, enabled = scene != null) { Text("PNG に保存", fontSize = 11.sp) }
            Spacer(Modifier.weight(1f))
            Text("${nodes.size} ノード / ${visibleEdges.size} エッジ", fontSize = 11.sp, color = Palette.SubText)
        }
        // 型・relation のチェックボックス（多ければ折り返す。アドホックなレンズ。宣言的レンズ定義は需要待ち。architecture/graph-query.md「エンティティページ」）
        LensFilterRow("型:") {
            types.forEach { t ->
                LensFilterItem(t !in hiddenTypes, { checked -> if (checked) hiddenTypes.remove(t) else hiddenTypes.put(t, true) }) {
                    Box(Modifier.size(8.dp).background(typeColors[t] ?: Palette.Primary, PanelShape))
                    Text(" " + (state.entityTypes[t]?.displayLabel ?: t), fontSize = 10.sp, color = Palette.SubText)
                }
            }
        }
        LensFilterRow("関係:") {
            relations.forEach { rel ->
                LensFilterItem(rel !in hiddenRelations, { checked -> if (checked) hiddenRelations.remove(rel) else hiddenRelations.put(rel, true) }) {
                    Text(
                        Relations.labelOf(state.relations, rel.takeIf { it != "（未宣言）" }) ?: rel,
                        fontSize = 10.sp, color = Palette.SubText,
                    )
                }
            }
        }
        // 配置の切替（放射は起点があるときだけ）。代替した理由と計算中はここに出す
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("配置:", fontSize = 10.sp, color = Palette.SubText)
            Spacer(Modifier.width(4.dp))
            LensLayoutKind.entries.forEach { kind ->
                val enabled = kind != LensLayoutKind.RADIAL || origin != null
                TextButton(
                    onClick = { state.memory.lensLayoutKind[origin != null] = kind.name }, enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(
                        kind.label, fontSize = 11.sp,
                        fontWeight = if (layoutKind == kind) FontWeight.Bold else FontWeight.Normal,
                        color = if (layoutKind == kind) Palette.Primary else if (enabled) Palette.Text else Palette.SubText,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(layoutKind.description, fontSize = 10.sp, color = Palette.SubText)
            arranged?.result?.note?.let { note ->
                Spacer(Modifier.width(8.dp))
                Text(note, fontSize = 10.sp, color = Palette.WarnOrange)
            }
            if (computing) {
                Spacer(Modifier.width(8.dp))
                Text("配置を計算中…", fontSize = 10.sp, color = Palette.Primary)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(0.58f).padding(horizontal = 12.dp)) {
            val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            val current = arranged
            // 新しい配置ができたら全体を収めて見せる（画面の大きさが変わったときも）
            LaunchedEffect(current, widthPx, heightPx) {
                current?.let {
                    val fit = fitViewport(it.result.width, it.result.height, widthPx, heightPx)
                    zoom = fit.zoom
                    pan = Offset(fit.panX, fit.panY)
                }
            }
            Canvas(
                Modifier.fillMaxSize().clipToBounds()
                    .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
                    // ホバー・ホイール・右クリックは最初の経路で見る（タップの検出より先に受け、右クリックは消費してタップにしない）
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull() ?: continue
                                when (event.type) {
                                    PointerEventType.Move -> hovered = hitNode(change.position)
                                    PointerEventType.Exit -> hovered = null
                                    PointerEventType.Scroll -> {
                                        val dy = change.scrollDelta.y
                                        if (dy != 0f) {
                                            zoomAt(change.position, if (dy < 0) 1.15f else 1 / 1.15f)
                                            change.consume()
                                        }
                                    }
                                    PointerEventType.Press -> if (event.buttons.isSecondaryPressed) {
                                        hitNode(change.position)?.let { menu = it to change.position }
                                        change.consume()
                                    }
                                }
                            }
                        }
                    }
                    // ドラッグ: 節点の上なら節点を動かす（位置を固定）、それ以外は全体を動かす
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { start -> dragging = hitNode(start) },
                            onDragEnd = { dragging = null },
                            onDragCancel = { dragging = null },
                        ) { change, amount ->
                            change.consume()
                            val index = dragging
                            val box = index?.let { latest?.result?.boxes?.getOrNull(it) }
                            if (index != null && box != null) pins[index] = LensPoint(box.x + amount.x / zoom, box.y + amount.y / zoom)
                            else pan += amount
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { tap ->
                            // ズーム・パンを逆変換して箱の中を拾い、エンティティページへ着地する
                            val a = latest ?: return@detectTapGestures
                            hitNode(tap)?.let { state.openCrossView(a.nodes[it]) }
                        }
                    }
            ) {
                val s = scene ?: return@Canvas
                translate(pan.x, pan.y) {
                    scale(zoom, pivot = Offset.Zero) {
                        drawLens(s, zoom, hovered, adjacency)
                    }
                }
            }
            if (nodes.isEmpty()) {
                Text("表示するエンティティがありません（型のチェックを増やすか、起点を解除してください）",
                    fontSize = 12.sp, color = Palette.SubText, modifier = Modifier.align(Alignment.Center))
            }
            // ミニマップ: 全体が画面に収まっていないときだけ右下に出す。押した・なぞった場所を画面の中央にする
            val a = effective
            if (a != null && (a.result.width * zoom > widthPx + 1f || a.result.height * zoom > heightPx + 1f)) {
                val mapWidth = with(density) { 200.dp.toPx() }
                val mapHeight = with(density) { 140.dp.toPx() }
                val mapScale = minOf((mapWidth - 8f) / a.result.width, (mapHeight - 8f) / a.result.height)
                val mapOx = (mapWidth - a.result.width * mapScale) / 2
                val mapOy = (mapHeight - a.result.height * mapScale) / 2
                fun centerOn(p: Offset) {
                    val cx = (p.x - mapOx) / mapScale
                    val cy = (p.y - mapOy) / mapScale
                    pan = Offset(widthPx / 2 - cx * zoom, heightPx / 2 - cy * zoom)
                }
                val mapPaint = paint
                Canvas(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).size(200.dp, 140.dp)
                        .background(Palette.Surface.copy(alpha = 0.9f), PanelShape).border(1.dp, Palette.Border, PanelShape)
                        .clipToBounds()
                        .pointerInput(a) { detectTapGestures { p -> centerOn(p) } }
                        .pointerInput(a) { detectDragGestures { change, _ -> change.consume(); centerOn(change.position) } }
                ) {
                    a.result.boxes.forEachIndexed { i, b ->
                        val id = a.nodes.getOrNull(i) ?: return@forEachIndexed
                        drawRect(
                            (typeColors[typeOf(id)] ?: mapPaint.primary).copy(alpha = 0.8f),
                            Offset(mapOx + b.x * mapScale, mapOy + b.y * mapScale),
                            Size(maxOf(2f, b.width * mapScale), maxOf(2f, b.height * mapScale)),
                        )
                    }
                    val vx = -pan.x / zoom
                    val vy = -pan.y / zoom
                    drawRect(
                        mapPaint.primary,
                        Offset(mapOx + vx * mapScale, mapOy + vy * mapScale),
                        Size(widthPx / zoom * mapScale, heightPx / zoom * mapScale),
                        style = Stroke(1.5f),
                    )
                }
            }
            // 右クリックのメニュー（節点の上で開く）
            menu?.let { (index, at) ->
                val id = effective?.nodes?.getOrNull(index)
                if (id == null) menu = null
                else {
                    val entity = graph.entities[id]
                    val origin0 = entity?.defining ?: entity?.occurrences?.firstOrNull()
                    val type = typeOf(id)
                    Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }) {
                        DropdownMenu(expanded = true, onDismissRequest = { menu = null }) {
                            Text(
                                id + (entity?.title?.takeIf { it.isNotBlank() && it != id }?.let { "  $it" } ?: ""),
                                fontSize = 11.sp, color = Palette.SubText, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                            DropdownMenuItem(text = { Text("エンティティページを開く", fontSize = 12.sp) },
                                onClick = { menu = null; state.openCrossView(id) })
                            DropdownMenuItem(text = { Text("起点にする", fontSize = 12.sp) }, enabled = id != origin,
                                onClick = { menu = null; state.lensOriginId = id })
                            if (origin0 != null) {
                                DropdownMenuItem(text = { Text("原本へ（${origin0.document.value}）", fontSize = 12.sp) },
                                    onClick = { menu = null; state.openFile(origin0.document, origin0.path) })
                            }
                            DropdownMenuItem(
                                text = { Text("この型を隠す（${state.entityTypes[type]?.displayLabel ?: type}）", fontSize = 12.sp) },
                                enabled = id != origin && type !in hiddenTypes,
                                onClick = { menu = null; hiddenTypes[type] = true },
                            )
                            if (index in pins) {
                                DropdownMenuItem(text = { Text("動かした位置を戻す", fontSize = 12.sp) }, onClick = { menu = null; pins.remove(index) })
                            }
                        }
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

// ================================================================ 絞り込みのチェックボックス

/**
 * 絞り込みの 1 行（「型:」「関係:」）。項目が多ければ折り返す。
 * 横スクロールにしない: 見えていない項目があることに気づけず、右端が切れたように見える。
 */
@Composable
private fun LensFilterRow(caption: String, items: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(caption, fontSize = 10.sp, color = Palette.SubText, modifier = Modifier.align(Alignment.CenterVertically))
        items()
    }
}

/** 絞り込みの 1 項目（チェックボックスとラベル。折り返しで分かれないように 1 つにまとめる）。 */
@Composable
private fun LensFilterItem(checked: Boolean, onChange: (Boolean) -> Unit, label: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, modifier = Modifier.size(26.dp))
        label()
    }
}

// ================================================================ 描画（画面と PNG で共通）

/**
 * 配置を描く。`zoom` は文字を出すかの判定に使う（縮小すると省く）。
 * `hovered` があれば、その節点と隣だけを濃く、他を薄くする。
 */
private fun DrawScope.drawLens(scene: LensScene, zoom: Float, hovered: Int?, adjacency: LensAdjacency) {
    val a = scene.arranged
    val result = a.result
    val paint = scene.paint
    val neighbors = hovered?.let { adjacency.neighbors.getOrNull(it) }
    val incident = hovered?.let { adjacency.incident.getOrNull(it) }
    fun edgeAlpha(i: Int) = if (incident == null) 1f else if (i in incident) 1f else 0.15f
    fun nodeAlpha(i: Int) = if (hovered == null) 1f else if (i == hovered || neighbors?.contains(i) == true) 1f else 0.25f
    // 辺: 折れ線と矢印。ラベルは読める倍率のときだけ
    result.routes.forEachIndexed { i, route ->
        val pts = route.points
        if (pts.size < 2) return@forEachIndexed
        val emphasized = incident != null && i in incident
        val alpha = edgeAlpha(i)
        val lineColor = if (emphasized) paint.primary else paint.border
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(path, lineColor, alpha = alpha, style = Stroke((if (emphasized) 2.2f else 1.2f) / zoom.coerceIn(0.3f, 1f)))
        val p = pts[pts.size - 2]
        val q = pts.last()
        val length = sqrt((q.x - p.x) * (q.x - p.x) + (q.y - p.y) * (q.y - p.y)).coerceAtLeast(1f)
        val dx = (q.x - p.x) / length
        val dy = (q.y - p.y) / length
        val arrow = Path().apply {
            moveTo(q.x, q.y)
            lineTo(q.x - dx * 9 + dy * 4, q.y - dy * 9 - dx * 4)
            lineTo(q.x - dx * 9 - dy * 4, q.y - dy * 9 + dx * 4)
            close()
        }
        drawPath(arrow, if (emphasized) paint.primary else paint.subText, alpha = alpha)
        if (zoom >= 0.5f) {
            val rel = a.edges.getOrNull(i)?.third ?: return@forEachIndexed
            val text = scene.labelTexts[rel] ?: return@forEachIndexed
            val c = route.label ?: return@forEachIndexed
            val w = text.size.width.toFloat()
            val h = text.size.height.toFloat()
            drawRect(paint.surface, Offset(c.x - w / 2 - 2, c.y - h / 2), Size(w + 4, h), alpha = alpha)
            drawText(text, topLeft = Offset(c.x - w / 2, c.y - h / 2), alpha = alpha)
        }
    }
    // 節点: 型の色で縁取った箱に ID とタイトル。起点は太い縁、ホバー中は太く
    result.boxes.forEachIndexed { i, b ->
        val id = a.nodes.getOrNull(i) ?: return@forEachIndexed
        val color = scene.typeColorOf(id)
        val alpha = nodeAlpha(i)
        val topLeft = Offset(b.x, b.y)
        val size = Size(b.width, b.height)
        val isOrigin = id == scene.origin
        drawRoundRect(color.copy(alpha = if (i == hovered) 0.3f else 0.12f), topLeft, size, CornerRadius(8f), alpha = alpha)
        drawRoundRect(
            if (isOrigin) paint.primary else color, topLeft, size, CornerRadius(8f), alpha = alpha,
            style = Stroke(if (isOrigin || i == hovered) 3.5f else 1.5f),
        )
        if (zoom >= 0.4f) {
            val idText = scene.idTexts[id] ?: return@forEachIndexed
            drawText(idText, topLeft = Offset(b.x + 10f, b.y + 6f), alpha = alpha)
            scene.titleTexts[id]?.let { drawText(it, topLeft = Offset(b.x + 10f, b.y + 6f + idText.size.height), alpha = alpha) }
        }
    }
}

/** 配置の全体を等倍で PNG に書き出す（長辺は 8,000 px まで縮める）。 */
private fun exportLensPng(path: FilePath, scene: LensScene, background: Color, density: Density) {
    val result = scene.arranged.result
    // 動かした節点が左上の外に出ていれば、その分だけ寄せて全部を入れる
    val points = result.routes.flatMap { it.points }
    val minX = minOf(0f, result.boxes.minOfOrNull { it.x } ?: 0f, points.minOfOrNull { it.x } ?: 0f)
    val minY = minOf(0f, result.boxes.minOfOrNull { it.y } ?: 0f, points.minOfOrNull { it.y } ?: 0f)
    val shiftX = if (minX < 0f) 24f - minX else 0f
    val shiftY = if (minY < 0f) 24f - minY else 0f
    val contentWidth = (result.width + shiftX).coerceAtLeast(1f)
    val contentHeight = (result.height + shiftY).coerceAtLeast(1f)
    val scale = minOf(1f, 8000f / maxOf(contentWidth, contentHeight))
    val width = (contentWidth * scale).roundToInt().coerceAtLeast(1)
    val height = (contentHeight * scale).roundToInt().coerceAtLeast(1)
    val bitmap = ImageBitmap(width, height)
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, Size(width.toFloat(), height.toFloat())) {
        drawRect(background)
        scale(scale, pivot = Offset.Zero) {
            translate(shiftX, shiftY) { drawLens(scene, 1f, null, LensAdjacency(emptyList(), emptyList())) }
        }
    }
    val target = if (path.fileName.toString().lowercase().endsWith(".png")) path else path.resolveSibling(path.fileName.toString() + ".png")
    ImageIO.write(bitmap.toAwtImage(), "png", target.toFile())
}
