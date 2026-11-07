package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** レンズの定義の箱の絞り込み（ADR-0027）: 向き・対象・起点・決められない条件の扱い。 */
class LensFiltersTest {

    private fun filter(id: String, mode: LensFilterMode, where: String, entityType: String? = null, level: String? = null) =
        LensFilter(id, id, mode, TransformDef.parseConditions(YamlIO.parseToRaw(where), "where"), level, entityType)

    private fun box(id: String, type: String, data: Map<String, Any?>) = LensFilters.Candidate(id, type) {
        SemanticTransform.TemplatePlace(YamlIO.fromAny(mapOf("id" to id, "title" to id, "entityType" to type, "data" to data)), null, emptyList())
    }

    private val boxes = listOf(
        box("UX-01", "item", mapOf("status" to "todo")),
        box("UX-02", "item", mapOf("status" to "resolved")),
        box("UX-03", "item", mapOf("status" to "declined")),
        box("UX-04", "item", emptyMap()),
        box("T-01", "theme", mapOf("status" to "resolved")),
    )

    private val closed = "{path: data.status, oneOf: [resolved, declined]}"

    @Test
    fun `hide は条件に合う箱を隠し 対象の型の外には効かない`() {
        val hide = filter("closed", LensFilterMode.HIDE, closed, entityType = "item")
        assertEquals(setOf("UX-02", "UX-03"), LensFilters.hidden(listOf(hide), "entity", boxes))
    }

    @Test
    fun `show は条件に合う箱だけを見せ 値が無くて決められない箱も隠す`() {
        val show = filter("open", LensFilterMode.SHOW, "{path: data.status, oneOf: [todo, doing]}", entityType = "item")
        assertEquals(setOf("UX-02", "UX-03", "UX-04"), LensFilters.hidden(listOf(show), "entity", boxes))
    }

    @Test
    fun `入れた絞り込みのどれかが隠せば隠し 起点は隠さない`() {
        val hide = filter("closed", LensFilterMode.HIDE, closed)
        val noTodo = filter("no-todo", LensFilterMode.HIDE, "{path: data.status, equals: todo}")
        assertEquals(setOf("UX-01", "UX-03", "T-01"), LensFilters.hidden(listOf(hide, noTodo), "entity", boxes, origin = "UX-02"))
    }

    @Test
    fun `段が違う絞り込みは効かず 条件は id や型も読める`() {
        val typeOnly = filter("t", LensFilterMode.HIDE, closed, level = "type")
        assertEquals(emptySet(), LensFilters.hidden(listOf(typeOnly), "entity", boxes))
        val byId = filter("id", LensFilterMode.SHOW, "[{path: entityType, equals: item}, {not: {path: id, equals: UX-01}}]")
        assertEquals(setOf("UX-01", "T-01"), LensFilters.hidden(listOf(byId), "entity", boxes))
    }
}
