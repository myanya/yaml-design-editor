package dev.toros.yde.workspace

import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 配列を受け取る条件（`includes` / `includesAny`）: 項目の値（配列）をそのまま受け取り、その要素に値がある（ADR-0010 の
 * 「値は項」を条件の側へ）。where と check が同じ演算子・同じ判定を共有し、要素の型が数なら数として比べる。
 */
class ArrayConditionsTest {
    private val files = mapOf(
        "_schemas/task.yaml" to """
            id: task
            match: "tasks/*.yaml"
            root:
              type: object
              entity: { type: task, defines: true }
              fields:
                - { key: id, type: id, required: true }
                - { key: tags, type: array, item: { type: string } }
                - { key: scores, type: array, item: { type: int } }
              constraints:
                # 型の検査（where）: 急ぎのタグを持つ要素は、メモの項目が要る（ここでは指摘を出すだけ）
                - id: urgent
                  where: [{ path: tags, includes: urgent }]
                  expect: absent
                  level: warning
                  message: "急ぎ"
            constraints:
              # パターンの where（includesAny）: 並びのどれかを持つ
              - id: any-of
                match: [{ node: { entity: task, bind: t } }]
                where: [{ of: t, path: tags, includesAny: [docs, review] }]
                expect: absent
                level: warning
                message: "{id} は文書か確認のタグ"
              # check（includes）: 点数に 1 を持つこと（int の要素は数として比べる。1.0 も同じ）
              - id: has-one
                match: [{ node: { entity: task, bind: t } }]
                check: [{ includes: [{ of: t, path: scores }, 1] }]
                level: warning
                message: "{id} は点数に 1 がありません"
        """.trimIndent(),
        "tasks/T-1.yaml" to "id: T-1\ntags: [urgent, docs]\nscores: [1.0, 3]\n",
        "tasks/T-2.yaml" to "id: T-2\ntags: [review]\nscores: [2]\n",
        "tasks/T-3.yaml" to "id: T-3\ntags: [other]\nscores: [1]\n",
        "tasks/T-4.yaml" to "id: T-4\n",
    )

    @Test
    fun `where と check で配列の要素を受け取り 数の要素は数として比べ 配列が無ければ判定しない`() {
        val ws = WorkspaceReader.load(InMemoryDocumentStore(files))
        assertEquals(emptyList(), ws.schemaErrors)
        val issues = WorkspaceCheckRunner.validateAll(ws).flatMap { (doc, v) -> v.issues.map { doc.value.substringAfter("tasks/").substringBefore(".yaml") to it.message } }
        fun of(word: String) = issues.filter { word in it.second }.map { it.first }.sorted()
        // 型の検査: 急ぎのタグを持つのは T-1 だけ
        assertEquals(listOf("T-1"), of("急ぎ"))
        // includesAny: docs か review を持つのは T-1・T-2
        assertEquals(listOf("T-1", "T-2"), of("文書か確認のタグ"))
        // includes（check）: 1 を持たないのは T-2（T-1 の 1.0 は数として 1。T-4 は点数が無いので判定しない）
        assertEquals(listOf("T-2"), of("点数に 1 がありません"))
    }

    @Test
    fun `includesAny は値 1 つでも並びでも書け includes は値 1 つ`() {
        val single = files + ("_schemas/task.yaml" to files.getValue("_schemas/task.yaml").replace("includesAny: [docs, review]", "includesAny: review"))
        val ws = WorkspaceReader.load(InMemoryDocumentStore(single))
        assertEquals(emptyList(), ws.schemaErrors)
        val wrong = files + ("_schemas/task.yaml" to files.getValue("_schemas/task.yaml").replace("includes: urgent", "includes: [urgent, docs]"))
        assertTrue(WorkspaceReader.load(InMemoryDocumentStore(wrong)).schemaErrors.any { "includes" in it }, "includes は値 1 つ")
        assertTrue(DocumentId("x").value == "x")
    }
}
