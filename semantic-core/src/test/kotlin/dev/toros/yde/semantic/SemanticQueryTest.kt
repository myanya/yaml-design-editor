package dev.toros.yde.semantic

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.semantic.schema.EntityDecl
import dev.toros.yde.semantic.schema.QuerySpec
import dev.toros.yde.semantic.schema.SchemaTreeParser
import dev.toros.yde.yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * パターン照合カーネル（SemanticQuery。R22-01）の最小契約テスト（Phase 6C）。
 * 網羅はルート index/QueryTest（シェル経由＝旧経路との一致）が担い、ここでは
 * semantic-core 単体で「宣言 → 評価」が閉じることを固定する。
 */
class SemanticQueryTest {

    private val schema = SchemaTreeParser.parse(
        """
        id: node
        root:
          type: object
          fields:
            - key: id
              type: id
            - key: uses
              type: array
              item:
                type: ref
                relation: uses
        """.trimIndent(),
        fallbackId = "node",
    )

    private val entities = listOf(
        EntityDecl(type = "screen", idPattern = "SCR-*", defines = true),
        EntityDecl(type = "usecase", idPattern = "UC-*", defines = true),
    )

    private fun snapshot(): SemanticSnapshot = SemanticEngine.build(
        SemanticInput(
            1,
            listOf(
                SemanticDocument(DocumentId("a.yaml"), YamlIO.parse("id: SCR-1\nuses:\n  - UC-1\n  - UC-2"), schema, "node", entities),
                SemanticDocument(DocumentId("b.yaml"), YamlIO.parse("id: UC-1"), schema, "node", entities),
                SemanticDocument(DocumentId("c.yaml"), YamlIO.parse("id: UC-2"), schema, "node", entities),
            ),
        )
    )

    @Test
    fun `matchの連鎖で束縛の表が決定的に返る`() {
        val s = snapshot()
        val spec = QuerySpec.parse(
            mapOf(
                "match" to listOf(
                    mapOf("node" to mapOf("entity" to "screen", "bind" to "scr")),
                    mapOf("edge" to mapOf("relation" to "uses")),
                    mapOf("node" to mapOf("entity" to "usecase", "bind" to "uc")),
                )
            ),
            "spec",
        )
        val result = SemanticQuery.run(s.ids, s.refs, s.graph, spec) { null }
        assertEquals(listOf("scr", "uc"), result.columns)
        assertEquals(listOf("UC-1", "UC-2"), result.rows.map { it.bindings.getValue("uc").id })
        assertEquals("a.yaml", result.rows[0].bindings.getValue("scr").document?.value)
    }

    @Test
    fun `neighborIdsはoutとinの隣接をID昇順で返す`() {
        val s = snapshot()
        assertEquals(
            listOf("UC-1", "UC-2"),
            SemanticQuery.neighborIds(s.ids, s.refs, s.graph, listOf("SCR-1"), follow = "out"),
        )
        assertEquals(
            listOf("SCR-1"),
            SemanticQuery.neighborIds(s.ids, s.refs, s.graph, listOf("UC-1"), follow = "in"),
        )
    }
}
