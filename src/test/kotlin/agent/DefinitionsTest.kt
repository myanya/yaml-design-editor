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
        assertTrue(activity.contextItems.isEmpty())            // 空既定（P19: カタログも明示宣言制）
    }

    // ---------------------------------------------------------------- コンテキストレシピ（R19-02）

    @Test
    fun `context items are parsed with kind-specific keys`() {
        write("_agents/editor.yaml", "id: editor\npersona: 正確に。")
        write(
            "_activities/recipe.yaml",
            """
            id: recipe
            agent: editor
            instruction: x
            context:
              items:
                - kind: catalog
                  match: "docs/*"
                - kind: view
                  view: usecase-list
                  rows: "{target}"
                  columns: [機能名, 機能説明]
                  when: no-target
                  budgetChars: 4000
                - kind: doc
                  file: "docs/{id}.yaml"
                  path: title
                - kind: related
                  id: "{target}"
                  when: has-target
                - kind: sample
                  schema: usecase
                  count: 2
                - kind: phase
            """,
        )
        val defs = Definitions.load(ws)
        assertTrue(defs.errors.isEmpty(), defs.errors.toString())
        val items = defs.activity("recipe")!!.contextItems
        assertEquals(listOf("catalog", "view", "doc", "related", "sample", "phase"), items.map { it.kind })
        assertEquals("docs/*", items[0].match)
        assertEquals(ContextWhen.ALWAYS, items[0].condition)
        assertEquals(ContextWhen.NO_TARGET, items[1].condition)
        assertEquals(4000, items[1].budgetChars)
        assertEquals(listOf("機能名", "機能説明"), items[1].columns)
        assertEquals("docs/{id}.yaml", items[2].file)
        assertEquals(ContextWhen.HAS_TARGET, items[3].condition)
        assertEquals(2, items[4].count)
    }

    @Test
    fun `entity context item requires id and rejects unknown keys`() {
        write("_agents/editor.yaml", "id: editor\npersona: 正確に。")
        write(
            "_activities/entity.yaml",
            """
            id: entity-act
            agent: editor
            instruction: x
            context:
              items:
                - kind: entity
                  id: "{target}"
                  budgetChars: 4000
            """,
        )
        write("_activities/entity-bad.yaml", "id: entity-bad\nagent: editor\ninstruction: x\ncontext:\n  items:\n    - kind: entity")
        write("_activities/entity-bad2.yaml", "id: entity-bad2\nagent: editor\ninstruction: x\ncontext:\n  items:\n    - kind: entity\n      id: X\n      rows: \"Y-*\"")
        val defs = Definitions.load(ws)
        val item = defs.activity("entity-act")!!.contextItems.single()
        assertEquals("entity", item.kind)
        assertEquals("{target}", item.id)
        assertEquals(4000, item.budgetChars)
        assertTrue(defs.errors.any { "entity-bad" in it && "id（論理ID）" in it }, defs.errors.toString())
        assertTrue(defs.errors.any { "entity-bad2" in it && "rows" in it }, defs.errors.toString())
    }

    @Test
    fun `legacy context vocabulary is a definition error with migration hint`() {
        write("_agents/editor.yaml", "id: editor\npersona: 正確に。")
        write("_activities/legacy.yaml", "id: legacy\nagent: editor\ninstruction: x\ncontext:\n  views: [usecase-list]")
        write("_activities/legacy2.yaml", "id: legacy2\nagent: editor\ninstruction: x\ncontext:\n  include: [catalog]")
        val defs = Definitions.load(ws)
        assertEquals(2, defs.errors.count { "廃止" in it && "context.items" in it }, defs.errors.toString())
    }

    @Test
    fun `context item errors are reported per kind`() {
        write("_agents/editor.yaml", "id: editor\npersona: 正確に。")
        write("_activities/bad1.yaml", "id: bad1\nagent: editor\ninstruction: x\ncontext:\n  items:\n    - kind: catalog\n      rows: \"X-*\"")
        write("_activities/bad2.yaml", "id: bad2\nagent: editor\ninstruction: x\ncontext:\n  items:\n    - kind: view")
        write("_activities/bad3.yaml", "id: bad3\nagent: editor\ninstruction: x\ncontext:\n  items:\n    - kind: nope")
        val defs = Definitions.load(ws)
        assertTrue(defs.errors.any { "bad1" in it && "rows" in it }, defs.errors.toString())
        assertTrue(defs.errors.any { "bad2" in it && "view（ビューID）" in it }, defs.errors.toString())
        assertTrue(defs.errors.any { "bad3" in it && "未知の kind" in it }, defs.errors.toString())
    }

    @Test
    fun `graph context item parses steps and validates follow requirement`() {
        write("_agents/editor.yaml", "id: editor\npersona: 正確に。")
        write(
            "_activities/graph.yaml",
            """
            id: graph-act
            agent: editor
            instruction: x
            context:
              items:
                - kind: graph
                  start: "{target}"
                  steps:
                    - emit: full
                    - { follow: out, via: actor, emit: full, limitPerHop: 5 }
                  when: has-target
            """,
        )
        write("_activities/graph-bad.yaml", "id: graph-bad\nagent: editor\ninstruction: x\ncontext:\n  items:\n    - kind: graph\n      start: X\n      steps:\n        - emit: full\n        - emit: title")
        write("_activities/graph-nostart.yaml", "id: graph-nostart\nagent: editor\ninstruction: x\ncontext:\n  items:\n    - kind: graph")
        val defs = Definitions.load(ws)
        val item = defs.activity("graph-act")!!.contextItems.single()
        assertEquals("graph", item.kind)
        assertEquals("{target}", item.start)
        assertEquals(2, item.steps.size)
        assertEquals("actor", item.steps[1].via)
        assertEquals(5, item.steps[1].limitPerHop)
        assertEquals(ContextWhen.HAS_TARGET, item.condition)
        assertTrue(defs.errors.any { "graph-bad" in it && "follow" in it }, defs.errors.toString())
        assertTrue(defs.errors.any { "graph-nostart" in it && "start" in it }, defs.errors.toString())
    }

    @Test
    fun `missing agent reference is reported`() {
        write("_activities/a.yaml", "id: a\nagent: ghost\ninstruction: x")
        val defs = Definitions.load(ws)
        assertTrue(defs.errors.any { "ghost" in it }, defs.errors.toString())
    }

    // ---------------------------------------------------------------- 出力値による連鎖分岐（R9-04）

    @Test
    fun `chain rules are parsed and unknown targets are reported`() {
        write("_agents/editor.yaml", "id: editor\npersona: 正確に。")
        write(
            "_activities/branching.yaml",
            """
            id: branching
            agent: editor
            instruction: 点検する
            chain:
              rules:
                - when: { outcome: success, changedFiles: "issues/**", minChangedFiles: 2 }
                  then: ghost-next
              onFailure: also-ghost
            """,
        )
        val defs = Definitions.load(ws)
        val rule = defs.activity("branching")!!.chain.rules.single()
        assertEquals("ghost-next", rule.then)
        assertEquals("success", rule.outcome)
        assertEquals("issues/**", rule.changedFiles)
        assertEquals(2, rule.minChangedFiles)
        // ルールの then も onFailure と同様に存在チェックされる
        assertTrue(defs.errors.any { "ghost-next" in it }, defs.errors.toString())
        assertTrue(defs.errors.any { "also-ghost" in it }, defs.errors.toString())
    }

    @Test
    fun `selectNext evaluates rules in order with AND conditions and falls back`() {
        val chain = ChainDef(
            onSuccess = "ok",
            onFailure = "ng",
            rules = listOf(
                ChainRule(then = "big-change", outcome = "success", changedFiles = "issues/**", minChangedFiles = 2),
                ChainRule(then = "flagged", noteContains = "要確認"),
            ),
        )
        // 条件AND: ファイル1件では minChangedFiles=2 を満たさず、次のルールへ
        assertEquals("flagged", chain.selectNext(true, listOf("issues/a.yaml"), "要確認: 重複"))
        // 両条件成立なら先頭のルールが勝つ
        assertEquals("big-change", chain.selectNext(true, listOf("issues/a.yaml", "issues/b.yaml"), ""))
        // どのルールにも一致しない → 終わり方でフォールバック
        assertEquals("ok", chain.selectNext(true, listOf("docs/a.yaml"), ""))
        assertEquals("ng", chain.selectNext(false, emptyList(), ""))
        // outcome: success のルールは失敗時に評価されない
        assertEquals("flagged", chain.selectNext(false, listOf("issues/a.yaml", "issues/b.yaml"), "要確認"))
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

    @Test
    fun `foreach is parsed with defaults and template`() {
        write("_agents/worker.yaml", "id: worker\npersona: 作業する。")
        write(
            "_activities/batch.yaml",
            """
            id: batch
            agent: worker
            instruction: 対象を1件処理する。
            foreach: { source: ids, idPattern: "DOC-*", schemaIds: [doc], as: "対象: {id}", limit: 5 }
            """,
        )
        val defs = Definitions.load(ws)
        assertTrue(defs.errors.isEmpty(), defs.errors.toString())
        val foreach = defs.activity("batch")!!.foreach!!
        assertEquals(ForeachSource.IDS, foreach.source)
        assertEquals("DOC-*", foreach.idPattern)
        assertEquals(listOf("doc"), foreach.schemaIds)
        assertEquals("対象: {id}", foreach.asTemplate)
        assertEquals(5, foreach.limit)
    }

    @Test
    fun `foreach definition errors are reported`() {
        write("_agents/worker.yaml", "id: worker\npersona: 作業する。")
        // 列挙元に使えないキー（source: files に idPattern）
        write("_activities/b1.yaml", """id: b1
agent: worker
instruction: x
foreach: { source: files, match: "docs/**", idPattern: "X-*" }""")
        // source: view なのに view 未指定
        write("_activities/b2.yaml", "id: b2\nagent: worker\ninstruction: x\nforeach: { source: view }")
        // trigger: manual 以外との併用は不可
        write("_activities/b3.yaml", """id: b3
agent: worker
instruction: x
foreach: { source: issues }
trigger: { type: schedule, everyMinutes: 5 }""")
        val defs = Definitions.load(ws)
        assertTrue(defs.errors.any { "b1" in it && "idPattern" in it }, defs.errors.toString())
        assertTrue(defs.errors.any { "b2" in it && "view" in it }, defs.errors.toString())
        assertTrue(defs.errors.any { "b3" in it && "manual" in it }, defs.errors.toString())
    }
}
