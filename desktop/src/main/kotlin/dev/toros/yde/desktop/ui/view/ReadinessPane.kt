package dev.toros.yde.desktop.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.validate.Readiness
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.ui.common.CompactTextField
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PanelShape
import dev.toros.yde.desktop.ui.common.TableColumn
import dev.toros.yde.desktop.ui.common.VirtualTable

// ================================================================ 準備完了レポート（台帳スペースの「準備」タブ）

/** 図のデータ（純ロジック）。行＝型やフェーズ、系列＝準備完了と未完（総数 − 準備完了）。 */
internal data class ReadinessChartData(val categories: List<String>, val ready: List<Double>, val pending: List<Double>)

internal fun readinessChartData(rows: List<Readiness.GroupRow>): ReadinessChartData = ReadinessChartData(
    categories = rows.map { if (it.key == "-") "（なし）" else it.key },   // 型やフェーズの無い要素の集まり
    ready = rows.map { it.ready.toDouble() },
    pending = rows.map { (it.total - it.ready).coerceAtLeast(0).toDouble() },
)

/** 準備完了レポートの表の列。 */
private enum class ReadyColumn(val label: String) {
    STATE("状態"), ID("ID"), TYPE("型"), TITLE("タイトル"), PHASE("フェーズ"),
    ERRORS("エラー"), WARNINGS("警告"), WITHHELD("保留"), FILE("ファイル"),
}

private val READY_COLUMNS = listOf(
    TableColumn(ReadyColumn.STATE, 76.dp, pinned = true), TableColumn(ReadyColumn.ID, 170.dp, pinned = true),
    TableColumn(ReadyColumn.TYPE, 120.dp), TableColumn(ReadyColumn.TITLE, 260.dp), TableColumn(ReadyColumn.PHASE, 100.dp),
    TableColumn(ReadyColumn.ERRORS, 64.dp), TableColumn(ReadyColumn.WARNINGS, 64.dp), TableColumn(ReadyColumn.WITHHELD, 64.dp),
    TableColumn(ReadyColumn.FILE, 380.dp),
)

/**
 * 準備完了レポート（CLI `readiness` と同じ計算＝保存済みの内容で判定）。
 * 上から、件数のタイル → 型別・フェーズ別の積み上げ棒（準備完了／未完） → エンティティの表（行クリックで横ぐしへ）。
 */
@Composable
fun ReadinessPane(state: AppState) {
    var refresh by remember { mutableStateOf(0) }
    val report = remember(state.workspace, state.indexVersion, refresh) { state.readinessReport() }
    // 絞り込みは画面の覚え（タブを離れても残る）
    val pendingOnly = state.memory.readinessPendingOnly
    val query = state.memory.readinessQuery
    Column(Modifier.fillMaxSize().background(Palette.EditorBg)) {
        Row(
            Modifier.fillMaxWidth().background(Palette.Surface).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("準備完了レポート", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
            Text("保存済みの内容で判定します（CLI の readiness と同じ）。未保存の編集は保存後に反映されます",
                fontSize = 11.sp, color = Palette.SubText)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { refresh++ }) { Text("再集計", fontSize = 11.sp) }
        }
        if (report == null) {
            Text("ワークスペースが開かれていません", Modifier.padding(16.dp), fontSize = 13.sp, color = Palette.SubText)
            return
        }
        val total = report.entities.size
        val ready = report.entities.count { it.ready }
        val errors = report.entities.sumOf { it.errors } + report.unassigned.sumOf { it.errors }
        val warnings = report.entities.sumOf { it.warnings } + report.unassigned.sumOf { it.warnings }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("準備完了", "$ready / $total", Palette.ChartReady)
            StatTile("未完", "${total - ready}", Palette.ChartPending)
            StatTile("エラー", "$errors", Palette.ErrorRed)
            StatTile("警告", "$warnings", Palette.WarnOrange)
            if (report.withheld.isNotEmpty()) StatTile("保留した検査", "${report.withheld.size}", Palette.SubText)
        }
        val byType = remember(report) { readinessChartData(report.byType) }
        val byPhase = remember(report) { readinessChartData(report.byPhase.filter { it.key != "-" }) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ChartCard("型別", byType, Modifier.weight(1f))
            if (byPhase.categories.isNotEmpty()) ChartCard("フェーズ別", byPhase, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(pendingOnly, { state.memory.readinessPendingOnly = it }, modifier = Modifier.size(30.dp))
            Text("未完だけ", fontSize = 12.sp)
            Spacer(Modifier.width(12.dp))
            CompactTextField(query, { state.memory.readinessQuery = it }, singleLine = true,
                placeholder = { Text("ID・タイトル・ファイルで絞り込み") }, modifier = Modifier.width(260.dp))
        }
        val rows = remember(report, pendingOnly, query) {
            report.entities
                .filter { !pendingOnly || !it.ready }
                .filter { query.isBlank() || listOf(it.id, it.title, it.file, it.entityType).any { v -> v?.contains(query, true) == true } }
                .sortedWith(compareBy({ it.ready }, { it.id }))
        }
        VirtualTable(
            columns = READY_COLUMNS, rows = rows, rowKey = { it.id },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            rowPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            rowModifier = { row -> Modifier.clickable { state.openCrossView(row.id) } },
            header = { col -> Text(col.key.label, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
            cell = { row, col ->
                when (col.key) {
                    ReadyColumn.STATE -> ReadyMark(row.ready)
                    ReadyColumn.ID -> Cell(row.id, bold = true)
                    ReadyColumn.TYPE -> Cell(row.entityType ?: "—")
                    ReadyColumn.TITLE -> Cell(row.title ?: "—")
                    ReadyColumn.PHASE -> Cell(row.phase ?: "—")
                    ReadyColumn.ERRORS -> Cell(if (row.errors > 0) "${row.errors}" else "—", color = if (row.errors > 0) Palette.ErrorRed else null)
                    ReadyColumn.WARNINGS -> Cell(if (row.warnings > 0) "${row.warnings}" else "—", color = if (row.warnings > 0) Palette.WarnOrange else null)
                    ReadyColumn.WITHHELD -> Cell(if (row.withheld > 0) "${row.withheld}" else "—")
                    ReadyColumn.FILE -> Cell(row.file ?: "—", color = Palette.SubText)
                }
            },
        )
        if (report.unassigned.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(Palette.PanelBg).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("エンティティに帰属しない指摘のあるファイル", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.SubText)
                report.unassigned.take(8).forEach { f ->
                    Row(
                        Modifier.clickable { state.workspace?.resolve(f.file)?.let { state.openFile(it) } }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(f.file, fontSize = 11.sp, color = Palette.Primary, modifier = Modifier.width(420.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("エラー ${f.errors}・警告 ${f.warnings}", fontSize = 11.sp, color = Palette.SubText)
                    }
                }
                if (report.unassigned.size > 8) Text("…ほか ${report.unassigned.size - 8} 件", fontSize = 10.sp, color = Palette.SubText)
            }
        }
    }
}

@Composable
private fun ChartCard(title: String, data: ReadinessChartData, modifier: Modifier) {
    Box(modifier.border(1.dp, Palette.Border, PanelShape).background(Palette.Surface, PanelShape).padding(10.dp)) {
        BarChart(
            categories = data.categories,
            series = listOf(
                ChartSeries("準備完了", data.ready, Palette.ChartReady),
                ChartSeries("未完", data.pending, Palette.ChartPending),
            ),
            stacked = true, height = 200.dp, title = title,
        )
    }
}

/** 件数のタイル。数字は本文の色で、左の帯だけが意味の色（色だけに頼らない）。 */
@Composable
private fun StatTile(label: String, value: String, accent: Color) {
    Row(
        Modifier.border(1.dp, Palette.Border, PanelShape).background(Palette.Surface, PanelShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(44.dp).background(accent))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(label, fontSize = 10.sp, color = Palette.SubText)
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        }
    }
}

@Composable
private fun ReadyMark(ready: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        Icon(
            if (ready) Icons.Default.CheckCircle else Icons.Default.ErrorOutline, contentDescription = null,
            Modifier.size(14.dp), tint = if (ready) Palette.ChartReady else Palette.ChartPending,
        )
        Spacer(Modifier.width(4.dp))
        Text(if (ready) "完了" else "未完", fontSize = 11.sp)
    }
}

@Composable
private fun Cell(text: String, bold: Boolean = false, color: Color? = null) = Text(
    text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
    fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
    color = color ?: Color.Unspecified, modifier = Modifier.padding(top = 3.dp, end = 6.dp),
)
