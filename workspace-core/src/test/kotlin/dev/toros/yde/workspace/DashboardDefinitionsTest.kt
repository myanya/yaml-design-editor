package dev.toros.yde.workspace

import dev.toros.yde.semantic.DashboardInputType
import dev.toros.yde.semantic.DashboardPlacement
import dev.toros.yde.semantic.VirtualDashboardDef
import dev.toros.yde.semantic.sectionGroups
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

    @Test
    fun `共有の入力を読み 区画の条件と表の params で使える名前を点検する`() {
        val value = "id: home\nkind: dashboard\ninputs:\n" +
            "  - { key: from, label: から, type: date, default: ${'$'}monthStart }\n" +
            "  - { key: kind, label: 区分, type: enum, values: [{ value: a, label: A }, b] }\n" +
            "  - { key: q }\n" +
            "sections: [{ id: all, label: 全件, view: grid, filter: { match: [{ node: { entity: t, bind: t } }], where: [{ of: t, path: kind, equals: ${'$'}kind }], expect: exists } }]"
        val parsed = ViewDefinitions.parseView(YamlIO.parseToRaw(value) as Map<*, *>, source) as VirtualDashboardDef
        assertEquals(listOf("from", "kind", "q"), parsed.inputs.map { it.key })
        assertEquals(DashboardInputType.DATE, parsed.inputs[0].type)
        assertEquals("2026-10-01", parsed.inputs[0].defaultFor(java.time.LocalDate.of(2026, 10, 26)))
        assertEquals(listOf("a" to "A", "b" to null), parsed.inputs[1].values)
        assertEquals(DashboardInputType.STRING, parsed.inputs[2].type)
        assertEquals(setOf("kind"), parsed.sections.single().filter!!.referenced)
        // 空の入力を使う条件は外れ、何も残らなければ条件なし
        val filter = parsed.sections.single().filter!!
        assertNotNull(filter.ruleOrNull(mapOf("today" to "2026-10-26"), setOf("from", "kind", "q")))  // expect は残る
        val dated = ViewDefinitions.parseView(YamlIO.parseToRaw(value.replace("where: [{ of: t, path: kind, equals: ${'$'}kind }], expect: exists", "check: [{ gte: [{ of: t, path: due }, ${'$'}from], type: date }]")) as Map<*, *>, source) as VirtualDashboardDef
        val check = dated.sections.single().filter!!
        assertNull(check.ruleOrNull(mapOf("today" to "2026-10-26"), setOf("from", "kind", "q")))
        assertNotNull(check.ruleOrNull(mapOf("today" to "2026-10-26", "from" to "2026-10-01"), setOf("from", "kind", "q")))
        // 誤り: 宣言していない名前・today・重複・enum に候補なし
        fun bad(text: String) = assertTrue(ViewDefinitions.load(listOf(source to text, grid)).dashboards.isEmpty(), text)
        bad(value.replace("equals: ${'$'}kind", "equals: ${'$'}nothing"))
        bad(value.replace("key: q", "key: today"))
        bad(value.replace("key: q", "key: from"))
        bad(value.replace("values: [{ value: a, label: A }, b]", "values: []"))
    }

    @Test
    fun `区画の群と並べ方を読み群は無名を先頭に最初に現れた順で分ける`() {
        val value = "id: home\nkind: dashboard\nplacement: vertical\nsections:\n" +
            "  - {id: all, label: 全件, view: grid}\n" +
            "  - {id: a, label: A, view: grid, group: 図}\n" +
            "  - {id: b, label: B, view: grid, group: 状況}\n" +
            "  - {id: c, label: C, view: grid, group: 図}\n"
        val parsed = ViewDefinitions.parseView(YamlIO.parseToRaw(value) as Map<*, *>, source) as VirtualDashboardDef
        assertEquals(DashboardPlacement.VERTICAL, parsed.placement)
        assertEquals(listOf(null, "図", "状況"), parsed.sectionGroups().map { it.first })
        assertEquals(listOf(listOf("all"), listOf("a", "c"), listOf("b")), parsed.sectionGroups().map { group -> group.second.map { it.id } })
        // 省略時は auto。並べ方の誤記と空の群の名前は読み込み時に拒否する
        val plain = ViewDefinitions.parseView(YamlIO.parseToRaw(text) as Map<*, *>, source) as VirtualDashboardDef
        assertEquals(DashboardPlacement.AUTO, plain.placement)
        assertEquals(listOf(null), plain.sectionGroups().map { it.first })
        val sideways = ViewDefinitions.load(listOf(source to (text + "\nplacement: sideways"), grid))
        assertTrue(sideways.dashboards.isEmpty())
        assertTrue(sideways.errors.any { "placement" in it }, sideways.errors.toString())
        val empty = ViewDefinitions.load(listOf(source to text.replace("view: grid}", "view: grid, group: ''}"), grid))
        assertTrue(empty.dashboards.isEmpty())
        assertTrue(empty.errors.any { "group" in it }, empty.errors.toString())
    }
}
