package core

import model.parseYPath
import model.pretty
import validate.Severity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import yaml.YamlIO

/**
 * ヘッドレスCLI（R1-01）。UIを起動せずにワークスペースの探索・検索・パッチ・検証を行う。
 * 実行例: ./gradlew runCli --args="catalog sample-workspace"
 * 出力は原則 YAML（機械可読）。
 */
fun main(args: Array<String>) {
    // プラットフォームの既定文字コードに依存せず、常に UTF-8 で出力する（機械可読性のため）
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, Charsets.UTF_8))
    System.setErr(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true, Charsets.UTF_8))
    try {
        print(runCli(args))
    } catch (e: Exception) {
        System.err.println("エラー: ${e.message ?: e.toString()}")
        exitProcess(1)
    }
}

internal const val CLI_USAGE = """コマンド:
  catalog  <workspace>                          文書カタログ（_document 要約・スキーマ・ID数）
  outline  <workspace> <file>                   文書の骨子（キー構造・配列件数・要約）
  read     <workspace> <file> [path] [--max-chars N]   指定パス配下の全文（YAML）
  bundle   <workspace> <file> [path] [--budget N]      対象と関連情報（参照・被参照）の文脈バンドル
  search   <workspace> [--text q] [--schema id]... [--file glob] [--path glob]
                       [--ref-to id] [--unresolved] [--limit n]
  validate <workspace> [file]                   検証（スキーマ・参照・重複・カバレッジ）
  apply    <workspace> <changeset.yaml> [--dry-run] [--require-valid]   変更セットの適用
  hash     <workspace> <file>                   変更セットの base に使う内容ハッシュ
  help                                          このヘルプ
"""

internal fun runCli(args: Array<String>): String {
    if (args.isEmpty() || args[0] == "help" || args[0] == "--help") return CLI_USAGE
    val command = args[0]
    val rest = args.drop(1)
    fun workspace(): Workspace {
        val dir = rest.firstOrNull() ?: error("ワークスペースのパスを指定してください")
        return Workspace.open(Path.of(dir))
    }
    return when (command) {
        "catalog" -> {
            val ws = workspace()
            dumpYaml(Disclosure.catalog(ws).map { entry ->
                linkedMapOf<String, Any?>(
                    "file" to entry.file,
                    "documentId" to entry.documentId,
                    "name" to entry.name,
                    "description" to entry.description,
                    "schema" to entry.schemaId,
                    "ids" to entry.idCount,
                ).filterValues { it != null }
            })
        }
        "outline" -> {
            val ws = workspace()
            val rel = rest.getOrNull(1) ?: error("ファイルを指定してください")
            val doc = ws.load(rel)
            val root = doc.root ?: error("YAML パースエラー: ${doc.parseError}")
            Disclosure.outline(root) + "\n"
        }
        "read" -> {
            val ws = workspace()
            val rel = rest.getOrNull(1) ?: error("ファイルを指定してください")
            val pathArg = rest.getOrNull(2)?.takeIf { !it.startsWith("--") } ?: ""
            val maxChars = intFlag(rest, "--max-chars") ?: 20_000
            val doc = ws.load(rel)
            val root = doc.root ?: error("YAML パースエラー: ${doc.parseError}")
            Disclosure.nodeYaml(root, parseYPath(pathArg), maxChars)
        }
        "bundle" -> {
            val ws = workspace()
            val rel = rest.getOrNull(1) ?: error("ファイルを指定してください")
            val pathArg = rest.getOrNull(2)?.takeIf { !it.startsWith("--") } ?: ""
            val budget = intFlag(rest, "--budget") ?: 8_000
            Disclosure.bundle(ws, rel, parseYPath(pathArg), budget)
        }
        "search" -> {
            val ws = workspace()
            val query = SearchQuery(
                text = stringFlag(rest, "--text"),
                schemaIds = stringFlags(rest, "--schema"),
                fileGlob = stringFlag(rest, "--file"),
                pathGlob = stringFlag(rest, "--path"),
                refsTo = stringFlag(rest, "--ref-to"),
                unresolvedRefs = "--unresolved" in rest,
                limit = intFlag(rest, "--limit") ?: 50,
            )
            dumpYaml(Search.run(ws, query).map { hit ->
                linkedMapOf(
                    "file" to hit.file,
                    "path" to hit.path,
                    "kind" to hit.kind,
                    "excerpt" to hit.excerpt,
                )
            })
        }
        "validate" -> {
            val ws = workspace()
            val target = rest.getOrNull(1)?.takeIf { !it.startsWith("--") }
            val files = if (target != null) listOf(ws.resolve(target))
            else ws.files.filter { !ws.relPath(it).startsWith(schema.SchemaLoader.SCHEMA_DIR + "/") }
            var errors = 0
            var warnings = 0
            val fileResults = files.mapNotNull { file ->
                val issues = ws.validate(ws.relPath(file))
                if (issues.isEmpty()) return@mapNotNull null
                errors += issues.count { it.severity == Severity.ERROR }
                warnings += issues.count { it.severity == Severity.WARNING }
                linkedMapOf(
                    "file" to ws.relPath(file),
                    "issues" to issues.map { issueMap(it) },
                )
            }
            dumpYaml(
                linkedMapOf(
                    "schemaErrors" to ws.schemaErrors,
                    "files" to fileResults,
                    "errors" to errors,
                    "warnings" to warnings,
                )
            )
        }
        "apply" -> {
            val ws = workspace()
            val changeSetFile = rest.getOrNull(1)?.takeIf { !it.startsWith("--") }
                ?: error("変更セットのYAMLファイルを指定してください")
            val changeSet = ChangeSets.parse(Files.readString(Path.of(changeSetFile)))
            val result = ws.apply(
                changeSet,
                dryRun = "--dry-run" in rest,
                requireValid = "--require-valid" in rest,
            )
            dumpYaml(
                linkedMapOf(
                    "ok" to result.ok,
                    "dryRun" to result.dryRun,
                    "changes" to result.results.map { r ->
                        linkedMapOf<String, Any?>(
                            "file" to r.file,
                            "applied" to r.applied,
                            "message" to r.message,
                            "issues" to r.issues.map { issueMap(it) },
                        ).filterValues { it != null }
                    },
                )
            )
        }
        "hash" -> {
            val ws = workspace()
            val rel = rest.getOrNull(1) ?: error("ファイルを指定してください")
            val text = Files.readString(ws.resolve(rel))
            dumpYaml(linkedMapOf("file" to rel, "base" to ChangeSets.contentHash(text)))
        }
        else -> error("不明なコマンドです: $command\n$CLI_USAGE")
    }
}

private fun issueMap(issue: validate.Issue) = linkedMapOf(
    "severity" to issue.severity.name,
    "path" to issue.path.pretty(),
    "message" to issue.message,
)

private fun stringFlag(args: List<String>, name: String): String? =
    args.indexOf(name).takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }

private fun stringFlags(args: List<String>, name: String): List<String> =
    args.indices.filter { args[it] == name && it + 1 < args.size }.map { args[it + 1] }

private fun intFlag(args: List<String>, name: String): Int? =
    stringFlag(args, name)?.let { it.toIntOrNull() ?: error("$name には整数を指定してください: $it") }

private fun dumpYaml(value: Any?): String = YamlIO.dump(YamlIO.fromAny(value))
