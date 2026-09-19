package schema

/**
 * このスキーマが定義する ID への詳細化の充足要件（出現ベースのカバレッジルール）。
 * 「idPattern に一致する ID は、指定スキーマの文書に minCount 件以上出現すること」
 * （段階的詳細化の詳細文書の存在検証。R7-02）。
 *
 * 参照ベース（requiredBy＝被参照数の数え上げ）は R29-01（Core Calculus Phase 2 の
 * 語彙刈り込み MP-01・意図的差分 D-16）で削除した——数え上げの正準は
 * 関係レジストリの cardinality（1ホップ・無条件）と constraints の count / exists
 * （条件付き・多段）。coverage は「同一IDの出現集合への集約」という、参照では
 * 書けない演算（出現集合の Aggregate。CALC-21）の専用語彙として残る。
 */
data class CoverageRule(
    val id: String? = null,                       // ルールの識別子（表示用）
    val description: String? = null,              // 人間向けの説明
    val idPattern: String? = null,                // 対象 ID の glob（null = このスキーマの全 ID）
    val detailSchemaIds: List<String> = emptyList(),// 出現先スキーマ: このスキーマの文書への出現を数える
    val minCount: Int = 1,                        // 必要な出現数
    val level: CoverageLevel = CoverageLevel.WARNING,
    val activeFrom: String? = null,               // IDを定義する文書がこのフェーズに達してから判定（null = 常時）
)
