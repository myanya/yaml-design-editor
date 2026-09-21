package index

import semantic.SemanticEntityGraph
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
 * エンティティグラフ（R20-04。ARCHITECTURE §4.30）の Path 座標シェル（B-2・RB-02 でカーネル一本化）。
 * 導出の実体——最内出現の突合（sourceEntryOf）・出次エッジ（outgoingRefs）・エンティティ表・
 * 文書ノード表——はカーネル semantic.SemanticEntityGraph（SM-003）で、ここは IdEntry / RefEntry ↔
 * Scanned の座標変換（SemanticBridge。document＝絶対パス文字列＝1:1可逆）と、被参照の全体順
 * （backlinksTo＝Path 側の集約）だけを持つ。公開APIと出力は従来と同一（EntityGraphTest が固定）。
 */
class EntityGraph private constructor(
    /** 論理ID → エンティティノード。 */
    val entities: Map<String, EntityNode>,
    /** documentId → 文書ノード（先勝ち。documentId 衝突時はパス順の先頭）。 */
    val documents: Map<String, DocumentNode>,
    private val refsByTarget: Map<String, List<RefEntry>>,
    private val refOrder: Map<RefEntry, Int>,
    private val kernel: SemanticEntityGraph,
) {

    /** 指定 ID 群への被参照。全参照の並び順（パス順＝IdIndex.refs の順）を保つ。 */
    fun backlinksTo(idSet: Set<String>): List<RefEntry> {
        val merged = idSet.flatMap { refsByTarget[it].orEmpty() }
        return if (idSet.size <= 1) merged else merged.sortedBy { refOrder[it] ?: Int.MAX_VALUE }
    }

    /** 参照値の位置を含む、最も内側（パスが最長）の ID 出現＝参照を書いた要素。 */
    fun sourceEntryOf(ref: RefEntry): IdEntry? = kernel.sourceEntryOf(ref.toScanned())?.toIdEntry()

    /** ID の全出現のサブツリー配下に書かれた参照（出次エッジ。出現順→ファイル内の走査順）。 */
    fun outgoingRefs(id: String): List<RefEntry> = kernel.outgoingRefs(id).map { it.toRefEntry() }

    /** 1出現のサブツリー配下に書かれた参照。 */
    fun outgoingRefs(entry: IdEntry): List<RefEntry> = kernel.outgoingRefs(entry.toScanned()).map { it.toRefEntry() }

    companion object {
        /**
         * kernel は、同じ走査結果から作ったカーネルのグラフ（IdIndex.toKernelView のもの）。ここでは作り直さず、
         * ファイルの座標（Path）の表へ写すだけ（P27 段D の D-d）。
         */
        internal fun build(refs: List<RefEntry>, kernel: SemanticEntityGraph): EntityGraph {
            // 参照の全体順（backlinksTo の複数ID版が従来の全走査順を保つための索引）＝Path 側の集約として残す
            val refOrder = HashMap<RefEntry, Int>(refs.size * 2)
            refs.forEachIndexed { i, r -> refOrder.putIfAbsent(r, i) }
            val refsByTarget = refs.groupBy { it.targetId }

            val entityNodes = LinkedHashMap<String, EntityNode>(kernel.entities.size * 2)
            kernel.entities.forEach { (id, n) ->
                entityNodes[id] = EntityNode(
                    id = n.id,
                    entityType = n.entityType,
                    defining = n.defining?.toIdEntry(),
                    occurrences = n.occurrences.map { it.toIdEntry() },
                    title = n.title,
                )
            }
            val documentNodes = LinkedHashMap<String, DocumentNode>(kernel.documents.size * 2)
            kernel.documents.forEach { (id, d) ->
                documentNodes[id] = DocumentNode(d.documentId, Path.of(d.document.value), d.entityIds)
            }
            return EntityGraph(entityNodes, documentNodes, refsByTarget, refOrder, kernel)
        }
    }
}
