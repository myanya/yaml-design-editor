package dev.toros.yde.desktop.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.core.Dashboards
import dev.toros.yde.app.core.Views
import dev.toros.yde.semantic.DashboardInput
import dev.toros.yde.semantic.DashboardInputType
import dev.toros.yde.semantic.DashboardPlacement
import dev.toros.yde.semantic.DashboardResolution
import dev.toros.yde.semantic.DashboardSection
import dev.toros.yde.semantic.DashboardSectionResult
import dev.toros.yde.semantic.VirtualDashboardDef
import dev.toros.yde.semantic.sectionGroups
import java.time.LocalDate
import kotlin.math.ceil
import kotlinx.coroutines.delay
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.TableScrollPosition
import dev.toros.yde.desktop.ui.common.ActionMenuItem
import dev.toros.yde.desktop.ui.common.CompactTextField
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PanelShape

/**
 * 業務の入口と詳細は同じスペースに置き、原本を開くときだけ編集へ移る。
 * 詳細（エンティティページ）は骨格が entityPage で渡す（ui.view は ui.graph を import しない）。
 */
@Composable
fun DashboardPane(state: AppState, entityPage: @Composable (id: String) -> Unit) {
    val ws = state.workspace ?: return
    val catalog = remember(ws, state.indexVersion, state.editVersion) {
        Views.load(ws, state.schemas, state.unsavedFirstTree())
    }
    val view = state.dashboardId?.let(catalog::dashboard) ?: catalog.defaultDashboard()
    var today by remember(ws) { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(ws) {
        while (true) { delay(30_000); today = LocalDate.now() }
    }
    Column(Modifier.fillMaxSize().background(Palette.EditorBg)) {
        DashboardHeader(state, catalog, view, today)
        if (catalog.errors.isNotEmpty()) Text("定義エラー: " + catalog.errors.joinToString(" ／ "),
            Modifier.fillMaxWidth().padding(8.dp), fontSize = 11.sp, color = Palette.WarnOrange)
        if (view == null) {
            Text("ダッシュボード定義がありません。設計スペースで kind: dashboard のビューを作成してください。",
                Modifier.padding(16.dp), fontSize = 13.sp, color = Palette.SubText)
            return@Column
        }
        val date = state.memory.dashboardDate[view.id] ?: today
        // 共有の入力: 画面の覚えにある鍵だけ渡す（無い鍵は定義の初期値。空文字＝絞らない）
        val inputs = view.inputs.mapNotNull { i -> state.memory.dashboardInputs[view.id to i.key]?.let { i.key to it } }.toMap()
        val result = remember(view, catalog, date, inputs, state.indexVersion, state.editVersion) {
            Dashboards.resolve(view, date, catalog, state.overlayIndex(), state::schemaFor, state.unsavedFirstTree(), inputs)
        }
        val section = result.sections.firstOrNull { it.definition.id == state.memory.dashboardSection[view.id] }
            ?: result.sections.firstOrNull() ?: return@Column
        val entity = state.dashboardEntityId
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (entity != null) {
                TextButton(enabled = state.canGoBack, onClick = { state.goBack() }) { Text("戻る") }
                TextButton(onClick = { state.showDashboardOverview() }) { Text("一覧へ") }
                Text(view.name + " ／ 詳細", fontSize = 12.sp, modifier = Modifier.weight(1f))
            } else Text(view.description ?: view.name, fontSize = 12.sp, color = Palette.SubText, modifier = Modifier.weight(1f))
            TextButton(onClick = { state.memory.dashboardSplit[view.id] = state.memory.dashboardSplit[view.id] != true }) {
                Text(if (state.memory.dashboardSplit[view.id] == true) "全幅表示" else "一覧と並べる", fontSize = 12.sp)
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val split = state.memory.dashboardSplit[view.id] == true && maxWidth >= 1000.dp && entity != null
            // 一覧（タイルと表）に使える幅。分割時は 4:6 の 4
            val listWidth = if (split) maxWidth * .4f else maxWidth
            Row(Modifier.fillMaxSize()) {
                if (entity == null || split) {
                    // 区画のタイルの並べ方は定義の placement。auto は、一覧の幅に横へ折り返して 3 段以上になるときだけ縦の一覧にする
                    val groups = view.sectionGroups()
                    val vertical = when (view.placement) {
                        DashboardPlacement.VERTICAL -> true
                        DashboardPlacement.HORIZONTAL -> false
                        // 分割中（一覧 4：詳細 6）は、縦にすると表が狭くなるので横に決める
                        DashboardPlacement.AUTO -> !split && estimatedTileRows(result.sections.size, groups.count { it.first != null }, listWidth) >= 3
                    }
                    if (vertical) Row(Modifier.weight(if (split) .4f else 1f).fillMaxHeight()) {
                        // 縦の一覧の幅。分割中は表を潰さないように狭める
                        Column(Modifier.width(if (split) 170.dp else 220.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(8.dp)) {
                            SectionTiles(state, view, result, section, vertical = true)
                        }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Border))
                        Column(Modifier.weight(1f).fillMaxHeight()) { SectionBody(state, view, section) }
                    } else Column(Modifier.weight(if (split) .4f else 1f).fillMaxHeight()) {
                        Box(Modifier.fillMaxWidth().padding(8.dp)) { SectionTiles(state, view, result, section, vertical = false) }
                        SectionBody(state, view, section)
                    }
                }
                if (entity != null) Column(Modifier.weight(if (split) .6f else 1f).fillMaxHeight()) {
                    if (state.overlayIndex().occurrences(entity).isEmpty()) {
                        Text("対象「" + entity + "」は削除されたか、現在の索引にありません。一覧へ戻るか、Undoで確認してください。",
                            Modifier.padding(16.dp), color = Palette.WarnOrange)
                    } else key(view.id, entity) { entityPage(entity) }
                }
            }
        }
    }
}

/** placement: auto の判定に使う、横に折り返して並べたときの段数の見積もり（タイル 1 枚 150dp・群の見出し 80dp）。 */
private fun estimatedTileRows(tiles: Int, groups: Int, width: Dp): Int =
    ceil((tiles * 150f + groups * 80f) / width.value.coerceAtLeast(1f)).toInt()

/** 選んだ区画の見出し・判定できない対象・表（またはチャート）。 */
@Composable
private fun ColumnScope.SectionBody(state: AppState, view: VirtualDashboardDef, section: DashboardSectionResult) {
    Text(section.definition.label, Modifier.padding(12.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    section.error?.let { Text(it, Modifier.padding(12.dp), color = Palette.WarnOrange, fontSize = 12.sp) }
    PendingRows(state, section)
    if (section.error == null && section.embedded == null && section.rows.isEmpty()) Text("該当する対象はありません", Modifier.padding(16.dp), color = Palette.SubText)
    val sectionKey = view.id to section.definition.id
    // 拡張の種類の区画（チャートなど。ADR-0014）: 表の代わりにその種類の描画。材料の ID は業務詳細へ（表の行と同じ行き先）
    section.embedded?.let { result -> key(sectionKey) {
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
            ExtensionViewContent(state, result, ViewPlace.DASHBOARD, onOpenEntity = { state.openDashboardEntity(it) })
        }
    } }
    section.view?.let { grid -> key(sectionKey) {
        Box(Modifier.weight(1f)) {
            ResolvedViewGrid(state, grid, section.rows, section.selected(state.memory.dashboardRow[sectionKey])?.key,
                position = state.memory.dashboardScroll.getOrPut(sectionKey) { dev.toros.yde.desktop.TableScrollPosition() }) { row ->
                state.memory.dashboardRow[sectionKey] = row.key
                state.openDashboardEntity(row.id)
            }
        }
    } }
}

/**
 * 区画のタイル。群（sections[].group）ごとに見出しを付け、見出しを押すと畳む（畳みは ScreenMemory の覚え）。
 * 横＝折り返して並べる（群の見出しもタイルの列に混ぜる）。縦＝左の一覧（1 区画 1 行）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectionTiles(state: AppState, view: VirtualDashboardDef, result: DashboardResolution, selected: DashboardSectionResult, vertical: Boolean) {
    val byId = result.sections.associateBy { it.definition.id }
    @Composable fun group(name: String?, items: List<DashboardSection>, headerModifier: Modifier, tileModifier: Modifier) {
        val collapsed = name != null && state.memory.dashboardGroupCollapsed[view.id to name] == true
        if (name != null) GroupHeader(name, items.size, collapsed, headerModifier) { state.memory.dashboardGroupCollapsed[view.id to name] = !collapsed }
        if (!collapsed) items.forEach { def -> byId[def.id]?.let { item ->
            SectionTile(state, view, item, item.definition.id == selected.definition.id, line = vertical, tileModifier)
        } }
    }
    val groups = view.sectionGroups()
    if (vertical) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        groups.forEach { (name, items) -> group(name, items, Modifier.fillMaxWidth(), Modifier.fillMaxWidth()) }
    } else FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        groups.forEach { (name, items) -> group(name, items, Modifier.heightIn(min = 52.dp), Modifier.widthIn(min = 120.dp)) }
    }
}

@Composable
private fun GroupHeader(name: String, size: Int, collapsed: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    Row(modifier.then(if (collapsed) Modifier.border(1.dp, Palette.Border, PanelShape) else Modifier)
        .clickable(onClick = onToggle).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text((if (collapsed) "▸ " else "▾ ") + name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.SubText)
        if (collapsed) Text("（$size）", fontSize = 11.sp, color = Palette.SubText)
    }
}

/** 1 区画のタイル（見出しと件数）。line＝縦の一覧の 1 行、そうでなければ小さな箱。 */
@Composable
private fun SectionTile(state: AppState, view: VirtualDashboardDef, item: DashboardSectionResult, selected: Boolean, line: Boolean, modifier: Modifier) {
    val base = modifier
        .border(1.dp, if (selected) Palette.Primary else Palette.Border, PanelShape)
        .background(if (selected) Palette.Selected else Palette.Surface, PanelShape)
        .clickable { state.memory.dashboardSection[view.id] = item.definition.id }
    val count = if (item.error == null) item.count + " " + (item.embedded?.unit ?: "行") +
        (item.truncation?.let { "（limit ${it.limit} で打ち切り）" } ?: "") else "表示できません"
    val countColor = if (item.error == null) Palette.Primary else Palette.WarnOrange
    if (line) Column(base.padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.definition.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(count, fontSize = 13.sp, color = countColor)
        }
        if (item.pending.isNotEmpty()) Text("判定できない対象: " + item.pending.size + " 行", fontSize = 10.sp, color = Palette.WarnOrange)
    } else Column(base.padding(8.dp)) {
        Text(item.definition.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(count, fontSize = 16.sp, color = countColor)
        if (item.pending.isNotEmpty()) Text("判定できない対象: " + item.pending.size + " 行", fontSize = 10.sp, color = Palette.WarnOrange)
    }
}

@Composable
private fun PendingRows(state: AppState, section: DashboardSectionResult) {
    if (section.pending.isEmpty()) return
    Text("判定できない対象（件数には含めません）", Modifier.padding(horizontal = 12.dp), fontSize = 11.sp, color = Palette.WarnOrange)
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 110.dp).padding(horizontal = 12.dp)) {
        items(section.pending, key = { it.row.key }) { item ->
            TextButton(onClick = { state.openCrossView(item.row.id) }) {
                Text("${item.row.id}: ${item.reason}", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun DashboardHeader(state: AppState, catalog: Views.LoadResult, view: VirtualDashboardDef?, today: LocalDate) {
    var chooser by remember { mutableStateOf(false) }
    var actionsOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(Palette.Surface).horizontalScroll(rememberScrollState()).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(enabled = catalog.dashboards.isNotEmpty(), onClick = { chooser = true }) { Text(view?.name ?: "ダッシュボード") }
            DropdownMenu(chooser, { chooser = false }) {
                catalog.dashboards.forEach { item -> DropdownMenuItem(text = { Text(item.name) },
                    onClick = { chooser = false; state.openDashboard(item.id) }) }
            }
        }
        if (view != null) {
            val date = state.memory.dashboardDate[view.id] ?: today
            var input by remember(view.id, date) { mutableStateOf(date.toString()) }
            var error by remember(view.id) { mutableStateOf<String?>(null) }
            Text("基準日", fontSize = 12.sp)
            CompactTextField(input, { input = it; error = null }, singleLine = true, modifier = Modifier.width(120.dp))
            TextButton(onClick = {
                runCatching { LocalDate.parse(input) }.onSuccess { state.memory.dashboardDate[view.id] = it; error = null }
                    .onFailure { error = "YYYY-MM-DDで入力してください" }
            }) { Text("反映") }
            TextButton(onClick = { state.memory.dashboardDate.remove(view.id); input = today.toString(); error = null }) { Text("本日") }
            error?.let { Text(it, fontSize = 11.sp, color = Palette.ErrorRed) }
            DashboardInputs(state, view, date)
            if (view.actions.isNotEmpty()) Box {
                OutlinedButton(onClick = { actionsOpen = true }) { Text("操作") }
                DropdownMenu(actionsOpen, { actionsOpen = false }) {
                    val available = state.actionsForWorkspace()
                    view.actions.forEach { name ->
                        val entry = available.singleOrNull { it.name == name }
                        if (entry == null) DropdownMenuItem(enabled = false, text = { Text("$name（対象なしで開始できる操作がありません）") }, onClick = {})
                        else ActionMenuItem(state, entry, null) { actionsOpen = false; state.startAction(entry, null) }
                    }
                }
            }
            Text("未保存の編集も表示に反映", fontSize = 11.sp, color = Palette.SubText)
            val dirty = state.dirtyDocCount()
            if (dirty > 0) TextButton(onClick = { state.saveAll() }) { Text("すべて保存（$dirty）") }
            if (state.actionWork != null) TextButton(onClick = { state.showActionPanel() }) { Text("操作の入力・結果を開く") }
        }
    }
}

/**
 * 共有の入力（inputs）の欄。文字と日付は「反映」で効かせ、選択はすぐ効く。空は「絞らない」。
 * 欄の値は画面の覚え（鍵が無ければ定義の初期値を見せる）。「入力を戻す」で初期値に戻す。
 */
@Composable
private fun DashboardInputs(state: AppState, view: VirtualDashboardDef, date: LocalDate) {
    if (view.inputs.isEmpty()) return
    val texts = remember(view.id) { mutableStateMapOf<String, String>() }
    fun effective(input: DashboardInput): String = state.memory.dashboardInputs[view.id to input.key] ?: input.defaultFor(date).orEmpty()
    view.inputs.forEach { input ->
        Text(input.label, fontSize = 12.sp)
        if (input.type == DashboardInputType.ENUM) {
            var open by remember(view.id, input.key) { mutableStateOf(false) }
            val current = effective(input)
            Box {
                OutlinedButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                    Text(input.values.firstOrNull { it.first == current }?.let { it.second ?: it.first } ?: current.ifEmpty { "（すべて）" }, fontSize = 12.sp)
                }
                DropdownMenu(open, { open = false }) {
                    DropdownMenuItem(text = { Text("（すべて）") }, onClick = { open = false; state.memory.dashboardInputs[view.id to input.key] = "" })
                    input.values.forEach { (v, label) ->
                        DropdownMenuItem(text = { Text(label ?: v) }, onClick = { open = false; state.memory.dashboardInputs[view.id to input.key] = v })
                    }
                }
            }
        } else {
            CompactTextField(
                texts[input.key] ?: effective(input), { texts[input.key] = it }, singleLine = true,
                modifier = Modifier.width(if (input.type == DashboardInputType.DATE) 110.dp else 150.dp),
            )
        }
    }
    if (view.inputs.any { it.type != DashboardInputType.ENUM }) TextButton(onClick = {
        view.inputs.filter { it.type != DashboardInputType.ENUM }.forEach { input ->
            state.memory.dashboardInputs[view.id to input.key] = texts[input.key] ?: effective(input)
        }
    }) { Text("反映") }
    TextButton(onClick = {
        view.inputs.forEach { state.memory.dashboardInputs.remove(view.id to it.key) }
        texts.clear()
    }) { Text("入力を戻す") }
}
