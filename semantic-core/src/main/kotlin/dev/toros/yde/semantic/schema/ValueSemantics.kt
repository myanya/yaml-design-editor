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

    /** 値として読む（存在しない日付・時刻は null）。正準の形であることは前提にしない。表示の形（ValueFormat）はこの値を整形する。 */
    abstract fun parse(text: String): TemporalAccessor?

    /** 正準の形で、値としても存在するか（式の順序や並びが「読める」と見なす条件）。 */
    fun reads(text: String): Boolean = isCanonical(text) && parse(text) != null

    companion object {
        fun of(id: String?): ValueSemantics? = entries.firstOrNull { it.id == id }
    }
}
