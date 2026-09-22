package dev.toros.yde.app.ai

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AI送信経路のリトライ機構（R18-04）の単体テスト。
 * 実HTTPは使わず、送信は台本（AiResponse の列）、待機は記録用ラムダを注入して検証する。
 */
class HttpTest {

    // ---------------------------------------------------------------- リトライ判定

    @Test
    fun `リトライ対象は 408・429・5xx`() {
        assertTrue(AiHttp.isRetryable(408))
        assertTrue(AiHttp.isRetryable(429))
        assertTrue(AiHttp.isRetryable(500))
        assertTrue(AiHttp.isRetryable(503))
        assertTrue(AiHttp.isRetryable(529))
        assertFalse(AiHttp.isRetryable(200))
        assertFalse(AiHttp.isRetryable(400))
        assertFalse(AiHttp.isRetryable(401))
        assertFalse(AiHttp.isRetryable(404))
    }

    // ---------------------------------------------------------------- 待機時間

    @Test
    fun `retry-after ヘッダの秒数を待機時間に使う`() {
        assertEquals(20_000L, AiHttp.retryDelayMillis("20", attempt = 0, jitter = 0.0))
        assertEquals(1_500L, AiHttp.retryDelayMillis("1.5", attempt = 0, jitter = 0.0))
        assertEquals(3_000L, AiHttp.retryDelayMillis(" 3 ", attempt = 2, jitter = 0.0))
    }

    @Test
    fun `retry-after が不正または欠落なら指数バックオフ`() {
        assertEquals(2_000L, AiHttp.retryDelayMillis(null, attempt = 0, jitter = 0.0))
        assertEquals(4_000L, AiHttp.retryDelayMillis("abc", attempt = 1, jitter = 0.0))
        assertEquals(8_000L, AiHttp.retryDelayMillis("-5", attempt = 2, jitter = 0.0))
    }

    @Test
    fun `ジッタが待機時間に加算される`() {
        assertEquals(2_500L, AiHttp.retryDelayMillis(null, attempt = 0, jitter = 0.5))
    }

    @Test
    fun `待機時間は上限で打ち切る`() {
        // 日次上限などで retry-after が極端に長くても、実行を塞がない
        assertEquals(AiHttp.MAX_WAIT_MILLIS, AiHttp.retryDelayMillis("86400", attempt = 0, jitter = 0.0))
    }

    // ---------------------------------------------------------------- 送信ループ

    @Test
    fun `429 が続いた後に成功したら成功応答を返す`() = runBlocking {
        val script = ArrayDeque(
            listOf(
                AiHttp.AiResponse(429, "limit", retryAfter = "1"),
                AiHttp.AiResponse(429, "limit", retryAfter = null),
                AiHttp.AiResponse(200, "ok", retryAfter = null),
            )
        )
        val waits = mutableListOf<Long>()
        val result = AiHttp.retryLoop(
            send = { script.removeFirst() },
            sleep = { waits += it },
            jitter = { 0.0 },
        )
        assertEquals(200 to "ok", result)
        // 1回目は retry-after=1秒、2回目はヘッダなしで指数バックオフ（attempt=1 → 4秒）
        assertEquals(listOf(1_000L, 4_000L), waits)
    }

    @Test
    fun `リトライ上限を超えたら最後の応答をそのまま返す`() = runBlocking {
        var sends = 0
        val result = AiHttp.retryLoop(
            send = { sends++; AiHttp.AiResponse(429, "limit", retryAfter = null) },
            sleep = { },
            jitter = { 0.0 },
        )
        assertEquals(429 to "limit", result)
        assertEquals(1 + AiHttp.MAX_RETRIES, sends)  // 初回＋リトライ3回
    }

    @Test
    fun `リトライ対象外のエラーは待機せず即座に返す`() = runBlocking {
        var sends = 0
        val result = AiHttp.retryLoop(
            send = { sends++; AiHttp.AiResponse(400, "bad request", retryAfter = null) },
            sleep = { fail("リトライ対象外では待機しない") },
        )
        assertEquals(400 to "bad request", result)
        assertEquals(1, sends)
    }

    @Test
    fun `成功応答は一度も待機せず返す`() = runBlocking {
        val result = AiHttp.retryLoop(
            send = { AiHttp.AiResponse(200, "ok", retryAfter = null) },
            sleep = { fail("成功時は待機しない") },
        )
        assertEquals(200 to "ok", result)
    }
}
