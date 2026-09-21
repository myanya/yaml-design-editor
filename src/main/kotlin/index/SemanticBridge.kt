package index

import model.DocumentId
import semantic.DocumentScan
import semantic.ScannedId
import semantic.ScannedRef
import semantic.SemanticEntityGraph
import java.nio.file.Path

/*
 * IdIndex（Path 座標）と semantic-core カーネル（DocumentId 座標）の変換ブリッジ。
 * Phase 6A/6B のシェル（Validator / Aspects）が共有する。DocumentId の値はパス文字列。
 * IdIndex が SemanticEngine の上へ再構成される Phase 7〜8 でこのブリッジごと解消する。
 */

fun IdEntry.toScanned(): ScannedId =
    ScannedId(id, DocumentId(file.toString()), path, title, schemaId, documentId, entityType, role)

fun RefEntry.toScanned(): ScannedRef =
    ScannedRef(targetId, DocumentId(file.toString()), path, viaKey, idPatterns, scope, relation, sourceSchemaId, attributes, targetKind)

fun ScannedId.toIdEntry(): IdEntry =
    IdEntry(id, Path.of(document.value), path, title, schemaId, documentId, entityType, role)

fun ScannedRef.toRefEntry(): RefEntry =
    RefEntry(targetId, Path.of(document.value), path, viaKey, idPatterns, scope, relation, sourceSchemaId, attributes, targetKind)

/** 走査結果を文書単位の DocumentScan へ写す（パス順で安定化＝索引の derive と同じ）。 */
fun IdIndex.toDocumentScans(): List<DocumentScan> {
    val idsByFile = entries.values.flatten().groupBy { it.file }
    val refsByFile = refs.groupBy { it.file }
    return (idsByFile.keys + refsByFile.keys).sortedBy { it.toString() }.map { f ->
        DocumentScan(
            document = DocumentId(f.toString()),
            documentId = idsByFile[f]?.firstOrNull()?.documentId ?: f.toString(),
            ids = idsByFile[f].orEmpty().map { it.toScanned() },
            refs = refsByFile[f].orEmpty().map { it.toScanned() },
        )
    }
}

/** カーネル入力一式（走査列・ID集約・全参照・グラフ）。シェルが1回の変換で共有する。 */
class KernelIndexView(
    val scans: List<DocumentScan>,
    val entries: Map<String, List<ScannedId>>,
    val refs: List<ScannedRef>,
    val graph: SemanticEntityGraph,
) {
    /**
     * 検査の評価の覚え書き（P27 段B）: この索引の上では、検査 × 要素の結果を1回だけ評価する。全体を回す経路
     * （CLI validate・準備完了レポート・提出ゲート・アプリの全体検証）が共有する。この一式は走査結果が変わると作り直されるため、
     * 覚え書きも一緒に捨てられる。共有してよいのは、loader の中身がこの索引と同じ内容の呼び出しだけ（SemanticCheckSession の前提）。
     */
    val checkSession: semantic.SemanticCheckSession by lazy { semantic.SemanticCheckSession(entries, refs, graph) }

    /**
     * ファイルに当たる、この索引の文書ID（索引に無いファイルは、パス文字列そのままの ID）。検査の報告先の判定は文書ID の一致で行うため、
     * 呼び出し側の Path の表記（大文字・小文字など）が索引と違っても、索引の側の ID に揃える。
     */
    fun documentIdOf(file: java.nio.file.Path): model.DocumentId =
        documentIdsByFile[file] ?: model.DocumentId(file.toString())

    private val documentIdsByFile: Map<java.nio.file.Path, model.DocumentId> by lazy {
        scans.associate { java.nio.file.Path.of(it.document.value) to it.document }
    }

    /**
     * グラフ整合（宣言のない関係名）のワークスペース全体の指摘。文書ごとの検証が、そのたびに全参照を調べ直さないように、
     * 宣言の内容ごとに1回だけ求める（この一式は走査結果が変わると作り直されるため、無効化は要らない。BACKLOG UX-116）。
     */
    fun integrityIssues(declarations: semantic.SemanticDeclarations): Map<model.DocumentId, List<semantic.SemanticIssue>> =
        synchronized(integrityCache) {
            integrityCache.getOrPut(declarations) {
                semantic.SemanticGraphIntegrity.issues(refs, graph, declarations).groupBy { it.document }
            }
        }

    private val integrityCache = HashMap<semantic.SemanticDeclarations, Map<model.DocumentId, List<semantic.SemanticIssue>>>()
}

private fun aggregateEntries(scans: List<DocumentScan>): Map<String, List<ScannedId>> {
    val entries = linkedMapOf<String, MutableList<ScannedId>>()
    scans.forEach { scan -> scan.ids.forEach { entries.getOrPut(it.id) { mutableListOf() }.add(it) } }
    return entries
}

/** DocumentScan 列からカーネルのエンティティグラフを導出する。 */
fun kernelGraphOf(scans: List<DocumentScan>): SemanticEntityGraph =
    SemanticEntityGraph.build(scans, aggregateEntries(scans))

/** この索引の走査結果から導出したカーネルのエンティティグラフ（索引につき1回だけ構築する toKernelView のもの）。 */
fun IdIndex.toKernelGraph(): SemanticEntityGraph = toKernelView().graph

/** この索引の走査結果からカーネル入力一式を作る（索引につき1回だけ構築し、走査結果が変わるまで使い回す）。 */
fun IdIndex.toKernelView(): KernelIndexView = cachedKernelView {
    val scans = toDocumentScans()
    val entries = aggregateEntries(scans)
    KernelIndexView(scans, entries, scans.flatMap { it.refs }, SemanticEntityGraph.build(scans, entries))
}

/**
 * セッション派生の走査結果から IdIndex ファサードを構築する（A-1 後半・RA-02）。
 * セッション座標（DocumentId＝ワークスペース相対パス）を UI 座標（絶対 Path）へ写し、
 * 集約（entries / refs / graph）は IdIndex 自身の derive に任せる＝並び・グラフ導出は従来と同一。
 * `state.overlayIndex()` の供給元がこれに切り替わり、UI の「見たまま判定」はカーネル導出になる（UX-03）。
 */
fun derivedIdIndex(scans: List<DocumentScan>, workspace: Path): IdIndex =
    IdIndex().also { it.populateFromScans(scans, workspace) }

/** 既存の IdIndex インスタンスへセッション派生の走査結果を投入する（B-2・RB-07: `Workspace.index` の供給切替用）。 */
fun IdIndex.populateFromScans(scans: List<DocumentScan>, workspace: Path) {
    populate(
        scans.map { scan ->
            val file = workspace.resolve(scan.document.value).normalize()
            IdIndex.PopulatedScan(
                file = file,
                documentId = scan.documentId,
                ids = scan.ids.map { IdEntry(it.id, file, it.path, it.title, it.schemaId, it.documentId, it.entityType, it.role) },
                refs = scan.refs.map {
                    RefEntry(
                        it.targetId, file, it.path, it.viaKey, it.idPatterns, it.scope,
                        it.relation, it.sourceSchemaId, it.attributes, it.targetKind,
                    )
                },
            )
        }
    )
}
