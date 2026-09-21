package semantic

import model.DocumentId
import schema.EntityDecl
import schema.EntityRole
import schema.SchemaTreeParser
import yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 走査カーネル（SemanticScanner）の契約テスト（Phase 5・SM-001）。
 * ファイルI/O・Path なしで、スキーマあり／なし・入れ子・配列・属性付き参照・entity 宣言の
 * 走査規則が旧 index.DocScanner と同じであることを固定する。
 */
class SemanticScannerTest {

    private val schema = SchemaTreeParser.parse(
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
              relation: depends
            - key: parts
              type: array
              item:
                type: object
                fields:
                  - key: id
                    type: id
                  - key: name
                    type: string
        """.trimIndent(),
        fallbackId = "item",
    )

    private fun doc(yaml: String, withSchema: Boolean = true, entities: List<EntityDecl> = emptyList()) =
        SemanticDocument(
            id = DocumentId("items/a.yaml"),
            root = YamlIO.parse(yaml.trimIndent()),
            schema = if (withSchema) schema else null,
            schemaId = if (withSchema) "item" else null,
            entities = entities,
        )

    @Test
    fun `スキーマありでルートID・入れ子ID・refが検出される`() {
        val (ids, refs) = SemanticScanner.scan(
            doc(
                """
                id: IT-001
                title: アイテムA
                link: IT-002
                parts:
                  - id: PT-001
                    name: 部品1
                """
            )
        )
        assertEquals(listOf("IT-001", "PT-001"), ids.map { it.id })
        assertEquals("アイテムA", ids.first { it.id == "IT-001" }.title, "タイトルが表示解決される")
        assertEquals(listOf<Any>("parts", 0), ids.first { it.id == "PT-001" }.path)
        val ref = refs.single()
        assertEquals("IT-002", ref.targetId)
        assertEquals("depends", ref.relation)
        assertEquals("item", ref.sourceSchemaId)
    }

    @Test
    fun `entity宣言で型と役割が解決される`() {
        val entities = listOf(EntityDecl(type = "item", idPattern = "IT-*", defines = true))
        val (ids, _) = SemanticScanner.scan(doc("id: IT-001\ntitle: t", entities = entities))
        val entry = ids.single()
        assertEquals("item", entry.entityType)
        assertEquals(EntityRole.DEFINING, entry.role)
    }

    @Test
    fun `スキーマ外サブツリーは索引化しない`() {
        val (ids, refs) = SemanticScanner.scan(
            doc("id: IT-001\nunknown:\n  id: X-9\n  link: X-8")
        )
        assertEquals(listOf("IT-001"), ids.map { it.id }, "未知項目の境界で走査が止まる")
        assertTrue(refs.isEmpty())
    }

    @Test
    fun `スキーマなしは命名規約ヒューリスティクスで検出する`() {
        val (ids, refs) = SemanticScanner.scan(
            doc(
                """
                id: DOC-1
                owner_ref: ACT-1
                depends_on:
                  - DOC-2
                  - DOC-3
                """,
                withSchema = false,
            )
        )
        assertEquals(listOf("DOC-1"), ids.map { it.id })
        assertEquals(listOf("ACT-1", "DOC-2", "DOC-3"), refs.map { it.targetId })
        assertEquals(listOf<Any>("depends_on", 1), refs[2].path)
    }

    @Test
    fun `属性付き参照はto座標と属性の写しを持つ`() {
        val attributed = SchemaTreeParser.parse(
            """
            id: attributed
            root:
              type: object
              fields:
                - key: id
                  type: id
                - key: owner
                  type: ref
                  fields:
                    - key: role
                      type: string
            """.trimIndent(),
            fallbackId = "attributed",
        )
        val (_, refs) = SemanticScanner.scan(
            SemanticDocument(
                id = DocumentId("docs/a.yaml"),
                root = YamlIO.parse("id: SCR-1\nowner:\n  to: ACT-1\n  role: 管理者"),
                schema = attributed,
                schemaId = "attributed",
            )
        )
        val ref = refs.single()
        assertEquals("ACT-1", ref.targetId)
        assertEquals(listOf<Any>("owner", "to"), ref.path, "座標は to 値")
        assertEquals(mapOf("role" to "管理者"), ref.attributes)
    }

    @Test
    fun `ルートの_documentメタデータは索引化しない`() {
        val (ids, refs) = SemanticScanner.scan(
            doc("_document:\n  id: doc-logical\nid: IT-001", withSchema = false)
        )
        assertEquals(listOf("IT-001"), ids.map { it.id })
        assertTrue(refs.isEmpty())
    }
}
