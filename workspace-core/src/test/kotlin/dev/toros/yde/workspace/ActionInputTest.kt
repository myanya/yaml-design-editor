package dev.toros.yde.workspace

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 公開サンプルと独立した、フォーム準備のカーネル契約。 */
class ActionInputTest {
    private val now = LocalDateTime.of(2026, 10, 25, 23, 59)
    private val schema = """
        id: items
        match: list.yaml
        entity: {type: item, idPattern: 'I-*', defines: true}
        root:
          type: array
          item:
            type: object
            fields:
              - {key: id, type: id}
              - {key: name, type: string}
        actions:
          - id: edit
            target: {entity: item}
            params:
              - {key: name, type: string, default: 既定}
              - {key: note, type: string}
              - {key: date, type: date, required: true}
              - {key: time, type: string}
            input:
              submit: changed
              initial:
                name: {${'$'}copy: name, ${'$'}of: self, ${'$'}in: {schemaId: items}}
                note: {${'$'}copy: note, ${'$'}of: self, ${'$'}in: {schemaId: detail}}
                date: {${'$'}copy: today, ${'$'}of: context}
                time: {${'$'}copy: now, ${'$'}of: context}
            do:
              - set: {of: self, in: {schemaId: items}, path: name, value: ${'$'}name}
    """.trimIndent()

    private fun session(def: String = schema) = WorkspaceSession(InMemoryDocumentStore(mapOf(
        "_schemas/items.yaml" to def,
        "_schemas/detail.yaml" to """
            id: detail
            match: 'detail/*.yaml'
            entity: {type: item, idPattern: 'I-*'}
            root:
              type: object
              fields:
                - {key: id, type: id}
                - {key: note, type: string}
        """.trimIndent(),
        "list.yaml" to "- {id: I-1, name: 一覧の現在値}\n- {id: I-2, name: 個票なし}\n",
        "detail/I-1.yaml" to "id: I-1\nnote: 詳細の現在値\n",
    )))

    @Test
    fun `出現を指定して現在値と日時を読み明示指定を優先する`() {
        val s = session()
        assertEquals(emptyList(), s.schemaErrors)
        val request = ActionRequest("edit", "I-1")
        val prepared = ActionPlanner.prepareInput(s, request, now)
        assertEquals(mapOf("name" to "一覧の現在値", "note" to "詳細の現在値", "date" to "2026-10-25", "time" to "2026-10-25T23:59"), prepared.values)
        assertEquals(prepared, ActionPlanner.prepareInput(s, request, now))
        assertEquals("指定値", ActionPlanner.prepareInput(s, request.copy(params = mapOf("name" to "指定値")), now).values["name"])
        assertEquals("2026-10-26", ActionPlanner.prepareInput(s, request, now.plusDays(1)).values["date"])
        assertFalse(ActionPlanner.prepareInput(s, request.copy(target = "I-2"), now).values.containsKey("note"))
        assertEquals("既定", ActionPlanner.prepareInput(s, request.copy(target = null), now).values["name"])
        assertFailsWith<ActionFailure> { ActionPlanner.prepareInput(s, request.copy(target = "I-9"), now) }
        assertNull(s.peekUndo())
        assertTrue(s.dirtyDocuments().isEmpty())
    }

    @Test
    fun `初期設定は実行時の暗黙の補完にせずCLIの必須入力を保つ`() {
        val s = session()
        assertFailsWith<ActionFailure> { ActionPlanner.plan(s, ActionRequest("edit", "I-1"), now) }
        val prepared = ActionPlanner.prepareInput(s, ActionRequest("edit", "I-1"), now)
        assertFalse(ActionPlanner.plan(s, ActionRequest("edit", "I-1", prepared.values), now).isEmpty)
    }

    @Test
    fun `初期値の宣言漏れと未知の束縛と送信方式は読込時に指摘する`() {
        listOf(
            schema.replace("submit: changed", "submit: unknown"),
            schema.replace("initial:\n        name:", "initial:\n        missing:"),
            schema.replace("${'$'}of: self", "${'$'}of: other"),
            schema.replace("target: {entity: item}", "target: {entity: item, bind: context}"),
            schema.replace("submit: changed", "unknown: true"),
            schema.replace("${'$'}copy: today, ${'$'}of: context", "${'$'}copy: today, ${'$'}of: typo"),
        ).forEach { assertTrue(session(it).schemaErrors.isNotEmpty(), it) }
    }
}
