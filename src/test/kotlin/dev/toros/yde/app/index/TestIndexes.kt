package dev.toros.yde.app.index

import dev.toros.yde.semantic.DocumentScan
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.app.schema.SchemaDef
import java.nio.file.Path

/**
 * テスト用の索引フィクスチャ（RB-09: IdIndex.rebuild のディスク走査経路削除に伴う置換）。
 * 走査は DocScanner（カーネル委譲シェル）で行い、populate で投入する——本体の供給経路
 * （セッション派生）と同じ走査カーネルを通るため、判定テストの前提は変わらない。
 */
fun testIndex(
    workspace: Path,
    files: List<Path>,
    schemas: List<SchemaDef>,
    layout: WorkspaceLayout = WorkspaceLayout.LEGACY,
    loader: (Path) -> YNode?,
): IdIndex {
    val idx = IdIndex()
    idx.populate(
        files.mapNotNull { f ->
            val rel = IdIndex.relPathOf(workspace, f)
            // 旧 IdIndex.scanFile と同じ対象判定（索引対象外・台帳外の除外）
            if (layout.indexExcluded(rel)) return@mapNotNull null
            if (!layout.inLedger(rel) && layout.zoneOf(rel) == null) return@mapNotNull null
            val root = loader(f) ?: return@mapNotNull null
            val schemaDef = schemas.firstOrNull { it.matches(rel) }
            val documentId = IdIndex.documentIdOf(root, rel)
            // 場所は、文書ID（ワークスペース相対パス）で表す（本体の供給経路＝セッション派生と同じ座標）
            val document = DocumentId(rel)
            val (ids, refs) = DocScanner.scan(root, document, schemaDef, documentId)
            DocumentScan(document, documentId, ids, refs)
        }
    )
    return idx
}
