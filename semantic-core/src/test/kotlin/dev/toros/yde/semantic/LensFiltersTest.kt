package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** レンズの定義の箱の絞り込み（ADR-0027）: 向き・対象・起点・決められない条件の扱い。 */
class LensFiltersTest {

    private fun conditions(where: String?) = TransformDef.parseConditions(where?.let { YamlIO.parseToRaw(it) }, "where")

    private fun filter(id: String, mode: LensFilterMode, where: String, entityType: String? = null, level: String? = null,
        keep: LensFilterKeep? = null) = LensFilter(id, id, mode, conditions(where), level, entityType, keepIfLinked = keep)

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

    // ---------------------------------------------------------------- つながりで残す（keepIfLinked）

    private val open = "{not: {path: data.status, oneOf: [resolved, declined]}}"

    /** UX-02（解決）は関連で UX-01（未着手）と、UX-03（見送り）は束ねで T-01 と、関連で UX-02 とつながる。 */
    private val links = mapOf(
        "UX-02" to listOf(LensLink("relates-to", "UX-01"), LensLink("relates-to", "UX-03")),
        "UX-01" to listOf(LensLink("relates-to", "UX-02")),
        "UX-03" to listOf(LensLink("bundled-in", "T-01"), LensLink("relates-to", "UX-02")),
        "T-01" to listOf(LensLink("bundled-in", "UX-03")),
    )

    @Test
    fun `つながる相手のどれかが条件に合えば隠さずに残し 残した箱を別に返す`() {
        val hide = filter("closed", LensFilterMode.HIDE, closed, entityType = "item",
            keep = LensFilterKeep(setOf("relates-to"), "item", conditions(open)))
        val result = LensFilters.evaluate(listOf(hide), "entity", boxes, links = links)
        assertEquals(setOf("UX-03"), result.hidden, "相手は 1 段だけ。UX-03 の関連の相手 UX-02 は解決なので残らない")
        assertEquals(setOf("UX-02"), result.kept)
    }

    @Test
    fun `見る線は via の関係だけで 相手の型と条件も合わせて見る`() {
        val anyLine = filter("closed", LensFilterMode.HIDE, closed, entityType = "item", keep = LensFilterKeep())
        assertEquals(emptySet(), LensFilters.hidden(listOf(anyLine), "entity", boxes, links = links), "via も条件も省けば、つながっていれば残す")
        val bundled = filter("closed", LensFilterMode.HIDE, closed, entityType = "item", keep = LensFilterKeep(setOf("bundled-in"), "item"))
        assertEquals(setOf("UX-02", "UX-03"), LensFilters.hidden(listOf(bundled), "entity", boxes, links = links), "束ねの相手 T-01 は型が違う")
        val theme = filter("closed", LensFilterMode.HIDE, closed, entityType = "item", keep = LensFilterKeep(setOf("bundled-in"), "theme"))
        assertEquals(setOf("UX-02"), LensFilters.hidden(listOf(theme), "entity", boxes, links = links))
    }

    @Test
    fun `例外はその絞り込みが隠す分だけを取り消し 相手は別の絞り込みで隠れていても値で数える`() {
        val keepOpen = LensFilterKeep(setOf("relates-to"), "item", conditions(open))
        val hide = filter("closed", LensFilterMode.HIDE, closed, entityType = "item", keep = keepOpen)
        val noTodo = filter("no-todo", LensFilterMode.HIDE, "{path: data.status, equals: todo}")
        val result = LensFilters.evaluate(listOf(hide, noTodo), "entity", boxes, links = links)
        assertEquals(setOf("UX-01", "UX-03"), result.hidden, "UX-01 は別の絞り込みで隠れるが、UX-02 は UX-01 の値で残る")
        assertEquals(setOf("UX-02"), result.kept)
        val resolvedHide = filter("resolved", LensFilterMode.HIDE, "{path: data.status, equals: resolved}", entityType = "item")
        assertEquals(setOf("UX-02", "UX-03"), LensFilters.hidden(listOf(hide, resolvedHide), "entity", boxes, links = links),
            "例外の無い絞り込みが隠す箱は隠す")
        val show = filter("open-only", LensFilterMode.SHOW, open, entityType = "item", keep = keepOpen)
        assertEquals(setOf("UX-03", "UX-04"), LensFilters.hidden(listOf(show), "entity", boxes, links = links),
            "show にも書ける（UX-04 は状態が無くて決められず、つながりも無い）")
    }

    @Test
    fun `depth を増やすと via の線をその段数まで辿り 途中の箱は条件を問わない`() {
        // UX-03（見送り）→ 関連 → UX-02（解決）→ 関連 → UX-01（未着手）。1 段では残らず、2 段で残る
        val keep1 = LensFilterKeep(setOf("relates-to"), "item", conditions(open))
        val keep2 = keep1.copy(depth = 2)
        fun hide(keep: LensFilterKeep) = filter("closed", LensFilterMode.HIDE, closed, entityType = "item", keep = keep)
        assertEquals(setOf("UX-03"), LensFilters.hidden(listOf(hide(keep1)), "entity", boxes, links = links))
        val two = LensFilters.evaluate(listOf(hide(keep2)), "entity", boxes, links = links)
        assertEquals(emptySet(), two.hidden)
        assertEquals(setOf("UX-02", "UX-03"), two.kept)
        // via の外の線は、何段でも辿らない（束ねの線で T-01 へ行っても先へは進まない）
        val bundled = LensFilters.hidden(listOf(hide(LensFilterKeep(setOf("bundled-in"), "item", conditions(open), depth = 5))), "entity", boxes, links = links)
        assertEquals(setOf("UX-02", "UX-03"), bundled)
        // 自分自身は相手に数えない（輪になって戻っても）
        val selfOnly = LensFilters.hidden(listOf(hide(LensFilterKeep(setOf("relates-to"), "item", conditions("{path: id, equals: UX-03}"), depth = 3))),
            "entity", boxes, links = links)
        assertEquals(setOf("UX-03"), selfOnly, "UX-02 は 1 段で UX-03 に着くので残る。UX-03 は自分に戻っても数えない")
    }
}
