package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.TemplateCondition
import dev.toros.yde.yaml.model.DocumentId

// ================================================================ レンズの見え方の定義（kind: lens。architecture/views.md「レンズの見え方」）
//
// グラフレンズ（実体の段・型の段）の見え方のうち、ワークスペースで共有したい決まりを _views の定義文書に書く。
// スキーマと関係レジストリは「データの契約」で見え方を持たない（views.md 規律「画面合成のためのオプションをスキーマへ足さない」）。
// 定義は箱の絞り込み（filters）の既定を持てる（ADR-0027）。入れる・外すと、型・関係のチェック・起点・畳みは画面の操作（画面の覚え）。

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

/** 絞り込みの向き: 条件に合う箱を隠すか、条件に合う箱だけを見せるか。 */
enum class LensFilterMode(val id: String) {
    /** 条件に合う箱を隠す。 */
    HIDE("hide"),

    /** 条件に合う箱だけを見せる（合わない箱を隠す。値が無くて決められない箱も隠す）。 */
    SHOW("show");

    companion object {
        fun parse(value: String?): LensFilterMode? = value?.let { v -> entries.firstOrNull { it.id == v } }
    }
}

/**
 * 箱の絞り込み 1 つ（ADR-0027）。対象（段・エンティティ型。省略で全部）の箱にだけ効き、対象外の箱は触らない。
 * 条件は変換の `$when` と同じ書き方で、記法と同じ箱の材料（id・title・entityType・data）の上で評価する。
 */
data class LensFilter(
    val id: String,
    val label: String,
    val mode: LensFilterMode,
    val conditions: List<TemplateCondition>,
    val level: String? = null,
    val entityType: String? = null,
    /** 定義を選んだとき、画面の覚えが無ければ入っているか。 */
    val default: Boolean = false,
) {
    fun appliesTo(level: String, type: String?): Boolean =
        (this.level == null || this.level == level) && (entityType == null || entityType == type)
}

/** レンズの見え方の定義（`kind: lens`）。記法も絞り込みも持たない定義（矢印だけ）は、全部を合わせて読む。 */
data class VirtualLensDef(
    override val id: String,
    override val name: String,
    override val description: String? = null,
    override val source: DocumentId,
    val relations: List<VirtualLensRelation> = emptyList(),
    val appearance: DiagramAppearance? = null,
    val filters: List<LensFilter> = emptyList(),
) : VirtualViewDefinition {
    /** 矢印を逆向きに描く関係名。 */
    val reversedRelations: Set<String> get() = relations.filter { it.arrow == LensArrow.REVERSE }.map { it.name }.toSet()

    /** 画面の「見え方」で選ぶ定義か（記法か絞り込みを持つ）。選ばない定義は矢印だけで、いつも合わせて読む。 */
    val selectable: Boolean get() = appearance != null || filters.isNotEmpty()
}

/** 絞り込みの評価（ADR-0027）。条件は変換と同じ評価器（`SemanticTransform.holds`）で、表示の選択だけを決める。 */
object LensFilters {
    /** 箱 1 つの手がかり: ID・型・評価の入力（作れなければ null＝条件は成り立たない）。 */
    class Candidate(val id: String, val type: String?, val input: () -> SemanticTransform.TemplatePlace?)

    /**
     * 入れた絞り込み（active）のどれかが隠す箱の ID。段 level（entity / type）の対象の箱だけを調べ、起点（origin）は隠さない。
     * 入力は箱ごとに一度だけ作る。
     */
    fun hidden(filters: List<LensFilter>, level: String, candidates: List<Candidate>, origin: String? = null): Set<String> {
        if (filters.isEmpty()) return emptySet()
        return candidates.filter { c ->
            if (c.id == origin) return@filter false
            val relevant = filters.filter { it.appliesTo(level, c.type) }
            if (relevant.isEmpty()) return@filter false
            val input by lazy { c.input() }
            relevant.any { f ->
                val holds = input?.let { SemanticTransform.holds(f.conditions, emptyMap(), it) } ?: false
                if (f.mode == LensFilterMode.HIDE) holds else !holds
            }
        }.map { it.id }.toSet()
    }
}
