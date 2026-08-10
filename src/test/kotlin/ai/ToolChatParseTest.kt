package ai

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolChatParseTest {

    // ---------------------------------------------------------------- Anthropic

    private fun anthropicSession() = AnthropicToolSession("key", "model", "system", emptyList())

    @Test
    fun `anthropic tool_use blocks become tool calls`() {
        val body = """
            {"content":[
                {"type":"text","text":"検索します"},
                {"type":"tool_use","id":"toolu_1","name":"search","input":{"text":"ログイン"}}
             ],
             "stop_reason":"tool_use",
             "usage":{"input_tokens":120,"output_tokens":45}}
        """.trimIndent()
        val turn = anthropicSession().parseAnthropicToolResponse(200, body)
        assertEquals("検索します", turn.assistantText)
        val call = turn.toolCalls.single()
        assertEquals("toolu_1", call.id)
        assertEquals("search", call.name)
        assertEquals("ログイン", call.arguments["text"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals(120, turn.usage.inputTokens)
        assertEquals("tool_use", turn.stopReason)
    }

    @Test
    fun `anthropic end_turn without tools has no calls`() {
        val body = """{"content":[{"type":"text","text":"完了"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":2}}"""
        val turn = anthropicSession().parseAnthropicToolResponse(200, body)
        assertTrue(turn.toolCalls.isEmpty())
        assertEquals("完了", turn.assistantText)
    }

    @Test
    fun `anthropic error surfaces api message with provider prefix`() {
        val body = """{"type":"error","error":{"type":"invalid_request_error","message":"bad model"}}"""
        val e = assertFailsWith<IllegalStateException> { anthropicSession().parseAnthropicToolResponse(400, body) }
        assertEquals("Anthropic API: bad model", e.message)
    }

    @Test
    fun `anthropic request marks cache breakpoints on system, last tool and latest message`() {
        val session = AnthropicToolSession(
            "key", "model", "SYS",
            listOf(
                ToolSpec("first", "d1", kotlinx.serialization.json.buildJsonObject {}),
                ToolSpec("second", "d2", kotlinx.serialization.json.buildJsonObject {}),
            ),
        )
        session.addUserText("hello")
        val body = session.buildRequestBody()
        // system は cache_control 付きのブロック配列
        val sys = body["system"]!!.jsonArray.single().jsonObject
        assertEquals("SYS", sys["text"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", sys["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        // ツール定義は末尾だけに切れ目
        val tools = body["tools"]!!.jsonArray
        assertNull(tools[0].jsonObject["cache_control"])
        assertEquals("ephemeral", tools[1].jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        // 最新（唯一）のユーザーメッセージはブロック化され末尾に切れ目
        val lastContent = body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
        assertEquals("ephemeral", lastContent.last().jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        // ターンが進むと切れ目は最新メッセージへ移動し、過去のメッセージは素のまま
        session.parseAnthropicToolResponse(
            200,
            """{"content":[{"type":"tool_use","id":"toolu_1","name":"first","input":{}}],"stop_reason":"tool_use","usage":{"input_tokens":1,"output_tokens":1}}""",
        )
        session.addToolResults(listOf(ToolResult("toolu_1", "ok")))
        val body2 = session.buildRequestBody()
        val messages = body2["messages"]!!.jsonArray
        assertEquals(3, messages.size)
        assertTrue(messages[0].jsonObject["content"] is kotlinx.serialization.json.JsonPrimitive)   // 過去分は変更しない
        val toolResult = messages[2].jsonObject["content"]!!.jsonArray.last().jsonObject
        assertEquals("tool_result", toolResult["type"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", toolResult["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anthropic cache tokens are normalized into total input`() {
        // Anthropic の input_tokens はキャッシュ分を含まないため、総入力へ正規化される（ARCHITECTURE §4.28）
        val body = """
            {"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",
             "usage":{"input_tokens":100,"output_tokens":10,
                      "cache_read_input_tokens":400,"cache_creation_input_tokens":50}}
        """.trimIndent()
        val turn = anthropicSession().parseAnthropicToolResponse(200, body)
        assertEquals(550, turn.usage.inputTokens)
        assertEquals(400, turn.usage.cachedInputTokens)
        assertEquals(10, turn.usage.outputTokens)
    }

    // ---------------------------------------------------------------- OpenAI

    private fun openAiSession() = OpenAiToolSession("key", "model", "system", emptyList())

    @Test
    fun `openai function_call items become tool calls`() {
        val body = """
            {"output":[
                {"type":"message","role":"assistant","content":[{"type":"output_text","text":"実行します"}]},
                {"type":"function_call","id":"fc_1","call_id":"call_9","name":"apply_patch",
                 "arguments":"{\"file\":\"a.yaml\",\"ops\":[]}"}
             ],
             "usage":{"input_tokens":200,"output_tokens":80},
             "status":"completed"}
        """.trimIndent()
        val turn = openAiSession().parseOpenAiToolResponse(200, body)
        assertEquals("実行します", turn.assistantText)
        val call = turn.toolCalls.single()
        assertEquals("call_9", call.id)
        assertEquals("apply_patch", call.name)
        assertEquals(200, turn.usage.inputTokens)
    }

    @Test
    fun `openai text only response has no calls`() {
        val body = """{"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"done"}]}],"usage":{"input_tokens":1,"output_tokens":1}}"""
        val turn = openAiSession().parseOpenAiToolResponse(200, body)
        assertTrue(turn.toolCalls.isEmpty())
        assertEquals("done", turn.assistantText)
    }

    @Test
    fun `openai invalid arguments json fails clearly`() {
        val body = """{"output":[{"type":"function_call","call_id":"c1","name":"x","arguments":"{oops"}]}"""
        val e = assertFailsWith<IllegalStateException> { openAiSession().parseOpenAiToolResponse(200, body) }
        assertTrue("ツール引数" in e.message!!)
    }

    @Test
    fun `openai error surfaces api message with provider prefix`() {
        val body = """{"error":{"message":"quota exceeded"}}"""
        val e = assertFailsWith<IllegalStateException> { openAiSession().parseOpenAiToolResponse(429, body) }
        assertEquals("OpenAI API: quota exceeded", e.message)
    }

    @Test
    fun `usage defaults to zero when missing`() {
        val turn = openAiSession().parseOpenAiToolResponse(200, """{"output":[]}""")
        assertEquals(0, turn.usage.inputTokens)
        assertEquals(0, turn.usage.cachedInputTokens)
        assertNull(turn.assistantText)
    }

    @Test
    fun `openai cached tokens come from input_tokens_details`() {
        // cached_tokens は input_tokens の内数（自動キャッシュの報告）
        val body = """{"output":[],"usage":{"input_tokens":300,"output_tokens":5,"input_tokens_details":{"cached_tokens":250}}}"""
        val turn = openAiSession().parseOpenAiToolResponse(200, body)
        assertEquals(300, turn.usage.inputTokens)
        assertEquals(250, turn.usage.cachedInputTokens)
    }
}
