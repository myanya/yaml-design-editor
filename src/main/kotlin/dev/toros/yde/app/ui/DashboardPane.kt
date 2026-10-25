package dev.toros.yde.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.AppState
import dev.toros.yde.app.core.Dashboards
import dev.toros.yde.app.core.Views
import dev.toros.yde.semantic.DashboardSectionResult
import dev.toros.yde.semantic.VirtualDashboardDef
import java.time.LocalDate
import kotlinx.coroutines.delay

/** 業務の入口と詳細は同じスペースに置き、原本を開くときだけ編集へ移る。 */
@Composable
fun DashboardPane(state: AppState) {
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
        val result = remember(view, catalog, date, state.indexVersion, state.editVersion) {
            Dashboards.resolve(view, date, catalog, state.overlayIndex(), state::schemaFor, state.unsavedFirstTree())
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
            Row(Modifier.fillMaxSize()) {
                if (entity == null || split) Column(Modifier.weight(if (split) .4f else 1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        result.sections.forEach { item ->
                            val selected = section.definition.id == item.definition.id
                            Column(Modifier.widthIn(min = 145.dp)
                                .border(1.dp, if (selected) Palette.Primary else Palette.Border, PanelShape)
                                .background(if (selected) Palette.Selected else Palette.Surface, PanelShape)
                                .clickable { state.memory.dashboardSection[view.id] = item.definition.id }.padding(12.dp)) {
                                Text(item.definition.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(if (item.error == null) item.count + " 行" else "表示できません", fontSize = 19.sp,
                                    color = if (item.error == null) Palette.Primary else Palette.WarnOrange)
                                if (item.pending.isNotEmpty()) Text("判定できない対象: " + item.pending.size + " 行", fontSize = 11.sp, color = Palette.WarnOrange)
                            }
                        }
                    }
                    Text(section.definition.label, Modifier.padding(12.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    section.error?.let { Text(it, Modifier.padding(12.dp), color = Palette.WarnOrange, fontSize = 12.sp) }
                    PendingRows(state, section)
                    if (section.error == null && section.rows.isEmpty()) Text("該当する対象はありません", Modifier.padding(16.dp), color = Palette.SubText)
                    val sectionKey = view.id to section.definition.id
                    section.view?.let { grid -> key(sectionKey) {
                        Box(Modifier.weight(1f)) {
                            ResolvedViewGrid(state, grid, section.rows, section.selected(state.memory.dashboardRow[sectionKey])?.key,
                                position = state.memory.dashboardScroll.getOrPut(sectionKey) { dev.toros.yde.app.TableScrollPosition() }) { row ->
                                state.memory.dashboardRow[sectionKey] = row.key
                                state.openDashboardEntity(row.id)
                            }
                        }
                    } }
                }
                if (entity != null) Column(Modifier.weight(if (split) .6f else 1f).fillMaxHeight()) {
                    if (state.overlayIndex().occurrences(entity).isEmpty()) {
                        Text("対象「" + entity + "」は削除されたか、現在の索引にありません。一覧へ戻るか、Undoで確認してください。",
                            Modifier.padding(16.dp), color = Palette.WarnOrange)
                    } else key(view.id, entity) { EntityPage(state, entity, business = true) }
                }
            }
        }
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

/** 開いている業務対象だけを使い、削除された対象は使わない。 */
internal fun dashboardActionTarget(state: AppState): String? =
    state.dashboardEntityId?.takeIf { state.overlayIndex().occurrences(it).isNotEmpty() }
