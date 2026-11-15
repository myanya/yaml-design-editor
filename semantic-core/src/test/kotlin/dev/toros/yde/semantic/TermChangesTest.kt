package dev.toros.yde.semantic

import dev.toros.yde.yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 項の変更（段の規則と補正）: 並びの要素を選び、置き換え・中へ足し・後ろへ足す。値は変換のひな型で、足したものに出どころが付く。
 */
class TermChangesTest {
    private val term = YamlIO.parseToRaw(
        """
        entities:
          - id: todo
            fields:
              - { key: title }
              - { key: updated_on, auto: updated }
          - id: tag
            fields:
              - { key: name }
        actions:
          - id: edit
            effects:
              - { op: set, path: title, entity: todo }
              - { op: set, path: updated_on, entity: todo, auto: updated }
        """.trimIndent(),
    ).let { YamlIO.fromAny(it) }

    private val origin = mapOf("stage" to "basic", "by" to "r")

    private fun apply(text: String): TermChanges.Applied = TermChanges.apply(term, TermChanges.parse(YamlIO.parseToRaw(text.trimIndent()), "r.yaml"), origin)

    @Suppress("UNCHECKED_CAST")
    private fun TermChanges.Applied.raw(): Map<String, Any?> = YamlIO.toAny(term) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.list(key: String): List<Map<String, Any?>> = this[key] as List<Map<String, Any?>>

    @Test
    fun `後ろへ足すは選んだ要素の直後に 入れ子の並びも each で広げる`() {
        val r = apply(
            """
            id: r
            stage: basic
            kind: rule
            changes:
              - each: "entities[*].fields"
                where: [{ path: auto, equals: updated }]
                after: [{ key: updated_by }]
              - each: "actions[*].effects"
                where: [{ path: auto, equals: updated }]
                after: { op: set, path: updated_by, entity: { ${'$'}copy: entity }, value: { ${'$'}${'$'}copy: actor } }
            """,
        )
        assertEquals(emptyList(), r.errors)
        val todo = r.raw().list("entities")[0].list("fields")
        assertEquals(listOf("title", "updated_on", "updated_by"), todo.map { it["key"] })
        assertEquals(origin, todo[2]["origin"], "足した要素に出どころ")
        assertEquals(listOf("name"), r.raw().list("entities")[1].list("fields").map { it["key"] }, "条件に合わない型には足さない")
        val effect = r.raw().list("actions")[0].list("effects")[2]
        assertEquals("todo", effect["entity"], "値はひな型（選んだ要素の値を写す）")
        assertEquals(mapOf("\$copy" to "actor"), effect["value"], "\$\$ で始まるキーは書いたまま")
        assertEquals(null, YamlIO.toAny(term).let { (it as Map<*, *>)["entities"] as List<*> }.let { ((it[0] as Map<*, *>)["fields"] as List<*>).getOrNull(2) }, "渡した項は変えない")
    }

    @Test
    fun `置き換えと中へ足す 名前と model から読める`() {
        val r = apply(
            """
            id: p
            stage: detail
            kind: patch
            changes:
              - each: entities
                where: [{ path: id, equals: todo }]
                bind: e
                set: { "names.table": t_todo }
                changes:
                  - each: fields
                    where: [{ path: key, equals: title }]
                    set: { column: { ${'$'}format: "{id}_title", ${'$'}of: e } }
              - each: entities
                where: [{ path: id, equals: tag }]
                add: { fields: { key: count, actions: { ${'$'}copy: "actions[0].id", ${'$'}of: model } } }
            """,
        )
        assertEquals(emptyList(), r.errors)
        val todo = r.raw().list("entities")[0]
        assertEquals(mapOf("table" to "t_todo"), todo["names"], "途中の object は作る")
        assertEquals(listOf(mapOf("path" to "names.table") + origin), todo["origins"], "置き換えた場所は origins に")
        assertEquals("todo_title", todo.list("fields")[0]["column"])
        val tag = r.raw().list("entities")[1].list("fields")
        assertEquals("edit", tag[1]["actions"], "model は当てる前の項")
        assertEquals(origin, tag[1]["origin"])
    }

    @Test
    fun `補正は当たる要素が無い変更を誤りにし 規則は誤りにしない`() {
        val text = """
            id: x
            stage: basic
            kind: %s
            changes:
              - each: entities
                where: [{ path: id, equals: gone }]
                set: { label: 消えた型 }
        """
        assertTrue(apply(text.format("patch")).errors.single().contains("当たる要素がありません"))
        assertEquals(emptyList(), apply(text.format("rule")).errors)
    }

    @Test
    fun `書き方の誤りは読むときに分かる`() {
        fun parse(text: String) = TermChanges.parse(YamlIO.parseToRaw(text.trimIndent()), "r.yaml")
        assertFailsWith<IllegalStateException> { parse("id: r\nstage: basic\nkind: rule\nchanges: [{ each: entities, remove: x }]") }
        assertFailsWith<IllegalArgumentException> { parse("id: r\nstage: basic\nkind: rule\nchanges: [{ after: [1] }]") }
        assertFailsWith<IllegalStateException> { parse("id: r\nstage: basic\nkind: other\nchanges: [{ each: entities, set: { a: 1 } }]") }
        val e = assertFailsWith<IllegalArgumentException> { parse("id: r\nstage: basic\nkind: rule\nchanges: [{ each: entities, set: { a: { \$copy: id, \$of: x } } }]") }
        assertTrue("x" in e.message.orEmpty(), "宣言していない名前: ${e.message}")
    }
}
