package index

import model.DocumentId
import semantic.DocumentScan
import semantic.ScannedId
import semantic.ScannedRef
import semantic.SemanticEntityGraph
import java.nio.file.Path

/*
 * IdIndex（Path 座標）と semantic-core カーネル（DocumentId 座標）の変換ブリッジ。
 * Phase 6A/6B のシェル（GraphIntegrity / Aspects）が共有する。DocumentId の値はパス文字列。
 * IdIndex が SemanticEngine の上へ再構成される Phase 7〜8 でこのブリッジごと解消する。
 */

fun IdEntry.toScanned(): ScannedId =
    ScannedId(id, DocumentId(file.toString()), path, title, schemaId, documentId, entityType, role)

fun RefEntry.toScanned(): ScannedRef =
    ScannedRef(targetId, DocumentId(file.toString()), path, viaKey, idPatterns, scope, relation, sourceSchemaId, attributes, targetKind)

fun ScannedId.toIdEntry(): IdEntry =
    IdEntry(id, Path.of(document.value), path, title, schemaId, documentId, entityType, role)

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

/** DocumentScan 列からカーネルのエンティティグラフを導出する。 */
fun kernelGraphOf(scans: List<DocumentScan>): SemanticEntityGraph {
    val entries = linkedMapOf<String, MutableList<ScannedId>>()
    scans.forEach { scan -> scan.ids.forEach { entries.getOrPut(it.id) { mutableListOf() }.add(it) } }
    return SemanticEntityGraph.build(scans, entries)
}

/** この索引の走査結果からカーネルのエンティティグラフを導出する（呼び出しごとに構築）。 */
fun IdIndex.toKernelGraph(): SemanticEntityGraph = kernelGraphOf(toDocumentScans())
