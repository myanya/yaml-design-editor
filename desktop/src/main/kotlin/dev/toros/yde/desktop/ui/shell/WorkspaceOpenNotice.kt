package dev.toros.yde.desktop.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.desktop.AppState
import java.nio.file.Path

/** スタート画面と切替後の元画面に残す通知。再試行も各画面の未保存確認を通す。 */
@Composable
internal fun WorkspaceOpenNotice(state: AppState, retry: (Path) -> Unit) {
    val failure = state.workspaceOpenFailure ?: return
    val workspaceStatus by state.host.status.collectAsState()
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                Text("ワークスペースを開けませんでした", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    failure.path?.let { Text("対象: $it", fontSize = 12.sp) }
                    Text(failure.message, fontSize = 13.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                failure.path?.let { path ->
                    OutlinedButton(onClick = { retry(path) }, enabled = workspaceStatus.busy == null && !workspaceStatus.closed) {
                        Text("再試行")
                    }
                }
                TextButton(onClick = state::dismissWorkspaceOpenFailure) { Text("閉じる") }
            }
        }
    }
}
