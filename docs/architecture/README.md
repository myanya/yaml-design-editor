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
| [workspace.md](workspace.md) | ワークスペース構造と layout・文書キャッシュと Workspace・外部変更の取り込み・変更セットの適用・セッションの fork |
| [agents.md](agents.md) | 安全性・エージェント実行と Staging・自動実行・自己改善・AI 生成・AI 接続・コンテキスト・一括実行・skill |
| [ui.md](ui.md) | UI シェル・フォームと FormCtx・閲覧専用・テーブル表示・畳みとハイライト・エラー処理 |

## 技術スタック

- Kotlin (JVM 17) + Compose for Desktop + Material 3
- YAML 入出力: SnakeYAML（Node API でコメント・表記を保持）
- AI 連携: Java 11 HttpClient + kotlinx-serialization-json（外部 SDK 不使用）。HttpClient の生成は `ai/Http.kt` の単一ファクトリ
- Excel・Word 入出力: Apache POI（skill のインポート・エクスポートのみ）
- 永続化: `java.util.prefs.Preferences`（UI レイアウト・テーマ・履歴・AI モデル設定）
- ビルド: Gradle（Wrapper 同梱）マルチモジュール。ルート＝Compose アプリ＋ `yaml-core` / `semantic-core` / `workspace-core` / `cli`。テスト: kotlin.test
- ヘッドレス CLI: `./gradlew runCli --args="…"`（[manual/cli.md](../manual/cli.md)）

## モジュールと依存方向

パッケージは `dev.toros.yde.<モジュール>.*`（規則は CONVENTIONS「モジュールの規約」）。依存方向は ADR-0002 が正: `yaml-core ← semantic-core ← workspace-core ← cli／アプリ`。
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
| | `app.ai` | AI 生成・LLM クライアント |
| | `app.skill` | 入出力変換（skill 定義・表形式の読み書き・インポート・エクスポート・テンプレートパック） |
| | `app.agent` | エージェント実行（定義・ステージング・ツールセット・カーネル・実行記録・自動化・メトリクス・評価） |

アプリの中の依存は `app.core` → `app.skill` → `app.agent` → `app`（AppState）→ `app.ui` の一方向。`app.index` / `app.schema` / `app.validate` は `app.core` より下。
`app.core` / `app.index` / `app.schema` / `app.validate` / `app.agent` / `app.ai` / `app.skill` は Compose 非依存の純粋ロジックで、単体テストはこの層に書く。`app`（AppState）は `mutableStateOf` だけに依存する。

## 入口のクラス

| クラス | ファイル | 責務 |
|---|---|---|
| `AppState` / `OpenDocument` | app/AppState.kt | アプリ状態（走査・スキーマ・索引は `core.Workspace` へ委譲し、Compose 購読用のミラー・文書キャッシュ・ナビゲーション・ファイル操作を担う）。`OpenDocument` は開いている 1 文書の Compose シェル |
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

## 規模の計測

CLI `bench [--modules 2,4,8,16] [--keep <dir>]` が、大きさを指定した合成ワークスペース（`core/Bench.kt`。検査の語彙をひととおり使い、検証エラーは 0）を作り、開く・全体を検証する・準備完了レポートを作る時間と、前の大きさとの伸び方（`validateGrowth`）を出す。
伸び方が 1.0 前後なら文書数に比例、2.0 なら規模 2 倍で時間 4 倍。検査の実行の作りを変える前後で比べる道具で、`BenchTest` が決定性と検査の空振りの無いことを確かめる。
全体の検証は文書数に比例する（4,107 文書で約 6 秒）。仕組みは [checks.md「評価は 1 回」](checks.md)。
