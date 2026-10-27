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
- AI 連携: Java 11 HttpClient + kotlinx-serialization-json（外部 SDK 不使用）。HttpClient の生成は `ai/Http.kt` の単一ファクトリ
- Excel・Word 入出力: Apache POI（skill のインポート・エクスポートのみ）
- 永続化: `java.util.prefs.Preferences`（UI レイアウト・テーマ・履歴・AI モデル設定）
- ビルド: Gradle（Wrapper 同梱）マルチモジュール。ルート＝Compose アプリ＋ `yaml-core` / `semantic-core` / `workspace-core` / `cli`。テスト: kotlin.test
- ヘッドレス CLI: `./gradlew runCli --args="…"`（[manual/cli.md](../manual/cli.md)）

## モジュールと依存方向

パッケージは `dev.toros.yde.<モジュール>.*`（規則は CONVENTIONS「モジュールの規約」）。依存方向は ADR-0002 が正: `yaml-core ← semantic-core ← workspace-core ← cli／アプリ`。
目指す構成（画面を持たない応用層 app-core・agent・入口 desktop／cli／mcp・形式とビューの種類の拡張）は ADR-0013 にあり、移行は ROADMAP の計画で進める。下の表は移る前の今の姿。
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
| workspace-core | `workspace` | セッション・ストア・読み込み・スキーマ文書の読み取り・検査の定義の読み込み・変更セットの適用・ひな型の文書・ビュー定義・リファクタリングの影響 |
| | `workspace.layout` | `WorkspaceLayout`（配置の判定） |
| | `workspace.changeset` | `ChangeSet` / `FileChange` / `ChangeSets` |
| | `workspace.validate` | 文書の検証の組み立てと検査の配り方（`DocumentValidator`・`WorkspaceChecks`・`CodedChecks`・`IssueSource`） |
| cli | `cli` | カーネル側の CLI（`cli.MainKt`。`workspace validate / apply / refactor / bench`） |
| アプリ（root） | `app` | `Main.kt`（エントリポイント・ウィンドウ・ショートカット・スタート画面）・`AppState` / `OpenDocument` |
| | `app.ui` | Compose の画面一式（骨格は `ui/Shell.kt`） |
| | `app.core` | ワークスペースの窓口（`Workspace`）・文書キャッシュ・ファイル監視・読み出しモデル（`Traverse` / `Disclosure` / `Search` / `Aspects` / `Views`）・root の CLI（`core/Cli.kt`）・規模の計測 |
| | `app.index` | 索引のファサード（`IdIndex`。カーネルの型の別名）・クエリの窓口 |
| | `app.schema` | `SchemaDef`（カーネルのスキーマにファイルの場所を足す薄い型）・`SchemaLoader`・表示解決 `Display`・ID 候補・スキーマ推測 |
| | `app.validate` | `Validator`（窓口）・`Readiness` |
| | `app.session` | セッション側の状態（Compose 非依存）: `DocumentSession`（コアとカーネルの保持・索引の写し・開いている文書・未保存編集を重ねた索引と派生・編集の記録・開き直しと同期）と 1 文書の論理 `DocumentState`。再読込・保存・履歴の段はまだ AppState にあり、順に寄せていく |
| | `app.text` | 純粋な文字の計算（行の差分と 3 者併合 `LineMerge`・履歴の値を優先した書式の差し込み `SourceHistorySync`） |
| | `app.ai` | AI 生成・LLM クライアント |
| | `app.skill` | 入出力変換（skill 定義・表形式の読み書き・インポート・エクスポート） |
| | `app.agent` | エージェント実行（定義・ステージング・ツールセット・カーネル・実行記録・自動化・メトリクス・評価） |

アプリの中の依存は `app.core` → `app.session` → `app.ai` → `app.skill` → `app.agent` → `app`（AppState）→ `app.ui` の一方向。`app.index` / `app.schema` / `app.validate` / `app.text` は `app.core` より下。
`app.core` / `app.index` / `app.schema` / `app.validate` / `app.text` / `app.agent` / `app.ai` / `app.skill` は Compose 非依存の純粋ロジックで、単体テストはこの層に書く。`app`（AppState）は `mutableStateOf` だけに依存する。

## ソース規則テスト

上の依存方向と各主題の「規律」のうち機械で確かめられるものは、root のテスト `rules/SourceRulesTest`（規則の表は `rules/SourceRules`）が全モジュールの main のソース（テストの書き方の規則は test のソース）を走査して守る。構文解析はせず、import 行と文字の一致で見る。
規則は 13 本。純粋ロジック層に Compose を持ち込まない・app の層は一方向・yaml-core と semantic-core にファイルの場所を持ち込まない・等幅は `AppFonts.Mono`・外部の UI ライブラリは包む 1 ファイルだけ・AI の HTTP は `ai/Http.kt` だけ。
`JFileChooser` と `Dispatchers.Main` を使わない・生の色は `Palette` だけ・UI は `state.index` を読まない・`FieldType` は基底の種類だけ・`Files.exists` は許可リストのファイルだけ・同じ裸の名前のファイルを作らない・テストに OS 固有の絶対パス（ドライブ文字）を書かない。
文書の参照（今の姿の文書が指す `.kt` の名前・テスト名・「ファイル名＋見出し」）の実在は同じ場所の `rules/DocReferencesTest` が見る。
落ちたらメッセージの「説明」の見出しを読む。規則を足すときは表に 1 つ足し、テストを 1 つ足す。今は直せていない違反は表の `known` に理由つきで書き、直したら消す（残っているとテストが「不要」と知らせる）。

## 入口のクラス

| クラス | ファイル | 責務 |
|---|---|---|
| `AppState` / `OpenDocument` | app/AppState.kt・app/OpenDocument.kt | 画面側の状態。セッション側（`session.DocumentSession`）の値を Compose の状態へ写し、文書の殻のキャッシュ・ナビゲーション・通知・確認・ファイル操作を担う。`OpenDocument` は開いている 1 文書の Compose の殻で、論理は `session.DocumentState` |
| `DocumentSession` | session/DocumentSession.kt | セッション側の状態（Compose 非依存）。`core.Workspace` とカーネルのセッションを持ち、索引の写し・開いている文書・編集の記録・開き直しと同期を担う |
| `Workspace` | core/Workspace.kt | ヘッドレスなワークスペース操作の窓口（開く・読む・保存・検証・変更セット適用）。内部にディスクを映すセッションを持つ |
| `WorkspaceSession` | workspace-core | カーネルのセッション。読み込み・派生状態・履歴（Undo/Redo）・トランザクション・保存・リファクタリングの計画 |
| `SemanticEngine` | semantic-core | 意味解析の評価器。走査・グラフ・クエリ・検査の判定 |
| `Validator` | validate/Validator.kt | 文書の検証の窓口。索引とスキーマを渡してカーネルの `DocumentValidator` を呼ぶ |
| `AgentKernel` | agent/Kernel.kt | エージェント実行のマルチターンのツール使用ループ |
| `runCli` / `cli.MainKt` | core/Cli.kt・cli/ | root の CLI（探索・検索・検証・エージェント・入出力）とカーネル側の CLI |

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
