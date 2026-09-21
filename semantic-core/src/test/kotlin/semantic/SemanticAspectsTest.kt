package semantic

import model.DocumentId
import model.YNode
import schema.EntityDecl
import schema.SchemaTreeParser
import yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * エンティティ合成カーネル（SemanticAspects。R23-01）の契約テスト（Phase 6B）。
 * ルート core/AspectTest がシェル（Path 座標）経由の一致を引き続き保証する。
 * 完了条件: 合成結果の各値が原本文書（DocumentId）と YPath の来歴を持つこと。
 */
class SemanticAspectsTest {

    private val structural = SchemaTreeParser.parse(
        """
        id: comp
        root:
          type: object
          fields:
            - key: id
              type: id
            - key: name
              type: string
            - key: memo
              type: string
        """.trimIndent(),
        fallbackId = "comp",
    )

    private val schemas = mapOf(
        "comp" to SchemaSemantics(
            id = "comp",
            tree = structural,
            entities = listOf(EntityDecl(type = "comp", idPattern = "C-*", defines = true)),
        )
    )

    private val docA = YamlIO.parse("id: C-1\nname: 部品A\nmemo: 定義側")
    private val docB = YamlIO.parse("id: C-1\nmemo: 詳細側")

    private fun compose(): SemanticEntityComposite {
        val engine = SemanticEngine.build(
            SemanticInput(
                1,
                listOf(
                    SemanticDocument(DocumentId("a.yaml"), docA, structural, "comp", schemas.getValue("comp").entities),
                    SemanticDocument(DocumentId("b.yaml"), docB, structural, "comp", schemas.getValue("comp").entities),
                ),
            )
        )
        val trees = mapOf("a.yaml" to docA, "b.yaml" to docB)
        return SemanticAspects.compose("C-1", engine.graph, schemas, emptyMap()) { d -> trees[d.value] }!!
    }

    @Test
    fun `合成フィールドは原本文書とYPathの来歴を持つ`() {
        val composite = compose()
        assertEquals("comp", composite.entityType)
        assertEquals("部品A", composite.title)
        val first = composite.sections.first()
        assertEquals("a.yaml", first.entry.document.value, "定義出現が先頭（両方definesのためa.yamlが定義）")
        val name = first.fields.single { it.key == "name" }
        assertEquals("a.yaml", name.document.value)
        assertEquals(listOf<Any>("name"), name.path)
        assertEquals("部品A", (name.value as? YNode.Scalar)?.value)
    }

    @Test
    fun `重複所有はduplicateOfで注記され値は隠されない`() {
        val composite = compose()
        val second = composite.sections[1]
        assertEquals("b.yaml", second.entry.document.value)
        val memo = second.fields.single { it.key == "memo" }
        assertEquals("a.yaml", memo.duplicateOf?.first?.value, "先行アスペクトの座標が注記される")
        assertEquals("詳細側", (memo.value as? YNode.Scalar)?.value, "値は隠さない")
        // 先行（定義側）の memo には注記がない
        assertNull(composite.sections[0].fields.single { it.key == "memo" }.duplicateOf)
    }

    @Test
    fun `所有しないキーのownerSectionはnullになる`() {
        val composite = compose()
        assertEquals("a.yaml", SemanticAspects.ownerSection(composite, "name")?.entry?.document?.value)
        assertTrue(SemanticAspects.ownerSection(composite, "unknown") == null)
    }
}
