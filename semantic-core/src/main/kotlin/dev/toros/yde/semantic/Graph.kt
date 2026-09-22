package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.QueryNodeStep
import dev.toros.yde.semantic.schema.RefTarget
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.semantic.schema.EntityRole
import dev.toros.yde.yaml.model.YPath

/**
 * グラフ導出用の文書単位走査結果（SemanticDocument 全体を要求しない軽量ビュー。6A）。
 * ルート側のシェル（IdIndex の走査結果からの変換）もこの形で渡す。
 */
data class DocumentScan(
    val document: DocumentId,
    val documentId: String,
    val ids: List<ScannedId>,
    val refs: List<ScannedRef>,
)

/** エンティティ表の1ノード（論理ID。ルート index.EntityNode の DocumentId 座標版。SM-003）。 */
data class SemanticEntityNode(
    val id: String,
    /** 出現の宣言型（定義出現の型を優先。宣言なしは null）。 */
    val entityType: String?,
    /** 定義出現（role = DEFINING の先頭。なければ null）。 */
    val defining: ScannedId?,
    val occurrences: List<ScannedId>,
    val title: String?,
)

/** 文書ノード（容れ物の仮想ノード）。包含（contains）は entityIds として導出する（原本には書かない）。 */
data class SemanticDocumentNode(
    val documentId: String,
    val document: DocumentId,
    val entityIds: List<String>,
)

/**
 * 走査結果から導出する読み取り専用のエンティティグラフ（R20-04 の DocumentId 座標版。Phase 5・SM-003）。
 * ノード＝エンティティ（論理ID）と文書、エッジ＝型付き参照（ScannedRef）。永続ストアは持たず、
 * SemanticEngine.build のたびに全再構築される（原本主義）。
 * ルート index.EntityGraph（ディスク基準シェルのキャッシュ）は Phase 7〜8 で IdIndex が
 * SemanticEngine の上へ再構成されるまで併存する（移行台帳 SM-003 参照）。
 */
class SemanticEntityGraph private constructor(
    /** 論理ID → エンティティノード。 */
    val entities: Map<String, SemanticEntityNode>,
    /** documentId → 文書ノード（先勝ち。documentId 衝突時は文書ID順の先頭）。 */
    val documents: Map<String, SemanticDocumentNode>,
    private val sourceByRef: Map<ScannedRef, ScannedId>,
    private val outByEntry: Map<ScannedId, List<ScannedRef>>,
    /** ID 出現 → その出現を含む、最も内側の別の ID 出現（入れ子の外側の要素。いちばん外側なら無い）。 */
    private val parentByEntry: Map<ScannedId, ScannedId>,
) {

    /** 参照値の位置を含む、最も内側（パスが最長）の ID 出現＝参照を書いた要素。 */
    fun sourceEntryOf(ref: ScannedRef): ScannedId? = sourceByRef[ref]

    /**
     * 全参照から引く表（クエリの評価が使う）。クエリは「検査 × 要素」の数だけ評価されるため、評価のたびに作ると
     * 全体の検証の時間が規模の2乗で伸びる（BACKLOG UX-116 の実測）。同じ参照の並び（同じ走査結果）については1回だけ作る。
     */
    fun refIndexFor(refs: List<ScannedRef>): SemanticRefIndex {
        refIndexCache?.let { (cachedRefs, index) -> if (cachedRefs === refs) return index }
        return SemanticRefIndex(refs, this).also { refIndexCache = refs to it }
    }

    @Volatile
    private var refIndexCache: Pair<List<ScannedRef>, SemanticRefIndex>? = null

    /** ID の全出現のサブツリー配下に書かれた参照（出次エッジ。出現順→文書内の走査順）。 */
    fun outgoingRefs(id: String): List<ScannedRef> =
        entities[id]?.occurrences.orEmpty().flatMap { outByEntry[it].orEmpty() }

    /** 1出現のサブツリー配下に書かれた参照。 */
    fun outgoingRefs(entry: ScannedId): List<ScannedRef> = outByEntry[entry].orEmpty()

    /**
     * その要素が**直接書いた**参照（参照を含む最も内側の ID 出現が、この ID のもの。入れ子の要素が書いた参照は含めない）。
     * クエリの edge と参照の本数は、向きによらずこの捉え方を使う（sourceEntryOf と対になる。意図的差分 D-27）。
     * outgoingRefs（配下の全参照）は、その要素の配下を読む機能（文脈の開示・エンティティページ）が使う。
     */
    fun ownRefs(id: String): List<ScannedRef> = outgoingRefs(id).filter { sourceByRef[it]?.id == id }

    /** 入れ子の線（contains）: この要素の直下に入れ子になった、ID を持つ要素（ID 昇順）。 */
    fun childIds(id: String): List<String> = childrenById[id].orEmpty()

    /** 入れ子の線の逆向き: この要素を直下に持つ、外側の要素（ID 昇順。出現ごとに外側が違えば複数）。 */
    fun parentIds(id: String): List<String> =
        entities[id]?.occurrences.orEmpty().mapNotNull { parentByEntry[it]?.id }.distinct().sorted()

    /** その文書に、いちばん外側で（他の ID を持つ要素の入れ子にならずに）書かれた要素（ID 昇順）。 */
    fun topLevelIds(document: DocumentId): List<String> = topLevelByDocument[document].orEmpty()

    /** この要素が、いちばん外側で書かれている文書。 */
    fun topLevelDocuments(id: String): List<DocumentId> =
        entities[id]?.occurrences.orEmpty().filter { it !in parentByEntry }.map { it.document }.distinct()

    private val childrenById: Map<String, List<String>> by lazy {
        parentByEntry.entries.groupBy({ it.value.id }, { it.key.id }).mapValues { it.value.distinct().sorted() }
    }

    private val topLevelByDocument: Map<DocumentId, List<String>> by lazy {
        entities.values.flatMap { it.occurrences }.filter { it !in parentByEntry }
            .groupBy({ it.document }, { it.id }).mapValues { it.value.distinct().sorted() }
    }

    /** node の絞り込みに合うエンティティの ID（ID 昇順）。「すべての要素について」の検査が、起点ごとに数え直さないように覚える。 */
    fun entityIdsMatching(step: QueryNodeStep): List<String> = synchronized(membersCache) {
        membersCache.getOrPut(step) { entities.entries.filter { (id, node) -> step.matchesEntity(id, node) }.map { it.key }.sorted() }
    }

    private val membersCache = HashMap<QueryNodeStep, List<String>>()

    companion object {
        /** 文書単位の走査結果からグラフを導出する（同一文書内の突合だけで足りるため、コストは文書単位に閉じる）。 */
        fun build(
            perDocument: List<DocumentScan>,
            entries: Map<String, List<ScannedId>>,
        ): SemanticEntityGraph {
            // 参照 → 参照元要素（最内のID出現）と、ID出現 → サブツリー配下の参照（出次）
            val sourceByRef = HashMap<ScannedRef, ScannedId>()
            val outByEntry = HashMap<ScannedId, MutableList<ScannedRef>>()
            // ID 出現 → それを含む最も内側の別の ID 出現（入れ子の線。同じ文書の中だけで決まる）
            val parentByEntry = HashMap<ScannedId, ScannedId>()
            for ((_, _, ids, _) in perDocument) {
                if (ids.size < 2) continue
                for (e in ids) {
                    var parent: ScannedId? = null
                    for (other in ids) {
                        if (other.path.size < e.path.size && e.path.subList(0, other.path.size) == other.path) {
                            if (parent == null || other.path.size > parent.path.size) parent = other
                        }
                    }
                    parent?.let { parentByEntry[e] = it }
                }
            }
            for ((_, _, ids, refs) in perDocument) {
                if (refs.isEmpty() || ids.isEmpty()) continue
                for (ref in refs) {
                    var innermost: ScannedId? = null
                    for (e in ids) {
                        if (e.path.size <= ref.path.size && ref.path.subList(0, e.path.size) == e.path) {
                            outByEntry.getOrPut(e) { mutableListOf() }.add(ref)
                            if (innermost == null || e.path.size > innermost.path.size) innermost = e
                        }
                    }
                    innermost?.let { sourceByRef[ref] = it }
                }
            }

            // エンティティ表: 型・タイトルは定義出現を優先する（R20-01 の resolve と同じ規則）
            val entities = LinkedHashMap<String, SemanticEntityNode>(entries.size * 2)
            entries.forEach { (id, occurrences) ->
                val defining = occurrences.firstOrNull { it.role == EntityRole.DEFINING }
                entities[id] = SemanticEntityNode(
                    id = id,
                    entityType = defining?.entityType ?: occurrences.firstNotNullOfOrNull { it.entityType },
                    defining = defining,
                    occurrences = occurrences,
                    title = defining?.title ?: occurrences.firstNotNullOfOrNull { it.title },
                )
            }

            // 文書ノード表: 識別は既存 documentId（_document.id ＞ 相対パス）。衝突は先勝ち（文書ID順）
            val documents = LinkedHashMap<String, SemanticDocumentNode>()
            for ((document, documentId, ids, _) in perDocument) {
                documents.putIfAbsent(
                    documentId,
                    SemanticDocumentNode(documentId, document, ids.map { it.id }.distinct()),
                )
            }

            return SemanticEntityGraph(entities, documents, sourceByRef, outByEntry, parentByEntry)
        }
    }
}

/**
 * 全参照から引く表（SemanticEntityGraph.refIndexFor が、同じ参照の並びにつき1回だけ作る）。どれも必要になったときに作る。
 */
class SemanticRefIndex(refs: List<ScannedRef>, graph: SemanticEntityGraph) {
    /** エンティティ参照の被参照索引（in エッジ）。全参照の並び順を保つ。 */
    val entityRefsByTarget: Map<String, List<ScannedRef>> by lazy {
        refs.filter { it.targetKind == RefTarget.ENTITY }.groupBy { it.targetId }
    }

    /** 文書内に書かれた全参照（文書ノードの out エッジ）。 */
    val refsByDocument: Map<DocumentId, List<ScannedRef>> by lazy { refs.groupBy { it.document } }

    /** 文書参照（targetKind: document）の被参照索引（文書ノードの in エッジ）。 */
    val docRefsByTarget: Map<String, List<ScannedRef>> by lazy {
        refs.filter { it.targetKind == RefTarget.DOCUMENT }.groupBy { it.targetId }
    }

    /** 文書 → 文書ノードID（in エッジで「参照が書かれた文書」へ着地するときの解決）。 */
    val documentIdByDoc: Map<DocumentId, String> by lazy {
        graph.documents.values.associate { it.document to it.documentId }
    }

    /** 行（文書と、参照の1つ上のパス）→ その行の直下に書かれたエンティティ参照（行を線とみなす指定 row 用）。 */
    val refsByRow: Map<Pair<DocumentId, YPath>, List<ScannedRef>> by lazy {
        refs.filter { it.targetKind == RefTarget.ENTITY }.groupBy { it.document to it.path.dropLast(1) }
    }
}
