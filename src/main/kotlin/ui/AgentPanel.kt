package ui

import agent.AgentEvent
import agent.AgentHost
import agent.AgentKernel
import agent.AgentRunRequest
import agent.AgentRunResult
import agent.Definitions
import agent.RunLog
import agent.RunStatus
import agent.Staging
import ai.AiProvider
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.AppState
import core.ChangeSet
import core.Workspace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import validate.Severity
import java.nio.file.Path

/**
 * アクティビティ実行ダイアログ（R2-03/06/07 のUI）。
 * 選択 → 実行（進捗ストリーミング・質問応答・キャンセル）→ 変更セットレビュー（部分採用）→ 適用。
 * 上限到達・中断でもステージングがあれば未提出ドラフトとして同じレビューを表示する。
 */

private enum class AgentStage { SELECT, RUNNING, REVIEW, DONE }

private class PendingQuestion(val question: String, val answer: CompletableDeferred<String?>)

@Composable
fun AgentRunHost(state: AppState, engine: agent.AutomationEngine? = null, onClose: () -> Unit) {
    val wsPath = state.workspace ?: return
    var runActivityId by remember { mutableStateOf<String?>(null) }
    var stage by remember { mutableStateOf(AgentStage.SELECT) }
    var defsRefresh by remember { mutableStateOf(0) }
    val defs = remember(wsPath, defsRefresh) { Definitions.load(wsPath) }
    var selectedActivity by remember(defs) { mutableStateOf(defs.activities.firstOrNull()) }
    var extraInstruction by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    // 実行状態
    val events = remember { mutableStateListOf<String>() }
    var plan by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingQuestion by remember { mutableStateOf<PendingQuestion?>(null) }
    var runJob by remember { mutableStateOf<Job?>(null) }
    var runResult by remember { mutableStateOf<AgentRunResult?>(null) }
    var runFile by remember { mutableStateOf<Path?>(null) }
    var coreWs by remember { mutableStateOf<Workspace?>(null) }
    var doneMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // 実効プロバイダ／モデル: エージェント定義のピン留めが最優先、なければダイアログの選択値。
    // APIキーは必ず「実効プロバイダ」のものを使う（選択中プロバイダの鍵を流用しない）。
    val selectedAgentDef = selectedActivity?.let { defs.agent(it.agentId) }
    val effectiveProvider = selectedAgentDef?.provider ?: AiSettings.provider
    val effectiveModel = selectedAgentDef?.model ?: AiSettings.modelFor(effectiveProvider).trim()

    fun startRun() {
        val activity = selectedActivity ?: return
        val agentDef = defs.agent(activity.agentId) ?: run {
            errorMessage = "エージェント「${activity.agentId}」が見つかりません"; return
        }
        if (effectiveModel.isBlank()) {
            errorMessage = "${effectiveProvider.label} のモデルを入力してください"; return
        }
        errorMessage = ""
        events.clear(); plan = emptyList(); runResult = null; runFile = null
        runActivityId = activity.id
        stage = AgentStage.RUNNING
        val request = AgentRunRequest(
            activity = activity,
            agent = agentDef,
            provider = effectiveProvider,
            model = effectiveModel,
            apiKey = AiSettings.apiKeyFor(effectiveProvider),
            extraInstruction = extraInstruction.takeIf { it.isNotBlank() },
            apiKeys = AiProvider.entries.associateWith { AiSettings.apiKeyFor(it) }.filterValues { it.isNotBlank() },
            subAgents = defs.agents,
        )
        // Compose のスナップショット状態はスレッド安全のため、カーネルのIOスレッドから直接更新する。
        // Dispatchers.Main は Compose for Desktop の classpath に提供モジュールがなく、使うと実行時例外になる。
        val host = object : AgentHost {
            override suspend fun askHuman(question: String): String? {
                val deferred = CompletableDeferred<String?>()
                pendingQuestion = PendingQuestion(question, deferred)
                try {
                    return deferred.await()
                } finally {
                    pendingQuestion = null
                }
            }
            override suspend fun onEvent(event: AgentEvent) {
                when (event) {
                    is AgentEvent.TurnStarted -> events.add("── ターン ${event.turn}/${event.maxTurns}")
                    is AgentEvent.AssistantText -> events.add(event.text)
                    is AgentEvent.ToolInvoked -> events.add("🔧 ${event.name} ${event.summary}")
                    is AgentEvent.ToolFailed -> events.add("⚠ ${event.name}: ${event.message}")
                    is AgentEvent.QuestionAsked -> events.add("❓ ${event.question}")
                    is AgentEvent.PlanUpdated -> { plan = event.steps; events.add("📋 計画: ${event.steps.joinToString(" / ")}") }
                    is AgentEvent.Finished -> events.add("── 終了: ${event.status}${event.message?.let { " — $it" } ?: ""}")
                }
            }
        }
        runJob = scope.launch {
            val ws = withContext(Dispatchers.IO) { Workspace.open(wsPath) }
            coreWs = ws
            val result = withContext(Dispatchers.IO) { AgentKernel.run(ws, request, host) }
            runResult = result
            runFile = withContext(Dispatchers.IO) { runCatching { RunLog.write(ws, request, result) }.getOrNull() }
            stage = if (result.changeSet.changes.isEmpty() && result.status != RunStatus.SUBMITTED) {
                doneMessage = "${statusLabel(result.status)}: ${result.message ?: ""}（変更はありません）"
                AgentStage.DONE
            } else AgentStage.REVIEW
        }
    }

    AlertDialog(
        onDismissRequest = { if (stage != AgentStage.RUNNING) onClose() },
        modifier = Modifier.widthIn(min = 760.dp, max = 1120.dp),
        title = {
            Column {
                Text(
                    when (stage) {
                        AgentStage.SELECT -> "アクティビティを実行"
                        AgentStage.RUNNING -> "実行中: ${selectedActivity?.name ?: ""}"
                        AgentStage.REVIEW -> "変更セットのレビュー"
                        AgentStage.DONE -> "実行結果"
                    }
                )
                Text("エージェントの編集はステージング上で行われ、承認するまで原本は変わりません",
                    fontSize = 11.sp, color = Palette.SubText)
            }
        },
        text = {
            when (stage) {
                AgentStage.SELECT -> SelectContent(
                    defs = defs,
                    selected = selectedActivity,
                    onSelect = { selectedActivity = it },
                    extraInstruction = extraInstruction,
                    onExtraChange = { extraInstruction = it },
                    onRefresh = { defsRefresh++ },
                    errorMessage = errorMessage,
                    pinnedProvider = selectedAgentDef?.provider,
                    pinnedModel = selectedAgentDef?.model,
                    effectiveProvider = effectiveProvider,
                )
                AgentStage.RUNNING -> RunningContent(events, plan)
                AgentStage.REVIEW -> runResult?.let { result ->
                    ReviewContent(state, coreWs!!, result, runFile) { adopted, applied, message ->
                        // 手動実行の連鎖はエンジン有効時のみ発火する（結果を渡すと出力値による分岐も効く。R9-04）
                        runActivityId?.let {
                            engine?.notifyManualCompletion(it, applied && adopted.isNotEmpty(), result, adopted)
                        }
                        doneMessage = message
                        stage = AgentStage.DONE
                    }
                } ?: Text("結果がありません")
                AgentStage.DONE -> Text(doneMessage, fontSize = 13.sp)
            }
        },
        confirmButton = {
            when (stage) {
                AgentStage.SELECT -> Button(
                    enabled = selectedActivity != null && AiSettings.apiKeyFor(effectiveProvider).isNotBlank(),
                    onClick = ::startRun,
                ) { Text("実行") }
                else -> Unit
            }
        },
        dismissButton = {
            when (stage) {
                AgentStage.RUNNING -> TextButton(onClick = {
                    runJob?.cancel(); runJob = null
                }) { Text("キャンセル", color = Palette.ErrorRed) }
                AgentStage.DONE -> TextButton(onClick = onClose) { Text("閉じる") }
                else -> TextButton(onClick = onClose) { Text("閉じる") }
            }
        },
    )

    // R2-07: エージェントからのブロッキングな質問
    pendingQuestion?.let { q ->
        var answer by remember(q) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { },
            title = { Text("エージェントからの質問") },
            text = {
                Column(Modifier.width(560.dp)) {
                    Text(q.question, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    CompactTextField(answer, { answer = it },
                        placeholder = { Text("回答（追加の指示もここで伝えられます）", fontSize = 12.sp, color = Palette.SubText) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp))
                }
            },
            confirmButton = {
                Button(enabled = answer.isNotBlank(), onClick = { q.answer.complete(answer) }) { Text("回答する") }
            },
            dismissButton = {
                TextButton(onClick = { q.answer.complete(null) }) { Text("回答しない") }
            },
        )
    }
}

// ================================================================ 選択

@Composable
private fun SelectContent(
    defs: Definitions.LoadResult,
    selected: agent.ActivityDef?,
    onSelect: (agent.ActivityDef) -> Unit,
    extraInstruction: String,
    onExtraChange: (String) -> Unit,
    onRefresh: () -> Unit,
    errorMessage: String,
    pinnedProvider: AiProvider?,
    pinnedModel: String?,
    effectiveProvider: AiProvider,
) {
    Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState())) {
        if (defs.activities.isEmpty()) {
            Text("アクティビティが定義されていません。", fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "_activities/ にアクティビティ定義、_agents/ にエージェント定義（いずれもYAML）を置いてください。\n" +
                    "どちらも組込みスキーマ付きで、この編集画面のフォーム・検証がそのまま使えます。",
                fontSize = 12.sp, color = Palette.SubText,
            )
        } else {
            Text("アクティビティ", fontSize = 12.sp, color = Palette.SubText)
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { open = true }) {
                    Text(selected?.let { "${it.name}（${it.id}）" } ?: "選択してください", fontSize = 13.sp)
                }
                DropdownMenu(open, { open = false }) {
                    defs.activities.forEach { activity ->
                        DropdownMenuItem(text = {
                            Column {
                                Text(activity.name, fontSize = 13.sp)
                                Text(
                                    listOfNotNull(activity.id, "agent: ${activity.agentId}", activity.targetMatch)
                                        .joinToString(" ／ "),
                                    fontSize = 11.sp, color = Palette.SubText,
                                )
                            }
                        }, onClick = { onSelect(activity); open = false })
                    }
                }
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
                            "ゲート: " + if (activity.gate == agent.ValidationGate.ERROR_FREE) "検証エラー0" else "なし",
                        ).joinToString(" ／ "),
                        fontSize = 11.sp, color = Palette.SubText,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("追加指示（任意）", fontSize = 12.sp, color = Palette.SubText)
            CompactTextField(extraInstruction, onExtraChange,
                placeholder = { Text("今回の実行で特に重視する点など", fontSize = 12.sp, color = Palette.SubText) },
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
// 実行ダイアログと自動化パネル（承認待ち）の双方から使う。
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
        }.getOrElse { listOf("(全体)" to validate.Issue(Severity.ERROR, "検証できません: ${it.message}", emptyList())) }
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
                "トークン 入力${result.usage.inputTokens}／出力${result.usage.outputTokens}",
                runFile?.let { "記録 ${ws.relPath(it)}" },
            ).joinToString(" ／ "),
            fontSize = 11.sp, color = Palette.SubText,
        )
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
                        val applyResult = withContext(Dispatchers.IO) { ws.apply(ChangeSet(adopted)) }
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

private fun statusLabel(status: RunStatus): String = when (status) {
    RunStatus.SUBMITTED -> "提出されました"
    RunStatus.ABORTED -> "中断されました"
    RunStatus.LIMIT -> "上限に達しました"
    RunStatus.CANCELLED -> "キャンセルされました"
    RunStatus.ERROR -> "エラーが発生しました"
}
