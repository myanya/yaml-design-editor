package semantic

import model.DocumentId
import schema.CardinalityDef
import schema.CoverageLevel
import schema.EnforcementDef
import schema.EntityDecl
import schema.RelationDef
import schema.StructuralSchemaParser
import validate.Severity
import yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * グラフ整合検証カーネル（SemanticGraphIntegrity。R21-01）の契約テスト（Phase 6A）。
 * ルート validate/GraphIntegrityTest が旧経路（IdIndex シェル）との一致を引き続き保証する。
 */
class SemanticGraphIntegrityTest {

    private val schema = StructuralSchemaParser.parse(
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
            - key: peer
              type: ref
              relation: mystery
        """.trimIndent(),
        fallbackId = "node",
    )

    private val compEntities = listOf(EntityDecl(type = "comp", idPattern = "C-*", defines = true))

    private fun doc(name: String, yaml: String) = SemanticDocument(
        id = DocumentId(name),
        root = YamlIO.parse(yaml.trimIndent()),
        schema = schema,
        schemaId = "node",
        entities = compEntities,
    )

    private fun build(declarations: SemanticDeclarations, vararg docs: SemanticDocument) =
        SemanticEngine.build(SemanticInput(1, docs.toList(), declarations))

    @Test
    fun `未宣言のrelationはenforcementで指摘される`() {
        val snapshot = build(
            SemanticDeclarations(enforcement = EnforcementDef(undeclaredRelation = CoverageLevel.WARNING)),
            doc("a.yaml", "id: C-1\npeer: C-2"),
            doc("b.yaml", "id: C-2"),
        )
        val issue = snapshot.issues.single { "mystery" in it.message }
        assertEquals(Severity.WARNING, issue.severity)
        assertTrue("宣言されていません" in issue.message, issue.message)
    }

    @Test
    fun `対象型の不一致が参照値の座標へ指摘される`() {
        val snapshot = build(
            SemanticDeclarations(relations = mapOf("uses" to RelationDef(name = "uses", source = listOf("screen")))),
            doc("a.yaml", "id: C-1\nuses:\n  - C-2"),
            doc("b.yaml", "id: C-2"),
        )
        val issue = snapshot.issues.single { "参照元エンティティ型" in it.message }
        assertTrue("「comp」" in issue.message && "screen" in issue.message, issue.message)
        assertEquals("a.yaml", issue.document.value)
        assertEquals(listOf<Any>("uses", 0), issue.path)
    }

    @Test
    fun `多重度の不足と過剰が定義出現の座標へ指摘される`() {
        val snapshot = build(
            SemanticDeclarations(
                relations = mapOf(
                    "uses" to RelationDef(
                        name = "uses",
                        source = listOf("comp"),
                        target = listOf("comp"),
                        cardinality = CardinalityDef(outMin = 2, inMax = 0),
                    )
                )
            ),
            doc("a.yaml", "id: C-1\nuses:\n  - C-2"),
            doc("b.yaml", "id: C-2"),
        )
        val messages = snapshot.issues.map { it.message }
        assertTrue(messages.any { "「C-1」の関係「uses」の参照が不足" in it && "現在 1 件／下限 2 件" in it }, "$messages")
        assertTrue(messages.any { "「C-2」への関係「uses」の被参照が過剰" in it && "現在 1 件／上限 0 件" in it }, "$messages")
    }

    @Test
    fun `acyclic宣言の循環がERRORと経路つきで指摘される`() {
        val snapshot = build(
            SemanticDeclarations(relations = mapOf("uses" to RelationDef(name = "uses", acyclic = true))),
            doc("a.yaml", "id: C-1\nuses:\n  - C-2"),
            doc("b.yaml", "id: C-2\nuses:\n  - C-1"),
        )
        val cycleIssues = snapshot.issues.filter { "循環" in it.message }
        assertEquals(2, cycleIssues.size, "循環を構成する両エッジに指摘: ${snapshot.issues}")
        assertTrue(cycleIssues.all { it.severity == Severity.ERROR })
        assertTrue(cycleIssues.all { "C-1 → C-2 → C-1" in it.message }, "${cycleIssues.map { it.message }}")
    }

    @Test
    fun `レジストリが空でenforcementもなければ何も指摘しない`() {
        val snapshot = build(
            SemanticDeclarations(),
            doc("a.yaml", "id: C-1\nuses:\n  - C-2"),
            doc("b.yaml", "id: C-2"),
        )
        assertTrue(snapshot.issues.isEmpty(), "${snapshot.issues}")
    }
}
