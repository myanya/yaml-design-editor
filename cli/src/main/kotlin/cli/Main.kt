package cli

import model.DocumentId
import model.YPath
import model.pretty
import validate.Severity
import workspace.FileSystemDocumentStore
import workspace.WorkspaceReader
import yaml.DefaultYamlCodec
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * 3層コアの簡易CLI（Phase 7・WS-003＝Phase 10 の最小分。計画書§6.4）。
 * dispatcher（引数解釈と表示）だけを持ち、処理本体は3層コアのAPIを呼ぶ。
 *
 * exit code（計画書 Phase 10 で固定）:
 *   0 = 成功（検証エラーなし。警告は exit code に影響しない）
 *   1 = コマンドまたは入力エラー
 *   2 = structural validation error（スキーマ・文書のパース失敗を含む）
 *   3 = semantic validation error
 */
fun main(args: Array<String>) {
    exitProcess(run(args) { println(it) })
}

/** テスト可能な本体。出力は out へ、結果は exit code で返す。 */
fun run(args: Array<String>, out: (String) -> Unit): Int = try {
    when {
        args.size >= 2 && args[0] == "yaml" && args[1] == "parse" -> yamlParse(args.getOrNull(2), out)
        args.size >= 2 && args[0] == "yaml" && args[1] == "roundtrip" -> yamlRoundtrip(args.getOrNull(2), out)
        args.size >= 2 && args[0] == "workspace" && args[1] == "validate" -> workspaceValidate(args.getOrNull(2), out)
        args.size >= 2 && args[0] == "workspace" && args[1] == "rename-entity" ->
            renameEntity(args.getOrNull(2), args.getOrNull(3), args.getOrNull(4), "--apply" in args, out)
        else -> {
            out("使い方:")
            out("  yaml parse <file>            YAMLをパースしてドキュメント数を表示する")
            out("  yaml roundtrip <file>        parse → dump → parse の往復一致を確認する")
            out("  workspace validate <root>    ワークスペースを読み取り、構造・意味検証の結果を表示する")
            out("  workspace rename-entity <root> <old> <new> [--apply]")
            out("                               エンティティ改名の計画を表示し、--apply で適用・保存する")
            1
        }
    }
} catch (e: Exception) {
    out("エラー: ${e.message}")
    1
}

private fun yamlParse(file: String?, out: (String) -> Unit): Int {
    val path = Path.of(file ?: error("ファイルを指定してください"))
    val text = Files.readString(path)
    val doc = DefaultYamlCodec.parse(DocumentId(path.fileName.toString()), text)
    out("OK: ${doc.documents.size} ドキュメント")
    return 0
}

private fun yamlRoundtrip(file: String?, out: (String) -> Unit): Int {
    val path = Path.of(file ?: error("ファイルを指定してください"))
    val text = Files.readString(path)
    val docs = YamlIO.parseAll(text)
    val dumped = YamlIO.dumpAll(docs)
    val reparsed = YamlIO.parseAll(dumped)
    val same = docs.size == reparsed.size &&
        docs.zip(reparsed).all { (a, b) -> YamlIO.dumpPlain(a) == YamlIO.dumpPlain(b) }
    return if (same) {
        out("OK: round-trip一致（${docs.size} ドキュメント）")
        0
    } else {
        out("NG: round-tripで内容が変わりました")
        1
    }
}

private fun workspaceValidate(root: String?, out: (String) -> Unit): Int {
    val dir = Path.of(root ?: error("ワークスペースのルートを指定してください"))
    require(Files.isDirectory(dir)) { "ディレクトリではありません: $dir" }
    val ws = WorkspaceReader.load(FileSystemDocumentStore(dir))

    out("スキーマ: ${ws.schemas.size} 件（${ws.schemas.joinToString(", ") { it.id }}）")
    out("文書: ${ws.snapshot.documents.size} 件（台帳内 ${ws.snapshot.documents.values.count { it.inLedger }} 件）")
    ws.schemaErrors.forEach { out("スキーマエラー: $it") }
    ws.parseErrors.forEach { (id, message) -> out("パースエラー: ${id.value}: $message") }

    var structuralErrors = 0
    var structuralWarnings = 0
    for ((id, issues) in ws.derived.structuralIssues) {
        for (issue in issues) {
            when (issue.severity) {
                Severity.ERROR -> structuralErrors++
                Severity.WARNING -> structuralWarnings++
                Severity.INFO -> Unit
            }
            out("  [構造:${issue.severity}] ${id.value} ${pathText(issue.path)}: ${issue.message}")
        }
    }
    var semanticErrors = 0
    var semanticWarnings = 0
    for (issue in ws.derived.semantic.issues) {
        when (issue.severity) {
            Severity.ERROR -> semanticErrors++
            Severity.WARNING -> semanticWarnings++
            Severity.INFO -> Unit
        }
        out("  [意味:${issue.severity}] ${issue.document.value} ${pathText(issue.path)}: ${issue.message}")
    }
    out("構造検証: エラー $structuralErrors 件 / 警告 $structuralWarnings 件")
    out("意味検証: エラー $semanticErrors 件 / 警告 $semanticWarnings 件")

    return when {
        ws.schemaErrors.isNotEmpty() || ws.parseErrors.isNotEmpty() || structuralErrors > 0 -> 2
        semanticErrors > 0 -> 3
        else -> 0
    }
}

private fun pathText(path: YPath): String = path.pretty()

/** エンティティ改名の plan / apply（Phase 9: plan は semantic-core・apply は WorkspaceSession）。 */
private fun renameEntity(root: String?, oldId: String?, newId: String?, apply: Boolean, out: (String) -> Unit): Int {
    val dir = Path.of(root ?: error("ワークスペースのルートを指定してください"))
    require(Files.isDirectory(dir)) { "ディレクトリではありません: $dir" }
    require(!oldId.isNullOrBlank() && !newId.isNullOrBlank()) { "改名元と改名先のIDを指定してください" }
    val session = workspace.WorkspaceSession(FileSystemDocumentStore(dir))
    val plan = session.planRefactoring(semantic.RefactoringRequest.RenameEntity(oldId, newId))
    plan.summary.forEach(out)
    plan.warnings.forEach { out("注意: $it") }
    plan.changes.forEach { change -> out("  ${change.document.value}: ${change.operations.size} 操作") }
    if (!apply) {
        out("（プレビューのみ。適用するには --apply を付けてください）")
        return 0
    }
    val result = session.applyRefactoring(plan)
    if (!result.ok) {
        out("適用できません: ${result.message}")
        return 1
    }
    val save = session.save()
    return if (save.ok) {
        out("適用して保存しました（${save.written.size} 文書）")
        0
    } else {
        out("保存に失敗しました: ${save.failures}")
        5
    }
}
