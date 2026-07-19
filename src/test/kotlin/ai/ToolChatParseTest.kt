package ai

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
        assertNull(turn.assistantText)
    }
}
