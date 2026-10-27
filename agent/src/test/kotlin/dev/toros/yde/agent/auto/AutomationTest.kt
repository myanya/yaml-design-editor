package dev.toros.yde.agent.auto

import dev.toros.yde.agent.llm.AiProvider
import dev.toros.yde.agent.llm.ChatTurn
import dev.toros.yde.agent.llm.ChatUsage
import dev.toros.yde.agent.llm.ToolCall
import dev.toros.yde.agent.llm.ToolChatSession
import dev.toros.yde.agent.llm.ToolResult
import dev.toros.yde.app.core.Workspace
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
 * 自動化エンジンの結合テスト（R3-01〜R3-05）。
 * tick(now) / processOnce() を直接呼ぶことで、タイマーに依存せず決定的に検証する。
 * LLM応答は台本（FakeSession）に差し替え、ツール実行・適用・記録は本物を通す。
 */
class AutomationTest {

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

    /** 実行ごとに台本を1つ消費するエンジンを作る。 */
    private fun engine(scripts: ArrayDeque<List<ChatTurn>>, dirty: (Path) -> Boolean = { false }): AutomationEngine =
        AutomationEngine(
            dir,
            credentials = {
                AutomationEngine.Credentials(AiProvider.OPENAI, apiKeys = mapOf(AiProvider.OPENAI to "test-key"))
            },
            dirtyChecker = dirty,
            sessionFactory = { _, _, _, _, _ -> FakeSession(scripts.removeFirst()) },
        )

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-automation")
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
        write("docs/a.yaml", "id: DOC-A\ntitle: 文書A")
        write("_agents/editor.yaml", "id: editor\npersona: 正確に編集する。")
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

    private fun drain(e: AutomationEngine) = runBlocking { while (e.processOnce()) { /* キューを消化 */ } }

    // ---------------------------------------------------------------- on-change → 自動適用 → 巻き戻し

    @Test
    fun `on-change triggers auto apply without retriggering itself and revert restores`() {
        write(
            "_activities/fix-title.yaml",
            """
            id: fix-title
            agent: editor
            instruction: タイトルを整える
            approval: auto
            trigger: { type: on-change, match: "docs/**", debounceSeconds: 0 }
            """,
        )
        val scripts = ArrayDeque(listOf(listOf(
            turn(call("apply_patch", """{"file":"docs/a.yaml","ops":[{"op":"set","path":"title","value":"整形済みタイトル"}]}""")),
            turn(call("submit_changeset", """{"summary":"タイトル整形"}""")),
        )))
        val e = engine(scripts)
        e.setEnabled(true)  // ここまでの状態が基準になる

        write("docs/a.yaml", "id: DOC-A\ntitle: 人間が編集したタイトル")
        e.tick(1_000_000)
        assertEquals(listOf("fix-title"), e.queueSnapshot().map { it.activityId })

        drain(e)
        assertTrue("整形済みタイトル" in Files.readString(dir.resolve("docs/a.yaml")))
        assertTrue(e.historySnapshot().any { it.status == "自動適用" })

        // 自動適用による自己変更では再発火しない
        e.tick(2_000_000)
        assertTrue(e.queueSnapshot().isEmpty(), e.queueSnapshot().toString())

        // 実行記録のバックアップから巻き戻せる（R3-04）
        val runFile = Files.list(dir.resolve("_runs")).use { s ->
            s.filter { it.fileName.toString().endsWith(".yaml") && "changeset" !in it.fileName.toString() }.findFirst().get()
        }
        val revert = AutoApply.revert(Workspace.open(dir), runFile)
        assertTrue(revert.ok, revert.messages.toString())
        assertTrue("人間が編集したタイトル" in Files.readString(dir.resolve("docs/a.yaml")))
    }

    // ---------------------------------------------------------------- 承認待ち → 採否 → 連鎖

    @Test
    fun `human approval creates durable pending review and chains after adoption`() {
        write(
            "_activities/review-a.yaml",
            """
            id: review-a
            agent: editor
            instruction: 提案を作る
            approval: human
            chain: { onSuccess: follow-up }
            """,
        )
        write("_activities/follow-up.yaml", "id: follow-up\nagent: editor\ninstruction: 後続処理")
        val scripts = ArrayDeque(listOf(listOf(
            turn(call("apply_patch", """{"file":"docs/a.yaml","ops":[{"op":"set","path":"title","value":"提案タイトル"}]}""")),
            turn(call("submit_changeset", """{"summary":"提案"}""")),
        )))
        val e = engine(scripts)
        e.setEnabled(true)
        e.enqueue("review-a", "テスト起動")
        drain(e)

        val review = e.pendingSnapshot().single()
        assertEquals("review-a", review.activityId)
        assertTrue("提案タイトル" !in Files.readString(dir.resolve("docs/a.yaml")))  // 未適用
        // 耐久性: 変更セットがファイルにも保存される
        assertTrue(Files.list(dir.resolve("_runs")).use { s -> s.anyMatch { it.fileName.toString().endsWith("-changeset.yaml") } })

        // 採用 → 連鎖が発火（onSuccess: follow-up）
        e.completeReview(review, adoptedFiles = listOf("docs/a.yaml"), applied = true)
        assertEquals(listOf("follow-up"), e.queueSnapshot().map { it.activityId })
    }

    // ---------------------------------------------------------------- 出力値による連鎖分岐（R9-04）

    @Test
    fun `chain rules branch on notes before falling back to onSuccess`() {
        write(
            "_activities/triage.yaml",
            """
            id: triage
            agent: editor
            instruction: 点検する
            approval: auto
            chain:
              rules:
                - when: { outcome: success, noteContains: 重複 }
                  then: dedupe
              onSuccess: fallback
            """,
        )
        write("_activities/dedupe.yaml", "id: dedupe\nagent: editor\ninstruction: 重複を統合する")
        write("_activities/fallback.yaml", "id: fallback\nagent: editor\ninstruction: 通常の後続")
        val scripts = ArrayDeque(listOf(listOf(
            turn(call("note", """{"text":"DOC-A に重複の疑いあり"}""")),
            turn(call("apply_patch", """{"file":"docs/a.yaml","ops":[{"op":"set","path":"title","value":"点検済み"}]}""")),
            turn(call("submit_changeset", """{"summary":"点検完了"}""")),
        )))
        val e = engine(scripts)
        e.setEnabled(true)
        e.enqueue("triage", "テスト起動")
        runBlocking { e.processOnce() }

        // メモに「重複」が含まれるため、onSuccess ではなくルールの then へ分岐する
        assertEquals(listOf("dedupe"), e.queueSnapshot().map { it.activityId })
    }

    @Test
    fun `chain rules fall back to onSuccess when no rule matches`() {
        write(
            "_activities/triage2.yaml",
            """
            id: triage2
            agent: editor
            instruction: 点検する
            approval: auto
            chain:
              rules:
                - when: { changedFiles: "issues/**" }
                  then: dedupe2
              onSuccess: fallback2
            """,
        )
        write("_activities/dedupe2.yaml", "id: dedupe2\nagent: editor\ninstruction: 重複を統合する")
        write("_activities/fallback2.yaml", "id: fallback2\nagent: editor\ninstruction: 通常の後続")
        val scripts = ArrayDeque(listOf(listOf(
            turn(call("apply_patch", """{"file":"docs/a.yaml","ops":[{"op":"set","path":"title","value":"点検済み2"}]}""")),
            turn(call("submit_changeset", """{"summary":"点検完了"}""")),
        )))
        val e = engine(scripts)
        e.setEnabled(true)
        e.enqueue("triage2", "テスト起動")
        runBlocking { e.processOnce() }

        // 変更は docs/** のみで issues/** に一致しない → フォールバック
        assertEquals(listOf("fallback2"), e.queueSnapshot().map { it.activityId })
    }

    // ---------------------------------------------------------------- リトライと収束打ち切り（R3-03）

    @Test
    fun `retry feeds back failure and converges on repeated identical failure`() {
        write(
            "_activities/flaky.yaml",
            """
            id: flaky
            agent: editor
            instruction: 何かする
            retry: { maxAttempts: 3 }
            """,
        )
        val scripts = ArrayDeque(listOf(
            listOf(turn(call("abort", """{"reason":"データ不足"}"""))),
            listOf(turn(call("abort", """{"reason":"データ不足"}"""))),
        ))
        val e = engine(scripts)
        e.setEnabled(true)
        e.enqueue("flaky", "テスト起動")
        runBlocking { e.processOnce() }

        // 試行1の失敗理由がフィードバックとして次の試行へ渡る
        val requeued = e.queueSnapshot().single()
        assertEquals(2, requeued.attempt)
        assertTrue("前回（試行 1）は失敗しました" in (requeued.extraInstruction ?: ""))
        assertTrue("データ不足" in (requeued.extraInstruction ?: ""))

        // 試行2も同一の失敗 → 収束しないと判断して打ち切り（試行3は行わない）
        runBlocking { e.processOnce() }
        assertTrue(e.queueSnapshot().isEmpty())
        assertTrue(e.historySnapshot().any { "同一の失敗" in (it.detail ?: "") })
    }

    // ---------------------------------------------------------------- 編集中の降格（R3-04/05）

    @Test
    fun `auto approval demotes to pending review when target is being edited`() {
        write(
            "_activities/auto-edit.yaml",
            """
            id: auto-edit
            agent: editor
            instruction: タイトルを直す
            approval: auto
            """,
        )
        val scripts = ArrayDeque(listOf(listOf(
            turn(call("apply_patch", """{"file":"docs/a.yaml","ops":[{"op":"set","path":"title","value":"自動修正"}]}""")),
            turn(call("submit_changeset", """{"summary":"修正"}""")),
        )))
        val e = engine(scripts, dirty = { it.fileName.toString() == "a.yaml" })
        e.setEnabled(true)
        e.enqueue("auto-edit", "テスト起動")
        drain(e)

        assertTrue("自動修正" !in Files.readString(dir.resolve("docs/a.yaml")))  // 適用されていない
        val review = e.pendingSnapshot().single()
        assertTrue("編集中" in review.reason)
    }

    // ---------------------------------------------------------------- 導出元文書への自動適用の降格（R11-03）

    @Test
    fun `auto approval demotes when changeset touches derive source documents`() {
        // 派生スキーマ（R11-03）の導出元文書への自動適用は、検証ルールが無人で変わるため承認待ちへ降格する
        write(
            "_schemas/record-template.yaml",
            """
            id: record-template
            derive:
              from: "tables/**/*.yaml"
              id: "record-{id}"
              match: "records/{id}/**/*.yaml"
              fields: { source: columns, key: name }
            root:
              type: object
              fields:
                - { key: id, type: id }
            """,
        )
        write("tables/tbl-a.yaml", "id: TBL-A\ncolumns:\n  - { name: col1 }")
        write(
            "_activities/edit-table.yaml",
            """
            id: edit-table
            agent: editor
            instruction: テーブル定義を整える
            approval: auto
            """,
        )
        val scripts = ArrayDeque(listOf(listOf(
            turn(call("apply_patch", """{"file":"tables/tbl-a.yaml","ops":[{"op":"set","path":"columns[0].name","value":"renamed"}]}""")),
            turn(call("submit_changeset", """{"summary":"列名を整えた"}""")),
        )))
        val e = engine(scripts)
        e.setEnabled(true)
        e.enqueue("edit-table", "テスト起動")
        drain(e)

        assertTrue("renamed" !in Files.readString(dir.resolve("tables/tbl-a.yaml")))  // 自動適用されていない
        val review = e.pendingSnapshot().single()
        assertTrue("導出元" in review.reason, review.reason)
    }

    // ---------------------------------------------------------------- 連鎖の循環ガード（R3-02）

    @Test
    fun `chain cycle is detected and stopped`() {
        write("_activities/a.yaml", "id: a\nagent: editor\ninstruction: x\napproval: auto\nchain: { onSuccess: b }")
        write("_activities/b.yaml", "id: b\nagent: editor\ninstruction: y\napproval: auto\nchain: { onSuccess: a }")
        // どちらも「変更なしで提出」= 成功扱い
        val scripts = ArrayDeque(listOf(
            listOf(turn(call("submit_changeset", """{"summary":"aは変更なし"}"""))),
            listOf(turn(call("submit_changeset", """{"summary":"bも変更なし"}"""))),
        ))
        val e = engine(scripts)
        e.setEnabled(true)
        e.enqueue("a", "テスト起動")
        drain(e)

        assertTrue(e.queueSnapshot().isEmpty())
        assertTrue(e.historySnapshot().any { "循環" in (it.detail ?: "") })
    }

    // ---------------------------------------------------------------- スケジュール（R3-01）

    @Test
    fun `schedule fires only after the interval elapses`() {
        write(
            "_activities/nightly.yaml",
            """
            id: nightly
            agent: editor
            instruction: 定期チェック
            trigger: { type: schedule, everyMinutes: 1 }
            """,
        )
        val e = engine(ArrayDeque())
        e.setEnabled(true)
        val t0 = 10_000_000L
        e.tick(t0)                    // 基準時刻の記録（起動直後は発火しない）
        assertTrue(e.queueSnapshot().isEmpty())
        e.tick(t0 + 59_000)
        assertTrue(e.queueSnapshot().isEmpty())
        e.tick(t0 + 61_000)
        assertEquals(listOf("nightly"), e.queueSnapshot().map { it.activityId })
    }

    // ---------------------------------------------------------------- on-invalid（R3-01）

    @Test
    fun `on-invalid fires only when changed files have validation errors`() {
        write(
            "_activities/fix-invalid.yaml",
            """
            id: fix-invalid
            agent: editor
            instruction: 検証エラーを直す
            trigger: { type: on-invalid, match: "docs/**", debounceSeconds: 0 }
            """,
        )
        val e = engine(ArrayDeque())
        e.setEnabled(true)

        // 妥当な変更 → 起動しない
        write("docs/a.yaml", "id: DOC-A\ntitle: まだ妥当")
        e.tick(1_000_000)
        assertTrue(e.queueSnapshot().isEmpty())

        // 必須項目 title を欠く変更 → 起動する
        write("docs/a.yaml", "id: DOC-A")
        e.tick(2_000_000)
        assertEquals(listOf("fix-invalid"), e.queueSnapshot().map { it.activityId })
        assertTrue("検証エラー" in e.queueSnapshot().single().reason)
    }

    // ---------------------------------------------------------------- 停止（R3-04/05）

    @Test
    fun `foreach expands queue per target and continues after item failure`() {
        write("docs/b.yaml", "id: DOC-B\ntitle: 文書B")
        write(
            "_activities/batch.yaml",
            """
            id: batch
            agent: editor
            instruction: 対象を1件処理する
            foreach: { source: ids, idPattern: "DOC-*" }
            """,
        )
        // 1件目は中断・2件目は変更なし提出 → 項目の失敗が後続の項目を塞がないこと
        val scripts = ArrayDeque(listOf(
            listOf(turn(call("abort", """{"reason":"テストのための失敗"}"""))),
            listOf(turn(call("submit_changeset", """{"summary":"変更なし"}"""))),
        ))
        val e = engine(scripts)
        val expansion = e.enqueueForeach("batch")
        assertEquals(2, expansion.enqueued, expansion.toString())
        assertEquals(listOf("DOC-A", "DOC-B"), e.queueSnapshot().map { it.targetKey })
        // 同一対象の重複投入はスキップされる（アクティビティ×対象の重複ガード）
        assertEquals(0, e.enqueueForeach("batch").enqueued)

        drain(e)
        val history = e.historySnapshot()
        assertTrue(history.any { it.status == "失敗" && it.detail?.contains("DOC-A") == true }, history.toString())
        assertTrue(history.any { it.status == "完了" && it.detail?.contains("DOC-B") == true }, history.toString())
    }

    @Test
    fun `foreach preview enqueues only the selected subset`() {
        write("docs/b.yaml", "id: DOC-B\ntitle: 文書B")
        write(
            "_activities/batch.yaml",
            """
            id: batch
            agent: editor
            instruction: 対象を1件処理する
            foreach: { source: ids, idPattern: "DOC-*" }
            """,
        )
        val e = engine(ArrayDeque())
        val preview = e.previewForeach("batch")
        assertEquals(2, preview.items.size, preview.toString())
        assertEquals(2, preview.total)

        // 選択したサブセット（DOC-B のみ）だけが投入される
        val result = e.enqueueForeachItems("batch", preview.items.filter { it.key == "DOC-B" })
        assertEquals(1, result.enqueued)
        assertEquals(listOf("DOC-B"), e.queueSnapshot().map { it.targetKey })
    }

    @Test
    fun `stopAll clears queue and disables triggers`() {
        write("_activities/x.yaml", "id: x\nagent: editor\ninstruction: x")
        write("_activities/y.yaml", "id: y\nagent: editor\ninstruction: y")
        val e = engine(ArrayDeque())
        e.setEnabled(true)
        e.enqueue("x", "t1")
        e.enqueue("y", "t2")
        assertEquals(2, e.queueSnapshot().size)

        e.stopAll()
        assertTrue(e.queueSnapshot().isEmpty())
        assertFalse(e.enabled)
        // 停止中はトリガも発火しない
        write("docs/a.yaml", "id: DOC-A\ntitle: 変更")
        e.tick(9_000_000)
        assertTrue(e.queueSnapshot().isEmpty())
    }
}
