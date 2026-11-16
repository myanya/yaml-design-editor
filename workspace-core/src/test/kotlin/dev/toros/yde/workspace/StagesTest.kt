package dev.toros.yde.workspace

import dev.toros.yde.semantic.TermChanges
import dev.toros.yde.workspace.layout.DefinitionStage
import dev.toros.yde.workspace.layout.DefinitionStages
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 段の項（ADR-0034）: 段の並びの順に、段ごとに規則 → 補正を当てる。補正は規則の結果も直せ、段の項の頭に当てた段の一覧が付く。
 */
class StagesTest {
    private val model = YamlIO.fromAny(YamlIO.parseToRaw("entities:\n  - { id: todo, label: TODO }\n"))
    private val chain = listOf(DefinitionStage("basic", "基本設計", null), DefinitionStage("detail", "詳細設計", null))

    private fun doc(text: String) = TermChanges.parse(YamlIO.parseToRaw(text.trimIndent()), "x.yaml")

    @Suppress("UNCHECKED_CAST")
    private fun todo(term: YNode): Map<String, Any?> = ((YamlIO.toAny(term) as Map<String, Any?>)["entities"] as List<Map<String, Any?>>)[0]

    @Test
    fun `段の中は規則のあとに補正 段は並びの順`() {
        // 置き場の順は補正が先でも、段の中では規則を先に当てる
        val defs = listOf(
            doc("id: fix\nstage: basic\nkind: patch\nchanges: [{ each: entities, where: [{ path: id, equals: todo }], set: { table: t_todo } }]"),
            doc("id: naming\nstage: basic\nkind: rule\nchanges: [{ each: entities, set: { table: { \$copy: id } } }]"),
            doc("id: later\nstage: detail\nkind: rule\nchanges: [{ each: entities, set: { mapper: { \$format: \"{table}Mapper\" } } }]"),
        )
        val basic = Stages.apply(model, chain.take(1), defs)
        assertEquals(emptyList(), basic.errors)
        assertEquals("t_todo", todo(basic.term)["table"], "補正が規則の結果を直す")
        assertEquals(null, todo(basic.term)["mapper"], "後の段の規則は当てない")
        val detail = Stages.apply(model, chain, defs)
        assertEquals("t_todoMapper", todo(detail.term)["mapper"], "後の段は前の段の項を読む")
        assertEquals(listOf("naming", "fix", "later"), (todo(detail.term)["origins"] as List<*>).map { (it as Map<*, *>)["by"] }, "置き換えた場所ごとの出どころ")
        @Suppress("UNCHECKED_CAST")
        val stages = (YamlIO.toAny(detail.term) as Map<String, Any?>)["stages"] as List<Map<String, Any?>>
        assertEquals(listOf("basic", "detail"), stages.map { it["id"] })
        assertEquals(listOf("naming", "fix"), (stages[0]["changes"] as List<*>).map { (it as Map<*, *>)["id"] })
        assertEquals(null, (YamlIO.toAny(model) as Map<*, *>)["stages"], "業務モデルの項は変えない")
        val resumed = Stages.apply(basic.term, chain, defs)
        assertEquals(emptyList(), resumed.errors)
        assertEquals(YamlIO.toAny(detail.term), YamlIO.toAny(resumed.term), "基本の結果に詳細を当てても、基本の規則と来歴は重ならない")
        assertTrue(Stages.apply(detail.term, chain.take(1), defs).errors.any { "適用済み" in it })
    }

    @Test
    fun `段の宣言の誤り`() {
        val parsed = DefinitionStages.parse(YamlIO.parseFirst("stages:\n  - { id: basic }\n  - { id: basic }\n  - { name: 名前だけ }\n") as YNode.Map)
        assertEquals(listOf("basic"), parsed.stages.map { it.id })
        assertTrue(parsed.errors.any { "重複" in it } && parsed.errors.any { "id は" in it }, "${parsed.errors}")
        assertEquals(listOf("basic"), parsed.upTo("basic")?.map { it.id })
        assertEquals(null, parsed.upTo("none"))
    }
}
