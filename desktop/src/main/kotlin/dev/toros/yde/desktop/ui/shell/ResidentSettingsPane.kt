package dev.toros.yde.desktop.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.desktop.lifecycle.PresenceStatus
import dev.toros.yde.desktop.lifecycle.ResidentSettings
import dev.toros.yde.desktop.ui.common.Palette

@Composable
internal fun ResidentSettingsPane(
    settings: ResidentSettings, actual: PresenceStatus?, enabled: Boolean,
    onChange: (ResidentSettings) -> Unit, onHide: () -> Unit,
) {
    val effective = if (actual?.temporaryTray == true) settings.copy(trayEnabled = true) else settings
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("トレイと画面の表示", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(effective.trayEnabled, { onChange(ResidentSettings(it, effective.startHidden && it)) }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "トレイ常駐を有効にする" })
            Text("トレイに常駐する（×で画面を隠す）", fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(effective.startHidden, { onChange(effective.copy(startHidden = it)) }, enabled = enabled && effective.trayEnabled,
                modifier = Modifier.semantics { contentDescription = "画面を表示せずに起動する" })
            Text("次回から画面を表示せずに起動する", fontSize = 13.sp)
        }
        Text("画面を隠しても未保存編集・監視・実行・承認待ちは残ります。常駐の設定だけでは API・MCP・Web は有効になりません。", fontSize = 12.sp)
        Text("トレイから画面・受信箱・設定を開けます。アプリを止めるときは「完全終了」を選んでください。", fontSize = 12.sp, color = Palette.SubText)
        actual?.let {
            if (it.temporaryTray) Text("起動オプションにより今回だけ常駐しています。保存済みの設定は変更していません。", fontSize = 12.sp)
            Text(if (it.trayActive) "トレイ: 常駐中" else "トレイ: 停止", fontSize = 13.sp)
            it.error?.let { message -> Text(message, color = Palette.ErrorRed, fontSize = 12.sp) }
        }
        OutlinedButton(onClick = onHide, enabled = enabled && actual?.trayActive == true && !actual.exiting) { Text("画面を隠す") }
    }
}

@Composable
internal fun ResidentNotice(message: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f), color = Palette.ErrorRed, fontSize = 13.sp)
        TextButton(onClick = onDismiss) { Text("閉じる") }
    }
}
