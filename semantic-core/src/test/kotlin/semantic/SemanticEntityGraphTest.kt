package semantic

import model.DocumentId
import schema.EntityDecl
import schema.StructuralSchemaParser
import yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * エンティティグラフのカーネル版（SemanticEntityGraph）の契約テスト（Phase 5・SM-003）。
 * ルート index.EntityGraph と同じ導出規則（定義出現優先・最内出現・文書ノード）を固定する。
 */
class SemanticEntityGraphTest {

    private val schema = StructuralSchemaParser.parse(
        """
        id: item
        root:
          type: object
          fields:
            - key: id
              type: id
            - key: title
              type: string
            - key: link
              type: ref
            - key: parts
              type: array
              item:
                type: object
                fields:
                  - key: id
                    type: id
                  - key: link
                    type: ref
        """.trimIndent(),
        fallbackId = "item",
    )

    private fun doc(name: String, yaml: String, entities: List<EntityDecl> = emptyList()) =
        SemanticDocument(
            id = DocumentId(name),
            root = YamlIO.parse(yaml.trimIndent()),
            schema = schema,
            schemaId = "item",
            entities = entities,
        )

    private fun build(vararg docs: SemanticDocument) =
        SemanticEngine.build(SemanticInput(1, docs.toList())).graph

    @Test
    fun `エンティティ表は定義出現の型とタイトルを優先する`() {
        val entities = listOf(EntityDecl(type = "item", idPattern = "IT-*", defines = true))
        val graph = build(
            doc("items/detail.yaml", "id: IT-001\ntitle: 詳細側"),
            doc("items/main.yaml", "id: IT-001\ntitle: 定義側", entities = entities),
        )
        val node = graph.entities.getValue("IT-001")
        assertEquals("item", node.entityType)
        assertEquals("定義側", node.title)
        assertEquals("items/main.yaml", node.defining?.document?.value)
        assertEquals(2, node.occurrences.size)
    }

    @Test
    fun `sourceEntryOfは参照を書いた最内のID出現を返す`() {
        val graph = build(
            doc(
                "items/a.yaml",
                """
                id: IT-001
                parts:
                  - id: PT-001
                    link: IT-002
                """,
            ),
            doc("items/b.yaml", "id: IT-002"),
        )
        val ref = graph.outgoingRefs("PT-001").single()
        assertEquals("IT-002", ref.targetId)
        assertEquals("PT-001", graph.sourceEntryOf(ref)?.id, "ルートIT-001ではなく最内のPT-001")
    }

    @Test
    fun `outgoingRefsは出現サブツリー配下の参照を返す`() {
        val graph = build(
            doc(
                "items/a.yaml",
                """
                id: IT-001
                link: IT-002
                parts:
                  - id: PT-001
                    link: IT-003
                """,
            ),
        )
        // ルート IT-001 のサブツリーには両方の参照が含まれる
        assertEquals(listOf("IT-002", "IT-003"), graph.outgoingRefs("IT-001").map { it.targetId })
        // 部品 PT-001 の配下は自分の参照だけ
        assertEquals(listOf("IT-003"), graph.outgoingRefs("PT-001").map { it.targetId })
    }

    @Test
    fun `文書ノードが包含エンティティを持つ`() {
        val graph = build(
            doc("items/a.yaml", "id: IT-001\nparts:\n  - id: PT-001"),
            doc("items/b.yaml", "id: IT-002"),
        )
        assertEquals(listOf("IT-001", "PT-001"), graph.documents.getValue("items/a.yaml").entityIds)
        assertTrue("items/b.yaml" in graph.documents.keys)
    }
}
