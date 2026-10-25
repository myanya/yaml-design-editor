package dev.toros.yde.workspace

import dev.toros.yde.semantic.VirtualDashboardDef
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.*

class DashboardDefinitionsTest {
    private val source = DocumentId(".yde/views/dashboard.yaml")
    private val grid = DocumentId(".yde/views/grid.yaml") to
        "id: grid\nrows: {idPattern: '*'}\ncolumns: [{label: 名前, path: title}]"
    private val text = "id: home\nkind: dashboard\nsections: [{id: all, label: 全件, view: grid}]"

    @Test
    fun `区画の参照は一意な一覧に限り循環や別種のビューを受け付けない`() {
        assertTrue(ViewDefinitions.load(listOf(source to text, grid)).errors.isEmpty())
        assertTrue(ViewDefinitions.load(listOf(source to text)).errors.single().contains("一意"))
        assertTrue(ViewDefinitions.load(listOf(source to text, grid, DocumentId("duplicate.yaml") to grid.second)).errors.size >= 2)
        val self = text.replace("view: grid", "view: home")
        assertTrue(ViewDefinitions.load(listOf(source to self)).errors.single().contains("一意"))
        val doc = grid.first to "id: grid\nkind: document\nschemaId: item\nsections: [{fields: {}}]"
        assertTrue(ViewDefinitions.load(listOf(source to text, doc)).errors.single().contains("一意"))
    }

    @Test
    fun `定義の誤記と区画の重複と未宣言のパラメータを読み込み時に拒否する`() {
        val invalid = listOf(text + "\nlayout: anything", text.replace("view: grid", "views: grid"),
            text.replace("sections: [{id: all, label: 全件, view: grid}]", "sections: []"),
            text.replace("view: grid}]", "view: grid}, {id: all, view: grid}]"),
            text.replace("view: grid}", "view: grid, filter: {match: [{node: {id: ${'$'}unknown}}], expect: exists}}"))
        invalid.forEach { value -> assertTrue(ViewDefinitions.load(listOf(source to value, grid)).dashboards.isEmpty(), value) }
    }

    @Test
    fun `既定の入口は真偽値で宣言し重複を診断する`() {
        val first = text + "\ndefault: true"
        val second = first.replace("id: home", "id: second")
        val catalog = ViewDefinitions.load(listOf(source to first, DocumentId("second.yaml") to second, grid))
        assertTrue(catalog.dashboards.all { it.default })
        assertTrue(catalog.errors.any { "default" in it })
        assertTrue(ViewDefinitions.load(listOf(source to (text + "\ndefault: bad"), grid)).dashboards.isEmpty())
    }

    @Test
    fun `基準日は既存のパラメータ差し込みで条件へ渡す`() {
        val value = text.replace("view: grid}", "view: grid, filter: {match: [{node: {bind: t}}], check: [{lt: [{of: t, path: due}, ${'$'}today], type: date}]}}")
        val parsed = ViewDefinitions.parseView(YamlIO.parseToRaw(value) as Map<*, *>, source) as VirtualDashboardDef
        val filter = assertNotNull(parsed.sections.single().filter)
        assertEquals(setOf("today"), filter.referenced)
        assertNotNull(filter.rule(mapOf("today" to "2026-10-25")))
    }
}
