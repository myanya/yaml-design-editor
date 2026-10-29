package dev.toros.yde.desktop.ui.run

import dev.toros.yde.agent.auto.AutomationEngine
import androidx.compose.foundation.background
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.ui.common.AppFonts
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PanelShape

/**
 * 自動化セクション（R3のUI）。実行スペースのタブとして常設表示する（R12-01。モーダルにしない）。
 * トリガ監視の有効化・実行キュー・履歴・ログを扱う。承認待ちのレビューは受信箱（R12-02）が担う。
 * version はエンジンの onUpdate で加算されるカウンタで、再コンポーズの購読に使う。
 */
@Composable
fun AutomationSection(state: AppState, engine: AutomationEngine, version: Int, onOpenInbox: () -> Unit) {
    version  // エンジン更新の購読
    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }
    fun time(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFormat)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("トリガ（ファイル更新・スケジュール）でアクティビティを起動し、承認待ちは受信箱でレビューします",
            fontSize = 11.sp, color = Palette.SubText)
        Spacer(Modifier.height(8.dp))

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
        if (engine.pendingSnapshot().isNotEmpty()) {
            TextButton(onClick = onOpenInbox) { Text("承認待ち ${engine.pendingSnapshot().size} 件を受信箱でレビュー", fontSize = 12.sp) }
        }
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
                Text(line, fontFamily = AppFonts.Mono, fontSize = 10.sp, color = Palette.SubText)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "自動適用された変更は実行記録（_runs）にバックアップされ、CLI の revert で巻き戻せます。",
            fontSize = 10.sp, color = Palette.SubText,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.SubText,
        modifier = Modifier.padding(vertical = 3.dp))
}
