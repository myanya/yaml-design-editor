package dev.toros.yde.app.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * グラフトラバース評価器（R19-03）のテスト。
 * ノード=論理ID・エッジ=型付き参照のグラフを経路パターンで辿り、
 * 決定的な出力（安定ソート・循環停止・上限と予算の打切り）になることを検証する。
 */
class TraverseTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-traverse")
        write(
            "_schemas/cap.yaml",
            """
            id: cap
            match: "caps/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - key: functions
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: name, type: string, role: title }
                      - key: actor
                        type: array
                        item: { type: ref, relation: uses, scope: { schemaIds: [actor] } }
            """,
        )
        write(
            "_schemas/actor.yaml",
            """
            id: actor
            match: "actors/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
            """,
        )
        write(
            "_schemas/uc.yaml",
            """
            id: uc
            match: "ucs/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
                - key: actorIds
                  type: array
                  item: { type: ref, scope: { schemaIds: [actor] } }
            """,
        )
        write("caps/CAP-1.yaml", "id: CAP-1\nfunctions:\n- id: USC-1\n  name: 機能1\n  actor:\n  - ACT-1")
        write("actors/ACT-1.yaml", "id: ACT-1\nname: アクター1")
        write("ucs/UC-A.yaml", "id: UC-A\nname: ユースA\nactorIds:\n- ACT-1")
        write("ucs/UC-B.yaml", "id: UC-B\nname: ユースB\nactorIds:\n- ACT-1")
        write("ucs/UC-C.yaml", "id: UC-C\nname: ユースC\nactorIds:\n- ACT-1")
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

    private fun run(spec: TraverseSpec): String {
        val ws = Workspace.open(dir)
        return Traverse.run(ws.index, { d -> ws.load(d.value).root }, spec)
    }

    @Test
    fun `out step follows refs with via and relation filters and emits full`() {
        val text = run(
            TraverseSpec(
                start = "USC-1",
                steps = listOf(
                    TraverseStep(emit = "title"),
                    TraverseStep(follow = "out", via = "actor", relation = "uses", emit = "full"),
                ),
            )
        )
        assertTrue("- USC-1 — 機能1" in text, text)
        assertTrue("- ACT-1 — アクター1" in text, text)
        assertTrue("name: アクター1" in text, text)   // full = YAML 全文
        // 一致しない関係種別では辿らない
        val none = run(
            TraverseSpec(
                start = "USC-1",
                steps = listOf(TraverseStep(follow = "out", relation = "derives")),
            )
        )
        assertTrue("（該当なし）" in none, none)
    }

    @Test
    fun `in step filters by schema, sorts stably and caps per hop`() {
        val text = run(
            TraverseSpec(
                start = "ACT-1",
                steps = listOf(
                    TraverseStep(emit = "title"),
                    TraverseStep(follow = "in", schema = "uc", emit = "title", limitPerHop = 2),
                ),
            )
        )
        // schema: uc の絞り込みで USC-1（cap 側の書き手）は除外され、UC-A/B/C の3件中先頭2件だけ表示
        assertTrue("3件（先頭 2 件を表示）" in text, text)
        assertTrue("- UC-A — ユースA" in text, text)
        assertTrue("- UC-B — ユースB" in text, text)
        assertTrue("UC-C" !in text, text)
        assertTrue("USC-1" !in text.substringAfter("== ステップ1"), text)
    }

    @Test
    fun `visited nodes are not re-entered on the way back`() {
        val text = run(
            TraverseSpec(
                start = "USC-1",
                steps = listOf(
                    TraverseStep(emit = "title"),
                    TraverseStep(follow = "out", via = "actor", emit = "title"),
                    TraverseStep(follow = "in", emit = "title"),
                ),
            )
        )
        // ACT-1 の被参照には起点 USC-1 も含まれるが、訪問済みのため step2 には現れない（循環停止）
        val step2 = text.substringAfter("== ステップ2")
        assertTrue("- UC-A" in step2, text)
        assertTrue("USC-1" !in step2, text)
    }

    @Test
    fun `budget truncates deterministically and unknown start is reported`() {
        val text = run(
            TraverseSpec(
                start = "ACT-1",
                steps = listOf(TraverseStep(emit = "title"), TraverseStep(follow = "in", emit = "full")),
                budgetChars = 150,
            )
        )
        assertTrue("…（予算 150 文字で打切り）" in text, text)
        assertTrue("ID「ACT-9」は見つかりません" in run(TraverseSpec("ACT-9", listOf(TraverseStep()))))
    }

    @Test
    fun `steps after the first require follow and parse validates vocabulary`() {
        val e = assertFailsWith<IllegalStateException> {
            run(TraverseSpec("ACT-1", listOf(TraverseStep(), TraverseStep(emit = "title"))))
        }
        assertTrue("follow" in e.message!!, e.message)
        val bad = assertFailsWith<IllegalStateException> {
            TraverseStep.parse(mapOf("follow" to "sideways"), "steps[0]")
        }
        assertTrue("out / in" in bad.message!!, bad.message)
        val step = TraverseStep.parse(mapOf("follow" to "out", "via" to "actor", "emit" to "full", "limitPerHop" to 3), "steps[0]")
        assertEquals("out", step.follow)
        assertEquals("actor", step.via)
        assertEquals(3, step.limitPerHop)
    }
}
