package dev.toros.yde.app.ui

import dev.toros.yde.semantic.SemanticDocument
import dev.toros.yde.semantic.SemanticEngine
import dev.toros.yde.semantic.SemanticInput
import dev.toros.yde.semantic.SemanticSnapshot
import dev.toros.yde.semantic.schema.EntityDecl
import dev.toros.yde.semantic.schema.SchemaTreeParser
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * グラフレンズの節点と辺（LensGraphs。architecture/graph-query.md「エンティティページ」）:
 * - 一体（箱＝エンティティ）: 定義出現の保有だけを箱の入れ子にし、詳細化の出現の保有は線
 * - 側面で分ける（箱＝出現）: 文書の入れ子をそのまま箱にし、同じエンティティの箱を点線で結ぶ
 * - 絞り込みと畳み: 隠した型・関係・起点の近傍・畳んだ箱の中の辺の付け替え
 */
class LensGraphTest {

    private val listSchema = SchemaTreeParser.parse(
        """
        id: list
        root:
          type: object
          fields:
            - key: screens
              type: array
              item:
                type: object
                fields:
                  - { key: id, type: id }
                  - { key: name, type: string }
        """.trimIndent(),
        fallbackId = "list",
    )
    private val screenSchema = SchemaTreeParser.parse(
        """
        id: screen
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
                  - { key: source, type: ref, relation: source }
        """.trimIndent(),
        fallbackId = "screen",
    )
    private val planSchema = SchemaTreeParser.parse(
        """
        id: plan
        root:
          type: object
          fields:
            - { key: id, type: id }
            - key: cases
              type: array
              item:
                type: object
                fields:
                  - { key: id, type: id }
                  - { key: status, type: string }
        """.trimIndent(),
        fallbackId = "plan",
    )
    private val plainSchema = SchemaTreeParser.parse(
        "id: plain\nroot:\n  type: object\n  fields:\n    - { key: id, type: id }",
        fallbackId = "plain",
    )

    private val snapshot: SemanticSnapshot = SemanticEngine.build(
        SemanticInput(
            1,
            listOf(
                SemanticDocument(
                    DocumentId("screens.yaml"), YamlIO.parse("screens:\n  - { id: SCR-1, name: 画面1 }"), listSchema, "list",
                    listOf(EntityDecl(type = "screen", idPattern = "SCR-*", defines = true)),
                ),
                SemanticDocument(
                    DocumentId("screens/SCR-1.yaml"), YamlIO.parse("id: SCR-1\nitems:\n  - { id: SI-1, source: EA-1 }\n  - { id: SI-2 }"),
                    screenSchema, "screen",
                    listOf(
                        EntityDecl(type = "screen", idPattern = "SCR-*", aspect = "detail"),
                        EntityDecl(type = "screen-item", idPattern = "SI-*", defines = true),
                    ),
                ),
                // テスト計画の中に、テストケースの詳細化の出現を書く（保有だが誕生の場所ではない）
                SemanticDocument(
                    DocumentId("plans/TP-1.yaml"), YamlIO.parse("id: TP-1\ncases:\n  - { id: TC-1, status: done }"), planSchema, "plan",
                    listOf(
                        EntityDecl(type = "plan", idPattern = "TP-*", defines = true),
                        EntityDecl(type = "case", idPattern = "TC-*"),
                    ),
                ),
                SemanticDocument(
                    DocumentId("cases/TC-1.yaml"), YamlIO.parse("id: TC-1"), plainSchema, "plain",
                    listOf(EntityDecl(type = "case", idPattern = "TC-*", defines = true)),
                ),
                SemanticDocument(
                    DocumentId("attrs/EA-1.yaml"), YamlIO.parse("id: EA-1"), plainSchema, "plain",
                    listOf(EntityDecl(type = "attr", idPattern = "EA-*", defines = true)),
                ),
            ),
        ),
    )

    private val typeOf: (String) -> String = { id -> snapshot.graph.entities[id]?.entityType ?: "" }

    @Test
    fun `一体では定義出現の保有だけが箱の入れ子になり 詳細化の出現の保有は線になる`() {
        val g = LensGraphs.build(snapshot.graph, snapshot.refs, LensMode.ENTITY)
        val byKey = g.nodes.associateBy { it.key }
        assertEquals(listOf("EA-1", "SCR-1", "SI-1", "SI-2", "TC-1", "TP-1"), g.nodes.map { it.key }, "箱はエンティティ 1 つに 1 つ")
        assertEquals("SCR-1", byKey.getValue("SI-1").parent)
        assertEquals("has-item", byKey.getValue("SI-1").relation)
        assertNull(byKey.getValue("TC-1").parent, "詳細化の出現の保有は誕生の場所ではない")
        assertTrue(LensGraphEdge("TP-1", "TC-1", "cases", LensEdgeKind.HOLDING) in g.edges, "線として残る: ${g.edges}")
        assertTrue(LensGraphEdge("SI-1", "EA-1", "source", LensEdgeKind.REF) in g.edges)
        assertTrue(g.edges.none { it.kind == LensEdgeKind.TIE })
    }

    @Test
    fun `側面で分けると出現ごとの箱になり 同じエンティティの箱は点線で結ばれる`() {
        val g = LensGraphs.build(snapshot.graph, snapshot.refs, LensMode.OCCURRENCE)
        val scr = g.nodes.filter { it.id == "SCR-1" }
        assertEquals(2, scr.size)
        assertEquals("screens.yaml", scr[0].occurrence?.document?.value, "定義出現が先")
        val detail = scr[1].key
        assertEquals(detail, g.nodes.first { it.id == "SI-1" }.parent, "画面項目は詳細の出現の箱の中")
        assertTrue(LensGraphEdge(scr[0].key, detail, null, LensEdgeKind.TIE) in g.edges, "同じエンティティの箱は点線")
        // 詳細化の出現はテスト計画の箱の中、定義出現は外。2 つは点線で結ばれる
        val tc = g.nodes.filter { it.id == "TC-1" }
        assertEquals(listOf("cases/TC-1.yaml", "plans/TP-1.yaml"), tc.map { it.occurrence?.document?.value })
        assertEquals(g.nodes.first { it.id == "TP-1" }.key, tc[1].parent)
        assertTrue(LensGraphEdge(tc[0].key, tc[1].key, null, LensEdgeKind.TIE) in g.edges)
        // 参照は書いた出現から相手の定義出現へ
        val si1 = g.nodes.first { it.id == "SI-1" }.key
        val ea1 = g.nodes.first { it.id == "EA-1" }.key
        assertTrue(LensGraphEdge(si1, ea1, "source", LensEdgeKind.REF) in g.edges)
    }

    @Test
    fun `畳んだ箱の中は見えず 中の箱につながる辺は見える箱へ付け替わる`() {
        val g = LensGraphs.build(snapshot.graph, snapshot.refs, LensMode.ENTITY)
        val closed = LensGraphs.select(g, LensSelectParams(typeOf))
        assertEquals(listOf("EA-1", "SCR-1", "TC-1", "TP-1"), closed.nodes.map { it.node.key }, "起点が無ければ全部畳む")
        val scr = closed.nodes.first { it.node.key == "SCR-1" }
        assertEquals(2, scr.hiddenChildren)
        assertTrue(LensGraphEdge("SCR-1", "EA-1", "source", LensEdgeKind.REF) in closed.edges, "SI-1 → EA-1 は SCR-1 → EA-1 に: ${closed.edges}")
        val open = LensGraphs.select(g, LensSelectParams(typeOf, expanded = mapOf("SCR-1" to true)))
        assertEquals(listOf("EA-1", "SCR-1", "SI-1", "SI-2", "TC-1", "TP-1"), open.nodes.map { it.node.key }, "親が先の深さ優先")
        assertEquals("SCR-1", open.nodes.first { it.node.key == "SI-1" }.parent)
        assertTrue(LensGraphEdge("SI-1", "EA-1", "source", LensEdgeKind.REF) in open.edges)
    }

    @Test
    fun `起点の近傍では起点の外側の箱が既定で開き 隠した型と関係は入れ子を切る`() {
        val g = LensGraphs.build(snapshot.graph, snapshot.refs, LensMode.ENTITY)
        val near = LensGraphs.select(g, LensSelectParams(typeOf, origin = "SI-1", depth = 1))
        assertEquals(listOf("EA-1", "SCR-1", "SI-1"), near.nodes.map { it.node.key }, "起点・参照の相手・起点を含む箱")
        assertTrue(near.nodes.first { it.node.key == "SCR-1" }.expanded, "起点の外側の箱は開く")
        // 型を隠すと箱が消え、その箱につながる辺も消える
        val noItems = LensGraphs.select(g, LensSelectParams(typeOf, hiddenTypes = setOf("screen-item"), expanded = mapOf("SCR-1" to true)))
        assertEquals(listOf("EA-1", "SCR-1", "TC-1", "TP-1"), noItems.nodes.map { it.node.key })
        assertTrue(noItems.edges.none { it.to == "EA-1" })
        // 保有の関係を隠すと、子はいちばん外側の箱になる
        val cut = LensGraphs.select(g, LensSelectParams(typeOf, hiddenRelations = setOf("has-item")))
        assertNull(cut.nodes.first { it.node.key == "SI-1" }.parent)
        assertEquals(0, cut.nodes.first { it.node.key == "SCR-1" }.hiddenChildren)
    }
}
