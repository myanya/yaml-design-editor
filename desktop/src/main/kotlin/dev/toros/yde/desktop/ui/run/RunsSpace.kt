package dev.toros.yde.desktop.ui.run

import dev.toros.yde.desktop.statusLabel

import dev.toros.yde.desktop.RunController

import dev.toros.yde.desktop.AiSettings

import dev.toros.yde.agent.ActivityDef
import dev.toros.yde.agent.run.AgentEvent
import dev.toros.yde.agent.run.AgentHost
import dev.toros.yde.agent.run.AgentKernel
import dev.toros.yde.agent.run.AgentRunRequest
import dev.toros.yde.agent.run.AgentRunResult
import dev.toros.yde.agent.auto.AutomationEngine
import dev.toros.yde.agent.Definitions
import dev.toros.yde.agent.auto.ForeachItem
import dev.toros.yde.agent.run.RunLog
import dev.toros.yde.agent.run.RunStatus
import dev.toros.yde.agent.run.Staging
import dev.toros.yde.agent.ValidationGate
import dev.toros.yde.agent.llm.AiProvider
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.validate.Issue
import dev.toros.yde.workspace.changeset.ChangeSet
import dev.toros.yde.app.core.Workspace
import dev.toros.yde.workspace.validate.CodedChecks
import dev.toros.yde.workspace.validate.IssueDelivery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.toros.yde.yaml.validate.Severity
import java.nio.file.Path
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.ui.common.ChoiceMenu
import dev.toros.yde.desktop.ui.common.ChoiceOption
import dev.toros.yde.desktop.ui.common.CompactTextField
import dev.toros.yde.desktop.ui.common.DiffLineRow
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PanelShape
import dev.toros.yde.desktop.ui.common.calculateDiff

/**
 * 実行スペース（R12-01/02）。
 * エージェント実行・自動化・計測評価を1スペースに集約し、先頭タブの「受信箱」に
 * 人間の判断待ち（承認待ち変更セット・エージェントの質問・外部変更競合）を集める。
 * 受信箱はデータを持たない射影で、各機構（AutomationEngine / RunController / OpenDocument）が正。
 */

// ================================================================ 実行状態（RunController）

// ================================================================ スペース本体

@Composable
fun RunsSpace(state: AppState, engine: AutomationEngine?, controller: RunController?, version: Int) {
    version  // エンジン更新の購読
    if (engine == null || controller == null) return
    // タブとレビュー対象は画面の覚え（ScreenMemory。スペースを離れても残る。承認待ちから消えたレビュー対象は下で読み捨てる）
    val tab = state.memory.runsTab
    val reviewTarget = state.memory.runsReview
    val pending = engine.pendingSnapshot()
    val conflicts = state.conflictedDocs()
    val external = state.externalWork
    val externalReview = external?.records?.firstOrNull { it.id == state.memory.externalWorkReview }
    val inboxCount = pending.size + conflicts.size + (if (controller.pendingQuestion != null) 1 else 0) + (external?.pendingCount ?: 0)

    Column(Modifier.fillMaxSize().background(Palette.EditorBg)) {
        TabRow(selectedTabIndex = tab, containerColor = Palette.Surface, contentColor = Palette.Primary) {
            Tab(tab == 0, { state.memory.runsTab = 0 }, text = { Text(if (inboxCount > 0) "受信箱（$inboxCount）" else "受信箱", fontSize = 13.sp) })
            Tab(tab == 1, { state.memory.runsTab = 1 }, text = { Text("手動実行", fontSize = 13.sp) })
            Tab(tab == 2, { state.memory.runsTab = 2 }, text = { Text("自動化", fontSize = 13.sp) })
            Tab(tab == 3, { state.memory.runsTab = 3 }, text = { Text("計測・評価", fontSize = 13.sp) })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> {
                    val review = reviewTarget
                    if (external != null && externalReview != null) {
                        ExternalWorkPane(external, externalReview) { state.memory.externalWorkReview = null }
                    } else if (review != null && pending.contains(review)) {
                        PendingReviewPane(state, engine, review) { state.memory.runsReview = null }
                    } else {
                        InboxTab(state, engine, controller, onReview = { state.memory.runsReview = it })
                    }
                }
                1 -> ManualRunTab(state, engine, controller, onGoAutomation = { state.memory.runsTab = 2 })
                2 -> AutomationSection(state, engine, version) { state.memory.runsTab = 0 }
                3 -> ImprovementSection(state)
            }
        }
    }
}

// ================================================================ 受信箱（R12-02）

@Composable
private fun InboxTab(
    state: AppState,
    engine: AutomationEngine,
    controller: RunController,
    onReview: (AutomationEngine.PendingReview) -> Unit,
) {
    val pending = engine.pendingSnapshot()
    val conflicts = state.conflictedDocs()
    val question = controller.pendingQuestion
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("人間の判断を待っているものが、ここに集まります", fontSize = 11.sp, color = Palette.SubText)
        Spacer(Modifier.height(10.dp))

        state.externalWork?.let { external ->
            ExternalWorkList(external) { state.memory.externalWorkReview = it }
            Spacer(Modifier.height(10.dp))
        }

        if (question == null && pending.isEmpty() && conflicts.isEmpty() && (state.externalWork?.pendingCount ?: 0) == 0) {
            Text("判断待ちはありません", fontSize = 13.sp, color = Palette.OkGreen)
            Spacer(Modifier.height(4.dp))
            Text(
                "エージェントや外部接続の承認待ち変更セット・実行中の質問・外部変更の競合が発生すると、ここに表示されます。",
                fontSize = 12.sp, color = Palette.SubText,
            )
            return@Column
        }

        // ---------------- エージェントの質問（実行をブロック中） ----------------
        question?.let { QuestionCard(it) }

        // ---------------- 承認待ち変更セット ----------------
        if (pending.isNotEmpty()) {
            InboxSection("承認待ち変更セット（${pending.size}）")
            pending.forEach { review ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .background(Palette.PanelBg, PanelShape)
                        .border(1.dp, Palette.Border, PanelShape)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${review.activityName}（${review.activityId}）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${review.result.changeSet.changes.size} ファイル ／ ${review.reason}",
                            fontSize = 11.sp, color = Palette.SubText, maxLines = 2,
                        )
                    }
                    Button(onClick = { onReview(review) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                        Text("レビュー", fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ---------------- 外部変更の競合 ----------------
        if (conflicts.isNotEmpty()) {
            InboxSection("外部変更との競合（${conflicts.size}）")
            conflicts.forEach { doc ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .background(Palette.ConflictBg, PanelShape)
                        .border(1.dp, Palette.Border, PanelShape)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(state.relPath(doc.file), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("外部で変更されました。未保存の編集内容を保持しています。", fontSize = 11.sp, color = Palette.SubText)
                    }
                    TextButton(onClick = { state.openFile(doc.file) }) { Text("開いて確認", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun InboxSection(title: String) {
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.SubText,
        modifier = Modifier.padding(vertical = 3.dp))
}

/**
 * エージェントの質問カード（R2-07）。モーダルにせず、文書を確認しながら回答できるようにする
 * （カーネルは回答まで suspend しており、放置しても原本には作用しない）。
 */
@Composable
internal fun QuestionCard(question: RunController.PendingQuestion) {
    var answer by remember(question) { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .background(Palette.PanelBg, PanelShape)
            .border(1.dp, Palette.WarnOrange, PanelShape)
            .padding(10.dp),
    ) {
        Text("❓ エージェントからの質問（回答するまで実行が待機します）", fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold, color = Palette.WarnOrange)
        Spacer(Modifier.height(6.dp))
        Text(question.question, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        CompactTextField(answer, { answer = it },
            placeholder = { Text("回答（追加の指示もここで伝えられます）") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp))
        Spacer(Modifier.height(6.dp))
        Row {
            Button(enabled = answer.isNotBlank(), onClick = { question.respond(answer) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                Text("回答する", fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { question.respond(null) }) { Text("回答しない", fontSize = 12.sp) }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** 承認待ち変更セットのレビュー（受信箱内の全画面。モーダルにしない。R12-02）。 */
@Composable
private fun PendingReviewPane(
    state: AppState,
    engine: AutomationEngine,
    review: AutomationEngine.PendingReview,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "受信箱へ戻る（保留のまま）", Modifier.size(18.dp))
            }
            Column {
                Text("承認待ち: ${review.activityName}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(review.reason, fontSize = 11.sp, color = Palette.SubText)
            }
        }
        Spacer(Modifier.height(8.dp))
        ReviewContent(state, review.ws, review.result, review.runFile) { adopted, applied, _ ->
            engine.completeReview(review, adopted, applied)
            onBack()
        }
    }
}

// ================================================================ 手動実行（旧 AgentRunHost。R2-03/06/07）

@Composable
private fun ManualRunTab(
    state: AppState,
    engine: AutomationEngine,
    controller: RunController,
    onGoAutomation: () -> Unit = {},
) {
    val defs = remember(controller.wsPath, controller.defsVersion) { Definitions.load(controller.wsPath) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(defs) {
        val cur = controller.selectedActivity
        if (cur == null || defs.activities.none { it.id == cur.id }) {
            controller.selectedActivity = defs.activities.firstOrNull()
        }
    }
    val selected = controller.selectedActivity

    // 実効プロバイダ／モデル: エージェント定義のピン留めが最優先、なければ設定の選択値。
    // APIキーは必ず「実効プロバイダ」のものを使う（選択中プロバイダの鍵を流用しない）。
    val selectedAgentDef = selected?.let { defs.agent(it.agentId) }
    val effectiveProvider = selectedAgentDef?.provider ?: AiSettings.provider
    val effectiveModel = selectedAgentDef?.model ?: AiSettings.modelFor(effectiveProvider).trim()

    fun startRun() {
        val activity = selected ?: return
        val agentDef = defs.agent(activity.agentId) ?: run {
            controller.errorMessage = "エージェント「${activity.agentId}」が見つかりません"; return
        }
        if (effectiveModel.isBlank()) {
            controller.errorMessage = "${effectiveProvider.label} のモデルを入力してください"; return
        }
        controller.startRun(activity.id, AgentRunRequest(
            activity = activity,
            agent = agentDef,
            provider = effectiveProvider,
            model = effectiveModel,
            apiKey = AiSettings.apiKeyFor(effectiveProvider),
            extraInstruction = controller.extraInstruction.takeIf { it.isNotBlank() },
            apiKeys = AiProvider.entries.associateWith { AiSettings.apiKeyFor(it) }.filterValues { it.isNotBlank() },
            subAgents = defs.agents,
        ))
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("エージェントの編集はステージング上で行われ、承認するまで原本は変わりません",
            fontSize = 11.sp, color = Palette.SubText)
        Spacer(Modifier.height(8.dp))
        when (controller.stage) {
            RunController.Stage.SELECT -> {
                SelectContent(
                    defs = defs,
                    selected = selected,
                    onSelect = { controller.selectedActivity = it },
                    extraInstruction = controller.extraInstruction,
                    onExtraChange = { controller.extraInstruction = it },
                    onRefresh = { controller.refreshDefs() },
                    errorMessage = controller.errorMessage,
                    pinnedProvider = selectedAgentDef?.provider,
                    pinnedModel = selectedAgentDef?.model,
                    effectiveProvider = effectiveProvider,
                )
                Spacer(Modifier.height(10.dp))
                val batchTarget = selected?.takeIf { it.foreach != null }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = selected != null && AiSettings.apiKeyFor(effectiveProvider).isNotBlank(),
                        onClick = ::startRun,
                    ) { Text("実行") }
                    if (batchTarget != null) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            scope.launch {
                                val preview = withContext(Dispatchers.IO) { engine.previewForeach(batchTarget.id) }
                                when {
                                    preview.error != null -> controller.errorMessage = preview.error.orEmpty()  // 別モジュールの公開プロパティはスマートキャストできない
                                    preview.items.isEmpty() -> {
                                        controller.clearForeachPreview()
                                        controller.errorMessage = "一括実行の対象がありません"
                                    }
                                    else -> {
                                        controller.errorMessage = ""
                                        controller.showForeachPreview(
                                            RunController.ForeachPreview(batchTarget.id, preview.items, preview.total)
                                        )
                                    }
                                }
                            }
                        }) { Text("一括実行（対象を選択）") }
                    }
                }
                if (batchTarget != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "一括実行は foreach（${batchTarget.foreach?.source?.id}）で対象を列挙し、選択した対象を1件=1実行として自動化キューへ積みます。" +
                            "自動化タブで「自動実行」を有効にすると順次実行されます",
                        fontSize = 11.sp, color = Palette.SubText,
                    )
                    val preview = controller.foreachPreview
                    if (preview != null && preview.activityId == batchTarget.id) {
                        Spacer(Modifier.height(8.dp))
                        ForeachPreviewPane(engine, controller, preview, onGoAutomation)
                    }
                }
            }
            RunController.Stage.RUNNING -> {
                Text("実行中: ${selected?.name ?: ""}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                controller.pendingQuestion?.let { QuestionCard(it) }
                RunningContent(controller.events, controller.plan)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { controller.cancel() }) { Text("キャンセル", color = Palette.ErrorRed) }
            }
            RunController.Stage.REVIEW -> {
                Text("変更セットのレビュー", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                val result = controller.runResult
                val ws = controller.coreWs
                if (result == null || ws == null) Text("結果がありません", fontSize = 12.sp, color = Palette.SubText)
                else ReviewContent(state, ws, result, controller.runFile) { adopted, applied, message ->
                    // 手動実行の連鎖はエンジン有効時のみ発火する（結果を渡すと出力値による分岐も効く。R9-04）
                    controller.runActivityId?.let {
                        engine.notifyManualCompletion(it, applied && adopted.isNotEmpty(), result, adopted)
                    }
                    controller.finish(message)
                }
            }
            RunController.Stage.DONE -> {
                Text(controller.doneMessage, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { controller.resetToSelect() }) { Text("新しい実行") }
            }
        }
    }
}

// ================================================================ 一括実行の対象選択（R18-01）

/**
 * foreach の列挙結果をチェックボックス付き一覧で表示し、選択した対象だけを自動化キューへ投入する。
 * モーダルではなく SELECT ステージ内のインライン区画（architecture/ui.md「UI シェル」: 新しい画面をモーダルで作らない）。
 * 選択状態は RunController が保持し、スペースを切り替えても失われない。
 */
@Composable
private fun ForeachPreviewPane(
    engine: AutomationEngine,
    controller: RunController,
    preview: RunController.ForeachPreview,
    onGoAutomation: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val selectedCount = preview.items.count { it.key in controller.foreachSelected }
    Column(
        Modifier.fillMaxWidth().background(Palette.PanelBg, PanelShape)
            .border(1.dp, Palette.Border, PanelShape).padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "一括実行の対象（${preview.items.size} 件を列挙" +
                    (if (preview.total > preview.items.size) "、条件一致は全 ${preview.total} 件＝展開上限で切られています" else "") + "）",
                fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { controller.setForeachSelection(preview.items.map { it.key }.toSet()) }) {
                Text("全選択", fontSize = 11.sp)
            }
            TextButton(onClick = { controller.setForeachSelection(emptySet()) }) {
                Text("全解除", fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        preview.items.forEach { item ->
            Row(
                Modifier.fillMaxWidth().clickable { controller.toggleForeachTarget(item.key) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = item.key in controller.foreachSelected,
                    onCheckedChange = { controller.toggleForeachTarget(item.key) },
                )
                Column {
                    Text(item.label, fontSize = 13.sp)
                    Text(item.instruction.lineSequence().first(), fontSize = 11.sp, color = Palette.SubText, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = selectedCount > 0,
                onClick = {
                    val items = preview.items.filter { it.key in controller.foreachSelected }
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { engine.enqueueForeachItems(preview.activityId, items) }
                        controller.clearForeachPreview()
                        if (result.error != null) controller.errorMessage = result.error.orEmpty() else onGoAutomation()  // 別モジュールの公開プロパティはスマートキャストできない
                    }
                },
            ) { Text("選択した $selectedCount 件をキューへ投入") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { controller.clearForeachPreview() }) { Text("やめる") }
        }
    }
}

// ================================================================ 選択

@Composable
private fun SelectContent(
    defs: Definitions.LoadResult,
    selected: ActivityDef?,
    onSelect: (ActivityDef) -> Unit,
    extraInstruction: String,
    onExtraChange: (String) -> Unit,
    onRefresh: () -> Unit,
    errorMessage: String,
    pinnedProvider: AiProvider?,
    pinnedModel: String?,
    effectiveProvider: AiProvider,
) {
    Column(Modifier.fillMaxWidth()) {
        if (defs.activities.isEmpty()) {
            Text("アクティビティが定義されていません。", fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "_activities/ にアクティビティ定義、_agents/ にエージェント定義（いずれもYAML）を置いてください。\n" +
                    "どちらも組込みスキーマ付きで、設計スペース・編集画面のフォーム・検証がそのまま使えます。",
                fontSize = 12.sp, color = Palette.SubText,
            )
        } else {
            Text("アクティビティ", fontSize = 12.sp, color = Palette.SubText)
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { open = true }) {
                    Text(selected?.let { "${it.name}（${it.id}）" } ?: "選択してください", fontSize = 13.sp)
                }
                ChoiceMenu(open, { open = false }, defs.activities.map { activity ->
                    ChoiceOption(activity, activity.name,
                        listOfNotNull(activity.id, "agent: ${activity.agentId}", activity.targetMatch).joinToString(" ／ "),
                        description = activity.description, selected = activity.id == selected?.id)
                }, onSelect)
            }
            selected?.let { activity ->
                Spacer(Modifier.height(6.dp))
                Column(
                    Modifier.fillMaxWidth().background(Palette.PanelBg, PanelShape)
                        .border(1.dp, Palette.Border, PanelShape).padding(10.dp)
                ) {
                    activity.description?.let { Text(it, fontSize = 12.sp) }
                    Text("指示: ${activity.instruction.trim().take(300)}", fontSize = 12.sp, color = Palette.SubText)
                    val agentDef = defs.agent(activity.agentId)
                    Text(
                        listOfNotNull(
                            "エージェント: ${agentDef?.name ?: activity.agentId}",
                            activity.targetMatch?.let { "対象: $it" },
                            "上限: ${activity.limits.maxTurns}ターン",
                            "ゲート: " + when (activity.gate) {
                                ValidationGate.ERROR_FREE -> "検証エラー0"
                                ValidationGate.NO_NEW_ERRORS -> "新規エラーなし"
                                ValidationGate.NONE -> "なし"
                            },
                            activity.foreach?.let { "一括: ${it.source.id}（展開上限 ${it.limit} 件）" },
                        ).joinToString(" ／ "),
                        fontSize = 11.sp, color = Palette.SubText,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("追加指示（任意）", fontSize = 12.sp, color = Palette.SubText)
            CompactTextField(extraInstruction, onExtraChange,
                placeholder = { Text("今回の実行で特に重視する点など") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp))
        }
        if (defs.errors.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            defs.errors.forEach { Text("⚠ $it", fontSize = 11.sp, color = Palette.ErrorRed) }
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onRefresh) { Text("定義を再読込", fontSize = 11.sp) }

        Spacer(Modifier.height(10.dp))
        Text("AI接続（この実行で実際に使われる接続先）", fontSize = 12.sp, color = Palette.SubText)
        if (pinnedProvider != null) {
            // エージェント定義がプロバイダを指定している場合は、選択ではなく事実を表示する
            Text(
                "プロバイダ: ${pinnedProvider.label} — エージェント定義（_agents/${selected?.agentId}）の model.provider による指定",
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AiProvider.entries.forEach { p ->
                    RadioButton(selected = AiSettings.provider == p, onClick = { AiSettings.provider = p })
                    Text(p.label, fontSize = 13.sp)
                    Spacer(Modifier.width(12.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = AiSettings.apiKeyFor(effectiveProvider),
                onValueChange = { AiSettings.setApiKeyFor(effectiveProvider, it) },
                label = { Text("${effectiveProvider.label} APIキー（保存しません）") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            if (pinnedModel != null) {
                OutlinedTextField(
                    value = pinnedModel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("モデル（エージェント定義で指定）") },
                    singleLine = true,
                    modifier = Modifier.width(240.dp),
                )
            } else {
                OutlinedTextField(
                    value = AiSettings.modelFor(effectiveProvider),
                    onValueChange = { AiSettings.setModelFor(effectiveProvider, it) },
                    label = { Text(modelFieldLabel(effectiveProvider)) },
                    singleLine = true,
                    modifier = Modifier.width(240.dp),
                )
            }
        }
        AiConnectionSettingsSection(effectiveProvider)
        if (errorMessage.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(errorMessage, color = Palette.ErrorRed, fontSize = 12.sp)
        }
    }
}

// ================================================================ 実行中

@Composable
private fun RunningContent(events: List<String>, plan: List<String>) {
    Column(Modifier.fillMaxWidth()) {
        if (plan.isNotEmpty()) {
            Text("計画: ${plan.joinToString(" → ")}", fontSize = 12.sp, color = Palette.Primary)
            Spacer(Modifier.height(6.dp))
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 520.dp).background(Palette.EditorBg, PanelShape).padding(10.dp)) {
            val listState = rememberLazyListState()
            LaunchedEffect(events.size) {
                if (events.isNotEmpty()) listState.animateScrollToItem(events.size - 1)
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 8.dp)) {
                items(events.size) { i ->
                    Text(events[i], fontSize = 12.sp,
                        color = if (events[i].startsWith("──")) Palette.SubText else Palette.Text,
                        modifier = Modifier.padding(vertical = 2.dp))
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("エージェントが作業しています…（質問が来ることがあります）", fontSize = 12.sp, color = Palette.SubText)
        }
    }
}

// ================================================================ レビュー（R2-06）
// 手動実行と受信箱（承認待ち）の双方から使う。
// onDecision(採用ファイル, 適用したか, メッセージ) で採否を通知する。

@Composable
internal fun ReviewContent(
    state: AppState,
    ws: Workspace,
    result: AgentRunResult,
    runFile: Path?,
    onDecision: (adoptedFiles: List<String>, applied: Boolean, message: String) -> Unit,
) {
    val changes = result.changeSet.changes
    val selected = remember { mutableStateMapOf<String, Boolean>().apply { changes.forEach { put(it.file, true) } } }
    var currentFile by remember { mutableStateOf(changes.firstOrNull()?.file) }
    var applyMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // 部分採用のたびに、採用サブセットをステージングへ再構成して検証する（参照整合の破れを見せる）
    val subsetIssues = remember(selected.toMap()) {
        runCatching {
            val staging = Staging(ws)
            changes.filter { selected[it.file] == true }.forEach { change ->
                if (change.create) staging.createDocument(change.file, change.content!!)
                else staging.applyOps(change.file, change.ops)
            }
            if (staging.isEmpty()) emptyList() else staging.validateAll().flatMap { (rel, issues) -> issues.map { rel to it } }
        }.getOrElse {
            // 検証そのものが失敗した（コードで実装された検査の指摘として、出どころを付ける）
            val source = CodedChecks.source("validation", "failed", IssueDelivery.REPORT, emptyList(), emptyList())
            listOf("(全体)" to Issue(Severity.ERROR, "検証できません: ${it.message}", emptyList(), source = source))
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            "${statusLabel(result.status)}${result.message?.let { " — $it" } ?: ""}",
            fontSize = 12.sp,
            color = if (result.status == RunStatus.SUBMITTED) Palette.OkGreen else Palette.WarnOrange,
        )
        Text(
            listOfNotNull(
                "ターン ${result.turnsUsed}",
                "トークン 入力${result.usage.inputTokens}" +
                    (if (result.usage.cachedInputTokens > 0) "（うちキャッシュ${result.usage.cachedInputTokens}）" else "") +
                    "／出力${result.usage.outputTokens}",
                runFile?.let { "記録 ${ws.relPath(it)}" },
            ).joinToString(" ／ "),
            fontSize = 11.sp, color = Palette.SubText,
        )
        // コンテキスト会計（R19-01）: 毎ターン再送される固定部の内訳（概算）。どの宣言が高いかを見てレシピを調整する
        if (result.contextStats.isNotEmpty()) {
            Text(
                "コンテキスト内訳（毎ターン再送・概算トークン）: " +
                    result.contextStats.joinToString(" ／ ") { "${it.section} ≈${it.tokensEst}" },
                fontSize = 11.sp, color = Palette.SubText,
            )
        }
        if (result.turnInputTokens.isNotEmpty()) {
            Text(
                "送信別入力トークン（実測）: " + result.turnInputTokens.joinToString(", "),
                fontSize = 11.sp, color = Palette.SubText,
            )
        }
        if (result.status != RunStatus.SUBMITTED) {
            Text("未提出のドラフトです。内容を確認のうえ、必要な分だけ採用できます。", fontSize = 11.sp, color = Palette.WarnOrange)
        }
        Spacer(Modifier.height(6.dp))
        val errors = subsetIssues.count { it.second.severity == Severity.ERROR }
        val warnings = subsetIssues.count { it.second.severity == Severity.WARNING }
        Text(
            "採用中の変更の検証: エラー $errors 件・警告 $warnings 件",
            fontSize = 12.sp,
            color = if (errors > 0) Palette.ErrorRed else if (warnings > 0) Palette.WarnOrange else Palette.OkGreen,
        )
        subsetIssues.take(5).forEach { (rel, issue) ->
            Text("・[$rel] ${issue.message}", fontSize = 11.sp,
                color = if (issue.severity == Severity.ERROR) Palette.ErrorRed else Palette.WarnOrange)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 460.dp)) {
            // 左: ファイル一覧（チェックで採否）
            Column(Modifier.width(300.dp).fillMaxHeight().background(Palette.PanelBg, PanelShape).padding(6.dp)) {
                changes.forEach { change ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (currentFile == change.file) Palette.Selected else Color.Transparent, PanelShape)
                            .clickable { currentFile = change.file }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(selected[change.file] == true, { selected[change.file] = it }, Modifier.size(28.dp))
                        Column {
                            Text(change.file, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(
                                if (change.create) "新規作成" else "${change.ops.size} 操作",
                                fontSize = 10.sp, color = Palette.SubText,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            // 右: 選択ファイルの差分
            Box(Modifier.weight(1f).fillMaxHeight().background(Palette.EditorBg, PanelShape).padding(8.dp)) {
                val file = currentFile
                if (file == null) Text("ファイルを選択してください", fontSize = 12.sp, color = Palette.SubText)
                else {
                    val entry = result.staging.touchedFiles().firstOrNull { it.rel == file }
                    val before = entry?.baseText ?: ""
                    val after = result.staging.proposedText(file) ?: ""
                    val lines = remember(file) { calculateDiff(before, after) }
                    val listState = rememberLazyListState()
                    Column {
                        Row(Modifier.fillMaxWidth().background(Palette.PanelBg).padding(vertical = 4.dp)) {
                            Text(if (entry?.created == true) "（新規）" else "現在", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f).padding(start = 42.dp))
                            Text("提案", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f).padding(start = 42.dp))
                        }
                        Box(Modifier.fillMaxSize()) {
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 8.dp)) {
                                items(lines) { line -> DiffLineRow(line) }
                            }
                            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = changes.any { selected[it.file] == true },
                onClick = {
                    val adopted = changes.filter { selected[it.file] == true }
                    val rejected = changes.filter { selected[it.file] != true }.map { it.file }
                    val dirtyTargets = adopted.mapNotNull { c ->
                        runCatching { ws.resolve(c.file) }.getOrNull()?.let { p -> state.docFor(p)?.takeIf { it.dirty }?.let { c.file } }
                    }
                    if (dirtyTargets.isNotEmpty()) {
                        applyMessage = "エディタに未保存の変更があるため適用できません: ${dirtyTargets.joinToString(", ")}"
                        return@Button
                    }
                    scope.launch {
                        // A-2b: 書込はセッション集約の適用経路（検証・競合判定は従来と同一）
                        val applyResult = withContext(Dispatchers.IO) { state.applyAgentChanges(ChangeSet(adopted)) }
                        runFile?.let {
                            withContext(Dispatchers.IO) {
                                RunLog.appendAdoption(it, adopted.map { a -> a.file }, rejected, applyResult.ok)
                            }
                        }
                        if (applyResult.ok) {
                            state.reload()
                            onDecision(adopted.map { it.file }, true,
                                "適用しました（${adopted.size} ファイル）。適用結果は各文書の検証パネルでも確認できます。")
                        } else {
                            applyMessage = "適用に失敗しました:\n" + applyResult.results
                                .filter { it.message != null }.joinToString("\n") { "・${it.file}: ${it.message}" }
                        }
                    }
                },
            ) { Text("選択した変更を適用") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                runFile?.let { RunLog.appendAdoption(it, emptyList(), changes.map { c -> c.file }, applied = false) }
                onDecision(emptyList(), false,
                    "変更を採用せず終了しました（実行記録: ${runFile?.let { ws.relPath(it) } ?: "なし"}）")
            }) { Text("すべて却下") }
        }
        if (applyMessage.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(applyMessage, fontSize = 12.sp, color = Palette.ErrorRed)
        }
    }
}
