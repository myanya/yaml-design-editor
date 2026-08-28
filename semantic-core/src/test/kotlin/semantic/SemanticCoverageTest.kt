package semantic

import model.DocumentId
import schema.CoverageRule
import schema.RefTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** カバレッジ判定コア（B-3・RB-03a で root Validator から移設）のテスト。判定規則は旧実装と同一。 */
class SemanticCoverageTest {

    private fun idOf(id: String, doc: String, schemaId: String? = null) =
        ScannedId(id, DocumentId(doc), emptyList(), null, schemaId, doc)

    private fun refOf(
        target: String,
        doc: String,
        relation: String? = null,
        sourceSchemaId: String? = null,
        targetKind: RefTarget = RefTarget.ENTITY,
    ) = ScannedRef(target, DocumentId(doc), listOf("x"), "ref", relation = relation, sourceSchemaId = sourceSchemaId, targetKind = targetKind)

    @Test
    fun `参照ベースはrelationと参照元スキーマで絞り込み文書参照は数えない`() {
        val inbound = listOf(
            refOf("A", "a.yaml", relation = "uses", sourceSchemaId = "s1"),
            refOf("A", "b.yaml", relation = "derives", sourceSchemaId = "s1"),
            refOf("A", "c.yaml", relation = "uses", sourceSchemaId = "s2"),
            refOf("A", "d.yaml", relation = "uses", sourceSchemaId = "s1", targetKind = RefTarget.DOCUMENT),
        )
        val statuses = SemanticCoverage.statuses(
            id = "A",
            rules = listOf(CoverageRule(relation = "uses", fromSchemaIds = listOf("s1"), minCount = 2)),
            occurrences = emptyList(),
            inbound = inbound,
        )
        assertEquals(1, statuses.size)
        assertEquals(1, statuses.single().count)
        assertFalse(statuses.single().satisfied)
    }

    @Test
    fun `出現ベースは指定スキーマ群の文書への出現を数える`() {
        val occurrences = listOf(
            idOf("A", "list.yaml", schemaId = "list"),
            idOf("A", "detail.yaml", schemaId = "detail"),
        )
        val statuses = SemanticCoverage.statuses(
            id = "A",
            rules = listOf(CoverageRule(detailSchemaIds = listOf("detail"))),
            occurrences = occurrences,
            inbound = emptyList(),
        )
        assertEquals(1, statuses.single().count)
        assertTrue(statuses.single().satisfied)
    }

    @Test
    fun `idPatternに一致しないIDのルールは対象外になる`() {
        val statuses = SemanticCoverage.statuses(
            id = "MEM-001",
            rules = listOf(CoverageRule(idPattern = "ISS-*")),
            occurrences = emptyList(),
            inbound = emptyList(),
        )
        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `activeFromはフェーズ到達で判定が有効になり不明なら常時判定`() {
        val rule = CoverageRule(activeFrom = "design")
        val order = listOf("draft", "design", "done")
        fun activeAt(current: Int?) = SemanticCoverage.statuses(
            "A", listOf(rule), emptyList(), emptyList(), phaseOrder = order, currentPhaseIndex = current,
        ).single().active

        assertFalse(activeAt(0))          // draft = 未到達
        assertTrue(activeAt(1))           // design = 到達
        assertTrue(activeAt(2))           // done = 通過済み
        assertTrue(activeAt(null))        // 定義文書のフェーズ不明 = 常時判定
        // phases 未宣言（order 空）= 常時判定
        assertTrue(
            SemanticCoverage.statuses("A", listOf(rule), emptyList(), emptyList()).single().active
        )
    }
}
