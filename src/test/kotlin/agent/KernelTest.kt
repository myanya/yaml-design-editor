package agent

import ai.AiProvider
import ai.ChatTurn
import ai.ChatUsage
import ai.ToolCall
import ai.ToolChatSession
import ai.ToolResult
import core.Workspace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * エージェントカーネルの結合テスト。
 * LLM応答だけを台本（FakeSession）に差し替え、ツール実行・ステージング・検証・変更セットは本物を通す。
 * 「用語改名 → 影響探索 → 複数文書パッチ → ゲート拒否 → 自己修正 → 提出 → 適用」の
 * P2完了条件シナリオをAPIキーなしで検証する。
 */
class KernelTest {

    private lateinit var dir: Path

    // ---------------------------------------------------------------- 台本セッション

    private class FakeSession(turns: List<ChatTurn>) : ToolChatSession {
        private val queue = turns.toMutableList()
        val userTexts = mutableListOf<String>()
        val toolResults = mutableListOf<List<ToolResult>>()
        override fun addUserText(text: String) { userTexts += text }
        override fun addToolResults(results: List<ToolResult>) { toolResults += results }
        override suspend fun send(): ChatTurn =
            if (queue.isEmpty()) ChatTurn(null, emptyList(), ChatUsage(1, 1), null) else queue.removeAt(0)
    }

    private var callSeq = 0
    private fun call(name: String, argsJson: String): ToolCall =
        ToolCall("call-${callSeq++}", name, Json.parseToJsonElement(argsJson).jsonObject)

    private fun turn(vararg calls: ToolCall, text: String? = null): ChatTurn =
        ChatTurn(text, calls.toList(), ChatUsage(100, 50), null)

    private class RecordingHost(private val answer: String? = null) : AgentHost {
        val events = mutableListOf<AgentEvent>()
        val asked = mutableListOf<String>()
        override suspend fun askHuman(question: String): String? {
            asked += question
            return answer
        }
        override suspend fun onEvent(event: AgentEvent) { events += event }
    }

    // ---------------------------------------------------------------- fixtures

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-kernel")
        write(
            "_schemas/glossary.yaml",
            """
            id: glossary
            match: "glossary/**/*.yaml"
            coverage:
              - requiredBy: { minCount: 1 }
                level: error
                description: 用語は1箇所以上から参照されること
            root:
              type: object
              fields:
                - key: terms
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: title, type: string, required: true }
            """,
        )
        write(
            "_schemas/doc.yaml",
            """
            id: doc
            match: "docs/**/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: title, type: string }
                - key: uses
                  type: array
                  item:
                    type: ref
                    scope: { schemaIds: [glossary] }
            """,
        )
        write("glossary/main.yaml", "terms:\n- id: TERM-001\n  title: 旧用語")
        write("docs/a.yaml", "id: DOC-A\ntitle: 文書A\nuses:\n- TERM-001")
        write("docs/b.yaml", "id: DOC-B\ntitle: 文書B\nuses:\n- TERM-001")
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

    private fun activity(
        maxTurns: Int = 24,
        allowedFiles: List<String> = emptyList(),
        gate: ValidationGate = ValidationGate.ERROR_FREE,
    ) = ActivityDef(
        id = "rename-term", name = "用語改名", agentId = "editor",
        targetMatch = "**/*.yaml",
        instruction = "TERM-001 を TERM-100 に改名し、参照元をすべて更新する。",
        allowedFiles = allowedFiles,
        limits = ActivityLimits(maxTurns = maxTurns, maxTokens = 1_000_000, maxSeconds = 300),
        gate = gate,
        source = Path.of("_activities/rename-term.yaml"),
    )

    private val agentDef = AgentDef(
        id = "editor", name = "編集担当", persona = "正確に編集する。",
        source = Path.of("_agents/editor.yaml"),
    )

    private fun run(ws: Workspace, session: FakeSession, host: RecordingHost, act: ActivityDef = activity()): AgentRunResult =
        runBlocking {
            AgentKernel.run(
                ws,
                AgentRunRequest(act, agentDef, AiProvider.OPENAI, "test-model", "test-key"),
                host,
                sessionFactory = { _, _, _, _, _ -> session },
            )
        }

    // ---------------------------------------------------------------- シナリオ

    @Test
    fun `end to end - explore, multi-file patch, gate rejection, self-fix, submit, apply`() {
        val ws = Workspace.open(dir)
        val session = FakeSession(
            listOf(
                // 1: 影響調査（探索はエージェントの行動）
                turn(call("related", """{"id":"TERM-001"}""")),
                // 2: 用語集の改名と docs/a の修正…だが docs/b を忘れている
                turn(
                    call("apply_patch", """{"file":"glossary/main.yaml","ops":[{"op":"set","path":"terms[0].id","value":"TERM-100"}]}"""),
                    call("apply_patch", """{"file":"docs/a.yaml","ops":[{"op":"set","path":"uses[0]","value":"TERM-100"}]}"""),
                ),
                // 3: 提出 → docs/b の参照が壊れたままなのでゲートに拒否される
                turn(call("submit_changeset", """{"summary":"用語を改名しました"}""")),
                // 4: 拒否理由を受けて自己修正
                turn(call("apply_patch", """{"file":"docs/b.yaml","ops":[{"op":"set","path":"uses[0]","value":"TERM-100"}]}""")),
                // 5: 検証して提出
                turn(call("validate", "{}")),
                turn(call("submit_changeset", """{"summary":"用語 TERM-001 を TERM-100 に改名し、参照元2文書を更新"}""")),
            )
        )
        val host = RecordingHost()
        val result = run(ws, session, host)

        assertEquals(RunStatus.SUBMITTED, result.status)
        assertEquals(setOf("glossary/main.yaml", "docs/a.yaml", "docs/b.yaml"),
            result.changeSet.changes.map { it.file }.toSet())
        // ゲート拒否がツール結果としてモデルへ返っている（ターン3への応答）
        assertTrue(session.toolResults[2].single().content.contains("提出は拒否されました"))
        // 終了時点の検証はエラーなし・原本は提出時点では未変更
        assertTrue(result.issues.values.flatten().none { it.severity == validate.Severity.ERROR })
        assertTrue("TERM-001" in Files.readString(dir.resolve("docs/b.yaml")))
        // 人間が承認 → P1 の apply で反映（レビューUIと同じ経路）
        val applied = ws.apply(result.changeSet)
        assertTrue(applied.ok, applied.results.toString())
        assertTrue("TERM-100" in Files.readString(dir.resolve("docs/b.yaml")))
    }

    @Test
    fun `ask_human answer flows back and is recorded`() {
        val ws = Workspace.open(dir)
        val session = FakeSession(
            listOf(
                turn(call("ask_human", """{"question":"新しいIDは TERM-100 でよいですか？"}""")),
                turn(call("abort", """{"reason":"確認のため中断"}""")),
            )
        )
        val host = RecordingHost(answer = "TERM-100 でお願いします")
        val result = run(ws, session, host)
        assertEquals(listOf("新しいIDは TERM-100 でよいですか？"), host.asked)
        assertEquals("TERM-100 でお願いします", session.toolResults[0].single().content)
        assertEquals("新しいIDは TERM-100 でよいですか？" to "TERM-100 でお願いします", result.questions.single())
        assertEquals(RunStatus.ABORTED, result.status)
    }

    @Test
    fun `turn limit preserves staging as draft`() {
        val ws = Workspace.open(dir)
        val session = FakeSession(
            listOf(
                turn(call("apply_patch", """{"file":"docs/a.yaml","ops":[{"op":"set","path":"title","value":"編集済み"}]}""")),
                turn(call("read_outline", """{"file":"docs/a.yaml"}""")),
                turn(call("read_outline", """{"file":"docs/b.yaml"}""")),
            )
        )
        val result = run(ws, session, RecordingHost(), activity(maxTurns = 2))
        assertEquals(RunStatus.LIMIT, result.status)
        // ドラフト（未提出の変更セット）が残り、人間が引き継げる
        assertEquals(listOf("docs/a.yaml"), result.changeSet.changes.map { it.file })
        assertTrue("編集済み" in result.staging.proposedText("docs/a.yaml")!!)
        assertTrue("文書A" in Files.readString(dir.resolve("docs/a.yaml")))  // 原本は未変更
    }

    @Test
    fun `write permissions are enforced by tools not by prompt`() {
        val ws = Workspace.open(dir)
        val session = FakeSession(
            listOf(
                // 許可外ファイルへの書込と予約ディレクトリへの書込はツールが拒否する
                turn(call("apply_patch", """{"file":"glossary/main.yaml","ops":[{"op":"set","path":"terms[0].title","value":"x"}]}""")),
                turn(call("apply_patch", """{"file":"_schemas/doc.yaml","ops":[{"op":"delete","path":"root"}]}""")),
                turn(call("abort", """{"reason":"テスト終了"}""")),
            )
        )
        val result = run(ws, session, RecordingHost(), activity(allowedFiles = listOf("docs/**")))
        assertTrue(session.toolResults[0].single().content.startsWith("エラー:"))
        assertTrue("allowedFiles" in session.toolResults[0].single().content)
        assertTrue("予約ディレクトリ" in session.toolResults[1].single().content)
        assertTrue(result.changeSet.changes.isEmpty())  // 何もステージされていない
    }

    @Test
    fun `agent that never terminates is stopped after idle turns`() {
        val ws = Workspace.open(dir)
        val session = FakeSession(
            listOf(
                ChatTurn("作業が完了しました", emptyList(), ChatUsage(1, 1), null),
                ChatTurn("以上です", emptyList(), ChatUsage(1, 1), null),
            )
        )
        val result = run(ws, session, RecordingHost())
        assertEquals(RunStatus.ABORTED, result.status)
        assertTrue("submit_changeset" in (result.message ?: ""))
        // 1回目の沈黙では終了手続きを促すメッセージが送られている
        assertTrue(session.userTexts.any { "submit_changeset" in it })
    }

    @Test
    fun `delegate consults sub agent on shared staging and charges usage`() {
        val ws = Workspace.open(dir)
        // 親: ステージングへ編集 → レビュー担当へ相談 → 中断
        val parentSession = FakeSession(
            listOf(
                turn(call("apply_patch", """{"file":"docs/a.yaml","ops":[{"op":"set","path":"title","value":"下書きタイトル"}]}""")),
                turn(call("delegate", """{"agent":"reviewer","instruction":"docs/a.yaml のタイトルを見てください"}""")),
                turn(call("abort", """{"reason":"相談まで確認"}""")),
            )
        )
        // 相談先: ステージング済みの編集内容を読んでから所見を返す（ツールなしターン＝所見）
        val consultSession = FakeSession(
            listOf(
                ChatTurn(null, listOf(call("read_node", """{"file":"docs/a.yaml","path":"title"}""")), ChatUsage(10, 5), null),
                ChatTurn("所見: タイトルは簡潔で良いです", emptyList(), ChatUsage(7, 3), null),
            )
        )
        var factoryCalls = 0
        val reviewer = AgentDef(id = "reviewer", name = "レビュー担当", persona = "根拠を添える。",
            source = Path.of("_agents/reviewer.yaml"))
        val result = runBlocking {
            AgentKernel.run(
                ws,
                AgentRunRequest(
                    activity(), agentDef, AiProvider.OPENAI, "test-model", "test-key",
                    apiKeys = mapOf(AiProvider.OPENAI to "test-key"),
                    subAgents = listOf(agentDef, reviewer),  // 自分自身は委譲先から除外される
                ),
                RecordingHost(),
                sessionFactory = { _, _, _, _, _ -> if (factoryCalls++ == 0) parentSession else consultSession },
            )
        }
        // 相談先はステージング済みの編集（下書きタイトル）を読めている
        assertTrue("下書きタイトル" in consultSession.toolResults[0].single().content)
        // 所見が親のツール結果として返っている
        assertTrue("所見: タイトルは簡潔で良いです" in parentSession.toolResults[1].single().content)
        // 相談分のトークンも合算される（親3ターン×100/50 + 相談2ターン 10+7/5+3）
        assertEquals(300 + 10 + 7, result.usage.inputTokens)
        assertEquals(150 + 5 + 3, result.usage.outputTokens)
        assertEquals(RunStatus.ABORTED, result.status)
    }

    @Test
    fun `initial context carries instruction catalog and gate`() {
        val ws = Workspace.open(dir)
        val session = FakeSession(listOf(turn(call("abort", """{"reason":"確認のみ"}"""))))
        run(ws, session, RecordingHost())
        val initial = session.userTexts.first()
        assertTrue("TERM-001 を TERM-100 に改名" in initial)
        assertTrue("glossary/main.yaml" in initial)   // カタログ
        assertTrue("検証エラー0" in initial)           // ゲートの明示
    }
}
