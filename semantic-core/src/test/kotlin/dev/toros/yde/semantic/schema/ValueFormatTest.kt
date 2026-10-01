package dev.toros.yde.semantic.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 表示の形（format）のテスト: 値は素のまま、画面で見せる文字だけをパターンで組み立てる（値と表示形式の分離）。
 */
class ValueFormatTest {

    private fun field(type: FieldType, format: String? = null, grouped: Boolean = false) =
        FieldDef(type = type, hints = FieldHints(format = format, numberDisplay = if (grouped) NumberDisplay.GROUPED else null))

    @Test
    fun `数はDecimalFormatのパターンで整形し 値は変えない`() {
        assertEquals("¥20,000", ValueFormat.display(field(FieldType.INT, "¥#,##0"), "20000"))
        assertEquals("1,234.50", ValueFormat.display(field(FieldType.NUMBER, "#,##0.00"), "1234.5"))
        assertEquals("12.5%", ValueFormat.display(field(FieldType.NUMBER, "0.0%"), "0.125"))
        assertEquals("20,000 円", ValueFormat.display(field(FieldType.INT, "#,##0 円"), "20000"))
        // 読めない値・空はそのまま
        assertEquals("abc", ValueFormat.display(field(FieldType.INT, "¥#,##0"), "abc"))
        assertEquals("", ValueFormat.display(field(FieldType.INT, "¥#,##0"), ""))
    }

    @Test
    fun `桁区切りの短縮 display grouped は今までどおり NumberDisplay で整形し format が優先する`() {
        assertEquals("12,345.5", ValueFormat.display(field(FieldType.NUMBER, grouped = true), "12345.5"))
        assertEquals("¥12,346", ValueFormat.display(field(FieldType.NUMBER, "¥#,##0", grouped = true), "12345.5"))
        assertEquals("12345", ValueFormat.display(field(FieldType.INT), "12345"))
    }

    @Test
    fun `日付と時刻と日時はDateTimeFormatterのパターンで整形する`() {
        assertEquals("2026年10月1日", ValueFormat.display(field(FieldType.DATE, "yyyy年M月d日"), "2026-10-01"))
        assertEquals("9:05", ValueFormat.display(field(FieldType.TIME, "H:mm"), "09:05"))
        assertEquals("10/1 09:30", ValueFormat.display(field(FieldType.DATETIME, "M/d HH:mm"), "2026-10-01 09:30"))
        // 存在しない日付・正準でない形はそのまま
        assertEquals("2026-02-30", ValueFormat.display(field(FieldType.DATE, "yyyy年M月d日"), "2026-02-30"))
        assertEquals("2026/10/01", ValueFormat.display(field(FieldType.DATE, "yyyy年M月d日"), "2026/10/01"))
    }

    @Test
    fun `型の分からない値（ビューの値の式の列）は数か日付として読めれば整形する`() {
        assertEquals("¥3,000", ValueFormat.display(FieldType.ANY, "¥#,##0", "3000"))
        assertEquals("2026年10月1日", ValueFormat.display(FieldType.ANY, "yyyy年M月d日", "2026-10-01"))
        assertEquals("abc", ValueFormat.display(FieldType.ANY, "yyyy年M月d日", "abc"))
    }

    @Test
    fun `パターンの点検は型に合わない format と読めないパターンを誤りにする`() {
        assertNull(ValueFormat.validate(FieldType.INT, "¥#,##0"))
        assertNull(ValueFormat.validate(FieldType.DATE, "yyyy年M月d日"))
        assertNotNull(ValueFormat.validate(FieldType.STRING, "¥#,##0"))
        assertNotNull(ValueFormat.validate(FieldType.INT, "#,##0.##0.#"))
        assertNotNull(ValueFormat.validate(FieldType.DATE, "yyyy-MM-dd-QQ-X-x-p"))
        // 読めないパターンは表示でも素の値のまま
        assertEquals("20000", ValueFormat.display(field(FieldType.INT, "#,##0.##0.#"), "20000"))
    }
}
