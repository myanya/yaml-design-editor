package cli

import edit.YamlOperation
import model.DocumentId
import model.YNode
import model.parseYPath
import model.pretty
import validate.Severity
import workspace.ChangeIntent
import workspace.FileSystemDocumentStore
import workspace.WorkspaceOperation
import workspace.WorkspaceSession
import workspace.WorkspaceTransaction
import yaml.YamlIO

/**
 * 対話 shell（Phase 10 完了条件: apply / undo / redo / save / reload を確認できる）。
 * 1つの WorkspaceSession を保持し、行コマンドを core API へ写すだけ（業務ロジックなし）。
 * readLine を注入して JUnit から台本でテストできる（Main.kt が標準入力を配線する）。
 */
fun workspaceShell(root: String?, readLine: () -> String?, out: (String) -> Unit): Int {
    val dir = requireDir(root)
    val session = WorkspaceSession(FileSystemDocumentStore(dir))
    out("ワークスペース: $dir（文書 ${session.snapshot.documents.size} 件・version ${session.version}）")
    out("コマンド: status / validate / set <file> <path> <値> / refactor <op> … [--apply] / undo / redo / save / reload / help / exit")
    while (true) {
        val line = readLine() ?: return 0
        val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) continue
        try {
            when (tokens[0]) {
                "exit", "quit" -> return 0
                "help" -> {
                    out("status                     version・dirty・undo/redo可否・外部変更を表示する")
                    out("validate                   working 状態の構造・意味検証を表示する")
                    out("set <file> <path> <値>     スカラー値を書き換える（1トランザクション＝undo 1回分）")
                    out("refactor <op> … [--apply]  リファクタリング計画の表示・適用（保存は save で行う）")
                    out("undo / redo                変更を1手戻す・やり直す")
                    out("save                       dirty な文書と削除予約をストアへ書く")
                    out("reload                     ストアから読み直す（外部変更の取り込み。履歴は破棄）")
                    out("exit                       終了する")
                }
                "status" -> {
                    out("version: ${session.version}")
                    val dirty = session.dirtyDocuments()
                    out("dirty: " + if (dirty.isEmpty()) "なし" else dirty.joinToString(", ") { it.value })
                    out("undo可: ${session.canUndo()} / redo可: ${session.canRedo()}")
                    val external = session.externalChanges()
                    if (external.isNotEmpty()) {
                        out("外部変更あり: ${external.joinToString(", ") { it.value }}（reload で取り込み）")
                    }
                }
                "validate" -> {
                    var structuralErrors = 0
                    var semanticErrors = 0
                    session.schemaErrors.forEach { out("スキーマエラー: $it") }
                    session.derived.structuralIssues.forEach { (id, issues) ->
                        issues.forEach { issue ->
                            if (issue.severity == Severity.ERROR) structuralErrors++
                            out("  [構造:${issue.severity}] ${id.value} ${issue.path.pretty()}: ${issue.message}")
                        }
                    }
                    session.derived.semantic.issues.forEach { issue ->
                        if (issue.severity == Severity.ERROR) semanticErrors++
                        out("  [意味:${issue.severity}] ${issue.document.value} ${issue.path.pretty()}: ${issue.message}")
                    }
                    out("構造検証: エラー $structuralErrors 件 / 意味検証: エラー $semanticErrors 件")
                }
                "set" -> {
                    val file = tokens.getOrNull(1) ?: error("使い方: set <file> <path> <値>")
                    val path = tokens.getOrNull(2) ?: error("使い方: set <file> <path> <値>")
                    val valueText = tokens.drop(3).joinToString(" ").ifEmpty { error("使い方: set <file> <path> <値>") }
                    val value = (YamlIO.parse(valueText) as? YNode.Scalar)?.value
                        ?: error("スカラー値を指定してください: $valueText")
                    val result = session.apply(
                        WorkspaceTransaction(
                            baseVersion = session.version,
                            intent = ChangeIntent("set $file $path"),
                            operations = listOf(
                                WorkspaceOperation.EditDocument(
                                    DocumentId(file),
                                    listOf(YamlOperation.SetScalar(parseYPath(path), value)),
                                )
                            ),
                        )
                    )
                    out(if (result.ok) "OK（version ${result.version}）" else "適用できません: ${result.message}")
                }
                "refactor" -> {
                    val params = tokens.drop(1)
                    val plan = session.planRefactoring(refactorRequest(params))
                    plan.summary.forEach(out)
                    plan.warnings.forEach { out("注意: $it") }
                    plan.moves.forEach { (from, to) -> out("  移動: ${from.value} → ${to.value}") }
                    plan.changes.forEach { out("  ${it.document.value}: ${it.operations.size} 操作") }
                    if ("--apply" in params) {
                        val result = session.applyRefactoring(plan)
                        out(
                            if (result.ok) "適用しました（version ${result.version}。保存は save で行います）"
                            else "適用できません: ${result.message}"
                        )
                    } else {
                        out("（プレビューのみ。適用するには --apply を付けてください）")
                    }
                }
                "undo" -> {
                    val r = session.undo()
                    out(if (r.ok) "戻しました（version ${r.version}）" else (r.message ?: "戻せません"))
                }
                "redo" -> {
                    val r = session.redo()
                    out(if (r.ok) "やり直しました（version ${r.version}）" else (r.message ?: "やり直せません"))
                }
                "save" -> {
                    val r = session.save()
                    out(
                        if (r.ok) "保存しました（書込 ${r.written.size} 件・削除 ${r.deleted.size} 件）"
                        else "保存に失敗しました: ${r.failures}"
                    )
                }
                "reload" -> {
                    session.reload()
                    out("読み直しました（version ${session.version}。履歴は破棄されます）")
                }
                else -> out("不明なコマンドです: ${tokens[0]}（help で一覧）")
            }
        } catch (e: Exception) {
            out("エラー: ${e.message}")
        }
    }
}
