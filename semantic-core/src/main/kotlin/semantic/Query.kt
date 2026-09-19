package semantic

import model.DocumentId
import model.YNode
import model.YPath
import model.parseYPath
import model.pretty
import model.scalarText
import schema.CheckTerm
import schema.ConstraintCheck
import schema.Projection
import schema.QueryWhereClause
import schema.ValueExpr
import schema.QueryDir
import schema.QueryEdgeStep
import schema.QueryNodeKind
import schema.QueryNodeStep
import schema.QuerySpec
import schema.QueryWhere
import schema.RefTarget

/**
 * パターン照合エンジンのカーネル（R22-01。3層コア移行 Phase 6C で index/Query.kt から移動）。
 * `QuerySpec`（schema/Query.kt）を SemanticEntityGraph の隣接上で評価し、束縛の表を返す。
 * - 決定性: 起点候補はID昇順・エッジ展開は distinct＋ID昇順・行の並びは
 *   「束縛名の辞書順に並べた束縛値（ID昇順）の辞書式比較」・limit 超過は決定的打切り
 * - 判定保留の哲学（§4.26）: 未解決参照・型未宣言のエンティティは黙って照合から外す
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
 * node ステップの絞り込み（型のどれか・出現スキーマ・ID パターンと、その否定）にエンティティが合うか。
 * クエリ・網羅マトリクス・遷移検証が共有する（1つの意味には1つの評価器）。
 * 否定（not）は3値で判定する——中の条件がすべて合えば「合わない」、どれかが合わなければ「合う」、
 * 型が宣言されていないために決まらないときは判定保留＝合わない（型未宣言のエンティティには黙る §4.26）。
 */
internal fun QueryNodeStep.matchesEntity(id: String, node: SemanticEntityNode): Boolean {
    if (idPattern != null && !idMatchesPattern(id, idPattern!!)) return false
    if (entities.isNotEmpty() && node.entityType !in entities) return false
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
            .filter { via == null || it.viaKey == via }
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
                .filter { via == null || it.viaKey == via }
                .mapNotNull { graph.sourceEntryOf(it)?.id }
                .distinct()
                .filter { schemaId == null || ids[it].orEmpty().any { e -> e.schemaId == schemaId } }
                .sorted()
                .toList()
        }
        else -> error("follow は out / in のいずれかです: $follow")
    }

    // ---------------------------------------------------------------- 評価

    /** 照合経路上のノードの同一性（kind＋ID）。エンティティと文書は別名前空間（§4.9 原則3）。 */
    private data class NodeKey(val kind: QueryNodeKind, val id: String)

    private class Evaluator(
        private val ids: Map<String, List<ScannedId>>,
        private val allRefs: List<ScannedRef>,
        private val graph: SemanticEntityGraph,
        private val spec: QuerySpec,
        private val loader: (DocumentId) -> YNode?,
        private val rel: (DocumentId) -> String = { it.value },
    ) {
        private val trees = mutableMapOf<DocumentId, YNode?>()

        private fun tree(d: DocumentId): YNode? = trees.getOrPut(d) { loader(d) }

        /** エンティティ参照の被参照索引（in エッジ）。全参照の並び順を保つ。 */
        private val entityRefsByTarget: Map<String, List<ScannedRef>> by lazy {
            allRefs.filter { it.targetKind == RefTarget.ENTITY }.groupBy { it.targetId }
        }

        /** 文書内に書かれた全参照（文書ノードの out エッジ）。 */
        private val refsByDocument: Map<DocumentId, List<ScannedRef>> by lazy { allRefs.groupBy { it.document } }

        /** 文書参照（targetKind: document）の被参照索引（文書ノードの in エッジ）。 */
        private val docRefsByTarget: Map<String, List<ScannedRef>> by lazy {
            allRefs.filter { it.targetKind == RefTarget.DOCUMENT }.groupBy { it.targetId }
        }

        /** 文書 → 文書ノードID（in エッジで「参照が書かれた文書」へ着地するときの解決）。 */
        private val documentIdByDoc: Map<DocumentId, String> by lazy {
            graph.documents.values.associate { it.document to it.documentId }
        }

        /** 束縛名 → その束縛に効くプロパティ述語（束縛が確定した時点で枝刈りする）。 */
        private val predsByBind: Map<String, List<QueryWhereClause>> = spec.where.groupBy { it.of }

        /** CALC-17: この評価の解探索で、未解決参照により候補が失われたか（expand が記録する）。 */
        private var unknownDropped = false

        /** 照合経路の1歩: 着いたノードと、edge に bind があるときは辿った参照そのもの。 */
        private inner class Step(val key: NodeKey, val ref: ScannedRef? = null)

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
                    val sameKey = hop.node.same?.let { path[bindPositionInMatch(it)].key }
                    if (sameKey != null) {
                        // same: 束縛済みノードそのものへの到達を要求（二度通らない規則の意図的な例外。R28-07）
                        expand(path.last().key, hop.edge, hop.node.copy(kind = sameKey.kind))
                            .filter { it.key == sameKey }
                            .map { path + it }
                    } else {
                        expand(path.last().key, hop.edge, hop.node)
                            .filter { step -> path.none { it.key == step.key } }
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
                                    .filter { step -> path.none { it.key == step.key } && bPath.none { it.key == step.key } }
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
                )
                if (firstOnly) break
            }
            // 行の並び: 束縛名の辞書順に並べた列の値（ID昇順）の辞書式比較（§4.32。列の表示順は宣言順のまま）。
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
            val candidates = (restrictStart?.let { r -> candidates0.filter { it == r } } ?: candidates0.sorted())
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
         * エッジ1段の展開。端の解決は到達側 node ステップの kind が決める（§4.32）:
         * out → entity = 解決済みのエンティティ参照先／document = 解決済みの文書参照先、
         * in → entity = 参照を書いた最内のID要素／document = 参照が書かれた文書。
         * edge に bind があれば参照ごとに1歩（同じ相手への参照が2本あれば2歩）、なければ着いたノードごとに1歩。
         */
        private fun expand(from: NodeKey, edge: QueryEdgeStep, target: QueryNodeStep): List<Step> {
            val refs = when (edge.dir) {
                QueryDir.OUT -> when (from.kind) {
                    QueryNodeKind.ENTITY -> graph.outgoingRefs(from.id)
                    QueryNodeKind.DOCUMENT -> graph.documents[from.id]?.let { refsByDocument[it.document] }.orEmpty()
                }
                QueryDir.IN -> when (from.kind) {
                    QueryNodeKind.ENTITY -> entityRefsByTarget[from.id].orEmpty()
                    QueryNodeKind.DOCUMENT -> docRefsByTarget[from.id].orEmpty()
                }
            }.asSequence()
                .filter { edge.relation == null || it.relation == edge.relation }
                .filter { edge.via == null || it.viaKey == edge.via }
                .filter { ref -> edge.inDoc?.matches(ref.sourceSchemaId, rel(ref.document)) ?: true }
            // 端をノードへ解決できない参照（対象IDが索引になし・書き手をエンティティへ解決できない）は
            // 黙って除外しつつ「候補の喪失」を記録する（CALC-17 の unknown-dropped）。
            // 対象種別の不一致（entity 到達に document 参照など）は構造的な非該当であり、喪失に数えない
            val ends: Sequence<Pair<NodeKey, ScannedRef>> = when (edge.dir) {
                QueryDir.OUT -> refs.mapNotNull { ref ->
                    when (target.kind) {
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
                    when (target.kind) {
                        QueryNodeKind.ENTITY -> graph.sourceEntryOf(ref)?.let { NodeKey(QueryNodeKind.ENTITY, it.id) to ref }
                            ?: run { unknownDropped = true; null }
                        QueryNodeKind.DOCUMENT -> documentIdByDoc[ref.document]?.let { NodeKey(QueryNodeKind.DOCUMENT, it) to ref }
                            ?: run { unknownDropped = true; null }
                    }
                }
            }
            val matched = ends.filter { nodeMatches(target, it.first) }.toList()
            return if (edge.bind == null) matched.map { it.first }.distinct().sortedBy { it.id }.map { Step(it) }
            else matched.distinctBy { it.first to refSortKey(it.second) }
                .sortedWith(compareBy({ it.first.id }, { refSortKey(it.second) }))
                .map { Step(it.first, it.second) }
        }

        /** 束縛が確定したノードに、その束縛のプロパティ述語をすべて適用する（1件の中の「または」は、どれかが成立すればよい）。 */
        private fun predsOk(bind: String?, key: NodeKey): Boolean {
            val clauses = bind?.let { predsByBind[it] } ?: return true
            return clauses.all { clause -> clause.alternatives.any { predValue(it, key) == true } }
        }

        /**
         * 選択述語（where）の3値の判定: σ の候補（sigmaCandidates）を存在量化する（CALC-13。エンティティは
         * いずれかの出現の該当パス＝多段定義の OR。§4.32）。値の読み出しは共有評価部の Get。
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
                    val found = counterexampleOf(row, check, pin) ?: continue
                    // 集計を含む項目は、どの解で評価しても同じ結果になる——同じ組を解の数だけ並べない
                    val duplicate = check.operands.any { it.hasAggregate() } && violations.any {
                        it.check == found.check && it.left == found.left && it.right == found.right
                    }
                    if (!duplicate) violations += found
                }
            }
            return violations
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
        private fun counterexampleOf(
            row: SemanticQueryRow,
            check: ConstraintCheck,
            pin: Pair<ValueExpr.ScopeRef, SemanticExpression.Occurrence>?,
        ): SemanticCheckViolation? {
            val sources = check.operands.flatMap { it.sources() }.distinct()
            val candidates: List<List<SemanticExpression.Occurrence?>> = sources.map { source ->
                if (pin != null && pin.first == source) listOf(pin.second)
                else candidatesFor(row, source).ifEmpty { listOf(null) }
            }
            val assignment = HashMap<ValueExpr.ScopeRef, SemanticExpression.Occurrence?>()
            fun search(index: Int): SemanticCheckViolation? {
                if (index == sources.size) {
                    // 項目は値の条件の「または」（1つなら単独）: どれかが成り立てば成立・成り立つものがなく
                    // 評価できない条件が残れば判定保留（飛ばす）・すべて成り立たなければ、最初の条件の値を示して違反
                    var unknown = false
                    var firstFailed: SemanticCheckViolation? = null
                    for (term in check.terms) {
                        val left = evalExpr(term.left, row, assignment)
                        val rights = term.rights.map { evalExpr(it, row, assignment) }
                        // 値の条件は共有評価部——評価できない（null）条件は判定保留
                        val plain = left?.let { SemanticExpression.test(term.op, it.text, rights.map { r -> r?.text }) }
                        if (left == null || plain == null) { unknown = true; continue }
                        if (plain != term.negated) return null
                        if (firstFailed == null) {
                            // 右辺が複数（oneOf の選択肢）のときは、選択肢を並べた1つの辺として示す
                            val right = rights.singleOrNull()
                                ?: SemanticCheckSide(text = rights.filterNotNull().joinToString("・") { it.text })
                            firstFailed = SemanticCheckViolation(check, term, left, right, row)
                        }
                    }
                    return if (unknown) null else firstFailed
                }
                for (occ in candidates[index]) {
                    assignment[sources[index]] = occ
                    search(index + 1)?.let { return it }
                }
                return null
            }
            return search(0)
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

        /** 値の式を、決めた出現の組の下で計算する（得られなければ null）。値の出どころを併せて返す。 */
        private fun evalExpr(
            expr: ValueExpr,
            row: SemanticQueryRow,
            assignment: Map<ValueExpr.ScopeRef, SemanticExpression.Occurrence?>,
        ): SemanticCheckSide? = when (expr) {
            is ValueExpr.Get -> assignment[ValueExpr.ScopeRef(expr.of, expr.inDoc)]?.let { occ ->
                SemanticExpression.textAt(::tree, occ, expr.path)?.let { text ->
                    SemanticCheckSide(
                        text = text,
                        bind = expr.of, id = row.bindings[expr.of]?.id,
                        document = occ.document, occPath = occ.path, path = expr.path,
                        raw = text, sourceDocument = occ.document, sourcePath = occ.path + expr.path,
                    )
                }
            }
            is ValueExpr.Literal -> expr.value?.let { SemanticCheckSide(text = it, raw = it) }
            is ValueExpr.Format -> evalExpr(expr.template, row, assignment)?.let { template ->
                // 入れ物は、出現が決まったものだけを宣言順（近い順）に並べる
                val scopes = expr.scopes.mapNotNull { ref ->
                    assignment[ref]?.let { occ -> SemanticExpression.nodeAt(::tree, occ, emptyList())?.let { occ to it } }
                }
                val evaluated = Projection.evaluate(template.text, scopes.map { it.second })
                if (!evaluated.complete) return@let null
                template.copy(
                    text = evaluated.text,
                    raw = template.text,
                    inputs = evaluated.resolutions.map { r ->
                        val occ = r.scopeIndex?.let { scopes.getOrNull(it)?.first }
                        val inputPath = occ?.let { o -> runCatching { o.path + parseYPath(r.placeholder) }.getOrNull() }
                        SemanticValueInput(r.placeholder, r.value, occ?.document, inputPath)
                    },
                )
            }
            is ValueExpr.MapValue -> evalExpr(expr.input, row, assignment)
                ?.let { it.copy(text = Projection.applyMap(it.text, expr.using)) }
            is ValueExpr.First -> expr.options.firstNotNullOfOrNull { evalExpr(it, row, assignment) }
            is ValueExpr.Unary -> evalExpr(expr.input, row, assignment)?.let { input ->
                SemanticExpression.unary(expr.fn, input.text)?.let { text ->
                    input.copy(text = text, raw = input.raw ?: input.text, note = expr.fn.label ?: input.note)
                }
            }
            // 参照の本数（SP-03）: グラフの隣接から数える。出ていく参照は相手が見つからないものも数え（CALC-20）、
            // 入ってくる参照はエンティティへの参照を数える。値の読み出しではないため、出現は選ばない
            is ValueExpr.RefCount -> row.bindings[expr.of]?.takeIf { it.kind == QueryNodeKind.ENTITY }?.let { node ->
                val refs = when (expr.dir) {
                    QueryDir.OUT -> graph.outgoingRefs(node.id)
                    QueryDir.IN -> entityRefsByTarget[node.id].orEmpty()
                }
                val n = refs.count { (expr.relation == null || it.relation == expr.relation) && (expr.via == null || it.viaKey == expr.via) }
                SemanticCheckSide(text = n.toString(), id = node.id, raw = n.toString())
            }
            // 集計（SP-03）: 同じ起点の全ての解にわたり、束縛の要素（重複は1つ）の値をまとめる。値は指定に合う文書のうち
            // 定義した側を優先して1つから読む（CALC-19）。得られない・数値として読めない値が1つでもあれば得られない
            is ValueExpr.Aggregate -> {
                val nodes = aggregateRows.mapNotNull { it.bindings[expr.of] }.distinctBy { it.kind to it.id }
                val path = expr.path
                if (path == null) SemanticCheckSide(text = nodes.size.toString(), raw = nodes.size.toString())
                else {
                    val values = nodes.map { node ->
                        val occ = aggregateOccurrence(node, expr.inDoc) ?: return null
                        SemanticExpression.textAt(::tree, occ, path) ?: return null
                    }
                    SemanticExpression.aggregate(expr.fn, values)?.let { text ->
                        SemanticCheckSide(text = text, raw = "${expr.fn.label}: ${values.joinToString("・")}")
                    }
                }
            }
            is ValueExpr.Arith -> {
                val operands = expr.operands.map { evalExpr(it, row, assignment) ?: return null }
                SemanticExpression.arith(expr.op, operands.map { it.text })?.let { text ->
                    // 計算結果は特定の1か所に書かれた値ではない——式（3 + 12）を元の値として示し、移動先は最初の値の場所
                    val first = operands.firstOrNull { it.sourceDocument != null }
                    SemanticCheckSide(
                        text = text,
                        id = first?.id,
                        raw = operands.joinToString(" ${expr.op.symbol} ") { it.text },
                        sourceDocument = first?.sourceDocument, sourcePath = first?.sourcePath,
                    )
                }
            }
        }

        /** 集計で値を読む出現: 指定に合う出現のうち、定義した側を優先して1つ（式側の決定的な選択。CALC-19）。 */
        private fun aggregateOccurrence(node: SemanticQueryNodeRef, selector: schema.DerivationSelector?): SemanticExpression.Occurrence? =
            when (node.kind) {
                QueryNodeKind.ENTITY -> SemanticExpression.preferDefining(graph.entities[node.id]?.occurrences.orEmpty())
                    .firstOrNull { selector == null || selector.matches(it.schemaId, rel(it.document)) }
                    ?.let { SemanticExpression.Occurrence(it.document, it.path) }
                QueryNodeKind.DOCUMENT -> sigmaCandidates(node.kind, node.id).firstOrNull()
            }

        /**
         * σ の割当て先（where の存在量化と check の反例量化が共有する。R29-05）: エンティティは
         * 全出現（定義＋詳細化）・文書は _document 配下の自明な1点（プロパティは _document 配下に限る。
         * ROADMAP §4.9 原則3）。
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
