package dev.toros.yde.desktop.ui.run

import dev.toros.yde.desktop.AiSettings

import dev.toros.yde.agent.llm.AiConnection
import dev.toros.yde.agent.llm.AiHttp
import dev.toros.yde.agent.llm.AiProvider
import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.prefs.Preferences
import dev.toros.yde.desktop.ui.common.Palette



// ================================================================ 接続の詳細設定UI（生成ダイアログ・エージェント実行で共有）

/** モデル入力欄のラベル（Azure はデプロイメント名を指定する）。 */
fun modelFieldLabel(provider: AiProvider): String =
    if (provider == AiProvider.AZURE) "デプロイメント名" else "モデル"

/**
 * エンドポイント（Azure・OpenAI互換ベースURL）とプロキシの設定欄。
 * 折りたたみ式で、必要なプロバイダのときだけエンドポイント欄を出す。
 */
@Composable
fun AiConnectionSettingsSection(provider: AiProvider) {
    var open by remember { mutableStateOf(provider == AiProvider.AZURE && AiSettings.azureEndpoint.isBlank()) }
    TextButton(onClick = { open = !open }) {
        Text(
            (if (open) "▼" else "▶") + " 接続の詳細設定（エンドポイント・プロキシ）",
            fontSize = 11.sp, color = Palette.SubText,
        )
    }
    if (!open) return
    Column(Modifier.fillMaxWidth().padding(start = 8.dp)) {
        when (provider) {
            AiProvider.AZURE -> {
                OutlinedTextField(
                    value = AiSettings.azureEndpoint,
                    onValueChange = { AiSettings.azureEndpoint = it },
                    label = { Text("Azure エンドポイント（例: https://<リソース名>.openai.azure.com）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = AiSettings.azureApiVersion,
                    onValueChange = { AiSettings.azureApiVersion = it },
                    label = { Text("APIバージョン（空なら v1 サーフェス）") },
                    singleLine = true,
                    modifier = Modifier.width(280.dp),
                )
            }
            AiProvider.OPENAI -> {
                OutlinedTextField(
                    value = AiSettings.openAiBaseUrl,
                    onValueChange = { AiSettings.openAiBaseUrl = it },
                    label = { Text("ベースURL上書き（空なら公式。ollama / vLLM 等の互換エンドポイント）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AiProvider.ANTHROPIC -> Unit
        }
        Spacer(Modifier.height(10.dp))
        Text("プロキシ", fontSize = 12.sp, color = Palette.SubText)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AiHttp.ProxyMode.entries.forEach { mode ->
                RadioButton(selected = AiSettings.proxyMode == mode, onClick = { AiSettings.proxyMode = mode })
                Text(mode.label, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
            }
        }
        if (AiSettings.proxyMode == AiHttp.ProxyMode.MANUAL) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = AiSettings.proxyHost,
                    onValueChange = { AiSettings.proxyHost = it },
                    label = { Text("ホスト") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = AiSettings.proxyPort,
                    onValueChange = { AiSettings.proxyPort = it },
                    label = { Text("ポート") },
                    singleLine = true,
                    modifier = Modifier.width(120.dp),
                )
            }
        }
        if (AiSettings.proxyMode != AiHttp.ProxyMode.DIRECT) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = AiSettings.proxyUser,
                    onValueChange = { AiSettings.proxyUser = it },
                    label = { Text("プロキシ認証ID（不要なら空）") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = AiSettings.proxyPassword,
                    onValueChange = { AiSettings.proxyPassword = it },
                    label = { Text("パスワード（保存しません）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
