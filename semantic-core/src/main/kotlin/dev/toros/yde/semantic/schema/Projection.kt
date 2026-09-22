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

    /**
     * `{パス}` をスコープ連鎖（近い順）のスカラー値で置換する。パスは各スコープ要素からの相対YPath。
     * 最初に見つかったスカラー値を採用し、どのスコープにも無ければそのまま残す。
     */
    fun evaluate(template: String, scopes: List<YNode>): Evaluated {
        var complete = true
        val resolutions = LinkedHashMap<String, Resolution>()
        val text = PLACEHOLDER.replace(template) { m ->
            val placeholder = m.groupValues[1]
            val path = runCatching { parseYPath(placeholder) }.getOrNull()
            val hit = path?.let { p ->
                scopes.withIndex().firstNotNullOfOrNull { (i, scope) ->
                    scalarText(getAt(scope, p))?.let { i to it }
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
