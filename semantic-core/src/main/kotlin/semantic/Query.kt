package semantic

import model.DocumentId
import model.YNode
import model.YPath
import model.getAt
import model.scalarText
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
 * - 1本の照合経路の中で同じノードを二度通らない（循環照合の停止）
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

/** 照合結果。total は limit 打切り前の全行数。 */
data class SemanticQueryResult(val columns: List<String>, val rows: List<SemanticQueryRow>, val total: Int)

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

        fun evaluate(restrictStart: String? = null, firstOnly: Boolean = false): SemanticQueryResult {
            var paths = startCandidates(restrictStart)
                .filter { predsOk(spec.start.bind, it) }
                .map { listOf(it) }
            for (hop in spec.hops) {
                paths = paths.flatMap { path ->
                    expand(path.last(), hop.edge, hop.node)
                        .filter { it !in path }
                        .filter { predsOk(hop.node.bind, it) }
                        .map { path + it }
                }
            }
            // 分岐（also）: from の束縛ノードを起点に、もう1本の線形連鎖を内部結合する
            val branch = spec.also
            if (branch != null) {
                val fromPos = bindPositionInMatch(branch.from)
                paths = paths.flatMap { path ->
                    var branches = listOf(emptyList<NodeKey>())
                    for (hop in branch.hops) {
                        branches = branches.flatMap { bPath ->
                            val fromKey = bPath.lastOrNull() ?: path[fromPos]
                            expand(fromKey, hop.edge, hop.node)
                                .filter { it !in path && it !in bPath }
                                .filter { predsOk(hop.node.bind, it) }
                                .map { bPath + it }
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
            return SemanticQueryResult(columns = spec.binds, rows = sorted.take(spec.limit), total = sorted.size)
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
            val ends = when (edge.dir) {
                QueryDir.OUT -> refs.mapNotNull { ref ->
                    when (target.kind) {
                        QueryNodeKind.ENTITY ->
                            if (ref.targetKind == RefTarget.ENTITY && graph.entities.containsKey(ref.targetId)) {
                                NodeKey(QueryNodeKind.ENTITY, ref.targetId)
                            } else null
                        QueryNodeKind.DOCUMENT ->
                            if (ref.targetKind == RefTarget.DOCUMENT && graph.documents.containsKey(ref.targetId)) {
                                NodeKey(QueryNodeKind.DOCUMENT, ref.targetId)
                            } else null
                    }
                }
                QueryDir.IN -> refs.mapNotNull { ref ->
                    when (target.kind) {
                        QueryNodeKind.ENTITY -> graph.sourceEntryOf(ref)?.let { NodeKey(QueryNodeKind.ENTITY, it.id) }
                        QueryNodeKind.DOCUMENT -> documentIdByDoc[ref.document]?.let { NodeKey(QueryNodeKind.DOCUMENT, it) }
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

        private fun predOk(pred: QueryWhere, key: NodeKey): Boolean {
            fun nodesAt(): List<YNode?> = when (key.kind) {
                // エンティティ: いずれかの出現の該当パス（多段定義の OR。§4.32）
                QueryNodeKind.ENTITY -> graph.entities[key.id]?.occurrences.orEmpty()
                    .map { e -> tree(e.document)?.let { getAt(it, e.path + pred.path) } }
                // 文書: プロパティは _document 配下に限る（ROADMAP §4.9 原則3）
                QueryNodeKind.DOCUMENT -> listOf(
                    graph.documents[key.id]?.let { doc ->
                        ((tree(doc.document) as? YNode.Map)?.entries?.get("_document"))?.let { getAt(it, pred.path) }
                    }
                )
            }
            val values = nodesAt()
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
    }
}
