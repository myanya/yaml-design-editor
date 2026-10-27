package dev.toros.yde.agent

import dev.toros.yde.agent.llm.AiProvider
import dev.toros.yde.agent.llm.ChatTurn
import dev.toros.yde.agent.llm.ChatUsage
import dev.toros.yde.agent.llm.ToolCall
import dev.toros.yde.agent.llm.ToolChatSession
import dev.toros.yde.agent.llm.ToolResult
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 評価セット（R4-02）と比較実験（R4-04）のテスト。
 * LLM応答は台本に差し替え、サンドボックス実行・期待結果の判定・変異比較・レポート保存を検証する。
 */
class EvalTest {

    @Test
    fun `評価の準備は絶対パスと親参照と隠し領域を拒否する`() {
        for (path in listOf("/tmp/a", "C:/a", "C:a", "\\\\server\\share\\a", "docs/../../a", "docs/../_agents/a", "./_runs/a", "docs/.private/a")) {
            kotlin.test.assertFailsWith<IllegalArgumentException>(path) { Evals.setupPath(path) }
        }
        assertEquals("docs/a.yaml", Evals.setupPath("docs\\a.yaml"))
    }

    @Test
    fun `直接構築した評価定義でも外部ファイルを上書きしない`() = runBlocking {
        val marker = dir.resolve("docs/a.yaml")
        val original = Files.readString(marker)
        val definition = EvalDef("unsafe", "unsafe", activityId = "missing", source = dir.resolve("eval.yaml"),
            cases = listOf(EvalCase("c", "c", setup = listOf(EvalSetupFile(marker.toString(), "overwritten")))))
        val result = runner(ArrayDeque()).run(dir, definition, writeReport = false)
        assertFalse(result.results.single().pass)
        assertEquals(original, Files.readString(marker))
    }

    private lateinit var dir: Path

    private class FakeSession(turns: List<ChatTurn>) : ToolChatSession {
        private val queue = turns.toMutableList()
        override fun addUserText(text: String) {}
        override fun addToolResults(results: List<ToolResult>) {}
        override suspend fun send(): ChatTurn =
            if (queue.isEmpty()) ChatTurn(null, emptyList(), ChatUsage(1, 1), null) else queue.removeAt(0)
    }

    private var callSeq = 0
    private fun call(name: String, argsJson: String): ToolCall =
        ToolCall("call-${callSeq++}", name, Json.parseToJsonElement(argsJson).jsonObject)

    private fun turn(vararg calls: ToolCall): ChatTurn = ChatTurn(null, calls.toList(), ChatUsage(10, 5), null)

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-eval-test")
        write(
            "_schemas/doc.yaml",
            """
            id: doc
            match: "docs/**/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: title, type: string, required: true }
            """,
        )
        write("docs/a.yaml", "id: DOC-A\ntitle: 原本のタイトル")
        write("docs/b.yaml", "id: DOC-B\ntitle: 文書B")
        write("_agents/editor.yaml", "id: editor\npersona: 正確に編集する。")
        write("_activities/fix-title.yaml", "id: fix-title\nagent: editor\ninstruction: タイトルを整える")
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

    private fun runner(scripts: ArrayDeque<List<ChatTurn>>, capturedModels: MutableList<String>? = null) =
        EvalRunner(
            credentials = {
                AutomationEngine.Credentials(AiProvider.OPENAI, apiKeys = mapOf(AiProvider.OPENAI to "test-key"))
            },
            sessionFactory = { _, _, model, _, _ ->
                capturedModels?.add(model)
                FakeSession(scripts.removeFirst())
            },
        )

    // ---------------------------------------------------------------- 期待結果の判定とサンドボックス隔離

    @Test
    fun `eval judges cases against expectations in a sandbox`() {
        write(
            "_evals/fix-eval.yaml",
            """
            id: fix-eval
            activity: fix-title
            cases:
              - id: repairs-title
                name: 壊れたタイトルを修正できる
                setup:
                  - file: docs/a.yaml
                    content: |
                      id: DOC-A
                      title: 壊れたタイトル
                expect:
                  status: submitted
                  errorFree: true
                  changedFiles: ["docs/a.yaml"]
                  unchangedFiles: ["docs/b.yaml"]
                  content:
                    - { file: docs/a.yaml, path: title, contains: 修正済み }
              - id: must-submit
                name: 中断せず提出する
                expect:
                  status: submitted
            """,
        )
        val loaded = Evals.load(dir)
        assertTrue(loaded.errors.isEmpty(), loaded.errors.toString())
        val eval = loaded.eval("fix-eval")!!

        val scripts = ArrayDeque(listOf(
            // case 1: 修正して提出 → 合格
            listOf(
                turn(call("apply_patch", """{"file":"docs/a.yaml","ops":[{"op":"set","path":"title","value":"修正済みタイトル"}]}""")),
                turn(call("submit_changeset", """{"summary":"修正"}""")),
            ),
            // case 2: 中断 → 期待（submitted）に反して不合格
            listOf(turn(call("abort", """{"reason":"わからない"}"""))),
        ))
        val report = runBlocking { runner(scripts).run(dir, eval) }

        val byCase = report.results.associateBy { it.caseId }
        assertTrue(byCase.getValue("repairs-title").pass, byCase.getValue("repairs-title").failures.toString())
        assertFalse(byCase.getValue("must-submit").pass)
        assertTrue(byCase.getValue("must-submit").failures.any { "submitted" in it })

        // サンドボックス隔離: 原本は setup にもパッチにも影響されない
        assertTrue("原本のタイトル" in Files.readString(dir.resolve("docs/a.yaml")))
        // レポートは原本の _runs に保存される
        assertTrue(report.reportFile != null && Files.isRegularFile(report.reportFile!!))
        assertTrue(report.reportFile!!.fileName.toString().startsWith("eval-"))
        // 評価レポートは実行メトリクスの集計対象にならない
        assertTrue(RunMetrics.collect(dir).isEmpty())
    }

    // ---------------------------------------------------------------- 変異のA/B比較（R4-04）

    @Test
    fun `variants run the same cases with overridden model and are compared`() {
        write(
            "_evals/ab-eval.yaml",
            """
            id: ab-eval
            activity: fix-title
            variants:
              - name: base
              - name: alt
                model: { provider: openai, id: model-x }
                instruction: 簡潔に。
            cases:
              - id: submits
                expect: { status: submitted }
            """,
        )
        val eval = Evals.load(dir).eval("ab-eval")!!
        val capturedModels = mutableListOf<String>()
        val scripts = ArrayDeque(listOf(
            listOf(turn(call("submit_changeset", """{"summary":"変更なし"}"""))),   // base → 合格
            listOf(turn(call("abort", """{"reason":"だめ"}"""))),                    // alt → 不合格
        ))
        val report = runBlocking { runner(scripts, capturedModels).run(dir, eval) }

        val summaries = report.summaries().associateBy { it.variant }
        assertEquals(1.0, summaries.getValue("base").passRate)
        assertEquals(0.0, summaries.getValue("alt").passRate)
        // 変異のモデル差し替えが実際のセッション生成に反映される
        assertEquals(2, capturedModels.size)
        assertEquals("model-x", capturedModels[1])
        assertTrue(capturedModels[0] != "model-x")
    }

    // ---------------------------------------------------------------- 定義エラー

    @Test
    fun `variant context override swaps the recipe per variant`() {
        write("_activities/fix-title2.yaml", "id: fix-title2\nagent: editor\ninstruction: タイトルを整える\ncontext:\n  items:\n    - kind: doc\n      file: docs/b.yaml")
        write(
            "_evals/ctx-eval.yaml",
            """
            id: ctx-eval
            activity: fix-title2
            variants:
              - name: base
              - name: catalog-only
                context:
                  items:
                    - kind: catalog
              - name: no-context
                context:
                  items: []
            cases:
              - id: quick
                name: 中断で終わる
                expect: { status: aborted }
            """,
        )
        val eval = Evals.load(dir).eval("ctx-eval")!!
        // 各実行の初期コンテキストを捕捉する台本セッション
        class CapturingSession(turns: List<dev.toros.yde.agent.llm.ChatTurn>) : dev.toros.yde.agent.llm.ToolChatSession {
            private val queue = turns.toMutableList()
            val userTexts = mutableListOf<String>()
            override fun addUserText(text: String) { userTexts += text }
            override fun addToolResults(results: List<ToolResult>) {}
            override suspend fun send(): dev.toros.yde.agent.llm.ChatTurn =
                if (queue.isEmpty()) dev.toros.yde.agent.llm.ChatTurn(null, emptyList(), ChatUsage(1, 1), null) else queue.removeAt(0)
        }
        val sessions = mutableListOf<CapturingSession>()
        val runner = EvalRunner(
            credentials = { AutomationEngine.Credentials(AiProvider.OPENAI, apiKeys = mapOf(AiProvider.OPENAI to "test-key")) },
            sessionFactory = { _, _, _, _, _ ->
                CapturingSession(listOf(turn(call("abort", """{"reason":"確認のみ"}""")))).also { sessions += it }
            },
        )
        val report = kotlinx.coroutines.runBlocking { runner.run(dir, eval, writeReport = false) }
        assertTrue(report.results.all { it.pass }, report.results.toString())
        assertEquals(3, sessions.size)
        val initials = sessions.map { it.userTexts.first() }
        assertTrue("## 文書: docs/b.yaml" in initials[0], initials[0])         // base = アクティビティ定義のまま
        assertTrue("## ワークスペースカタログ" in initials[1], initials[1])      // catalog-only = 差し替え
        assertTrue("## 文書" !in initials[1], initials[1])
        assertTrue("## ワークスペースカタログ" !in initials[2] && "## 文書" !in initials[2], initials[2])  // no-context = 指示のみ
        // レシピ差し替えのサマリはトークンと合格率で比較できる
        val summaries = report.summaries()
        assertEquals(setOf("base", "catalog-only", "no-context"), summaries.map { it.variant }.toSet())
    }

    @Test
    fun `eval definition errors are reported`() {
        write("_evals/no-cases.yaml", "id: no-cases\nactivity: fix-title\ncases: []")
        write("_evals/bad-status.yaml", """
            id: bad-status
            activity: fix-title
            cases:
              - id: c1
                expect: { status: perfect }
        """)
        val loaded = Evals.load(dir)
        assertTrue(loaded.evals.isEmpty())
        assertTrue(loaded.errors.any { "1件以上" in it }, loaded.errors.toString())
        assertTrue(loaded.errors.any { "perfect" in it }, loaded.errors.toString())
    }
}
