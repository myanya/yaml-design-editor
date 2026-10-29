package dev.toros.yde.desktop.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.ui.common.Palette

/** 通常の再読込と区別し、失う編集・下書き・履歴を確定前に示す。 */
@Composable
internal fun ReloadConfirmationDialog(state: AppState) {
    val pending = state.pendingReload ?: return
    AlertDialog(
        onDismissRequest = state::cancelDiscardAndReload,
        title = { Text("編集と履歴を破棄して開き直しますか？") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text("未保存の編集・ソースの下書き ${pending.files.size} 件と、Undo/Redo 履歴 ${pending.historyCount} 件を失います。")
                pending.files.forEach { Text(it) }
                Text("ディスクから読み直します。保存済みの内容やファイルの配置は変更しません。この破棄は取り消せません。")
            }
        },
        confirmButton = { TextButton(onClick = state::confirmDiscardAndReload) { Text("破棄して開き直す", color = Palette.ErrorRed) } },
        dismissButton = { TextButton(onClick = state::cancelDiscardAndReload) { Text("キャンセル") } },
    )
}
