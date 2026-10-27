package dev.toros.yde.agent.run

import dev.toros.yde.agent.Definitions
import dev.toros.yde.agent.llm.AiProvider
import dev.toros.yde.agent.llm.ChatTurn
import dev.toros.yde.agent.llm.ChatUsage
import dev.toros.yde.agent.llm.ToolCall
import dev.toros.yde.agent.llm.ToolChatSession
import dev.toros.yde.agent.llm.ToolResult
import dev.toros.yde.app.core.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * メタアクティビティ（R4-03）のテスト。
 * meta: true のときだけ定義文書（_agents / _activities / _evals）へ書き込め、
 * ステージされた定義が実際にパースできることが提出ゲートに含まれることを検証する。
 */
class MetaActivityTest {

    private lateinit var dir: Path

    private class FakeSession(turns: List<ChatTurn>) : ToolChatSession {
        private val queue = turns.toMutableList()
        val toolResults = mutableListOf<List<ToolResult>>()
        override fun addUserText(text: String) {}
        override fun addToolResults(results: List<ToolResult>) { toolResults += results }
        override suspend fun send(): ChatTurn =
            if (queue.isEmpty()) ChatTurn(null, emptyList(), ChatUsage(1, 1), null) else queue.removeAt(0)
    }

    private var callSeq = 0
    private fun call(name: String, argsJson: String): ToolCall =
        ToolCall("call-${callSeq++}", name, Json.parseToJsonElement(argsJson).jsonObject)

    private fun turn(vararg calls: ToolCall): ChatTurn = ChatTurn(null, calls.toList(), ChatUsage(10, 5), null)

    private object SilentHost : AgentHost {
        override suspend fun askHuman(question: String): String? = null
    }

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-meta")
        write("_agents/editor.yaml", "id: editor\npersona: 定義を丁寧に扱う。")
        write("_activities/improve.yaml", "id: improve\nagent: editor\ninstruction: 定義を改善する\nmeta: true")
        write("_activities/plain.yaml", "id: plain\nagent: editor\ninstruction: 通常作業")
        write("docs/readme.yaml", "note: 対象文書")
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun write(rel: String, content: String) {
        val file = dir.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent() + "\n")
    }

    private fun runActivity(activityId: String, session: FakeSession): AgentRunResult {
        val ws = Workspace.open(dir)
        val defs = Definitions.load(dir)
        val request = AgentRunRequest(
            activity = defs.activity(activityId)!!,
            agent = defs.agent("editor")!!,
            provider = AiProvider.OPENAI, model = "test-model", apiKey = "test-key",
        )
        return runBlocking { AgentKernel.run(ws, request, SilentHost) { _, _, _, _, _ -> session } }
    }

    // ---------------------------------------------------------------- meta: 定義の作成と提出ゲート

    @Test
    fun `meta activity can propose definitions and gate rejects unparseable ones`() {
        val session = FakeSession(listOf(
            // 1. 新しいアクティビティ定義を作る（schedule なのに everyMinutes がない = パース不能）
            turn(call("create_document",
                """{"file":"_activities/nightly.yaml","content_yaml":"id: nightly\nagent: editor\ninstruction: 定期チェック\ntrigger:\n  type: schedule"}""")),
            // 2. 提出 → 定義パースゲートで拒否される
            turn(call("submit_changeset", """{"summary":"夜間チェックの追加"}""")),
            // 3. 自己修正（everyMinutes を追加）
            turn(call("apply_patch",
                """{"file":"_activities/nightly.yaml","ops":[{"op":"set","path":"trigger.everyMinutes","value":60}]}""")),
            // 4. 再提出 → 通過
            turn(call("submit_changeset", """{"summary":"夜間チェックの追加"}""")),
        ))
        val result = runActivity("improve", session)

        assertEquals(RunStatus.SUBMITTED, result.status)
        // 最初の提出はゲートで拒否され、パース不能の理由が返っている
        val rejection = session.toolResults[1].single().content
        assertTrue("提出は拒否されました" in rejection, rejection)
        assertTrue("定義として解釈できません" in rejection, rejection)
        assertTrue("everyMinutes" in rejection, rejection)
        // 提出物は定義文書の新規作成。原本には書かれていない（承認待ち）
        assertEquals(listOf("_activities/nightly.yaml"), result.changeSet.changes.map { it.file })
        assertTrue(result.changeSet.changes.single().create)
        assertFalse(Files.exists(dir.resolve("_activities/nightly.yaml")))
    }

    // ---------------------------------------------------------------- 非meta: 定義への書込は拒否

    @Test
    fun `non-meta activity cannot write to definition directories`() {
        val session = FakeSession(listOf(
            turn(call("create_document", """{"file":"_agents/rogue.yaml","content_yaml":"id: rogue\npersona: 自作"}""")),
            turn(call("abort", """{"reason":"書けなかった"}""")),
        ))
        val result = runActivity("plain", session)

        assertEquals(RunStatus.ABORTED, result.status)
        val denial = session.toolResults[0].single().content
        assertTrue("予約ディレクトリ" in denial, denial)
        assertTrue(result.changeSet.changes.isEmpty())
    }

    // ---------------------------------------------------------------- meta でも _schemas / _runs は不可

    @Test
    fun `meta activity still cannot write schemas or runs`() {
        val session = FakeSession(listOf(
            turn(call("create_document", """{"file":"_schemas/hack.yaml","content_yaml":"id: hack"}""")),
            turn(call("create_document", """{"file":"_runs/fake.yaml","content_yaml":"status: SUBMITTED"}""")),
            turn(call("abort", """{"reason":"終了"}""")),
        ))
        val result = runActivity("improve", session)

        assertEquals(RunStatus.ABORTED, result.status)
        assertTrue("予約ディレクトリ" in session.toolResults[0].single().content)
        assertTrue("予約ディレクトリ" in session.toolResults[1].single().content)
        assertTrue(result.changeSet.changes.isEmpty())
    }
}
