package schema

/**
 * 網羅マトリクス検証の宣言（R28-03。ARCHITECTURE §4.32）。スキーマの `matrix:` に列挙する。
 *
 * 「行の各エンティティ × 列の各エンティティ」の全組合せ（セル）について、行と列の両方を
 * 参照する**証人（witness）**エンティティが存在することを検証する
 * （例: 処理 × 試験観点 → テスト項目。テスト項目一覧の「機械的抽出」規約の宣言化）。
 *
 * - 行・列・証人の選択はクエリの node ステップと同じ語彙（entity / schema / id。bind は不可）
 * - 証人から行・列への参照は relation で指定する（`row: { relation: ... }` の形＝
 *   関係改名リファクタリング（rename-relation）の追随がクエリと同じ walker で効く）
 * - あえて対象外とするセルは、証人側に `excluded: true` の行として残す（証人が存在すれば
 *   セルは充足＝明示的除外の流儀 UX-16。理由はデータとして記録される）
 * - 列の母集団が空のときは判定しない（判定保留の哲学 §4.26）
 */
data class MatrixWitness(
    val node: QueryNodeStep,
    val rowRelation: String,
    val columnRelation: String,
) {
    companion object {
        fun parse(raw: Any?, where: String): MatrixWitness {
            val m = raw as? Map<*, *> ?: error("$where.witness はobjectである必要があります")
            m.keys.map { it.toString() }.firstOrNull { it !in setOf("node", "row", "column") }
                ?.let { error("$where.witness: 未知のキーです: $it（使えるキー: node, row, column）") }
            val node = parseSelector(m["node"], "$where.witness.node")
            return MatrixWitness(
                node = node,
                rowRelation = parseRelationOf(m["row"], "$where.witness.row"),
                columnRelation = parseRelationOf(m["column"], "$where.witness.column"),
            )
        }

        private fun parseRelationOf(raw: Any?, where: String): String {
            val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります（{relation: 関係名}）")
            m.keys.map { it.toString() }.firstOrNull { it != "relation" }
                ?.let { error("$where: 未知のキーです: $it（使えるキー: relation）") }
            return m["relation"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.relation が必要です")
        }
    }
}

/** マトリクスのノード選択（行・列・証人）のパース: kind: entity 限定・bind 不可・条件は1つ以上。 */
private fun parseSelector(raw: Any?, where: String): QueryNodeStep {
    val step = QueryNodeStep.parse(raw ?: error("$where が必要です"), where)
    require(step.kind == QueryNodeKind.ENTITY) { "$where: マトリクスの選択は kind: entity のみです" }
    require(step.bind == null) { "$where: マトリクスの選択に bind は書けません" }
    require(step.entity != null || step.schema != null || step.idPattern != null) {
        "$where: entity / schema / id のいずれかで母集団を絞る必要があります"
    }
    return step
}

data class MatrixRule(
    val id: String? = null,
    val description: String? = null,
    val rows: QueryNodeStep,
    val columns: QueryNodeStep,
    val witness: MatrixWitness,
    val level: CoverageLevel = CoverageLevel.WARNING,
    val activeFrom: String? = null,
) {
    companion object {
        private val KEYS = setOf("id", "description", "rows", "columns", "witness", "level", "activeFrom")

        fun parse(raw: Any?, where: String): MatrixRule {
            val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります")
            m.keys.map { it.toString() }.firstOrNull { it !in KEYS }
                ?.let { error("$where: 未知のキーです: $it（使えるキー: ${KEYS.joinToString(", ")}）") }
            val level = when (val v = m["level"]?.toString()) {
                null -> CoverageLevel.WARNING
                else -> CoverageLevel.entries.firstOrNull { it.id == v } ?: error("$where.level は warning / error のいずれかです: $v")
            }
            return MatrixRule(
                id = m["id"]?.toString()?.takeIf { it.isNotBlank() },
                description = m["description"]?.toString()?.takeIf { it.isNotBlank() },
                rows = parseSelector(m["rows"], "$where.rows"),
                columns = parseSelector(m["columns"], "$where.columns"),
                witness = MatrixWitness.parse(m["witness"], where),
                level = level,
                activeFrom = m["activeFrom"]?.toString()?.takeIf { it.isNotBlank() },
            )
        }
    }
}
