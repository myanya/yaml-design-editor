package dev.toros.yde.app.core

/**
 * リファクタリングの影響の解決の、アプリ側の窓口。実体はカーネルの workspace.RefactoringImpact（P27 段D の D-c・意図的差分 D-36）で、
 * セッションの planRefactoring が計画に含める——アプリ（AppState）は、もう計画を補強しない。ここは、ディスクを映した
 * ワークスペース（Workspace）から、同じ解決を直接呼ぶための入口（テストと、計画を介さずに警告だけが要る場面用）。
 */
object RefactorImpact {

    /** フィールド一括改名（R25-01）のビュー追随＋言及警告。解決できない場合は追随なし（警告のみ）。 */
    fun fieldRename(ws: Workspace, schemaId: String, fieldPath: String, newName: String): dev.toros.yde.workspace.RefactoringImpact.Impact =
        dev.toros.yde.workspace.RefactoringImpact.fieldRename(ws.mirror, schemaId, fieldPath, newName)

    /** フィールド一括削除（R25-03）の言及警告（ビュー参照が壊れる旨＋宣言節・定義文書）。 */
    fun fieldDeleteWarnings(ws: Workspace, fieldPath: String): List<String> =
        dev.toros.yde.workspace.RefactoringImpact.fieldDeleteWarnings(ws.mirror, fieldPath)

    /** 文書移動・改名（R21-03）でビュー定義の対象から外れる警告。 */
    fun moveWarnings(ws: Workspace, oldRel: String, newRel: String): List<String> =
        dev.toros.yde.workspace.RefactoringImpact.moveWarnings(ws.mirror, oldRel, newRel)
}
