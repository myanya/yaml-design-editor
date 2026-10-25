package dev.toros.yde.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.FormDisplayMode
import dev.toros.yde.yaml.model.YPath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull

/** 一時的なレイアウト座標は画面の寿命だけに閉じ、文書や履歴に書かない。 */
internal class FormPositions(val scroll: ScrollState) {
    var viewportTop = 0f
    val bounds = mutableStateMapOf<YPath, Pair<Float, Float>>()
    private val owners = mutableMapOf<YPath, Any>()
    fun place(path: YPath, owner: Any, top: Float, height: Float) {
        val value = (top - viewportTop + scroll.value) to (top - viewportTop + scroll.value + height)
        // 同じパスが複数描画される文書ビューでは、先に登録された表示を移動先にする。
        if (owners[path] == null || owners[path] === owner) {
            owners[path] = owner
            if (bounds[path] != value) bounds[path] = value
        }
    }
    fun remove(path: YPath, owner: Any) {
        if (owners[path] === owner) { owners.remove(path); bounds.remove(path) }
    }

    fun activePath(paths: Set<YPath>): YPath? =
        activeFormPath(bounds.filterKeys { it in paths }, scroll.value + 24f)

    /** 移動の終了・折り畳みの再配置でも、現在位置から選び直す。 */
    fun observeActivePath(paths: () -> Set<YPath>, paused: () -> Boolean) = snapshotFlow {
        if (paused()) null else activePath(paths())
    }
}

internal val LocalFormPositions = staticCompositionLocalOf<FormPositions?> { null }

@Composable
internal fun FormAnchor(path: YPath, content: @Composable () -> Unit) {
    val positions = LocalFormPositions.current
    val owner = remember(path, positions) { Any() }
    DisposableEffect(path, positions) { onDispose { positions?.remove(path, owner) } }
    Column(Modifier.fillMaxWidth().onGloballyPositioned {
        positions?.place(path, owner, it.positionInRoot().y, it.size.height.toFloat())
    }) { content() }
}

/** スキーマと通常文書が共有する構成一覧・狭幅切替・全体フォームのスクロール連動。 */
@Composable
internal fun StructuredForm(
    ctx: FormCtx, schema: Boolean, items: List<FormOutlineItem>, selected: YPath?, onSelect: (YPath) -> Unit,
    reveal: (YPath) -> Unit,
    onActivate: (YPath) -> Unit = {},
    beforeItem: @Composable (FormOutlineItem) -> Unit = {},
    treeFooter: @Composable () -> Unit = {},
    treeActions: @Composable () -> Unit = {},
    selectedContent: @Composable (Modifier) -> Unit,
    wholeContent: @Composable () -> Unit,
) {
    val document = ctx.state.documentOf(ctx.doc.file)
    val memory = ctx.state.memory.navigation(document, schema)
    val display = ctx.state.memory.formDisplay
    val mode = display.modeFor(document)
    val scroll = remember(ctx.doc) { ScrollState(0) }
    val positions = remember(ctx.doc) { FormPositions(scroll) }
    var request by remember(ctx.doc) { mutableStateOf<Pair<Int, YPath>?>(null) }
    var serial by remember(ctx.doc) { mutableIntStateOf(0) }
    var moving by remember(ctx.doc) { mutableStateOf(false) }
    var viewportWidth by remember(ctx.doc) { mutableIntStateOf(0) }
    var followed: YPath? by remember(ctx.doc) { mutableStateOf(null) }
    val currentSelect by rememberUpdatedState(onSelect)
    val currentSelected by rememberUpdatedState(selected)
    val currentItems by rememberUpdatedState(items)
    fun select(path: YPath) {
        followed = null
        onSelect(path)
        onActivate(path)
        memory.navigationOpen = false
        items.filter { it.path.size < path.size && path.take(it.path.size) == it.path }
            .forEach { memory.expanded[it.path] = true }
        if (mode != FormDisplayMode.SELECTED) { reveal(path); request = ++serial to path }
    }
    LaunchedEffect(ctx.doc, mode, viewportWidth) {
        if (mode != FormDisplayMode.SELECTED && selected != null) { reveal(selected); request = ++serial to selected }
    }
    LaunchedEffect(ctx.doc, selected) {
        if (mode != FormDisplayMode.SELECTED && selected != null && selected != followed) { reveal(selected); request = ++serial to selected }
    }
    LaunchedEffect(ctx.doc, ctx.state.highlightPath) {
        ctx.state.highlightPath?.let { target -> nearestFormPath(items.map { it.path }, target)?.let(::select) }
    }
    LaunchedEffect(ctx.doc, request, mode, memory.navigationOpen) {
        val target = request?.second ?: return@LaunchedEffect
        if (mode == FormDisplayMode.SELECTED || memory.navigationOpen) return@LaunchedEffect
        moving = true
        try {
            // 祖先の展開による再配置を待ってから、見出しを上端へ合わせる。
            withFrameNanos { }
            val at = withTimeoutOrNull(800) {
                snapshotFlow { positions.bounds[target]?.first }.first { it != null }
            } ?: nearestFormPath(positions.bounds.keys, target)?.let { positions.bounds[it]?.first }
            if (at != null) scroll.scrollTo((at - 8f).toInt().coerceAtLeast(0))
            withFrameNanos { }
        } finally { moving = false }
    }
    LaunchedEffect(ctx.doc, mode) {
        if (mode == FormDisplayMode.SELECTED) return@LaunchedEffect
        val paths = { currentItems.map { it.path }.toSet() }
        val paused = { moving || memory.navigationOpen }
        positions.observeActivePath(paths, paused).collectLatest { path ->
            if (path == null) return@collectLatest
            // スクロールと再配置が揃った最新の位置だけを反映する。
            withFrameNanos { }
            if (paused() || positions.activePath(paths()) != path) return@collectLatest
            followed = path
            if (currentSelected != path) currentSelect(path)
            if (mode == FormDisplayMode.LINKED) currentItems.filter {
                it.path.size < path.size && path.take(it.path.size) == it.path
            }.forEach { memory.expanded[it.path] = true }
        }
    }
    val tree: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.background(Palette.PanelBg, PanelShape).border(1.dp, Palette.Border, PanelShape)) {
            Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("構成", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                treeActions()
                UiTooltip("構成を閉じて全体のみ表示") {
                    IconButton(onClick = {
                        display.choose(document, FormDisplayMode.WHOLE)
                        memory.navigationOpen = false
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "構成を閉じて全体のみ表示", Modifier.size(18.dp))
                    }
                }
            }
            val treeScroll = remember(ctx.doc) { ScrollState(0) }
            Box(Modifier.weight(1f)) {
                Column(Modifier.fillMaxSize().verticalScroll(treeScroll).padding(end = 10.dp)) {
                    val byPath = items.associateBy { it.path }
                    val parents = items.mapNotNull { it.parent }.toSet()
                    val visible = mutableSetOf<YPath>()
                    items.forEach { item ->
                        if (item.parent == null || item.parent in visible && (memory.expanded[item.parent] ?: ((byPath[item.parent]?.depth ?: 0) < 2)))
                            visible.add(item.path)
                    }
                    val activePath = nearestFormPath(visible, selected)
                    items.forEach { item -> key(item.path) {
                        beforeItem(item)
                        if (item.path in visible) {
                            val children = item.path in parents
                            val expanded = memory.expanded[item.path] ?: (item.depth < 2)
                            val active = activePath == item.path
                            val requester = remember { BringIntoViewRequester() }
                            LaunchedEffect(ctx.doc, selected, active) { if (active) requester.bringIntoView() }
                            Row(Modifier.fillMaxWidth().bringIntoViewRequester(requester)
                                .background(if (active) Palette.Selected else Palette.PanelBg)
                                .selectable(active, role = Role.RadioButton, onClick = { select(item.path) })
                                .padding(start = (item.depth * 12).coerceAtMost(48).dp, end = 8.dp).heightIn(min = 44.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (children) IconButton(onClick = { memory.expanded[item.path] = !expanded }, modifier = Modifier.size(32.dp)) {
                                    Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        "${item.title}の子項目を開閉", Modifier.size(18.dp))
                                } else Spacer(Modifier.width(32.dp))
                                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                    Text(item.title, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (item.subtitle.isNotEmpty()) Text(item.subtitle, fontSize = 11.sp, color = Palette.SubText,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                item.badge?.let { Text(it, fontSize = 11.sp, color = Palette.Primary) }
                            }
                        }
                    } }
                    treeFooter()
                }
                VerticalScrollbar(rememberScrollbarAdapter(treeScroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
    val detail: @Composable (Modifier) -> Unit = { modifier ->
        if (mode == FormDisplayMode.SELECTED) selectedContent(modifier)
        else Box(modifier.background(Palette.Surface, PanelShape).border(1.dp, Palette.Border, PanelShape)
            .onGloballyPositioned {
                positions.viewportTop = it.positionInRoot().y
                viewportWidth = it.size.width
            }) {
            CompositionLocalProvider(LocalFormPositions provides positions) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp)) {
                    wholeContent()
                    Spacer(Modifier.height(80.dp))
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)) {
        val treeWidth = (maxWidth * .32f).coerceAtMost(300.dp)
        if (mode == FormDisplayMode.WHOLE) detail(Modifier.fillMaxSize())
        else if (maxWidth >= 680.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tree(Modifier.width(treeWidth).fillMaxHeight())
            detail(Modifier.weight(1f).fillMaxHeight())
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { memory.navigationOpen = !memory.navigationOpen }, modifier = Modifier.height(36.dp), shape = PanelShape) {
                Icon(Icons.Default.AccountTree, null, Modifier.size(16.dp))
                Text(if (memory.navigationOpen) " 編集に戻る" else " 構成を開く", fontSize = 12.sp)
                Text(" · " + items.firstOrNull { it.path == selected }?.title.orEmpty(), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (memory.navigationOpen) tree(Modifier.fillMaxWidth().weight(1f))
            else detail(Modifier.fillMaxWidth().weight(1f))
        }
    }
}

/** 囲み枠を重ねず、通常文書と同じ見出し・縦線で折り畳む。 */
@Composable
internal fun FormFold(title: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = FormSpacing.FieldGap)) {
        FormFoldHeader(title, expanded, onToggle)
        if (expanded) FormGroup(nested = true) { content() }
    }
}

@Composable
internal fun FormFoldHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    TextButton(onClick = onToggle, shape = PanelShape, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) {
        Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            if (expanded) "折り畳む" else "展開する", Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(title, fontSize = 12.sp, color = Palette.SubText, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
