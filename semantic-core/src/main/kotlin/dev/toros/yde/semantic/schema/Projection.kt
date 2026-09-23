package dev.toros.yde.semantic.schema

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.scalarText

/**
 * 決定論的射影の評価器（P16: R16-02）。`{パス}` プレースホルダのテンプレート置換と値辞書を提供する。
 * ビューの計算列（core/Views）・将来の整合検証・スキャフォールドが共有する単一実装（ARCHITECTURE §4.26）。
 * - 1段評価: プレースホルダはスコープ連鎖（近い順）の**原本の値**だけを参照する（計算値の上の計算はしない）
 * - 決定論: 同じ入力に対して常に同じ出力。任意式・コード実行は持たない
 * - 失敗は作業を止めない: 解決できないプレースホルダは `{...}` のまま残す（表示で気づける）
 */
object Projection {

    /** プレースホルダ1件の解決結果（来歴。R16-04）。scopeIndex はどのスコープが解決したか（null = 未解決）。 */
    data class Resolution(val placeholder: String, val scopeIndex: Int?, val value: String?)

    /** テンプレート評価の結果。complete=false は未解決のプレースホルダが残っている。 */
    data class Evaluated(
        val text: String,
        val complete: Boolean,
        /** プレースホルダごとの解決の来歴（初出順・重複除去）。 */
        val resolutions: List<Resolution> = emptyList(),
    )

    private val PLACEHOLDER = Regex("""\{([^{}]+)}""")

    /** テンプレートの中の穴の名前（初出順・重複なし）。 */
    fun placeholders(template: String): List<String> = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.distinct().toList()

    /** 残っている穴をすべて同じ値で埋める（定義の点検で代表値を得るためのもの）。 */
    fun fillRemaining(text: String, value: String): String = PLACEHOLDER.replace(text) { value }

    /**
     * `{パス}` をスコープ連鎖（近い順）のスカラー値で置換する。パスは各スコープ要素からの相対YPath。
     * 最初に見つかったスカラー値を採用し、どのスコープにも無ければそのまま残す。
     * holeValue は、穴に入る値の直し方（ファイル名に使える文字に直す、など。来歴にも直した後の値が残る）。
     */
    fun evaluate(template: String, scopes: List<YNode>, holeValue: (String) -> String = { it }): Evaluated {
        var complete = true
        val resolutions = LinkedHashMap<String, Resolution>()
        val text = PLACEHOLDER.replace(template) { m ->
            val placeholder = m.groupValues[1]
            val path = runCatching { parseYPath(placeholder) }.getOrNull()
            val hit = path?.let { p ->
                scopes.withIndex().firstNotNullOfOrNull { (i, scope) ->
                    scalarText(getAt(scope, p))?.let { i to holeValue(it) }
                }
            }
            resolutions.putIfAbsent(placeholder, Resolution(placeholder, hit?.first, hit?.second))
            hit?.second ?: m.value.also { complete = false }
        }
        return Evaluated(text, complete, resolutions.values.toList())
    }

    /** 値辞書（`DeriveTypeRule`・skill列マッピングと同じ語彙）: 辞書にあれば置換、なければそのまま。 */
    fun applyMap(value: String, map: Map<String, String>): String = map[value] ?: value
}
