package validate

import index.IdIndex
import index.RefEntry
import index.kernelGraphOf
import index.toDocumentScans
import model.DocumentId
import schema.EnforcementDef
import schema.RelationDef
import semantic.SemanticDeclarations
import semantic.SemanticGraphIntegrity
import java.nio.file.Path

/**
 * グラフ整合検証（R21-01。ARCHITECTURE §4.31）のディスク索引向けシェル。
 * 判定アルゴリズムの本体は semantic-core の SemanticGraphIntegrity（3層コア移行 Phase 6A）で、
 * ここは IdIndex の走査結果をカーネル座標（DocumentId ＝ パス文字列）へ写して委譲し、
 * per-file の検証から「このファイルに座標を持つ指摘」だけを返す。
 * - 判定保留の哲学（§4.26）: 型未宣言のエンティティ・未解決参照には黙る（未解決は V-02 の領分）。
 * - 入次数・出現数のカウンタはカバレッジ（requiredBy / detailIn）と共有する（coverage は糖衣として存続）。
 */
object GraphIntegrity {

    /**
     * 指定IDへの参照（入次数エッジ）。relation / 参照元スキーマで絞り込む。
     * カバレッジ（requiredBy）と多重度（in）の共有カウンタ。
     */
    fun inboundRefs(
        indexObj: IdIndex,
        id: String,
        relation: String? = null,
        fromSchemaIds: List<String> = emptyList(),
    ): List<RefEntry> = indexObj.graph.backlinksTo(setOf(id)).filter { ref ->
        ref.targetKind == schema.RefTarget.ENTITY &&
            (relation == null || ref.relation == relation) &&
            (fromSchemaIds.isEmpty() || ref.sourceSchemaId in fromSchemaIds)
    }

    /** 指定IDの、指定スキーマ群の文書への出現数。カバレッジ（detailIn）の共有カウンタ。 */
    fun occurrenceCount(indexObj: IdIndex, id: String, schemaIds: List<String>): Int =
        indexObj.occurrences(id).count { it.schemaId in schemaIds }

    /**
     * このファイルに座標を持つグラフ整合の指摘を返す。
     * レジストリが空なら何もしない（宣言のないワークスペースは挙動不変）。
     */
    fun issues(
        file: Path,
        indexObj: IdIndex,
        relations: Map<String, RelationDef>,
        enforcement: EnforcementDef = EnforcementDef(),
    ): List<Issue> {
        if (relations.isEmpty() && enforcement.undeclaredRelation == null) return emptyList()

        // IdIndex の走査結果をカーネル座標へ写して委譲する（変換は index/SemanticBridge.kt に一元化）
        val scans = indexObj.toDocumentScans()
        val graph = kernelGraphOf(scans)
        val allRefs = scans.flatMap { it.refs }

        val target = DocumentId(file.toString())
        return SemanticGraphIntegrity.issues(allRefs, graph, SemanticDeclarations(relations = relations, enforcement = enforcement))
            .filter { it.document == target }
            .map { Issue(it.severity, it.message, it.path) }
    }
}
