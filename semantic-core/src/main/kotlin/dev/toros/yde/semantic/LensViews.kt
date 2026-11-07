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
    /** この絞り込みが隠す箱でも、線でつながる相手のどれかが合えば残す（例外。無ければ例外なし）。 */
    val keepIfLinked: LensFilterKeep? = null,
) {
    fun appliesTo(level: String, type: String?): Boolean =
        (this.level == null || this.level == level) && (entityType == null || entityType == type)
}

/**
 * 「つながりで残す」の例外: 直接つながる相手（1 段・線の向きは問わない）のうち、via の関係の線（空＝全部の線）でつながり、
 * 型（entityType。省略で全部）と条件（conditions。空＝いつも成り立つ）に合う相手が 1 つでもあれば残す。
 * 相手は値で判定する（相手が別の絞り込みで隠れていても、相手の値が合えば残す）。
 */
data class LensFilterKeep(
    val via: Set<String> = emptySet(),
    val entityType: String? = null,
    val conditions: List<TemplateCondition> = emptyList(),
)

/** 箱から相手の箱への線 1 本（向きは問わない。relation は線の関係名。名前の無い参照は null）。 */
data class LensLink(val relation: String?, val other: String)

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

    /** 評価の結果: 隠す箱と、隠すはずだったが「つながりで残す」で残した箱（どちらも ID）。 */
    data class Result(val hidden: Set<String>, val kept: Set<String>)

    /**
     * 入れた絞り込み（filters）を当てる。段 level（entity / type）の対象の箱だけを調べ、起点（origin）は隠さない。
     * どれかの絞り込みが隠す箱は隠す。絞り込みの例外（keepIfLinked）は、その絞り込みが隠す分だけを取り消す。
     * links は箱ごとの線（相手は candidates の ID。候補に無い相手は数えない）。入力は箱ごとに一度だけ作る。
     */
    fun evaluate(
        filters: List<LensFilter>, level: String, candidates: List<Candidate>,
        origin: String? = null, links: Map<String, List<LensLink>> = emptyMap(),
    ): Result {
        if (filters.isEmpty()) return Result(emptySet(), emptySet())
        val byId = candidates.associateBy { it.id }
        val inputs = HashMap<String, SemanticTransform.TemplatePlace?>()
        fun inputOf(c: Candidate) = inputs.getOrPut(c.id) { c.input() }
        fun holds(conditions: List<TemplateCondition>, c: Candidate): Boolean =
            conditions.isEmpty() || inputOf(c)?.let { SemanticTransform.holds(conditions, emptyMap(), it) } ?: false
        fun keptByLink(keep: LensFilterKeep, c: Candidate): Boolean = links[c.id].orEmpty().any { link ->
            val other = byId[link.other]?.takeIf { it.id != c.id } ?: return@any false
            (keep.via.isEmpty() || link.relation in keep.via) &&
                (keep.entityType == null || keep.entityType == other.type) && holds(keep.conditions, other)
        }
        val hidden = LinkedHashSet<String>()
        val kept = LinkedHashSet<String>()
        candidates.forEach { c ->
            if (c.id == origin) return@forEach
            var hide = false
            var rescued = false
            filters.filter { it.appliesTo(level, c.type) }.forEach { f ->
                val matches = holds(f.conditions, c)
                if (if (f.mode == LensFilterMode.HIDE) matches else !matches) {
                    if (f.keepIfLinked != null && keptByLink(f.keepIfLinked, c)) rescued = true else hide = true
                }
            }
            if (hide) hidden += c.id else if (rescued) kept += c.id
        }
        return Result(hidden, kept)
    }

    /** 隠す箱の ID だけ（[evaluate] の hidden）。 */
    fun hidden(filters: List<LensFilter>, level: String, candidates: List<Candidate>, origin: String? = null,
        links: Map<String, List<LensLink>> = emptyMap()): Set<String> = evaluate(filters, level, candidates, origin, links).hidden
}
