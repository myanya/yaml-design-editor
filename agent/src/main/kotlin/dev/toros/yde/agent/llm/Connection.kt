package dev.toros.yde.agent.llm

/**
 * OpenAI互換エンドポイントの接続先解決（R9-07）。
 * OpenAI クライアント（生成系 `OpenAiGenerator`・エージェント系 `OpenAiToolSession`）を
 * 「ベースURL・認証ヘッダ形式・APIバージョン」でパラメタ化し、Azure OpenAI / AI Foundry を
 * プリセットとして扱う。ollama / vLLM 等のOpenAI互換エンドポイントもベースURL上書きで利用できる。
 * エンドポイント・APIバージョンは設定UI側で永続化される（APIキーはメモリのみ）。
 */
object AiConnection {

    /** OpenAI のベースURL上書き（空なら公式 https://api.openai.com/v1）。ollama / vLLM 等の互換エンドポイントもここで指定する。 */
    @Volatile var openAiBaseUrl: String = System.getenv("OPENAI_BASE_URL").orEmpty()

    /** Azure のエンドポイント（例: https://myresource.openai.azure.com）。 */
    @Volatile var azureEndpoint: String = System.getenv("AZURE_OPENAI_ENDPOINT").orEmpty()

    /** Azure の api-version クエリ（空なら付与しない＝v1 サーフェス。例: preview）。 */
    @Volatile var azureApiVersion: String = System.getenv("AZURE_OPENAI_API_VERSION").orEmpty()

    /** 解決済みの接続先（URI＋認証ヘッダ）。 */
    data class Target(val uri: String, val headers: Map<String, String>)

    /** Responses API の接続先を現在の設定から解決する。 */
    fun responsesTarget(provider: AiProvider, apiKey: String): Target =
        resolveResponsesTarget(provider, apiKey, openAiBaseUrl, azureEndpoint, azureApiVersion)
}

/** 純粋関数: プロバイダと設定から Responses API の接続先を組み立てる（単体テスト対象）。 */
internal fun resolveResponsesTarget(
    provider: AiProvider,
    apiKey: String,
    openAiBaseUrl: String,
    azureEndpoint: String,
    azureApiVersion: String,
): AiConnection.Target = when (provider) {
    AiProvider.OPENAI -> {
        val base = openAiBaseUrl.trim().ifBlank { "https://api.openai.com/v1" }.trimEnd('/')
        AiConnection.Target(
            uri = "$base/responses",
            headers = mapOf("Authorization" to "Bearer ${apiKey.trim()}"),
        )
    }
    AiProvider.AZURE -> {
        require(azureEndpoint.isNotBlank()) { "Azure エンドポイントを設定してください（例: https://<リソース名>.openai.azure.com）" }
        val base = azureEndpoint.trim().trimEnd('/')
        val root = if (base.endsWith("/openai/v1")) base else "$base/openai/v1"
        val query = azureApiVersion.trim().takeIf { it.isNotBlank() }?.let { "?api-version=$it" }.orEmpty()
        AiConnection.Target(
            uri = "$root/responses$query",
            headers = mapOf("api-key" to apiKey.trim()),
        )
    }
    AiProvider.ANTHROPIC -> error("Anthropic は OpenAI 互換エンドポイントではありません")
}
