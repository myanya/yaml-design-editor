package dev.toros.yde.workspace

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 前提の名前（`require[].id`）: 断りの種類を指す名前で、操作の中で 1 つ。判定には使わない（書いても書かなくても同じに断る）。
 * 設計書のエラー定義とメッセージのキー（`操作ID.前提ID`）が、この名前から決まる。
 */
class ActionRequireIdTest {
    private val now = LocalDateTime.of(2026, 11, 15, 10, 0)

    /** require は前提の並び（字下げなしで書く。ここで操作の require の下へ字下げする）。 */
    private fun schema(require: String) = """
        id: task
        match: "tasks/*.yaml"
        root:
          type: object
          entity: { type: task, defines: true }
          fields:
            - { key: id, type: id, required: true }
            - { key: done, type: boolean }
            - { key: memo, type: string }
        actions:
          - id: note-task
            target: { entity: task }
            params: [{ key: memo, type: string, required: true }]
            require:
        REQUIRE
            do: [{ set: { path: memo, value: ${'$'}memo, of: self } }]
    """.trimIndent().replace("REQUIRE", require.trimIndent().prependIndent("      "))

    private fun session(require: String) = WorkspaceSession(InMemoryDocumentStore(mapOf(
        "_schemas/task.yaml" to schema(require),
        "tasks/T-1.yaml" to "id: T-1\ndone: true\n",
    )))

    private val open = """
        - id: open
          match: [{ node: { entity: task, bind: t } }]
          where: [{ of: t, path: done, equals: false }]
          expect: exists
          message: "{id} は済んでいます"
    """.trimIndent()

    @Test
    fun `前提の名前を読み 判定は名前に関係なく同じに断る`() {
        val s = session(open + "\n- match: [{ node: { entity: task, bind: t } }]\n  expect: exists")
        assertEquals(emptyList(), s.schemaErrors)
        val action = s.schemas.flatMap { it.actions }.single()
        assertEquals(listOf("open", null), action.require.map { it.id })
        val refused = assertFailsWith<ActionFailure> { ActionPlanner.plan(s, ActionRequest("note-task", "T-1", mapOf("memo" to "x")), now) }
        assertTrue("T-1 は済んでいます" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `前提の名前の重なりと書き方の誤りは定義の誤り`() {
        val twice = session(open + "\n" + open).schemaErrors
        assertTrue(twice.any { "前提の ID「open」が重複しています" in it }, twice.toString())
        val bad = session(open.replace("id: open", "id: 1st")).schemaErrors
        assertTrue(bad.any { "前提の ID は英字で始まる" in it }, bad.toString())
    }
}
