package index

import schema.EntityRole
import java.nio.file.Path

/** グラフ導出用のファイル走査結果のビュー（IdIndex の内部表現から渡される）。 */
internal data class FileScanView(
    val file: Path,
    val documentId: String,
    val ids: List<IdEntry>,
    val refs: List<RefEntry>,
)

/** エンティティ表の1ノード（論理ID）。 */
data class EntityNode(
    val id: String,
    /** 出現の宣言型（定義出現の型を優先。宣言なしは null）。 */
    val entityType: String?,
    /** 定義出現（role=DEFINING の先頭。なければ null＝従来の「最初の出現」ヒューリスティクスの領分）。 */
    val defining: IdEntry?,
    val occurrences: List<IdEntry>,
    val title: String?,
)

/** 文書ノード（容れ物の仮想ノード）。包含（contains）エッジは entityIds として導出する（原本には書かない）。 */
data class DocumentNode(
    val documentId: String,
    val file: Path,
    val entityIds: List<String>,
)

/**
 * 走査結果から導出する読み取り専用のエンティティグラフ（R20-04。ARCHITECTURE §4.30）。
 * ノード＝エンティティ（論理ID）と文書、エッジ＝型付き参照（RefEntry）。グラフの永続ストアは持たず、
 * 索引の再構築でいつでも復元できる（原本主義。ROADMAP §4.9 原則2）。
 * `sourceEntryOf` / `backlinksTo` / トラバースの出次列挙は、従来の全参照線形走査と**同一の出力**を
 * 返す事前計算の載せ替え（公開APIと出力は不変。EntityGraphTest が旧実装との一致を保証する）。
 */
class EntityGraph private constructor(
    /** 論理ID → エンティティノード。 */
    val entities: Map<String, EntityNode>,
    /** documentId → 文書ノード（先勝ち。documentId 衝突時はパス順の先頭）。 */
    val documents: Map<String, DocumentNode>,
    private val refsByTarget: Map<String, List<RefEntry>>,
    private val sourceByRef: Map<RefEntry, IdEntry>,
    private val outByEntry: Map<IdEntry, List<RefEntry>>,
    private val refOrder: Map<RefEntry, Int>,
) {

    /** 指定 ID 群への被参照。全参照の並び順（パス順＝IdIndex.refs の順）を保つ。 */
    fun backlinksTo(idSet: Set<String>): List<RefEntry> {
        val merged = idSet.flatMap { refsByTarget[it].orEmpty() }
        return if (idSet.size <= 1) merged else merged.sortedBy { refOrder[it] ?: Int.MAX_VALUE }
    }

    /** 参照値の位置を含む、最も内側（パスが最長）の ID 出現＝参照を書いた要素。 */
    fun sourceEntryOf(ref: RefEntry): IdEntry? = sourceByRef[ref]

    /** ID の全出現のサブツリー配下に書かれた参照（出次エッジ。出現順→ファイル内の走査順）。 */
    fun outgoingRefs(id: String): List<RefEntry> =
        entities[id]?.occurrences.orEmpty().flatMap { outByEntry[it].orEmpty() }

    /** 1出現のサブツリー配下に書かれた参照。 */
    fun outgoingRefs(entry: IdEntry): List<RefEntry> = outByEntry[entry].orEmpty()

    companion object {
        internal fun build(
            files: List<FileScanView>,
            entries: Map<String, List<IdEntry>>,
            refs: List<RefEntry>,
        ): EntityGraph {
            // 参照の全体順（backlinksTo の複数ID版が従来の全走査順を保つための索引）
            val refOrder = HashMap<RefEntry, Int>(refs.size * 2)
            refs.forEachIndexed { i, r -> refOrder.putIfAbsent(r, i) }
            val refsByTarget = refs.groupBy { it.targetId }

            // 参照 → 参照元要素（最内のID出現）と、ID出現 → サブツリー配下の参照（出次）。
            // 同一ファイル内だけの突合で足りるため、コストはファイル単位に閉じる
            val sourceByRef = HashMap<RefEntry, IdEntry>()
            val outByEntry = HashMap<IdEntry, MutableList<RefEntry>>()
            for (scan in files) {
                if (scan.refs.isEmpty() || scan.ids.isEmpty()) continue
                for (ref in scan.refs) {
                    var innermost: IdEntry? = null
                    for (e in scan.ids) {
                        if (e.path.size <= ref.path.size && ref.path.subList(0, e.path.size) == e.path) {
                            outByEntry.getOrPut(e) { mutableListOf() }.add(ref)
                            if (innermost == null || e.path.size > innermost.path.size) innermost = e
                        }
                    }
                    innermost?.let { sourceByRef[ref] = it }
                }
            }

            // エンティティ表: 型・タイトルは定義出現を優先する（R20-01 の resolve と同じ規則）
            val entities0 = LinkedHashMap<String, EntityNode>(entries.size * 2)
            entries.forEach { (id, occurrences) ->
                val defining = occurrences.firstOrNull { it.role == EntityRole.DEFINING }
                entities0[id] = EntityNode(
                    id = id,
                    entityType = defining?.entityType ?: occurrences.firstNotNullOfOrNull { it.entityType },
                    defining = defining,
                    occurrences = occurrences,
                    title = defining?.title ?: occurrences.firstNotNullOfOrNull { it.title },
                )
            }

            // 文書ノード表: 識別は既存 documentId（_document.id ＞ 相対パス）。衝突は先勝ち（パス順）
            val documents = LinkedHashMap<String, DocumentNode>()
            for (scan in files) {
                documents.putIfAbsent(
                    scan.documentId,
                    DocumentNode(scan.documentId, scan.file, scan.ids.map { it.id }.distinct()),
                )
            }

            return EntityGraph(entities0, documents, refsByTarget, sourceByRef, outByEntry, refOrder)
        }
    }
}
