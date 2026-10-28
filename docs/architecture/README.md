# 設計（アーキテクチャ）

今の姿だけを書く。主題ごとに 1 ファイルで、各ファイルは「概要 → 部品 → 流れ → 規律 → 拡張ポイント」の型。
経緯は [archive/](../archive/README.md) と git log にある。他の文書やコードからは「ファイル名＋見出しの文言」で参照する（例: `architecture/checks.md「4 つの結果」`）。

## 主題の目次

| ファイル | 内容 |
|---|---|
| [yaml-model.md](yaml-model.md) | YAML の木・YPath・Slot・コメントと表記の保持・複数ドキュメント・変更の基礎命令 |
| [editing-history.md](editing-history.md) | 編集の経路・履歴と dirty・履歴の合体・ソースとの同期・文書の作成と削除 |
| [index-references.md](index-references.md) | 索引の二層・オーバーレイ・ID と参照の意味・場所は文書ID・表示解決 |
| [schema.md](schema.md) | スキーマの読み込み・`$ref` と実効フィールド・フェーズ・推測とメタスキーマと派生・構造スキーマの導出・値の式・リファクタリング・ひな型 |
| [auto-values.md](auto-values.md) | 保存時に自動で入る値（印 `auto`＝更新日・作成日・版）: 計画は Rewrite・当てる場所は保存の直前と変更セットの適用と新しい要素 |
| [graph-query.md](graph-query.md) | エンティティ型と関係レジストリ・導出グラフ・クエリコア・線の意味・エンティティ操作・エンティティページ |
| [checks.md](checks.md) | 定義層の 3 段・検査の書き方・専用チェッカー・4 つの結果・指摘の出どころ・評価は 1 回・配り方・文書 1 つの検証 |
| [transform.md](transform.md) | YAML から YAML への変換・計算列と決定論的射影 |
| [views.md](views.md) | ビュー機構・文書ビュー・definition 参照とリント・ビュー定義の読み取り |
| [dashboards.md](dashboards.md) | 業務の入口・区画の件数と一覧・対象の詳細と操作・基準日と選択の保持 |
| [workspace.md](workspace.md) | ワークスペース構造と layout・ワークスペースの初期化（テンプレートとウィザード）・文書キャッシュと Workspace・外部変更の取り込み・変更セットの適用・セッションの fork |
| [actions.md](actions.md) | 操作（型の操作 `methods` とアクション `actions`。受け手・受け取った値・前提・手・変更セットへの計画・CLI） |
| [agents.md](agents.md) | 安全性・エージェント実行と Staging・自動実行・自己改善・AI 生成・AI 接続・コンテキスト・一括実行・skill |
| [ui.md](ui.md) | UI シェル・フォームと FormCtx・閲覧専用・テーブル表示・畳みとハイライト・エラー処理 |
| [ui-interactions.md](ui-interactions.md) | UI 操作規則の適用範囲・例外・入力からの退出・項目説明 |

## 技術スタック

Windows ZIP の構成と検査経路は [配布手順](../releasing.md) にまとめる。アプリ本体と専用 Java ランタイムを一式として扱う。

- Kotlin (JVM 17) + Compose for Desktop + Material 3
- YAML 入出力: SnakeYAML（Node API でコメント・表記を保持）
- AI 連携: Java 11 HttpClient + kotlinx-serialization-json（外部 SDK 不使用）。HttpClient の生成は `agent/llm/Http.kt` の単一ファクトリ
- Excel・Word 入出力: Apache POI（skill のインポート・エクスポートのみ）
- 永続化: `java.util.prefs.Preferences`（UI レイアウト・テーマ・履歴・AI モデル設定）
- ビルド: Gradle（Wrapper 同梱）マルチモジュール。root はビルド全体の設定だけでソースを置かない（モジュールの一覧は下の表）。テスト: kotlin.test
- ヘッドレス CLI: `./gradlew runCli --args="…"`（[manual/cli.md](../manual/cli.md)）

## モジュールと依存方向

パッケージは `dev.toros.yde.<モジュール>.*`（規則は CONVENTIONS「モジュールの規約」）。依存方向は ADR-0002（app-core は ADR-0013）が正: `yaml-core ← semantic-core ← workspace-core ← app-core ← agent ← desktop`（cli は app-core・agent・拡張まで・拡張 format-*・view-* は app-core まで）。repo-tests はテストだけのモジュールで、全モジュールに依存してよい。
構成（画面を持たない応用層 app-core・agent・入口 desktop／cli／mcp・形式とビューの種類の拡張）は ADR-0013（ビューの種類は ADR-0014）が正。入口の mcp はまだ無い。
文書とコメントでは接頭辞を省いて `schema.FieldDef`・`validate/Validator.kt` のように書き、同じ短い名前が複数のモジュールにあるとき（`schema`・`validate`・`core`）はモジュール名を添える。

| モジュール | パッケージ | 中身 |
|---|---|---|
| yaml-core | `yaml` | `YamlIO` / `YamlCodec`: SnakeYAML との相互変換 |
| | `yaml.model` | `YNode`・`YPath`・`YDocument`・`DocumentId`（依存なし） |
| | `yaml.edit` | `YamlOperation`（変更の基礎命令＝内部の正準） |
| | `yaml.patch` | `PatchOp` / `Patcher`（YPath パッチ＝外部との交換形式） |
| | `yaml.structure` | 最小の構造のスキーマとその検証（`StructureSchema` / `StructureValidator`）・`UnknownFieldsPolicy` |
| | `yaml.validate` | `Severity`・`StructuralIssue`・`StructuralIssueKind` |
| semantic-core | `semantic` | 意味解析の評価器（`SemanticEngine`・走査・グラフ・クエリ・検査の判定・書き換え・変換・仮想文書） |
| | `semantic.schema` | 利用者が書くスキーマの宣言（`FieldDef`・`SchemaTree`・`EntityDecl`・`QuerySpec`・検査の定義・変換定義・ひな型） |
| workspace-core | `workspace` | セッション・ストア・読み込み・スキーマ文書の読み取り・検査の定義の読み込み・変更セットの適用・ひな型の文書・ビュー定義・ビューの種類の差し込み口（`ViewKind`）とメタスキーマの断片の合成・リファクタリングの影響 |
| | `workspace.layout` | `WorkspaceLayout`（配置の判定） |
| | `workspace.changeset` | `ChangeSet` / `FileChange` / `ChangeSets` |
| | `workspace.validate` | 文書の検証の組み立てと検査の配り方（`DocumentValidator`・`WorkspaceChecks`・`CodedChecks`・`IssueSource`） |
| cli | `cli` | 1 つの CLI（`cli.MainKt`）。カーネルの命令（`yaml …` / `workspace …`。Commands.kt・CliShell.kt）と応用の命令（`AppCli.kt` と `cli.commands`＝命令ごとのファイル）。app-core・agent・拡張に依存し、引数の解釈と表示（全命令の `--format`。応用の命令は値か文字の `CliResult` を返し `render` が書き分ける）だけを持つ |
| app-core | `app` | 応用層の中核（画面無し。ADR-0013）: ワークスペースを開き・読み・検証し・変える窓口と、開いている文書の集合、索引とスキーマの窓口、読み出し、入出力変換。Compose・`java.util.prefs`・AI・POI に依存しない（Gradle の依存で止める） |
| | `app.core` | ワークスペースの窓口（`Workspace`）・文書キャッシュ・ファイル監視・読み出しモデル（`Traverse` / `Disclosure` / `Search` / `Aspects` / `Views`）・規模の計測 |
| | `app.index` | 索引のファサード（`IdIndex`。カーネルの型の別名）・クエリの窓口 |
| | `app.schema` | `SchemaDef`（カーネルのスキーマにファイルの場所を足す薄い型）・`SchemaLoader`・表示解決 `Display`・ID 候補・スキーマ推測 |
| | `app.validate` | `Validator`（窓口）・`Readiness` |
| | `app.tools` | ツールの一式（ADR-0013「窓口の形」）: 中核のツール `Toolset`・`Tool`・`ToolContext`／`ToolPolicy`・作業コピー `WorkingCopy`・読み出しの文 `Readouts`・JSON の値と JSON Schema の変換（`JsonValues` / `JsonSchemas`）。入口（agent の LLM 宣言・MCP・CLI）が包む |
| | `app.ext` | 拡張の差し込み口と登録簿（`FormatCodec`・`ExtensionRegistry`／`Extensions`・表 `Table`）。入口が `Extensions.install` で一覧を渡す（自動発見はしない）。登録したビューの種類（`Extensions.viewKinds`。差し込み口はカーネルの `ViewKind`）もここから読む |
| | `app.session` | セッション側の状態（Compose 非依存）: `DocumentSession`（コアとカーネルの保持・索引の写し・開いている文書・未保存編集を重ねた索引と派生・編集の記録・開き直しと同期）と 1 文書の論理 `DocumentState`。再読込・保存・履歴の一手・全体検証と検索・一括変更・リファクタリング・文書の作成・配置の計画と前後・操作（アクション）の論理もここ。画面側に残るのは開く手順と場所・通知・パネルの作業 |
| | `app.text` | 純粋な文字の計算（行の差分と 3 者併合 `LineMerge`・履歴の値を優先した書式の差し込み `SourceHistorySync`） |
| | `app.skill` | 入出力変換（skill 定義・表形式の読み書き・インポート・エクスポート） |
| agent | `agent` | エージェントの定義（`Definitions`＝`_agents` / `_activities`・`Evals`＝`_evals` の読み込み・`ForeachDef`）。書き込みはステージング → 変更セット → app-core の適用だけ。パッケージは llm → 定義 → run → auto → eval の一方向で、規則テストが守る |
| | `agent.run` | 1 回の実行: カーネル `AgentKernel`・ツールを LLM 向けに包む `AgentToolbox`・ステージング `Staging`（作業コピー＋定義文書のゲート）・実行記録 `RunLog`・AI 生成の実行 `GenerationRun`・土台の型（`AgentHost`・`RunStatus`・`ContextSection`） |
| | `agent.auto` | 自動実行: `AutomationEngine`（トリガ・キュー・承認・連鎖・リトライ）・`AutoApply`（巻き戻せる自動適用）・`Foreach`（一括実行の対象の列挙） |
| | `agent.eval` | 評価と指標: `EvalRunner`（評価セット・比較実験）・`RunMetrics`（実行記録の集計） |
| | `agent.llm` | LLM との接続（`AiGenerator`・各プロバイダの生成器・`Connection`・`Http`・ツール呼び出しの往復 `ToolChat`） |
| format-text | `format.text` | 拡張: 文字の形式（csv・json・markdown・html）の `FormatCodec`（`TextFormats.kt`） |
| format-xlsx | `format.xlsx` | 拡張: Excel の表（Apache POI）の `FormatCodec`（`XlsxFormat.kt`） |
| format-docx | `format.docx` | 拡張: Word 文書の中の表（Apache POI。読むだけ）の `FormatCodec`（`DocxFormat.kt`） |
| view-chart | `view.chart` | 拡張: ビューの種類 `kind: chart` の `ViewKind`（`ChartViewKind.kt`）と、描画が読む定義と結果の型（`ChartViews.kt`・`ChartSelection.kt`）。定義の形の断片は資源 `view-chart/schema.yaml` |
| view-flow | `view.flow` | 拡張: ビューの種類 `kind: flow` の `ViewKind`（`FlowViewKind.kt`）と、描画が読む定義・図のモデル・選択・表示の設定（`FlowViews.kt`・`FlowSelection.kt`・`FlowDisplayOptions.kt`）。CLI・read_view の値は `FlowReports.kt`。資源は断片 `view-flow/schema.yaml` と図の構造 `view-flow/flow-model.yaml` |
| desktop | `desktop` | `Main.kt`（エントリポイント・ウィンドウ・ショートカット・スタート画面）・`AppState` / `OpenDocument` |
| | `desktop.ui` | Compose の画面一式（骨格は `ui/Shell.kt`）。拡張の種類のビューの描画の一覧 `ViewRenderers` もここ（ADR-0014） |
| | `desktop.ext` | 拡張の登録の一覧（`DesktopExtensions`。入出力の形式 6 つとビューの種類 2 つ）。入口が `Extensions.install` に渡す 1 ファイル |
| repo-tests | `app.rules` ほか | テストだけ（main は無い）。規則テスト（`rules/SourceRulesTest`・`rules/ModuleRules`・`rules/DocReferencesTest`）と、入口（desktop・cli）や拡張の一覧をまたぐテスト（CLI と画面の結果の突き合わせ・テンプレート・取り込み・家計簿の試作）。パッケージは試す対象のもの（`app.core` など） |

応用層の中の依存は `app.ext`（登録簿。カーネルだけを使う）→ `app.core` → `app.session` → `app.tools` → `app.skill`（app-core）→ `agent.llm` → `agent` → `agent.run` → `agent.auto` → `agent.eval`（agent）→ `desktop`（AppState・`desktop.ext`）→ `desktop.ui` の一方向。`app.index` / `app.schema` / `app.validate` / `app.text` は `app.core` より下。
app-core（`app.core` / `app.index` / `app.schema` / `app.validate` / `app.text` / `app.session` / `app.tools` / `app.ext` / `app.skill`）と agent（`agent` / `agent.llm` / `agent.run` / `agent.auto` / `agent.eval`）と拡張（format-*・view-*）は Compose 非依存の純粋ロジックで、単体テストはこの層に書く。拡張は app-core とカーネルだけを使い、拡張どうし・agent・入口に依存しない。入口が登録の一覧（`desktop/ext/DesktopExtensions.kt`）で `Extensions.install` に渡す。`desktop`（AppState）は `mutableStateOf` だけに依存する。

## ソース規則テスト

上の依存方向と各主題の「規律」のうち機械で確かめられるものは、repo-tests のテスト `rules/SourceRulesTest`（規則の表は `rules/SourceRules`）が全モジュールの main のソース（テストの書き方の規則は test のソース）を走査して守る。構文解析はせず、import 行と文字の一致で見る。
規則は 18 本。純粋ロジック層に Compose を持ち込まない・app の層は一方向・yaml-core と semantic-core にファイルの場所を持ち込まない・等幅は `AppFonts.Mono`・外部の UI ライブラリは包む 1 ファイルだけ・AI の HTTP は `agent/llm/Http.kt` だけ。
`JFileChooser` と `Dispatchers.Main` を使わない・生の色は `Palette` だけ・UI は `state.index` を読まない・`FieldType` は基底の種類だけ・`Files.exists` は許可リストのファイルだけ・同じ裸の名前のファイルを作らない・テストに OS 固有の絶対パス（ドライブ文字）を書かない。
モジュールの形の規則（`rules/ModuleRules`）: settings.gradle.kts の全モジュールを走査し root にソースを置かない・拡張の実装は入口の登録の一覧に載せる・拡張は app-core とカーネルだけを使う・拡張は決まった形（README・共通の設定の build・テスト・モジュール名どおりのパッケージ・公開は差し込み口の実装だけ。ビューの種類は描画が読む型も）・agent のファイルの間に循環を作らない。
文書の参照（今の姿の文書が指す `.kt` の名前・テスト名・「ファイル名＋見出し」）の実在は同じ場所の `rules/DocReferencesTest` が見る。
落ちたらメッセージの「説明」の見出しを読む。規則を足すときは表に 1 つ足し、テストを 1 つ足す。今は直せていない違反は表の `known` に理由つきで書き、直したら消す（残っているとテストが「不要」と知らせる）。
800 行を超える main のファイルは目安として報告するだけで失敗にしない（文書の字数と同じ扱い。`repo-tests/build/reports/source-rules/large-files.txt` に書き、pre-commit のフックが表示する）。

## 入口のクラス

| クラス | ファイル | 責務 |
|---|---|---|
| `AppState` / `OpenDocument` | desktop/AppState.kt・desktop/OpenDocument.kt | 画面側の状態。セッション側（`session.DocumentSession`）の値を Compose の状態へ写し、文書の殻のキャッシュ・ナビゲーション・通知・確認・スレッド（EDT／IO）と画面の追随を担う。`OpenDocument` は開いている 1 文書の Compose の殻で、論理は `session.DocumentState` |
| `DocumentSession` | session/DocumentSession.kt | セッション側の状態（Compose 非依存）。`core.Workspace` とカーネルのセッションを持ち、ワークスペースを開く・索引の写し・開いている文書・編集の記録・開き直しと同期・再読込と保存・履歴の一手・全体検証と検索・一括変更・リファクタリング・文書の作成・配置の計画と前後・操作（アクション）の論理を担う |
| `Workspace` | core/Workspace.kt | ヘッドレスなワークスペース操作の窓口（開く・読む・保存・検証・変更セット適用）。内部にディスクを映すセッションを持つ |
| `WorkspaceSession` | workspace-core | カーネルのセッション。読み込み・派生状態・履歴（Undo/Redo）・トランザクション・保存・リファクタリングの計画 |
| `SemanticEngine` | semantic-core | 意味解析の評価器。走査・グラフ・クエリ・検査の判定 |
| `Validator` | validate/Validator.kt | 文書の検証の窓口。索引とスキーマを渡してカーネルの `DocumentValidator` を呼ぶ |
| `AgentKernel` | agent/run/Kernel.kt | エージェント実行のマルチターンのツール使用ループ |
| `cli.MainKt`（`runCli`） | cli/Main.kt・cli/AppCli.kt・cli/commands/ | 1 つの CLI: カーネルの命令（yaml … / workspace …）と応用の命令（命令ごとのファイル） |

## 全体の流れ

- **編集**: フォーム操作 → `Slot.set` またはツリー直接変更 → `markEdited()`（履歴と dirty はセッションが記録）→ `version` が進み再コンポーズ。→ [editing-history.md](editing-history.md)
- **保存**: `canonicalText()` をファイルへ → `Workspace.noteDiskChanges`（増分か全再構築かの振り分け）→ `indexVersion` が進む。→ [workspace.md](workspace.md)
- **検証**: 構造の検証 → ID と参照 → エンティティ型 → 関係名 → 定義体の検査 → テンプレート → 案内、をカーネルの `DocumentValidator` が組み立てる。→ [checks.md](checks.md)
- **エージェント**: アクティビティ起動 → ツール使用ループ（探索 → パッチ → 検証）→ 変更セットの提出 → レビュー → `Workspace.apply`。→ [agents.md](agents.md)
- **操作**: 名前と受け取った値 → 受け手の解決 → 前提の判定 → 手を順に当てる → 変更セット → `ChangeSetApplier`（CLI `action`）。→ [actions.md](actions.md)

## 規模の計測

CLI `bench [--modules 2,4,8,16] [--keep <dir>]` が、大きさを指定した合成ワークスペース（`core/Bench.kt`。検査の語彙をひととおり使い、検証エラーは 0）を作り、開く・全体を検証する・準備完了レポートを作る時間と、前の大きさとの伸び方（`validateGrowth`）を出す。
伸び方が 1.0 前後なら文書数に比例、2.0 なら規模 2 倍で時間 4 倍。検査の実行の作りを変える前後で比べる道具で、`BenchTest` が決定性と検査の空振りの無いことを確かめる。
全体の検証は文書数に比例する（4,107 文書で約 6 秒）。仕組みは [checks.md「評価は 1 回」](checks.md)。
