package dev.toros.yde.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.AppState
import dev.toros.yde.app.UiSpace

/**
 * UIシェル（R12-01）: アクティビティレールと台帳スペース。
 * レールが「編集・台帳・実行・設計」の4スペースを切り替える（ARCHITECTURE §4.24）。
 * 実行スペースは定義が空でも常時表示する（何が行える場所かを最初から見せる。UX-20 で R12-05 の
 * プロファイル判定を撤廃）。
 */

// ================================================================ アクティビティレール

@Composable
fun ActivityRail(
    state: AppState,
    inboxCount: Int,
    automationActive: Boolean,
) {
    Column(
        Modifier.width(64.dp).fillMaxHeight().background(Palette.PanelBg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(6.dp))
        RailItem(Icons.Default.Description, "編集", state.uiSpace == UiSpace.EDIT) { state.showSpace(UiSpace.EDIT) }
        RailItem(Icons.Default.TableRows, "台帳", state.uiSpace == UiSpace.LEDGER) { state.showSpace(UiSpace.LEDGER) }
        RailItem(
            Icons.Default.SmartToy, "実行", state.uiSpace == UiSpace.RUNS,
            badge = inboxCount, activeDot = automationActive,
        ) { state.showSpace(UiSpace.RUNS) }
        RailItem(Icons.Default.Build, "設計", state.uiSpace == UiSpace.DESIGN) { state.showSpace(UiSpace.DESIGN) }
    }
}

@Composable
private fun RailItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    badge: Int = 0,
    activeDot: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) Palette.Selected else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Icon(icon, contentDescription = label, Modifier.size(20.dp),
                tint = if (selected) Palette.Primary else Palette.SubText)
            if (badge > 0) {
                Text(
                    "$badge", fontSize = 9.sp, color = Palette.WarnOrange, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-4).dp),
                )
            }
            if (activeDot) {
                Text("●", fontSize = 7.sp, color = Palette.OkGreen,
                    modifier = Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 2.dp))
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.sp,
            color = if (selected) Palette.Primary else Palette.SubText,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

// ================================================================ 台帳スペース（R12-04: 一覧系の空間統合）

/** workspaceView（1〜4）とタブ位置の対応。一覧 → ビュー → レンズ → エンティティ の順に並べる。 */
private val ledgerTabs = listOf(
    1 to "一覧",
    4 to "ビュー",
    2 to "レンズ",
    3 to "エンティティ",
)

@Composable
fun LedgerSpace(state: AppState) {
    val tabIndex = ledgerTabs.indexOfFirst { it.first == state.workspaceView }.coerceAtLeast(0)
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tabIndex, containerColor = Palette.Surface, contentColor = Palette.Primary) {
            ledgerTabs.forEachIndexed { i, (view, label) ->
                Tab(tabIndex == i, onClick = {
                    when (view) {
                        3 -> state.openCrossView()
                        4 -> state.openViewGrid()
                        else -> state.showWorkspaceView(view)
                    }
                }, text = { Text(label, fontSize = 13.sp) })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.workspaceView) {
                2 -> GraphLensView(state)
                3 -> IdCrossView(state)
                4 -> ViewGridView(state)
                else -> WorkspaceTableView(state)
            }
        }
    }
}
