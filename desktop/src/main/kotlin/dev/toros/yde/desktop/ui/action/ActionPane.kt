package dev.toros.yde.desktop.ui.action

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import dev.toros.yde.app.index.IdEntry
import dev.toros.yde.workspace.ActionEntry
import dev.toros.yde.workspace.ActionPlan
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.validate.Severity
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.OpenDocument
import dev.toros.yde.desktop.UiSpace
import dev.toros.yde.desktop.ui.common.ChoiceMenu
import dev.toros.yde.desktop.ui.common.ChoiceOption
import dev.toros.yde.desktop.ui.common.CompactTextField
import dev.toros.yde.desktop.ui.common.DiffLineRow
import dev.toros.yde.desktop.ui.common.MapSlot
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PanelShape
import dev.toros.yde.desktop.ui.common.calculateDiff
import dev.toros.yde.desktop.ui.form.FieldRow
import dev.toros.yde.desktop.ui.form.FormCtx
import dev.toros.yde.desktop.ui.form.activeActionTarget
import dev.toros.yde.desktop.ui.view.dashboardActionTarget

data class ActionPanelContext(val document: OpenDocument?, val targetId: String?, val elementTypes: List<String>)

/** 表示中の画面の選択を入口の対象にする。開始済みの ActionWork は書き換えない。repo-tests の DashboardsTest も読むので公開。 */
fun actionPanelContext(state: AppState, doc: OpenDocument?, localIds: List<IdEntry>): ActionPanelContext {
    if (state.uiSpace == UiSpace.DASHBOARD) {
        return ActionPanelContext(null, dashboardActionTarget(state), emptyList())
    }
    if (state.uiSpace == UiSpace.LEDGER && state.workspaceView == 3) {
        val selected = state.crossViewId?.takeIf { state.overlayIndex().occurrences(it).isNotEmpty() }
        // 横ぐしは文書をまたぐ画面。背後で開いている文書や、その行選択へはフォールバックしない。
        return ActionPanelContext(null, selected, emptyList())
    }
    val chosen = doc?.let { state.memory.navigation(state.documentOf(it.file), false).actionTarget }
    return ActionPanelContext(doc, activeActionTarget(localIds, chosen),
        localIds.filter { it.path.isNotEmpty() }.mapNotNull { it.entityType }.distinct())
}

/**
 * 右ペインの「操作」タブ（型の操作とアクション。architecture/actions.md）: 開いている文書に使える操作の一覧と、作業中の操作の開き直し。
 * - rootId は表示中の画面で選んだ要素（フォームの明示選択・文書ルート・横ぐしの選択ID）。
 *   台帳では要素の型（elementTypes）から対象を選ぶ入口も並べる。
 * - 作業（AppState.actionWork）は浮いたパネル（ActionPanelHost）で進める。パネルを隠してもここから開き直せる。
 * 入口（エンティティページの操作メニュー・行のメニュー・文書ヘッダのメニュー・コマンドパレット）はどれも AppState.startAction を呼ぶだけ。
 */
@Composable
fun ActionTabContent(state: AppState, doc: OpenDocument?, rootId: String?, elementTypes: List<String>) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 12.dp)) {
        val work = state.actionWork
        if (work != null) WorkInProgress(state, work)
        // 編集と操作の線引き（architecture/actions.md「規律」）。閲覧専用が封じるのは編集で、操作はここから使える
        Text(
            if (state.readOnlyMode) "閲覧専用モードでも操作は使えます（確認してから書いて保存。フォームの編集はできません）"
            else "操作は確認してから書いて保存します。フォームの追加・削除は編集（未保存）です",
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 11.sp, color = Palette.SubText,
        )
        state.indexVersion
        val forDocument = remember(doc, state.indexVersion) { doc?.let { state.actionsForDocument(it.file) }.orEmpty() }
        val forEntity = remember(rootId, state.indexVersion) { rootId?.let { state.actionsForEntity(it) }.orEmpty() }
        val forTypes = remember(elementTypes, state.indexVersion) {
            elementTypes.map { it to state.actionsForEntityType(it) }.filter { it.second.isNotEmpty() }
        }
        val forWorkspace = remember(state.indexVersion) { state.actionsForWorkspace() }
        if (rootId == null && forDocument.isEmpty() && forEntity.isEmpty() && forTypes.isEmpty() && forWorkspace.isEmpty()) {
            Text(
                "使える操作がありません。スキーマの methods（型の操作）か actions（アクション）に定義します",
                Modifier.padding(12.dp), fontSize = 12.sp, color = Palette.SubText,
            )
            return@Column
        }
        if (forDocument.isNotEmpty() && doc != null) {
            ActionSection("この文書の操作")
            forDocument.forEach { e -> ActionRow(state, e, state.relPath(doc.file)) { state.startAction(e, state.relPath(doc.file)) } }
        }
        if (rootId != null) {
            val title = state.overlayIndex().occurrences(rootId).firstNotNullOfOrNull { it.title }
            ActionSection("選択中: " + listOfNotNull(rootId, title).joinToString(" · "))
            if (forEntity.isEmpty()) Text("この要素に使える操作はありません", Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                fontSize = 12.sp, color = Palette.SubText)
            forEntity.forEach { e -> ActionRow(state, e, rootId) { state.startAction(e, rootId) } }
        }
        if (forTypes.isNotEmpty()) {
            ActionSection("この文書の要素の操作（対象はパネルで選ぶ）")
            forTypes.forEach { (type, entries) ->
                Text(type, Modifier.padding(start = 12.dp, top = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.Primary)
                entries.forEach { e -> ActionRow(state, e, null) { state.startAction(e, null) } }
            }
        }
        if (forWorkspace.isNotEmpty()) {
            ActionSection("ワークスペースの操作")
            forWorkspace.forEach { e -> ActionRow(state, e, null) { state.startAction(e, null) } }
        }
    }
}

/** 作業中の操作（パネルを隠しているときの開き直し・やめる）。 */
@Composable
private fun WorkInProgress(state: AppState, work: AppState.ActionWork) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).background(Palette.Selected, PanelShape).padding(10.dp)) {
        Text("作業中: ${work.title}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        work.target?.let { Text("対象: $it", fontSize = 11.sp, color = Palette.SubText) }
        work.applied?.let { Text(it, fontSize = 11.sp, color = Palette.OkGreen) }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { state.showActionPanel() }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                Text(if (work.applied != null) "結果を見る" else "パネルを開く", fontSize = 12.sp)
            }
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = { state.dismissAction() }) { Text(if (work.applied != null) "閉じる" else "やめる", fontSize = 12.sp) }
        }
    }
}

/** 札（編集／操作）。エンティティページの型の札と同じ見た目。 */
@Composable
private fun Tag(text: String) {
    Text(
        text, fontSize = 10.sp, color = Palette.Primary,
        modifier = Modifier.border(1.dp, Palette.Primary, PanelShape).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun ActionSection(title: String) {
    Text(title, Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.SubText)
}

@Composable
private fun ActionRow(state: AppState, entry: ActionEntry, target: String?, onClick: () -> Unit) {
    val available = remember(entry, target, state.indexVersion, state.editVersion) { state.actionAvailability(entry, target) }
    Column(Modifier.fillMaxWidth().clickable(enabled = available.enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.label ?: entry.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = if (available.enabled) Palette.Text else Palette.SubText)
            if (entry.kind == "method") {
                Spacer(Modifier.width(6.dp))
                Text("型の操作", fontSize = 10.sp, color = Palette.Primary)
            }
        }
        entry.description?.let { Text(it, fontSize = 11.sp, color = Palette.SubText) }
        available.reason?.let { Text(it, fontSize = 11.sp, color = Palette.SubText) }
    }
}

/** 行・個票・文書ヘッダーで同じ前提と理由を表示する。 */
@Composable
internal fun ActionMenuItem(state: AppState, entry: ActionEntry, target: String?, onClick: () -> Unit) {
    val available = remember(entry, target, state.indexVersion, state.editVersion) { state.actionAvailability(entry, target) }
    DropdownMenuItem(enabled = available.enabled, text = {
        Column {
            Text(entry.label ?: entry.name, fontSize = 12.sp)
            available.reason?.let { Text(it, fontSize = 11.sp, color = Palette.SubText) }
        }
    }, onClick = onClick)
}

// ================================================================ 浮いたパネル

/**
 * 操作のパネル（浮いたパネル。Main がスペースに関係なく載せる）: 対象と受け取る値を入れる →「確認」で計画と適用後の検証 → 差分 →
 * 「適用」（書いて保存）。追加ボタンからの作業（insertInto）は値を入れて「追加」。
 * 「AI で作成」と同じ幅。外側のクリックでは閉じず、Esc と「いったん隠す」は隠すだけ（作業は AppState.actionWork に残り、
 * 右ペインの「操作」タブから開き直せる）。「やめる」で捨てる。受け取る値はフォームの部品（FieldRow）で編集する
 * （型・選択肢・参照の候補・ID の提案がそのまま効く）。
 */
@Composable
fun ActionPanelHost(state: AppState) {
    val work = state.actionWork ?: return
    if (!state.actionPanelOpen) return
    val form = work.form
    form.version
    state.indexVersion
    LaunchedEffect(work, form.version, state.indexVersion, state.editVersion) { state.refreshActionPlan() }
    val ctx = remember(form, form.version, state.indexVersion, work.applied) { FormCtx(state, form, emptyList(), readOnly = work.applied != null) }
    var reload by remember(work) { mutableStateOf(false) }
    if (reload) AlertDialog(onDismissRequest = { reload = false }, title = { Text("初期値を読み直す") },
        text = { Text("入力中の変更を破棄して、対象の現在値を読み直します。") },
        confirmButton = { TextButton(onClick = {
            state.reloadActionInput().onFailure { work.error = it.message }; reload = false
        }) { Text("破棄して読み直す", color = Palette.ErrorRed) } },
        dismissButton = { TextButton(onClick = { reload = false }) { Text("キャンセル") } })
    var allowErrors by remember(work) { mutableStateOf(false) }
    // 本文の高さは窓に合わせる（題名とボタンのぶんを除く）。測れていないうちは控えめな既定
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val bodyMax = if (windowHeight <= 0.dp) 640.dp else (windowHeight * 0.85f - 170.dp).coerceAtLeast(360.dp)
    val plan = work.plan
    val preview = work.preview
    val issues = remember(preview) {
        preview?.results.orEmpty().flatMap { r -> r.issues.map { r.file to it } }.filter { it.second.severity != Severity.INFO }
    }
    val errors = issues.count { it.second.severity == Severity.ERROR }
    val warnings = issues.count { it.second.severity == Severity.WARNING }
    val insertInto = work.insertInto
    val done = work.applied != null

    AlertDialog(
        onDismissRequest = { state.hideActionPanel() },
        properties = DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.widthIn(min = 680.dp, max = 1040.dp),
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(work.title)
                    Spacer(Modifier.width(8.dp))
                    // 札: 編集（この文書の木を変える。未保存）か 操作（確認してから書いて保存）か
                    Tag(if (insertInto != null) "編集" else "操作")
                }
                work.entry.description?.let { Text(it, fontSize = 11.sp, color = Palette.SubText) }
                val readOnlyNote = state.readOnlyMode && insertInto == null
                Text(
                    when {
                        insertInto != null -> "この文書の編集です。保存はしません（Ctrl+Z で戻せます）"
                        readOnlyNote -> "閲覧専用モードです。操作は確認のうえ書いて保存します"
                        else -> "確認してから書いて保存します"
                    },
                    fontSize = 11.sp, color = if (readOnlyNote) Palette.WarnOrange else Palette.SubText,
                )
            }
        },
        text = {
            val scroll = rememberScrollState()
            Box(Modifier.fillMaxWidth().heightIn(max = bodyMax)) {
                Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = 10.dp)) {
                    val type = work.entry.entityType
                    when {
                        insertInto != null -> if (insertInto.alternatives.isNotEmpty()) {
                            // 橋渡し: この配列へ足すアクション（複数の文書をまとめて変える）があれば案内し、値を持ち越して切り替えられる
                            Column(Modifier.fillMaxWidth().background(Palette.Selected, PanelShape).padding(8.dp)) {
                                Text("まとめて足す操作があります（確認してから書いて保存）", fontSize = 11.sp, color = Palette.SubText)
                                insertInto.alternatives.forEach { alt ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(alt.label ?: alt.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                            alt.description?.let { Text(it, fontSize = 11.sp, color = Palette.SubText) }
                                        }
                                        TextButton(onClick = { state.switchInsertToAction(alt) }) { Text("操作に切り替える", fontSize = 12.sp) }
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        type != null -> TargetPicker(state, work, type, enabled = !done)
                        else -> work.target?.let { Text("受け手: $it", fontSize = 11.sp, color = Palette.SubText) }
                    }
                    Spacer(Modifier.height(4.dp))
                    if (work.entry.input != null && !done) {
                        if (work.entry.input?.submit == "changed") Text("変更した項目を送ります。項目の削除は操作で用意された消去欄を使ってください。", fontSize = 11.sp, color = Palette.SubText)
                        TextButton(onClick = {
                            if (state.actionInputModified()) reload = true
                            else state.reloadActionInput().onFailure { work.error = it.message }
                        }) { Text("初期値を読み直す") }
                    }
                    if (work.entry.params.isEmpty()) {
                        Text("受け取る値はありません", fontSize = 11.sp, color = Palette.SubText)
                    } else {
                        val map = work.values
                        work.entry.params.forEach { p ->
                            val key = p.key ?: return@forEach
                            FieldRow(ctx, p, MapSlot(map, key), listOf(key))
                        }
                    }
                    work.error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, fontSize = 12.sp, color = Palette.ErrorRed)
                    }
                    if (plan != null && insertInto == null) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = Palette.Border)
                        Spacer(Modifier.height(6.dp))
                        if (plan.isEmpty) Text("変更はありません", fontSize = 12.sp, color = Palette.SubText)
                        plan.summary.forEach { Text("・$it", fontSize = 11.sp) }
                        if (preview != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "適用後の検証: エラー $errors 件・警告 $warnings 件",
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                color = if (errors > 0) Palette.ErrorRed else if (warnings > 0) Palette.WarnOrange else Palette.OkGreen,
                            )
                            issues.take(12).forEach { (file, issue) ->
                                Text(
                                    "・[$file] ${issue.message}", fontSize = 11.sp,
                                    color = if (issue.severity == Severity.ERROR) Palette.ErrorRed else Palette.WarnOrange,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            ActionDiff(state, plan, height = (bodyMax - 320.dp).coerceIn(220.dp, 560.dp))
                        }
                    }
                    work.applied?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, fontSize = 12.sp, color = Palette.OkGreen)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                // 本文の高さは中身で決める（スクロールバーに fillMaxHeight を直接付けると枠が上限いっぱいに伸びる）
                Box(Modifier.matchParentSize()) {
                    VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    done -> Unit
                    insertInto != null -> Button(onClick = { state.applyInsert().onFailure { work.error = it.message ?: "追加できません" } }) { Text("行を追加") }
                    else -> {
                        val canApply = plan != null && preview != null && !plan.isEmpty
                        if (canApply && errors > 0) {
                            Checkbox(allowErrors, { allowErrors = it }, Modifier.size(28.dp))
                            Text("検証エラーがあっても適用する", fontSize = 11.sp)
                            Spacer(Modifier.width(8.dp))
                        }
                        if (canApply) {
                            OutlinedButton(onClick = { state.planAction() }) { Text("確認し直す") }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                enabled = errors == 0 || allowErrors,
                                onClick = { state.applyAction(allowErrors).onFailure { work.error = it.message ?: "適用に失敗しました" } },
                            ) { Text("適用（書いて保存）") }
                        } else {
                            Button(enabled = !work.targetEditing, onClick = { state.planAction() }) { Text("確認") }
                        }
                    }
                }
            }
        },
        dismissButton = {
            Row {
                if (done) {
                    TextButton(onClick = { state.dismissAction() }) { Text("閉じる") }
                } else {
                    TextButton(onClick = { state.dismissAction() }) { Text("やめる") }
                    TextButton(onClick = { state.hideActionPanel() }) { Text("いったん隠す") }
                }
            }
        },
    )
}

/**
 * 「対象」の欄: 受け手の要素を、その型の要素だけを候補にして選ぶ（候補は上書き索引＝未保存の編集を含む。ID と 1 行要約）。
 * 入口が受け手を知っていれば先に入っている。打ち込んだ文字で候補を絞り、型の要素でない ID は赤で知らせる。
 */
@Composable
private fun TargetPicker(state: AppState, work: AppState.ActionWork, type: String, enabled: Boolean) {
    work.form.version
    val candidates = remember(type, state.indexVersion, state.editVersion) { state.entityCandidates(type) }
    val current = if (work.targetEditing) work.targetDraft else work.target.orEmpty()
    val resolved = candidates.any { it.first == current }
    val title = candidates.firstOrNull { it.first == current }?.second
    var menuOpen by remember { mutableStateOf(false) }
    var proposed by remember(work) { mutableStateOf<String?>(null) }
    fun set(id: String) {
        if (state.actionInputModified()) proposed = id
        else state.changeActionTarget(id).onFailure { work.error = it.message }
    }
    proposed?.let { target ->
        fun cancel() { proposed = null; work.targetDraft = work.target.orEmpty(); work.targetEditing = false }
        AlertDialog(onDismissRequest = ::cancel, title = { Text("操作対象を変更する") },
            text = { Text("入力中の変更を破棄して、新しい対象の初期値を読み込みます。") },
            confirmButton = { TextButton(onClick = {
                state.changeActionTarget(target).onFailure { work.error = it.message }; proposed = null
            }) { Text("破棄して変更する", color = Palette.ErrorRed) } },
            dismissButton = { TextButton(onClick = ::cancel) { Text("キャンセル") } })
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(120.dp)) {
            Text("対象", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(" *", fontSize = 12.sp, color = Palette.ErrorRed)
        }
        if (!enabled) {
            Text(listOfNotNull(current, title).joinToString(" — "), fontSize = 13.sp)
            return@Row
        }
        Box(Modifier.weight(1f)) {
            CompactTextField(
                value = current,
                onValueChange = { work.targetDraft = it; work.targetEditing = it != work.target.orEmpty(); work.invalidate() },
                singleLine = true,
                isError = current.isNotBlank() && !resolved,
                placeholder = { Text("$type の要素の ID") },
                suffix = title?.let {
                    {
                        Text(
                            it, fontSize = 12.sp, color = Palette.SubText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 320.dp).padding(start = 8.dp),
                        )
                    }
                },
                trailingIcon = {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "候補を表示", modifier = Modifier.size(18.dp))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            ChoiceMenu(menuOpen, { menuOpen = false }, candidates.map { (id, title) ->
                ChoiceOption(id, id, title.orEmpty(), selected = id == current)
            }, onSelect = { set(it) })
        }
    }
    if (work.targetEditing) TextButton(enabled = current.isBlank() || resolved, onClick = { set(current) }) {
        Text("対象の変更を確定")
    }
    if (current.isNotBlank() && !resolved) {
        Text("$type の要素ではありません（候補から選んでください）", fontSize = 11.sp, color = Palette.ErrorRed, modifier = Modifier.padding(start = 120.dp))
    }
}

/** 計画の差分: 文書を選んで、いまの内容と適用後の内容を並べる（変更セットの承認と同じ見せ方）。 */
@Composable
private fun ActionDiff(state: AppState, plan: ActionPlan, height: Dp) {
    val texts = remember(plan) { state.actionDiffTexts(plan) }
    val files = plan.changeSet.changes.map { it.file }
    var current by remember(plan) { mutableStateOf(files.firstOrNull()) }
    Text("差分（${files.size} 文書）", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    files.forEach { file ->
        val change = plan.changeSet.changes.first { it.file == file }
        Row(
            Modifier.fillMaxWidth()
                .background(if (current == file) Palette.Selected else Color.Transparent, PanelShape)
                .clickable { current = file }
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(file, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1)
            Text(if (change.create) "新規" else "${change.ops.size} 操作", fontSize = 10.sp, color = Palette.SubText)
        }
    }
    val file = current ?: return
    val (before, after) = texts[file] ?: return
    val lines = remember(file, before, after) { calculateDiff(before, after) }
    Box(Modifier.fillMaxWidth().height(height).background(Palette.EditorBg, PanelShape).padding(4.dp)) {
        val listState = rememberLazyListState()
        // 最初の変更が見えるところまで送る（長い文書の末尾の変更を探さずに済む）
        LaunchedEffect(lines) {
            val first = lines.indexOfFirst { it.changed }
            if (first > 0) listState.scrollToItem((first - 2).coerceAtLeast(0))
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 8.dp)) {
            items(lines) { line -> DiffLineRow(line) }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}
