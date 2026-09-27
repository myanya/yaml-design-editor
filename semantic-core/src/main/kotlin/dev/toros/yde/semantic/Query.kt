package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.ConstraintForAll
import dev.toros.yde.semantic.schema.DerivationSelector
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.semantic.schema.CheckTerm
import dev.toros.yde.semantic.schema.ConstraintCheck
import dev.toros.yde.semantic.schema.Projection
import dev.toros.yde.semantic.schema.QueryWhereClause
import dev.toros.yde.semantic.schema.ValueExpr
import dev.toros.yde.semantic.schema.QueryDir
import dev.toros.yde.semantic.schema.QueryEdgeStep
import dev.toros.yde.semantic.schema.QueryNodeKind
import dev.toros.yde.semantic.schema.QueryNodeStep
import dev.toros.yde.semantic.schema.QuerySpec
import dev.toros.yde.semantic.schema.QueryWhere
import dev.toros.yde.semantic.schema.RefTarget

/**
 * パターン照合エンジンのカーネル（R22-01。3層コア移行 Phase 6C で index/Query.kt から移動）。
 * `QuerySpec`（schema/Query.kt）を SemanticEntityGraph の隣接上で評価し、束縛の表を返す。
 * - 決定性: 起点候補はID昇順・エッジ展開は distinct＋ID昇順・行の並びは
 *   「束縛名の辞書順に並べた束縛値（ID昇順）の辞書式比較」・limit 超過は決定的打切り
 * - 判定保留の哲学（architecture/transform.md「計算列と決定論的射影」）: 未解決参照・型未宣言のエンティティは黙って照合から外す
 * - 1本の照合経路の中で同じノードを二度通らない（循環照合の停止）。例外は same ステップ
 *   （束縛済みノードそのものへの到達を要求＝意図的にサイクルを閉じる。R28-07）
 * - プロパティ述語の値は loader 注入で読む（ルート側シェルがディスク・docCache・ステージングを渡す）
 * Traverse の隣接1段（`neighborIds`）もここに一元化する（R22-02③）。
 */

/** 結果の1ノード。エンティティの座標は定義出現（なければ先頭出現）、文書は文書＋空パス。 */
data class SemanticQueryNodeRef(
    val kind: QueryNodeKind,
    val id: String,
    val title: String?,
    val entityType: String?,
    val document: DocumentId?,
    val path: YPath,
)

/**
 * 結果の1行（束縛名 → ノード。列順は束縛の宣言順）。refs は edge の bind で束縛した参照（参照の束縛名 → 参照）で、
 * 行の同一性に加わる（同じ相手への参照が2本あれば2行）。
 */
data class SemanticQueryRow(
    val bindings: Map<String, SemanticQueryNodeRef>,
    val refs: Map<String, ScannedRef> = emptyMap(),
    /**
     * 見つけた道すじ（通った要素の ID。束縛していない途中の要素と、何段でも辿る線が通った要素を含む）。
     * 行の同一性には加わらない（同じ束縛の組へ着く道が複数あれば、決まった順で最初に見つけた道）。指摘の文面の `{path}` に使う。
     */
    val trail: List<String> = emptyList(),
)

/**
 * 照合結果。total は limit 打切り前の全行数。
 * unknownDropped は「解探索の途中で、未解決参照により候補が失われた」記録（CALC-17 の unknown-dropped。
 * エッジ展開で参照の端をノードへ解決できなかった場合のみ真。実在するノードの絞り込み＝選択は含めない）。
 * 消費側の制約判定は、これが真のとき「解の不足を理由とする違反」を判定保留＝沈黙へ降格する。
 */
data class SemanticQueryResult(
    val columns: List<String>,
    val rows: List<SemanticQueryRow>,
    val total: Int,
    val unknownDropped: Boolean = false,
)

/** テンプレートへ差し込んだ値1件の出どころ（未解決なら値と場所なし）。 */
data class SemanticValueInput(
    val placeholder: String,
    val value: String?,
    val document: DocumentId?,
    val path: YPath?,
)

/**
 * check で条件を満たさなかった組の片辺（R28-08）: 計算した値と、その出どころ（provenance の最小形）。
 * 束縛からの読み出しなら bind / id / 読んだ出現（document・occPath）と要素内のパス（path）を持つ。
 * raw と source は元の値とその場所（テンプレートの展開ならテンプレートの文字列と、それが書かれた場所）、
 * inputs はテンプレートへ差し込んだ値の場所。エラー時の対応（移動・書き込み。SP-09）がこれを使う。
 */
data class SemanticCheckSide(
    val text: String,
    val bind: String? = null,
    val id: String? = null,
    val document: DocumentId? = null,
    val occPath: YPath? = null,
    val path: YPath? = null,
    val raw: String? = null,
    val sourceDocument: DocumentId? = null,
    val sourcePath: YPath? = null,
    val inputs: List<SemanticValueInput> = emptyList(),
    /** 読んだ値に施した計算の呼び名（「文字数」など。文面で「name の文字数: 25」のように添える）。 */
    val note: String? = null,
)

/**
 * check 判定で条件を満たさなかった組1件（項目の中で、同じ「束縛と文書の指定」は同じ出現から読む。CORE-CALCULUS §8）。
 * term は満たさなかった条件（「または」の項目では、満たさなかった最初の条件）で、left / right はその条件の値。
 * row は、その組が見つかった解（参照の場所への指摘と、文面への ID の埋め込みに使う）。
 */
data class SemanticCheckViolation(
    val check: ConstraintCheck,
    val term: CheckTerm,
    val left: SemanticCheckSide,
    val right: SemanticCheckSide,
    val row: SemanticQueryRow,
)

/**
 * 「すべての要素について」の判定の結果（SP-04）: total = 集まりの要素の数・missing = 解のなかった要素（ID 昇順）。
 * 解を探す途中で相手の見つからない参照に出会った要素は、不足に数えない（判定保留。CALC-17）。
 */
data class SemanticForAllResult(
    val total: Int,
    val missing: List<SemanticQueryNodeRef>,
    /** 解が無く、しかも相手の見つからない参照に出会ったため、不足とも充足とも決められなかった要素の数（検査の結果の「保留」の根拠）。 */
    val undecided: Int = 0,
)

/**
 * 起点を固定した check 判定の1回分（P27 段A）: 条件を満たさなかった組と、「充足」と言い切れるかどうかを決める材料。
 * - evaluated: 評価できた「解 × 項目」の数（0 なら、比べる組が1つも無かった）
 * - unknownDropped: 解を探す途中で、相手の見つからない参照により候補が失われた（CALC-17）
 * - noValue: 比べる値が書かれていないために評価できなかった「解 × 項目」がある
 * - incomparable: 値はあるが、大小の比較で数値として読めないために評価できなかった「解 × 項目」がある
 */
data class SemanticCheckRun(
    val violations: List<SemanticCheckViolation>,
    val evaluated: Int,
    val unknownDropped: Boolean,
    val noValue: Boolean,
    val incomparable: Boolean,
)

/**
 * check の1項目を評価した結果1件（検査の状況の表示用）: 条件を満たさなかった組があればその組（satisfied = false）、
 * なければ評価できた最初の組（satisfied = true）。評価できる組が1つも無い項目は結果に含まれない（判定保留）。
 */
data class SemanticCheckEvaluation(
    val check: ConstraintCheck,
    val term: CheckTerm,
    val left: SemanticCheckSide,
    val right: SemanticCheckSide,
    val row: SemanticQueryRow,
    val satisfied: Boolean,
)

/**
 * node ステップの絞り込み（型のどれか・出現スキーマ・ID パターンと、その否定）にエンティティが合うか。
 * クエリ・網羅マトリクス・遷移検証が共有する（1つの意味には1つの評価器）。
 * 否定（not）は3値で判定する——中の条件がすべて合えば「合わない」、どれかが合わなければ「合う」、
 * 型が宣言されていないために決まらないときは判定保留＝合わない（型未宣言のエンティティには黙る architecture/transform.md「計算列と決定論的射影」）。
 */
internal fun QueryNodeStep.matchesEntity(id: String, node: SemanticEntityNode): Boolean {
    if (idPattern != null && !idMatchesPattern(id, idPattern!!)) return false
    if (entities.isNotEmpty() && node.entityType !in entities) return false
    if (typed != null && (node.entityType != null) != typed) return false
    if (schema != null && node.occurrences.none { it.schemaId == schema }) return false
    val filter = not ?: return true
    // 中の条件のどれかが合わなければ、否定は成り立つ
    if (filter.idPattern != null && !idMatchesPattern(id, filter.idPattern!!)) return true
    if (filter.schema != null && node.occurrences.none { it.schemaId == filter.schema }) return true
    val type = node.entityType
    if (filter.entities.isNotEmpty() && type != null && type !in filter.entities) return true
    // すべて合った（否定は成り立たない）か、型が宣言されておらず決まらない（判定保留）
    return false
}

object SemanticQuery {

    /** パターンを評価して束縛の表を返す。 */
    fun run(
        ids: Map<String, List<ScannedId>>,
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        spec: QuerySpec,
        loader: (DocumentId) -> YNode?,
    ): SemanticQueryResult = Evaluator(ids, refs, graph, spec, loader).evaluate()

    /**
     * 起点を固定した存在判定（R22-03 パターン制約用）: startId を起点束縛に固定したとき、
     * パターン全体（起点フィルタ・hops・also・where）に解が1つ以上あるか。
     */
    fun anyMatchFrom(
        ids: Map<String, List<ScannedId>>,
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        spec: QuerySpec,
        startId: String,
        loader: (DocumentId) -> YNode?,
    ): Boolean = Evaluator(ids, refs, graph, spec, loader).anyMatchFrom(startId)

    /**
     * 起点を固定した解の列挙（R28-05: 制約指摘の説明用）: 束縛の表（limit まで。total は打切り前）。
     * absent / count 違反の「該当の組」をメッセージ・ジャンプ導線に使う。
     */
    fun matchesFrom(
        ids: Map<String, List<ScannedId>>,
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        spec: QuerySpec,
        startId: String,
        loader: (DocumentId) -> YNode?,
    ): SemanticQueryResult = Evaluator(ids, refs, graph, spec, loader).evaluate(restrictStart = startId)

    /**
     * 起点を固定した解の件数（R28-02 count 制約用）: 束縛の組（bind 列）の distinct 件数
     * （limit 打切り前の total）。数えたい対象のノードに bind を書くこと——束縛のない中間ノードは
     * 行の同一性に関与しないため、bind がなければ件数は最大1になる。
     */
    fun countMatchesFrom(
        ids: Map<String, List<ScannedId>>,
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        spec: QuerySpec,
        startId: String,
        loader: (DocumentId) -> YNode?,
    ): Int = Evaluator(ids, refs, graph, spec, loader).evaluate(restrictStart = startId).total

    /**
     * 起点を固定した check 判定（R28-08。CORE-CALCULUS §8 の判定述語）: 解（束縛の表）ごとに
     * 各 check 項目を出現割当て σ で反例量化する——項目内の σ は一貫（同一束縛は同一出現から読む）・
     * 違反する組を発見したら反例として返す（行×項目につき決定的な最初の1件）・評価可能な組が
     * なければその項目は判定保留（黙る）。母集団判定（startMatches）は呼び出し側が先に行う。
     */
    fun checkFrom(
        ids: Map<String, List<ScannedId>>,
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        spec: QuerySpec,
        checks: List<ConstraintCheck>,
        startId: String,
        /** 文書のワークスペース相対パス（値を読む文書の指定の fileGlob 判定に使う）。 */
        rel: (DocumentId) -> String = { it.value },
        /**
         * 値を読む出現の固定（SP-08: 指摘を「値が書かれた場所」へ出す検査で、その場所の出現だけを調べるために使う）。
         * 指定した「束縛と文書の指定」は、この出現からだけ読む。
         */
        pin: Pair<ValueExpr.ScopeRef, SemanticExpression.Occurrence>? = null,
        loader: (DocumentId) -> YNode?,
    ): List<SemanticCheckViolation> = Evaluator(ids, refs, graph, spec, loader, rel).checkFrom(startId, checks, pin)

    /** 起点を固定した check 判定を、結果を決める材料つきで返す（checkFrom と同じ評価。P27 段A の SemanticRules が使う）。 */
    fun checkRunFrom(
        ids: Map<String, List<ScannedId>>,
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        spec: QuerySpec,
        checks: List<ConstraintCheck>,
        startId: String,
        rel: (DocumentId) -> String = { it.value },
        pin: Pair<ValueExpr.ScopeRef, SemanticExpression.Occurrence>? = null,
        loader: (DocumentId) -> YNode?,
    ): SemanticCheckRun = Evaluator(ids, refs, graph, spec, loader, rel).checkRun(startId, checks, pin)

    /**
     * 起点を固定した check の評価（検査の状況の表示用）: checkFrom と同じ評価で、条件を満たしている項目も
     * 値つきで返す（「出現 1 件（必要 1 件）」のように、満たしている状況も示すため）。
     */
    fun checkEvaluationsFrom(
        ids: Map<String, List<ScannedId>>,
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        spec: QuerySpec,
        checks: List<ConstraintCheck>,
        startId: String,
        rel: (DocumentId) -> String = { it.value },
        loader: (DocumentId) -> YNode?,
    ): List<SemanticCheckEvaluation> = Evaluator(ids, refs, graph, spec, loader, rel).checkEvaluationsFrom(startId, checks)

    /**
     * 起点を固定した「すべての要素について」の判定（SP-04）。起点が母集団に入らない・集まりが空なら null（判定しない）。
     */
    fun forAllFrom(
        ids: Map<String, List<ScannedId>>,
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        spec: QuerySpec,
        forAll: ConstraintForAll,
        startId: String,
        rel: (DocumentId) -> String = { it.value },
        loader: (DocumentId) -> YNode?,
    ): SemanticForAllResult? {
        if (!Evaluator(ids, refs, graph, spec, loader, rel).startMatches(startId)) return null
        val members = graph.entityIdsMatching(forAll.node)
        if (members.isEmpty()) return null
        val memberBind = forAll.node.bind ?: return null
        // 起点側の束縛（from）から steps を辿る。集まりの要素は、あらかじめ決まった束縛として same から指す
        val fromStep = spec.bindStep(forAll.from) ?: return null
        val branch = QuerySpec(start = fromStep, hops = forAll.hops)
        var undecided = 0
        val missing = members.filter { member ->
            val evaluator = Evaluator(
                ids, refs, graph, branch, loader, rel,
                fixed = mapOf(memberBind to NodeKey(QueryNodeKind.ENTITY, member)),
            )
            val result = evaluator.evaluate(restrictStart = startId, firstOnly = true)
            if (result.total == 0 && result.unknownDropped) undecided++
            result.total == 0 && !result.unknownDropped
        }
        return SemanticForAllResult(
            undecided = undecided,
            total = members.size,
            missing = missing.map { id ->
                val node = graph.entities.getValue(id)
                val anchor = node.defining ?: node.occurrences.first()
                SemanticQueryNodeRef(QueryNodeKind.ENTITY, id, node.title, node.entityType, anchor.document, anchor.path)
            },
        )
    }

    /** startId が起点束縛の母集団（起点 node のフィルタ＋起点束縛の where 述語）に入るか（R22-03）。 */
    fun startMatches(
        ids: Map<String, List<ScannedId>>,
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        spec: QuerySpec,
        startId: String,
        loader: (DocumentId) -> YNode?,
    ): Boolean = Evaluator(ids, refs, graph, spec, loader).startMatches(startId)

    /**
     * Traverse 互換の隣接1段（R22-02③）。out = フロンティア配下に書かれた参照の targetId、
     * in = フロンティアIDへの被参照の書き手（最内のID要素）。distinct＋ID昇順（visited・limit は呼び出し側）。
     */
    fun neighborIds(
        ids: Map<String, List<ScannedId>>,
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        frontier: List<String>,
        follow: String,
        relation: String? = null,
        via: String? = null,
        schemaId: String? = null,
    ): List<String> = when (follow) {
        "out" -> frontier.flatMap { id -> graph.outgoingRefs(id) }
            .asSequence()
            .filter { relation == null || it.relation == relation }
            .filter { via == null || QueryEdgeStep.viaMatches(via, it.viaKey, it.path) }
            .map { it.targetId }
            .distinct()
            .filter { schemaId == null || ids[it].orEmpty().any { e -> e.schemaId == schemaId } }
            .sorted()
            .toList()
        "in" -> {
            val targets = frontier.toSet()
            refs.filter { it.targetKind == RefTarget.ENTITY && it.targetId in targets }
                .asSequence()
                .filter { relation == null || it.relation == relation }
                .filter { via == null || QueryEdgeStep.viaMatches(via, it.viaKey, it.path) }
                .mapNotNull { graph.sourceEntryOf(it)?.id }
                .distinct()
                .filter { schemaId == null || ids[it].orEmpty().any { e -> e.schemaId == schemaId } }
                .sorted()
                .toList()
        }
        else -> error("follow は out / in のいずれかです: $follow")
    }

    // ---------------------------------------------------------------- 評価

    /** 照合経路上のノードの同一性（kind＋ID）。エンティティと文書は別名前空間（architecture/agents.md「AI 生成」 原則3）。 */
    private data class NodeKey(val kind: QueryNodeKind, val id: String)

    private class Evaluator(
        private val ids: Map<String, List<ScannedId>>,
        private val allRefs: List<ScannedRef>,
        private val graph: SemanticEntityGraph,
        private val spec: QuerySpec,
        private val loader: (DocumentId) -> YNode?,
        private val rel: (DocumentId) -> String = { it.value },
        /** あらかじめ決まった束縛（forAll の集まりの要素）。node の same から指せる。 */
        private val fixed: Map<String, NodeKey> = emptyMap(),
    ) {
        private val trees = mutableMapOf<DocumentId, YNode?>()

        private fun tree(d: DocumentId): YNode? = trees.getOrPut(d) { loader(d) }

        /**
         * 全参照から引く表（被参照・文書ごとの参照・行ごとの参照など）。評価のたびに作らず、グラフが同じ参照の並びにつき
         * 1回だけ作ったものを使う（評価は「検査 × 要素」の数だけ起きるため。BACKLOG UX-116）。
         */
        private val refIndex: SemanticRefIndex = graph.refIndexFor(allRefs)
        private val entityRefsByTarget: Map<String, List<ScannedRef>> get() = refIndex.entityRefsByTarget
        private val refsByDocument: Map<DocumentId, List<ScannedRef>> get() = refIndex.refsByDocument
        private val docRefsByTarget: Map<String, List<ScannedRef>> get() = refIndex.docRefsByTarget
        private val documentIdByDoc: Map<DocumentId, String> get() = refIndex.documentIdByDoc

        /** 束縛名 → その束縛に効くプロパティ述語（束縛が確定した時点で枝刈りする）。 */
        private val predsByBind: Map<String, List<QueryWhereClause>> = spec.where.groupBy { it.of }

        /** CALC-17: この評価の解探索で、未解決参照により候補が失われたか（expand が記録する）。 */
        private var unknownDropped = false

        /**
         * 照合経路の1歩: 着いたノードと、edge に bind があるときは辿った参照そのもの。
         * through は、何段でも辿る線が途中で通った要素（出発した要素と着いた要素の間。道すじの表示用）。
         * stayed は、0回以上くり返す線（repeat: {min: 0}）で「進まずに、出発した要素にとどまった」歩。
         */
        private inner class Step(
            val key: NodeKey,
            val ref: ScannedRef? = null,
            val through: List<String> = emptyList(),
            val stayed: Boolean = false,
        )

        private val refsByRow: Map<Pair<DocumentId, YPath>, List<ScannedRef>> get() = refIndex.refsByRow

        fun evaluate(restrictStart: String? = null, firstOnly: Boolean = false): SemanticQueryResult {
            val sorted = solve(restrictStart, firstOnly)
            return SemanticQueryResult(
                columns = spec.binds,
                rows = sorted.take(spec.limit),
                total = sorted.size,
                unknownDropped = unknownDropped,
            )
        }

        /** 全ての解（並びは決定的。limit で切らない）。 */
        private fun solve(restrictStart: String?, firstOnly: Boolean): List<SemanticQueryRow> {
            var paths: List<List<Step>> = startCandidates(restrictStart)
                .filter { predsOk(spec.start.bind, it) }
                .map { listOf(Step(it)) }
            for (hop in spec.hops) {
                paths = paths.flatMap { path ->
                    val sameKey = hop.node.same?.let { fixed[it] ?: path[bindPositionInMatch(it)].key }
                    if (sameKey != null) {
                        // same: 束縛済みノードそのものへの到達を要求（二度通らない規則の意図的な例外。R28-07）
                        expand(path.last().key, hop.edge, hop.node.copy(kind = sameKey.kind))
                            .filter { it.key == sameKey }
                            .map { path + it }
                    } else {
                        expand(path.last().key, hop.edge, hop.node)
                            .filter { step -> path.none { it.key == step.key } || staysInPlace(hop.edge, path.last().key, step) }
                            .filter { predsOk(hop.node.bind, it.key) }
                            .map { path + it }
                    }
                }
            }
            // 分岐（also）: from の束縛ノードを起点に、もう1本の線形連鎖を内部結合する
            val branch = spec.also
            if (branch != null) {
                val fromPos = bindPositionInMatch(branch.from)
                paths = paths.flatMap { path ->
                    var branches = listOf(emptyList<Step>())
                    for ((hopIndex, hop) in branch.hops.withIndex()) {
                        branches = branches.flatMap { bPath ->
                            val fromKey = bPath.lastOrNull()?.key ?: path[fromPos].key
                            val sameKey = hop.node.same?.let { s ->
                                val mPos = bindPositionInMatch(s)
                                if (mPos >= 0) path[mPos].key
                                else bPath[branch.hops.take(hopIndex).indexOfFirst { it.node.bind == s }].key
                            }
                            if (sameKey != null) {
                                expand(fromKey, hop.edge, hop.node.copy(kind = sameKey.kind))
                                    .filter { it.key == sameKey }
                                    .map { bPath + it }
                            } else {
                                expand(fromKey, hop.edge, hop.node)
                                    .filter { step ->
                                        (path.none { it.key == step.key } && bPath.none { it.key == step.key }) ||
                                            staysInPlace(hop.edge, fromKey, step)
                                    }
                                    .filter { predsOk(hop.node.bind, it.key) }
                                    .map { bPath + it }
                            }
                        }
                    }
                    branches.map { path + it }
                }
            }
            // 行 = 束縛値の組（宣言順の列）＋束縛した参照。束縛されない中間ノードは行の同一性に関与しない
            val bindPositions = bindPositions()
            val edgePositions = edgeBindPositions()
            val seen = LinkedHashSet<List<String>>()
            val rows = mutableListOf<SemanticQueryRow>()
            for (path in paths) {
                val key = bindPositions.map { (_, pos) -> path[pos].key.kind.id + ":" + path[pos].key.id } +
                    edgePositions.map { (_, pos) -> refSortKey(path[pos].ref) }
                if (!seen.add(key)) continue
                rows += SemanticQueryRow(
                    bindings = bindPositions.associate { (bind, pos) -> bind to refOf(path[pos].key) },
                    refs = edgePositions.mapNotNull { (bind, pos) -> path[pos].ref?.let { bind to it } }.toMap(),
                    // 0回の線でとどまった歩は、道すじに並べない（自分自身へ戻る歩は「A → A」と並べる）
                    trail = path.filter { !it.stayed }.flatMap { it.through + it.key.id },
                )
                if (firstOnly) break
            }
            // 行の並び: 束縛名の辞書順に並べた列の値（ID昇順）の辞書式比較（architecture/graph-query.md「クエリコア」。列の表示順は宣言順のまま）。
            // 束縛した参照があれば、その書かれた場所（文書・パス）で続けて比べる
            val sortBinds = spec.binds.sorted()
            val sortEdges = spec.edgeBinds.sorted()
            return rows.sortedWith(
                Comparator { a, b ->
                    for (bind in sortBinds) {
                        val c = (a.bindings[bind]?.id ?: "").compareTo(b.bindings[bind]?.id ?: "")
                        if (c != 0) return@Comparator c
                    }
                    for (bind in sortEdges) {
                        val c = refSortKey(a.refs[bind]).compareTo(refSortKey(b.refs[bind]))
                        if (c != 0) return@Comparator c
                    }
                    0
                }
            )
        }

        /**
         * 0回以上くり返す線（repeat: {min: 0}）で、出発した要素にとどまる1歩か。「同じ要素を二度通らない」規則の例外
         * （0回＝進まないことを選んだだけで、戻ってきたわけではない）。
         */
        private fun staysInPlace(edge: QueryEdgeStep, from: NodeKey, step: Step): Boolean =
            edge.repeatMin == 0 && step.key == from && step.stayed

        /** 参照の同一性と並びのキー（書かれた文書とパス）。 */
        private fun refSortKey(ref: ScannedRef?): String = ref?.let { it.document.value + "#" + it.path.pretty() } ?: ""

        fun anyMatchFrom(startId: String): Boolean =
            evaluate(restrictStart = startId, firstOnly = true).total > 0

        fun startMatches(startId: String): Boolean =
            startCandidates(startId).any { predsOk(spec.start.bind, it) }

        /** match 連鎖（start＋hops）の中での束縛位置。 */
        private fun bindPositionInMatch(bind: String): Int {
            val steps = listOf(spec.start) + spec.hops.map { it.node }
            return steps.indexOfFirst { it.bind == bind }
        }

        /** 全束縛（match＋also）の経路内位置（宣言順）。 */
        private fun bindPositions(): List<Pair<String, Int>> {
            val steps = listOf(spec.start) + spec.hops.map { it.node } + spec.also?.hops?.map { it.node }.orEmpty()
            return steps.mapIndexedNotNull { i, s -> s.bind?.let { it to i } }
        }

        /** 参照の束縛（edge の bind）の経路内位置（その edge で着いた歩の位置。宣言順）。 */
        private fun edgeBindPositions(): List<Pair<String, Int>> =
            (spec.hops + spec.also?.hops.orEmpty()).mapIndexedNotNull { i, hop -> hop.edge.bind?.let { it to i + 1 } }

        private fun startCandidates(restrictStart: String?): List<NodeKey> {
            val candidates0 = when (spec.start.kind) {
                QueryNodeKind.ENTITY -> graph.entities.keys
                QueryNodeKind.DOCUMENT -> graph.documents.keys
            }
            // 起点を固定した評価は、全要素を総なめしない（評価は要素の数だけ起きるため）
            val candidates = (restrictStart?.let { r -> if (r in candidates0) listOf(r) else emptyList() } ?: candidates0.sorted())
            return candidates.map { NodeKey(spec.start.kind, it) }.filter { nodeMatches(spec.start, it) }
        }

        private fun nodeMatches(step: QueryNodeStep, key: NodeKey): Boolean {
            if (key.kind != step.kind) return false
            return when (key.kind) {
                QueryNodeKind.ENTITY -> graph.entities[key.id]?.let { step.matchesEntity(key.id, it) } ?: false
                QueryNodeKind.DOCUMENT -> graph.documents.containsKey(key.id) &&
                    (step.idPattern == null || idMatchesPattern(key.id, step.idPattern)) &&
                    (step.not?.idPattern == null || !idMatchesPattern(key.id, step.not.idPattern))
            }
        }

        /**
         * エッジの展開。edge に bind があれば参照ごとに1歩（同じ相手への参照が2本あれば2歩）、なければ着いたノードごとに1歩。
         * repeat を書いた線は、くり返して辿り着けるすべての要素へ進む（reach）。
         */
        private fun expand(from: NodeKey, edge: QueryEdgeStep, target: QueryNodeStep): List<Step> {
            val repeatMin = edge.repeatMin
            if (repeatMin != null) return reach(from, edge, target, repeatMin)
            val matched = expandOnce(from, edge, target.kind).filter { nodeMatches(target, it.first) }
            if (edge.bind == null) return matched.map { it.first }.distinct().sortedBy { it.id }.map { Step(it) }
            // 参照を束縛する線（入れ子の線に bind は書けないため、ここへ来る線は必ず参照を持つ）
            return matched.mapNotNull { (key, ref) -> ref?.let { key to it } }
                .distinctBy { it.first to refSortKey(it.second) }
                .sortedWith(compareBy({ it.first.id }, { refSortKey(it.second) }))
                .map { Step(it.first, it.second) }
        }

        /**
         * 何段でも辿る（SP-06）: この線をくり返して辿り着ける要素の集まりを、幅優先で求める（専用アルゴリズム。
         * 一度着いた要素は二度調べないため必ず止まる・隣は ID 昇順で決定的）。道すじは最短のものを覚える。
         * 出発した要素へ戻る循環があれば、出発した要素も「辿り着ける要素」に入る（node の same で循環を調べられる）。
         * 途中の要素は node の絞り込みを受けない——絞り込みは辿り着いた先にだけ効く。
         */
        private fun reach(from: NodeKey, edge: QueryEdgeStep, target: QueryNodeStep, min: Int): List<Step> {
            val parent = LinkedHashMap<NodeKey, NodeKey>()   // 着いた要素 → その1つ手前の要素（最短の道すじ）
            val queue = ArrayDeque(listOf(from))
            val expanded = HashSet<NodeKey>()
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (!expanded.add(current)) continue
                for (next in expandOnce(current, edge, QueryNodeKind.ENTITY).map { it.first }.distinct().sortedBy { it.id }) {
                    if (next !in parent) {
                        parent[next] = current
                        queue.addLast(next)
                    }
                }
            }
            fun throughOf(end: NodeKey): List<String> {
                val reversed = mutableListOf<String>()
                var cursor = parent[end]
                while (cursor != null && cursor != from) {
                    reversed += cursor.id
                    cursor = parent[cursor]
                }
                return reversed.reversed()
            }
            val steps = parent.keys.filter { nodeMatches(target, it) }.map { Step(it, through = throughOf(it)) }
            // 0回以上: 出発した要素そのものも含む（進まずにとどまる歩。循環で戻ってくる歩とは別に、いつでも選べる）
            val self = if (min == 0 && nodeMatches(target, from)) listOf(Step(from, stayed = true)) else emptyList()
            return (self + steps).sortedBy { it.key.id }
        }

        /**
         * エッジ1段の展開（着いた先の node の絞り込みをかける前）。端の解決は到達側の kind が決める（architecture/graph-query.md「クエリコア」）:
         * out → entity = 解決済みのエンティティ参照先／document = 解決済みの文書参照先、
         * in → entity = 参照を書いた最内のID要素／document = 参照が書かれた文書。
         * row（行を線とみなす。SP-07）は、同じ行の直下の2つの参照——from の相手から to の相手へ——を1本の線として辿る。
         * contains（入れ子の線）は参照を通らない: out → 直下に入れ子になった要素（文書からは、いちばん外側で書かれた要素）、
         * in → 外側の要素（文書へは、その要素がいちばん外側で書かれている文書）。
         */
        private fun expandOnce(from: NodeKey, edge: QueryEdgeStep, targetKind: QueryNodeKind): List<Pair<NodeKey, ScannedRef?>> {
            if (edge.contains) {
                val keys: List<NodeKey> = when (edge.dir) {
                    QueryDir.OUT -> when {
                        targetKind != QueryNodeKind.ENTITY -> emptyList()
                        from.kind == QueryNodeKind.ENTITY -> graph.childIds(from.id).map { NodeKey(QueryNodeKind.ENTITY, it) }
                        else -> graph.documents[from.id]?.let { graph.topLevelIds(it.document) }.orEmpty()
                            .map { NodeKey(QueryNodeKind.ENTITY, it) }
                    }
                    QueryDir.IN -> when {
                        from.kind != QueryNodeKind.ENTITY -> emptyList()
                        targetKind == QueryNodeKind.ENTITY -> graph.parentIds(from.id).map { NodeKey(QueryNodeKind.ENTITY, it) }
                        else -> graph.topLevelDocuments(from.id).mapNotNull { documentIdByDoc[it] }.distinct().sorted()
                            .map { NodeKey(QueryNodeKind.DOCUMENT, it) }
                    }
                }
                return keys.map { it to null }
            }
            val row = edge.row
            if (row != null) {
                if (from.kind != QueryNodeKind.ENTITY || targetKind != QueryNodeKind.ENTITY) return emptyList()
                // この要素を「こちら側」の参照で指している行を探し、同じ行の「向こう側」の参照の相手へ進む
                val (nearRelation, farRelation) = when (edge.dir) {
                    QueryDir.OUT -> row.fromRelation to row.toRelation
                    QueryDir.IN -> row.toRelation to row.fromRelation
                }
                return entityRefsByTarget[from.id].orEmpty().asSequence()
                    .filter { it.relation == nearRelation }
                    .filter { ref -> edge.inDoc?.matches(ref.sourceSchemaId, rel(ref.document)) ?: true }
                    .flatMap { near -> refsByRow[near.document to near.path.dropLast(1)].orEmpty().asSequence() }
                    .filter { it.relation == farRelation }
                    .mapNotNull { far ->
                        if (!graph.entities.containsKey(far.targetId)) { unknownDropped = true; null }
                        else NodeKey(QueryNodeKind.ENTITY, far.targetId) to far
                    }
                    .toList()
            }
            val refs = when (edge.dir) {
                QueryDir.OUT -> when (from.kind) {
                    // その要素が直接書いた参照だけ（入れ子の要素が書いた参照は、その要素のもの。意図的差分 D-27）
                    QueryNodeKind.ENTITY -> graph.ownRefs(from.id)
                    QueryNodeKind.DOCUMENT -> graph.documents[from.id]?.let { refsByDocument[it.document] }.orEmpty()
                }
                QueryDir.IN -> when (from.kind) {
                    QueryNodeKind.ENTITY -> entityRefsByTarget[from.id].orEmpty()
                    QueryNodeKind.DOCUMENT -> docRefsByTarget[from.id].orEmpty()
                }
            }.asSequence()
                .filter { edge.relation == null || it.relation == edge.relation }
                .filter { edge.viaMatches(it.viaKey, it.path) }
                .filter { ref -> edge.inDoc?.matches(ref.sourceSchemaId, rel(ref.document)) ?: true }
            // 端をノードへ解決できない参照（対象IDが索引になし・書き手をエンティティへ解決できない）は
            // 黙って除外しつつ「候補の喪失」を記録する（CALC-17 の unknown-dropped）。
            // 対象種別の不一致（entity 到達に document 参照など）は構造的な非該当であり、喪失に数えない
            return when (edge.dir) {
                QueryDir.OUT -> refs.mapNotNull { ref ->
                    when (targetKind) {
                        QueryNodeKind.ENTITY ->
                            if (ref.targetKind != RefTarget.ENTITY) null
                            else if (!graph.entities.containsKey(ref.targetId)) { unknownDropped = true; null }
                            else NodeKey(QueryNodeKind.ENTITY, ref.targetId) to ref
                        QueryNodeKind.DOCUMENT ->
                            if (ref.targetKind != RefTarget.DOCUMENT) null
                            else if (!graph.documents.containsKey(ref.targetId)) { unknownDropped = true; null }
                            else NodeKey(QueryNodeKind.DOCUMENT, ref.targetId) to ref
                    }
                }
                QueryDir.IN -> refs.mapNotNull { ref ->
                    when (targetKind) {
                        QueryNodeKind.ENTITY -> graph.sourceEntryOf(ref)?.let { NodeKey(QueryNodeKind.ENTITY, it.id) to ref }
                            ?: run { unknownDropped = true; null }
                        QueryNodeKind.DOCUMENT -> documentIdByDoc[ref.document]?.let { NodeKey(QueryNodeKind.DOCUMENT, it) to ref }
                            ?: run { unknownDropped = true; null }
                    }
                }
            }.toList()
        }

        /** 束縛が確定したノードに、その束縛のプロパティ述語をすべて適用する（1件の中の「または」は、どれかが成立すればよい）。 */
        private fun predsOk(bind: String?, key: NodeKey): Boolean {
            val clauses = bind?.let { predsByBind[it] } ?: return true
            return clauses.all { clause -> clause.alternatives.any { predValue(it, key) == true } }
        }

        /**
         * 選択述語（where）の3値の判定: σ の候補（sigmaCandidates）を存在量化する（CALC-13。エンティティは
         * いずれかの出現の該当パス＝多段定義の OR。architecture/graph-query.md「クエリコア」）。値の読み出しは共有評価部の Get。
         * 値が無くて判定できないときは null（否定しても null＝ノードは選ばれない）。
         */
        private fun predValue(pred: QueryWhere, key: NodeKey): Boolean? {
            val nodes = sigmaCandidates(key.kind, key.id).map { SemanticExpression.nodeAt(::tree, it, pred.path) }
            val texts = nodes.mapNotNull { scalarText(it) }
            val test = pred.test
            val plain: Boolean? = when {
                pred.equals != null -> if (texts.isEmpty()) null else texts.any { it == pred.equals }
                // notEquals（R28-02）: 値が1つ以上あり、どの出現の値も指定値でない
                // （equals の OR 解釈の否定。値がなければ黙る＝判定保留）
                pred.notEquals != null ->
                    if (nodes.none { it != null }) null else nodes.none { scalarText(it) == pred.notEquals }
                pred.exists != null -> nodes.any { it != null } == pred.exists
                // empty: false = 中身のある値がどこかに書かれている／true = どの出現にも中身のある値がない
                pred.empty != null -> nodes.all { SemanticExpression.isEmpty(it) } == pred.empty
                // 値の条件（check と同じ判定）: どれかの出現の値が条件を満たせば成立。満たす値がなく、
                // 評価できない値（書かれていない・数値として読めない）が残れば判定保留
                test != null -> {
                    val results = texts.map { SemanticExpression.test(test.first, it, test.second) }
                    when {
                        results.any { it == true } -> true
                        results.isEmpty() || results.any { it == null } -> null
                        else -> false
                    }
                }
                else -> null
            }
            return plain?.let { if (pred.negated) !it else it }
        }

        private fun refOf(key: NodeKey): SemanticQueryNodeRef = when (key.kind) {
            QueryNodeKind.ENTITY -> {
                val node = graph.entities.getValue(key.id)
                val anchor = node.defining ?: node.occurrences.first()
                SemanticQueryNodeRef(QueryNodeKind.ENTITY, key.id, node.title, node.entityType, anchor.document, anchor.path)
            }
            QueryNodeKind.DOCUMENT -> {
                val doc = graph.documents.getValue(key.id)
                val name = tree(doc.document)?.let { DocumentMeta.nameOf(it) }
                SemanticQueryNodeRef(QueryNodeKind.DOCUMENT, key.id, name, null, doc.document, emptyList())
            }
        }

        // ---------------------------------------------------------------- check 判定（R28-08）

        fun checkFrom(
            startId: String,
            checks: List<ConstraintCheck>,
            pin: Pair<ValueExpr.ScopeRef, SemanticExpression.Occurrence>? = null,
        ): List<SemanticCheckViolation> {
            val all = solve(startId, firstOnly = false)
            // 集計（合計・最小・最大・件数）は、同じ起点の全ての解にわたって計算する
            aggregateRows = all
            val violations = mutableListOf<SemanticCheckViolation>()
            for (row in all.take(spec.limit)) {
                for (check in checks) {
                    val found = evaluationOf(row, check, pin)?.takeIf { !it.satisfied }
                        ?.let { SemanticCheckViolation(it.check, it.term, it.left, it.right, it.row) } ?: continue
                    // 集計を含む項目は、どの解で評価しても同じ結果になる——同じ組を解の数だけ並べない
                    val duplicate = check.operands.any { it.hasAggregate() } && violations.any {
                        it.check == found.check && it.left == found.left && it.right == found.right
                    }
                    if (!duplicate) violations += found
                }
            }
            return violations
        }

        /** checkFrom と同じ評価を、結果を決める材料つきで返す。 */
        fun checkRun(
            startId: String,
            checks: List<ConstraintCheck>,
            pin: Pair<ValueExpr.ScopeRef, SemanticExpression.Occurrence>? = null,
        ): SemanticCheckRun {
            val violations = checkFrom(startId, checks, pin)
            return SemanticCheckRun(violations, evaluatedCount, unknownDropped, sawNoValue, sawIncomparable)
        }

        /** 評価できた「解 × 項目」の数と、評価できなかった理由（値が書かれていない／数値として読めない）の記録。 */
        private var evaluatedCount = 0
        private var sawNoValue = false
        private var sawIncomparable = false

        fun checkEvaluationsFrom(startId: String, checks: List<ConstraintCheck>): List<SemanticCheckEvaluation> {
            val all = solve(startId, firstOnly = false)
            aggregateRows = all
            val out = mutableListOf<SemanticCheckEvaluation>()
            for (row in all.take(spec.limit)) {
                for (check in checks) {
                    val found = evaluationOf(row, check, pin = null) ?: continue
                    val duplicate = check.operands.any { it.hasAggregate() } && out.any {
                        it.check == found.check && it.left == found.left && it.right == found.right
                    }
                    if (!duplicate) out += found
                }
            }
            return out
        }

        /** 集計の対象になる解（checkFrom が、評価の前に同じ起点の全ての解を入れる）。 */
        private var aggregateRows: List<SemanticQueryRow> = emptyList()

        /**
         * 1行×1項目の検査。項目が読む「束縛と文書の指定」ごとに、値を読む出現の候補を並べ、その全ての組を
         * 決まった順（項目に現れた順・出現は索引順）で調べて、条件を満たさない最初の組を返す（決定的）。
         * 値が得られない・大小の比較で数値でない組は評価できないものとして飛ばし、評価できる組がなければ
         * null（判定保留＝黙る）。同じ「束縛と文書の指定」は項目の中で同じ出現から読む（CORE-CALCULUS §7 の σ）。
         * 候補が1つも無い指定は「その入れ物は無い」として扱う（読み出しは得られない・テンプレートの入れ物からは外れる）。
         */
        private fun evaluationOf(
            row: SemanticQueryRow,
            check: ConstraintCheck,
            pin: Pair<ValueExpr.ScopeRef, SemanticExpression.Occurrence>?,
        ): SemanticCheckEvaluation? {
            val sources = check.operands.flatMap { it.sources() }.distinct()
            val candidates: List<List<SemanticExpression.Occurrence?>> = sources.map { source ->
                if (pin != null && pin.first == source) listOf(pin.second)
                else candidatesFor(row, source).ifEmpty { listOf(null) }
            }
            val assignment = HashMap<ValueExpr.ScopeRef, SemanticExpression.Occurrence?>()
            // 条件を満たした最初の組（満たさない組が見つからなかったときに、状況の表示用として返す）
            var firstSatisfied: SemanticCheckEvaluation? = null
            // この「解 × 項目」で、評価できない条件に出会った理由（評価できる組が1つも無かったときだけ、結果の材料にする）
            var noValue = false
            var incomparable = false
            fun search(index: Int): SemanticCheckEvaluation? {
                if (index == sources.size) {
                    // 項目は値の条件の「または」（1つなら単独）: どれかが成り立てば成立・成り立つものがなく
                    // 評価できない条件が残れば判定保留（飛ばす）・すべて成り立たなければ、最初の条件の値を示して違反
                    var unknown = false
                    var firstFailed: SemanticCheckEvaluation? = null
                    for (term in check.terms) {
                        val left = evalExpr(term.left, row, assignment)
                        val rights = term.rights.map { evalExpr(it, row, assignment) }
                        // 値の条件は共有評価部——評価できない（null）条件は判定保留
                        val plain = left?.let { SemanticExpression.test(term.op, it.text, rights.map { r -> r?.text }) }
                        if (left == null || plain == null) {
                            unknown = true
                            if (left == null || rights.any { it == null }) noValue = true else incomparable = true
                            continue
                        }
                        // 右辺が複数（oneOf の選択肢）のときは、選択肢を並べた1つの辺として示す
                        val right = rights.singleOrNull()
                            ?: SemanticCheckSide(text = rights.filterNotNull().joinToString("・") { it.text })
                        if (plain != term.negated) {
                            if (firstSatisfied == null) firstSatisfied = SemanticCheckEvaluation(check, term, left, right, row, satisfied = true)
                            return null
                        }
                        if (firstFailed == null) firstFailed = SemanticCheckEvaluation(check, term, left, right, row, satisfied = false)
                    }
                    return if (unknown) null else firstFailed
                }
                for (occ in candidates[index]) {
                    assignment[sources[index]] = occ
                    search(index + 1)?.let { return it }
                }
                return null
            }
            val found = search(0) ?: firstSatisfied
            if (found != null) evaluatedCount++
            else if (incomparable) sawIncomparable = true
            else if (noValue) sawNoValue = true
            return found
        }

        /** 値を読む出現の候補: 束縛の全出現のうち、文書の指定（スキーマID・ファイルのパターン）に合うもの（索引順）。 */
        private fun candidatesFor(row: SemanticQueryRow, source: ValueExpr.ScopeRef): List<SemanticExpression.Occurrence> {
            val ref = row.bindings[source.of] ?: return emptyList()
            val selector = source.inDoc
            return when (ref.kind) {
                QueryNodeKind.ENTITY -> graph.entities[ref.id]?.occurrences.orEmpty()
                    .filter { selector == null || selector.matches(it.schemaId, rel(it.document)) }
                    .map { SemanticExpression.Occurrence(it.document, it.path) }
                // 文書ノードの値は _document 配下の1点（文書の指定は効かない）
                QueryNodeKind.DOCUMENT -> sigmaCandidates(ref.kind, ref.id)
            }
        }

        /**
         * 値の式を、決めた出現の組の下で計算する（得られなければ null）。値の出どころを併せて返す。
         * 評価そのものは SemanticValues（1つの環境での値の式の評価。P31）——解の束縛と出現の割当てを環境に写して呼ぶ。
         */
        private fun evalExpr(
            expr: ValueExpr,
            row: SemanticQueryRow,
            assignment: Map<ValueExpr.ScopeRef, SemanticExpression.Occurrence?>,
        ): SemanticCheckSide? = SemanticValues.evaluate(
            expr,
            SemanticValues.Environment(
                tree = ::tree, rel = rel, nodes = row.bindings, assignment = assignment, graph = graph,
                refsInto = { entityRefsByTarget[it].orEmpty() },
                aggregate = ::aggregateSide,
            ),
        )

        /**
         * 集計（SP-03）: 同じ起点の全ての解にわたり、束縛の要素（重複は1つ）の値をまとめる。値は指定に合う文書のうち
         * 定義した側を優先して1つから読む（CALC-19）。パスに `[*]` があれば、各要素の配列の全ての要素に広がる。
         * 得られない・順序の種類として読めない値が1つでもあれば得られない（計算は SemanticValues.aggregate と共有）。
         * 解の一覧を要するため、クエリの評価器だけが担う（環境の aggregate として SemanticValues へ渡す）。
         */
        private fun aggregateSide(expr: ValueExpr.Aggregate): SemanticValues.AggregateOutcome? {
            val of = expr.of ?: return null
            val nodes = aggregateRows.mapNotNull { it.bindings[of] }.distinctBy { it.kind to it.id }
            val path = expr.path
            if (path == null) {
                val n = nodes.size.toString()
                return SemanticValues.AggregateOutcome(SemanticCheckSide(text = n, raw = n))
            }
            val items = nodes.flatMap { node ->
                val occ = aggregateOccurrence(node, expr.inDoc)
                    ?: return SemanticValues.AggregateOutcome(null, "${node.id} の出現がありません")
                SemanticExpression.collect(::tree, occ, path)
            }
            return SemanticValues.aggregate(expr, items)
        }

        /** 集計で値を読む出現: 指定に合う出現のうち、定義した側を優先して1つ（式側の決定的な選択。CALC-19）。 */
        private fun aggregateOccurrence(node: SemanticQueryNodeRef, selector: DerivationSelector?): SemanticExpression.Occurrence? =
            when (node.kind) {
                QueryNodeKind.ENTITY -> SemanticExpression.preferDefining(graph.entities[node.id]?.occurrences.orEmpty())
                    .firstOrNull { selector == null || selector.matches(it.schemaId, rel(it.document)) }
                    ?.let { SemanticExpression.Occurrence(it.document, it.path) }
                QueryNodeKind.DOCUMENT -> sigmaCandidates(node.kind, node.id).firstOrNull()
            }

        /**
         * σ の割当て先（where の存在量化と check の反例量化が共有する。R29-05）: エンティティは
         * 全出現（定義＋詳細化）・文書は _document 配下の自明な1点（プロパティは _document 配下に限る。
         * ROADMAP architecture/agents.md「AI 生成」 原則3）。
         */
        private fun sigmaCandidates(kind: QueryNodeKind, id: String): List<SemanticExpression.Occurrence> = when (kind) {
            QueryNodeKind.ENTITY ->
                graph.entities[id]?.occurrences.orEmpty().map { SemanticExpression.Occurrence(it.document, it.path) }
            QueryNodeKind.DOCUMENT ->
                graph.documents[id]?.let { listOf(SemanticExpression.Occurrence(it.document, DOCUMENT_META_PATH)) }.orEmpty()
        }
    }

    /** 文書ノードのプロパティの読み出し起点（_document 配下限定の規約）。 */
    private val DOCUMENT_META_PATH: YPath = parseYPath("_document")
}
