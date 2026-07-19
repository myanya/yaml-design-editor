package ui

import agent.AutomationEngine
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.AppState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 自動実行パネル（R3のUI）。
 * トリガ監視の有効化・実行キュー・承認待ちレビュー・履歴・ログを1画面で扱う。
 * version はエンジンの onUpdate で加算されるカウンタで、再コンポーズの購読に使う。
 */
@Composable
fun AutomationPanel(state: AppState, engine: AutomationEngine, version: Int, onClose: () -> Unit) {
    version  // エンジン更新の購読
    var reviewTarget by remember { mutableStateOf<AutomationEngine.PendingReview?>(null) }
    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }
    fun time(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFormat)

    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.widthIn(min = 760.dp, max = 1000.dp),
        title = {
            Column {
                Text("自動実行")
                Text("トリガ（ファイル更新・スケジュール）でアクティビティを起動し、承認待ちをここでレビューします",
                    fontSize = 11.sp, color = Palette.SubText)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                // ---------------- 制御 ----------------
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = engine.enabled, onCheckedChange = { engine.setEnabled(it) })
                    Spacer(Modifier.width(8.dp))
                    Text(if (engine.enabled) "監視・自動実行 有効" else "監視・自動実行 停止中", fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (engine.enabled) Palette.OkGreen else Palette.SubText)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { engine.stopAll() }) {
                        Text("停止（キュー消去・実行中断）", fontSize = 12.sp, color = Palette.ErrorRed)
                    }
                }
                Text(
                    "実行中: ${engine.runningActivity ?: "なし"} ／ 待機: ${engine.queueSnapshot().size} 件 ／ 承認待ち: ${engine.pendingSnapshot().size} 件",
                    fontSize = 12.sp, color = Palette.SubText,
                )
                Spacer(Modifier.height(8.dp))

                // ---------------- キュー ----------------
                val queue = engine.queueSnapshot()
                if (queue.isNotEmpty()) {
                    SectionTitle("実行キュー（${queue.size}）")
                    queue.forEach { item ->
                        Text(
                            "・${item.activityId} — ${item.reason}" +
                                (if (item.attempt > 1) "（試行 ${item.attempt}）" else "") +
                                (if (item.chainDepth > 0) "（連鎖 深さ${item.chainDepth}）" else ""),
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // ---------------- 承認待ち ----------------
                val pending = engine.pendingSnapshot()
                SectionTitle("承認待ちレビュー（${pending.size}）")
                if (pending.isEmpty()) Text("承認待ちはありません", fontSize = 11.sp, color = Palette.SubText)
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
                        Button(onClick = { reviewTarget = review },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                            Text("レビュー", fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                // ---------------- 履歴 ----------------
                val history = engine.historySnapshot()
                SectionTitle("履歴（直近 ${history.size} 件）")
                if (history.isEmpty()) Text("履歴はありません", fontSize = 11.sp, color = Palette.SubText)
                history.forEach { entry ->
                    Text(
                        "${time(entry.atMillis)} [${entry.status}] ${entry.activityId}" +
                            (entry.detail?.let { " — ${it.lineSequence().first().take(80)}" } ?: ""),
                        fontSize = 11.sp,
                        color = when (entry.status) {
                            "自動適用", "採用", "完了" -> Palette.OkGreen
                            "失敗", "スキップ" -> Palette.ErrorRed
                            "再試行", "承認待ち", "却下" -> Palette.WarnOrange
                            else -> Palette.Text
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))

                // ---------------- ログ ----------------
                SectionTitle("ログ")
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 180.dp)
                        .background(Palette.EditorBg, PanelShape).padding(8.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    engine.logSnapshot().forEach { line ->
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Palette.SubText)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "自動適用された変更は実行記録（_runs）にバックアップされ、CLI の revert で巻き戻せます。",
                    fontSize = 10.sp, color = Palette.SubText,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("閉じる") } },
    )

    // 承認待ちのレビュー（実行ダイアログと同じ ReviewContent を再利用）
    reviewTarget?.let { review ->
        AlertDialog(
            onDismissRequest = { reviewTarget = null },  // 閉じる = 保留のまま
            modifier = Modifier.widthIn(min = 760.dp, max = 1120.dp),
            title = {
                Column {
                    Text("承認待ち: ${review.activityName}")
                    Text(review.reason, fontSize = 11.sp, color = Palette.SubText)
                }
            },
            text = {
                ReviewContent(state, review.ws, review.result, review.runFile) { adopted, applied, _ ->
                    engine.completeReview(review, adopted, applied)
                    reviewTarget = null
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { reviewTarget = null }) { Text("閉じる（保留）") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.SubText,
        modifier = Modifier.padding(vertical = 3.dp))
}
