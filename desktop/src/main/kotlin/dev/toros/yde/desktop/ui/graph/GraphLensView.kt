package dev.toros.yde.desktop.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.PathEffect
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.desktop.ui.common.*
import dev.toros.yde.semantic.*
import dev.toros.yde.app.core.DiagramInputs
import dev.toros.yde.app.core.Aspects
import dev.toros.yde.app.core.Views
import dev.toros.yde.semantic.ScannedId
import dev.toros.yde.semantic.SemanticSchemaGraph
import dev.toros.yde.semantic.schema.EntityRole
import dev.toros.yde.semantic.schema.RelationDef
import dev.toros.yde.semantic.schema.Relations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path as FilePath
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.math.sqrt
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.DiagramContext
import dev.toros.yde.desktop.DiagramNodeId
import dev.toros.yde.desktop.sameDiagramPositions
import dev.toros.yde.desktop.diagramPositions
import dev.toros.yde.desktop.saveDiagramPositions
import dev.toros.yde.desktop.reloadDiagramPositions
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.desktop.ui.common.FilePickers
import dev.toros.yde.desktop.ui.common.GraphLayouts
import dev.toros.yde.desktop.ui.common.LensArrangement
import dev.toros.yde.desktop.ui.common.LensEdgeSpec
import dev.toros.yde.desktop.ui.common.LensLayoutKind
import dev.toros.yde.desktop.ui.common.LensNestedNode
import dev.toros.yde.desktop.ui.common.LensPoint
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PanelShape
import dev.toros.yde.desktop.ui.common.fitViewport
import dev.toros.yde.desktop.ui.common.rememberFileSaver

// ================================================================ グラフレンズ（architecture/graph-query.md「エンティティページ」）

/** 配置の入力（文字を計測した大きさ込み）。変わったときだけ配置を計算し直す。 */
private data class LensInput(val nodes: List<LensNestedNode>, val edges: List<LensEdgeSpec>, val origin: Int?)

/** 未宣言の参照（relation なし）を絞り込みの行に並べるときの名前。 */
private const val UNDECLARED = "（未宣言）"

/** 箱の見出しの左にある開閉の印の幅（この中を押すと開く・畳む。それ以外はエンティティページへ）。 */
internal const val TOGGLE_ZONE = 22f

/** 辺に添える文字: 関係の label。逆向きに描く辺（レジストリの `arrow: reverse`）は、矢印の読む向きに合わせて inverseLabel。 */
private fun lensEdgeLabel(relations: Map<String, RelationDef>, edge: LensGraphEdge): String? =
    edge.relation?.let { if (edge.reversed) Relations.inverseLabelOf(relations, it) else Relations.labelOf(relations, it) }

/** 型の箱の添え書き: 現れるスキーマ（2 つまでは ID、それより多ければ数）。どこにも現れない型は「宣言だけ」。 */
private fun typeSubtitle(graph: SemanticSchemaGraph, type: String): String? {
    val ids = graph.typeMap[type]?.appearances?.map { it.schemaId }?.distinct() ?: return null
    return when {
        ids.isEmpty() -> "〔宣言だけ〕"
        ids.size <= 2 -> "〔${ids.joinToString("・")}〕"
        else -> "〔${ids.size} スキーマ〕"
    }
}

/** 型を定義するスキーマ文書を開く（無ければ最初に現れるスキーマ。どこにも現れなければ状態欄に案内）。 */
private fun openTypeSchema(state: AppState, type: String) {
    val schemaId = state.schemaGraph.typeMap[type]?.definingSchemaId
    val schema = schemaId?.let { id -> state.schemas.firstOrNull { it.id == id } }
    if (schema == null) state.status = "型「$type」はどのスキーマにも現れません（関係レジストリの宣言だけ）"
    else state.openFile(schema.source)
}

/**
 * レンズの材料（描く画面と右パネルが同じものを見る）: 見え方ごとの節点と辺、型と関係の一覧、絞り込みと畳みを当てた見える部分。
 * 画面の覚え（型・関係の隠し・開いた箱・深さ・見え方）と索引から作る。
 */
private class LensModel(
    val catalog: Views.LoadResult?,
    val profile: VirtualLensDef?,
    val level: LensLevel,
    /** 矢印を逆向きに描く関係名（_views の kind: lens の定義）。 */
    val reversed: Set<String>,
    val typeOf: (String) -> String,
    val types: List<String>,
    val typeColors: Map<String, Color>,
    val relations: List<String>,
    val holdingNames: Set<String>,
    val origin: String?,
    val mode: LensMode,
    val nodes: List<LensVisibleNode>,
    val keyIndex: Map<String, Int>,
    val visibleEdges: List<LensGraphEdge>,
    val sources: Map<LensGraphEdge, List<DiagramLinkSource>>,
    /** 選んだ見え方の定義の絞り込みのうち、この段に効くもの（ADR-0027）。 */
    val filters: List<LensFilter> = emptyList(),
    /** 入っている絞り込みの ID。 */
    val activeFilters: Set<String> = emptySet(),
    /** 絞り込みで隠した箱の数（起点の近傍に限る前）。 */
    val filteredOut: Int = 0,
    /** 絞り込みが隠すはずだったが「つながりで残す」で残した箱の数。 */
    val keptByLinks: Int = 0,
) {
    /** 辺の端（箱の鍵）のエンティティ ID。 */
    fun idOf(key: String): String = nodes.getOrNull(keyIndex[key] ?: -1)?.node?.id ?: key
}

@Composable
private fun rememberLensModel(state: AppState): LensModel {
    state.indexVersion
    val overlay = state.overlayIndex()
    val graph = overlay.graph
    // 段: 実体（箱＝エンティティ）か型（箱＝エンティティ型＝スキーマの図）。型の段では「型」はその箱自身
    val level = if (state.memory.lensSchemaLevel) LensLevel.TYPE else LensLevel.ENTITY
    val schemaGraph = state.schemaGraph
    val typeOf: (String) -> String =
        if (level == LensLevel.TYPE) { t -> t } else { id -> graph.entities[id]?.entityType ?: "（型なし）" }
    val mode = if (level == LensLevel.ENTITY && state.memory.lensSplitOccurrences) LensMode.OCCURRENCE else LensMode.ENTITY
    // 矢印を逆向きに描く関係（_views の kind: lens の定義。表示だけ）。定義は未保存優先で読む
    val ws = state.workspace
    val catalog = remember(ws, state.indexVersion, state.editVersion) {
        ws?.let { Views.load(it, state.schemas, state.unsavedFirstTree()) }
    }
    val profile = catalog?.selectableLenses?.singleOrNull { it.id == state.memory.lensAppearanceId }
    val reversed = catalog?.reversedRelations.orEmpty() + profile?.reversedRelations.orEmpty()
    // 節点と辺（段と見え方ごと）。箱の入れ子は保有の線（型の段では保有の宣言）
    val lensGraph = remember(state.indexVersion, state.editVersion, mode, level, schemaGraph, reversed, catalog, profile) {
        if (level == LensLevel.TYPE) LensGraphs.buildTypes(schemaGraph, reversed)
        else LensGraphs.withPortReferences(LensGraphs.build(graph, overlay.refs, mode, reversed, includeSelfReferences = true)) { edge, source ->
            val bindings = profile?.appearance?.edge(edge.relation).orEmpty()
            if (profile == null || bindings.isEmpty()) false else {
                val input = DiagramInputs.edge(listOf(source), edge.relation, lensEdgeLabel(state.relations, edge),
                    edge.kind.name.lowercase(), state.unsavedFirstTree())
                DiagramNotations.resolve(catalog.notations, bindings, input.place, profile.source, "edge")?.edge?.hasPorts == true
            }
        }
    }
    // 型の一覧は ID 一覧から（参照を持たない型も出す。型の段ではスキーマの図の型）。関係の一覧は辺と入れ子から（保有の名前には印を付ける）
    val types = remember(state.indexVersion, state.editVersion, level, schemaGraph) {
        if (level == LensLevel.TYPE) schemaGraph.types.map { it.type } else graph.entities.keys.map(typeOf).distinct().sorted()
    }
    val holdingNames = remember(lensGraph) {
        (lensGraph.nodes.mapNotNull { it.relation } + lensGraph.edges.filter { it.kind == LensEdgeKind.HOLDING }.mapNotNull { it.relation }).toSet()
    }
    val relations = remember(lensGraph) {
        (lensGraph.edges.filter { it.kind != LensEdgeKind.TIE }.map { it.relation ?: UNDECLARED } + holdingNames).distinct().sorted()
    }
    // 型 → 色（凡例つき）
    val lensColors = listOf(Palette.Primary, Palette.OkGreen, Palette.WarnOrange, Palette.Secondary) + Palette.Accents
    val typeColors = remember(types) { types.mapIndexed { i, t -> t to lensColors[i % lensColors.size] }.toMap() }
    // 型・関係のチェック・深さ・見え方・開いた箱は画面の覚え（ScreenMemory）。隠した名前だけ持つので、再読込で増えた型は見える側で始まる
    val hiddenTypes = state.memory.lensHiddenTypes
    val hiddenRelations = state.memory.lensHiddenRelations
    val expandedBoxes = state.memory.lensExpanded
    val depth = state.memory.lensDepth
    val origin =
        if (level == LensLevel.TYPE) state.memory.lensTypeOrigin?.takeIf { schemaGraph.typeMap.containsKey(it) }
        else state.lensOriginId?.takeIf { graph.entities.containsKey(it) }
    // 見え方の定義の絞り込み（ADR-0027）: この段に効くものと、入っているもの（画面の覚えが無ければ定義の default）
    val levelName = if (level == LensLevel.TYPE) "type" else "entity"
    val filters = profile?.filters.orEmpty().filter { it.level == null || it.level == levelName }
    val activeFilters = filters.filter { state.memory.lensFilters[lensFilterKey(profile!!, it)] ?: it.default }
    val filterResult = remember(lensGraph, activeFilters, state.indexVersion, state.editVersion, level, origin) {
        LensFilters.evaluate(activeFilters, levelName, lensGraph.nodes.map { it.id }.distinct().map { id ->
            LensFilters.Candidate(id, if (level == LensLevel.TYPE) id else graph.entities[id]?.entityType) {
                if (level == LensLevel.TYPE) DiagramInputs.type(id, schemaGraph.typeMap[id]?.label ?: id, schemaGraph.typeMap[id]).place
                else DiagramInputs.entity(id, null, overlay, state.schemas, state.entityTypes, state.unsavedFirstTree()).place
            }
        }, origin, if (activeFilters.any { it.keepIfLinked != null }) LensGraphs.links(lensGraph) else emptyMap())
    }
    val filteredIds = filterResult.hidden
    // 絞り込み: 型・関係・定義の絞り込み・起点の近傍・畳み（LensGraphs.select）。起点はどの絞り込みでも消えない（起点自身が消えると意味を失う）
    val stopAtHubs = state.memory.lensStopAtHubs && origin != null && depth >= 2
    val selection = remember(lensGraph, hiddenTypes.toMap(), hiddenRelations.toMap(), expandedBoxes.toMap(), origin, depth, stopAtHubs, filteredIds) {
        LensGraphs.select(
            lensGraph,
            LensSelectParams(typeOf, hiddenTypes.keys.toSet(), hiddenRelations.keys.toSet(), origin, depth, expandedBoxes.toMap(), stopAtHubs,
                hiddenIds = filteredIds),
        )
    }
    val visibleEdges = remember(selection, hiddenRelations.toMap()) {
        selection.edges.filter { !(it.kind == LensEdgeKind.REF && it.relation == null && UNDECLARED in hiddenRelations) }
    }
    val keyIndex = remember(selection) { selection.nodes.withIndex().associate { (i, n) -> n.node.key to i } }
    val activeIds = activeFilters.map { it.id }.toSet()
    val filteredOut = filteredIds.size
    val keptByLinks = filterResult.kept.size
    return remember(selection, visibleEdges, keyIndex, types, typeColors, relations, holdingNames, origin, mode, level, reversed, catalog, profile, filters, activeIds, filteredOut, keptByLinks) {
        LensModel(catalog, profile, level, reversed, typeOf, types, typeColors, relations, holdingNames, origin, mode, selection.nodes, keyIndex, visibleEdges, selection.sources,
            filters, activeIds, filteredOut, keptByLinks)
    }
}

/** 絞り込みを入れた・外したことの覚えの鍵（定義ごとに分ける）。 */
private fun lensFilterKey(profile: VirtualLensDef, filter: LensFilter): String = "${profile.id}/${filter.id}"

/**
 * グラフレンズ: エンティティグラフの導出表示。
 * ①エンティティ起点の近傍（深さ1〜2。エンティティページの「グラフで見る」から遷移）
 * ②チェックした型のエンティティ（ID 一覧から。参照の有無を問わない）と、その間の参照と保有（relation のチェックで絞る）、
 * を配置（`GraphLayouts`＝放射・階層・応力・力学）して描く。型・関係の絞り込みと辺の一覧は右パネル（`LensSidePanel`）にある。
 * 節点は「ID＋タイトル」の箱（型で色分け）・辺は矢印と関係のラベル。保有は箱の入れ子（`LensGraphs`。一体＝箱はエンティティ、
 * 側面で分ける＝箱は出現で同じエンティティの箱を点線で結ぶ）。入れ子の箱は畳めて、畳んだ箱の中につながる辺は箱に付く。
 * クリックはエンティティページへ着地する（ナビゲーション履歴に乗る）。見出しの左の印を押すと開く・畳む。
 * 操作: ホイールでカーソル中心の拡縮・箱のドラッグ（位置を固定。中の箱も一緒に動く）・ホバーで隣接の強調・右クリックのメニュー・PNG の書き出し・ミニマップ。
 */
@Composable
fun GraphLensView(state: AppState) {
    val model = rememberLensModel(state)
    val graph = state.overlayIndex().graph
    val level = model.level
    val schemaGraph = state.schemaGraph
    val typeOf = model.typeOf
    val mode = model.mode
    val origin = model.origin
    val nodes = model.nodes
    val keyIndex = model.keyIndex
    val visibleEdges = model.visibleEdges
    val typeColors = model.typeColors
    val hiddenTypes = state.memory.lensHiddenTypes
    val expandedBoxes = state.memory.lensExpanded
    val depth = state.memory.lensDepth
    // rememberLensModel と同じ条件（深さ 2 で起点があるときだけ効く）。効くときだけ配置の記録を分ける
    val stopAtHubs = state.memory.lensStopAtHubs && origin != null && depth >= 2
    val edgeRenderings =remember(model, state.indexVersion, state.editVersion, state.relations) {
        val profile = model.profile
        if (profile == null) emptyMap() else visibleEdges.filter { it.kind != LensEdgeKind.TIE }.mapNotNull { edge ->
            val bindings = profile.appearance?.edge(edge.relation).orEmpty()
            if (bindings.isEmpty()) return@mapNotNull null
            val input = DiagramInputs.edge(model.sources[edge].orEmpty(), edge.relation, lensEdgeLabel(state.relations, edge),
                edge.kind.name.lowercase(), state.unsavedFirstTree())
            DiagramNotations.resolve(model.catalog?.notations.orEmpty(), bindings, input.place, profile.source, "edge")?.let { edge to it }
        }.toMap()
    }
    val edgeStyles = remember(edgeRenderings, state.relations, model) {
        edgeRenderings.mapNotNull { (edge, rendering) -> rendering.edge?.let { style ->
            val directed = lensEndpointStyle(style, edge, model.sources[edge].orEmpty(), model.idOf(edge.from), model.idOf(edge.to))
            edge to directed.copy(label = directed.label ?: lensEdgeLabel(state.relations, edge))
        } }.toMap()
    }
    val edgeVisuals = rememberDiagramEdges(edgeStyles)
    val edgeIndices = remember(visibleEdges, keyIndex, state.relations, edgeVisuals) {
        visibleEdges.mapNotNull { e ->
            val s = keyIndex[e.from] ?: return@mapNotNull null
            val t = keyIndex[e.to] ?: return@mapNotNull null
            LensArrangedEdge(s, t, e.relation, e.kind, edgeStyles[e]?.label ?: lensEdgeLabel(state.relations, e), edgeVisuals[e])
        }
    }
    val paint = LensPaint(Palette.Border, Palette.Surface, Palette.Primary, Palette.SubText)

    // 配置の種類: 既定は起点があれば放射、無ければ応力（節点が多ければ GraphLayouts が階層に代替する）。
    // 利用者が選んだ配置は画面の覚えに起点の有無ごとに名前で持つ（覚えが無い・知らない名前・使えない放射は既定に戻す）
    val layoutKind = state.memory.lensLayoutKind[origin != null]
        ?.let { name -> LensLayoutKind.entries.firstOrNull { it.name == name } }
        ?.takeIf { it != LensLayoutKind.RADIAL || origin != null }
        ?: if (origin != null) LensLayoutKind.RADIAL else LensLayoutKind.STRESS
    // 配置の調整: 覚えた選択のうち、選んだ配置の種類に効くものだけを配置と記録の区別に使う
    val layoutOptions = LensLayoutOption.entries.filter { state.memory.lensLayoutOptions[it.name] == true && it.appliesTo(layoutKind) }.toSet()

    val density = LocalDensity.current
    val context = DiagramContext(mapOf("kind" to "lens", "level" to level.name.lowercase(), "mode" to mode.name.lowercase()),
        withLayoutOptionSelection(mapOf("layout" to listOf(layoutKind.name.lowercase()), "origin" to listOfNotNull(origin), "depth" to listOf(depth.toString()),
            "hiddenTypes" to hiddenTypes.keys.sorted(), "hiddenRelations" to state.memory.lensHiddenRelations.keys.sorted(),
            "appearance" to listOfNotNull(state.memory.lensAppearanceId)) + (if (stopAtHubs) mapOf("hubs" to listOf("stop")) else emptyMap()) +
            (if (model.activeFilters.isNotEmpty()) mapOf("filters" to model.activeFilters.sorted()) else emptyMap()),
            layoutOptions.map { it.key }))
    val positionMemory = remember(state.workspace, context) { state.diagramPositions(context) }
    fun nodeId(n: LensNode) = if (level == LensLevel.TYPE) DiagramNodeId("type", n.id)
        else n.occurrence?.let { DiagramNodeId("occurrence", n.id, it.document.value, it.path.pretty()) }
            ?: DiagramNodeId("entity", n.id)
    val retained = nodes.mapNotNull { n ->
        val frame = n.parent?.let { parent -> nodes.firstOrNull { it.node.key == parent }?.node?.let(::nodeId) }
        positionMemory.positions[nodeId(n.node)]?.takeIf { it.frame == frame }?.let {
            n.node.key to LensPoint(it.x * density.density, it.y * density.density)
        }
    }.toMap()
    val fixed = retained.filterKeys { key -> nodes.firstOrNull { it.node.key == key }?.let { positionMemory.positions[nodeId(it.node)]?.pinned } == true }

    // 出現の箱の添え書き（側面で分けるとき）: 側面の表示名（合成の見出しと同じ決まり）と「定義」
    fun aspectLabelOf(e: ScannedId): String {
        val label = Aspects.aspectLabelOf(e, state.schemas, state.entityTypes)
        return if (e.role == EntityRole.DEFINING) "〔$label・定義〕" else "〔$label〕"
    }

    // 箱の文字（開閉の印・ID・タイトル・添え書き）と辺のラベルを計測し、箱の大きさを配置に渡す
    val renderings = remember(model, state.editVersion, state.indexVersion) {
        val profile = model.profile
        if (profile == null) emptyMap() else nodes.mapNotNull { n ->
            val bindings = profile.appearance?.node(if (level == LensLevel.TYPE) "type" else "entity", typeOf(n.node.id)).orEmpty()
            if (bindings.isEmpty()) return@mapNotNull null
            val input = if (level == LensLevel.TYPE) DiagramInputs.type(n.node.id, schemaGraph.typeMap[n.node.id]?.label ?: n.node.id, schemaGraph.typeMap[n.node.id])
                else DiagramInputs.entity(n.node.id, n.node.occurrence, state.overlayIndex(), state.schemas, state.entityTypes, state.unsavedFirstTree())
            DiagramNotations.resolve(model.catalog?.notations.orEmpty(), bindings, input.place, profile.source, "node")
                ?.let { n.node.key to it.copy(notices = it.notices + input.notices) }
        }.toMap()
    }
    val bodies = rememberDiagramBodies(renderings, state)
    val portNotices = remember(renderings, edgeRenderings, edgeStyles) {
        edgeStyles.flatMap { (edge, style) ->
            listOf(Triple("sourcePort", style.sourcePort, edge.from), Triple("targetPort", style.targetPort, edge.to)).flatMap { (field, port, key) ->
                val message = DiagramPorts.issue(port, renderings[key]?.node) ?: return@flatMap emptyList()
                val originalField = if (!edge.reversed) field else if (field == "sourcePort") "targetPort" else "sourcePort"
                val rendering = edgeRenderings.getValue(edge)
                val locations = rendering.locations(listOf(originalField)).ifEmpty {
                    rendering.definition?.let { listOf(DiagramLocation(it, listOf("build", originalField))) }.orEmpty()
                }
                locations.map { DiagramNotice(message, it) }
            }
        }
    }
    val textMeasurer = rememberTextMeasurer()
    val idStyle = TextStyle(fontSize = 11.sp, color = Palette.Text, fontWeight = FontWeight.SemiBold)
    val titleStyle = TextStyle(fontSize = 10.sp, color = Palette.SubText)
    val edgeLabelStyle = TextStyle(fontSize = 9.sp, color = Palette.SubText)
    val idTexts: Map<String, TextLayoutResult> = remember(nodes, idStyle) {
        nodes.associate { n ->
            val glyph = when {
                n.expanded -> "▾ "
                n.hiddenChildren > 0 -> "▸ ${n.hiddenChildren}  "
                else -> ""
            }
            n.node.key to textMeasurer.measure(glyph + n.node.id, idStyle)
        }
    }
    // タイトル: 実体はエンティティのタイトル、型は表示名（レジストリの label）。添え書き: 出現の側面、型は現れるスキーマ
    val titleTexts: Map<String, TextLayoutResult> = remember(nodes, titleStyle, state.indexVersion, state.editVersion, level, schemaGraph) {
        nodes.mapNotNull { n ->
            val title =
                if (level == LensLevel.TYPE) schemaGraph.typeMap[n.node.id]?.label
                else n.node.occurrence?.title ?: graph.entities[n.node.id]?.title
            title?.takeIf { it.isNotBlank() && it != n.node.id }?.let { n.node.key to textMeasurer.measure(it.take(30), titleStyle) }
        }.toMap()
    }
    val subTexts: Map<String, TextLayoutResult> = remember(nodes, titleStyle, state.schemas, state.entityTypes, level, schemaGraph) {
        nodes.mapNotNull { n ->
            val text = if (level == LensLevel.TYPE) typeSubtitle(schemaGraph, n.node.id) else n.node.occurrence?.let { aspectLabelOf(it) }
            text?.let { n.node.key to textMeasurer.measure(it, titleStyle) }
        }.toMap()
    }
    val labelTexts: Map<String, TextLayoutResult> = remember(edgeIndices, edgeLabelStyle) {
        edgeIndices.mapNotNull { it.label }.distinct().associateWith { textMeasurer.measure(it, edgeLabelStyle) }
    }
    val input = remember(nodes, edgeIndices, idTexts, titleTexts, subTexts, labelTexts, origin, keyIndex, bodies) {
        val sizes = nodes.map { n ->
            val key = n.node.key
            val idText = idTexts.getValue(key)
            val title = titleTexts[key]
            val sub = subTexts[key]
            val body = bodies[key]
            LensNestedNode(
                key,
                body?.let { it.size.width + if (n.expanded || n.hiddenChildren > 0) TOGGLE_ZONE else 0f }
                    ?: (maxOf(idText.size.width, title?.size?.width ?: 0, sub?.size?.width ?: 0) + 20f),
                body?.size?.height ?: (idText.size.height + (title?.size?.height ?: 0) + (sub?.size?.height ?: 0) + 12f),
                n.parent?.let { keyIndex[it] },
            )
        }
        val specs = edgeIndices.map { e ->
            val label = e.label?.let { e.visual?.texts?.get(it) ?: labelTexts[it] }
            LensEdgeSpec(e.from, e.to, label?.let { it.size.width + 6f } ?: 0f, label?.size?.height?.toFloat() ?: 0f)
        }
        // 起点の箱（側面で分けるときは定義出現の箱、見えていなければその ID の最初の箱）
        val originIndex = origin?.let { o ->
            nodes.indexOfFirst { it.node.id == o && it.node.occurrence?.role == EntityRole.DEFINING }.takeIf { it >= 0 }
                ?: nodes.indexOfFirst { it.node.id == o }.takeIf { it >= 0 }
        }
        LensInput(sizes, specs, originIndex)
    }
    // 配置はバックグラウンドで計算し、できるまでは前の絵を出す（スナップショット状態の直接更新。Dispatchers.Main は使わない）
    var arranged by remember(context) { mutableStateOf<LensArranged?>(null) }
    var arrangedFor by remember(context) { mutableStateOf<Pair<LensInput, LensLayoutKind>?>(null) }
    var layoutError by remember(context) { mutableStateOf<String?>(null) }
    var arranging by remember(context) { mutableStateOf(false) }
    var rearrange by remember(context) { mutableStateOf(0) }
    var appliedRearrange by remember(context) { mutableStateOf(0) }
    val pins = remember(context) { mutableStateMapOf<Int, LensPoint>() }
    fun snapshot(a: LensArranged, pin: Int? = null) = lensPositionSnapshot(a.nested, a.nodes.map { nodeId(it.node) },
        a.result, density.density, positionMemory.positions, pin)
    fun routeSpecs(a: LensArranged) = a.edges.map { e ->
        val label = e.label?.let { e.visual?.texts?.get(it) ?: labelTexts[it] }
        LensEdgeSpec(e.from, e.to, label?.let { it.size.width + 6f } ?: 0f, label?.size?.height?.toFloat() ?: 0f)
    }
    val computing = nodes.isNotEmpty() && arrangedFor != (input to layoutKind)
    LaunchedEffect(input, layoutKind, layoutOptions, bodies, edgeIndices, retained, positionMemory.positions, positionMemory.ready, rearrange) {
        if (!positionMemory.ready) { arranging = false; return@LaunchedEffect }
        val reflow = rearrange != appliedRearrange
        val candidate = arranged?.let { a -> a.copy(result = GraphLayouts.withMovedNested(a.result, a.nested,
            routeSpecs(a), pins.toMap())) }
        if (!reflow && arrangedFor == (input to layoutKind) && candidate != null && candidate.bodies == bodies && candidate.edges == edgeIndices &&
            candidate.nodes == nodes && sameDiagramPositions(snapshot(candidate), positionMemory.positions)) {
            arranged = candidate; pins.clear(); arranging = false
            return@LaunchedEffect
        }
        arranging = true
        val result = withContext(Dispatchers.Default) { runCatching { GraphLayouts.arrangeNested(input.nodes, input.edges, layoutKind, input.origin, if (reflow) fixed else retained, layoutOptions) } }
        result.onSuccess {
            val next = LensArranged(nodes, input.nodes, edgeIndices, it, bodies)
            arranged = next
            arrangedFor = input to layoutKind; layoutError = null
            if (reflow) state.saveDiagramPositions(context, positionMemory.positions + snapshot(next))
        }.onFailure { layoutError = "配置を計算できません: ${it.message}" }
        appliedRearrange = rearrange
        arranging = false
    }
    // ドラッグ中だけの絶対位置。保存された固定位置は別に持ち、親の移動で子も一緒に動かす。
    LaunchedEffect(arranged, positionMemory.ready, positionMemory.saving, positionMemory.positions, positionMemory.warning, pins.toMap(), arranging) {
        val a = arranged
        if (a != null && !computing && positionMemory.ready && positionMemory.writable && !positionMemory.saving && positionMemory.warning == null && pins.isEmpty() && !arranging) {
            val positions = positionMemory.positions + snapshot(a)
            if (!sameDiagramPositions(positions, positionMemory.positions)) state.saveDiagramPositions(context, positions)
        }
    }
    LaunchedEffect(positionMemory.saving, positionMemory.warning) {
        if (!positionMemory.saving && positionMemory.warning != null) pins.clear()
    }
    val effective: LensArranged? = remember(arranged, pins.toMap()) {
        arranged?.let { a ->
            val edges = routeSpecs(a)
            val moved = if (pins.isEmpty()) a.result else GraphLayouts.withMovedNested(a.result, a.nested, edges, pins.toMap())
            val diamonds = a.nodes.indices.filter { i ->
                val n = a.nodes[i]
                !n.expanded && n.hiddenChildren == 0 && a.bodies[n.node.key]?.rendering?.node?.let { it.kind == "box" && it.shape == "diamond" } == true
            }.toSet()
            withLensPorts(a.copy(result = GraphLayouts.withDiamondOutlines(moved, diamonds, edges)))
        }
    }
    val adjacency = remember(effective) {
        val a = effective ?: return@remember LensAdjacency(emptyList(), emptyList())
        val neighbors = List(a.nodes.size) { mutableSetOf<Int>() }
        val incident = List(a.nodes.size) { mutableSetOf<Int>() }
        a.edges.forEachIndexed { i, (s, t) ->
            if (s in neighbors.indices && t in neighbors.indices) {
                neighbors[s] += t; neighbors[t] += s; incident[s] += i; incident[t] += i
            }
        }
        LensAdjacency(neighbors, incident)
    }
    val originKeys = remember(nodes, origin) { nodes.filter { it.node.id == origin }.map { it.node.key }.toSet() }
    val hideLabels = state.memory.lensHideLabels
    val scene = remember(effective, originKeys, typeColors, idTexts, titleTexts, subTexts, labelTexts, paint, hideLabels) {
        effective?.let { a ->
            val typeOfKey = a.nodes.associate { it.node.key to typeOf(it.node.id) }
            LensScene(a, originKeys, { key -> typeColors[typeOfKey[key]] ?: paint.primary }, idTexts, titleTexts, subTexts, labelTexts, paint, hideLabels)
        }
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
        val bounds = scene?.let(::lensBounds) ?: return
        val fit = fitViewport(bounds.width, bounds.height, canvasSize.width, canvasSize.height)
        zoom = fit.zoom
        pan = Offset(fit.panX - bounds.left * zoom, fit.panY - bounds.top * zoom)
    }
    var hovered by remember { mutableStateOf<Int?>(null) }
    var dragging by remember(context) { mutableStateOf<Int?>(null) }
    var dragMotion by remember(context) { mutableStateOf<DiagramDrag?>(null) }
    var cancelled by remember(context) { mutableStateOf(false) }
    LaunchedEffect(arranged, input) { pins.clear(); dragging = null; dragMotion = null; cancelled = true }
    val focus = remember { FocusRequester() }
    var menu by remember { mutableStateOf<Pair<Int, Offset>?>(null) }
    val latest by rememberUpdatedState(effective)
    /** 画面の点を配置の座標に戻す。 */
    fun toContent(p: Offset): Offset = Offset((p.x - pan.x) / zoom, (p.y - pan.y) / zoom)
    /** 押した箱（いちばん内側。箱の並びは親が先なので、最後に当たったものが内側）。 */
    fun hitNode(p: Offset): Int? {
        val a = latest ?: return null
        val c = toContent(p)
        return a.result.boxes.indexOfLast { it.contains(c.x, c.y) }.takeIf { it >= 0 && it < a.nodes.size }
    }
    /** 箱に中の箱があるか（開いている・畳んでいる）。 */
    fun hasChildren(n: LensVisibleNode) = n.expanded || n.hiddenChildren > 0
    fun toggle(n: LensVisibleNode) {
        expandedBoxes[n.node.key] = !n.expanded
    }

    val startDrag by rememberUpdatedState<(Offset) -> Unit>({ start ->
        focus.requestFocus()
        val hit = hitNode(start)
        val enabled = positionMemory.ready && positionMemory.writable && !positionMemory.saving && !computing && !arranging
        cancelled = hit != null && !enabled
        dragging = hit.takeIf { enabled }
        dragMotion = dragging?.let { latest?.result?.boxes?.getOrNull(it) }?.let { DiagramDrag(LensPoint(it.x, it.y)) }
        if (enabled) pins.clear()
    })
    val moveDrag by rememberUpdatedState<(Offset) -> Unit>({ amount ->
        val index = dragging
        val a = latest
        val box = index?.let { a?.result?.boxes?.getOrNull(it) }
        if (!cancelled && index != null && box != null && a != null) {
            val frame = a.nested[index].parent?.let { parent ->
                val p = a.result.boxes[parent]
                val top = p.y + a.nested[parent].height
                DiagramDragFrame(LensPoint(p.x, top), p.x, top,
                    maxOf(p.x, p.x + p.width - box.width), maxOf(top, p.y + p.height - box.height))
            } ?: DiagramDragFrame()
            dragMotion?.move(amount.x, amount.y, zoom, positionMemory.grid, density.density, frame)?.let { pins[index] = it }
        } else if (!cancelled && index == null) pan += amount
    })
    val endDrag by rememberUpdatedState<() -> Unit>({
        val a = latest
        val index = dragging
        val point = index?.let { pins[it] }
        if (!cancelled && a != null && index != null && point != null) {
            state.saveDiagramPositions(context, positionMemory.positions + snapshot(a, index))
        }
        dragging = null
        dragMotion = null
    })

    // PNG の書き出し（配置の全体を等倍で描く。保存先は OS のダイアログで選ぶ）
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
        positionMemory.warning?.let { Text(it, color = Palette.WarnOrange, fontSize = 11.sp) }
        layoutError?.let { Text(it, color = Palette.WarnOrange, fontSize = 11.sp) }
        if (effective?.let { a -> a.edges.indices.any { i -> a.edges[i].visual?.style?.hasPorts == true && a.result.routes[i].points.isEmpty() } } == true)
            Text("一部の辺を描けません。ポートの指定・表示範囲・固定位置を確認してください", color = Palette.WarnOrange, fontSize = 11.sp)
        val notices = ((renderings.values + edgeRenderings.values).flatMap { it.notices } + portNotices).distinct()
        model.catalog?.errors.orEmpty().forEach { Text(it, color = Palette.WarnOrange, fontSize = 11.sp) }
        if (notices.isNotEmpty()) Column(Modifier.fillMaxWidth().heightIn(max = 100.dp).verticalScroll(rememberScrollState())) {
            notices.forEach { notice -> TextButton(onClick = { state.openFile(notice.location.document, notice.location.path) }) {
                Text(notice.message, color = Palette.WarnOrange, fontSize = 11.sp)
            } }
        }
        Row(
            Modifier.fillMaxWidth().background(Palette.Surface).horizontalScroll(rememberScrollState()).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("グラフレンズ", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                TextButton(onClick = { menuOpen = true }) { Text("見え方: ${model.profile?.name ?: "標準"}", fontSize = 11.sp) }
                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("標準") }, onClick = { state.memory.lensAppearanceId = null; menuOpen = false })
                    model.catalog?.selectableLenses.orEmpty().forEach { profile ->
                        DropdownMenuItem(text = { Text(profile.name) }, onClick = { state.memory.lensAppearanceId = profile.id; menuOpen = false })
                    }
                }
            }
            model.profile?.let { profile -> TextButton(onClick = { state.openFile(profile.source) }) { Text("見え方の定義", fontSize = 11.sp) } }
            // 見え方の定義の絞り込み（ADR-0027）: 押して入れる・外す。入れたものは太字で、隠した箱の数を添える
            model.profile?.let { profile ->
                model.filters.forEach { filter ->
                    val on = filter.id in model.activeFilters
                    TextButton(
                        onClick = { state.memory.lensFilters[lensFilterKey(profile, filter)] = !on },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Text(
                            (if (on) "☑ " else "☐ ") + filter.label, fontSize = 11.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            color = if (on) Palette.Primary else Palette.Text,
                        )
                    }
                }
                if (model.activeFilters.isNotEmpty()) Text(
                    if (model.keptByLinks == 0) "${model.filteredOut} 件を隠しています"
                    else "${model.filteredOut} 件を隠し、${model.keptByLinks} 件はつながりで残しています",
                    fontSize = 11.sp, color = Palette.SubText,
                )
            }
            Spacer(Modifier.width(8.dp))
            // 段: 実体（箱＝エンティティ・線＝参照）か型（箱＝エンティティ型・線＝関係＝スキーマの図）。型・関係の絞り込みは両方で同じ覚え
            LensLevel.entries.forEach { lv ->
                TextButton(
                    onClick = { state.memory.lensSchemaLevel = lv == LensLevel.TYPE },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                ) {
                    Text(
                        lv.label, fontSize = 11.sp,
                        fontWeight = if (level == lv) FontWeight.Bold else FontWeight.Normal,
                        color = if (level == lv) Palette.Primary else Palette.Text,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (origin != null) {
                Text("起点: $origin", fontSize = 11.sp, color = Palette.Primary, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { state.memory.lensDepth = if (depth == 1) 2 else 1 },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text("深さ $depth", fontSize = 11.sp)
                }
                // 深さ 2 で、多くの箱から参照される箱を経由して全体へ広がるのを止める
                if (depth >= 2) LensFilterItem(state.memory.lensStopAtHubs, { state.memory.lensStopAtHubs = it }) {
                    Text("線が${LensGraphs.HUB_EDGES}本以上の箱の先は辿らない", fontSize = 11.sp)
                }
                TextButton(onClick = { if (level == LensLevel.TYPE) state.memory.lensTypeOrigin = null else state.lensOriginId = null },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text("起点を解除", fontSize = 11.sp)
                }
            } else {
                Text(
                    if (level == LensLevel.TYPE) "型の図（スキーマと関係レジストリから。右クリックで起点に・箱を押すとスキーマへ）"
                    else "起点なし（全体をフィルタ表示。エンティティページの「グラフで見る」か右クリックで起点に）",
                    fontSize = 11.sp, color = Palette.SubText,
                )
            }
            Spacer(Modifier.width(12.dp))
            // 見え方（実体の段だけ）: 一体（箱＝エンティティ）か、側面で分ける（箱＝出現）
            if (level == LensLevel.ENTITY) {
                LensFilterItem(mode == LensMode.OCCURRENCE, { state.memory.lensSplitOccurrences = it }) {
                    Text(LensMode.OCCURRENCE.label, fontSize = 11.sp)
                }
            }
            if (expandedBoxes.isNotEmpty()) {
                TextButton(onClick = { expandedBoxes.clear() }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text("開いた箱を戻す", fontSize = 11.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = { zoomAt(Offset(canvasSize.width / 2, canvasSize.height / 2), 1.25f) }) { Text("＋", fontSize = 13.sp) }
            TextButton(onClick = { zoomAt(Offset(canvasSize.width / 2, canvasSize.height / 2), 1 / 1.25f) }) { Text("－", fontSize = 13.sp) }
            TextButton(onClick = { fitAll() }) { Text("全体", fontSize = 11.sp) }
            DiagramGridControls(positionMemory.grid,
                scene != null && positionMemory.ready && positionMemory.writable && !positionMemory.saving && !computing && !arranging && dragging == null) {
                effective?.let { a -> state.saveDiagramPositions(context, positionMemory.positions + snapshot(a), it) }
            }
            TextButton(onClick = { pins.clear(); state.reloadDiagramPositions(context) }, enabled = positionMemory.ready && !positionMemory.saving) {
                Text("配置を再読込", fontSize = 11.sp)
            }
            if (positionMemory.saving) Text("配置を保存中…", fontSize = 11.sp)
            TextButton(onClick = { pins.clear(); rearrange++ }, enabled = positionMemory.ready && positionMemory.writable && !positionMemory.saving && !computing && !arranging) {
                Text("再配置", fontSize = 11.sp)
            }
            if (positionMemory.positions.isNotEmpty() || positionMemory.warning != null && positionMemory.writable) {
                TextButton(onClick = { pins.clear(); state.saveDiagramPositions(context, emptyMap()) }, enabled = positionMemory.writable && !positionMemory.saving && !computing && !arranging) {
                    Text("配置を初期化", fontSize = 11.sp)
                }
            }
            TextButton(onClick = { saveImage((if (level == LensLevel.TYPE) "schema-lens-" else "lens-") + (origin ?: "all")) }, enabled = scene != null) {
                Text("PNG に保存", fontSize = 11.sp)
            }
            // 型・関係の絞り込みと辺の一覧は右パネル。閉じていればここから開く
            if (!state.rightPaneOpen) {
                TextButton(onClick = { state.rightPaneOpen = true }) { Text("型・関係・辺 ▶", fontSize = 11.sp) }
            }
            Spacer(Modifier.weight(1f))
            Text("${nodes.size} 箱 / ${visibleEdges.count { it.kind != LensEdgeKind.TIE }} 辺", fontSize = 11.sp, color = Palette.SubText)
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
            DiagramLayoutOptionsMenu(LensLayoutOption.entries.map { option ->
                val applies = option.appliesTo(layoutKind)
                DiagramLayoutChoice(option.key, option.label, option.description, state.memory.lensLayoutOptions[option.name] == true, applies,
                    if (applies) null else "${option.kinds.sortedBy { it.ordinal }.joinToString("・") { it.label }}配置で効きます")
            }) { key, checked ->
                val option = LensLayoutOption.entries.first { it.key == key }
                if (checked) state.memory.lensLayoutOptions[option.name] = true else state.memory.lensLayoutOptions.remove(option.name)
            }
            DiagramLabelToggle(!state.memory.lensHideLabels) { state.memory.lensHideLabels = !it }
            Spacer(Modifier.width(8.dp))
            Text(if (fixed.isEmpty()) layoutKind.description else "再配置では未固定ノードだけを並べ直します（固定 ${fixed.size} 件）", fontSize = 10.sp, color = Palette.SubText)
            arranged?.result?.note?.let { note ->
                Spacer(Modifier.width(8.dp))
                Text(note, fontSize = 10.sp, color = Palette.WarnOrange)
            }
            if (computing) {
                Spacer(Modifier.width(8.dp))
                Text("配置を計算中…", fontSize = 10.sp, color = Palette.Primary)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 4.dp)) {
            val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            val current = arranged
            var fitted by remember(context) { mutableStateOf(false) }
            LaunchedEffect(current, widthPx, heightPx) {
                current?.takeIf { !fitted }?.let {
                    val bounds = scene?.let(::lensBounds) ?: return@let
                    val fit = fitViewport(bounds.width, bounds.height, widthPx, heightPx)
                    zoom = fit.zoom
                    pan = Offset(fit.panX - bounds.left * zoom, fit.panY - bounds.top * zoom)
                    fitted = true
                }
            }
            Canvas(
                Modifier.fillMaxSize().clipToBounds()
                    .testTag("lens-canvas").focusRequester(focus).onPreviewKeyEvent {
                        if (it.key == Key.Escape && it.type == KeyEventType.KeyDown && dragging != null) {
                            cancelled = true; pins.clear(); dragging = null; dragMotion = null; true
                        } else false
                    }.focusable()
                    .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
                    // ホバー・ホイール・右クリックは最初の経路で見る（タップの検出より先に受け、右クリックは消費してタップにしない）
                    .pointerInput(context) {
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
                    // ドラッグ: 箱の上なら箱を動かす（位置を固定。中の箱も一緒）、それ以外は全体を動かす
                    .pointerInput(context) {
                        detectDragGestures(
                            onDragStart = { startDrag(it) },
                            onDragEnd = { endDrag() },
                            onDragCancel = { pins.clear(); dragging = null; dragMotion = null },
                        ) { change, amount ->
                            change.consume()
                            moveDrag(amount)
                        }
                    }
                    .pointerInput(context) {
                        detectTapGestures { tap ->
                            // ズーム・パンを逆変換して箱の中を拾う。見出しの左の印なら開く・畳む、それ以外はエンティティページへ着地する
                            val a = latest ?: return@detectTapGestures
                            val index = hitNode(tap) ?: return@detectTapGestures
                            val node = a.nodes.getOrNull(index) ?: return@detectTapGestures
                            val box = a.result.boxes.getOrNull(index) ?: return@detectTapGestures
                            val header = a.nested.getOrNull(index)?.height ?: box.height
                            val c = toContent(tap)
                            if (hasChildren(node) && c.x <= box.x + TOGGLE_ZONE && c.y <= box.y + header) toggle(node)
                            else {
                                val gutter = if (hasChildren(node)) TOGGLE_ZONE else 0f
                                val location = a.bodies[node.node.key]?.locationAt(c - Offset(box.x + gutter, box.y))
                                if (location != null) state.openFile(location.document, location.path)
                                else if (level == LensLevel.TYPE) openTypeSchema(state, node.node.id)
                                else state.openCrossView(node.node.id)
                            }
                        }
                    }
            ) {
                val s = scene ?: return@Canvas
                if (positionMemory.grid.enabled) {
                    val a = s.arranged
                    val children = a.nested.indices.groupBy { a.nested[it].parent }
                    val regions = listOf(DiagramGridRegion(LensPoint(0f, 0f), excluded = a.result.boxes)) +
                        a.nodes.indices.filter { a.nodes[it].expanded }.map { i ->
                            val b = a.result.boxes[i]
                            val top = b.y + a.nested[i].height
                            DiagramGridRegion(LensPoint(b.x, top), LensBox(b.x, top, b.width, maxOf(0f, b.y + b.height - top)),
                                children[i].orEmpty().map { a.result.boxes[it] })
                        }
                    drawDiagramGrid(positionMemory.grid, pan, zoom, density.density, regions)
                }
                translate(pan.x, pan.y) {
                    scale(zoom, pivot = Offset.Zero) {
                        drawLens(s, zoom, hovered, adjacency)
                        s.arranged.nodes.forEachIndexed { i, n ->
                            if (n.node.key in fixed) s.arranged.result.boxes.getOrNull(i)?.let { b ->
                                drawCircle(paint.primary, 3f / zoom, Offset(b.x + b.width - 5f / zoom, b.y + 5f / zoom))
                            }
                        }
                    }
                }
            }
            if (nodes.isEmpty()) {
                Text(
                    if (level == LensLevel.TYPE) "表示する型がありません（スキーマの entity 宣言か関係レジストリが要ります。右パネルで型のチェックを増やすか、起点を解除してください）"
                    else "表示するエンティティがありません（右パネルで型のチェックを増やすか、起点を解除してください）",
                    fontSize = 12.sp, color = Palette.SubText, modifier = Modifier.align(Alignment.Center),
                )
            }
            // ミニマップ: 全体が画面に収まっていないときだけ右下に出す。押した・なぞった場所を画面の中央にする
            val a = effective
            val bounds = scene?.let(::lensBounds)
            if (a != null && bounds != null && (bounds.width * zoom > widthPx + 1f || bounds.height * zoom > heightPx + 1f)) {
                val mapWidth = with(density) { 200.dp.toPx() }
                val mapHeight = with(density) { 140.dp.toPx() }
                val mapScale = minOf((mapWidth - 8f) / bounds.width, (mapHeight - 8f) / bounds.height)
                val mapOx = (mapWidth - bounds.width * mapScale) / 2 - bounds.left * mapScale
                val mapOy = (mapHeight - bounds.height * mapScale) / 2 - bounds.top * mapScale
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
                        val n = a.nodes.getOrNull(i) ?: return@forEachIndexed
                        drawRect(
                            (typeColors[typeOf(n.node.id)] ?: mapPaint.primary).copy(alpha = if (n.expanded) 0.3f else 0.8f),
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
            // 右クリックのメニュー（箱の上で開く）
            menu?.let { (index, at) ->
                val node = effective?.nodes?.getOrNull(index)
                if (node == null) menu = null
                else {
                    val id = node.node.id
                    val entity = graph.entities[id]
                    val origin0 = node.node.occurrence ?: entity?.defining ?: entity?.occurrences?.firstOrNull()
                    val type = typeOf(id)
                    val typeInfo = schemaGraph.typeMap[id]
                    Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }) {
                        DropdownMenu(expanded = true, onDismissRequest = { menu = null }) {
                            if (level == LensLevel.TYPE) {
                                // 型の箱: 現れるスキーマを開く・実体の段へ（この型だけを見せる）・起点にする
                                Text(
                                    id + (typeInfo?.label?.let { "  $it" } ?: ""),
                                    fontSize = 11.sp, color = Palette.SubText, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                )
                                typeInfo?.appearances?.map { it.schemaId }?.distinct()?.forEach { sid ->
                                    DropdownMenuItem(text = { Text("スキーマを開く: $sid", fontSize = 12.sp) },
                                        onClick = { menu = null; state.schemas.firstOrNull { it.id == sid }?.let { state.openFile(it.source) } })
                                }
                                DropdownMenuItem(text = { Text("この型の実体を見る", fontSize = 12.sp) }, onClick = {
                                    menu = null
                                    // 実体の段へ移り、この型だけを見せる（他の型を隠す。起点は解除）
                                    hiddenTypes.clear()
                                    graph.entities.values.map { it.entityType ?: "（型なし）" }.distinct().forEach { t -> if (t != id) hiddenTypes[t] = true }
                                    state.lensOriginId = null
                                    state.memory.lensSchemaLevel = false
                                })
                            } else {
                                Text(
                                    id + (entity?.title?.takeIf { it.isNotBlank() && it != id }?.let { "  $it" } ?: "") +
                                        (node.node.occurrence?.let { " " + aspectLabelOf(it) } ?: ""),
                                    fontSize = 11.sp, color = Palette.SubText, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                )
                                DropdownMenuItem(text = { Text("エンティティページを開く", fontSize = 12.sp) },
                                    onClick = { menu = null; state.openCrossView(id) })
                            }
                            if (hasChildren(node)) {
                                DropdownMenuItem(
                                    text = { Text(if (node.expanded) "中の箱を畳む" else "中の箱を開く（${node.hiddenChildren}）", fontSize = 12.sp) },
                                    onClick = { menu = null; toggle(node) },
                                )
                            }
                            DropdownMenuItem(text = { Text("起点にする", fontSize = 12.sp) }, enabled = id != origin,
                                onClick = { menu = null; if (level == LensLevel.TYPE) state.memory.lensTypeOrigin = id else state.lensOriginId = id })
                            if (level == LensLevel.ENTITY && origin0 != null) {
                                DropdownMenuItem(text = { Text("原本へ（${origin0.document.value}）", fontSize = 12.sp) },
                                    onClick = { menu = null; state.openFile(origin0.document, origin0.path) })
                            }
                            DropdownMenuItem(
                                text = { Text("この型を隠す（${state.entityTypes[type]?.displayLabel ?: type}）", fontSize = 12.sp) },
                                enabled = id != origin && type !in hiddenTypes,
                                onClick = { menu = null; hiddenTypes[type] = true },
                            )
                            val idForPosition = nodeId(node.node)
                            if (positionMemory.positions[idForPosition]?.pinned == true) {
                                DropdownMenuItem(text = { Text("固定を解除", fontSize = 12.sp) }, onClick = {
                                    menu = null; pins.clear()
                                    positionMemory.positions[idForPosition]?.let { state.saveDiagramPositions(context, positionMemory.positions + (idForPosition to it.copy(pinned = false))) }
                                }, enabled = !positionMemory.saving)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ================================================================ 右パネル（型・関係・辺）

/**
 * レンズの右パネル: 型と関係の絞り込み（チェックボックス）と辺の一覧を、描く画面と同じ材料（`rememberLensModel`）から出す。
 * 台帳スペースでレンズを表示しているとき、文書の検証パネルの代わりに右ペインに出す（Main の切替）。タブは画面の覚え。
 */
@Composable
fun LensSidePanel(state: AppState, width: Dp) {
    val model = rememberLensModel(state)
    val hiddenTypes = state.memory.lensHiddenTypes
    val hiddenRelations = state.memory.lensHiddenRelations
    val tab = state.memory.lensPanelTab
    val listed = remember(model) { model.visibleEdges.filter { it.kind != LensEdgeKind.TIE } }
    Column(Modifier.width(width).fillMaxHeight().background(Palette.PanelBg)) {
        TabRow(selectedTabIndex = tab, containerColor = Palette.PanelBg, contentColor = Palette.Primary) {
            Tab(tab == 0, { state.memory.lensPanelTab = 0 }, text = { Text("型 ${model.types.size - hiddenTypes.size}/${model.types.size}", fontSize = 11.sp) })
            Tab(tab == 1, { state.memory.lensPanelTab = 1 }, text = { Text("関係 ${model.relations.size - hiddenRelations.size}/${model.relations.size}", fontSize = 11.sp) })
            Tab(tab == 2, { state.memory.lensPanelTab = 2 }, text = { Text("辺 ${listed.size}", fontSize = 11.sp) })
        }
        Box(Modifier.fillMaxSize()) {
            val scroll = rememberLazyListState()
            LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().padding(end = 8.dp)) {
                when (tab) {
                    // ---------------- 型（色の凡例つき。隠した型の箱は出ない。起点は隠せない） ----------------
                    0 -> {
                        item { PanelActions(
                            "すべて" to { hiddenTypes.clear() },
                            "すべて隠す" to { model.types.forEach { t -> if (t != model.typeOf(model.origin ?: "")) hiddenTypes[t] = true } },
                        ) }
                        items(model.types) { t ->
                            PanelCheckRow(t !in hiddenTypes, { checked -> if (checked) hiddenTypes.remove(t) else hiddenTypes.put(t, true) }) {
                                Box(Modifier.size(10.dp).background(model.typeColors[t] ?: Palette.Primary, PanelShape))
                                Spacer(Modifier.width(6.dp))
                                Text(state.entityTypes[t]?.displayLabel ?: t, fontSize = 11.sp)
                            }
                        }
                    }
                    // ---------------- 関係（保有の名前には印。隠すと辺が消え、保有なら入れ子が解ける） ----------------
                    1 -> {
                        item { PanelActions("すべて" to { hiddenRelations.clear() }, "すべて隠す" to { model.relations.forEach { hiddenRelations[it] = true } }) }
                        items(model.relations) { rel ->
                            PanelCheckRow(rel !in hiddenRelations, { checked -> if (checked) hiddenRelations.remove(rel) else hiddenRelations.put(rel, true) }) {
                                Text(
                                    (Relations.labelOf(state.relations, rel.takeIf { it != UNDECLARED }) ?: rel) +
                                        (if (rel in model.holdingNames) "（保有）" else "") +
                                        (if (rel in model.reversed) "（矢印は逆向き）" else ""),
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                    // ---------------- 辺（見えている辺。クリックで相手のエンティティページへ） ----------------
                    else -> {
                        if (listed.isEmpty()) item { Text("見えている辺はありません", Modifier.padding(12.dp), fontSize = 11.sp, color = Palette.SubText) }
                        items(listed) { edge ->
                            val toId = model.idOf(edge.to)
                            val open: () -> Unit = { if (model.level == LensLevel.TYPE) openTypeSchema(state, toId) else state.openCrossView(toId) }
                            Column(Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = 12.dp, vertical = 4.dp)) {
                                Text(model.idOf(edge.from), fontSize = 11.sp, color = Palette.Primary, maxLines = 1)
                                Text(
                                    if (edge.kind == LensEdgeKind.HOLDING) "◆ $toId（保有: ${Relations.labelOf(state.relations, edge.relation) ?: edge.relation}）"
                                    else "→ $toId" + (lensEdgeLabel(state.relations, edge)?.let { "（関係: $it）" } ?: ""),
                                    fontSize = 11.sp, color = Palette.SubText, maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/** 右パネルの一括操作の行（「すべて」「すべて隠す」）。 */
@Composable
private fun PanelActions(vararg actions: Pair<String, () -> Unit>) {
    Row(Modifier.padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        actions.forEach { (label, onClick) ->
            TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text(label, fontSize = 11.sp) }
        }
    }
}

/** 右パネルのチェック 1 行。 */
@Composable
private fun PanelCheckRow(checked: Boolean, onChange: (Boolean) -> Unit, label: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onChange, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(4.dp))
        label()
    }
}

/** ツールバーの 1 項目（チェックボックスとラベル。折り返しで分かれないように 1 つにまとめる）。 */
@Composable
private fun LensFilterItem(checked: Boolean, onChange: (Boolean) -> Unit, label: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, modifier = Modifier.size(26.dp))
        label()
    }
}
