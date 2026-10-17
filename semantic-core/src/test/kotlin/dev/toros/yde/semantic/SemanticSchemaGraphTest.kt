package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.EntityDecl
import dev.toros.yde.semantic.schema.EntityTypeDef
import dev.toros.yde.semantic.schema.RelationDef
import dev.toros.yde.semantic.schema.SchemaTreeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * スキーマの図（SemanticSchemaGraph。architecture/graph-query.md「スキーマレンズ」）:
 * - 型はレジストリ・スキーマの entity 宣言・object の entity・線の端から集まる
 * - 線は宣言（source → target）と導出（参照項目を囲む要素の型 → scope / idPattern が指す型）。同じ線は 1 本にまとめる
 * - 入れ子は保有の宣言（名前は項目の relation かキー名）。子を定義する場所だけが誕生の場所
 * - 要素の型は object の entity ＞ ID の項目の型 ＞ 単一の宣言 ＞ 宣言の並びで推定
 */
class SemanticSchemaGraphTest {

    private fun parse(id: String, yaml: String) = SchemaTreeParser.parse(yaml.trimIndent(), fallbackId = id)

    private val screenList = SchemaGraphSource(
        "screen-list", "画面一覧",
        parse(
            "screen-list",
            """
            id: screen-list
            root:
              type: object
              fields:
                - key: screens
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id }
                      - { key: uses, type: ref, relation: uses, idPattern: "UC-*" }
            """,
        ),
        listOf(EntityDecl(type = "screen", idPattern = "SCR-*", defines = true)),
    )
    private val screenDef = SchemaGraphSource(
        "screen-def", "画面定義",
        parse(
            "screen-def",
            """
            id: screen-def
            root:
              type: object
              fields:
                - { key: id, type: id }
                - key: items
                  type: array
                  relation: has-item
                  item:
                    type: object
                    fields:
                      - { key: id, type: id }
                      - { key: source, type: ref, relation: field-source, idPattern: "EA-*", scope: { schemaIds: [entity-detail] } }
                      - { key: note, type: ref }
                - { key: spec, type: ref, target: document }
            """,
        ),
        listOf(
            EntityDecl(type = "screen", idPattern = "SCR-*", aspect = "detail"),
            EntityDecl(type = "screen-item", idPattern = "SI-*", defines = true),
        ),
    )
    private val entityDetail = SchemaGraphSource(
        "entity-detail", "エンティティ定義書",
        parse(
            "entity-detail",
            """
            id: entity-detail
            root:
              type: object
              fields:
                - { key: id, type: id }
                - key: attributes
                  type: array
                  item:
                    type: object
                    entity: { type: entity-attr, defines: true }
                    fields:
                      - { key: id, type: id }
            """,
        ),
        listOf(EntityDecl(type = "entity-model", idPattern = "ENT-*", aspect = "detail")),
    )
    private val usecaseList = SchemaGraphSource(
        "usecase-list", "ユースケース一覧",
        parse(
            "usecase-list",
            """
            id: usecase-list
            root:
              type: object
              fields:
                - key: usecases
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id }
            """,
        ),
        listOf(EntityDecl(type = "usecase", idPattern = "UC-*", defines = true)),
    )
    private val relations = listOf(
        RelationDef("uses", label = "利用する", inverseLabel = "利用される", source = listOf("screen"), target = listOf("usecase")),
        RelationDef("calls", source = listOf("screen"), target = listOf("api")),
        RelationDef("flow-start", target = listOf("screen")),
    )
    private val graph = SemanticSchemaGraph.build(
        listOf(screenList, screenDef, entityDetail, usecaseList), relations, listOf(EntityTypeDef("screen", label = "画面")),
    )

    @Test
    fun `型は宣言と出現と線の端から集まり 型名の昇順に並ぶ`() {
        assertEquals(listOf("api", "entity-attr", "entity-model", "screen", "screen-item", "usecase"), graph.types.map { it.type })
        assertEquals("画面", graph.labelOf("screen"))
        assertEquals("api", graph.labelOf("api"), "表示名の無い型は型名")
        assertTrue(graph.typeMap.getValue("api").appearances.isEmpty(), "宣言だけに現れる型はスキーマに出現しない")
        // screen: 一覧（定義）が先、定義書（側面）は宣言の並びからの推定
        val screen = graph.typeMap.getValue("screen").appearances
        assertEquals(listOf("screen-list" to "screens[]", "screen-def" to "/"), screen.map { it.schemaId to it.path })
        assertTrue(screen[0].defines && !screen[0].inferred)
        assertTrue(!screen[1].defines && screen[1].inferred && screen[1].aspect == "detail")
        assertEquals("screen-list", graph.typeMap.getValue("screen").definingSchemaId)
        // object の entity は推定ではない
        val attr = graph.typeMap.getValue("entity-attr").appearances.single()
        assertEquals("entity-detail" to "attributes[]", attr.schemaId to attr.path)
        assertTrue(attr.defines && !attr.inferred)
    }

    @Test
    fun `線は宣言と導出を同じ組で 1 本にまとめ 出どころを持つ`() {
        val byKey = graph.edges.associateBy { Triple(it.from, it.to, it.relation) }
        assertEquals(3, graph.edges.size, graph.edges.toString())
        val uses = byKey.getValue(Triple("screen", "usecase", "uses"))
        assertEquals(SchemaGraphEdgeSource.BOTH, uses.source, "宣言にあり、idPattern UC-* からも導ける")
        assertEquals(listOf(SchemaGraphSite("screen-list", "screens[].uses")), uses.sites)
        assertEquals(SchemaGraphEdgeSource.DECLARED, byKey.getValue(Triple("screen", "api", "calls")).source)
        val source = byKey.getValue(Triple("screen-item", "entity-attr", "field-source"))
        assertEquals(SchemaGraphEdgeSource.DERIVED, source.source, "scope のスキーマに現れる型を idPattern EA-* で絞る")
        assertNull(byKey[Triple("flow-start", "screen", "flow-start")], "source の無い宣言は線にならない")
        assertTrue(graph.edges.none { it.relation == null }, "文書への参照（target: document）は線にしない")
    }

    @Test
    fun `両端を宣言した関係では宣言が線を決め 宣言より広い導出は別に残る`() {
        val wide = SchemaGraphSource(
            "wide", "広い参照",
            parse(
                "wide",
                """
                id: wide
                root:
                  type: object
                  fields:
                    - { key: id, type: id }
                    - { key: uses, type: ref, relation: uses, scope: { schemaIds: [usecase-list, entity-detail] } }
                """,
            ),
            listOf(EntityDecl(type = "screen", idPattern = "SCR-*", defines = true)),
        )
        val g = SemanticSchemaGraph.build(listOf(wide, usecaseList, entityDetail), relations, emptyList())
        val uses = g.edges.filter { it.relation == "uses" }
        assertEquals(listOf("usecase"), uses.map { it.to }, "宣言の target だけが線: $uses")
        assertEquals(SchemaGraphEdgeSource.BOTH, uses.single().source)
        assertEquals(listOf("entity-attr", "entity-model"), g.beyondDeclaration.map { it.to }, "scope のスキーマに現れるが宣言の target に無い型")
        assertTrue(g.beyondDeclaration.all { it.from == "screen" && it.relation == "uses" && it.sites == listOf(SchemaGraphSite("wide", "uses")) })
    }

    @Test
    fun `入れ子は保有の宣言で 名前は項目の relation かキー名`() {
        assertEquals(
            listOf(Triple("entity-model", "entity-attr", "attributes"), Triple("screen", "screen-item", "has-item")),
            graph.holdings.map { Triple(it.parent, it.child, it.relation) },
        )
        assertTrue(graph.holdings.all { it.childDefines }, "どちらも子を定義する場所")
        assertEquals(listOf(SchemaGraphSite("screen-def", "items[]")), graph.holdings.last().sites)
    }

    @Test
    fun `線にできない参照項目は理由つきで残る`() {
        val u = graph.unresolved.single()
        assertEquals(SchemaGraphSite("screen-def", "items[].note"), u.site)
        assertTrue("絞っていない" in u.reason, u.reason)
    }

    @Test
    fun `参照を書く要素の型が決まらない参照と 相手の見つからない参照も理由つきで残る`() {
        val plain = SchemaGraphSource(
            "plain", "型なし",
            parse("plain", "id: plain\nroot:\n  type: object\n  fields:\n    - { key: id, type: id }\n    - { key: to, type: ref, idPattern: \"X-*\" }"),
            emptyList(),
        )
        val typed = SchemaGraphSource(
            "typed", "型あり",
            parse("typed", "id: typed\nroot:\n  type: object\n  fields:\n    - { key: id, type: id }\n    - { key: to, type: ref, scope: { schemaIds: [nowhere] } }"),
            listOf(EntityDecl(type = "thing", defines = true)),
        )
        val g = SemanticSchemaGraph.build(listOf(plain, typed), emptyList(), emptyList())
        assertEquals(listOf("thing"), g.types.map { it.type })
        assertTrue(g.edges.isEmpty())
        assertEquals(2, g.unresolved.size, g.unresolved.toString())
        assertTrue("型が決まらない" in g.unresolved.first { it.site.schemaId == "plain" }.reason)
        assertTrue("見つからない" in g.unresolved.first { it.site.schemaId == "typed" }.reason)
    }

    @Test
    fun `再帰する定義は 1 回だけ歩く`() {
        val outline = SchemaGraphSource(
            "outline", "アウトライン",
            parse(
                "outline",
                """
                id: outline
                definitions:
                  node:
                    type: object
                    fields:
                      - { key: id, type: id }
                      - key: children
                        type: array
                        item: { ${'$'}ref: node }
                root: { ${'$'}ref: node }
                """,
            ),
            listOf(EntityDecl(type = "node", defines = true)),
        )
        val g = SemanticSchemaGraph.build(listOf(outline), emptyList(), emptyList())
        assertEquals(listOf("node"), g.types.map { it.type })
        assertEquals(listOf("/"), g.typeMap.getValue("node").appearances.map { it.path }, "再帰の中は歩かない")
        assertTrue(g.holdings.isEmpty())
    }
}
