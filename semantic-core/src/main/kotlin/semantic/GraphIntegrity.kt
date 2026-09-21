package semantic

import schema.CoverageLevel
import schema.RefTarget
import validate.Severity

/**
 * グラフ整合検証のカーネル（R21-01。3層コア移行 Phase 6A で validate/GraphIntegrity.kt から移動）。
 * 強制スイッチ（enforcement）の宣言のない関係名を判定し、全文書分の指摘を文書座標つきで返す
 * （データではなく宣言そのものの検査のため、定義体にせずコードに残す。DL-04）。
 * 対象型と多重度は組込み定義体が作る検査に（P29 Phase 6 第2段）、非循環は専用チェッカー SemanticCycles を
 * 組込み定義体から使う検査になった（第4段）。
 * ルート側 GraphIntegrity は IdIndex → カーネル座標の変換シェル（ファイル単位の絞り込みのみ）。
 * - 判定保留の哲学（§4.26）: 型未宣言のエンティティ・未解決参照には黙る（未解決は参照解決の領分）。
 */
object SemanticGraphIntegrity {

    private fun severityOf(level: CoverageLevel): Severity =
        level.toSeverity()

    /**
     * グラフ整合の指摘（全文書分）。レジストリが空なら何もしない（宣言のないワークスペースは挙動不変）。
     */
    fun issues(
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        declarations: SemanticDeclarations,
    ): List<SemanticIssue> {
        val relations = declarations.relations
        val enforcement = declarations.enforcement
        if (enforcement.undeclaredRelation == null) return emptyList()
        val issues = mutableListOf<SemanticIssue>()

        // 1) 宣言のない関係名（enforcement.undeclaredRelation）: 参照値の座標へ指摘する。
        //    データではなく宣言そのものの検査のため、定義体にせずここに残す（P29 Phase 6・DL-04）
        enforcement.undeclaredRelation?.let { level ->
            for (ref in refs) {
                if (ref.targetKind != RefTarget.ENTITY) continue  // 文書参照（R21-03）は別名前空間
                val name = ref.relation ?: continue
                if (name in relations) continue
                issues += SemanticIssue(
                    severityOf(level),
                    "参照「${ref.targetId}」の関係「$name」は関係レジストリに宣言されていません",
                    ref.document, ref.path,
                    SemanticIssueKind.UNDECLARED_RELATION, ref.targetId,
                )
            }
        }

        // 2) 多重度と対象型は、組込み定義体（builtin-definitions/relations.yaml）が作る検査になった
        //    （P29 Phase 6 第2段。クエリ＋参照の本数＋値の比較／ノードの条件の否定＋参照の場所への指摘）

        // 3) 非循環（acyclic）は、専用チェッカー（SemanticCycles）を組込み定義体から使う検査になった
        //    （P29 Phase 6 第4段。builtin-definitions/relations.yaml の語彙 relation-acyclic）

        return issues
    }
}
