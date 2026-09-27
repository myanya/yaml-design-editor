package dev.toros.yde.app.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** ID自動採番（UX-64）: 連番提案の決定性・制約フィルタ・ゼロ埋め・初出生成のテスト。 */
class IdSuggestTest {

    @Test
    fun `最大番号の次をゼロ埋め幅を保って提案する`() {
        assertEquals("TASK-003", IdSuggest.next(listOf("TASK-001", "TASK-002")))
        assertEquals("TASK-010", IdSuggest.next(listOf("TASK-008", "TASK-009")))
        assertEquals("T-100", IdSuggest.next(listOf("T-99", "T-3")))
    }

    @Test
    fun `pattern 正規表現で系列を絞る`() {
        assertEquals(
            "TASK-002",
            IdSuggest.next(listOf("TASK-001", "NOTE-9", "NOTE-8", "NOTE-7"), pattern = """TASK-\d{3}"""),
        )
    }

    @Test
    fun `idPattern glob で系列を絞る`() {
        assertEquals(
            "SCR-2",
            IdSuggest.next(listOf("SCR-1", "TASK-5", "TASK-6"), idPatternGlobs = listOf("SCR-*")),
        )
    }

    @Test
    fun `優先集合（現在の文書のID）を含む系列を件数より優先する`() {
        val existing = listOf("A-1", "A-2", "B-1", "B-2", "B-3")
        assertEquals("A-3", IdSuggest.next(existing, preferred = listOf("A-1")))
        // 優先なしなら件数最大の系列
        assertEquals("B-4", IdSuggest.next(existing))
    }

    @Test
    fun `既存ゼロ件でも単純な pattern からは最初のIDを作る`() {
        assertEquals("TASK-001", IdSuggest.next(emptyList(), pattern = """TASK-\d{3}"""))
        assertEquals("T-1", IdSuggest.next(emptyList(), pattern = """T-\d"""))
        assertNull(IdSuggest.next(emptyList(), pattern = """[A-Z]+-\d+"""), "複雑な pattern からは生成しない")
        assertNull(IdSuggest.next(emptyList()))
    }

    @Test
    fun `末尾が数字のIDがなければ提案しない`() {
        assertNull(IdSuggest.next(listOf("alpha", "beta")))
    }

    @Test
    fun `接頭辞を決めた系列の次の連番（取り込みの採番）`() {
        assertEquals("TX-20210821-01", IdSuggest.nextInSeries(emptyList(), "TX-20210821-", 2))
        assertEquals(
            "TX-20210821-03",
            IdSuggest.nextInSeries(listOf("TX-20210821-02", "TX-20210821-01", "TX-20210822-05"), "TX-20210821-", 2),
        )
        assertEquals("TX-20210821-100", IdSuggest.nextInSeries(listOf("TX-20210821-99"), "TX-20210821-", 2), "桁があふれたら桁を増やす")
        assertEquals("TX-20210821-0004", IdSuggest.nextInSeries(listOf("TX-20210821-0003"), "TX-20210821-", 2), "既存の桁幅を保つ")
        assertEquals("A-01", IdSuggest.nextInSeries(listOf("A-x", "A-", "B-7"), "A-", 2), "数字でない残りは系列に入れない")
    }
}
