package dev.toros.yde.desktop.ui

import dev.toros.yde.app.ai.AiConnection
import dev.toros.yde.app.ai.AiHttp
import dev.toros.yde.app.ai.AiProvider
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

/**
 * AI接続設定（AI生成ダイアログとエージェント実行で共有）。
 * プロバイダ・モデル名・エンドポイント・プロキシ設定を端末の設定（Preferences）へ保存し、
 * APIキーとプロキシパスワードはセッション中のメモリのみに保持する。
 * エンドポイント・プロキシの実効値は `ai.AiConnection` / `ai.AiHttp` へ書き通す（R9-07・R9-08）。
 */
object AiSettings {
    private val preferences = Preferences.userRoot().node("yaml-design-editor/ai")

    private val providerState = mutableStateOf(
        runCatching { AiProvider.valueOf(preferences.get("provider", AiProvider.OPENAI.name)) }
            .getOrDefault(AiProvider.OPENAI)
    )
    var provider: AiProvider
        get() = providerState.value
        set(value) {
            providerState.value = value
            preferences.put("provider", value.name)
        }

    // APIキーはセッション中のみメモリに保持する（初期値は環境変数）。
    private val apiKeys = mutableStateMapOf<AiProvider, String>().apply {
        AiProvider.entries.forEach { put(it, System.getenv(it.envVar).orEmpty()) }
    }
    var apiKey: String
        get() = apiKeys[provider].orEmpty()
        set(value) { apiKeys[provider] = value }

    private val models = mutableStateMapOf<AiProvider, String>().apply {
        AiProvider.entries.forEach { put(it, preferences.get("model.${it.name}", it.defaultModel)) }
    }
    var model: String
        get() = models[provider].orEmpty()
        set(value) {
            models[provider] = value
            preferences.put("model.${provider.name}", value)
        }

    // ---- 実効プロバイダ指定でのアクセス ----
    // エージェント定義がプロバイダをピン留めする場合、キー・モデルは「選択中」ではなく
    // 「実際に呼び出すプロバイダ」のものを使う必要がある（取り違えると認証エラーになる）。

    fun apiKeyFor(target: AiProvider): String = apiKeys[target].orEmpty()

    fun setApiKeyFor(target: AiProvider, value: String) { apiKeys[target] = value }

    fun modelFor(target: AiProvider): String = models[target].orEmpty()

    fun setModelFor(target: AiProvider, value: String) {
        models[target] = value
        preferences.put("model.${target.name}", value)
    }

    // ---- 接続の詳細設定（R9-07・R9-08） ----
    // 永続化値の初期値は環境変数（ai 層の初期値）。保存済みの値があればそちらを実効値として ai 層へ反映する。

    private val openAiBaseUrlState = mutableStateOf(preferences.get("openAiBaseUrl", AiConnection.openAiBaseUrl))
    var openAiBaseUrl: String
        get() = openAiBaseUrlState.value
        set(value) {
            openAiBaseUrlState.value = value
            preferences.put("openAiBaseUrl", value)
            AiConnection.openAiBaseUrl = value
        }

    private val azureEndpointState = mutableStateOf(preferences.get("azureEndpoint", AiConnection.azureEndpoint))
    var azureEndpoint: String
        get() = azureEndpointState.value
        set(value) {
            azureEndpointState.value = value
            preferences.put("azureEndpoint", value)
            AiConnection.azureEndpoint = value
        }

    private val azureApiVersionState = mutableStateOf(preferences.get("azureApiVersion", AiConnection.azureApiVersion))
    var azureApiVersion: String
        get() = azureApiVersionState.value
        set(value) {
            azureApiVersionState.value = value
            preferences.put("azureApiVersion", value)
            AiConnection.azureApiVersion = value
        }

    private val proxyModeState = mutableStateOf(
        runCatching { AiHttp.ProxyMode.valueOf(preferences.get("proxyMode", AiHttp.ProxyMode.SYSTEM.name)) }
            .getOrDefault(AiHttp.ProxyMode.SYSTEM)
    )
    var proxyMode: AiHttp.ProxyMode
        get() = proxyModeState.value
        set(value) {
            proxyModeState.value = value
            preferences.put("proxyMode", value.name)
            AiHttp.proxyMode = value
        }

    private val proxyHostState = mutableStateOf(preferences.get("proxyHost", ""))
    var proxyHost: String
        get() = proxyHostState.value
        set(value) {
            proxyHostState.value = value
            preferences.put("proxyHost", value)
            AiHttp.proxyHost = value
        }

    private val proxyPortState = mutableStateOf(preferences.get("proxyPort", "8080"))
    var proxyPort: String
        get() = proxyPortState.value
        set(value) {
            proxyPortState.value = value
            preferences.put("proxyPort", value)
            value.toIntOrNull()?.let { AiHttp.proxyPort = it }
        }

    // プロキシのBasic認証はAPIキー同様にメモリのみ（ディスクへ保存しない）
    private val proxyUserState = mutableStateOf("")
    var proxyUser: String
        get() = proxyUserState.value
        set(value) {
            proxyUserState.value = value
            AiHttp.proxyUser = value
        }

    private val proxyPasswordState = mutableStateOf("")
    var proxyPassword: String
        get() = proxyPasswordState.value
        set(value) {
            proxyPasswordState.value = value
            AiHttp.proxyPassword = value
        }

    init {
        // 保存済みの設定を起動時に ai 層へ反映する（UIを開かずCLI以外の経路で使う場合も一致させる）
        AiConnection.openAiBaseUrl = openAiBaseUrlState.value
        AiConnection.azureEndpoint = azureEndpointState.value
        AiConnection.azureApiVersion = azureApiVersionState.value
        AiHttp.proxyMode = proxyModeState.value
        AiHttp.proxyHost = proxyHostState.value
        proxyPortState.value.toIntOrNull()?.let { AiHttp.proxyPort = it }
    }
}

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
