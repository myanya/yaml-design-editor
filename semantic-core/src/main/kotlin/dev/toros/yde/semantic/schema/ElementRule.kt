package dev.toros.yde.semantic.schema

/**
 * 型の検査（オブジェクト型。P33 第三弾）: object の定義（名前付きでも、その場でも）に書く、**その型の要素ごとに評価する**検査。
 * 文書をスキーマに沿って歩いて、解決した型がその型の要素を見つけ、要素を「いまの要素」として既存の値の式の評価器で
 * `where` と `check` を評価する（評価は semantic.SemanticElementChecks）。`of` を省くといまの要素。ID の無い型でも書ける
 * （明細の「単価 × 数量 ＝ 金額」など）。指摘はその要素の場所。
 *
 * 書けるもの: `id`・`description`・`where`（要素を絞る条件。省けば全ての要素）・`check`（値の条件。すべて成立が期待）か
 * `expect: absent`（条件に合う要素があってはならない）・`level`・`message`（`{left}` `{right}` `{left.raw}` `{right.raw}` `{id}` `{path}`）。
 * 他の要素へ線を辿る検査（`match` の edge・`count`・`forAll`）は書けない——スキーマの `constraints` に `node: {entity: 型}` で書く。
 */
data class ElementRule(
    val id: String? = null,
    val description: String? = null,
    val where: List<QueryWhereClause> = emptyList(),
    val check: List<ConstraintCheck> = emptyList(),
    /** `expect: absent`（where に合う要素があってはならない）。check と排他。 */
    val absent: Boolean = false,
    val level: CoverageLevel = CoverageLevel.WARNING,
    val message: String? = null,
    /** 書かれた場所（例 `definitions.明細行.constraints[0]`）。指摘の出どころに使う。 */
    val origin: String = "",
) {
    /** 表示用の名前（説明 → ID → 出どころ）。 */
    val displayName: String get() = description ?: id ?: origin

    companion object {
        private val KEYS = setOf("id", "description", "where", "check", "expect", "level", "message")

        fun parse(raw: Any?, where: String): ElementRule {
            val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります")
            m.keys.map { it.toString() }.firstOrNull { it !in KEYS }?.let { k ->
                error(
                    when (k) {
                        "match", "also", "count", "forAll", "limit", "reportAt" ->
                            "$where: 型の検査に $k は書けません（他の要素へ線を辿る検査は、スキーマの constraints に node: {entity: 型} で書きます）"
                        else -> "$where: 未知のキーです: $k（使えるキー: ${KEYS.joinToString(", ")}）"
                    }
                )
            }
            val whereRaw = m["where"]
            val clauses = when (whereRaw) {
                null -> emptyList()
                is List<*> -> whereRaw.mapIndexed { i, e -> QueryWhereClause.parse(e, "$where.where[$i]", current = true) }
                else -> error("$where.where は条件の配列である必要があります")
            }
            val checkRaw = m["check"]
            val checks = when {
                checkRaw == null -> emptyList()
                checkRaw !is List<*> -> error("$where.check は配列である必要があります")
                checkRaw.isEmpty() -> error("$where.check には1項目以上が必要です")
                else -> checkRaw.mapIndexed { i, e -> ConstraintCheck.parse(e, "$where.check[$i]", current = true) }
            }
            // 要素の検査では、束縛を書けるのは「いまの要素」だけ（型の外の要素を読む検査は、スキーマの constraints へ）
            checks.flatMap { it.binds }.firstOrNull { it != QueryWhere.SELF }?.let { bind ->
                error("$where.check: 型の検査では of に束縛名（$bind）を書けません（of を省くと、その型の要素の値を読みます）")
            }
            clauses.flatMap { it.alternatives }.firstOrNull { it.of != QueryWhere.SELF }?.let { pred ->
                error("$where.where: 型の検査では of に束縛名（${pred.of}）を書けません（of を省くと、その型の要素の値を見ます）")
            }
            val absent = when (val e = m["expect"]?.toString()) {
                null -> false
                "absent" -> true
                else -> error("$where.expect に書けるのは absent（条件に合う要素があってはならない）だけです: $e")
            }
            if (absent && checks.isNotEmpty()) error("$where: expect: absent と check は併用できません")
            if (!absent && checks.isEmpty()) error("$where: check（値の条件）か expect: absent（where に合う要素があってはならない）が必要です")
            if (absent && clauses.isEmpty()) error("$where: expect: absent には where（絞る条件）が必要です")
            val level = when (val v = m["level"]?.toString()) {
                null -> CoverageLevel.WARNING
                else -> CoverageLevel.entries.firstOrNull { it.id == v } ?: error("$where.level は warning / error のいずれかです: $v")
            }
            return ElementRule(
                id = m["id"]?.toString()?.takeIf { it.isNotBlank() },
                description = m["description"]?.toString()?.takeIf { it.isNotBlank() },
                where = clauses,
                check = checks,
                absent = absent,
                level = level,
                message = m["message"]?.toString()?.takeIf { it.isNotBlank() },
                origin = where,
            )
        }
    }
}
