package ai

import kotlinx.coroutines.future.await
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * AI呼び出し用 HttpClient の単一ファクトリ（R9-08）。
 * 外部通信はAIプロバイダ呼び出しに限定されているため、プロキシ設定はこのファクトリに閉じる。
 * プロキシのBasic認証（id/password）はAPIキーと同様にメモリのみで保持し、ディスクへ保存しない。
 */
object AiHttp {

    /** プロキシの利用方法。既定はシステム設定の尊重。 */
    enum class ProxyMode(val label: String) {
        SYSTEM("システム設定に従う"),
        DIRECT("プロキシを使わない"),
        MANUAL("手動設定"),
    }

    @Volatile var proxyMode: ProxyMode = ProxyMode.SYSTEM
    @Volatile var proxyHost: String = ""
    @Volatile var proxyPort: Int = 8080
    // Basic認証の資格情報はメモリのみ（設定UIから投入。永続化しない）
    @Volatile var proxyUser: String = ""
    @Volatile var proxyPassword: String = ""

    init {
        // JDKは既定でHTTPSトンネル（CONNECT）のBasic認証を無効化しているため、AI呼び出しの前に許可する。
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "")
        // システム設定の尊重を既定とする（DefaultProxySelector が初回ロード時に読むため、クライアント生成前に設定する）
        if (System.getProperty("java.net.useSystemProxies") == null) {
            System.setProperty("java.net.useSystemProxies", "true")
        }
    }

    private var cached: HttpClient? = null
    private var cachedConfig: List<Any> = emptyList()

    /** 現在のプロキシ設定を反映した HttpClient。設定が変わった場合のみ作り直す。 */
    @Synchronized
    fun client(): HttpClient {
        val config = listOf<Any>(proxyMode, proxyHost, proxyPort, proxyUser)
        cached?.takeIf { cachedConfig == config }?.let { return it }
        val builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
        proxySelectorFor(proxyMode, proxyHost, proxyPort)?.let(builder::proxy)
        if (proxyUser.isNotBlank()) {
            builder.authenticator(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? =
                    if (requestorType == RequestorType.PROXY) PasswordAuthentication(proxyUser, proxyPassword.toCharArray())
                    else null
            })
        }
        return builder.build().also { cached = it; cachedConfig = config }
    }

    /** JSONボディのPOST（全プロバイダ共通の送信経路）。 */
    suspend fun post(uri: String, headers: Map<String, String>, body: String, timeoutSeconds: Long = 300): Pair<Int, String> {
        val builder = HttpRequest.newBuilder(URI.create(uri))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (k, v) -> builder.header(k, v) }
        val response = client().sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).await()
        return response.statusCode() to response.body()
    }
}

/**
 * プロキシ設定から ProxySelector を決める（null = HttpClient 既定＝システム設定の尊重）。
 * MANUAL でホストが未入力の場合は接続に進ませず設定不備を伝える。
 */
internal fun proxySelectorFor(mode: AiHttp.ProxyMode, host: String, port: Int): ProxySelector? = when (mode) {
    AiHttp.ProxyMode.SYSTEM -> null
    AiHttp.ProxyMode.DIRECT -> HttpClient.Builder.NO_PROXY
    AiHttp.ProxyMode.MANUAL -> {
        require(host.isNotBlank()) { "プロキシのホスト名を入力してください" }
        require(port in 1..65535) { "プロキシのポート番号が不正です: $port" }
        ProxySelector.of(InetSocketAddress.createUnresolved(host.trim(), port))
    }
}
