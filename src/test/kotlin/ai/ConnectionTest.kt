package ai

import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** OpenAI互換エンドポイントの接続先解決（R9-07）とプロキシ設定（R9-08）の単体テスト。 */
class ConnectionTest {

    @Test
    fun `OpenAI 公式の接続先は Bearer 認証`() {
        // ベースURL未指定なら公式エンドポイント
        val target = resolveResponsesTarget(AiProvider.OPENAI, " key ", "", "", "")
        assertEquals("https://api.openai.com/v1/responses", target.uri)
        assertEquals("Bearer key", target.headers["Authorization"])
    }

    @Test
    fun `ベースURL上書きで互換エンドポイントへ向く`() {
        // ollama / vLLM 等のOpenAI互換エンドポイント。末尾スラッシュは吸収する
        val target = resolveResponsesTarget(AiProvider.OPENAI, "k", "http://localhost:11434/v1/", "", "")
        assertEquals("http://localhost:11434/v1/responses", target.uri)
    }

    @Test
    fun `Azure はエンドポイント必須`() {
        val e = assertFailsWith<IllegalArgumentException> {
            resolveResponsesTarget(AiProvider.AZURE, "k", "", "", "")
        }
        assertTrue("エンドポイント" in e.message.orEmpty())
    }

    @Test
    fun `Azure の接続先は api-key ヘッダと openai-v1 パス`() {
        val target = resolveResponsesTarget(AiProvider.AZURE, "azkey", "", "https://myres.openai.azure.com/", "")
        assertEquals("https://myres.openai.azure.com/openai/v1/responses", target.uri)
        assertEquals("azkey", target.headers["api-key"])
        assertNull(target.headers["Authorization"])
    }

    @Test
    fun `Azure の api-version はクエリに付く`() {
        val target = resolveResponsesTarget(AiProvider.AZURE, "k", "", "https://myres.openai.azure.com", "preview")
        assertEquals("https://myres.openai.azure.com/openai/v1/responses?api-version=preview", target.uri)
    }

    @Test
    fun `Azure エンドポイントに openai-v1 が含まれていても二重にしない`() {
        val target = resolveResponsesTarget(AiProvider.AZURE, "k", "", "https://myres.openai.azure.com/openai/v1", "")
        assertEquals("https://myres.openai.azure.com/openai/v1/responses", target.uri)
    }

    @Test
    fun `プロキシ SYSTEM は既定セレクタ（null）`() {
        assertNull(proxySelectorFor(AiHttp.ProxyMode.SYSTEM, "", 0))
    }

    @Test
    fun `プロキシ DIRECT は NO_PROXY`() {
        assertEquals(HttpClient.Builder.NO_PROXY, proxySelectorFor(AiHttp.ProxyMode.DIRECT, "", 0))
    }

    @Test
    fun `プロキシ MANUAL は指定ホスト・ポートへ向く`() {
        val selector = proxySelectorFor(AiHttp.ProxyMode.MANUAL, "proxy.example.com", 3128)!!
        val proxies = selector.select(URI.create("https://api.openai.com/v1/responses"))
        val address = proxies.single().address() as InetSocketAddress
        assertEquals("proxy.example.com", address.hostString)
        assertEquals(3128, address.port)
    }

    @Test
    fun `プロキシ MANUAL はホスト未入力を拒否`() {
        val e = assertFailsWith<IllegalArgumentException> { proxySelectorFor(AiHttp.ProxyMode.MANUAL, " ", 8080) }
        assertTrue("ホスト名" in e.message.orEmpty())
    }
}
