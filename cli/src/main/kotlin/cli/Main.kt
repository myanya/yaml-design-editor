package cli

import model.DocumentId
import yaml.DefaultYamlCodec
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * 3層コアの簡易CLI（Phase 7・WS-003 → Phase 10 で完成）。
 * dispatcher（引数解釈と標準入出力の配線）だけを持ち、処理本体（Commands.kt / Shell.kt）は
 * 3層コアのAPIを呼んで表示形式へ写すだけ——業務ロジックはCLIに置かない（計画書 Phase 10）。
 *
 * exit code（計画書 Phase 10 で固定）:
 *   0 = 成功（検証エラーなし。警告は exit code に影響しない）
 *   1 = コマンドまたは入力エラー
 *   2 = structural validation error（スキーマ・文書のパース失敗を含む）
 *   3 = semantic validation error
 *   4 = 古い baseVersion の拒否
 *   5 = 保存失敗
 */
fun main(args: Array<String>) {
    exitProcess(run(args) { println(it) })
}

/** テスト可能な本体。出力は out へ、結果は exit code で返す。 */
fun run(args: Array<String>, out: (String) -> Unit): Int = try {
    when {
        args.size >= 2 && args[0] == "yaml" && args[1] == "parse" -> yamlParse(args.getOrNull(2), out)
        args.size >= 2 && args[0] == "yaml" && args[1] == "roundtrip" -> yamlRoundtrip(args.getOrNull(2), out)
        args.size >= 2 && args[0] == "workspace" && args[1] == "validate" ->
            workspaceValidate(args.getOrNull(2), args.drop(3), out)
        args.size >= 2 && args[0] == "workspace" && args[1] == "catalog" ->
            workspaceCatalog(args.getOrNull(2), args.drop(3), out)
        args.size >= 2 && args[0] == "workspace" && args[1] == "entity" ->
            workspaceEntity(args.getOrNull(2), args.getOrNull(3), args.drop(4), out)
        args.size >= 2 && args[0] == "workspace" && args[1] == "refactor" ->
            workspaceRefactor(args.getOrNull(2), args.drop(3), out)
        // rename-entity は refactor rename-entity の別名（WS-003 からの互換）
        args.size >= 2 && args[0] == "workspace" && args[1] == "rename-entity" ->
            workspaceRefactor(args.getOrNull(2), listOf("rename-entity") + args.drop(3), out)
        args.size >= 2 && args[0] == "workspace" && args[1] == "bench" ->
            workspaceBench(args.getOrNull(2), args.drop(3), out)
        args.size >= 2 && args[0] == "workspace" && args[1] == "shell" ->
            workspaceShell(args.getOrNull(2), { readlnOrNull() }, out)
        else -> {
            out("使い方:")
            out("  yaml parse <file>                     YAMLをパースしてドキュメント数を表示する")
            out("  yaml roundtrip <file>                 parse → dump → parse の往復一致を確認する")
            out("  workspace validate <root>             構造・意味検証の結果を表示する")
            out("  workspace catalog <root>              スキーマと文書の一覧・割当を表示する")
            out("  workspace entity <root> <id>          エンティティの出現・被参照・出参照を表示する")
            out("  workspace refactor <root> <op> … [--apply]")
            out("                                        リファクタリング計画を表示し、--apply で適用・保存する")
            out("                                        （op: rename-entity / merge-entity / delete-entity / move-document /")
            out("                                         rename-field / move-field / align-order / rename-enum-value /")
            out("                                         delete-field / require-field / convert-type / rename-definition /")
            out("                                         extract-definition / inline-definition / rename-relation / rename-schema-id）")
            out("  workspace bench <root> [--iterations N]")
            out("                                        意味解析の全再構築と増分更新（1文書）を計測する")
            out("  workspace shell <root>                対話 shell（set / refactor / undo / redo / save / reload）")
            out("  共通: workspace 系は --format text|yaml|json で機械可読出力を選べる（shell を除く）")
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
