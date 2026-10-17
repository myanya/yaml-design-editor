package dev.toros.yde.semantic

import dev.toros.yde.yaml.model.DocumentId

// ================================================================ レンズの見え方の定義（kind: lens。architecture/views.md「レンズの見え方」）
//
// グラフレンズ（実体の段・型の段）の見え方のうち、ワークスペースで共有したい決まりを _views の定義文書に書く。
// スキーマと関係レジストリは「データの契約」で見え方を持たない（views.md 規律「画面合成のためのオプションをスキーマへ足さない」）。
// 節点の選択（型・関係の絞り込み・起点・畳み）は画面の操作（画面の覚え）のままで、ここには持たない。

/** 図に描く矢印の向き（表示だけ。検証・クエリの dir・バックリンクには関与しない）。 */
enum class LensArrow(val id: String) {
    /** 参照を書いた側から相手へ（文字は関係の label）。既定。 */
    FORWARD("forward"),

    /** 相手から参照を書いた側へ（文字は inverseLabel）。子が親を「導出元」と参照する書き方でも、図では親から子へ読ませる。 */
    REVERSE("reverse");

    companion object {
        fun parse(value: String?): LensArrow? = value?.let { v -> entries.firstOrNull { it.id == v } }
    }
}

/** 関係 1 つの見え方。 */
data class VirtualLensRelation(val name: String, val arrow: LensArrow = LensArrow.FORWARD)

/** レンズの見え方の定義（`kind: lens`）。複数の文書があれば、レンズは全部の定義を合わせて読む。 */
data class VirtualLensDef(
    override val id: String,
    override val name: String,
    override val description: String? = null,
    override val source: DocumentId,
    val relations: List<VirtualLensRelation> = emptyList(),
) : VirtualViewDefinition {
    /** 矢印を逆向きに描く関係名。 */
    val reversedRelations: Set<String> get() = relations.filter { it.arrow == LensArrow.REVERSE }.map { it.name }.toSet()
}
