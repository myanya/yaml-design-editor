package semantic

import model.DocumentId
import schema.CoverageRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * カバレッジ判定コア（B-3・RB-03a で root Validator から移設）のテスト。
 * R29-01（意図的差分 D-16）で参照ベース（requiredBy）を削除し、出現ベース（detailIn）専用になった。
 */
class SemanticCoverageTest {

    private fun idOf(id: String, doc: String, schemaId: String? = null) =
        ScannedId(id, DocumentId(doc), emptyList(), null, schemaId, doc)

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
        )
        assertEquals(1, statuses.single().count)
        assertTrue(statuses.single().satisfied)
    }

    @Test
    fun `idPatternに一致しないIDのルールは対象外になる`() {
        val statuses = SemanticCoverage.statuses(
            id = "MEM-001",
            rules = listOf(CoverageRule(idPattern = "ISS-*", detailSchemaIds = listOf("detail"))),
            occurrences = emptyList(),
        )
        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `activeFromはフェーズ到達で判定が有効になり不明なら常時判定`() {
        val rule = CoverageRule(detailSchemaIds = listOf("detail"), activeFrom = "design")
        val order = listOf("draft", "design", "done")
        fun activeAt(current: Int?) = SemanticCoverage.statuses(
            "A", listOf(rule), emptyList(), phaseOrder = order, currentPhaseIndex = current,
        ).single().active

        assertFalse(activeAt(0))          // draft = 未到達
        assertTrue(activeAt(1))           // design = 到達
        assertTrue(activeAt(2))           // done = 通過済み
        assertTrue(activeAt(null))        // 定義文書のフェーズ不明 = 常時判定
        // phases 未宣言（order 空）= 常時判定
        assertTrue(
            SemanticCoverage.statuses("A", listOf(rule), emptyList()).single().active
        )
    }
}
