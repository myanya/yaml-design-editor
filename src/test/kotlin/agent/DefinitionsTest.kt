package agent

import ai.AiProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefinitionsTest {

    private lateinit var ws: Path

    @BeforeTest
    fun setup() {
        ws = createTempDirectory("yde-defs")
    }

    @AfterTest
    fun cleanup() {
        Files.walk(ws).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun write(rel: String, content: String) {
        val file = ws.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent() + "\n")
    }

    @Test
    fun `loads agent and activity with defaults`() {
        write(
            "_agents/reviewer.yaml",
            """
            id: reviewer
            name: レビュー担当
            persona: 慎重で根拠を明示する。
            rubric: [妥当性, 一意性]
            model: { provider: anthropic, id: claude-opus-4-8 }
            """,
        )
        write(
            "_activities/review.yaml",
            """
            id: review
            name: 要求レビュー
            agent: reviewer
            target: { match: "requirements/**/*.yaml" }
            instruction: 各要求をレビューして指摘を追記する。
            allowedPaths: ["review_comments*"]
            limits: { maxTurns: 10 }
            """,
        )
        val defs = Definitions.load(ws)
        assertTrue(defs.errors.isEmpty(), defs.errors.toString())

        val agent = defs.agent("reviewer")!!
        assertEquals(AiProvider.ANTHROPIC, agent.provider)
        assertEquals("claude-opus-4-8", agent.model)
        assertEquals(ToolGroup.entries.toSet(), agent.tools)  // 省略時は全許可

        val activity = defs.activity("review")!!
        assertEquals("reviewer", activity.agentId)
        assertEquals("requirements/**/*.yaml", activity.targetMatch)
        assertEquals(10, activity.limits.maxTurns)
        assertEquals(400_000, activity.limits.maxTokens)      // 既定値
        assertEquals(ValidationGate.ERROR_FREE, activity.gate) // 既定値
        assertEquals(listOf("review_comments*"), activity.allowedPaths)
    }

    @Test
    fun `missing agent reference is reported`() {
        write("_activities/a.yaml", "id: a\nagent: ghost\ninstruction: x")
        val defs = Definitions.load(ws)
        assertTrue(defs.errors.any { "ghost" in it }, defs.errors.toString())
    }

    @Test
    fun `invalid definitions collect errors without crashing`() {
        write("_agents/bad.yaml", "id: bad")                       // persona なし
        write("_activities/bad.yaml", "id: bad\nagent: bad")       // instruction なし
        write("_activities/oops.yaml", "id: oops\nagent: bad\ninstruction: x\napproval: sometimes")  // 未知の承認方式
        val defs = Definitions.load(ws)
        assertEquals(0, defs.agents.size)
        assertEquals(0, defs.activities.size)
        assertTrue(defs.errors.any { "persona" in it })
        assertTrue(defs.errors.any { "instruction" in it })
        assertTrue(defs.errors.any { "sometimes" in it })
    }

    @Test
    fun `parses automation fields trigger chain retry approval`() {
        write("_agents/editor.yaml", "id: editor\npersona: 編集する。")
        write(
            "_activities/watcher.yaml",
            """
            id: watcher
            agent: editor
            instruction: 監視して直す
            approval: auto
            trigger:
              type: on-change
              match: "docs/**"
              debounceSeconds: 5
            chain: { onSuccess: reporter, onFailure: reporter }
            retry: { maxAttempts: 3 }
            """,
        )
        write("_activities/reporter.yaml", "id: reporter\nagent: editor\ninstruction: 報告する\ntrigger: manual")
        val defs = Definitions.load(ws)
        assertTrue(defs.errors.isEmpty(), defs.errors.toString())

        val watcher = defs.activity("watcher")!!
        assertEquals(ApprovalMode.AUTO, watcher.approval)
        assertEquals(TriggerType.ON_CHANGE, watcher.trigger.type)
        assertEquals("docs/**", watcher.trigger.match)
        assertEquals(5, watcher.trigger.debounceSeconds)
        assertEquals("reporter", watcher.chain.onSuccess)
        assertEquals(3, watcher.retry.maxAttempts)
        // 文字列ショートハンド
        assertEquals(TriggerType.MANUAL, defs.activity("reporter")!!.trigger.type)
    }

    @Test
    fun `automation definition errors are reported`() {
        write("_agents/editor.yaml", "id: editor\npersona: 編集する。")
        write("_activities/sched.yaml", "id: sched\nagent: editor\ninstruction: x\ntrigger: { type: schedule }")  // everyMinutes なし
        write("_activities/chainy.yaml", "id: chainy\nagent: editor\ninstruction: x\nchain: { onSuccess: ghost }") // 連鎖先なし
        val defs = Definitions.load(ws)
        assertTrue(defs.errors.any { "everyMinutes" in it }, defs.errors.toString())
        assertTrue(defs.errors.any { "ghost" in it }, defs.errors.toString())
    }

    @Test
    fun `meta activity is parsed and rejects auto approval`() {
        write("_agents/editor.yaml", "id: editor\npersona: 編集する。")
        write("_activities/improve.yaml", "id: improve\nagent: editor\ninstruction: 定義を改善する\nmeta: true")
        // meta かつ auto は定義エラー（定義変更の無人適用は不可）
        write("_activities/rogue.yaml", "id: rogue\nagent: editor\ninstruction: x\nmeta: true\napproval: auto")
        val defs = Definitions.load(ws)
        assertTrue(defs.activity("improve")!!.meta)
        assertTrue(defs.activity("rogue") == null)
        assertTrue(defs.errors.any { "メタアクティビティ" in it }, defs.errors.toString())
    }

    @Test
    fun `tool groups restrict available tools`() {
        write("_agents/writer.yaml", "id: writer\npersona: 執筆する\ntools: [explore, edit]")
        val defs = Definitions.load(ws)
        assertEquals(setOf(ToolGroup.EXPLORE, ToolGroup.EDIT), defs.agent("writer")!!.tools)
    }
}
