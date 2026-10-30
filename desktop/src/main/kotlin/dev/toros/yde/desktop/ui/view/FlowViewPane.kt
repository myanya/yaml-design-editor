package dev.toros.yde.desktop.ui.view

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.core.Views
import dev.toros.yde.app.index.DocScanner
import dev.toros.yde.semantic.*
import dev.toros.yde.view.flow.*
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.*
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.OpenDocument
import dev.toros.yde.desktop.ui.common.ChoiceMenu
import dev.toros.yde.desktop.ui.common.ChoiceOption
import dev.toros.yde.desktop.ui.common.GraphLayouts
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PanelShape
import dev.toros.yde.desktop.ui.common.fitViewport
import dev.toros.yde.desktop.ui.common.slotAt
import dev.toros.yde.desktop.ui.form.FieldRow
import dev.toros.yde.desktop.ui.form.FormCtx
import dev.toros.yde.desktop.ui.form.GenericEditor
import dev.toros.yde.desktop.ui.form.ObjectFields

/** 定義と原本を同じ未保存優先loaderで解決する。 */
@Composable
private fun flowCatalog(state: AppState): Views.LoadResult {
    val ws = state.workspace
    return remember(ws, state.indexVersion, state.editVersion) {
        if (ws == null) Views.LoadResult(emptyList(), emptyList(), emptyList())
        else Views.load(ws, state.schemas, state.unsavedFirstTree())
    }
}

@Composable
fun FlowViewPane(state: AppState) {
    val catalog = flowCatalog(state)
    val view = state.flowViewId?.let { catalog.extensionView(it) as? VirtualFlowDef }
    Column(Modifier.fillMaxSize().background(Palette.EditorBg).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlowChooser("フロービュー", view?.id, catalog.extensionViews.filterIsInstance<VirtualFlowDef>().map { it.id to it.name }) {
                state.openFlowView(it)
            }
            if (view != null) {
                val candidates = state.yamlFiles.map(state::documentOf)
                    .filter { view.appliesTo(it, state.schemaFor(it)?.id) }.sortedBy { it.value }
                Spacer(Modifier.width(8.dp))
                FlowChooser("対象文書", state.flowDocument?.value, candidates.map { it.value to it.value }) {
                    state.openFlowView(view.id, DocumentId(it))
                }
                // 候補が1件のときだけ選択を補う。複数件で先頭を暗黙に開かない。
                LaunchedEffect(view.id, candidates, state.flowDocument) {
                    if (state.flowDocument == null && candidates.size == 1) {
                        state.flowDocument = candidates.single()
                    }
                }
            }
        }
        if (catalog.errors.isNotEmpty()) Text(catalog.errors.joinToString("\n"), color = Palette.WarnOrange, fontSize = 11.sp)
        val document = state.flowDocument
        if (view == null || document == null) {
            Text("フロービューと対象文書を選択してください", fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        } else {
            key(view.id, document) {
                FlowPane(state, state.ensureDoc(document), view, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun FlowChooser(
    label: String, selected: String?, items: List<Pair<String, String>>,
    buttonText: String? = null, onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(buttonText ?: items.firstOrNull { it.first == selected }?.second ?: selected ?: "$label を選択", fontSize = 12.sp)
        }
        ChoiceMenu(expanded, { expanded = false }, items.map { (id, name) ->
            ChoiceOption(id, name, subtitle = id.takeIf { it != name }.orEmpty(), selected = id == selected)
        }, onSelect)
    }
}

@Composable
fun EmbeddedFlowPane(state: AppState, doc: OpenDocument, viewId: String) {
    val catalog = flowCatalog(state)
    val view = catalog.extensionView(viewId) as? VirtualFlowDef
    if (view == null) {
        Text("フロービュー「$viewId」が見つからないか、IDが重複しています", color = Palette.ErrorRed, fontSize = 12.sp)
        catalog.errors.forEach { Text(it, color = Palette.WarnOrange, fontSize = 11.sp) }
        catalog.docViews.filter { parent -> parent.sections.any { it.embed?.kind == FlowViewKind.id && it.embed?.view == viewId } }.forEach { parent ->
            TextButton(onClick = { state.openFile(parent.source) }) { Text("埋め込み元のビュー定義") }
        }
        return
    }
    Column(Modifier.fillMaxWidth().border(1.dp, Palette.Border, PanelShape).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(view.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { state.openFlowView(view.id, state.documentOf(doc.file)) }) { Text("拡大表示") }
        }
        key(view.id, doc) { FlowPane(state, doc, view, Modifier.fillMaxWidth().height(520.dp)) }
    }
}

@Composable
private fun FlowPane(state: AppState, doc: OpenDocument, view: VirtualFlowDef, modifier: Modifier) {
    doc.version
    val document = state.documentOf(doc.file)
    val definition = state.ensureDoc(view.source)
    definition.version
    val resolution = remember(doc, doc.version, view, state.indexVersion, state.editVersion) {
        (Views.resolveExtension(view, null, state.overlayIndex(), state.unsavedFirstTree(), state::schemaFor, document) as FlowViewResult).resolution
    }
    val selection = state.flowSelections[view.id to document]
    val model = resolution.model
    val displayKey = view.id to document
    val display = state.flowDisplayOptions[displayKey] ?: FlowDisplayOptions()
    val selected = model?.let { selection?.resolve(view.id, document, it, doc.flowRevision, definition.flowRevision) }
    LaunchedEffect(selection, selected) {
        if (selection != null && selected == null) state.selectFlow(view.id, document, null)
    }
    val choose: (FlowElementKind, YPath) -> Unit = { kind, output ->
        val key = if (kind == FlowElementKind.NODE) model?.nodes?.find { it.output == output }?.key
            else model?.edges?.find { it.output == output }?.key
        state.selectFlow(view.id, document,
            FlowSelection(view.id, document, kind, key, output, doc.flowRevision, definition.flowRevision))
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ノード・辺をクリックで詳細／ドラッグで移動", fontSize = 11.sp, color = Palette.SubText,
                modifier = Modifier.weight(1f))
            TextButton(onClick = { state.openFile(view.source) }) { Text("ビュー定義", fontSize = 11.sp) }
            TextButton(onClick = state::saveAll, enabled = !state.readOnlyMode && state.hasDirtyDocs()) {
                Text("すべて保存", fontSize = 11.sp)
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            FlowChooser("レイアウト", display.mode.name, FlowLayoutMode.entries.map { it.name to it.label },
                buttonText = "レイアウト: ${display.mode.label}") {
                state.flowDisplayOptions = state.flowDisplayOptions + (displayKey to display.copy(mode = FlowLayoutMode.valueOf(it)))
            }
            Spacer(Modifier.width(8.dp))
            val directions = listOf("default" to "ビュー定義に従う", "down" to "上から下", "right" to "左から右")
            FlowChooser("方向", display.direction ?: "default", directions,
                buttonText = "方向: ${directions.first { it.first == (display.direction ?: "default") }.second}") {
                state.flowDisplayOptions = state.flowDisplayOptions + (displayKey to display.copy(direction = it.takeUnless { it == "default" }))
            }
        }
        if (display.mode == FlowLayoutMode.SWIMLANES && model != null) {
            Text(if (model.lanes.isEmpty()) "レーンの定義がないため、担当別の帯は表示されません。"
                else "レーンは定義順に表示します。担当未設定の工程はレーン外に配置します。",
                fontSize = 11.sp, color = Palette.SubText)
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (model != null) FlowCanvas(model, display.directionOrDefault(view.direction), display.mode,
                selected, Modifier.weight(1f).fillMaxHeight(), choose)
            else Text("フローを表示できません", color = Palette.ErrorRed, modifier = Modifier.weight(1f))
            Column(Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(8.dp)) {
                val detail = when (selection?.kind) {
                    FlowElementKind.NODE -> model?.nodes?.find { it.output == selected }?.detail
                    FlowElementKind.EDGE -> model?.edges?.find { it.output == selected }?.detail
                    null -> null
                }
                if (detail != null) FlowDetailForm(state, detail) else Text("要素を選択してください", fontSize = 12.sp)
                if (resolution.diagnostics.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("診断", fontSize = 13.sp, color = Palette.WarnOrange)
                    resolution.diagnostics.forEach { diagnostic ->
                        Text(diagnostic.message, fontSize = 11.sp, color = Palette.WarnOrange)
                        diagnostic.locations.forEach { location ->
                            TextButton(onClick = { state.openFile(location.document, location.path) }) {
                                Text("${location.document.value} :: ${location.path.pretty()}", fontSize = 10.sp)
                            }
                        }
                    }
                }
                // key欠落・重複でも選択の対象から消さず、原本へ修正できる導線を残す。
                model?.nodes?.filter { n -> n.key.isNullOrBlank() || model.nodes.count { it.key == n.key } > 1 }
                    ?.forEach { n -> n.detail.origins.forEach { origin ->
                        TextButton(onClick = { state.openFile(origin.document, origin.path) }) {
                            Text("${n.label ?: n.output.pretty()} の原本", fontSize = 11.sp)
                        }
                    } }
            }
        }
    }
}

@Composable
private fun FlowDetailForm(state: AppState, detail: FlowDetail) {
    val origin = detail.origins.singleOrNull()
    if (!detail.editable || origin == null) {
        Text("導出された詳細（閲覧専用）", fontSize = 12.sp, color = Palette.SubText)
        Text(detail.value?.let(YamlIO::dumpPlain) ?: "詳細なし", fontSize = 11.sp)
    } else {
        val doc = state.ensureDoc(origin.document)
        doc.version
        val root = doc.root
        val slot = root?.let { slotAt(it, origin.path) }
        if (root != null && slot != null) {
            val ids = remember(doc, doc.version) { DocScanner.scan(root, origin.document, doc.schema).first }
            val ctx = FormCtx(state, doc, ids, baseDepth = origin.path.size)
            val field = doc.schema?.let { Views.fieldAt(it, root, origin.path) }
            val node = slot.get()
            if (field?.type == FieldType.OBJECT && node is YNode.Map) ObjectFields(ctx, field.fields, node, origin.path)
            else if (field != null) FieldRow(ctx, field, slot, origin.path)
            else GenericEditor(ctx, slot, origin.path, refHint = false)
        } else Text("原本の位置が見つかりません", color = Palette.WarnOrange)
    }
    detail.origins.forEach { location ->
        TextButton(onClick = { state.openFile(location.document, location.path) }) {
            Text("原本へ: ${location.path.pretty()}", fontSize = 11.sp)
        }
    }
}

@Composable
private fun FlowCanvas(
    model: FlowModel, direction: String, mode: FlowLayoutMode, selected: YPath?, modifier: Modifier,
    choose: (FlowElementKind, YPath) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 12.sp, color = Palette.Text)
    val texts = remember(model, style, measurer) {
        (model.nodes.map { it.output to (it.label ?: "（名称なし）") } +
            model.edges.map { it.output to (it.label ?: it.key ?: "（遷移）") } +
            model.lanes.map { it.output to (it.label ?: "（レーン名なし）") })
            .associate { (p, text) -> p to measurer.measure(text, style, constraints = Constraints(maxWidth = 220)) }
    }
    var layout by remember(model, direction, mode, texts) { mutableStateOf<Result<FlowArrangement>?>(null) }
    LaunchedEffect(model, direction, mode, texts) {
        layout = withContext(Dispatchers.Default) {
            runCatching { GraphLayouts.arrangeFlow(model,
                texts.mapValues { FlowSize(it.value.size.width.toFloat(), it.value.size.height.toFloat()) }, direction, mode) }
        }
    }
    val arrangement = layout?.getOrNull()
    if (arrangement == null) {
        Text(layout?.exceptionOrNull()?.let { "配置を計算できません: ${it.message}" } ?: "フローを配置しています…",
            modifier = modifier.padding(16.dp), color = if (layout == null) Palette.SubText else Palette.ErrorRed)
        return
    }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    fun fit() {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return
        val viewport = fitViewport(arrangement.size.width, arrangement.size.height, canvasSize.width, canvasSize.height)
        zoom = viewport.zoom
        pan = Offset(viewport.panX, viewport.panY)
    }
    LaunchedEffect(arrangement, canvasSize) { fit() }
    val latestChoose by rememberUpdatedState(choose)
    Column(modifier) {
        Row {
            TextButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(0.02f) }) { Text("−") }
            TextButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(3f) }) { Text("＋") }
            TextButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text("表示をリセット") }
            TextButton(onClick = { fit() }) { Text("全体") }
            Text("${(zoom * 100).toInt()}%", fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
        }
        val border = Palette.Border
        val primary = Palette.Primary
        val surface = Palette.Surface
        val selectedColor = Palette.Selected
        val subtext = Palette.SubText
        Canvas(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Palette.PanelBg)
            .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); pan += drag } }
            .pointerInput(arrangement, zoom, pan) { detectTapGestures { at ->
                val p = FlowPoint((at.x - pan.x) / zoom, (at.y - pan.y) / zoom)
                val node = arrangement.nodes.entries.lastOrNull { it.value.contains(p) }
                if (node != null) latestChoose(FlowElementKind.NODE, node.key)
                else arrangement.edges.lastOrNull { route ->
                    route.label.contains(p) || route.points.zipWithNext().any { (a, b) -> nearSegment(p, a, b, 8f / zoom) }
                }?.let { latestChoose(FlowElementKind.EDGE, it.output) }
            } }) {
            translate(pan.x, pan.y) { scale(zoom, pivot = Offset.Zero) {
                arrangement.lanes.forEach { (path, r) ->
                    drawRect(border, Offset(r.x, r.y), Size(r.width, r.height), style = Stroke(1f))
                    val header = arrangement.laneHeaders[path]
                    texts[path]?.let { drawText(it, topLeft = Offset(header?.x ?: (r.x + 8f), header?.y ?: (r.y + 8f))) }
                }
                arrangement.edges.forEach { route ->
                    val color = if (route.output == selected) primary else subtext
                    route.points.zipWithNext().forEach { (a, b) -> drawLine(color, Offset(a.x, a.y), Offset(b.x, b.y), 2f) }
                    if (route.points.size >= 2) {
                        val b = route.points.last(); val a = route.points[route.points.lastIndex - 1]
                        val length = sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)).coerceAtLeast(1f)
                        val dx = (b.x - a.x) / length; val dy = (b.y - a.y) / length
                        drawLine(color, Offset(b.x, b.y), Offset(b.x - dx * 10 + dy * 5, b.y - dy * 10 - dx * 5), 2f)
                        drawLine(color, Offset(b.x, b.y), Offset(b.x - dx * 10 - dy * 5, b.y - dy * 10 + dx * 5), 2f)
                    }
                }
                // 後続の接続線で条件の文字を上書きしないよう、ラベルは線の後にまとめて描く。
                arrangement.edges.forEach { route ->
                    val r = route.label
                    drawRect(if (route.output == selected) selectedColor else surface, Offset(r.x, r.y), Size(r.width, r.height))
                    texts[route.output]?.let { drawText(it, topLeft = Offset(r.x + 6, r.y + 6)) }
                }
                model.nodes.forEach { n ->
                    val r = arrangement.nodes.getValue(n.output)
                    val fill = if (n.output == selected) selectedColor else surface
                    val outline = if (n.output == selected) primary else border
                    val offset = Offset(r.x, r.y); val size = Size(r.width, r.height)
                    when (n.shape) {
                        "diamond" -> {
                            val path = Path().apply { moveTo(r.center.x, r.y); lineTo(r.right, r.center.y)
                                lineTo(r.center.x, r.bottom); lineTo(r.x, r.center.y); close() }
                            drawPath(path, fill); drawPath(path, outline, style = Stroke(2f))
                        }
                        "rounded" -> {
                            drawRoundRect(fill, offset, size, CornerRadius(20f))
                            drawRoundRect(outline, offset, size, CornerRadius(20f), style = Stroke(2f))
                        }
                        else -> { drawRect(fill, offset, size); drawRect(outline, offset, size, style = Stroke(2f)) }
                    }
                    texts[n.output]?.let { drawText(it, topLeft = Offset(r.center.x - it.size.width / 2, r.center.y - it.size.height / 2)) }
                }
            } }
        }
    }
}

private fun nearSegment(p: FlowPoint, a: FlowPoint, b: FlowPoint, tolerance: Float): Boolean {
    val dx = b.x - a.x; val dy = b.y - a.y
    val length = dx * dx + dy * dy
    val t = if (length == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / length).coerceIn(0f, 1f)
    val x = p.x - a.x - t * dx; val y = p.y - a.y - t * dy
    return x * x + y * y <= tolerance * tolerance
}

/** フローの描画（ViewRenderers の一覧に並ぶ）。フローは文書に縛られるので、台帳の「フロー」タブと文書ビューの区画で描く。 */
internal object FlowRenderer : ViewRenderer {
    override val kind: String = FlowViewKind.id

    override fun choiceName(def: ExtensionViewDef): String = def.name

    override fun choiceSubtitle(def: ExtensionViewDef): String = "${def.id} — ${def.listingText()}"

    @Composable
    override fun Content(state: AppState, result: ExtensionViewResult, place: ViewPlace, onOpenEntity: ((String) -> Unit)?) {
        val flow = result as FlowViewResult
        EmbeddedFlowPane(state, state.ensureDoc(flow.document), flow.def.id)
    }

    @Composable
    override fun Section(state: AppState, loaded: Views.LoadResult, def: ExtensionViewDef, doc: OpenDocument) {
        EmbeddedFlowPane(state, doc, def.id)
    }
}
