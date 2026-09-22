package dev.toros.yde.app.core

import dev.toros.yde.app.agent.AgentEvent
import dev.toros.yde.app.agent.AgentHost
import dev.toros.yde.app.agent.AgentKernel
import dev.toros.yde.app.agent.AgentRunRequest
import dev.toros.yde.app.agent.AutoApply
import dev.toros.yde.app.agent.AutomationEngine
import dev.toros.yde.app.agent.Definitions
import dev.toros.yde.app.agent.EvalRunner
import dev.toros.yde.app.agent.Evals
import dev.toros.yde.app.agent.Foreach
import dev.toros.yde.app.agent.RunLog
import dev.toros.yde.app.agent.RunMetrics
import dev.toros.yde.app.agent.RunStatus
import dev.toros.yde.app.ai.AiProvider
import dev.toros.yde.app.index.Query
import dev.toros.yde.app.schema.SchemaInference
import dev.toros.yde.app.skill.Exporter
import dev.toros.yde.app.skill.Importer
import dev.toros.yde.app.skill.Runner
import dev.toros.yde.app.skill.Skills
import dev.toros.yde.app.skill.TemplatePacks
import dev.toros.yde.app.validate.Issue
import dev.toros.yde.app.validate.Readiness
import dev.toros.yde.app.validate.Validator
import dev.toros.yde.semantic.SemanticTransform
import dev.toros.yde.semantic.schema.EntityRole
import dev.toros.yde.semantic.schema.QueryNodeKind
import dev.toros.yde.semantic.schema.QuerySpec
import dev.toros.yde.semantic.schema.SchemaKind
import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.semantic.schema.globToRegex
import dev.toros.yde.workspace.BuiltinTransforms
import dev.toros.yde.workspace.changeset.ChangeSet
import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.app.schema.validateDefinition
import dev.toros.yde.yaml.patch.PatchOp
import dev.toros.yde.yaml.validate.Severity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import dev.toros.yde.yaml.YamlIO

/**
 * ヘッドレスCLI（R1-01）。UIを起動せずにワークスペースの探索・検索・パッチ・検証を行う。
 * 実行例: ./gradlew runCli --args="catalog samples/sample-workspace"
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
  traverse <workspace> <id> [--steps <YAML配列>] [--budget N]
           ID関係グラフを経路パターンで多段に辿る（R19-03。コンテキストレシピ graph 項目の試作台）。
           steps は連鎖（各ステップは前ステップの到達ノードから辿る）。
           --steps 例: "[{emit: full}, {follow: out, via: actor, emit: full}]"
           省略時は「起点の要約 → 参照先の要約」
  query    <workspace> [spec.yaml] [--spec <YAML>]
           グラフパターン照合（R22-01）: match（node / edge の連鎖）＋ where ＋ also を評価し、
           束縛の表を返す（ビュー rows: match / foreach source: query / constraints の試作台）。
           --spec 例: "{match: [{node: {entity: screen, bind: scr}}, {edge: {relation: uses}}, {node: {entity: usecase, bind: uc}}]}"
  transform <workspace> <定義.yaml> [--input <file>] [--param 名前=値]... [--origins]
           YAML から YAML への変換（P27 段C。試作台）: 定義（from＝クエリで選ぶ／build＝出力のひな型／rules＝入れ子に当てる規則）を
           評価して、組み立てた YAML を返す。from を書かない定義は --input の文書1つが入力（ワークスペース相対か、ファイルのパス）。
           --param の値は YAML として読む（例: --param "reachedPhases=[基本設計, 詳細設計]"）。--origins で、出力の各値が
           入力のどこから来たかを添える。出力は保存しない（原本は変わらない）
           定義に「builtin:structural-schema」と書くと、組込みの変換定義（構造のスキーマの導出）を使う
           （例: transform <workspace> builtin:structural-schema --input _schemas/x.yaml＝そのスキーマから導出される、最小の構造のスキーマ）
  view     <workspace> [view-id] [file]         ビュー（_views）を解決する。横断グリッドは行×列、
           文書ビュー（kind: document）は file で対象文書を指定（適用対象1件なら省略可）。
           view-id 省略時は定義の一覧を表示する。各セルに原本上の座標（file / path）が付く
  validate <workspace> [file] [--sources]       検証（スキーマ・参照・重複・カバレッジ・グラフ整合）。判定を保留した検査があれば
           withheld に内訳と件数を出す。--sources は指摘の出どころ（どの検査の・どの要素についての指摘か）も出す
  readiness <workspace> [--pending]             準備完了レポート（R28-05b）: 全文書の検証結果をエンティティ単位に
           集約し、エラー0のエンティティを「準備完了」と判定する（警告は件数のみ・型／フェーズ別の集計つき）。
           --pending は未完了（エラーあり）のエンティティだけを列挙する
  bench    [--modules 2,4,8,16] [--keep <dir>]   規模の計測（P27）: 大きさを指定して合成ワークスペースを作り、開く・全体を検証する・
           準備完了レポートを作る、の時間を測る。--modules は大きさ（モジュールの数）の並び。前の大きさとの比（時間の伸び ÷ 大きさの伸び）で、
           時間が規模に比例しているか（1.0 前後）、それ以上に伸びているかが分かる。--keep は作ったワークスペースを残す場所（省略時は消す）
  checks   <workspace> [schema-id] [--definitions]   展開後の検査の表示（P29 Phase 6）: スキーマの語彙（consistency など）と
           定義の使用（constraints の use）が、検証の前にどんな基本の検査へ展開されたかを由来つきで表示する。
           --definitions は使える検査の定義（functions）と語彙（vocabulary）の一覧も出す
  apply    <workspace> <changeset.yaml> [--dry-run] [--require-valid]   変更セットの適用
  entity   <workspace> show <id>                エンティティ全体像（R23-04）: アスペクト合成（所有フィールド
           と原本座標）・関係・被参照をYAMLで返す（エンティティページ・read_entity と同じ合成）
           ※ 変更系（rename / merge / delete）・move・refactor・bench は新CLIへ移動した（B-1）:
             ./gradlew :cli:run --args="workspace refactor <workspace> <op> … --apply"
  hash     <workspace> <file>                   変更セットの base に使う内容ハッシュ
  activities <workspace>                        アクティビティ／エージェント定義の一覧と検証
  agent    <workspace> <activity-id> [--instruction 追加指示] [--provider openai|anthropic]
           [--model id] [--out changeset.yaml] [--apply] [--foreach]
           アクティビティを実行（APIキーは環境変数 OPENAI_API_KEY / ANTHROPIC_API_KEY）。
           提出された変更セットを出力し、--apply 指定時のみワークスペースへ適用する。
           --foreach は定義の foreach で対象を列挙し、1件=1実行で直列に一括実行する
           （--apply 併用時は1件ごとに適用するため、後続の項目から先行の成果が見える）
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
  infer-schema <workspace> <glob> [--id schema-id] [--apply]
           既存YAML文書（glob。複数文書はマージ）からスキーマのドラフトを推測する（R11-01）。
           既定は標準出力へドラフトを表示。--apply で _schemas/<id>.yaml へ保存する（既存は上書きしない）
  infer-relations <workspace> [--id registry-id] [--apply]
           索引の全参照から relation・viaKey・参照元／先のエンティティ型の実績を集計し、
           関係レジストリ（kind: relations）文書のドラフトを生成する（R20-02）。
           既定は標準出力へ表示。--apply で _schemas/<id>.yaml へ保存する（既存は上書きしない）
  migrate  <workspace> [--ledger dir]           レガシー構成（ルートの _schemas 等）を .yde 構成へ移行する（R13-01）。
           予約ディレクトリを .yde/ 配下へ移動し .yde/workspace.yaml を作成する。
           --ledger で台帳ディレクトリを宣言（未指定なら全体が台帳＝従来と同じ検証範囲）
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
        "traverse" -> {
            val ws = workspace()
            val start = rest.getOrNull(1)?.takeIf { !it.startsWith("--") } ?: error("起点の論理IDを指定してください")
            val steps = stringFlag(rest, "--steps")?.let { raw ->
                val list = dev.toros.yde.yaml.YamlIO.parseToRaw(raw) as? List<*> ?: error("--steps はYAMLの配列である必要があります")
                list.mapIndexed { i, e -> TraverseStep.parse(e, "steps[$i]") }
            } ?: TraverseSpec.DEFAULT_STEPS
            val budget = intFlag(rest, "--budget") ?: 8_000
            Traverse.run(
                ws.index,
                loader = { d -> runCatching { ws.load(d.value).root }.getOrNull() },
                spec = TraverseSpec(start = start, steps = steps, budgetChars = budget),
            ) + "\n"
        }
        "query" -> {
            val ws = workspace()
            // スペックはファイル（第2引数）かインライン（--spec）のどちらかで与える
            val specText = stringFlag(rest, "--spec")
                ?: rest.getOrNull(1)?.takeIf { !it.startsWith("--") }?.let { Files.readString(Path.of(it)) }
                ?: error("クエリのスペックを指定してください（query <workspace> <spec.yaml> または --spec \"<YAML>\"）")
            val raw = dev.toros.yde.yaml.YamlIO.parseToRaw(specText) as? Map<*, *> ?: error("スペックのルートはobjectである必要があります")
            val spec = QuerySpec.parse(raw, "query")
            val result = Query.run(ws.index, spec, ws::treeOf)
            dumpYaml(
                linkedMapOf<String, Any?>(
                    "columns" to result.columns,
                    "total" to result.total,
                    "rows" to result.rows.map { row ->
                        row.bindings.entries.associate { (bind, node) ->
                            bind to linkedMapOf<String, Any?>(
                                "id" to node.id,
                                "kind" to node.kind.id.takeIf { node.kind != QueryNodeKind.ENTITY },
                                "title" to node.title,
                                "entityType" to node.entityType,
                                "file" to node.document?.value,
                                "path" to node.path.takeIf { it.isNotEmpty() }?.pretty(),
                            ).filterValues { it != null }
                        }
                    },
                )
            )
        }
        "transform" -> {
            val ws = workspace()
            val defFile = rest.getOrNull(1)?.takeIf { !it.startsWith("--") }
                ?: error("変換の定義を指定してください（transform <workspace> <定義.yaml> [--input <file>] [--param 名前=値]）")
            // 「builtin:名前」は、組込みの変換定義（例: builtin:structural-schema＝構造のスキーマの導出）
            val def = if (defFile.startsWith("builtin:")) BuiltinTransforms.parse(defFile.removePrefix("builtin:"))
            else TransformDef.parse(dev.toros.yde.yaml.YamlIO.parseToRaw(Files.readString(Path.of(defFile))), "transform")
            val params = stringFlags(rest, "--param").associate { raw ->
                val at = raw.indexOf('=').takeIf { it > 0 } ?: error("--param は 名前=値 で書きます: $raw")
                raw.substring(0, at) to dev.toros.yde.yaml.YamlIO.parseToRaw(raw.substring(at + 1))
            }
            val result = if (def.from == null) {
                val input = stringFlag(rest, "--input") ?: error("この定義は入力の文書を1つ受け取ります。--input <file> を指定してください")
                // ワークスペースの文書ならその相対パス、そうでなければファイルのパスとして読む
                val file = ws.root.resolve(input).takeIf { Files.exists(it) } ?: Path.of(input)
                val root = dev.toros.yde.yaml.YamlIO.parseFirst(Files.readString(file)) ?: error("入力を YAML として読めません: $input")
                SemanticTransform.run(def, root, DocumentId(file.toString()), params)
            } else {
                val view = ws.index
                SemanticTransform.run(
                    def, view.entries, view.refs, view.graph,
                    rel = { d -> d.value },
                    params = params,
                    loader = { d -> runCatching { ws.load(d.value).root }.getOrNull() },
                )
            }
            val text = result.term?.let { YamlIO.dumpPlain(it) } ?: "# （何も得られませんでした）\n"
            if ("--origins" !in rest) text
            else text + "\n" + dumpYaml(
                linkedMapOf(
                    "origins" to result.origins.map { o ->
                        linkedMapOf(
                            "output" to o.output.pretty(),
                            "file" to o.document?.value,
                            "path" to o.path.pretty(),
                        ).filterValues { it != null }
                    },
                )
            )
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
            val loaded = Views.load(ws.root, ws.schemas)
            val viewId = rest.getOrNull(1)?.takeIf { !it.startsWith("--") }
                ?: return dumpYaml(
                    linkedMapOf(
                        "views" to (loaded.views.map { v ->
                            linkedMapOf(
                                "id" to v.id, "name" to v.name,
                                "idPattern" to v.rows?.idPattern,
                                "rowKey" to v.rowKey,
                                "columns" to v.columns.map { it.label },
                            ).filterValues { it != null }
                        } + loaded.docViews.map { v ->
                            linkedMapOf(
                                "id" to v.id, "name" to v.name, "kind" to "document",
                                "match" to v.match, "schemaId" to v.schemaId,
                            ).filterValues { it != null }
                        }),
                        "errors" to loaded.errors,
                    )
                )
            // 文書ビュー（R14-01）: view <workspace> <view-id> [file] で対象文書を解決する
            loaded.docView(viewId)?.let { dv ->
                val fileArg = rest.getOrNull(2)?.takeIf { !it.startsWith("--") }?.replace('\\', '/')
                val candidates = ws.files.map { ws.relPath(it) }
                    .filter { dv.appliesTo(it, ws.schemaFor(ws.resolve(it))?.id) }
                    .sorted()
                val rel = fileArg ?: candidates.singleOrNull() ?: return dumpYaml(
                    linkedMapOf(
                        "view" to dv.id,
                        "kind" to "document",
                        "files" to candidates,
                        "hint" to "view <workspace> ${dv.id} <file> で対象文書を指定してください",
                    )
                )
                val sections = Views.resolveDocument(dv, DocumentId(rel), ws.index, loader = ws::treeOf)
                val attachments = Views.resolveAttachments(dv, DocumentId(rel), ws.index, loader = ws::treeOf)
                // 被参照行 → YAML 構造（セクション・アタッチ共用）
                fun rowMaps(rows: List<DocBacklinkRow>?, def: DocBacklinksDef?): List<Map<String, Any?>>? =
                    rows?.map { row ->
                        linkedMapOf<String, Any?>(
                            "id" to row.id,
                            "title" to row.title,
                            "path" to row.path.pretty(),
                            "backlinks" to row.backlinks.map { b ->
                                linkedMapOf<String, Any?>(
                                    "id" to b.sourceId,
                                    "title" to b.sourceTitle,
                                    "file" to b.document.value,
                                    "path" to b.refPath.pretty(),
                                    "values" to def?.columns
                                        ?.mapIndexed { ci, col -> col.label to Views.cellText(b.values.getOrNull(ci)) }
                                        ?.toMap()?.takeIf { it.isNotEmpty() },
                                ).filterValues { it != null }
                            },
                        ).filterValues { it != null }
                    }
                return dumpYaml(
                    linkedMapOf(
                        "view" to dv.id,
                        "kind" to "document",
                        "name" to dv.name,
                        "file" to rel,
                        "sections" to sections.map { resolved ->
                            val section = resolved.section
                            linkedMapOf<String, Any?>(
                                "label" to section.label,
                                "fields" to section.fieldsPath?.let { p -> if (p.isEmpty()) "(root)" else p.pretty() },
                                "include" to section.fieldsInclude.takeIf { it.isNotEmpty() },
                                "exclude" to section.fieldsExclude.takeIf { it.isNotEmpty() },
                                "rows" to rowMaps(resolved.rows, section.backlinks),
                            ).filterValues { it != null }
                        },
                        "attachments" to attachments.map { resolved ->
                            linkedMapOf<String, Any?>(
                                "label" to resolved.attachment.label,
                                "at" to resolved.attachment.atText,
                                "rows" to rowMaps(resolved.rows, resolved.attachment.backlinks),
                            ).filterValues { it != null }
                        }.takeIf { it.isNotEmpty() },
                    ).filterValues { it != null }
                )
            }
            val view = loaded.view(viewId)
                ?: error("ビュー「$viewId」が見つかりません（view コマンドで一覧を確認できます）")
            val rows = Views.resolve(view, ws.index, ws::treeOf)
            dumpYaml(
                linkedMapOf<String, Any?>(
                    "view" to view.id,
                    "name" to view.name,
                    // 新規行の入口（配列モード: file+path へ要素追加／ファイルモード: filePattern の {id} 置換先へ文書作成）
                    "newRow" to view.newRow?.let { nr ->
                        if (nr.filePattern != null) linkedMapOf("filePattern" to nr.filePattern)
                        else linkedMapOf("file" to nr.file, "path" to nr.path.pretty())
                    },
                    "rows" to rows.map { row ->
                        linkedMapOf<String, Any?>(
                            "id" to row.id,
                            "title" to row.title,
                            "cells" to row.cells.map { cell ->
                                // 複合値は要約に留める（全文は read コマンドで座標を指定して取得する）
                                val value = when (cell.value) {
                                    is YNode.Scalar -> dev.toros.yde.yaml.YamlIO.toAny(cell.value!!)
                                    null -> null
                                    else -> Views.cellText(cell.value)
                                }
                                linkedMapOf<String, Any?>(
                                    "column" to cell.column.label,
                                    "value" to value,
                                    "file" to cell.document?.value,
                                    "path" to cell.path?.pretty(),
                                    "editable" to cell.column.editable.takeIf { it },
                                    // 計算列（R16-02/04）: 値は評価済み・座標は導出規則の位置。derivation が来歴（元値と入力値の座標）
                                    "computed" to cell.computed.takeIf { it },
                                    "derivation" to cell.trace?.takeIf { it.inputs.isNotEmpty() }?.let { t ->
                                        linkedMapOf<String, Any?>(
                                            "raw" to t.raw,
                                            "inputs" to t.inputs.map { input ->
                                                linkedMapOf<String, Any?>(
                                                    "placeholder" to input.placeholder,
                                                    "value" to input.value,
                                                    "file" to input.document?.value,
                                                    "path" to input.path?.pretty(),
                                                ).filterValues { it != null }
                                            },
                                        )
                                    },
                                    // バックリンク列: 参照元のIDと座標（参照元の要素へは read コマンドで辿る）
                                    "backlinks" to cell.backlinks?.map { b ->
                                        linkedMapOf(
                                            "id" to b.sourceId,
                                            "file" to b.document.value,
                                            "path" to b.path.pretty(),
                                        ).filterValues { it != null }
                                    },
                                ).filterValues { it != null }
                            },
                            "detail" to row.detailDocument?.value,
                            "detailCreatePath" to if (row.detailDocument?.value == null)
                                view.detail?.filePattern?.replace("{id}", row.id) else null,
                        ).filterValues { it != null }
                    },
                ).filterValues { it != null }
            )
        }
        "validate" -> {
            val ws = workspace()
            val target = rest.getOrNull(1)?.takeIf { !it.startsWith("--") }
            // `_schemas` 配下もメタスキーマ（R11-02）で検証対象になる（定義のパースエラーは schemaErrors 側）
            val files = if (target != null) listOf(ws.resolve(target)) else ws.files
            var errors = 0
            var warnings = 0
            var withheld = 0
            // --sources: 指摘の出どころ（どの検査の・どの要素についての指摘か）も出す（P27 段A）
            val withSources = "--sources" in rest
            val fileResults = files.mapNotNull { file ->
                val rel = ws.relPath(file)
                val result = ws.validateDetailed(rel)
                val issues = result.issues
                withheld += result.withheld.size
                errors += issues.count { it.severity == Severity.ERROR }
                warnings += issues.count { it.severity == Severity.WARNING }
                // phases 宣言のあるスキーマは記入進捗（未来フェーズの未記入はエラーではなくここに出る）も付与する
                val doc = ws.load(rel)
                val docSchema = doc.schema
                val progress = docSchema?.phases?.let { Validator.phaseProgress(doc.root, docSchema) }
                    ?.filter { it.total > 0 }
                if (issues.isEmpty() && progress.isNullOrEmpty() && result.withheld.isEmpty()) return@mapNotNull null
                linkedMapOf(
                    "file" to rel,
                    "issues" to issues.map { issueMap(it, withSources) },
                    // 判定を保留した検査（指摘ではない。データの不備で、充足とも違反とも決められなかった検査）
                    "withheld" to result.withheld.map { linkedMapOf("path" to it.path.pretty(), "message" to it.text) },
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
                    "withheld" to withheld.takeIf { it > 0 },
                ).filterValues { it != null }
            )
        }
        "readiness" -> {
            val ws = workspace()
            val report = Readiness.report(ws)
            val listed = if ("--pending" in rest) report.entities.filterNot { it.ready } else report.entities
            fun groupMaps(rows: List<Readiness.GroupRow>, key: String) =
                rows.map { linkedMapOf(key to it.key, "total" to it.total, "ready" to it.ready) }
            dumpYaml(
                linkedMapOf(
                    "schemaErrors" to ws.schemaErrors,
                    "summary" to linkedMapOf(
                        "entities" to report.entities.size,
                        "ready" to report.entities.count { it.ready },
                        "pending" to report.entities.count { !it.ready },
                        // 判定を保留した検査（データの不備で確かめられなかった検査）の件数。0 なら出さない
                        "withheld" to report.withheld.size.takeIf { it > 0 },
                    ).filterValues { it != null },
                    "byType" to groupMaps(report.byType, "type"),
                    "byPhase" to groupMaps(report.byPhase, "phase"),
                    "entities" to listed.map { row ->
                        linkedMapOf(
                            "id" to row.id,
                            "type" to row.entityType,
                            "title" to row.title,
                            "file" to row.file,
                            "phase" to row.phase,
                            "errors" to row.errors,
                            "warnings" to row.warnings,
                            "withheld" to row.withheld.takeIf { it > 0 },
                            "ready" to row.ready,
                        ).filterValues { it != null }
                    },
                    "unassigned" to report.unassigned.map {
                        linkedMapOf("file" to it.file, "errors" to it.errors, "warnings" to it.warnings)
                    },
                    // 判定を保留した検査の内訳（--pending でも全件を出す: 「エラー0」の要素にも、確かめられなかった検査がありうる）
                    "withheld" to report.withheld.map { (file, check) ->
                        linkedMapOf("file" to file, "id" to check.subjectId, "message" to check.text)
                    }.takeIf { it.isNotEmpty() },
                ).filterValues { it != null }
            )
        }
        "bench" -> {
            val sizes = (stringFlag(rest, "--modules") ?: "2,4,8,16").split(',').map { it.trim().toIntOrNull() ?: error("--modules には整数の並びを指定してください: $it") }
            val keep = stringFlag(rest, "--keep")?.let { Path.of(it) }
            // 1回目は JIT の暖機として捨てる（最小の大きさで1回まわす）
            run {
                val warm = Files.createTempDirectory("yde-bench-warm")
                try { Bench.measure(warm, Bench.generate(warm, sizes.min())) } finally { warm.toFile().deleteRecursively() }
            }
            var previous: Bench.Result? = null
            val rows = sizes.map { modules ->
                val dir = keep?.resolve("m$modules")?.also { Files.createDirectories(it) } ?: Files.createTempDirectory("yde-bench")
                try {
                    val result = Bench.measure(dir, Bench.generate(dir, modules))
                    val prev = previous
                    // 伸び方: 時間の伸び ÷ 文書数の伸び（1.0 前後なら文書数に比例。2.0 なら、規模が2倍で時間が4倍）
                    fun growth(now: Long, before: Long?): String? = prev?.let { p ->
                        if (before == null || before == 0L) null
                        else "%.2f".format((now.toDouble() / before) / (result.size.files.toDouble() / p.size.files))
                    }
                    previous = result
                    linkedMapOf(
                        "modules" to modules,
                        "files" to result.size.files,
                        "lines" to result.size.lines,
                        "entities" to result.size.entities,
                        "openMs" to result.openMs,
                        "validateMs" to result.validateMs,
                        "readinessMs" to result.readinessMs,
                        "validateMsPerFile" to "%.1f".format(result.validateMs.toDouble() / result.size.files),
                        "validateGrowth" to growth(result.validateMs, prev?.validateMs),
                        "openGrowth" to growth(result.openMs, prev?.openMs),
                        "errors" to result.errors,
                        "warnings" to result.warnings,
                    ).filterValues { it != null }
                } finally {
                    if (keep == null) dir.toFile().deleteRecursively()
                }
            }
            dumpYaml(linkedMapOf("bench" to rows))
        }
        "checks" -> {
            // 展開の透明性（P29 Phase 6・CALC-29）: 語彙（consistency など）と定義の使用（use）が、検証の前に
            // どんな基本の検査へ展開されたかを、由来つきで表示する。--definitions で使える定義と語彙の一覧も出す
            val ws = workspace()
            val only = rest.drop(1).firstOrNull { !it.startsWith("--") }
            val targets = ws.schemas.filter { it.expandedChecks.isNotEmpty() && (only == null || it.id == only) }
            val out = linkedMapOf<String, Any?>(
                "schemaErrors" to ws.schemaErrors,
                "schemas" to targets.map { s ->
                    linkedMapOf(
                        "schema" to s.id,
                        // 渡されなかった値（null）の項目は、読みやすさのため表示から省く
                        "checks" to s.expandedChecks.map { linkedMapOf("origin" to it.origin, "check" to pruneNulls(it.raw)) },
                    )
                },
            )
            // 特定のスキーマに属さない検査のうち、組込み定義体に直接書かれたもの（エンティティの整合など）
            if (only == null) {
                out["builtinChecks"] = dev.toros.yde.app.schema.SchemaLoader.builtinChecks.map {
                    linkedMapOf("origin" to it.origin, "check" to pruneNulls(it.raw))
                }
            }
            if ("--definitions" in rest) {
                val library = ws.schemas.fold(dev.toros.yde.app.schema.SchemaLoader.builtinLibrary) { acc, s ->
                    runCatching { acc + s.checkLibrary }.getOrDefault(acc)
                }
                out["definitions"] = linkedMapOf(
                    "functions" to library.functions.values.map {
                        linkedMapOf("id" to it.id, "params" to it.params, "definedIn" to it.origin)
                    },
                    "vocabulary" to library.words.map {
                        linkedMapOf("word" to it.word, "in" to it.location, "definedIn" to it.origin)
                    },
                )
            }
            dumpYaml(out)
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
        "entity" -> {
            val ws = workspace()
            val op = rest.getOrNull(1) ?: error("操作（show / rename / merge / delete）を指定してください")
            // 全体像の表示（R23-04。ARCHITECTURE §4.33）: エンティティページ・read_entity と同じ合成を返す
            if (op == "show") {
                val id = rest.getOrNull(2)?.takeIf { !it.startsWith("--") } ?: error("表示するIDを指定してください")
                val composite = Aspects.compose(id, ws.index, ws.schemas, ws.entityTypes, ws::treeOf)
                    ?: error("ID「$id」の出現が見つかりません")
                val idx = ws.index
                return dumpYaml(
                    linkedMapOf(
                        "id" to composite.id,
                        "type" to composite.entityType,
                        "typeLabel" to composite.typeLabel?.takeIf { it != composite.entityType },
                        "title" to composite.title,
                        "aspects" to composite.sections.map { s ->
                            linkedMapOf(
                                "aspect" to s.aspect,
                                "label" to s.label.takeIf { it != s.aspect },
                                "defining" to (s.entry.role == EntityRole.DEFINING).takeIf { it },
                                "file" to s.entry.document.value,
                                "path" to s.entry.path.pretty(),
                                "fields" to s.fields.map { f ->
                                    linkedMapOf(
                                        "key" to f.key,
                                        "value" to (f.value as? YNode.Scalar)?.value,
                                        "path" to f.path.pretty(),
                                        "duplicateOf" to f.duplicateOf?.let { "${it.first.value} :: ${it.second.pretty()}" },
                                    ).filterValues { it != null }
                                },
                            ).filterValues { it != null }
                        },
                        "unassigned" to composite.unassigned.map { "${it.document.value} :: ${it.path.pretty()}" }.ifEmpty { null },
                        "refs" to idx.outgoingRefs(id).map { r ->
                            linkedMapOf(
                                "to" to r.targetId,
                                "via" to r.viaKey,
                                "relation" to r.relation,
                                "attributes" to r.attributeSummary(),
                            ).filterValues { it != null }
                        }.ifEmpty { null },
                        "backlinks" to idx.backlinksTo(setOf(id)).map { b ->
                            linkedMapOf(
                                "from" to (idx.sourceEntryOf(b)?.id ?: b.document.value),
                                "file" to b.document.value,
                                "path" to b.path.pretty(),
                                "relation" to b.relation,
                                "attributes" to b.attributeSummary(),
                            ).filterValues { it != null }
                        }.ifEmpty { null },
                    ).filterValues { it != null }
                )
            }
            // 変更系（rename / merge / delete）は新CLI（cli モジュール）へ移動した（B-1・RB-01: 旧生成器 EntityOps の削除）
            error(
                "entity の変更操作は新CLIへ移動しました: " +
                    "./gradlew :cli:run --args=\"workspace refactor <workspace> rename-entity <old> <new> --apply\"" +
                    "（merge-entity / delete-entity も同様。ここで使えるのは entity show のみ）"
            )
        }
        "refactor" -> error(
            // スキーマ・リファクタリング（P25）は新CLI（cli モジュール）へ移動した（B-1・RB-01: 旧生成器 SchemaRefactor の削除）
            "refactor は新CLIへ移動しました: ./gradlew :cli:run --args=\"workspace refactor <workspace> <op> … --apply\"" +
                "（op: rename-field / move-field / align-order / rename-enum-value / delete-field / require-field / " +
                "convert-type / rename-definition / extract-definition / inline-definition / rename-relation / rename-schema-id）"
        )
        "move" -> error(
            // ファイル移動（R21-03）は新CLI（cli モジュール）へ移動した（B-1・RB-01: 旧 DocMove の削除）
            "move は新CLIへ移動しました: ./gradlew :cli:run --args=\"workspace refactor <workspace> move-document <old> <new> --apply\""
        )
        "activities" -> {
            val ws = workspace()
            val defs = Definitions.load(ws.root)
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
            val records = RunMetrics.collect(root)
            val filter = stringFlag(rest, "--activity")
            val summaries = RunMetrics.summarize(records)
                .filter { filter == null || it.activityId == filter }
            dumpYaml(RunMetrics.toYamlMap(summaries))
        }
        "eval" -> runEvalCommand(rest)
        "skills" -> {
            val ws = workspace()
            val loaded = Skills.load(ws.root)
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
            val loaded = Skills.load(ws.root)
            val def = loaded.skill(skillId) ?: error("skill「$skillId」が見つかりません（skills コマンドで確認できます）")
            val result = Exporter.run(ws, def)
            dumpYaml(linkedMapOf("file" to result.rel, "rows" to result.rows))
        }
        "run-skill" -> {
            // コード実行skill（R9-06）。明示許可制: --yes なしでは内容の表示だけを行い、実行しない
            val ws = workspace()
            val skillId = rest.getOrNull(1)?.takeIf { !it.startsWith("--") } ?: error("skill IDを指定してください")
            val loaded = Skills.load(ws.root)
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
                val outcome = Runner.run(ws, def, Runner.Approval.approve(runDef))
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
                        "templates" to TemplatePacks.all.map {
                            linkedMapOf("name" to it.name, "description" to it.description, "files" to it.files.size)
                        }
                    )
                )
            val pack = TemplatePacks.pack(name)
                ?: error("テンプレート「$name」が見つかりません（利用可能: ${TemplatePacks.all.joinToString(", ") { it.name }}）")
            val result = TemplatePacks.apply(dir, pack)
            dumpYaml(
                linkedMapOf(
                    "template" to pack.name,
                    "written" to result.written,
                    "skipped" to result.skipped.ifEmpty { null },
                ).filterValues { it != null }
            )
        }
        "bench" -> error(
            // 索引計測は新CLI（カーネルの意味解析計測）へ移動した（B-1・RB-01: 旧 Bench の削除）
            "bench は新CLIへ移動しました: ./gradlew :cli:run --args=\"workspace bench <workspace> --iterations 3\""
        )
        "migrate" -> {
            // レガシー構成 → .yde 構成への移行（R13-01）。ディレクトリ移動と構成ファイル作成のみで、
            // 文書・定義の中身は書き換えない（match 等の glob はワークスペース相対のため影響しない）
            val root = Path.of(rest.firstOrNull() ?: error("ワークスペースのパスを指定してください")).toAbsolutePath().normalize()
            require(Files.isDirectory(root)) { "ワークスペースが存在しません: $root" }
            val configDir = root.resolve(WorkspaceLayout.CONFIG_DIR)
            require(!Files.exists(configDir)) { "既に ${WorkspaceLayout.CONFIG_DIR}/ が存在します（移行済み）" }
            val ledger = stringFlag(rest, "--ledger")?.replace('\\', '/')?.trim('/')
            if (ledger != null) {
                require(!ledger.contains("..") && ledger.isNotBlank()) { "--ledger に使えないパスです: $ledger" }
                require(Files.isDirectory(root.resolve(ledger))) { "--ledger のディレクトリが存在しません: $ledger" }
            }
            Files.createDirectories(configDir)
            val moved = mutableListOf<String>()
            WorkspaceLayout.Zone.entries.forEach { zone ->
                val from = root.resolve(zone.legacyDir)
                if (Files.isDirectory(from)) {
                    val to = configDir.resolve(zone.ydeName)
                    Files.move(from, to)
                    moved += "${zone.legacyDir} -> ${WorkspaceLayout.CONFIG_DIR}/${zone.ydeName}"
                }
            }
            val configText = buildString {
                appendLine("# ワークスペース構成（R13-02。ARCHITECTURE §4.25）")
                appendLine("# ledger: 台帳ディレクトリの宣言（文字列またはリスト）。未宣言ならワークスペース全体が台帳。")
                if (ledger != null) appendLine("ledger: $ledger") else appendLine("# ledger: doc")
            }
            Files.writeString(configDir.resolve(WorkspaceLayout.CONFIG_FILE), configText)
            dumpYaml(
                linkedMapOf(
                    "configDir" to WorkspaceLayout.CONFIG_DIR,
                    "moved" to moved,
                    "ledger" to (ledger ?: "(未宣言: 全体が台帳)"),
                )
            )
        }
        "infer-schema" -> {
            // スキーマ推測（R11-01）: 既存文書からドラフトを推測し、表示または _schemas へ保存する
            val ws = workspace()
            val glob = rest.getOrNull(1)?.takeIf { !it.startsWith("--") }
                ?: error("対象文書の glob を指定してください（例 tables/**/*.yaml）")
            val regex = globToRegex(glob)
            val targets = ws.files
                .map { ws.relPath(it) to it }
                .filter { (rel, _) -> ws.layout.inLedger(rel) && regex.matches(rel) }
            val samples = targets.map { (rel, file) ->
                SchemaInference.Sample(rel, runCatching { YamlIO.parseFirst(Files.readString(file)) }.getOrNull())
            }
            val id = stringFlag(rest, "--id") ?: defaultInferredSchemaId(glob)
            val draft = SchemaInference.infer(samples, schemaId = id, match = glob)
            val yamlText = YamlIO.dump(draft)
            if ("--apply" !in rest) {
                yamlText
            } else {
                val rel = "${ws.layout.schemasDir}/$id.yaml"
                val file = ws.resolve(rel)
                require(!Files.exists(file)) { "既にスキーマファイルが存在します: $rel" }
                Files.createDirectories(file.parent)
                Files.writeString(file, yamlText)
                ws.refresh()
                dumpYaml(
                    linkedMapOf(
                        "file" to rel,
                        "documents" to targets.size,
                        "schemaErrors" to ws.schemaErrors.filter { it.startsWith("$id.yaml") },
                    )
                )
            }
        }
        "infer-relations" -> {
            // 関係レジストリの自動推測（R20-02）: 参照実績からドラフトを生成し、表示または _schemas へ保存する
            val ws = workspace()
            val id = stringFlag(rest, "--id") ?: "workspace-relations"
            val yamlText = inferRelationsDraft(ws, id)
            if ("--apply" !in rest) {
                yamlText
            } else {
                val rel = "${ws.layout.schemasDir}/$id.yaml"
                val file = ws.resolve(rel)
                require(!Files.exists(file)) { "既にレジストリファイルが存在します: $rel" }
                Files.createDirectories(file.parent)
                Files.writeString(file, yamlText)
                ws.refresh()
                dumpYaml(
                    linkedMapOf(
                        "file" to rel,
                        "relations" to ws.relations.keys.toList(),
                        "schemaErrors" to ws.schemaErrors.filter { it.startsWith("$id.yaml") },
                    )
                )
            }
        }
        "revert" -> {
            val ws = workspace()
            val runFile = rest.getOrNull(1)?.takeIf { !it.startsWith("--") }
                ?: error("実行記録（_runs/*.yaml）を指定してください")
            val result = AutoApply.revert(ws, ws.resolve(runFile), force = "--force" in rest)
            dumpYaml(linkedMapOf("ok" to result.ok, "messages" to result.messages))
        }
        else -> error("不明なコマンドです: $command\n$CLI_USAGE")
    }
}

/**
 * 関係レジストリのドラフト生成（R20-02。ARCHITECTURE §4.30）。
 * 索引の全参照から relation ごとに viaKey・参照元／先のエンティティ型の実績を集計する。
 * 生成物は必ず `SchemaLoader.parseSchemaText` → `validateDefinition` を通ること（R11-01 と同じ入口保証）。
 */
internal fun inferRelationsDraft(ws: Workspace, registryId: String = "workspace-relations"): String {
    val byRelation = ws.index.refs.filter { it.relation != null }.groupBy { it.relation!! }
    require(byRelation.isNotEmpty()) {
        "relation を宣言した参照が索引にありません（スキーマの ref フィールドに relation を宣言してから実行してください）"
    }
    fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val text = buildString {
        appendLine("# 関係レジストリのドラフト（infer-relations が参照実績から生成。R20-02）")
        appendLine("# inverseLabel（被参照方向の表示ラベル）は TODO を実際の語彙に書き換えてください。")
        appendLine("id: $registryId")
        appendLine("kind: relations")
        appendLine("relations:")
        byRelation.keys.sorted().forEach { name ->
            val refs = byRelation.getValue(name)
            val viaKeys = refs.map { it.viaKey }.distinct().sorted()
            val sources = refs.mapNotNull { ws.index.sourceEntryOf(it)?.entityType }.distinct().sorted()
            val targets = refs.flatMap { r -> ws.index.occurrences(r.targetId).mapNotNull { it.entityType } }
                .distinct().sorted()
            appendLine("  - name: ${quote(name)}")
            appendLine("    label: ${quote(name)}")
            appendLine("    # TODO: inverseLabel を被参照方向の語彙に書き換える（例: 〜される）")
            appendLine("    inverseLabel: ${quote("$name（被参照）")}")
            if (sources.isNotEmpty()) appendLine("    source: [${sources.joinToString(", ") { quote(it) }}]")
            if (targets.isNotEmpty()) appendLine("    target: [${targets.joinToString(", ") { quote(it) }}]")
            appendLine("    description: ${quote("実績: 参照 ${refs.size} 件・キー: ${viaKeys.joinToString(", ")}")}")
        }
    }
    // 入口保証: 生成物がメタスキーマ検証（レジストリの定義検証）を通ることを生成段階で確認する
    val parsed = dev.toros.yde.app.schema.SchemaLoader.parseSchemaText(text, Path.of("_schemas/$registryId.yaml"))
    val issues = parsed.validateDefinition()
    require(parsed.kind == SchemaKind.RELATIONS && issues.isEmpty()) {
        "生成したドラフトが定義検証を通りません: ${issues.joinToString("; ") { it.message }}"
    }
    return text
}

/** glob からスキーマIDの既定値を作る（最初のワイルドカードより前の最後のパス要素。R11-01）。 */
internal fun defaultInferredSchemaId(glob: String): String {
    val prefix = glob.takeWhile { it !in "*?{[" }
    val segment = prefix.trimEnd('/').substringAfterLast('/').substringBeforeLast('.')
    return segment.ifBlank { "inferred-schema" }
}

/** 環境変数に設定されているAPIキーをプロバイダごとに集める（delegate や watch で使う）。 */
private fun envApiKeys(): Map<AiProvider, String> =
    AiProvider.entries.associateWith { System.getenv(it.envVar).orEmpty() }.filterValues { it.isNotBlank() }

/** 自動実行エンジンをヘッドレスで起動する（R3-01〜05）。 */
private fun runWatchCommand(rest: List<String>): String {
    val dir = Path.of(rest.firstOrNull() ?: error("ワークスペースのパスを指定してください"))
    val provider = stringFlag(rest, "--provider")?.let { flag ->
        AiProvider.entries.firstOrNull { it.name.equals(flag, ignoreCase = true) } ?: error("未知のプロバイダです: $flag")
    } ?: AiProvider.OPENAI
    val keys = envApiKeys()
    val engine = AutomationEngine(
        dir,
        credentials = { AutomationEngine.Credentials(provider = provider, apiKeys = keys) },
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
    val loaded = Skills.load(ws.root)
    if (loaded.errors.isNotEmpty()) System.err.println("定義の警告:\n" + loaded.errors.joinToString("\n") { "- $it" })
    val def = loaded.skill(skillId) ?: error("skill「$skillId」が見つかりません（skills コマンドで確認できます）")

    val preview = Importer.build(ws, def, sourceFile)
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
    val loaded = Evals.load(root)
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
        AiProvider.entries.firstOrNull { it.name.equals(flag, ignoreCase = true) } ?: error("未知のプロバイダです: $flag")
    } ?: AiProvider.OPENAI
    val runner = EvalRunner(
        credentials = { AutomationEngine.Credentials(provider = provider, apiKeys = envApiKeys()) },
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
    val defs = Definitions.load(ws.root)
    if (defs.errors.isNotEmpty()) System.err.println("定義の警告:\n" + defs.errors.joinToString("\n") { "- $it" })
    val activity = defs.activity(activityId) ?: error("アクティビティ「$activityId」が見つかりません（activities コマンドで確認できます）")
    val agentDef = defs.agent(activity.agentId) ?: error("エージェント「${activity.agentId}」が見つかりません")

    val provider = stringFlag(rest, "--provider")?.let { flag ->
        AiProvider.entries.firstOrNull { it.name.equals(flag, ignoreCase = true) } ?: error("未知のプロバイダです: $flag")
    } ?: agentDef.provider ?: AiProvider.OPENAI
    // エージェント定義のモデルIDは、その定義のプロバイダと一致する場合のみ引き継ぐ
    // （--provider で上書きしたときに他社のモデルIDを送らない）
    val pinnedModel = agentDef.model?.takeIf { agentDef.provider == null || agentDef.provider == provider }
    val model = stringFlag(rest, "--model") ?: pinnedModel ?: provider.defaultModel
    val apiKey = System.getenv(provider.envVar).orEmpty()
    require(apiKey.isNotBlank()) { "環境変数 ${provider.envVar} にAPIキーを設定してください" }

    val host = object : AgentHost {
        override suspend fun askHuman(question: String): String? {
            System.err.println("[質問] $question（非対話実行のため回答できません）")
            return null
        }
        override suspend fun onEvent(event: AgentEvent) {
            val line = when (event) {
                is AgentEvent.TurnStarted -> "--- ターン ${event.turn}/${event.maxTurns}"
                is AgentEvent.AssistantText -> event.text.lineSequence().joinToString("\n") { "  $it" }
                is AgentEvent.ToolInvoked -> "  [tool] ${event.name} ${event.summary}"
                is AgentEvent.ToolFailed -> "  [tool失敗] ${event.name}: ${event.message}"
                is AgentEvent.QuestionAsked -> "  [質問] ${event.question}"
                is AgentEvent.PlanUpdated -> "  [計画] ${event.steps.joinToString(" / ")}"
                is AgentEvent.Finished -> "=== 終了: ${event.status}${event.message?.let { " — $it" } ?: ""}"
            }
            System.err.println(line)
        }
    }
    // 一括実行（R18-01）: foreach で対象を列挙し、1件=1実行で直列に回す。
    // --apply 併用時は1件ごとに適用し、後続の項目のワークスペースから先行の成果が見えるようにする
    if ("--foreach" in rest) {
        val foreachDef = activity.foreach
            ?: error("アクティビティ「${activity.id}」に foreach 定義がありません")
        val userInstruction = stringFlag(rest, "--instruction")
        val apply = "--apply" in rest
        val enumeration = Foreach.enumerate(ws, foreachDef)
        if (enumeration.items.isEmpty()) {
            return dumpYaml(linkedMapOf("total" to enumeration.total, "executed" to 0, "message" to "対象がありません"))
        }
        val itemResults = mutableListOf<Map<String, Any?>>()
        enumeration.items.forEachIndexed { i, item ->
            System.err.println("=== 一括 ${i + 1}/${enumeration.items.size}: ${item.label}")
            val itemWs = Workspace.open(ws.root)
            val request = AgentRunRequest(
                activity = activity, agent = agentDef,
                provider = provider, model = model, apiKey = apiKey,
                extraInstruction = (listOfNotNull(userInstruction) + item.instruction).joinToString("\n"),
                apiKeys = envApiKeys(),
                subAgents = defs.agents,
                targetValues = item.values,
            )
            val result = kotlinx.coroutines.runBlocking { AgentKernel.run(itemWs, request, host) }
            val itemRunFile = RunLog.write(itemWs, request, result)
            var appliedOk: Boolean? = null
            if (apply && result.status == RunStatus.SUBMITTED && result.changeSet.changes.isNotEmpty()) {
                appliedOk = itemWs.apply(result.changeSet).ok
            }
            itemResults += linkedMapOf(
                "target" to item.key,
                "status" to result.status.name,
                "message" to result.message,
                "applied" to appliedOk,
                "changes" to result.changeSet.changes.map { it.file },
                "runLog" to itemWs.relPath(itemRunFile),
            )
        }
        return dumpYaml(
            linkedMapOf(
                "total" to enumeration.total,
                "executed" to enumeration.items.size,
                "items" to itemResults,
            )
        )
    }

    val request = AgentRunRequest(
        activity = activity, agent = agentDef,
        provider = provider, model = model, apiKey = apiKey,
        extraInstruction = stringFlag(rest, "--instruction"),
        apiKeys = envApiKeys(),
        subAgents = defs.agents,
    )
    val result = kotlinx.coroutines.runBlocking { AgentKernel.run(ws, request, host) }
    val runFile = RunLog.write(ws, request, result)

    val changeSetYaml = changeSetToYaml(result.changeSet)
    stringFlag(rest, "--out")?.let { Files.writeString(Path.of(it), changeSetYaml) }
    var appliedOk: Boolean? = null
    if ("--apply" in rest && result.status == RunStatus.SUBMITTED && result.changeSet.changes.isNotEmpty()) {
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
                    is PatchOp.RenameKey -> linkedMapOf("op" to "rename", "path" to op.path.pretty(), "to" to op.newName)
                    is PatchOp.ReorderKeys -> linkedMapOf("op" to "reorder", "path" to op.path.pretty(), "order" to op.order)
                }
            },
        ).filterValues { it != null }
    }
    return YamlIO.dump(YamlIO.fromAny(linkedMapOf("changes" to changes)))
}

private fun issueMap(issue: Issue, withSource: Boolean = false): Map<String, Any?> = linkedMapOf(
    "severity" to issue.severity.name,
    "path" to issue.path.pretty(),
    "message" to issue.message,
    "source" to issue.source?.takeIf { withSource }?.let { source ->
        linkedMapOf(
            "rule" to source.rule?.key,
            "id" to source.ruleId,
            "kind" to source.kind,
            "subject" to source.subjectId,
            // 対象の要素の中の場所（配列の番号を除いたキーの並び。コードで実装された検査の指摘だけが持つ）
            "within" to source.within?.takeIf { it.isNotEmpty() },
            "delivery" to source.delivery.name,
            "evidence" to source.evidence.takeIf { it.isNotEmpty() },
        ).filterValues { it != null }
    },
).filterValues { it != null }

private fun stringFlag(args: List<String>, name: String): String? =
    args.indexOf(name).takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }

private fun stringFlags(args: List<String>, name: String): List<String> =
    args.indices.filter { args[it] == name && it + 1 < args.size }.map { args[it + 1] }

private fun intFlag(args: List<String>, name: String): Int? =
    stringFlag(args, name)?.let { it.toIntOrNull() ?: error("$name には整数を指定してください: $it") }

/**
 * 値が無い（null）項目を、入れ子も含めて取り除く（CLI の表示用）。中身が全て無くなったマップは、それ自体を「無い」として扱う
 * （例: 渡されなかった既定値 `{value: $default}` は `first` の選択肢から消える——評価の結果は変わらない）。
 */
private fun pruneNulls(value: Any?): Any? = when (value) {
    is Map<*, *> -> value.entries
        .mapNotNull { e -> pruneNulls(e.value)?.let { e.key to it } }
        .toMap(LinkedHashMap())
        .takeIf { it.isNotEmpty() || value.isEmpty() }
    is List<*> -> value.mapNotNull { pruneNulls(it) }
    else -> value
}

private fun dumpYaml(value: Any?): String = YamlIO.dump(YamlIO.fromAny(value))
