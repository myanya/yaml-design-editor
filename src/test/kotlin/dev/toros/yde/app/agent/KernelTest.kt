package dev.toros.yde.app.agent

import dev.toros.yde.app.ai.AiProvider
import dev.toros.yde.app.ai.ChatTurn
import dev.toros.yde.app.ai.ChatUsage
import dev.toros.yde.app.ai.ToolCall
import dev.toros.yde.app.ai.ToolChatSession
import dev.toros.yde.app.ai.ToolResult
import dev.toros.yde.app.core.TraverseStep
import dev.toros.yde.app.core.Workspace
import dev.toros.yde.yaml.validate.Severity
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
            constraints:
              - id: term-used
                description: 用語は1箇所以上から参照されること
                match:
                  - node: { schema: glossary, bind: t }
                  - edge: { dir: in }
                  - node: { schema: doc }
                expect: exists
                level: error
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
        write(
            "_schemas/table.yaml",
            """
            id: table
            match: "tables/**/*.yaml"
            phases:
              order: [requirements, logical-design]
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - key: phase
                  type: enum
                  values: [requirements, logical-design]
                  default: requirements
                - { key: name, type: string, required: true }
            """,
        )
        write("tables/t.yaml", "id: TBL-001\nphase: requirements\nname: たすく\n")
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
        contextItems: List<ContextItemDef> = listOf(ContextItemDef(kind = "catalog")),
    ) = ActivityDef(
        id = "rename-term", name = "用語改名", agentId = "editor",
        targetMatch = "**/*.yaml",
        instruction = "TERM-001 を TERM-100 に改名し、参照元をすべて更新する。",
        contextItems = contextItems,
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
        assertTrue(result.issues.values.flatten().none { it.severity == Severity.ERROR })
        assertTrue("TERM-001" in Files.readString(dir.resolve("docs/b.yaml")))
        // 人間が承認 → P1 の apply で反映（レビューUIと同じ経路）
        val applied = ws.apply(result.changeSet)
        assertTrue(applied.ok, applied.results.toString())
        assertTrue("TERM-100" in Files.readString(dir.resolve("docs/b.yaml")))
    }

    @Test
    fun `graph integrity gate rejects a changeset that creates a cycle`() {
        // グラフ整合検証（R21-01。ARCHITECTURE §4.31）が提出ゲートに乗ることの確認:
        // acyclic 宣言された relation の循環を作る変更セットは拒否され、拒否理由に循環経路が載る
        write(
            "_schemas/flow.yaml",
            """
            id: flow
            match: "flows/**/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: next, type: ref, relation: follows }
            """,
        )
        write(
            "_schemas/relations.yaml",
            """
            id: rels
            kind: relations
            relations:
              - name: follows
                acyclic: true
            """,
        )
        write("flows/f1.yaml", "id: FLOW-1\nnext: FLOW-2")
        write("flows/f2.yaml", "id: FLOW-2")
        val ws = Workspace.open(dir)
        val session = FakeSession(
            listOf(
                // FLOW-2 → FLOW-1 の参照を書くと FLOW-1 → FLOW-2 → FLOW-1 の循環になる
                turn(call("apply_patch", """{"file":"flows/f2.yaml","ops":[{"op":"insert","path":"next","value":"FLOW-1"}]}""")),
                turn(call("submit_changeset", """{"summary":"循環を作る変更"}""")),
                turn(call("abort", """{"reason":"拒否されたため中断"}""")),
            )
        )
        val result = run(ws, session, RecordingHost())

        assertEquals(RunStatus.ABORTED, result.status)
        val rejection = session.toolResults[1].single().content
        assertTrue("提出は拒否されました" in rejection, rejection)
        assertTrue("循環" in rejection && "FLOW-1 → FLOW-2 → FLOW-1" in rejection, rejection)
        // 原本は未変更
        assertTrue("next" !in Files.readString(dir.resolve("flows/f2.yaml")))
    }

    @Test
    fun `no-new-errors gate - 既存エラーを残したままの提出は通る`() {
        // 点検系ゲート（R28-05b・UX-47）: TERM-002 は未参照＝既存の ERROR。
        // 触った文書に既存エラーが残っていても、増やしていなければ提出できる（error-free なら拒否される場面）
        write("glossary/main.yaml", "terms:\n- id: TERM-001\n  title: 旧用語\n- id: TERM-002\n  title: 未参照用語")
        val ws = Workspace.open(dir)
        val session = FakeSession(
            listOf(
                turn(call("apply_patch", """{"file":"glossary/main.yaml","ops":[{"op":"set","path":"terms[1].title","value":"未参照用語（説明を更新）"}]}""")),
                turn(call("submit_changeset", """{"summary":"用語の説明を更新"}""")),
            )
        )
        val result = run(ws, session, RecordingHost(), activity(gate = ValidationGate.NO_NEW_ERRORS))

        assertEquals(RunStatus.SUBMITTED, result.status)
        assertTrue("提出しました" in session.toolResults[1].single().content)
    }

    @Test
    fun `no-new-errors gate - 新しく増えたエラーだけを理由に拒否する`() {
        // 既存エラー（TERM-002 未参照）は拒否理由に載らず、この変更で増えたエラー（TERM-999 未解決）だけが載る
        write("glossary/main.yaml", "terms:\n- id: TERM-001\n  title: 旧用語\n- id: TERM-002\n  title: 未参照用語")
        val ws = Workspace.open(dir)
        val session = FakeSession(
            listOf(
                turn(
                    call("apply_patch", """{"file":"glossary/main.yaml","ops":[{"op":"set","path":"terms[1].title","value":"未参照用語（更新）"}]}"""),
                    call("apply_patch", """{"file":"docs/a.yaml","ops":[{"op":"set","path":"uses[0]","value":"TERM-999"}]}"""),
                ),
                turn(call("submit_changeset", """{"summary":"参照を壊す変更"}""")),
                turn(call("abort", """{"reason":"拒否されたため中断"}""")),
            )
        )
        val result = run(ws, session, RecordingHost(), activity(gate = ValidationGate.NO_NEW_ERRORS))

        assertEquals(RunStatus.ABORTED, result.status)
        val rejection = session.toolResults[1].single().content
        assertTrue("新しく増えた検証エラー" in rejection, rejection)
        assertTrue("TERM-999" in rejection, rejection)
        assertTrue("TERM-002" !in rejection, rejection)
    }

    @Test
    fun `transcript records system instruction assistant text and full tool exchanges`() {
        val ws = Workspace.open(dir)
        val session = FakeSession(
            listOf(
                turn(call("read_outline", """{"file":"docs/a.yaml"}"""), text = "まず対象を確認します"),
                turn(call("abort", """{"reason":"確認のみ"}""")),
            )
        )
        val result = run(ws, session, RecordingHost())

        // セッション初期化: システム指示 → 初期コンテキスト（turn 0）
        assertEquals(listOf("system", "user"), result.transcript.take(2).map { it.role })
        assertEquals(0, result.transcript[1].turn)
        assertEquals(session.userTexts.first(), result.transcript[1].content)
        // 1ターン目: 本文テキストと、ツール呼び出し引数・結果の全文
        assertTrue(result.transcript.any { it.role == "assistant" && it.content == "まず対象を確認します" })
        val toolCall = result.transcript.first { it.role == "tool_call" }
        assertEquals("read_outline", toolCall.tool)
        assertTrue("docs/a.yaml" in toolCall.content)
        val toolResult = result.transcript.first { it.role == "tool_result" && it.tool == "read_outline" }
        assertEquals(session.toolResults[0].single().content, toolResult.content)
    }

    @Test
    fun `context accounting records section stats, per-turn input and tool result sizes`() {
        val ws = Workspace.open(dir)
        val session = FakeSession(
            listOf(
                turn(call("read_outline", """{"file":"docs/a.yaml"}""")),
                turn(call("abort", """{"reason":"確認のみ"}""")),
            )
        )
        val result = run(ws, session, RecordingHost())

        // 内訳: システム指示・ツール定義・指示＋context 宣言由来の区分（既定 catalog）が順に記録される
        assertEquals(
            listOf("システム指示", "ツール定義", "指示", "ワークスペースカタログ"),
            result.contextStats.map { it.section },
        )
        result.contextStats.forEach { stat ->
            assertTrue(stat.chars > 0 && stat.tokensEst > 0, "空の区分: ${stat.section}")
        }
        // 区分の合成が実際の送信文字列（従来書式）になっている
        val initial = session.userTexts.first()
        assertTrue("# アクティビティ: 用語改名" in initial)
        assertTrue("## ワークスペースカタログ" in initial)
        // 送信別入力トークン: 台本セッションは各送信 100 を報告する
        assertEquals(listOf(100L, 100L), result.turnInputTokens)
        // ツール結果は打切り前の全長が記録される
        val entry = result.toolLog.first { it.name == "read_outline" }
        assertEquals(session.toolResults[0].single().content.length, entry.resultChars)
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

    @Test
    fun `context include phase surfaces target phase state in initial context`() {
        val ws = Workspace.open(dir)
        val session = FakeSession(listOf(turn(call("abort", """{"reason":"確認のみ"}"""))))
        run(
            ws, session, RecordingHost(),
            activity(contextItems = listOf(ContextItemDef(kind = "catalog"), ContextItemDef(kind = "phase"))),
        )
        val initial = session.userTexts.first()
        assertTrue("フェーズ状態" in initial)
        assertTrue("tables/t.yaml: phase=requirements" in initial)
    }

    // ---------------------------------------------------------------- コンテキストレシピ（R19-02）

    private fun runWithValues(
        ws: Workspace,
        session: FakeSession,
        act: ActivityDef,
        targetValues: Map<String, String> = emptyMap(),
        extraInstruction: String? = null,
    ): AgentRunResult = runBlocking {
        AgentKernel.run(
            ws,
            AgentRunRequest(
                act, agentDef, AiProvider.OPENAI, "test-model", "test-key",
                extraInstruction = extraInstruction, targetValues = targetValues,
            ),
            RecordingHost(),
            sessionFactory = { _, _, _, _, _ -> session },
        )
    }

    @Test
    fun `context recipe resolves catalog match, doc placeholder, related and sample`() {
        val ws = Workspace.open(dir)
        val session = FakeSession(listOf(turn(call("abort", """{"reason":"確認のみ"}"""))))
        val act = activity(
            contextItems = listOf(
                ContextItemDef(kind = "catalog", match = "docs/*"),
                ContextItemDef(kind = "doc", file = "{file}", path = "title"),
                ContextItemDef(kind = "related", id = "{target}"),
                ContextItemDef(kind = "sample", schema = "doc", count = 1),
            ),
        )
        val result = runWithValues(
            ws, session, act,
            targetValues = mapOf("target" to "TERM-001", "file" to "docs/a.yaml"),
        )
        val initial = session.userTexts.first()
        // catalog は match で絞られる（glossary は docs/* に一致しない）
        assertTrue("docs/a.yaml" in initial)
        assertTrue("glossary/main.yaml" !in initial.substringBefore("## 文書"))
        // doc: {file} が解決され title ノードが開示される
        assertTrue("## 文書: docs/a.yaml :: title" in initial)
        assertTrue("文書A" in initial)
        // related: {target} が解決され出現・被参照が載る
        assertTrue("## 関連情報: TERM-001" in initial)
        assertTrue("被参照" in initial)
        // sample: doc スキーマの先頭1件（docs/a.yaml）が記述例になる
        assertTrue("## 記述例（schema: doc）" in initial)
        // 会計にも各区分が載る
        assertEquals(
            listOf("システム指示", "ツール定義", "指示", "ワークスペースカタログ", "文書: docs/a.yaml :: title", "関連情報: TERM-001", "記述例（schema: doc）"),
            result.contextStats.map { it.section },
        )
    }

    @Test
    fun `context recipe when-conditions and unresolved placeholders gate disclosure`() {
        val ws = Workspace.open(dir)
        val act = activity(
            contextItems = listOf(
                ContextItemDef(kind = "catalog", condition = ContextWhen.NO_TARGET),
                ContextItemDef(kind = "related", id = "{target}", condition = ContextWhen.HAS_TARGET),
                ContextItemDef(kind = "doc", file = "{missing}"),   // 未解決 → 開示しない
            ),
        )
        // 対象指定なし: catalog のみ（related は when で、doc は未解決で落ちる）
        val s1 = FakeSession(listOf(turn(call("abort", """{"reason":"x"}"""))))
        runWithValues(ws, s1, act)
        val noTarget = s1.userTexts.first()
        assertTrue("## ワークスペースカタログ" in noTarget)
        assertTrue("## 関連情報" !in noTarget)
        assertTrue("## 文書" !in noTarget)
        // 対象指定あり（targetValues）: catalog が落ち related が載る
        val s2 = FakeSession(listOf(turn(call("abort", """{"reason":"x"}"""))))
        runWithValues(ws, s2, act, targetValues = mapOf("target" to "TERM-001"))
        val hasTarget = s2.userTexts.first()
        assertTrue("## ワークスペースカタログ" !in hasTarget)
        assertTrue("## 関連情報: TERM-001" in hasTarget)
        // 追加指示だけでも「対象指定あり」扱い（プレースホルダ項目は解決できないため落ちる）
        val s3 = FakeSession(listOf(turn(call("abort", """{"reason":"x"}"""))))
        runWithValues(ws, s3, act, extraInstruction = "対象: TERM-001")
        val prose = s3.userTexts.first()
        assertTrue("## ワークスペースカタログ" !in prose)
        assertTrue("## 関連情報" !in prose)
    }

    @Test
    fun `graph context item traverses from placeholder start`() {
        val ws = Workspace.open(dir)
        val session = FakeSession(listOf(turn(call("abort", """{"reason":"x"}"""))))
        val act = activity(
            contextItems = listOf(
                ContextItemDef(
                    kind = "graph", start = "{target}",
                    steps = listOf(
                        TraverseStep(emit = "summary"),
                        TraverseStep(follow = "out", emit = "title"),
                    ),
                ),
            ),
        )
        val result = runWithValues(ws, session, act, targetValues = mapOf("target" to "DOC-A"))
        val initial = session.userTexts.first()
        assertTrue("## グラフ: DOC-A" in initial, initial)
        assertTrue("- TERM-001" in initial, initial)   // DOC-A の uses 参照を1段辿った先
        assertTrue(result.contextStats.any { it.section == "グラフ: DOC-A" })
    }

    @Test
    fun `entity context item discloses composition for placeholder target`() {
        val ws = Workspace.open(dir)
        val session = FakeSession(listOf(turn(call("abort", """{"reason":"x"}"""))))
        val act = activity(contextItems = listOf(ContextItemDef(kind = "entity", id = "{target}")))
        runWithValues(ws, session, act, targetValues = mapOf("target" to "TERM-001"))
        val initial = session.userTexts.first()
        // read_entity と同じ合成（アスペクト見出し＋所有フィールド＋両方向の関係）が初期文脈に載る（R23-04）
        assertTrue("## エンティティ: TERM-001" in initial, initial)
        assertTrue("- title: 旧用語" in initial, initial)
        assertTrue("この要素への参照 2 件" in initial, initial)
    }

    @Test
    fun `context recipe budget caps section deterministically`() {
        val ws = Workspace.open(dir)
        val session = FakeSession(listOf(turn(call("abort", """{"reason":"x"}"""))))
        val act = activity(contextItems = listOf(ContextItemDef(kind = "catalog", budgetChars = 100)))
        runWithValues(ws, session, act)
        val initial = session.userTexts.first()
        assertTrue("…（予算 100 文字で打切り）" in initial)
    }
}
