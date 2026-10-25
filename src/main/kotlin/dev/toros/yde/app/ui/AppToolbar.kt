package dev.toros.yde.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.AppState
import dev.toros.yde.app.Help
import dev.toros.yde.app.UiSpace
import java.nio.file.Path

/** 低頻度の操作はメニューに集め、移動・検索・左右の領域操作を常設する。 */
@Composable
fun AppToolbar(
    state: AppState, leftOpen: Boolean, rightOpen: Boolean, darkMode: Boolean,
    toggleDarkMode: () -> Unit, openCommands: () -> Unit, openWorkspaceDialog: () -> Unit,
    switchWorkspace: (Path) -> Unit, toggleReadOnly: () -> Unit,
    toggleLeft: () -> Unit, toggleRight: () -> Unit,
) {
    var menu by remember { mutableStateOf<String?>(null) }
    var recentOpen by remember { mutableStateOf(false) }
    val paneAvailable = state.uiSpace == UiSpace.EDIT || state.uiSpace == UiSpace.LEDGER
    Column(Modifier.fillMaxWidth().background(Palette.PanelBg)) {
        Row(Modifier.height(30.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("ファイル", "編集", "表示", "ヘルプ").forEach { label ->
                Box(Modifier.height(30.dp)) {
                    TextButton(shape = PanelShape, onClick = { menu = label }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                        Text(label, fontSize = 12.sp, color = Palette.Text)
                    }
                    DropdownMenu(menu == label, { menu = null }) {
                        @Composable fun action(text: String, enabled: Boolean = true, run: () -> Unit) {
                            DropdownMenuItem(text = { Text(text, fontSize = 13.sp) }, enabled = enabled,
                                onClick = { menu = null; run() })
                        }
                        when (label) {
                            "ファイル" -> {
                                action("ワークスペースを開く…", run = openWorkspaceDialog)
                                state.recentWorkspaces.take(5).forEach { path ->
                                    UiTooltip(path.toString()) {
                                        DropdownMenuItem(text = {
                                            Column {
                                                Text("最近: ${path.fileName ?: path}", fontSize = 13.sp,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(path.toString(), fontSize = 11.sp, color = Palette.SubText,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }, onClick = { menu = null; switchWorkspace(path) })
                                    }
                                }
                                HorizontalDivider(color = Palette.Border)
                                action(if (state.uiSpace == UiSpace.DASHBOARD) "すべて保存    Ctrl+S" else "保存    Ctrl+S",
                                    state.openDoc != null || state.uiSpace == UiSpace.DASHBOARD, state::saveCurrent)
                                action("すべて保存    Ctrl+Shift+S", state.hasDirtyDocs(), state::saveAll)
                                action("ワークスペースを再読込", run = state::reload)
                                action("編集と履歴を破棄して開き直す…", !state.fileOperationBusy, state::requestDiscardAndReload)
                            }
                            "編集" -> {
                                action("元に戻す    Ctrl+Z", state.canUndo(), state::undoFromUi)
                                action("やり直す    Ctrl+Shift+Z", state.canRedo(), state::redoFromUi)
                                HorizontalDivider(color = Palette.Border)
                                action("検索・コマンド    Ctrl+K", run = openCommands)
                            }
                            "表示" -> {
                                action("${if (leftOpen) "✓ " else ""}ファイルペイン", state.uiSpace == UiSpace.EDIT, toggleLeft)
                                action("${if (rightOpen) "✓ " else ""}右ペイン", paneAvailable, toggleRight)
                                HorizontalDivider(color = Palette.Border)
                                action("${if (state.readOnlyMode) "✓ " else ""}閲覧専用モード", run = toggleReadOnly)
                                HorizontalDivider(color = Palette.Border)
                                action("${if (!darkMode) "✓ " else ""}ライトモード") { if (darkMode) toggleDarkMode() }
                                action("${if (darkMode) "✓ " else ""}ダークモード") { if (!darkMode) toggleDarkMode() }
                            }
                            "ヘルプ" -> action("利用者マニュアル") { state.openHelp(Help.INDEX) }
                        }
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 850.dp
            Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PaneToggle("ファイル", left = true, open = leftOpen, enabled = state.uiSpace == UiSpace.EDIT, onClick = toggleLeft)
                UiTooltip("戻る (Alt+←)") {
                    IconButton(onClick = state::goBack, enabled = state.canGoBack, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る", Modifier.size(18.dp))
                    }
                }
                UiTooltip("進む (Alt+→)") {
                    IconButton(onClick = state::goForward, enabled = state.canGoForward, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "進む", Modifier.size(18.dp))
                    }
                }
                Box {
                    UiTooltip("最近開いたファイル") {
                        IconButton(onClick = { recentOpen = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.History, "最近開いたファイル", Modifier.size(18.dp))
                        }
                    }
                    DropdownMenu(recentOpen, { recentOpen = false }) {
                        if (state.recentFiles.isEmpty()) DropdownMenuItem(text = { Text("履歴なし") }, onClick = {}, enabled = false)
                        state.recentFiles.forEach { file ->
                            DropdownMenuItem(text = { Text(state.relPath(file), fontSize = 12.sp) },
                                onClick = { recentOpen = false; state.openFile(file) })
                        }
                    }
                }
                if (!compact) Text(state.workspace?.fileName?.toString().orEmpty(), modifier = Modifier.weight(1f),
                    fontSize = 12.sp, color = Palette.SubText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = openCommands, shape = PanelShape,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp), modifier = Modifier.height(30.dp)) {
                    Icon(Icons.Default.Search, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (compact) "検索  Ctrl+K" else "検索・コマンド    Ctrl+K", fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                PaneToggle(if (state.uiSpace == UiSpace.LEDGER && state.workspaceView == 2) "レンズ" else "検証・関係",
                    left = false, open = rightOpen, enabled = paneAvailable, onClick = toggleRight)
            }
        }
    }
}

@Composable
private fun PaneToggle(label: String, left: Boolean, open: Boolean, enabled: Boolean, onClick: () -> Unit) {
    UiTooltip("$label ペインを${if (open) "閉じる" else "開く"}") {
        TextButton(onClick = onClick, enabled = enabled, shape = PanelShape,
            colors = ButtonDefaults.textButtonColors(containerColor = if (open && enabled) Palette.Selected else Palette.PanelBg),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.height(30.dp).semantics { stateDescription = if (open) "表示中" else "非表示" }) {
            Icon(Icons.AutoMirrored.Filled.ViewSidebar, null, Modifier.size(18.dp).graphicsLayer { scaleX = if (left) -1f else 1f })
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 12.sp)
        }
    }
}

@Composable
fun PaneHeader(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(36.dp).background(Palette.PanelBg).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        UiTooltip("$title ペインを閉じる") {
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, "$title ペインを閉じる", Modifier.size(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UiTooltip(text: String, content: @Composable () -> Unit) {
    TooltipArea(tooltip = {
        Surface(shape = PanelShape, color = Palette.Surface, shadowElevation = 3.dp) {
            Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 11.sp)
        }
    }, content = content)
}
