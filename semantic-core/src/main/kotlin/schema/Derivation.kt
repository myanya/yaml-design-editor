package schema

import model.YPath

/**
 * 決定論的導出の宣言モデル（R16-02）。ビューの計算列（`computed:`）が使う（ARCHITECTURE §4.26）。
 * スキーマの整合検証（`consistency`）は P29 Phase 6 第1段で組込み定義体（YAML）になり、同じ内容を
 * 値の式（schema.ValueExpr）として書く——専用の規則の型（ConsistencyRule）は持たない。
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
