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
        /** この文書に書かれた ID（指摘の出どころの「対象の要素」を決めるのに使う）。 */
        ids: List<semantic.ScannedId> = emptyList(),
    ): List<Issue> {
        if (enforcement.undeclaredRelation == null) return emptyList()

        // IdIndex の走査結果をカーネル座標へ写して委譲する（変換は index/SemanticBridge.kt に一元化）。
        // ワークスペース全体の指摘は索引につき1回だけ求め、この文書の分を取り出す（文書ごとにグラフと全参照を調べ直さない）
        val declarations = SemanticDeclarations(relations = relations, enforcement = enforcement)
        return indexObj.toKernelView().integrityIssues(declarations)[DocumentId(file.toString())].orEmpty()
            .map { issue -> Issue(issue.severity, issue.message, issue.path, source = CodedChecks.semantic(issue, ids)) }
    }
}
