package schema

/**
 * このスキーマが定義する ID への充足要件（トレーサビリティのカバレッジルール）。
 * - 参照ベース（`requiredBy`）: 「idPattern に一致する ID は、条件を満たす参照を minCount 件以上受けること」
 * - 出現ベース（`detailIn`）: 「idPattern に一致する ID は、指定スキーマの文書に minCount 件以上出現すること」
 *   （段階的詳細化の詳細文書の存在検証。R7-02）
 * B-3（RB-03a）で root schema/Schema.kt から移動（判定コア semantic.SemanticCoverage と宣言を
 * semantic-core に揃える。§17-⑧ 検証族の第一弾）。同一パッケージのため root 側の利用箇所は変更なし。
 */
data class CoverageRule(
    val id: String? = null,                       // ルールの識別子（表示用）
    val description: String? = null,              // 人間向けの説明
    val idPattern: String? = null,                // 対象 ID の glob（null = このスキーマの全 ID）
    val fromSchemaIds: List<String> = emptyList(),// 参照元文書のスキーマIDを限定（空 = 制限なし。参照ベースのみ）
    val relation: String? = null,                 // 参照の関係種別を限定（null = 制限なし。参照ベースのみ）
    val detailSchemaIds: List<String> = emptyList(),// 空でなければ出現ベース: このスキーマの文書への出現を数える
    val minCount: Int = 1,                        // 必要な参照数（出現ベースでは出現数）
    val level: CoverageLevel = CoverageLevel.WARNING,
    val activeFrom: String? = null,               // IDを定義する文書がこのフェーズに達してから判定（null = 常時）
) {
    /** true = 出現ベース（詳細文書の存在検証）、false = 参照ベース（被参照数）。 */
    val isOccurrence: Boolean get() = detailSchemaIds.isNotEmpty()
}
