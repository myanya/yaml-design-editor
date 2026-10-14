package dev.toros.yde.app.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.AppState
import dev.toros.yde.app.OpenDocument
import dev.toros.yde.workspace.ActionEntry
import dev.toros.yde.yaml.validate.Severity

/**
 * 右ペインの「操作」タブ（型の操作とアクション。architecture/actions.md）。
 * 作業中（AppState.actionWork）ならそのカード＝受け取る値の入力 → 「確認」で計画と適用後の検証 → 差分 → 「適用」。
 * 作業が無ければ、開いている文書に使える操作の一覧（文書の操作・ルートの要素の操作・ワークスペースの操作）を出し、選ぶと始める。
 * 入口（エンティティページの操作メニュー・文書ヘッダのメニュー・コマンドパレット）はどれも AppState.startAction を呼ぶだけ。
 */
@Composable
fun ActionTabContent(state: AppState, doc: OpenDocument?, rootId: String?) {
    val work = state.actionWork
    if (work != null) {
        ActionCard(state, work)
        return
    }
    if (state.readOnlyMode) {
        Text("閲覧専用モードでは操作を使えません（ツールバーで解除してください）", Modifier.padding(12.dp), fontSize = 12.sp, color = Palette.SubText)
        return
    }
    state.indexVersion
    val forDocument = remember(doc, state.indexVersion) { doc?.let { state.actionsForDocument(it.file) }.orEmpty() }
    val forEntity = remember(rootId, state.indexVersion) { rootId?.let { state.actionsForEntity(it) }.orEmpty() }
    val forWorkspace = remember(state.indexVersion) { state.actionsForWorkspace() }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 12.dp)) {
        if (forDocument.isEmpty() && forEntity.isEmpty() && forWorkspace.isEmpty()) {
            Text(
                "使える操作がありません。スキーマの methods（型の操作）か actions（アクション）に定義します",
                Modifier.padding(12.dp), fontSize = 12.sp, color = Palette.SubText,
            )
            return@Column
        }
        if (forDocument.isNotEmpty() && doc != null) {
            ActionSection("この文書の操作")
            forDocument.forEach { e -> ActionRow(e) { state.startAction(e, state.relPath(doc.file)) } }
        }
        if (forEntity.isNotEmpty() && rootId != null) {
            ActionSection("$rootId の操作")
            forEntity.forEach { e -> ActionRow(e) { state.startAction(e, rootId) } }
        }
        if (forWorkspace.isNotEmpty()) {
            ActionSection("ワークスペースの操作")
            forWorkspace.forEach { e -> ActionRow(e) { state.startAction(e, null) } }
        }
    }
}

@Composable
private fun ActionSection(title: String) {
    Text(title, Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.SubText)
}

@Composable
private fun ActionRow(entry: ActionEntry, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.label ?: entry.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (entry.kind == "method") {
                Spacer(Modifier.width(6.dp))
                Text("型の操作", fontSize = 10.sp, color = Palette.Primary)
            }
        }
        entry.description?.let { Text(it, fontSize = 11.sp, color = Palette.SubText) }
    }
}

/** 作業中の操作のカード。受け取る値はフォームの部品（FieldRow）で編集する（型・選択肢・参照の候補・ID の提案がそのまま効く）。 */
@Composable
private fun ActionCard(state: AppState, work: AppState.ActionWork) {
    val form = work.form
    form.version
    state.indexVersion
    val ctx = remember(form, form.version, state.indexVersion) { FormCtx(state, form, emptyList(), readOnly = false) }
    var allowErrors by remember(work) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(work.title, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            work.entry.description?.let { Text(it, fontSize = 11.sp, color = Palette.SubText) }
            work.target?.let { Text("受け手: $it", fontSize = 11.sp, color = Palette.SubText) }
            Spacer(Modifier.height(6.dp))
            if (work.entry.params.isEmpty()) {
                Text("受け取る値はありません", fontSize = 11.sp, color = Palette.SubText)
            } else {
                val map = work.values
                work.entry.params.forEach { p ->
                    val key = p.key ?: return@forEach
                    FieldRow(ctx, p, MapSlot(map, key), listOf(key))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { state.planAction() }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) { Text("確認", fontSize = 12.sp) }
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = { state.dismissAction() }) { Text("やめる", fontSize = 12.sp) }
            }
            work.error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, color = Palette.ErrorRed)
            }
            val plan = work.plan
            if (plan != null) {
                Spacer(Modifier.height(8.dp))
                if (plan.isEmpty) {
                    Text("変更はありません", fontSize = 12.sp, color = Palette.SubText)
                }
                plan.summary.forEach { Text("・$it", fontSize = 11.sp) }
                val preview = work.preview
                if (preview != null) {
                    val issues = remember(preview) {
                        preview.results.flatMap { r -> r.issues.map { r.file to it } }.filter { it.second.severity != Severity.INFO }
                    }
                    val errors = issues.count { it.second.severity == Severity.ERROR }
                    val warnings = issues.count { it.second.severity == Severity.WARNING }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "適用後の検証: エラー $errors 件・警告 $warnings 件",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = if (errors > 0) Palette.ErrorRed else if (warnings > 0) Palette.WarnOrange else Palette.OkGreen,
                    )
                    issues.take(8).forEach { (file, issue) ->
                        Text(
                            "・[$file] ${issue.message}", fontSize = 11.sp,
                            color = if (issue.severity == Severity.ERROR) Palette.ErrorRed else Palette.WarnOrange,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    ActionDiff(state, plan)
                    Spacer(Modifier.height(8.dp))
                    if (work.applied == null) {
                        if (errors > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(allowErrors, { allowErrors = it }, Modifier.size(28.dp))
                                Text("検証エラーがあっても適用する", fontSize = 11.sp)
                            }
                        }
                        Button(
                            enabled = errors == 0 || allowErrors,
                            onClick = { state.applyAction(allowErrors).onFailure { work.error = it.message ?: "適用に失敗しました" } },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        ) { Text("適用", fontSize = 12.sp) }
                    }
                }
                work.applied?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 12.sp, color = Palette.OkGreen)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** 計画の差分: 文書を選んで、いまの内容と適用後の内容を並べる（変更セットの承認と同じ見せ方）。 */
@Composable
private fun ActionDiff(state: AppState, plan: dev.toros.yde.workspace.ActionPlan) {
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
    Box(Modifier.fillMaxWidth().height(220.dp).background(Palette.EditorBg, PanelShape).padding(4.dp)) {
        val listState = rememberLazyListState()
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 8.dp)) {
            items(lines) { line -> DiffLineRow(line) }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}
