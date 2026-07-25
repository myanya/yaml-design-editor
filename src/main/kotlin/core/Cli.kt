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
  view     <workspace> [view-id]                ビュー（_views）を行×列に解決する。
           view-id 省略時は定義の一覧を表示する。各セルに原本上の座標（file / path）が付く
  validate <workspace> [file]                   検証（スキーマ・参照・重複・カバレッジ）
  apply    <workspace> <changeset.yaml> [--dry-run] [--require-valid]   変更セットの適用
  hash     <workspace> <file>                   変更セットの base に使う内容ハッシュ
  activities <workspace>                        アクティビティ／エージェント定義の一覧と検証
  agent    <workspace> <activity-id> [--instruction 追加指示] [--provider openai|anthropic]
           [--model id] [--out changeset.yaml] [--apply]
           アクティビティを実行（APIキーは環境変数 OPENAI_API_KEY / ANTHROPIC_API_KEY）。
           提出された変更セットを出力し、--apply 指定時のみワークスペースへ適用する
  watch    <workspace> [--provider openai|anthropic] [--once]
           自動実行エンジンを起動（トリガ監視・キュー・自動適用/承認待ち）。
           --once はトリガ判定とキュー消化を1回だけ行って終了する
  revert   <workspace> <runlog.yaml> [--force]  自動適用された変更を実行記録のバックアップから巻き戻す
  metrics  <workspace> [--activity id]          実行記録（_runs）の集計（提出率・採用率・コスト等）
  eval     <workspace> [eval-id] [--provider openai|anthropic]
           評価セット（_evals）を実行する。eval-id 省略時は定義の一覧を表示する。
           サンドボックスコピー上で実行し、レポートを _runs/ に保存する
  skills   <workspace>                          skill定義（_skills。入出力変換）の一覧と検証
  import   <workspace> <skill-id> <file> [--apply] [--require-valid]
           表形式ファイル（xlsx/csv/json/markdown）を変更セットへ変換する。
           既定はプレビューのみ。--apply で適用（既存文書はマップした項目だけ更新）
  export   <workspace> <skill-id>               YAML原本から派生成果物（xlsx/csv/md/json/html）を再生成する
  run-skill <workspace> <skill-id> [--yes]      コード実行skill（kind: run）。--yes なしでは
           コマンド内容の表示のみ。人間が内容を確認し --yes を付けたときだけ実行する（明示許可制）
  init     <dir> [--template name]              テンプレートパックでワークスペースを初期化
           （--template 省略時は利用可能なテンプレートを一覧表示）
  bench    <dir> [--docs N] [--items N]         索引性能の計測（R8-02）。空ディレクトリならベンチマーク用
           ワークスペース（既定 1000 文書）を生成し、フル再構築と増分更新の時間を比較する
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
                    "phase" to entry.phase,
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
        "view" -> {
            val ws = workspace()
            val loaded = Views.load(ws.root)
            val viewId = rest.getOrNull(1)?.takeIf { !it.startsWith("--") }
                ?: return dumpYaml(
                    linkedMapOf(
                        "views" to loaded.views.map { v ->
                            linkedMapOf(
                                "id" to v.id, "name" to v.name, "idPattern" to v.rows.idPattern,
                                "columns" to v.columns.map { it.label },
                            )
                        },
                        "errors" to loaded.errors,
                    )
                )
            val view = loaded.view(viewId)
                ?: error("ビュー「$viewId」が見つかりません（view コマンドで一覧を確認できます）")
            val rows = Views.resolve(view, ws.root, ws.index)
            dumpYaml(
                linkedMapOf(
                    "view" to view.id,
                    "name" to view.name,
                    "rows" to rows.map { row ->
                        linkedMapOf<String, Any?>(
                            "id" to row.id,
                            "title" to row.title,
                            "cells" to row.cells.map { cell ->
                                // 複合値は要約に留める（全文は read コマンドで座標を指定して取得する）
                                val value = when (cell.value) {
                                    is model.YNode.Scalar -> yaml.YamlIO.toAny(cell.value!!)
                                    null -> null
                                    else -> Views.cellText(cell.value)
                                }
                                linkedMapOf<String, Any?>(
                                    "column" to cell.column.label,
                                    "value" to value,
                                    "file" to cell.rel,
                                    "path" to cell.path?.pretty(),
                                    "editable" to cell.column.editable.takeIf { it },
                                    // バックリンク列: 参照元のIDと座標（参照元の要素へは read コマンドで辿る）
                                    "backlinks" to cell.backlinks?.map { b ->
                                        linkedMapOf(
                                            "id" to b.sourceId,
                                            "file" to b.rel,
                                            "path" to b.path.pretty(),
                                        ).filterValues { it != null }
                                    },
                                ).filterValues { it != null }
                            },
                            "detail" to row.detailRel,
                            "detailCreatePath" to if (row.detailRel == null)
                                view.detail?.filePattern?.replace("{id}", row.id) else null,
                        ).filterValues { it != null }
                    },
                )
            )
        }
        "validate" -> {
            val ws = workspace()
            val target = rest.getOrNull(1)?.takeIf { !it.startsWith("--") }
            val files = if (target != null) listOf(ws.resolve(target))
            else ws.files.filter { !ws.relPath(it).startsWith(schema.SchemaLoader.SCHEMA_DIR + "/") }
            var errors = 0
            var warnings = 0
            val fileResults = files.mapNotNull { file ->
                val rel = ws.relPath(file)
                val issues = ws.validate(rel)
                errors += issues.count { it.severity == Severity.ERROR }
                warnings += issues.count { it.severity == Severity.WARNING }
                // phases 宣言のあるスキーマは記入進捗（未来フェーズの未記入はエラーではなくここに出る）も付与する
                val doc = ws.load(rel)
                val docSchema = doc.schema
                val progress = docSchema?.phases?.let { validate.Validator.phaseProgress(doc.root, docSchema) }
                    ?.filter { it.total > 0 }
                if (issues.isEmpty() && progress.isNullOrEmpty()) return@mapNotNull null
                linkedMapOf(
                    "file" to rel,
                    "issues" to issues.map { issueMap(it) },
                    "phaseProgress" to progress?.map {
                        linkedMapOf("phase" to it.phase, "filled" to it.filled, "total" to it.total)
                    },
                ).filterValues { it != null && it != emptyList<Any>() }
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
        "activities" -> {
            val ws = workspace()
            val defs = agent.Definitions.load(ws.root)
            dumpYaml(
                linkedMapOf(
                    "agents" to defs.agents.map { linkedMapOf("id" to it.id, "name" to it.name, "tools" to it.tools.map { t -> t.id }) },
                    "activities" to defs.activities.map {
                        linkedMapOf(
                            "id" to it.id, "name" to it.name, "agent" to it.agentId,
                            "target" to it.targetMatch, "gate" to it.gate.name,
                        )
                    },
                    "errors" to defs.errors,
                )
            )
        }
        "agent" -> runAgentCommand(rest)
        "watch" -> runWatchCommand(rest)
        "metrics" -> {
            val root = Path.of(rest.firstOrNull() ?: error("ワークスペースのパスを指定してください"))
            val records = agent.RunMetrics.collect(root)
            val filter = stringFlag(rest, "--activity")
            val summaries = agent.RunMetrics.summarize(records)
                .filter { filter == null || it.activityId == filter }
            dumpYaml(agent.RunMetrics.toYamlMap(summaries))
        }
        "eval" -> runEvalCommand(rest)
        "skills" -> {
            val ws = workspace()
            val loaded = skill.Skills.load(ws.root)
            dumpYaml(
                linkedMapOf(
                    "skills" to loaded.skills.map { s ->
                        linkedMapOf<String, Any?>(
                            "id" to s.id, "name" to s.name, "kind" to s.kind.id,
                            "target" to s.import?.targetFile,
                            "output" to s.export?.outputFile,
                        ).filterValues { it != null }
                    },
                    "errors" to loaded.errors,
                )
            )
        }
        "import" -> runImportCommand(rest)
        "export" -> {
            val ws = workspace()
            val skillId = rest.getOrNull(1)?.takeIf { !it.startsWith("--") } ?: error("skill IDを指定してください")
            val loaded = skill.Skills.load(ws.root)
            val def = loaded.skill(skillId) ?: error("skill「$skillId」が見つかりません（skills コマンドで確認できます）")
            val result = skill.Exporter.run(ws, def)
            dumpYaml(linkedMapOf("file" to result.rel, "rows" to result.rows))
        }
        "run-skill" -> {
            // コード実行skill（R9-06）。明示許可制: --yes なしでは内容の表示だけを行い、実行しない
            val ws = workspace()
            val skillId = rest.getOrNull(1)?.takeIf { !it.startsWith("--") } ?: error("skill IDを指定してください")
            val loaded = skill.Skills.load(ws.root)
            val def = loaded.skill(skillId) ?: error("skill「$skillId」が見つかりません（skills コマンドで確認できます）")
            val runDef = def.run ?: error("skill「$skillId」は run（コード実行）ではありません")
            val summary = linkedMapOf<String, Any?>(
                "skill" to def.id,
                "command" to runDef.command,
                "workdir" to runDef.workdir,
                "timeoutSeconds" to runDef.timeoutSeconds,
                "writes" to runDef.writes,
            )
            if ("--yes" !in rest) {
                dumpYaml(summary) +
                    "# 上記のコマンドがワークスペースの外にも作用しうることを確認し、実行するには --yes を付けてください\n"
            } else {
                val outcome = skill.Runner.run(ws, def, skill.Runner.Approval.approve(runDef))
                dumpYaml(
                    linkedMapOf<String, Any?>(
                        "ok" to outcome.ok,
                        "exitCode" to outcome.exitCode,
                        "timedOut" to outcome.timedOut.takeIf { it },
                        "changedFiles" to outcome.changedFiles.ifEmpty { null },
                        "outOfScope" to outcome.outOfScope.ifEmpty { null },
                        "reservedTouched" to outcome.reservedTouched.ifEmpty { null },
                        "stdout" to outcome.stdout.takeIf { it.isNotBlank() },
                        "stderr" to outcome.stderr.takeIf { it.isNotBlank() },
                    ).filterValues { it != null }
                )
            }
        }
        "init" -> {
            val dir = Path.of(rest.firstOrNull() ?: error("ディレクトリを指定してください"))
            val name = stringFlag(rest, "--template")
                ?: return dumpYaml(
                    linkedMapOf(
                        "templates" to skill.TemplatePacks.all.map {
                            linkedMapOf("name" to it.name, "description" to it.description, "files" to it.files.size)
                        }
                    )
                )
            val pack = skill.TemplatePacks.pack(name)
                ?: error("テンプレート「$name」が見つかりません（利用可能: ${skill.TemplatePacks.all.joinToString(", ") { it.name }}）")
            val result = skill.TemplatePacks.apply(dir, pack)
            dumpYaml(
                linkedMapOf(
                    "template" to pack.name,
                    "written" to result.written,
                    "skipped" to result.skipped.ifEmpty { null },
                ).filterValues { it != null }
            )
        }
        "bench" -> {
            val dir = Path.of(rest.firstOrNull() ?: error("ディレクトリを指定してください"))
            val generated = if (!Files.isDirectory(dir) || Files.list(dir).use { it.findFirst().isEmpty }) {
                Bench.generate(dir, documents = intFlag(rest, "--docs") ?: 1000, itemsPerDocument = intFlag(rest, "--items") ?: 8)
            } else null
            val result = Bench.measure(dir)
            dumpYaml(
                linkedMapOf(
                    "generatedDocuments" to generated,
                    "documents" to result.documents,
                    "ids" to result.ids,
                    "refs" to result.refs,
                    "fullRebuildMillis" to result.fullRebuildMillis,
                    "incrementalUpdateMillis" to result.incrementalUpdateMillis,
                    "speedup" to result.speedup,
                ).filterValues { it != null }
            )
        }
        "revert" -> {
            val ws = workspace()
            val runFile = rest.getOrNull(1)?.takeIf { !it.startsWith("--") }
                ?: error("実行記録（_runs/*.yaml）を指定してください")
            val result = agent.AutoApply.revert(ws, ws.resolve(runFile), force = "--force" in rest)
            dumpYaml(linkedMapOf("ok" to result.ok, "messages" to result.messages))
        }
        else -> error("不明なコマンドです: $command\n$CLI_USAGE")
    }
}

/** 環境変数に設定されているAPIキーをプロバイダごとに集める（delegate や watch で使う）。 */
private fun envApiKeys(): Map<ai.AiProvider, String> =
    ai.AiProvider.entries.associateWith { System.getenv(it.envVar).orEmpty() }.filterValues { it.isNotBlank() }

/** 自動実行エンジンをヘッドレスで起動する（R3-01〜05）。 */
private fun runWatchCommand(rest: List<String>): String {
    val dir = Path.of(rest.firstOrNull() ?: error("ワークスペースのパスを指定してください"))
    val provider = stringFlag(rest, "--provider")?.let { flag ->
        ai.AiProvider.entries.firstOrNull { it.name.equals(flag, ignoreCase = true) } ?: error("未知のプロバイダです: $flag")
    } ?: ai.AiProvider.OPENAI
    val keys = envApiKeys()
    val engine = agent.AutomationEngine(
        dir,
        credentials = { agent.AutomationEngine.Credentials(provider = provider, apiKeys = keys) },
    )
    engine.logSink = { System.err.println(it) }
    engine.setEnabled(true)
    return if ("--once" in rest) {
        engine.tick(System.currentTimeMillis())
        kotlinx.coroutines.runBlocking { while (engine.processOnce()) { /* キューを消化 */ } }
        dumpYaml(
            linkedMapOf(
                "history" to engine.historySnapshot().map {
                    linkedMapOf("activity" to it.activityId, "status" to it.status, "detail" to it.detail)
                },
                "pendingReviews" to engine.pendingSnapshot().map {
                    linkedMapOf(
                        "activity" to it.activityId,
                        "reason" to it.reason,
                        "runLog" to it.runFile?.let { f -> it.ws.relPath(f) },
                        "changes" to it.result.changeSet.changes.map { c -> c.file },
                    )
                },
            )
        )
    } else {
        System.err.println("自動実行を開始しました（Ctrl+C で停止）。承認待ちは _runs/ の changeset を apply で適用できます。")
        kotlinx.coroutines.runBlocking { engine.start(this).join() }
        ""
    }
}

/** 表形式ファイルを変更セットへ変換し、プレビューまたは適用する（R5-02）。 */
private fun runImportCommand(rest: List<String>): String {
    val ws = Workspace.open(Path.of(rest.firstOrNull() ?: error("ワークスペースのパスを指定してください")))
    val skillId = rest.getOrNull(1)?.takeIf { !it.startsWith("--") } ?: error("skill IDを指定してください")
    val sourceFile = rest.getOrNull(2)?.takeIf { !it.startsWith("--") }
        ?.let { Path.of(it) } ?: error("取り込むファイルを指定してください")
    require(Files.isRegularFile(sourceFile)) { "ファイルが見つかりません: $sourceFile" }
    val loaded = skill.Skills.load(ws.root)
    if (loaded.errors.isNotEmpty()) System.err.println("定義の警告:\n" + loaded.errors.joinToString("\n") { "- $it" })
    val def = loaded.skill(skillId) ?: error("skill「$skillId」が見つかりません（skills コマンドで確認できます）")

    val preview = skill.Importer.build(ws, def, sourceFile)
    if (!preview.ok) {
        return dumpYaml(linkedMapOf("ok" to false, "errors" to preview.errors))
    }
    val summary = linkedMapOf<String, Any?>(
        "ok" to true,
        "created" to preview.created,
        "updated" to preview.updated,
        "unchanged" to preview.unchanged,
    )
    if ("--apply" !in rest) {
        return dumpYaml(summary) +
            (if (preview.changeSet.changes.isEmpty()) "" else "---\n" + changeSetToYaml(preview.changeSet))
    }
    if (preview.changeSet.changes.isEmpty()) {
        summary["applied"] = "変更なし"
        return dumpYaml(summary)
    }
    val result = ws.apply(preview.changeSet, requireValid = "--require-valid" in rest)
    summary["applied"] = result.ok
    if (!result.ok) {
        summary["applyErrors"] = result.results.mapNotNull { r ->
            val issues = r.issues.map { "${it.severity} ${it.path.pretty()}: ${it.message}" }
            val parts = listOfNotNull(r.message?.takeIf { it.isNotBlank() }) + issues
            if (parts.isEmpty()) null else "${r.file}: ${parts.joinToString("; ")}"
        }
    }
    return dumpYaml(summary)
}

/** 評価セットをヘッドレス実行する（R4-02 / R4-04）。 */
private fun runEvalCommand(rest: List<String>): String {
    val root = Path.of(rest.firstOrNull() ?: error("ワークスペースのパスを指定してください"))
    val loaded = agent.Evals.load(root)
    if (loaded.errors.isNotEmpty()) System.err.println("定義の警告:\n" + loaded.errors.joinToString("\n") { "- $it" })
    val evalId = rest.getOrNull(1)?.takeIf { !it.startsWith("--") }
        ?: return dumpYaml(
            linkedMapOf(
                "evals" to loaded.evals.map {
                    linkedMapOf(
                        "id" to it.id, "name" to it.name, "activity" to it.activityId,
                        "cases" to it.cases.size, "variants" to it.variants.map { v -> v.name },
                    )
                },
                "errors" to loaded.errors,
            )
        )
    val eval = loaded.eval(evalId) ?: error("評価セット「$evalId」が見つかりません（eval コマンドで一覧を確認できます）")
    val provider = stringFlag(rest, "--provider")?.let { flag ->
        ai.AiProvider.entries.firstOrNull { it.name.equals(flag, ignoreCase = true) } ?: error("未知のプロバイダです: $flag")
    } ?: ai.AiProvider.OPENAI
    val runner = agent.EvalRunner(
        credentials = { agent.AutomationEngine.Credentials(provider = provider, apiKeys = envApiKeys()) },
    )
    val report = kotlinx.coroutines.runBlocking {
        runner.run(root, eval, onLog = { System.err.println(it) })
    }
    return dumpYaml(
        linkedMapOf(
            "eval" to report.evalId,
            "activity" to report.activityId,
            "report" to report.reportFile?.let { Workspace.open(root).relPath(it) },
            "variants" to report.summaries().map { s ->
                linkedMapOf(
                    "name" to s.variant, "cases" to s.cases, "passed" to s.passed, "passRate" to s.passRate,
                    "inputTokens" to s.inputTokens, "outputTokens" to s.outputTokens,
                )
            },
            "results" to report.results.map { r ->
                linkedMapOf<String, Any?>(
                    "variant" to r.variant, "case" to r.caseId, "pass" to r.pass,
                    "status" to r.status.name,
                    "failures" to r.failures,
                ).filterValues { it != null && (it !is List<*> || it.isNotEmpty()) }
            },
        )
    )
}

/** アクティビティをヘッドレス実行する。非対話のため ask_human には「回答なし」を返す。 */
private fun runAgentCommand(rest: List<String>): String {
    val ws = Workspace.open(Path.of(rest.firstOrNull() ?: error("ワークスペースのパスを指定してください")))
    val activityId = rest.getOrNull(1)?.takeIf { !it.startsWith("--") } ?: error("アクティビティIDを指定してください")
    val defs = agent.Definitions.load(ws.root)
    if (defs.errors.isNotEmpty()) System.err.println("定義の警告:\n" + defs.errors.joinToString("\n") { "- $it" })
    val activity = defs.activity(activityId) ?: error("アクティビティ「$activityId」が見つかりません（activities コマンドで確認できます）")
    val agentDef = defs.agent(activity.agentId) ?: error("エージェント「${activity.agentId}」が見つかりません")

    val provider = stringFlag(rest, "--provider")?.let { flag ->
        ai.AiProvider.entries.firstOrNull { it.name.equals(flag, ignoreCase = true) } ?: error("未知のプロバイダです: $flag")
    } ?: agentDef.provider ?: ai.AiProvider.OPENAI
    // エージェント定義のモデルIDは、その定義のプロバイダと一致する場合のみ引き継ぐ
    // （--provider で上書きしたときに他社のモデルIDを送らない）
    val pinnedModel = agentDef.model?.takeIf { agentDef.provider == null || agentDef.provider == provider }
    val model = stringFlag(rest, "--model") ?: pinnedModel ?: provider.defaultModel
    val apiKey = System.getenv(provider.envVar).orEmpty()
    require(apiKey.isNotBlank()) { "環境変数 ${provider.envVar} にAPIキーを設定してください" }

    val host = object : agent.AgentHost {
        override suspend fun askHuman(question: String): String? {
            System.err.println("[質問] $question（非対話実行のため回答できません）")
            return null
        }
        override suspend fun onEvent(event: agent.AgentEvent) {
            val line = when (event) {
                is agent.AgentEvent.TurnStarted -> "--- ターン ${event.turn}/${event.maxTurns}"
                is agent.AgentEvent.AssistantText -> event.text.lineSequence().joinToString("\n") { "  $it" }
                is agent.AgentEvent.ToolInvoked -> "  [tool] ${event.name} ${event.summary}"
                is agent.AgentEvent.ToolFailed -> "  [tool失敗] ${event.name}: ${event.message}"
                is agent.AgentEvent.QuestionAsked -> "  [質問] ${event.question}"
                is agent.AgentEvent.PlanUpdated -> "  [計画] ${event.steps.joinToString(" / ")}"
                is agent.AgentEvent.Finished -> "=== 終了: ${event.status}${event.message?.let { " — $it" } ?: ""}"
            }
            System.err.println(line)
        }
    }
    val request = agent.AgentRunRequest(
        activity = activity, agent = agentDef,
        provider = provider, model = model, apiKey = apiKey,
        extraInstruction = stringFlag(rest, "--instruction"),
        apiKeys = envApiKeys(),
        subAgents = defs.agents,
    )
    val result = kotlinx.coroutines.runBlocking { agent.AgentKernel.run(ws, request, host) }
    val runFile = agent.RunLog.write(ws, request, result)

    val changeSetYaml = changeSetToYaml(result.changeSet)
    stringFlag(rest, "--out")?.let { Files.writeString(Path.of(it), changeSetYaml) }
    var appliedOk: Boolean? = null
    if ("--apply" in rest && result.status == agent.RunStatus.SUBMITTED && result.changeSet.changes.isNotEmpty()) {
        appliedOk = ws.apply(result.changeSet).ok
    }
    return dumpYaml(
        linkedMapOf(
            "status" to result.status.name,
            "message" to result.message,
            "turns" to result.turnsUsed,
            "usage" to linkedMapOf("inputTokens" to result.usage.inputTokens, "outputTokens" to result.usage.outputTokens),
            "runLog" to ws.relPath(runFile),
            "applied" to appliedOk,
            "changes" to result.changeSet.changes.map { linkedMapOf("file" to it.file, "create" to it.create, "ops" to it.ops.size) },
        )
    ) + (if (result.changeSet.changes.isEmpty()) "" else "---\n$changeSetYaml")
}

/** 変更セットを apply コマンドで再適用できるYAMLへ変換する。 */
internal fun changeSetToYaml(cs: ChangeSet): String {
    val changes = cs.changes.map { change ->
        if (change.create) linkedMapOf(
            "file" to change.file,
            "create" to true,
            "content" to change.content?.let { YamlIO.toAny(it) },
        ) else linkedMapOf<String, Any?>(
            "file" to change.file,
            "base" to change.baseHash,
            "ops" to change.ops.map { op ->
                when (op) {
                    is PatchOp.Set -> linkedMapOf("op" to "set", "path" to op.path.pretty(), "value" to YamlIO.toAny(op.value))
                    is PatchOp.Insert -> linkedMapOf("op" to "insert", "path" to op.path.pretty(), "value" to YamlIO.toAny(op.value))
                    is PatchOp.Delete -> linkedMapOf("op" to "delete", "path" to op.path.pretty())
                    is PatchOp.Move -> linkedMapOf("op" to "move", "from" to op.from.pretty(), "to" to op.to.pretty())
                }
            },
        ).filterValues { it != null }
    }
    return YamlIO.dump(YamlIO.fromAny(linkedMapOf("changes" to changes)))
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
