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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.core.Views
import dev.toros.yde.app.index.DocScanner
import dev.toros.yde.semantic.*
import dev.toros.yde.app.view.flow.*
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.OpenDocument
import dev.toros.yde.desktop.DiagramContext
import dev.toros.yde.desktop.DiagramNodeId
import dev.toros.yde.desktop.sameDiagramPositions
import dev.toros.yde.desktop.diagramPositions
import dev.toros.yde.desktop.saveDiagramPositions
import dev.toros.yde.desktop.reloadDiagramPositions
import dev.toros.yde.desktop.ui.common.*
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
    // ダッシュボードの業務の流れ（flow）も同じ一覧から選び、箱の位置を編集する（対象文書は無い）
    val dashboards = catalog.dashboards.filter { it.flow != null }
    val dashboard = if (view == null) state.flowViewId?.let { id -> dashboards.firstOrNull { it.id == id } } else null
    Column(Modifier.fillMaxSize().background(Palette.EditorBg).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlowChooser("フロービュー", view?.id ?: dashboard?.id, catalog.extensionViews.filterIsInstance<VirtualFlowDef>().map { it.id to it.name } +
                dashboards.map { it.id to "業務の流れ: ${it.name}" }) {
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
        if (dashboard != null) {
            key(dashboard.id) { DashboardFlowEditor(state, dashboard, catalog, Modifier.weight(1f).fillMaxWidth()) }
            return@Column
        }
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
        (Views.resolveExtension(view, state.workspace?.let { Views.load(it, state.schemas, state.unsavedFirstTree()) }, state.overlayIndex(), state.unsavedFirstTree(), state::schemaFor, document) as FlowViewResult).resolution
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
            Text("枠・辺で詳細／ノードをドラッグで固定／背景をドラッグで画面移動", fontSize = 11.sp, color = Palette.SubText,
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
            Spacer(Modifier.width(8.dp))
            DiagramLayoutOptionsMenu(FlowLayoutOption.entries.map { DiagramLayoutChoice(it.key, it.label, it.description, it in display.options) }) { key, checked ->
                val option = FlowLayoutOption.entries.first { it.key == key }
                val current = state.flowDisplayOptions[displayKey] ?: display
                state.flowDisplayOptions = state.flowDisplayOptions + (displayKey to current.copy(
                    options = if (checked) current.options + option else current.options - option))
            }
            DiagramLabelToggle(!display.hideLabels) { shown ->
                val current = state.flowDisplayOptions[displayKey] ?: display
                state.flowDisplayOptions = state.flowDisplayOptions + (displayKey to current.copy(hideLabels = !shown))
            }
        }
        if (display.mode == FlowLayoutMode.SWIMLANES && model != null) {
            Text(if (model.lanes.isEmpty()) "レーンの定義がないため、担当別の帯は表示されません。"
                else "レーンは定義順に表示します。担当未設定の工程はレーン外に配置します。",
                fontSize = 11.sp, color = Palette.SubText)
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (model != null) FlowCanvas(state, model, display.directionOrDefault(view.direction), display.mode,
                selected, Modifier.weight(1f).fillMaxHeight(), DiagramContext(
                    mapOf("kind" to "flow", "view" to view.id, "document" to document.value),
                    withLayoutOptionSelection(
                        mapOf("mode" to listOf(display.mode.name.lowercase()), "direction" to listOf(display.directionOrDefault(view.direction))),
                        display.options.map { it.key })), display.options, display.hideLabels, choose = choose)
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
internal fun FlowCanvas(
    state: AppState, model: FlowModel, direction: String, mode: FlowLayoutMode, selected: YPath?, modifier: Modifier,
    context: DiagramContext,
    options: Set<FlowLayoutOption> = emptySet(),
    hideLabels: Boolean = false,
    /**
     * 位置の記録を書くか。false でも同じ図の記録を読んで同じ位置で描くが、記録は書かず（初回の自動配置も保存しない）、
     * ドラッグは画面の移動だけで、配置の道具も出さない（ダッシュボードの業務の流れの表示。編集は台帳のフロータブ。
     * architecture/dashboards.md「業務の流れ」）。
     */
    editable: Boolean = true,
    choose: (FlowElementKind, YPath) -> Unit,
) {
    val memory = remember(state.workspace, context) { state.diagramPositions(context) }
    // 記録へ書けるのは編集できる図だけ（読むだけの図は同じ記録を共有して表示する）
    val writable = editable && memory.writable
    val density = LocalDensity.current
    val ids = remember(model) { flowPositionIds(model) }
    val frames = remember(model, mode) { flowPositionFrames(model, mode) }
    var temporary by remember(model, context) { mutableStateOf<Map<YPath, FlowPoint>>(emptyMap()) }
    val retained = remember(memory.positions, ids, frames, density.density, temporary) {
        ids.mapNotNull { (path, id) -> memory.positions[id]?.takeIf { path in frames && it.frame == frames[path] }
            ?.let { path to FlowPoint(it.x * density.density, it.y * density.density) } }.toMap() + temporary
    }
    val fixed = retained.filterKeys { memory.positions[ids[it]]?.pinned == true || it in temporary }
    val lanePositions = flowLaneIds(model).mapNotNull { (path, id) -> memory.positions[id]?.takeIf { it.frame == null }
        ?.let { path to FlowPoint(it.x * density.density, it.y * density.density) } }.toMap()
    var preview by remember(model, context) { mutableStateOf<Pair<YPath, FlowPoint>?>(null) }
    var dragging by remember(model, context) { mutableStateOf<YPath?>(null) }
    var dragMotion by remember(model, context) { mutableStateOf<DiagramDrag?>(null) }
    var cancelled by remember(context) { mutableStateOf(false) }
    // 線の経由点（ADR-0028）: ラベルをドラッグ中の線と、その間だけの経由点（離すと記録へ）
    val edgeIds = remember(model) { flowEdgeIds(model) }
    var draggingLabel by remember(model, context) { mutableStateOf<YPath?>(null) }
    var labelPreview by remember(model, context) { mutableStateOf<Pair<YPath, FlowPoint>?>(null) }
    val storedVias = remember(model, memory.positions, density.density) { flowEdgeVias(model, memory.positions, density.density) }
    val vias = storedVias + listOfNotNull(labelPreview)
    LaunchedEffect(memory.saving, memory.warning) {
        if (!memory.saving && memory.warning != null) { preview = null; labelPreview = null }
    }
    // 記録が確定したら、離した位置の見せかけを外す（記録の経由点で同じ形に描かれる）
    LaunchedEffect(storedVias, memory.saving) {
        val shown = labelPreview ?: return@LaunchedEffect
        if (draggingLabel == null && !memory.saving && storedVias[shown.first] != null) labelPreview = null
    }
    val focus = remember { FocusRequester() }
    val renderings = remember(model) { model.nodes.mapNotNull { n -> n.rendering?.let { n.output to it } }.toMap() }
    val bodies = rememberDiagramBodies(renderings, state)
    val edgeStyles = remember(model) { model.edges.mapNotNull { e -> e.rendering?.edge?.let { e.output to it.copy(label = it.label ?: e.label ?: e.key ?: "（遷移）") } }.toMap() }
    val edgeVisuals = rememberDiagramEdges(edgeStyles)
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 12.sp, color = Palette.Text)
    val texts = remember(model, style, measurer) {
        (model.nodes.map { it.output to (it.label ?: "（名称なし）") } +
            model.edges.map { it.output to (edgeStyles[it.output]?.label ?: it.label ?: it.key ?: "（遷移）") } +
            model.lanes.map { it.output to (it.label ?: "（レーン名なし）") })
            .associate { (p, text) -> p to measurer.measure(text, style, constraints = Constraints(maxWidth = 220)) }
    }
    val labelMeasures = remember(model, texts, edgeVisuals, measurer) { measureFlowLabels(model, texts, edgeVisuals, measurer) }
    val labelSizes = remember(texts, labelMeasures) {
        (texts + labelMeasures.mapValues { it.value.first() }).mapValues { FlowSize(it.value.size.width.toFloat(), it.value.size.height.toFloat()) }
    }
    var layout by remember(context) { mutableStateOf<Result<FlowArrangement>?>(null) }
    var computing by remember(context) { mutableStateOf(false) }
    var rearrange by remember(context) { mutableStateOf(0) }
    var appliedRearrange by remember(context) { mutableStateOf(0) }
    val input = listOf(model, direction, mode, labelSizes, bodies, options)
    var layoutFor by remember(context) { mutableStateOf<List<Any>?>(null) }
    fun snapshot(a: FlowArrangement, pin: YPath? = null) =
        flowPositionSnapshot(model, mode, direction, labelSizes, a, density.density, memory.positions, pin)
    LaunchedEffect(input, retained, lanePositions, memory.positions, memory.ready, rearrange) {
        if (!memory.ready) { computing = false; return@LaunchedEffect }
        val reflow = rearrange != appliedRearrange
        val current = layout?.getOrNull()
        val candidate = current?.let { a -> preview?.let { flowDragPreview(a, it.first, it.second, model, direction) } ?: a }
        // ドラッグの確定と固定解除は位置を計算しない。既存の辺もそのまま使う。
        if (!reflow && layoutFor == input && candidate != null && sameDiagramPositions(snapshot(candidate), memory.positions)) {
            layout = Result.success(candidate); preview = null; computing = false
            return@LaunchedEffect
        }
        computing = true
        layout = withContext(Dispatchers.Default) {
            runCatching { GraphLayouts.arrangeFlow(model,
                labelSizes, direction, mode, bodies.mapValues { FlowSize(it.value.size.width, it.value.size.height) },
                if (reflow) fixed else retained, if (reflow) emptyMap() else lanePositions, options) }
        }
        layoutFor = input; appliedRearrange = rearrange
        if (reflow && editable) layout?.getOrNull()?.let { state.saveDiagramPositions(context, memory.positions + snapshot(it)) }
        computing = false
        if (dragging == null) preview = null
    }
    val base = layout?.getOrNull()
    LaunchedEffect(base, memory.ready, memory.saving, memory.positions, memory.warning, preview, computing) {
        if (base != null && base === layout?.getOrNull() && layoutFor == input && memory.ready && writable && !memory.saving && memory.warning == null && preview == null && !computing) {
            val positions = memory.positions + snapshot(base)
            if (!sameDiagramPositions(positions, memory.positions)) state.saveDiagramPositions(context, positions)
        }
    }
    fun reset() { temporary = emptyMap(); preview = null; state.saveDiagramPositions(context, emptyMap()) }
    if (base == null) {
        Column(modifier.padding(16.dp)) {
            Text(memory.warning ?: layout?.exceptionOrNull()?.let { "配置を計算できません: ${it.message}" } ?: "フローを配置しています…",
                color = if (layout == null) Palette.SubText else Palette.ErrorRed)
            if (editable) TextButton(onClick = { state.reloadDiagramPositions(context) }, enabled = memory.ready && !memory.saving) { Text("配置を再読込") }
            if (editable && memory.positions.isNotEmpty()) TextButton(onClick = ::reset, enabled = writable && !memory.saving) { Text("配置を初期化") }
        }
        return
    }
    val moved = preview?.let { flowDragPreview(base, it.first, it.second, model, direction) } ?: base
    val ported = remember(moved, model, bodies) { withFlowPorts(moved, model, bodies) }
    val routed = remember(ported, model, vias, labelSizes, direction) { withFlowVias(ported, model, vias, labelSizes, direction) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    val colors = diagramColors()
    val scene = remember(model, routed, texts, bodies, edgeVisuals, colors, labelMeasures, hideLabels, vias.keys) {
        withFlowLabels(FlowScene(model, routed, texts, bodies, edgeVisuals, colors, Palette.Selected, hideLabels, vias.keys), labelMeasures)
    }
    val arrangement = scene.arrangement
    fun fit() {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return
        val bounds = flowBounds(scene)
        val viewport = fitViewport(bounds.width, bounds.height, canvasSize.width, canvasSize.height)
        zoom = viewport.zoom
        pan = Offset(viewport.panX - bounds.left * zoom, viewport.panY - bounds.top * zoom)
    }
    var fitted by remember(context) { mutableStateOf(false) }
    LaunchedEffect(base, canvasSize) { if (!fitted && canvasSize.width > 0 && canvasSize.height > 0) { fit(); fitted = true } }
    val saveImage = rememberFileSaver(FilePickers.Purpose.EXPORT, "フローの画像を保存", "png", fallback = state.workspace,
        onError = { state.status = it }) { path ->
        runCatching { exportFlowPng(path, scene, Palette.PanelBg, density) }
            .onSuccess { state.status = "画像を保存しました: $path" }
            .onFailure { state.status = "画像の保存に失敗しました: ${it.message ?: it::class.simpleName}" }
    }
    val latestChoose by rememberUpdatedState(choose)
    val latestBodies by rememberUpdatedState(bodies)
    val latestArrangement by rememberUpdatedState(arrangement)
    val latestScene by rememberUpdatedState(scene)
    val latestSelected by rememberUpdatedState(selected)
    val latestBase by rememberUpdatedState(base)
    fun laneContent(path: YPath, r: FlowRect): LensBox {
        val inset = PinnedFlow.inset(labelSizes, path, mode, direction, model.lanes.map { it.output })
        return LensBox(r.x + inset.x, r.y + inset.y, maxOf(0f, r.width - inset.x - 24f), maxOf(0f, r.height - inset.y - 24f))
    }
    fun toContent(at: Offset) = FlowPoint((at.x - pan.x) / zoom, (at.y - pan.y) / zoom)
    fun finishDrag() {
        // 線のラベルを離した: その位置（ラベルの中心）を経由点として記録する
        draggingLabel?.let { path ->
            val point = labelPreview?.takeIf { it.first == path }?.second
            val id = edgeIds[path]
            if (!cancelled && point != null && id != null) state.saveDiagramPositions(context, memory.positions + (id to flowEdgeVia(id, point, density.density)))
            else labelPreview = null
            draggingLabel = null
            dragMotion = null
            return
        }
        val moved = preview
        if (!cancelled && moved != null) {
            val (path, point) = moved
            val frame = frames[path]
            val lane = model.nodes.firstOrNull { it.output == path }?.lane?.target
            val r = lane?.let { latestBase.lanes[it] }
            val origin = if (r != null) PinnedFlow.inset(labelSizes, lane, mode, direction, model.lanes.map { it.output }).let { FlowPoint(r.x + it.x, r.y + it.y) } else FlowPoint(0f, 0f)
            val local = FlowPoint(point.x - origin.x, point.y - origin.y)
            val id = ids[path]
            if (id == null || r != null && frame == null) {
                temporary = temporary + (path to local)
                layout = Result.success(flowDragPreview(latestBase, path, point, model, direction)); preview = null
                state.status = "識別子が無いか重複しているため、この表示中だけ位置を保持します"
            } else state.saveDiagramPositions(context, memory.positions + snapshot(flowDragPreview(latestBase, path, point, model, direction), path))
        }
        dragging = null
        dragMotion = null
    }
    val startDrag by rememberUpdatedState<(Offset) -> Unit>({ at ->
        focus.requestFocus()
        val hit = latestArrangement.nodes.entries.lastOrNull { it.value.contains(toContent(at)) }?.key
        val enabled = memory.ready && writable && !memory.saving && !computing
        // 箱でなければ、経由点を持てる線のラベル（見えているもの）を探す。読むだけの図では動かさない
        val label = if (hit != null || !editable) null else latestArrangement.edges.lastOrNull { r ->
            r.output in edgeIds && latestScene.showsLabel(r.output, latestSelected) && r.label.contains(toContent(at))
        }
        cancelled = (hit != null || label != null) && !enabled
        dragging = hit.takeIf { enabled }
        draggingLabel = label?.output?.takeIf { enabled }
        dragMotion = dragging?.let { latestArrangement.nodes[it] }?.let { DiagramDrag(LensPoint(it.x, it.y)) }
            ?: draggingLabel?.let { label?.label }?.let { DiagramDrag(LensPoint(it.x, it.y)) }
    })
    val moveDrag by rememberUpdatedState<(Offset) -> Unit>({ amount ->
        draggingLabel?.let { path ->
            val r = latestArrangement.edges.firstOrNull { it.output == path }?.label
            if (!cancelled && r != null) dragMotion?.move(amount.x, amount.y, zoom, memory.grid, density.density, DiagramDragFrame())?.let {
                labelPreview = path to FlowPoint(it.x + r.width / 2, it.y + r.height / 2)
            }
            return@rememberUpdatedState
        }
        val path = dragging
        val box = path?.let { latestArrangement.nodes[it] }
        if (!cancelled && path != null && box != null) {
            val lanePath = model.nodes.firstOrNull { it.output == path }?.lane?.target
            val lane = lanePath?.let { latestBase.lanes[it] }
            val frame = if (lane != null) {
                val content = laneContent(lanePath, lane)
                DiagramDragFrame(LensPoint(content.x, content.y), content.x, content.y,
                    content.x + maxOf(0f, content.width - box.width), content.y + maxOf(0f, content.height - box.height))
            } else if (mode == FlowLayoutMode.SWIMLANES && latestBase.lanes.isNotEmpty()) {
                if (direction == "right") DiagramDragFrame(minY = latestBase.lanes.values.maxOf { it.bottom } + 16f)
                else DiagramDragFrame(minX = latestBase.lanes.values.maxOf { it.right } + 16f)
            } else DiagramDragFrame()
            dragMotion?.move(amount.x, amount.y, zoom, memory.grid, density.density, frame)?.let {
                preview = path to FlowPoint(it.x, it.y)
            }
        } else if (!cancelled && path == null) pan += amount
    })
    val endDrag by rememberUpdatedState(::finishDrag)
    Column(modifier) {
        memory.warning?.let { Text(it, fontSize = 11.sp, color = Palette.WarnOrange) }
        if (editable) Text("手動で固定したノード: ${fixed.size} 件・経由点のある線: ${storedVias.size} 本（再配置でも保持。線のラベルをドラッグすると、線はその位置を通ります）",
            fontSize = 11.sp, color = Palette.SubText)
        val fixedBoxes = arrangement.nodes.values.map { LensBox(it.x, it.y, it.width, it.height) }
        if (fixedBoxes.indices.any { i -> fixedBoxes.drop(i + 1).any { PinnedLayout.overlap(fixedBoxes[i], it) } })
            Text("ノード同士が重なっています。位置を調整してください", fontSize = 11.sp, color = Palette.WarnOrange)
        if (arrangement.edges.any { e -> e.points.isEmpty() && model.edges.any { it.output == e.output && it.from.target != null && it.to.target != null } })
            Text(if (model.edges.any { it.rendering?.edge?.hasPorts == true }) "一部の辺を描けません。ポートの指定・表示範囲・固定位置を確認してください"
                else "一部の辺は経路を確保できません。固定位置を調整してください", fontSize = 11.sp, color = Palette.WarnOrange)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(0.02f) }) { Text("−") }
            TextButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(3f) }) { Text("＋") }
            TextButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text("表示をリセット") }
            TextButton(onClick = { fit() }) { Text("全体") }
            if (editable) DiagramGridControls(memory.grid, memory.ready && writable && !memory.saving && !computing && dragging == null) {
                state.saveDiagramPositions(context, memory.positions + snapshot(base), it)
            }
            TextButton(onClick = { saveImage("flow") }) { Text("PNG に保存") }
            if (editable) TextButton(onClick = { preview = null; rearrange++ }, enabled = memory.ready && writable && !memory.saving && !computing) { Text("再配置") }
            if (editable) TextButton(onClick = { preview = null; state.reloadDiagramPositions(context) }, enabled = memory.ready && !memory.saving) { Text("配置を再読込") }
            if (editable && (fixed.isNotEmpty() || memory.positions.isNotEmpty() || memory.warning != null && writable)) TextButton(onClick = ::reset, enabled = writable && !memory.saving && !computing) { Text("配置を初期化") }
            val selectedId = selected?.let { ids[it] }
            if (editable && selected != null && selected in fixed) TextButton(onClick = {
                temporary = temporary - setOf(selected)
                memory.positions[selectedId]?.let { state.saveDiagramPositions(context, memory.positions + (it.node to it.copy(pinned = false))) }
            }, enabled = !memory.saving && !computing) { Text("選択ノードの固定を解除") }
            val selectedEdge = selected?.let { edgeIds[it] }?.takeIf { it in memory.positions }
            if (editable && selectedEdge != null) TextButton(onClick = {
                state.saveDiagramPositions(context, memory.positions - selectedEdge)
            }, enabled = writable && !memory.saving && !computing) { Text("選択した線を自動に戻す") }
            if (memory.saving) Text("配置を保存中…", fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
            Text("${(zoom * 100).toInt()}%", fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
        }
        Canvas(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Palette.PanelBg)
            .testTag("flow-canvas").focusRequester(focus).onPreviewKeyEvent {
                if (it.key == Key.Escape && it.type == KeyEventType.KeyDown && (dragging != null || draggingLabel != null)) {
                    cancelled = true; preview = null; dragging = null; dragMotion = null; draggingLabel = null; labelPreview = null; true
                } else false
            }.focusable()
            .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(context, model) { detectDragGestures(onDragStart = { startDrag(it) }, onDragEnd = { endDrag() },
                onDragCancel = { preview = null; dragging = null; dragMotion = null; draggingLabel = null; labelPreview = null }) { change, drag -> change.consume(); moveDrag(drag) } }
            .pointerInput(context, model) { detectTapGestures { at ->
                val p = FlowPoint((at.x - pan.x) / zoom, (at.y - pan.y) / zoom)
                val node = latestArrangement.nodes.entries.lastOrNull { it.value.contains(p) }
                if (node != null) {
                    val location = latestBodies[node.key]?.locationAt(Offset(p.x - node.value.x, p.y - node.value.y))
                    if (location != null) state.openFile(location.document, location.path) else latestChoose(FlowElementKind.NODE, node.key)
                }
                else latestArrangement.edges.lastOrNull { route ->
                    (latestScene.showsLabel(route.output, latestSelected) && route.output !in latestScene.edgeVisuals && route.label.contains(p)) ||
                        latestScene.visualFor(route.output, latestSelected)?.let { visual ->
                        diagramEdgeLabels(visual, route.points.map { Offset(it.x, it.y) }, Offset(route.label.center.x, route.label.center.y))
                            .any { it.bounds.contains(Offset(p.x, p.y)) }
                    } == true || route.points.zipWithNext().any { (a, b) -> nearSegment(p, a, b, 8f / zoom) }
                }?.let { latestChoose(FlowElementKind.EDGE, it.output) }
            } }) {
            if (editable && memory.grid.enabled) {
                val boxes = arrangement.nodes.values.map { LensBox(it.x, it.y, it.width, it.height) }
                val regions = listOf(DiagramGridRegion(LensPoint(0f, 0f), excluded = boxes + arrangement.lanes.values.map {
                    LensBox(it.x, it.y, it.width, it.height)
                })) + arrangement.lanes.map { (path, lane) ->
                    val content = laneContent(path, lane)
                    DiagramGridRegion(LensPoint(content.x, content.y), content, boxes)
                }
                drawDiagramGrid(memory.grid, pan, zoom, density.density, regions)
            }
            translate(pan.x, pan.y) { scale(zoom, pivot = Offset.Zero) {
                drawFlow(scene, selected)
                if (editable) fixed.keys.forEach { path -> arrangement.nodes[path]?.let { b ->
                    drawCircle(Palette.Primary, 3f / zoom, Offset(b.right - 5f / zoom, b.y + 5f / zoom))
                } }
                // 経由点のある線は、ラベルの右上に同じ固定の印
                if (editable) arrangement.edges.filter { it.output in storedVias }.forEach { r ->
                    drawCircle(Palette.Primary, 3f / zoom, Offset(r.label.right - 5f / zoom, r.label.y + 5f / zoom))
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
