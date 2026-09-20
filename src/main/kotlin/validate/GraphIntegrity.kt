package validate

import index.IdIndex
import index.toKernelView
import index.RefEntry
import model.DocumentId
import schema.EnforcementDef
import schema.RelationDef
import semantic.SemanticDeclarations
import java.nio.file.Path

/**
 * グラフ整合検証（R21-01。ARCHITECTURE §4.31）のディスク索引向けシェル。
 * 判定アルゴリズムの本体は semantic-core の SemanticGraphIntegrity（3層コア移行 Phase 6A）で、
 * ここは IdIndex の走査結果をカーネル座標（DocumentId ＝ パス文字列）へ写して委譲し、
 * per-file の検証から「このファイルに座標を持つ指摘」だけを返す。
 * 扱うのは宣言のない関係名（enforcement）だけ。多重度と対象型は組込み定義体（builtin-definitions/relations.yaml）が
 * 作る検査に（P29 Phase 6 第2段）、非循環は専用チェッカーを組込み定義体から使う検査になった（第4段）。
 */
object GraphIntegrity {

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
        if (enforcement.undeclaredRelation == null) return emptyList()

        // IdIndex の走査結果をカーネル座標へ写して委譲する（変換は index/SemanticBridge.kt に一元化）。
        // ワークスペース全体の指摘は索引につき1回だけ求め、この文書の分を取り出す（文書ごとにグラフと全参照を調べ直さない）
        val declarations = SemanticDeclarations(relations = relations, enforcement = enforcement)
        return indexObj.toKernelView().integrityIssues(declarations)[DocumentId(file.toString())].orEmpty()
            .map { issue -> Issue(issue.severity, issue.message, issue.path) }
    }
}

/**
 * エラー時の対応「参照を書き足せそうな文書へ移動」（responses の jump: referenceSources。SP-09・UX-100）の解決。
 * 被参照が足りない要素について、その関係の参照を書けそうな文書を導出する。
 */
object ReferenceSources {

    /**
     * 「参照の追加先候補」（UX-100）: targetId への relation の参照を書けそうな文書を導出する。
     * ① 関係の source 型を entity 宣言するスキーマ（追加先スキーマ）を特定する
     * ② 対象IDの定義ファイルに出現するエンティティが追加先スキーマの文書にも出現していれば、その文書
     *    （同一IDの詳細化パターン: 画面定義の項目 → 同じ画面IDの画面設計書。先頭3件）
     * ③ 見つからなければ、**台帳型**の追加先スキーマ（対象IDのファイルと同じエンティティ型を宣言しない
     *    ＝文書が対象ごとに分かれない）の文書がワークスペースに1つだけのとき、その文書
     *    （一覧・台帳パターン: 業務型 → 物理型マッピング表）。同じ型を宣言するスキーマ（同一IDの
     *    詳細化文書が対象ごとにある形）は、該当文書の未作成を別の文書で肩代わりさせないため対象外
     * どちらでもなければ黙る（複数テーブルのどれに実装するか等、機械が選べない場合は誤誘導しない）。
     */
    fun candidates(
        indexObj: IdIndex,
        relations: Map<String, RelationDef>,
        allSchemas: List<schema.SchemaDef>,
        relation: String,
        targetId: String,
    ): List<Path> {
        val def = relations[relation] ?: return emptyList()
        if (def.source.isEmpty() || allSchemas.isEmpty()) return emptyList()
        val sourceSchemaIds = allSchemas
            .filter { s -> s.entities.any { it.type in def.source } }
            .map { it.id }
            .toSet()
        if (sourceSchemaIds.isEmpty()) return emptyList()
        val graph = indexObj.graph
        val targetFile = indexObj.resolve(targetId)?.file ?: return emptyList()
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
