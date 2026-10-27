package dev.toros.yde.cli

import dev.toros.yde.app.core.BuildInfo
import dev.toros.yde.app.core.Workspace
import dev.toros.yde.app.validate.Issue
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.pretty
import java.nio.file.Path
import dev.toros.yde.cli.commands.*

/**
 * 応用の命令（R1-01 のヘッドレス CLI: 探索・検索・検証・操作・エージェント・入出力・初期化・推測）の入口。
 * 命令ごとの本体は commands/ にあり、ここは使い方と名前での振り分け、共通の助け（引数の読み方・YAML への書き方）だけ。出力は原則 YAML（機械可読）。
 * カーネルの命令（yaml … / workspace …）は Main.kt の run にある。実行例: ./gradlew runCli --args="catalog samples/todos"
 */

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
  report   <workspace> [<id>] [--param 名前=値]... [--out <file>] [--print]
           レポート（.yde/reports の定義）を作る: 変換で値の一式を作り（ビューの行も受け取れる）、ひな形（Markdown など）にはめ込む。
           引数なしで一覧。--out は出力先（ワークスペース相対。省略時は定義の output）。--print で書かずに表示する
  transform <workspace> <定義.yaml> [--input <file>] [--param 名前=値]... [--origins]
           YAML から YAML への変換（P27 段C。試作台）: 定義（from＝クエリで選ぶ／build＝出力のひな型／rules＝入れ子に当てる規則）を
           評価して、組み立てた YAML を返す。from を書かない定義は --input の文書1つが入力（ワークスペース相対か、ファイルのパス）。
           --param の値は YAML として読む（例: --param "reachedPhases=[基本設計, 詳細設計]"）。--origins で、出力の各値が
           入力のどこから来たかを添える。出力は保存しない（原本は変わらない）
           定義に「builtin:structural-schema」と書くと、組込みの変換定義（構造のスキーマの導出）を使う
           （例: transform <workspace> builtin:structural-schema --input _schemas/x.yaml＝そのスキーマから導出される、最小の構造のスキーマ）
  view     <workspace> [view-id] [file]         ビュー（_views）を解決する。横断グリッドは行×列、
           文書ビュー（kind: document）は file で対象文書を指定（適用対象1件なら省略可）。
           ダッシュボード（kind: dashboard）は --date YYYY-MM-DD で基準日を指定（省略時は本日）。共有の入力（inputs）は --param 名前=値（省略時は定義の default。空文字で絞らない）。
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
  action   <workspace> [<id> | <型名.操作名>] [--on <ID | file>] [--param 名前=値]... [--apply] [--allow-errors]
           操作（P41）: スキーマに定義した操作（actions＝横断の操作・methods＝型の操作）を実行する。引数なしで使える操作の一覧。
           既定は計画だけ（変更セットと、適用後の姿の検証の結果を表示。原本は変わらない）。--apply で原本へ書き込む
           （検証エラーが残るなら書かない。--allow-errors で書く）。--on は受け手（エンティティの ID か、文書の相対パス）。
           --param の値は YAML として読む（例: --param "derived_from=[UA-003]"）。前提（require）に合わなければ理由を出して止まる
  entity   <workspace> show <id>                エンティティ全体像（R23-04）: アスペクト合成（所有フィールド
           と原本座標）・関係・被参照をYAMLで返す（エンティティページ・read_entity と同じ合成）
           ※ 変更系（rename / merge / delete）・move・refactor はカーネルの命令 workspace refactor で行う:
             ./gradlew runCli --args="workspace refactor <workspace> <op> … --apply"
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
  init     <dir> [--template name] [--answers file.yaml] [--param 名前=値]... [--no-sample-data] [--adopt] [--ledger dir]... [--dry-run]
           新しいワークスペースを作る（1 つのフォルダに 1 度だけ）。--template でテンプレート（templates/）を展開し、
           問いを持つテンプレート（basic-list＝自分の項目で）には --answers か --param で答えを渡す。--adopt は既存の
           フォルダへの後入れ（.yde/workspace.yaml の台帳の宣言だけ）。省略時はテンプレートの一覧。既存のファイルは上書きしない
  infer-schema <workspace> <glob> [--id schema-id] [--apply]
           既存YAML文書（glob。複数文書はマージ）からスキーマのドラフトを推測する（R11-01）。
           既定は標準出力へドラフトを表示。--apply で _schemas/<id>.yaml へ保存する（既存は上書きしない）
  infer-relations <workspace> [--id registry-id] [--apply]
           索引の全参照から relation・viaKey・参照元／先のエンティティ型の実績を集計し、
           関係レジストリ（kind: relations）文書のドラフトを生成する（R20-02）。
           既定は標準出力へ表示。--apply で _schemas/<id>.yaml へ保存する（既存は上書きしない）
  schema-graph <workspace>
           スキーマと関係レジストリから、型（箱）・関係（線）・保有の宣言（入れ子）を導いて表示する
           （スキーマの図＝レンズの「型」の段と同じ材料。線の出どころ＝宣言／導出と、線にできなかった参照項目の理由も出す）
  migrate  <workspace> [--ledger dir]           レガシー構成（ルートの _schemas 等）を .yde 構成へ移行する（R13-01）。
           予約ディレクトリを .yde/ 配下へ移動し .yde/workspace.yaml を作成する。
           --ledger で台帳ディレクトリを宣言（未指定なら全体が台帳＝従来と同じ検証範囲）
  --- カーネルの命令（3 層コアだけで動く。workspace 系は --format text|yaml|json で機械可読出力を選べる） ---
  yaml parse <file>                             YAML をパースしてドキュメント数を表示する
  yaml roundtrip <file>                         parse → dump → parse の往復一致を確認する
  workspace validate <root>                     カーネルの検証（スキーマの定義・構造・意味・検査）
  workspace apply <root> <changeset.yaml> [--dry-run] [--require-valid]
                                                変更セットの適用（競合の判定 → 適用後の姿の検証 → 保存）
  workspace catalog <root>                      スキーマと文書の一覧・割当
  workspace entity <root> <id>                  エンティティの出現・被参照・出参照
  workspace refactor <root> <op> … [--apply]    リファクタリング計画の表示と適用（op: rename-entity / merge-entity / delete-entity /
                                                move-document / rename-field / move-field / align-order / rename-enum-value / delete-field /
                                                require-field / convert-type / rename-definition / extract-definition / inline-definition /
                                                rename-relation / rename-schema-id）
  workspace bench <root> [--iterations N]       意味解析の全再構築と増分更新を計測する
  workspace shell <root>                        対話 shell（set / refactor / undo / redo / save / reload）
  --- 共通 ---
  version                                       バージョン（appVersion＝外部・buildVersion＝内部 yyyymmdd.ビルド番号・commit）
  help                                          このヘルプ
"""

/** 応用の命令を名前で振り分ける（テストと入口が呼ぶ）。help は使い方、不明な命令は使い方を添えて断る。 */
fun runCli(args: Array<String>): String {
    if (args.isEmpty() || args[0] == "help" || args[0] == "--help") return CLI_USAGE
    val command = args[0]
    val rest = args.drop(1)
    return when (command) {
        "version" -> versionText()
        "catalog" -> catalogCommand(rest)
        "outline" -> outlineCommand(rest)
        "read" -> readCommand(rest)
        "bundle" -> bundleCommand(rest)
        "traverse" -> traverseCommand(rest)
        "query" -> queryCommand(rest)
        "report" -> reportCommand(rest)
        "transform" -> transformCommand(rest)
        "search" -> searchCommand(rest)
        "view" -> viewCommand(rest)
        "validate" -> validateCommand(rest)
        "readiness" -> readinessCommand(rest)
        "bench" -> benchCommand(rest)
        "checks" -> checksCommand(rest)
        "action" -> actionCommand(rest)
        "apply" -> applyCommand(rest)
        "hash" -> hashCommand(rest)
        "entity" -> entityCommand(rest)
        "refactor" -> refactorCommand(rest)
        "move" -> moveCommand(rest)
        "activities" -> activitiesCommand(rest)
        "agent" -> agentCommand(rest)
        "watch" -> watchCommand(rest)
        "metrics" -> metricsCommand(rest)
        "eval" -> evalCommand(rest)
        "skills" -> skillsCommand(rest)
        "import" -> importCommand(rest)
        "export" -> exportCommand(rest)
        "run-skill" -> runSkillCommand(rest)
        "init" -> initCommand(rest)
        "migrate" -> migrateCommand(rest)
        "infer-schema" -> inferSchemaCommand(rest)
        "infer-relations" -> inferRelationsCommand(rest)
        "schema-graph" -> schemaGraphCommand(rest)
        "revert" -> revertCommand(rest)
        else -> error("不明なコマンドです: $command\n$CLI_USAGE")
    }
}

internal fun versionText(): String = "appVersion: ${BuildInfo.appVersion}\nbuildVersion: ${BuildInfo.buildVersion}\ncommit: ${BuildInfo.commit}\n"

/** 最初の引数をワークスペースとして開く。 */
internal fun workspaceOf(rest: List<String>): Workspace {
    val dir = rest.firstOrNull() ?: error("ワークスペースのパスを指定してください")
    return Workspace.open(Path.of(dir))
}

internal fun issueMap(issue: Issue, withSource: Boolean = false): Map<String, Any?> = linkedMapOf(
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

internal fun stringFlag(args: List<String>, name: String): String? =
    args.indexOf(name).takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }

internal fun stringFlags(args: List<String>, name: String): List<String> =
    args.indices.filter { args[it] == name && it + 1 < args.size }.map { args[it + 1] }

internal fun intFlag(args: List<String>, name: String): Int? =
    stringFlag(args, name)?.let { it.toIntOrNull() ?: error("$name には整数を指定してください: $it") }

/**
 * 値が無い（null）項目を、入れ子も含めて取り除く（CLI の表示用）。中身が全て無くなったマップは、それ自体を「無い」として扱う
 * （例: 渡されなかった既定値 `{value: $default}` は `first` の選択肢から消える——評価の結果は変わらない）。
 */
internal fun pruneNulls(value: Any?): Any? = when (value) {
    is Map<*, *> -> value.entries
        .mapNotNull { e -> pruneNulls(e.value)?.let { e.key to it } }
        .toMap(LinkedHashMap())
        .takeIf { it.isNotEmpty() || value.isEmpty() }
    is List<*> -> value.mapNotNull { pruneNulls(it) }
    else -> value
}

internal fun dumpYaml(value: Any?): String = YamlIO.dump(YamlIO.fromAny(value))
