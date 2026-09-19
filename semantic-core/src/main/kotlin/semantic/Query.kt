package semantic

import model.DocumentId
import model.YNode
import model.YPath
import model.parseYPath
import model.scalarText
import schema.ConstraintCheck
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

/** 結果の1行（束縛名 → ノード。列順は束縛の宣言順）。 */
data class SemanticQueryRow(val bindings: Map<String, SemanticQueryNodeRef>)

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

/** check 反例の片辺（R28-08）: どの束縛の・どの出現から・何を読んだか（provenance の最小形）。 */
data class SemanticCheckSide(
    val bind: String,
    val id: String,
    val document: DocumentId,
    val occPath: YPath,
    val text: String,
)

/** check 判定の反例1件: 違反した出現割当て σ（項目内で同一束縛は同一出現。CORE-CALCULUS §8）。 */
data class SemanticCheckViolation(
    val check: ConstraintCheck,
    val left: SemanticCheckSide,
    val right: SemanticCheckSide,
)

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
        loader: (DocumentId) -> YNode?,
    ): List<SemanticCheckViolation> = Evaluator(ids, refs, graph, spec, loader).checkFrom(startId, checks)

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
        private val predsByBind: Map<String, List<QueryWhere>> = spec.where.groupBy { it.of }

        /** CALC-17: この評価の解探索で、未解決参照により候補が失われたか（expand が記録する）。 */
        private var unknownDropped = false

        fun evaluate(restrictStart: String? = null, firstOnly: Boolean = false): SemanticQueryResult {
            var paths = startCandidates(restrictStart)
                .filter { predsOk(spec.start.bind, it) }
                .map { listOf(it) }
            for (hop in spec.hops) {
                paths = paths.flatMap { path ->
                    val sameKey = hop.node.same?.let { path[bindPositionInMatch(it)] }
                    if (sameKey != null) {
                        // same: 束縛済みノードそのものへの到達を要求（二度通らない規則の意図的な例外。R28-07）
                        expand(path.last(), hop.edge, hop.node.copy(kind = sameKey.kind))
                            .filter { it == sameKey }
                            .map { path + it }
                    } else {
                        expand(path.last(), hop.edge, hop.node)
                            .filter { it !in path }
                            .filter { predsOk(hop.node.bind, it) }
                            .map { path + it }
                    }
                }
            }
            // 分岐（also）: from の束縛ノードを起点に、もう1本の線形連鎖を内部結合する
            val branch = spec.also
            if (branch != null) {
                val fromPos = bindPositionInMatch(branch.from)
                paths = paths.flatMap { path ->
                    var branches = listOf(emptyList<NodeKey>())
                    for ((hopIndex, hop) in branch.hops.withIndex()) {
                        branches = branches.flatMap { bPath ->
                            val fromKey = bPath.lastOrNull() ?: path[fromPos]
                            val sameKey = hop.node.same?.let { s ->
                                val mPos = bindPositionInMatch(s)
                                if (mPos >= 0) path[mPos]
                                else bPath[branch.hops.take(hopIndex).indexOfFirst { it.node.bind == s }]
                            }
                            if (sameKey != null) {
                                expand(fromKey, hop.edge, hop.node.copy(kind = sameKey.kind))
                                    .filter { it == sameKey }
                                    .map { bPath + it }
                            } else {
                                expand(fromKey, hop.edge, hop.node)
                                    .filter { it !in path && it !in bPath }
                                    .filter { predsOk(hop.node.bind, it) }
                                    .map { bPath + it }
                            }
                        }
                    }
                    branches.map { path + it }
                }
            }
            // 行 = 束縛値の組（宣言順の列）。束縛されない中間ノードは行の同一性に関与しない
            val bindPositions = bindPositions()
            val seen = LinkedHashSet<List<String>>()
            val rows = mutableListOf<SemanticQueryRow>()
            for (path in paths) {
                val key = bindPositions.map { (_, pos) -> path[pos].kind.id + ":" + path[pos].id }
                if (!seen.add(key)) continue
                rows += SemanticQueryRow(bindPositions.associate { (bind, pos) -> bind to refOf(path[pos]) })
                if (firstOnly) break
            }
            // 行の並び: 束縛名の辞書順に並べた列の値（ID昇順）の辞書式比較（§4.32。列の表示順は宣言順のまま）
            val sortBinds = spec.binds.sorted()
            val sorted = rows.sortedWith(
                Comparator { a, b ->
                    for (bind in sortBinds) {
                        val c = (a.bindings[bind]?.id ?: "").compareTo(b.bindings[bind]?.id ?: "")
                        if (c != 0) return@Comparator c
                    }
                    0
                }
            )
            return SemanticQueryResult(
                columns = spec.binds,
                rows = sorted.take(spec.limit),
                total = sorted.size,
                unknownDropped = unknownDropped,
            )
        }

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
            if (step.idPattern != null && !idMatchesPattern(key.id, step.idPattern)) return false
            return when (key.kind) {
                QueryNodeKind.ENTITY -> {
                    val node = graph.entities[key.id] ?: return false
                    (step.entity == null || node.entityType == step.entity) &&
                        (step.schema == null || node.occurrences.any { it.schemaId == step.schema })
                }
                QueryNodeKind.DOCUMENT -> graph.documents.containsKey(key.id)
            }
        }

        /**
         * エッジ1段の展開。端の解決は到達側 node ステップの kind が決める（§4.32）:
         * out → entity = 解決済みのエンティティ参照先／document = 解決済みの文書参照先、
         * in → entity = 参照を書いた最内のID要素／document = 参照が書かれた文書。
         */
        private fun expand(from: NodeKey, edge: QueryEdgeStep, target: QueryNodeStep): List<NodeKey> {
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
            // 端をノードへ解決できない参照（対象IDが索引になし・書き手をエンティティへ解決できない）は
            // 黙って除外しつつ「候補の喪失」を記録する（CALC-17 の unknown-dropped）。
            // 対象種別の不一致（entity 到達に document 参照など）は構造的な非該当であり、喪失に数えない
            val ends = when (edge.dir) {
                QueryDir.OUT -> refs.mapNotNull { ref ->
                    when (target.kind) {
                        QueryNodeKind.ENTITY ->
                            if (ref.targetKind != RefTarget.ENTITY) null
                            else if (!graph.entities.containsKey(ref.targetId)) { unknownDropped = true; null }
                            else NodeKey(QueryNodeKind.ENTITY, ref.targetId)
                        QueryNodeKind.DOCUMENT ->
                            if (ref.targetKind != RefTarget.DOCUMENT) null
                            else if (!graph.documents.containsKey(ref.targetId)) { unknownDropped = true; null }
                            else NodeKey(QueryNodeKind.DOCUMENT, ref.targetId)
                    }
                }
                QueryDir.IN -> refs.mapNotNull { ref ->
                    when (target.kind) {
                        QueryNodeKind.ENTITY -> graph.sourceEntryOf(ref)?.let { NodeKey(QueryNodeKind.ENTITY, it.id) }
                            ?: run { unknownDropped = true; null }
                        QueryNodeKind.DOCUMENT -> documentIdByDoc[ref.document]?.let { NodeKey(QueryNodeKind.DOCUMENT, it) }
                            ?: run { unknownDropped = true; null }
                    }
                }
            }
            return ends.distinct().filter { nodeMatches(target, it) }.sortedBy { it.id }.toList()
        }

        /** 束縛が確定したノードに、その束縛のプロパティ述語をすべて適用する。 */
        private fun predsOk(bind: String?, key: NodeKey): Boolean {
            val preds = bind?.let { predsByBind[it] } ?: return true
            return preds.all { predOk(it, key) }
        }

        /**
         * 選択述語（where）: σ の候補（sigmaCandidates）を存在量化する（CALC-13。エンティティは
         * いずれかの出現の該当パス＝多段定義の OR。§4.32）。値の読み出しは共有評価部の Get。
         */
        private fun predOk(pred: QueryWhere, key: NodeKey): Boolean {
            val values = sigmaCandidates(key.kind, key.id).map { SemanticExpression.nodeAt(::tree, it, pred.path) }
            return when {
                pred.equals != null -> values.any { scalarText(it) == pred.equals }
                // notEquals（R28-02）: 値が1つ以上あり、どの出現の値も指定値でない
                // （equals の OR 解釈の否定。値がなければ黙る＝判定保留）
                pred.notEquals != null ->
                    values.any { it != null } && values.none { scalarText(it) == pred.notEquals }
                pred.exists == true -> values.any { it != null }
                else -> values.none { it != null }
            }
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

        fun checkFrom(startId: String, checks: List<ConstraintCheck>): List<SemanticCheckViolation> {
            val result = evaluate(restrictStart = startId)
            val violations = mutableListOf<SemanticCheckViolation>()
            for (row in result.rows) {
                for (check in checks) {
                    counterexampleOf(row, check)?.let { violations += it }
                }
            }
            return violations
        }

        /**
         * 1行×1項目の反例探索。σ（束縛への出現の割当て）を出現リスト順に走査し、要素述語が
         * False になる最初の組を返す（決定的）。値が読めない・順序比較で数値でない組は評価不能
         * として飛ばし、評価可能な組がなければ null（判定保留＝黙る）。
         */
        private fun counterexampleOf(row: SemanticQueryRow, check: ConstraintCheck): SemanticCheckViolation? {
            val leftRef = row.bindings[check.left.of] ?: return null
            val rightRef = row.bindings[check.right.of] ?: return null
            val sameBind = check.left.of == check.right.of
            for (leftOcc in sigmaCandidates(leftRef.kind, leftRef.id)) {
                val leftText = SemanticExpression.textAt(::tree, leftOcc, check.left.path) ?: continue
                // 項目内の σ は一貫: 同一束縛の両辺は同一出現から読む（CORE-CALCULUS §7 の σ）
                val rightOccs = if (sameBind) listOf(leftOcc) else sigmaCandidates(rightRef.kind, rightRef.id)
                for (rightOcc in rightOccs) {
                    val rightText = SemanticExpression.textAt(::tree, rightOcc, check.right.path) ?: continue
                    // 要素述語（σ 固定の単一値比較）は共有評価部——評価不能（null）の組は飛ばす
                    val satisfied = SemanticExpression.compare(check.op, leftText, rightText) ?: continue
                    if (!satisfied) {
                        return SemanticCheckViolation(
                            check = check,
                            left = SemanticCheckSide(check.left.of, leftRef.id, leftOcc.document, leftOcc.path, leftText),
                            right = SemanticCheckSide(check.right.of, rightRef.id, rightOcc.document, rightOcc.path, rightText),
                        )
                    }
                }
            }
            return null
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
