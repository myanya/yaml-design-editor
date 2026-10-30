package dev.toros.yde.cli

import dev.toros.yde.app.ext.Extensions
import dev.toros.yde.yaml.DefaultYamlCodec
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * 1 つの CLI（ADR-0013。R47-41）。入口はここだけで、引数の解釈と標準入出力の配線を持つ。
 * - カーネルの命令（`yaml …` / `workspace …`。3 層コアだけで動き、--format text|yaml|json）: Commands.kt / Shell.kt
 * - 応用の命令（catalog・view・validate・agent・import …。app-core と agent を使い、出力は YAML）: AppCli.kt と commands/
 * 業務ロジックは置かない——引数を窓口（Workspace・ActionPlanner・AgentKernel …）の入力へ写し、結果を表示形式へ写すだけ。
 *
 * exit code（カーネルの命令。計画書 Phase 10 で固定）: 0 成功（警告は影響しない）/ 1 コマンドまたは入力エラー /
 * 2 構造の検証エラー（スキーマ・文書のパース失敗を含む）/ 3 意味の検証エラー / 4 古い baseVersion の拒否 / 5 保存失敗。
 * 応用の命令は 0 成功 / 1 誤り（標準エラー出力に「エラー: …」）。
 */
fun main(args: Array<String>) {
    Extensions.install(CliExtensions.registry)  // 拡張（Excel・Word の形式）の登録は入口の一覧 1 ファイル（app-core に POI を入れない。組込みは最初から入っている）
    // プラットフォームの既定文字コードに依存せず、常に UTF-8 で出力する（機械可読性のため）
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, Charsets.UTF_8))
    System.setErr(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true, Charsets.UTF_8))
    if (isKernelCommand(args)) exitProcess(run(args) { println(it) })
    try {
        print(runCli(args))
    } catch (e: Exception) {
        System.err.println("エラー: ${e.message ?: e.toString()}")
        exitProcess(1)
    }
}

/** カーネルの命令（`yaml …` / `workspace …`）か。それ以外は応用の命令（runCli）。 */
internal fun isKernelCommand(args: Array<String>): Boolean = args.isNotEmpty() && (args[0] == "yaml" || args[0] == "workspace")

/** カーネルの命令の本体（テスト可能）。出力は out へ、結果は exit code で返す。応用の命令は runCli（AppCli.kt）。 */
fun run(args: Array<String>, out: (String) -> Unit): Int = try {
    when {
        args.size >= 2 && args[0] == "yaml" && args[1] == "parse" -> yamlParse(args.getOrNull(2), out)
        args.size >= 2 && args[0] == "yaml" && args[1] == "roundtrip" -> yamlRoundtrip(args.getOrNull(2), out)
        args.size >= 2 && args[0] == "workspace" && args[1] == "validate" ->
            workspaceValidate(args.getOrNull(2), args.drop(3), out)
        args.size >= 2 && args[0] == "workspace" && args[1] == "apply" ->
            workspaceApply(args.getOrNull(2), args.drop(3), out)
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
            out("使い方（カーネルの命令。ほかの命令は help を参照: ./gradlew runCli --args=\"help\"）:")
            out("  yaml parse <file>                     YAMLをパースしてドキュメント数を表示する")
            out("  yaml roundtrip <file>                 parse → dump → parse の往復一致を確認する")
            out("  workspace validate <root>             検証の結果（スキーマの定義・構造・意味・検査）を表示する")
            out("  workspace apply <root> <変更セット.yaml> [--dry-run] [--require-valid]")
            out("                                        変更セットを適用する（競合の判定 → 適用後の姿の検証 → 保存）")
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
