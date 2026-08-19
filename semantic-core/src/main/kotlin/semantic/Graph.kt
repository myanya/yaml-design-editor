package semantic

import model.DocumentId
import schema.EntityRole

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
) {

    /** 参照値の位置を含む、最も内側（パスが最長）の ID 出現＝参照を書いた要素。 */
    fun sourceEntryOf(ref: ScannedRef): ScannedId? = sourceByRef[ref]

    /** ID の全出現のサブツリー配下に書かれた参照（出次エッジ。出現順→文書内の走査順）。 */
    fun outgoingRefs(id: String): List<ScannedRef> =
        entities[id]?.occurrences.orEmpty().flatMap { outByEntry[it].orEmpty() }

    /** 1出現のサブツリー配下に書かれた参照。 */
    fun outgoingRefs(entry: ScannedId): List<ScannedRef> = outByEntry[entry].orEmpty()

    companion object {
        /** 文書単位の走査結果からグラフを導出する（同一文書内の突合だけで足りるため、コストは文書単位に閉じる）。 */
        internal fun build(
            perDocument: List<Triple<SemanticDocument, List<ScannedId>, List<ScannedRef>>>,
            entries: Map<String, List<ScannedId>>,
        ): SemanticEntityGraph {
            // 参照 → 参照元要素（最内のID出現）と、ID出現 → サブツリー配下の参照（出次）
            val sourceByRef = HashMap<ScannedRef, ScannedId>()
            val outByEntry = HashMap<ScannedId, MutableList<ScannedRef>>()
            for ((_, ids, refs) in perDocument) {
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
            for ((doc, ids, _) in perDocument) {
                documents.putIfAbsent(
                    doc.documentId,
                    SemanticDocumentNode(doc.documentId, doc.id, ids.map { it.id }.distinct()),
                )
            }

            return SemanticEntityGraph(entities, documents, sourceByRef, outByEntry)
        }
    }
}
