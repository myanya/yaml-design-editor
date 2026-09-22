package dev.toros.yde.app.schema

import dev.toros.yde.semantic.schema.idGlobToRegex

/**
 * ID入力支援（UX-64）: 既存IDの集合から「次の連番」を決定的に提案する純ロジック。
 * 形式の源は field の pattern（正規表現。UX-79）と entity 宣言・ビュー行の idPattern（glob）で、
 * 検証が読むのと同じ宣言を採番も読む（形式の源を増やさない）。
 */
object IdSuggest {

    private val TRAILING_NUMBER = Regex("""^(.*?)(\d+)$""")

    /** リテラル接頭辞 + \d{n}（または \d）だけの単純な pattern。既存ゼロ件時の最初のID生成に使う。 */
    private val SIMPLE_PATTERN = Regex("""^([A-Za-z0-9_-]+)\\d(?:\{(\d+)\})?$""")

    /**
     * 次の連番IDを提案する。提案できなければ null。
     * - existing のうち制約（pattern 正規表現、なければ idPatternGlobs のいずれか）に合い末尾が数字のIDを
     *   「数字を除いた接頭辞」でグループ化し、優先集合を含むグループ → 件数最大 → 接頭辞辞書順で
     *   選んだグループの最大番号 + 1 を、最大要素と同じ桁幅（ゼロ埋め維持）で返す。
     * - 該当する既存IDがない場合、pattern が「接頭辞 + \d{n}」の単純形なら最初のID（例 TASK-001）を作る。
     *
     * @param preferred 同じ文書・同じ配列の兄弟IDなど「この系列を優先したい」集合（グループ選択のバイアス）
     */
    fun next(
        existing: Collection<String>,
        pattern: String? = null,
        idPatternGlobs: List<String> = emptyList(),
        preferred: Collection<String> = emptyList(),
    ): String? {
        val regex = pattern?.let { runCatching { Regex(it) }.getOrNull() }
        val globs = idPatternGlobs.mapNotNull { g -> runCatching { idGlobToRegex(g) }.getOrNull() }
        fun allowed(id: String) = when {
            regex != null -> regex.matches(id)
            globs.isNotEmpty() -> globs.any { it.matches(id) }
            else -> true
        }
        val preferredSet = preferred.toSet()
        data class Numbered(val prefix: String, val digits: String, val id: String)
        val groups = existing.asSequence()
            .distinct()
            .filter(::allowed)
            .mapNotNull { id -> TRAILING_NUMBER.matchEntire(id)?.let { m -> Numbered(m.groupValues[1], m.groupValues[2], id) } }
            .groupBy { it.prefix }
        val group = groups.entries.sortedWith(
            compareByDescending<Map.Entry<String, List<Numbered>>> { e -> e.value.any { it.id in preferredSet } }
                .thenByDescending { it.value.size }
                .thenBy { it.key }
        ).firstOrNull()?.value ?: return firstFromPattern(pattern)
        val maxEntry = group.maxByOrNull { it.digits.toLongOrNull() ?: -1L } ?: return firstFromPattern(pattern)
        val maxNumber = maxEntry.digits.toLongOrNull() ?: return firstFromPattern(pattern)
        return maxEntry.prefix + (maxNumber + 1).toString().padStart(maxEntry.digits.length, '0')
    }

    /** pattern が「リテラル接頭辞 + \d{n}」の単純形なら最初のID（TASK-\d{3} → TASK-001）。 */
    private fun firstFromPattern(pattern: String?): String? {
        val m = pattern?.let { SIMPLE_PATTERN.matchEntire(it) } ?: return null
        val width = m.groupValues[2].toIntOrNull() ?: 1
        return m.groupValues[1] + "1".padStart(width, '0')
    }
}
