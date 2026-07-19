package ui

import ai.AiProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import java.util.prefs.Preferences

/**
 * AI接続設定（AI生成ダイアログとエージェント実行で共有）。
 * プロバイダとモデル名だけを端末の設定（Preferences）へ保存し、APIキーはセッション中のメモリのみに保持する。
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
}
