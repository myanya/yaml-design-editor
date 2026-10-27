package dev.toros.yde.agent.llm

import kotlinx.coroutines.delay
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
 * レート制限・一時エラーの自動リトライ（R18-04）もこの送信経路に一元化する。
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

    /**
     * JSONボディのPOST（全プロバイダ共通の送信経路）。
     * レート制限（429）・一時的なサーバーエラー（408 / 5xx）は待機して自動リトライする（R18-04）。
     * 待機は `retry-after` ヘッダを最優先し、なければ指数バックオフ。エージェント実行の
     * ツールループが途中ターンの 429 で丸ごと失敗しないよう、この層で吸収する。
     */
    suspend fun post(uri: String, headers: Map<String, String>, body: String, timeoutSeconds: Long = 300): Pair<Int, String> {
        val builder = HttpRequest.newBuilder(URI.create(uri))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.build()
        return retryLoop(send = {
            val response = client().sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            AiResponse(
                status = response.statusCode(),
                body = response.body(),
                retryAfter = response.headers().firstValue("retry-after").orElse(null),
            )
        })
    }

    // ---------------------------------------------------------------- リトライ（R18-04）

    /** 一時的な失敗の自動リトライ回数の上限（初回送信を除く）。 */
    internal const val MAX_RETRIES = 3

    /** 1回の待機時間の上限。`retry-after` が過大でも実行を長時間塞がない（キャンセルは delay が受ける）。 */
    internal const val MAX_WAIT_MILLIS = 60_000L

    /** 1回の応答（リトライ判定に必要な要素だけに絞った表現。テストの台本にも使う）。 */
    internal data class AiResponse(val status: Int, val body: String, val retryAfter: String?)

    /**
     * 送信→判定→待機のループ。`sleep` と `jitter` は注入可能で、テストは実時間を使わず
     * 待機時間の記録だけで検証する（AutomationTest と同じ流儀）。待機は delay ベースのため
     * 実行キャンセル（stopAll）で即座に中断される。
     */
    internal suspend fun retryLoop(
        send: suspend () -> AiResponse,
        sleep: suspend (Long) -> Unit = { delay(it) },
        jitter: () -> Double = { Math.random() },
    ): Pair<Int, String> {
        var attempt = 0
        while (true) {
            val res = send()
            if (attempt >= MAX_RETRIES || !isRetryable(res.status)) return res.status to res.body
            sleep(retryDelayMillis(res.retryAfter, attempt, jitter()))
            attempt++
        }
    }

    /** リトライすべき一時エラーか。4xx はタイムアウト（408）とレート制限（429）のみ、5xx は全て対象（529 過負荷を含む）。 */
    internal fun isRetryable(statusCode: Int): Boolean =
        statusCode == 408 || statusCode == 429 || statusCode >= 500

    /**
     * 待機時間（ミリ秒）。`retry-after` ヘッダ（秒。小数可）を最優先し、
     * なければ指数バックオフ（2s → 4s → 8s）。同時多発の再送が揃わないようジッタ（0〜1秒）を加える。
     */
    internal fun retryDelayMillis(retryAfter: String?, attempt: Int, jitter: Double = Math.random()): Long {
        val headerSeconds = retryAfter?.trim()?.toDoubleOrNull()?.takeIf { it >= 0 }
        val base = headerSeconds?.let { (it * 1_000).toLong() } ?: (2_000L shl attempt)
        return (base + (jitter * 1_000).toLong()).coerceIn(0L, MAX_WAIT_MILLIS)
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
