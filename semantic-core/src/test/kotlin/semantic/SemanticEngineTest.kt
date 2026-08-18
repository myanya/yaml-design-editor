package semantic

import model.DocumentId
import schema.EntityDecl
import schema.EntityRole
import schema.StructuralSchemaParser
import yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import validate.Severity

/**
 * 意味解析エンジン（SemanticEngine）の契約テスト（Phase 5・SM-002）。
 * ファイルシステムなしで複数文書の集約・文書内重複・参照解決・逆参照を固定する。
 */
class SemanticEngineTest {

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
            - key: narrow
              type: ref
              idPatterns: ["IT-*"]
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

    @Test
    fun `複数文書からIDが集約されsourceVersionが写される`() {
        val snapshot = SemanticEngine.build(
            SemanticInput(
                workspaceVersion = 42,
                documents = listOf(
                    doc("items/b.yaml", "id: IT-002\ntitle: B"),
                    doc("items/a.yaml", "id: IT-001\ntitle: A\nlink: IT-002"),
                ),
            )
        )
        assertEquals(42, snapshot.sourceVersion)
        assertEquals(setOf("IT-001", "IT-002"), snapshot.ids.keys)
        assertTrue(snapshot.issues.isEmpty(), "問題なし: ${snapshot.issues}")
        // 文書ID順の安定化（a.yaml が先）
        assertEquals("items/a.yaml", snapshot.refs.single().document.value)
    }

    @Test
    fun `文書内の重複IDはERRORで文書間の同一IDは合法`() {
        val snapshot = SemanticEngine.build(
            SemanticInput(
                workspaceVersion = 1,
                documents = listOf(
                    doc("items/a.yaml", "id: IT-001\ntitle: 第1出現"),
                    doc("items/b.yaml", "id: IT-001\ntitle: 第2出現（合法）"),
                ),
            )
        )
        assertTrue(snapshot.issues.isEmpty(), "文書間の同一IDはエラーにしない: ${snapshot.issues}")
        assertEquals(2, snapshot.occurrences("IT-001").size)
    }

    @Test
    fun `未解決refとスコープ外refが区別して報告される`() {
        val snapshot = SemanticEngine.build(
            SemanticInput(
                workspaceVersion = 1,
                documents = listOf(
                    doc("items/a.yaml", "id: XX-001\ntitle: パターン外"),
                    doc("items/b.yaml", "id: IT-002\nlink: IT-404\nnarrow: XX-001"),
                ),
            )
        )
        val messages = snapshot.issues.map { it.message }
        assertTrue(messages.any { "「IT-404」" in it && "解決できません" in it }, "$messages")
        assertTrue(messages.any { "「XX-001」" in it && "対象外" in it }, "存在するがidPatterns対象外: $messages")
        assertEquals(2, snapshot.issues.size)
        assertTrue(snapshot.issues.all { it.severity == Severity.ERROR })
    }

    @Test
    fun `参照解決はDEFINING出現を優先する`() {
        val entities = listOf(EntityDecl(type = "item", idPattern = "IT-*", defines = true))
        val snapshot = SemanticEngine.build(
            SemanticInput(
                workspaceVersion = 1,
                documents = listOf(
                    doc("items/detail.yaml", "id: IT-001\ntitle: 詳細"),
                    doc("items/main.yaml", "id: IT-001\ntitle: 定義", entities = entities),
                ),
            )
        )
        assertEquals(EntityRole.DEFINING, snapshot.resolve("IT-001")?.role)
        assertEquals("定義", snapshot.resolve("IT-001")?.title)
    }

    @Test
    fun `backlinksToが被参照を返す`() {
        val snapshot = SemanticEngine.build(
            SemanticInput(
                workspaceVersion = 1,
                documents = listOf(
                    doc("items/a.yaml", "id: IT-001"),
                    doc("items/b.yaml", "id: IT-002\nlink: IT-001"),
                    doc("items/c.yaml", "id: IT-003\nlink: IT-001"),
                ),
            )
        )
        val backs = snapshot.backlinksTo(setOf("IT-001"))
        assertEquals(listOf("items/b.yaml", "items/c.yaml"), backs.map { it.document.value })
    }

    @Test
    fun `文書参照は文書IDの名前空間で解決される`() {
        val attributed = StructuralSchemaParser.parse(
            """
            id: doc-ref
            root:
              type: object
              fields:
                - key: id
                  type: id
                - key: spec
                  type: ref
                  target: document
            """.trimIndent(),
            fallbackId = "doc-ref",
        )
        fun d(name: String, yaml: String, documentId: String) = SemanticDocument(
            id = DocumentId(name), root = YamlIO.parse(yaml),
            schema = attributed, schemaId = "doc-ref", documentId = documentId,
        )
        val ok = SemanticEngine.build(
            SemanticInput(
                1,
                listOf(
                    d("docs/a.yaml", "id: A-1\nspec: spec-doc", documentId = "docs/a.yaml"),
                    d("docs/spec.yaml", "id: S-1", documentId = "spec-doc"),
                ),
            )
        )
        assertTrue(ok.issues.isEmpty(), "文書論理IDで解決される: ${ok.issues}")
        val ng = SemanticEngine.build(
            SemanticInput(1, listOf(d("docs/a.yaml", "id: A-1\nspec: missing-doc", documentId = "docs/a.yaml"))),
        )
        assertTrue(ng.issues.single().message.contains("文書参照「missing-doc」"))
    }
}
