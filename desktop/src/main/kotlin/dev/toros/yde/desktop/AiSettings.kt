package dev.toros.yde.desktop

import androidx.compose.runtime.*
import dev.toros.yde.agent.llm.AiConnection
import dev.toros.yde.agent.llm.AiHttp
import dev.toros.yde.agent.llm.AiProvider
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
