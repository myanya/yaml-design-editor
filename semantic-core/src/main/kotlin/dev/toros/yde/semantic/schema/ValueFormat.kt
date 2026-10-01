package dev.toros.yde.semantic.schema

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 表示の形（P33 第二弾の手順 3）: 値と表示形式の分離。ファイルと編集中は素の値（20000・2026-10-01）のままで、
 * 画面で見せるときだけ型のヒント `format` で文字を組み立てる（¥20,000・2026年10月1日）。Excel の「値と表示形式」と同じ考え方。
 * - 数（int / number）は DecimalFormat のパターン（`¥#,##0`・`#,##0.00`。記号は英語圏の形＝小数点 `.`・桁区切り `,`）
 * - 日付・時刻・日時は DateTimeFormatter のパターン（`yyyy年M月d日`・`H:mm`）。値は ValueSemantics で読む
 * - `display: grouped`（桁区切り）は今までどおり NumberDisplay の整形（小数の桁を変えない）
 * 形が無い・値が読めない・パターンが不正なときは素の文字をそのまま返す（見せ方で値を隠さない）。CLI・書き出し・検証は使わない。
 */
object ValueFormat {

    /** 項目の型とヒントで整形した表示の文字。 */
    fun display(field: FieldDef, raw: String): String {
        val format = field.format
        if (format != null) return display(field.type, format, raw)
        val grouped = field.hints.numberDisplay?.takeIf { field.type == FieldType.INT || field.type == FieldType.NUMBER }
        return grouped?.format(raw) ?: raw
    }

    /**
     * 基底の種類とパターンで整形する。種類が string / any のとき（ビューの値の式の列など、型の分からない値）は、
     * 数として読めれば数のパターン、日付・時刻・日時として読めればその種類のパターンとして試す。
     */
    fun display(type: FieldType, format: String, raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return raw
        return when (type) {
            FieldType.INT, FieldType.NUMBER -> number(format, text) ?: raw
            FieldType.DATE, FieldType.TIME, FieldType.DATETIME -> temporal(type.value!!, format, text) ?: raw
            FieldType.STRING, FieldType.ANY -> number(format, text)
                ?: ValueSemantics.entries.firstNotNullOfOrNull { temporal(it, format, text) }
                ?: raw
            else -> raw
        }
    }

    /**
     * パターンが型に対して読めるか（スキーマの定義の点検）。読めなければ理由の文面、読めれば null。
     * 書けるのは数（int / number）と日付・時刻・日時だけ。
     */
    fun validate(type: FieldType, format: String): String? = when (type) {
        FieldType.INT, FieldType.NUMBER ->
            runCatching { DecimalFormat(format, symbols) }.exceptionOrNull()?.let { "format が数の形（DecimalFormat）として読めません: $format" }
        FieldType.DATE, FieldType.TIME, FieldType.DATETIME ->
            runCatching { DateTimeFormatter.ofPattern(format, locale) }.exceptionOrNull()
                ?.let { "format が${type.value!!.label}の形（DateTimeFormatter）として読めません: $format" }
        else -> "format は int / number / date / time / datetime 型でのみ使用できます"
    }

    private val symbols: DecimalFormatSymbols = DecimalFormatSymbols(Locale.US)
    private val locale: Locale = Locale.JAPAN

    private fun number(format: String, text: String): String? {
        val value = text.toBigDecimalOrNull() ?: return null
        return runCatching { DecimalFormat(format, symbols).format(value) }.getOrNull()
    }

    private fun temporal(value: ValueSemantics, format: String, text: String): String? {
        val parsed = value.parse(text) ?: return null
        if (!value.isCanonical(text)) return null
        return runCatching { DateTimeFormatter.ofPattern(format, locale).format(parsed) }.getOrNull()
    }
}
