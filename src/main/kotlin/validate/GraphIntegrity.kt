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
 */
object GraphIntegrity {

    /**
     * このファイルに座標を持つグラフ整合の指摘を返す。
     * レジストリが空なら何もしない（宣言のないワークスペースは挙動不変）。
     * allSchemas を渡すと、被参照不足（inMin）の指摘に「参照の追加先候補」の文書を添える（UX-100）。
     */
    fun issues(
        file: Path,
        indexObj: IdIndex,
        relations: Map<String, RelationDef>,
        enforcement: EnforcementDef = EnforcementDef(),
        allSchemas: List<schema.SchemaDef> = emptyList(),
    ): List<Issue> {
        if (relations.isEmpty() && enforcement.undeclaredRelation == null) return emptyList()

        // IdIndex の走査結果をカーネル座標へ写して委譲する（変換は index/SemanticBridge.kt に一元化）
        val scans = indexObj.toDocumentScans()
        val graph = kernelGraphOf(scans)
        val allRefs = scans.flatMap { it.refs }

        val target = DocumentId(file.toString())
        return SemanticGraphIntegrity.issues(allRefs, graph, SemanticDeclarations(relations = relations, enforcement = enforcement))
            .filter { it.document == target }
            .map { issue ->
                val open = issue.shortage
                    ?.let { referenceCandidates(indexObj, relations, allSchemas, it) }
                    .orEmpty()
                Issue(issue.severity, issue.message, issue.path, openFiles = open)
            }
    }

    /**
     * 被参照不足（inMin）の「参照の追加先候補」（UX-100）: 不足を解消する参照を書けそうな文書を導出する。
     * ① 関係の source 型を entity 宣言するスキーマ（追加先スキーマ）を特定する
     * ② 対象IDの定義ファイルに出現するエンティティが追加先スキーマの文書にも出現していれば、その文書
     *    （同一IDの詳細化パターン: 画面定義の項目 → 同じ画面IDの画面設計書。先頭3件）
     * ③ 見つからなければ、**台帳型**の追加先スキーマ（対象IDのファイルと同じエンティティ型を宣言しない
     *    ＝文書が対象ごとに分かれない）の文書がワークスペースに1つだけのとき、その文書
     *    （一覧・台帳パターン: 業務型 → 物理型マッピング表）。同じ型を宣言するスキーマ（同一IDの
     *    詳細化文書が対象ごとにある形）は、該当文書の未作成を別の文書で肩代わりさせないため対象外
     * どちらでもなければ黙る（複数テーブルのどれに実装するか等、機械が選べない場合は誤誘導しない）。
     */
    private fun referenceCandidates(
        indexObj: IdIndex,
        relations: Map<String, RelationDef>,
        allSchemas: List<schema.SchemaDef>,
        shortage: semantic.SemanticShortage,
    ): List<Path> {
        val def = relations[shortage.relation] ?: return emptyList()
        if (def.source.isEmpty() || allSchemas.isEmpty()) return emptyList()
        val sourceSchemaIds = allSchemas
            .filter { s -> s.entities.any { it.type in def.source } }
            .map { it.id }
            .toSet()
        if (sourceSchemaIds.isEmpty()) return emptyList()
        val graph = indexObj.graph
        val targetFile = indexObj.resolve(shortage.targetId)?.file ?: return emptyList()
        val siblingIds = graph.documents.values.firstOrNull { it.file == targetFile }?.entityIds.orEmpty()
        val cooccurring = siblingIds.asSequence()
            .flatMap { indexObj.occurrences(it) }
            .filter { it.schemaId in sourceSchemaIds && it.file != targetFile }
            .map { it.file }
            .distinct()
            .sortedBy { it.toString() }
            .take(3)
            .toList()
        if (cooccurring.isNotEmpty()) return cooccurring
        val siblingTypes = siblingIds.mapNotNull { graph.entities[it]?.entityType }.toSet()
        val registrySchemaIds = allSchemas
            .filter { s -> s.id in sourceSchemaIds && s.entities.none { it.type in siblingTypes } }
            .map { it.id }
            .toSet()
        if (registrySchemaIds.isEmpty()) return emptyList()
        val schemaFiles = graph.documents.values.asSequence()
            .filter { doc ->
                doc.file != targetFile && doc.entityIds.any { id ->
                    graph.entities[id]?.occurrences.orEmpty().any { it.file == doc.file && it.schemaId in registrySchemaIds }
                }
            }
            .map { it.file }
            .distinct()
            .toList()
        return if (schemaFiles.size == 1) schemaFiles else emptyList()
    }
}
