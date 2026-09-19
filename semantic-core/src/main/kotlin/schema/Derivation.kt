package schema

import model.YPath

/**
 * 決定論的導出の宣言モデル（R16-02 / R16-03a）。ビューの計算列（`computed:`）とスキーマの
 * 整合ルール（`consistency.expected`）が同じ語彙を共有する（ARCHITECTURE §4.26）。
 * 解決（対象ID→出現→スコープ→評価）は `index/Derivation.kt`、評価は `Projection` が担う。
 */

/** 出現の選択条件。schemaId / fileGlob の AND（null は制限なし）。ビューの ViewSource と同じ意味論。 */
data class DerivationSelector(
    val schemaId: String? = null,
    val fileGlob: String? = null,
) {
    private val regex: Regex? by lazy { fileGlob?.let { runCatching { globToRegex(it) }.getOrNull() } }

    fun matches(occSchemaId: String?, relPath: String): Boolean =
        (schemaId == null || occSchemaId == schemaId) &&
            (regex == null || regex!!.matches(relPath))

    val isEmpty: Boolean get() = schemaId == null && fileGlob == null
}

/** lookup（1ホップ）: 起点要素の ref フィールド（via）を辿り、対象IDを参照先へ切り替える。 */
data class DerivationLookup(
    val from: DerivationSelector = DerivationSelector(),  // 参照値を読む出現の選択
    val via: YPath,                                       // その出現要素からの相対YPath（ref 型フィールド）
)

/**
 * 導出1件の宣言: 対象ID（lookup 指定時は参照を1ホップ辿った先、省略時は起点のID自身）の
 * source 出現から元値（テンプレート）を読み、スコープ連鎖で `{パス}` を評価する。
 * プレースホルダの解決順は「source 要素 → values で選んだ出現要素 → lookup の起点要素」（1段評価）。
 */
data class DerivationDef(
    val lookup: DerivationLookup? = null,
    val source: DerivationSelector,                          // 元値（テンプレート）を読む出現の選択
    val path: YPath,                                         // source 出現要素からの相対YPath
    val values: List<DerivationSelector> = emptyList(),      // プレースホルダ解決に使う対象IDの出現（宣言順）
    val map: Map<String, String> = emptyMap(),               // 評価後の値辞書（DeriveTypeRule / skill と同じ語彙）
    val default: String? = null,                             // 元値が無いときの既定値
)

/**
 * 整合ルールの判定方法（R16-03b: 適合判定）。
 * - EQUALS: 宣言値が導出値と一致すること（既定）
 * - LTE / GTE: 宣言値が導出値**以下／以上**であること（両方を数値として比較。数値でなければ判定保留）
 *   例: 画面入力の maxLength は、参照する値型の maxLength 以下なら適合（等値でなくてよい）
 * 判定の実体は check 判定述語と同じ要素述語（`op`。評価は semantic.SemanticExpression.compare＝R29-05）。
 */
enum class ConsistencyAssert(val id: String, val op: CheckOp) {
    EQUALS("equals", CheckOp.EQ),
    LTE("lte", CheckOp.LTE),
    GTE("gte", CheckOp.GTE);
}

/**
 * 整合ルール（R16-03a/b）: 「この文書のID要素の `path` の値は、`expected` の導出値と
 * `assert` の関係（一致／以下／以上）を満たすこと」。
 * 検査される文書のスキーマが宣言し、判定は `Validator.validate` に一元化する（ARCHITECTURE §4.26）。
 */
data class ConsistencyRule(
    val id: String? = null,                       // ルールの識別子（表示用）
    val description: String? = null,              // 人間向けの説明
    val idPattern: String? = null,                // 対象ID要素の glob（null = 全ID要素）
    val path: YPath,                              // 検査する値のID要素からの相対YPath
    val expected: DerivationDef,                  // 期待値の導出
    val assert: ConsistencyAssert = ConsistencyAssert.EQUALS,  // 判定方法（R16-03b）
    val level: CoverageLevel = CoverageLevel.WARNING,
    val activeFrom: String? = null,               // 文書がこのフェーズに達してから判定（null = 常時）
)
