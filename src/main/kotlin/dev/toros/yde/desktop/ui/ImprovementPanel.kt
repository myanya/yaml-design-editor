package dev.toros.yde.desktop.ui

import dev.toros.yde.agent.auto.AutomationEngine
import dev.toros.yde.agent.EvalDef
import dev.toros.yde.agent.eval.EvalRunner
import dev.toros.yde.agent.Evals
import dev.toros.yde.agent.eval.RunMetrics
import dev.toros.yde.agent.llm.AiProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.desktop.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 計測・評価セクション（R4のUI）。実行スペースのタブとして常設表示する（R12-01。モーダルにしない）。
 * 実行メトリクス（_runs の集計）と評価セット（_evals）の実行・比較結果を1画面で扱う。
 * 評価はサンドボックスコピー上で走るため、開いているワークスペースは変更されない。
 */
@Composable
fun ImprovementSection(state: AppState) {
    val wsDir = state.workspace ?: return
    var metrics by remember { mutableStateOf<List<RunMetrics.ActivitySummary>>(emptyList()) }
    var evals by remember { mutableStateOf<Evals.LoadResult?>(null) }
    var runningEval by remember { mutableStateOf<String?>(null) }
    val reports = remember { mutableStateMapOf<String, EvalRunner.Report>() }
    var evalLog by remember { mutableStateOf(listOf<String>()) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            withContext(Dispatchers.IO) {
                // スナップショット状態はスレッド安全のため、IOスレッドから直接更新する（Dispatchers.Main は使わない）
                metrics = RunMetrics.summarize(RunMetrics.collect(wsDir))
                evals = Evals.load(wsDir)
            }
        }
    }
    LaunchedEffect(wsDir) { reload() }

    fun runEval(def: EvalDef) {
        runningEval = def.id
        evalLog = emptyList()
        scope.launch {
            val runner = EvalRunner(credentials = {
                AutomationEngine.Credentials(
                    provider = AiSettings.provider,
                    models = AiProvider.entries.associateWith { AiSettings.modelFor(it) },
                    apiKeys = AiProvider.entries.associateWith { AiSettings.apiKeyFor(it) }
                        .filterValues { it.isNotBlank() },
                )
            })
            try {
                val report = withContext(Dispatchers.IO) {
                    runner.run(wsDir, def, onLog = { line -> evalLog = evalLog + line })
                }
                reports[def.id] = report
            } finally {
                runningEval = null
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("実行記録の集計と、評価セットによるアクティビティの回帰評価・比較実験を行います",
            fontSize = 11.sp, color = Palette.SubText)
        Spacer(Modifier.height(8.dp))

        // ---------------- 実行メトリクス（R4-01） ----------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("実行メトリクス（_runs の集計）")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { reload() }) { Text("再集計", fontSize = 11.sp) }
        }
        if (metrics.isEmpty()) {
            Text("実行記録がありません（アクティビティを実行すると _runs に記録されます）",
                fontSize = 11.sp, color = Palette.SubText)
        } else {
            MetricsHeader()
            metrics.forEach { s -> MetricsRow(s) }
            Text(
                "採用率 = 採否が決まった実行のうち採用された割合 ／ 却下率 = 部分採用で却下されたファイルの割合（修正量の代理指標）",
                fontSize = 10.sp, color = Palette.SubText,
            )
        }
        Spacer(Modifier.height(12.dp))

        // ---------------- 評価セット（R4-02 / R4-04） ----------------
        SectionTitle("評価セット（_evals）")
        val loaded = evals
        loaded?.errors?.forEach { Text("⚠ $it", fontSize = 11.sp, color = Palette.ErrorRed) }
        if (loaded == null || loaded.evals.isEmpty()) {
            Text("評価セットがありません。_evals/*.yaml にゴールデン入力と期待結果を定義します（README §13）。",
                fontSize = 11.sp, color = Palette.SubText)
        }
        loaded?.evals?.forEach { def ->
            Column(
                Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .background(Palette.PanelBg, PanelShape)
                    .border(1.dp, Palette.Border, PanelShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${def.name}（${def.id}）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "対象: ${def.activityId} ／ ${def.cases.size} ケース" +
                                (if (def.variants.isNotEmpty()) " ／ 変異: ${def.variants.joinToString(", ") { it.name }}" else ""),
                            fontSize = 11.sp, color = Palette.SubText,
                        )
                    }
                    Button(
                        enabled = runningEval == null,
                        onClick = { runEval(def) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    ) {
                        Text(if (runningEval == def.id) "実行中…" else "実行", fontSize = 12.sp)
                    }
                }
                reports[def.id]?.let { report ->
                    Spacer(Modifier.height(4.dp))
                    report.summaries().forEach { s ->
                        Text(
                            "▸ ${s.variant}: ${s.passed}/${s.cases} 合格（${(s.passRate * 100).roundToInt()}%）" +
                                " ／ ${s.totalTurns} ターン ／ ${s.inputTokens}+${s.outputTokens} tokens",
                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            color = if (s.passed == s.cases) Palette.OkGreen else Palette.WarnOrange,
                        )
                    }
                    report.results.filter { !it.pass }.forEach { r ->
                        Text(
                            "  ✗ ${r.variant} / ${r.caseId}: ${r.failures.firstOrNull() ?: ""}",
                            fontSize = 10.sp, color = Palette.ErrorRed, maxLines = 2,
                        )
                    }
                    report.reportFile?.let {
                        Text("  レポート: ${wsDir.relativize(it)}", fontSize = 10.sp, color = Palette.SubText)
                    }
                }
            }
        }

        // ---------------- 実行ログ ----------------
        if (evalLog.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SectionTitle("評価ログ")
            Column(
                Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 160.dp)
                    .background(Palette.EditorBg, PanelShape).padding(8.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                evalLog.forEach { line ->
                    Text(line, fontFamily = AppFonts.Mono, fontSize = 10.sp, color = Palette.SubText)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "評価はワークスペースの一時コピー上で実行され、原本は変更されません。定義の改善提案は meta: true のアクティビティで行えます（承認は必ず人間）。",
            fontSize = 10.sp, color = Palette.SubText,
        )
    }
}

@Composable
private fun MetricsHeader() {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        MetricsCell("アクティビティ", 3f, bold = true)
        MetricsCell("実行", 1f, bold = true)
        MetricsCell("提出率", 1f, bold = true)
        MetricsCell("採用率", 1f, bold = true)
        MetricsCell("却下率", 1f, bold = true)
        MetricsCell("自動適用", 1f, bold = true)
        MetricsCell("巻戻し", 1f, bold = true)
        MetricsCell("平均ターン", 1f, bold = true)
        MetricsCell("tokens(in/out)", 2f, bold = true)
    }
}

@Composable
private fun MetricsRow(s: RunMetrics.ActivitySummary) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        MetricsCell(s.activityId, 3f)
        MetricsCell("${s.runs}", 1f)
        MetricsCell(percent(s.submitRate), 1f)
        MetricsCell(if (s.decided == 0) "—" else percent(s.adoptionRate), 1f)
        MetricsCell(if (s.adoptedFiles + s.rejectedFiles == 0) "—" else percent(s.rejectedFileRate), 1f)
        MetricsCell("${s.autoApplied}", 1f)
        MetricsCell("${s.reverted}", 1f)
        MetricsCell(String.format("%.1f", s.avgTurns), 1f)
        MetricsCell("${s.inputTokens}/${s.outputTokens}", 2f)
    }
}

@Composable
private fun RowScope.MetricsCell(text: String, weight: Float, bold: Boolean = false) {
    Text(
        text, fontSize = 11.sp, maxLines = 1,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = if (bold) Palette.SubText else Palette.Text,
        modifier = Modifier.weight(weight),
    )
}

private fun percent(rate: Double): String = "${(rate * 100).roundToInt()}%"

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.SubText,
        modifier = Modifier.padding(vertical = 3.dp))
}
