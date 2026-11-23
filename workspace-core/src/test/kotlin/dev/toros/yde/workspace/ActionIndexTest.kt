package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.ParamIndex
import dev.toros.yde.semantic.schema.StepPaths
import dev.toros.yde.yaml.patch.PatchOp
import dev.toros.yde.yaml.model.pretty
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 手のパスの「受け取った値の番号」（`comments[$row]`。UX-381）: ID を持たない並びの 1 件を、操作が受け取った番号で指す。
 * - 番号は 0 から数え、`comments[0]` と同じ場所を指す（set は要素の中の項目へも書ける・delete はその 1 件を消す）
 * - 範囲の外・整数でない値・並びでない場所は、何も書かずに止める
 * - 名前は params に宣言する（宣言の無い名前・書き方の誤りは定義の誤り）
 */
class ActionIndexTest {
    private val d = "$"
    private val now = LocalDateTime.of(2026, 11, 23, 9, 0)

    private fun schema(extra: String = "") = """
        id: memo
        match: "memos/*.yaml"
        entity: { type: memo, idPattern: "M-*", defines: true }
        root:
          type: object
          fields:
            - { key: id, type: id, required: true }
            - key: comments
              type: array
              item:
                type: object
                fields:
                  - { key: date, type: date }
                  - { key: body, type: string }
        actions:
          - id: edit-comment
            label: コメントを編集する
            target: { entity: memo }
            params:
              - { key: row, label: 番号, type: int, required: true }
              - { key: body, label: 本文, type: string, required: true }
            do:
              - set: { path: "comments[${d}row].body", value: ${d}body }
          - id: review-comment
            label: コメントを見直す
            target: { entity: memo }
            input:
              submit: changed
              initial:
                body: { ${d}copy: "comments[${d}row].body", ${d}of: self }
            params:
              - { key: row, label: 番号, type: int, required: true }
              - { key: body, label: 本文, type: string }
            do:
              - when: [ { path: body, exists: true } ]
                set: { path: "comments[${d}row].body", value: ${d}body }
          - id: delete-comment
            label: コメントを削除する
            target: { entity: memo }
            params:
              - { key: row, label: 番号, type: int, required: true }
            do:
              - delete: { path: "comments[${d}row]" }
    """.trimIndent() + "\n" + extra

    private fun session(schemaText: String = schema()) = WorkspaceSession(InMemoryDocumentStore(mapOf(
        "_schemas/memo.yaml" to schemaText,
        "memos/M-1.yaml" to "id: M-1\ncomments:\n  - { date: 2026-11-01, body: 一つ目 }\n  - { date: 2026-11-02, body: 二つ目 }\n  - { date: 2026-11-03, body: 三つ目 }\n",
        "memos/M-2.yaml" to "id: M-2\n",
    )))

    private fun plan(s: WorkspaceSession, name: String, target: String, vararg params: Pair<String, Any?>): ActionPlan =
        ActionPlanner.plan(s, ActionRequest(name, target, params.toMap()), now)

    @Test
    fun `受け取った番号で 並びの 1 件の項目を書き換え 1 件を消す`() {
        val s = session()
        assertEquals(emptyList(), s.schemaErrors)
        val edit = plan(s, "edit-comment", "M-1", "row" to 1, "body" to "直した")
        val set = edit.changeSet.changes.single().ops.single() as PatchOp.Set
        assertEquals("comments[1].body", set.path.pretty())
        assertTrue("comments[1].body ← 直した" in edit.summary.single(), edit.summary.toString())
        // 番号は文字で渡しても（CLI の --param）同じ場所
        val delete = plan(s, "delete-comment", "M-1", "row" to "2")
        assertEquals("comments[2]", (delete.changeSet.changes.single().ops.single() as PatchOp.Delete).path.pretty())
        // 既存の適用器をそのまま通り、指した 1 件だけが変わる
        assertTrue(ChangeSetApplier.apply(s, edit.changeSet, requireValid = true, now = now).ok)
        assertTrue(ChangeSetApplier.apply(s, plan(s, "delete-comment", "M-1", "row" to 0).changeSet, requireValid = true, now = now).ok)
        val text = dev.toros.yde.yaml.DefaultYamlCodec.dump(s.snapshot.documents.getValue(dev.toros.yde.yaml.model.DocumentId("memos/M-1.yaml")).document)
        assertTrue("一つ目" !in text && "直した" in text && "三つ目" in text, text)
    }

    @Test
    fun `初期値のひな型も 受け取った番号の 1 件を読む`() {
        val s = session()
        assertEquals(emptyList(), s.schemaErrors)
        // 行から始めると番号が先に渡り、その行の今の値が初期値になる
        val prepared = ActionPlanner.prepareInput(s, ActionRequest("review-comment", "M-1", mapOf("row" to 1)), now)
        assertEquals("二つ目", prepared.values["body"])
        assertEquals(1, prepared.values["row"])
        // 番号がまだ無い・その番号の要素が無いときは、初期値を作らない（誤りにしない）
        assertEquals(null, ActionPlanner.prepareInput(s, ActionRequest("review-comment", "M-1"), now).values["body"])
        assertEquals(null, ActionPlanner.prepareInput(s, ActionRequest("review-comment", "M-1", mapOf("row" to 9)), now).values["body"])
    }

    @Test
    fun `範囲の外と 整数でない値と 並びでない場所は 何も書かずに止める`() {
        val s = session()
        val beyond = assertFailsWith<ActionFailure> { plan(s, "delete-comment", "M-1", "row" to 3) }
        assertTrue("3 番の要素はありません（3 件。番号は 0 から数えます）" in beyond.message.orEmpty(), beyond.message)
        assertFailsWith<ActionFailure> { plan(s, "delete-comment", "M-1", "row" to -1) }
        val text = assertFailsWith<ActionFailure> { plan(s, "delete-comment", "M-1", "row" to "二番目") }
        assertTrue("整数" in text.message.orEmpty(), text.message)
        // 並びがまだ無い要素（書き足すのは insert の役目）
        val none = assertFailsWith<ActionFailure> { plan(s, "edit-comment", "M-2", "row" to 0, "body" to "x") }
        assertTrue("並びではありません" in none.message.orEmpty(), none.message)
    }

    @Test
    fun `番号の名前は params に宣言し 書き方の誤りは定義の誤りになる`() {
        val undeclared = session(schema("""
          - id: bad
            target: { entity: memo }
            do:
              - delete: { path: "comments[${d}line]" }
        """.trimIndent().prependIndent("  ") + "\n"))
        assertTrue(undeclared.schemaErrors.any { "受け取る値「${d}line」が params に宣言されていません" in it.toString() }, undeclared.schemaErrors.toString())
        val malformed = session(schema("""
          - id: bad
            target: { entity: memo }
            params:
              - { key: line, type: int }
            do:
              - delete: { path: "comments[${d}line + 1]" }
        """.trimIndent().prependIndent("  ") + "\n"))
        assertTrue(malformed.schemaErrors.any { "パスとして読めません" in it.toString() }, malformed.schemaErrors.toString())
    }

    @Test
    fun `パスの読み書きは 受け取った値の番号を区切りとして保つ`() {
        val path = StepPaths.parse("lines[${d}line].fees[0].amount")
        assertEquals(listOf("lines", ParamIndex("line"), "fees", 0, "amount"), path)
        assertEquals("lines[${d}line].fees[0].amount", StepPaths.pretty(path))
        assertEquals(listOf<Any>("comments", 2), StepPaths.parse("comments[2]"))
    }
}
