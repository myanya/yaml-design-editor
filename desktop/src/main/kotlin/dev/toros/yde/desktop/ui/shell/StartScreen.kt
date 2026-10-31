package dev.toros.yde.desktop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.key.*
import dev.toros.yde.app.core.BuildInfo
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.ui.common.FilePickers
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PanelShape
import dev.toros.yde.desktop.ui.common.rememberDirectoryChooser

/** ワークスペース未選択時のスタート画面（R10-05）。履歴からの選択で起動引数なしの切替を完結させる。 */
@Composable
fun StartScreen(state: AppState, openSettings: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("YAML Design Editor", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(BuildInfo.display, fontSize = 11.sp, color = Palette.SubText)
        TextButton(onClick = openSettings) { Text("設定 → 接続と常駐", fontSize = 12.sp) }
        Spacer(Modifier.height(4.dp))
        Text(
            "スキーマ駆動のYAML編集・検証・エージェント実行環境",
            fontSize = 12.sp, color = Palette.SubText,
        )
        Spacer(Modifier.height(20.dp))
        val chooseWorkspace = rememberDirectoryChooser(
            FilePickers.Purpose.WORKSPACE, "ワークスペース（ディレクトリ）を選択",
            fallback = state.recentWorkspaces.firstOrNull()?.parent,
            onError = { state.status = it },
        ) { state.openWorkspace(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 新しいワークスペースを作る（初期化ウィザード。ui/shell/InitWizard.kt）: 1 つのフォルダに 1 度だけ
            Button(onClick = { state.startInitWizard() }) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("新しいワークスペースを作る…", fontSize = 13.sp)
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = chooseWorkspace) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("ワークスペースを開く…", fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "初めてなら「新しいワークスペースを作る…」から、テンプレートか自分の項目で始められます",
            fontSize = 11.sp, color = Palette.SubText,
        )
        if (state.recentWorkspaces.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("最近開いたワークスペース", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.SubText)
            Spacer(Modifier.height(4.dp))
            Column(
                Modifier.width(560.dp).background(Palette.PanelBg, PanelShape)
                    .border(1.dp, Palette.Border, PanelShape).padding(vertical = 4.dp),
            ) {
                state.recentWorkspaces.forEach { ws ->
                    Row(
                        Modifier.fillMaxWidth().clickable { state.openWorkspace(ws) }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, Modifier.size(15.dp), tint = Palette.Folder)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(ws.fileName?.toString() ?: ws.toString(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(ws.toString(), fontSize = 10.sp, color = Palette.SubText, maxLines = 1)
                        }
                    }
                }
            }
        } else {
            Spacer(Modifier.height(16.dp))
            Text(
                "開いたワークスペースはここに履歴として表示されます",
                fontSize = 11.sp, color = Palette.SubText,
            )
        }
    }
}
