package dev.toros.yde.semantic.schema

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor

/**
 * 意味の基底型の値の表現（P33 第二弾）。`date` / `time` / `datetime` は Kotlin の型（FieldType）で、
 * 直列化（ファイル・編集）は正準の文字列、意味（存在の検証・順序・表示）は値（LocalDate 系）で扱う。
 * yaml-core には正準の形（canonicalPattern）だけが渡り（導出が string ＋ 形式に直す）、ここでの読みは
 * semantic-core の検証（存在）・式（順序）・表示（形）が使う。木（YNode）の中の値は文字列のまま。
 * 利用者の型はここから狭める（例 `予約時刻: { type: time, pattern: "^\\d{2}:(00|30)$" }`）。
 */
enum class ValueSemantics(
    val id: String,
    /** 指摘の文面に使う名前（日付・時刻・日時）。 */
    val label: String,
    /** 正準の形（直列化の形式。構造の検証に渡す）。 */
    val canonicalPattern: String,
    /** 入力欄の案内（利用者の型やヒントで上書きできる）。 */
    val placeholder: String,
    /** サンプル値。 */
    val sample: String,
    /** AI 生成の JSON Schema の format。 */
    val jsonFormat: String,
) {
    DATE("date", "日付", "^\\d{4}-\\d{2}-\\d{2}$", "YYYY-MM-DD", "2026-01-01", "date") {
        override fun parse(text: String): TemporalAccessor? =
            runCatching { LocalDate.parse(text.trim(), DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
    },
    TIME("time", "時刻", "^\\d{2}:\\d{2}(:\\d{2})?$", "HH:MM", "09:00", "time") {
        override fun parse(text: String): TemporalAccessor? =
            runCatching { LocalTime.parse(text.trim(), DateTimeFormatter.ISO_LOCAL_TIME) }.getOrNull()
    },
    DATETIME("datetime", "日時", "^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2})?$", "YYYY-MM-DD HH:MM", "2026-01-01 09:00", "date-time") {
        override fun parse(text: String): TemporalAccessor? =
            runCatching { LocalDateTime.parse(text.trim().replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME) }.getOrNull()
    };

    private val regex: Regex by lazy { Regex(canonicalPattern) }

    /** 正準の形か（形式の検証と同じ規則）。 */
    fun isCanonical(text: String): Boolean = regex.matches(text.trim())

    /**
     * 「今」をこの種類の正準の形の文字にする（保存時に自動で入る値 `auto: created / updated`。SemanticAutoValues が使う）。
     * 日付は yyyy-MM-dd・時刻は HH:mm:ss・日時は yyyy-MM-dd HH:mm:ss（直列化は文字列のまま。表示の形は ValueFormat）。
     */
    fun stamp(now: LocalDateTime): String = when (this) {
        DATE -> now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        TIME -> now.toLocalTime().format(STAMP_TIME)
        DATETIME -> now.format(STAMP_DATETIME)
    }

    /** 値として読む（存在しない日付・時刻は null）。正準の形であることは前提にしない。表示の形（ValueFormat）はこの値を整形する。 */
    abstract fun parse(text: String): TemporalAccessor?

    /** 正準の形で、値としても存在するか（式の順序や並びが「読める」と見なす条件）。 */
    fun reads(text: String): Boolean = isCanonical(text) && parse(text) != null

    /**
     * 順序の鍵（読める値だけ。null＝読めない）。同じ種類の鍵どうしを比べる（`09:30` と `09:30:00` は同じ時刻）。
     * LocalDate 系は自分の種類と比べられる Comparable で、ここでは同じ種類しか並べないため、型の印だけを落とす。
     */
    @Suppress("UNCHECKED_CAST")
    fun orderKey(text: String): Comparable<Any>? = if (reads(text)) parse(text) as? Comparable<Any> else null

    /** 2 つの値の順序（どちらかが読めなければ null＝比べられない）。 */
    fun compare(a: String, b: String): Int? {
        val x = orderKey(a) ?: return null
        val y = orderKey(b) ?: return null
        return x.compareTo(y)
    }

    companion object {
        fun of(id: String?): ValueSemantics? = entries.firstOrNull { it.id == id }

        private val STAMP_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val STAMP_DATETIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
