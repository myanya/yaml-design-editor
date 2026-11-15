package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * まとめる（`$group`）: 配列の要素をキーが同じものごとにまとめ、まとまり {key, items} ごとに組み立てる。
 * 生成で「同じテーブルへの書き込みを 1 つの文にまとめる」ために使う（XSLT の for-each-group・SQL の GROUP BY）。
 */
class TransformGroupTest {
    private fun run(build: String, input: String, rules: String = ""): Any? {
        val def = TransformDef.parse(YamlIO.parseToRaw("id: t\nbuild:\n" + build.prependIndent("  ") + (if (rules.isEmpty()) "" else "\nrules:\n" + rules.prependIndent("  "))))
        return YamlIO.toAny(SemanticTransform.runAt(def, SemanticTransform.TemplatePlace(YamlIO.parseFirst(input)!!, null, emptyList())).term!!)
    }

    private val writes = """
        writes:
          - { table: todo, column: completed_on, value: ${'$'}date }
          - { table: todo, column: result, value: ${'$'}result }
          - { table: todo_history, column: to, value: CLOSED }
          - { table: todo, column: status, value: CLOSED }
          - { column: note }
    """.trimIndent()

    @Test
    fun `キーが同じ要素をまとめ 初めて出た順に並べる`() {
        val out = run(
            """
            statements:
              ${'$'}group: writes
              ${'$'}by: { ${'$'}copy: table }
              ${'$'}build:
                table: { ${'$'}copy: key }
                position: ${'$'}position
                set: { ${'$'}each: items, ${'$'}build: { ${'$'}copy: column } }
            """.trimIndent(),
            writes,
        )
        assertEquals(
            mapOf(
                "statements" to listOf(
                    mapOf("table" to "todo", "position" to 1, "set" to listOf("completed_on", "result", "status")),
                    mapOf("table" to "todo_history", "position" to 2, "set" to listOf("to")),
                ),
            ),
            out,
            "キーの無い要素（table の無い note）は、どのまとまりにも入らない",
        )
    }

    @Test
    fun `計算した配列をまとめ 構造のキーと規則で組み立てる`() {
        val out = run(
            """
            ${'$'}group: { ${'$'}concat: [{ ${'$'}copy: writes }, [{ table: todo, column: updated_on }]] }
            ${'$'}by: { table: { ${'$'}copy: table }, kind: update }
            ${'$'}apply: statement
            """.trimIndent(),
            writes,
            rules = """
            - id: statement
              build:
                sql: { ${'$'}format: "UPDATE {key.table}" }
                columns: { ${'$'}each: items, ${'$'}build: { ${'$'}copy: column } }
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                mapOf("sql" to "UPDATE todo", "columns" to listOf("completed_on", "result", "status", "updated_on")),
                mapOf("sql" to "UPDATE todo_history", "columns" to listOf("to")),
                // キーがマップなら、中の値が欠けても（table が無い）キーはある: {kind: update} のまとまりになる
                mapOf("columns" to listOf("note")),
            ),
            out,
        )
    }

    @Test
    fun `キーと値の並びからマップを作る 同じキーは後の値で置き換える`() {
        val out = run(
            """
            ${'$'}entries:
              ${'$'}concat:
                - [{ key: 項目, value: status }, { key: register-todo, value: '' }, { key: start-todo, value: '' }]
                - ${'$'}each: writes
                  ${'$'}build: { key: { ${'$'}copy: op }, value: { ${'$'}copy: mark } }
            """.trimIndent(),
            "writes: [{ op: start-todo, mark: U }, { op: register-todo, mark: C }, { mark: X }, { op: cancel-todo }]",
        )
        assertEquals(mapOf("項目" to "status", "register-todo" to "C", "start-todo" to "U"), out, "キーの無い項目・値の無い項目は飛ばし、位置は初めのところ")
        assertEquals(listOf("項目", "register-todo", "start-todo"), (out as Map<*, *>).keys.toList())
    }

    @Test
    fun `条件の includes は場所の配列の要素で比べる`() {
        val out = run(
            """
            ${'$'}each: actions
            ${'$'}build:
              ${'$'}when: [{ path: creates, includes: todo }]
              ${'$'}then: { ${'$'}copy: id }
            """.trimIndent(),
            "actions: [{ id: register-todo, creates: [todo] }, { id: add-tag, creates: [tag] }, { id: start-todo }]",
        )
        assertEquals(listOf("register-todo"), out, "配列でない（無い）場所は判定できず、選ばない")
    }

    @Test
    fun `配列でなければ無い 書き方の誤りは定義の誤り`() {
        val out = run("x: { ${'$'}group: missing, ${'$'}by: { ${'$'}copy: table }, ${'$'}build: { ${'$'}copy: key } }\ny: 1", writes)
        assertEquals(mapOf("y" to 1), out, "まとめる配列が無ければ、その項目ごと落ちる")
        val noBy = assertFailsWith<IllegalStateException> { run("${'$'}group: writes\n${'$'}build: { ${'$'}copy: key }", writes) }
        assertTrue("\$by" in noBy.message!!, noBy.message)
        val noBody = assertFailsWith<IllegalStateException> { run("${'$'}group: writes\n${'$'}by: { ${'$'}copy: table }", writes) }
        assertTrue("\$build" in noBody.message!!, noBody.message)
        val ofWithTemplate = assertFailsWith<IllegalStateException> {
            run("${'$'}group: { ${'$'}copy: writes }\n${'$'}of: x\n${'$'}by: { ${'$'}copy: table }\n${'$'}build: { ${'$'}copy: key }", writes)
        }
        assertTrue("ひな型を書くとき" in ofWithTemplate.message!!, ofWithTemplate.message)
    }
}
