# アーキテクチャ（クラス設計と設計方針）

本書は実装の構造と、変更時に守るべき設計方針を定義します。構造や方針に影響する変更を行ったら**必ず本書を更新**してください。機能の有無・実装状況は [FEATURES.md](FEATURES.md)、利用者向けの仕様は [README.md](../README.md) を参照。

## 1. 技術スタック

- Kotlin (JVM 17) + Compose for Desktop + Material 3
- YAML入出力: SnakeYAML
- AI連携: Java 11 HttpClient + kotlinx-serialization-json（外部SDK不使用）
- Excel入出力: Apache POI（poi-ooxml。skill機構のインポート・エクスポートのみで使用）
- 永続化: `java.util.prefs.Preferences`（UIレイアウト・テーマ・履歴・AIモデル設定）
- ビルド: Gradle（Wrapper 同梱）、テスト: kotlin.test
- ヘッドレスCLI: `./gradlew runCli --args="…"`（GUIなしで探索・検索・パッチ・検証・エージェント実行・評価・入出力変換。README §16） 

## 2. パッケージ構成と依存方向

```
src/main/kotlin/
├── model/     YNode: YAMLの可変ツリー表現、YPath（パース含む） （依存なし）
├── yaml/      YamlIO: SnakeYAML との相互変換               （→ model）
├── schema/    スキーマモデル・ローダ・メタ検証・カバレッジルール・glob（→ model, yaml）
├── index/     ID索引・参照スキャナ（関係種別つき）          （→ model, schema, yaml）
├── validate/  スキーマ検証・参照解決・重複・カバレッジ検証  （→ model, schema, index）
├── core/      ヘッドレス層: ワークスペース操作・パッチ・段階的開示・構造検索・ビュー射影・CLI
│              （→ model, yaml, schema, index, validate。UI/Compose 非依存）
├── ai/        AI生成・LLMクライアント: JSON Schema変換・構造化出力・ツール使用チャット（→ model, schema, index）
├── skill/     入出力変換: skill定義・表形式の読み書き・インポート・エクスポート・
│              テンプレートパック （→ core ほか。UI/Compose 非依存）
├── agent/     エージェント実行: 定義・ステージング・ツールセット・カーネル・実行記録・
│              自動化・メトリクス・評価 （→ core, skill, ai, validate ほか。UI/Compose 非依存）
├── app/       AppState / OpenDocument: アプリ状態・ファイル操作（→ core とロジック層）
├── ui/        Composeビュー一式                             （→ 上記すべて）
└── Main.kt    エントリポイント・ウィンドウ・ツールバー・パレット（→ app, ui, index）
```

**依存ルール**: `model` → `yaml` → `schema` → `index` → `validate` → `core` → `skill` → `agent` → `app` → `ui` の一方向。逆流させない（例外: エントリポイントである `core/Cli.kt` は Main.kt と同様に `skill` / `agent` を呼んでよい）。

- `model` / `yaml` / `schema` / `index` / `validate` / `core` / `agent` / `ai` は **Compose・UI 非依存**（純粋ロジック）。単体テストはこの層に書く。
- `core` はエージェント・CLI・UI が共通で使うヘッドレス操作の層（ROADMAP P1）。UI（AppState）はファイル走査などの基盤処理を `core` に委譲する。
- `agent` はP1のAPIをツールとして束ね、マルチターンのエージェント実行を提供する層（ROADMAP P2）。LLMセッションは注入可能で、テストは台本を返す疑似セッションで行う。
- `app` は Compose の `mutableStateOf` のみに依存（UI部品には依存しない）。
- `ai` はネットワークを伴うが、リクエスト構築・レスポンス解析は純粋関数として分離しテスト可能にしている。

## 3. 主要クラスと責務

| クラス | ファイル | 責務 |
|---|---|---|
| `YNode` (Scalar/Seq/Map) | model/YNode.kt | YAMLの可変ツリー。`YPath`（String=キー, Int=索引）で位置を表現 |
| `YamlIO` | yaml/YamlIO.kt | テキスト⇔`YNode` 変換。表層情報を保持しない正規化ダンプ（ブロック・インデント2、リスト記号も親から一段下げる） |
| `FieldType` / `FieldDef` / `SchemaDef` | schema/Schema.kt | スキーマモデル。`resolve()` が `$ref` を属性合成しつつ解決。`FieldDef.relation` が参照の関係種別。`effectiveFields()` が enum の `cases` を合成した object の実効フィールドを返す |
| `CoverageRule` / `CoverageLevel` | schema/Schema.kt | スキーマの `coverage`（被参照必須などのトレーサビリティ要件）の定義 |
| `SchemaLoader` | schema/Schema.kt | `_schemas/` の読込・パース・スキーマID重複検出 |
| `validateDefinition` / `generateSample` | schema/Schema.kt | メタスキーマ検証・サンプルデータ生成（拡張関数） |
| `globToRegex` / `idMatchesPattern` | schema / index | glob→正規表現変換（パス用と ID 用で対応構文が異なる点に注意） |
| `DocScanner` | index/IdIndex.kt | 1文書からのID・参照抽出。スキーマ有無で検出規則を切替、未知キー境界で走査停止。参照には `relation` / `sourceSchemaId` を付与 |
| `IdIndex` | index/IdIndex.kt | ワークスペース全体の `id -> 出現一覧` 索引と全参照。**ディスク基準**（未保存編集は反映されない） |
| `Validator` | validate/Validator.kt | 文書の検証（UI非依存）。スキーマ適合＋参照解決＋文書内ID重複＋カバレッジ（`coverageStatuses` はUIとも共用） |
| `Workspace` / `WorkspaceScan` | core/Workspace.kt | ヘッドレスなワークスペース操作（開く・読む・保存・検証・変更セット適用）。相対パス解決はワークスペース外への脱出を拒否 |
| `PatchOp` / `Patcher` / `ChangeSets` | core/Patch.kt | YPathパッチ（set/insert/delete/move）と変更セット（baseハッシュ競合検出、YAML解析） |
| `Disclosure` | core/Disclosure.kt | 段階的開示: カタログ→骨子→ノード全文、文字数予算付き文脈バンドル |
| `Search` | core/Search.kt | 構造検索: 本文・キー部分一致＋スキーマ／glob条件、参照・未解決参照の検索 |
| `ViewDef` / `Views` | core/Views.kt | ビュー機構（P6）: `_views` 定義のロード、行（論理ID）×列（相対YPath）の解決＝原本座標化、データパスからのスキーマフィールド解決（`fieldAt`）、詳細文書スキャフォールド、テキスト表現（`renderText`） |
| `runCli` | core/Cli.kt | CLIコマンド（catalog/outline/read/bundle/search/validate/apply/hash/activities/agent/watch/revert/metrics/eval）。出力はUTF-8のYAML |
| `ToolChatSession` / `ToolChat` | ai/ToolChat.kt | ツール使用（function calling）対応のチャット抽象と OpenAI Responses / Anthropic Messages 実装。会話状態はセッションが保持 |
| `AgentDef` / `ActivityDef` / `Definitions` | agent/Definitions.kt | `_agents` / `_activities` 定義の実行時ロード（エラー収集つき）。フォーム編集・検証は `schema/BuiltinSchemas.kt` の組込みスキーマが担う |
| `Staging` | agent/Staging.kt | エージェントの作業オーバーレイ。パッチ記録・新規文書・オーバーレイ索引・影響ファイルを含む横断検証・変更セット化 |
| `AgentToolbox` | agent/Tools.kt | エージェントに公開する13ツール（探索・編集・検証・対話・管理）。書込権限（許可glob・予約ディレクトリ禁止）をツール側で強制 |
| `AgentKernel` / `AgentHost` | agent/Kernel.kt | マルチターンのツール使用ループ。上限（ターン・トークン・時間）、終了手続きの強制、質問の中継、イベント通知 |
| `RunLog` | agent/RunLog.kt | 実行記録を `_runs/` にYAML保存し、採否・自動適用バックアップ・巻き戻しを追記 |
| `AutomationEngine` | agent/Automation.kt | 自動化エンジン: トリガ監視（`tick(now)`）・直列実行キュー（`processOnce`）・承認ポリシー・連鎖・リトライ・常時停止。時刻注入でテスト可能 |
| `AutoApply` | agent/AutoApply.kt | 巻き戻し可能な適用: 適用前原文を実行記録へバックアップし、`revert` で復元（適用後の変更は競合として拒否） |
| `SkillDef` / `Skills` | skill/Skills.kt | `_skills` の宣言型変換定義（import / export）のロードとパース。コード実行は提供しない |
| `Table` / `Tables` | skill/Tables.kt | 表形式の共通抽象と xlsx / csv / json / markdown の読み書き（セル値は型を保持） |
| `Importer` | skill/Importer.kt | 表 → 変更セット。新規行は文書作成、既存文書はマップ項目のみ更新（値が変わる場合だけ）。エラーは行・列つきで宣言的に報告 |
| `Exporter` | skill/Exporter.kt | 対象glob×行×列 → 派生成果物（xlsx / csv / markdown）。YAML出力は拒否 |
| `TemplatePacks` | skill/TemplatePacks.kt | テンプレートパック（コード埋め込み）。`init` で展開、既存ファイルは上書きしない |
| `RunMetrics` | agent/Metrics.kt | 実行メトリクス（R4-01）: `_runs` の実行記録だけから提出率・採用率・却下ファイル率・自動適用/巻き戻し・コストを集計（実行時に追加記録しない） |
| `EvalDef` / `Evals` / `EvalRunner` | agent/Evals.kt | 評価セット（R4-02）と比較実験（R4-04）: `_evals` 定義のロード、サンドボックスコピー上でのケース×変異の実行、宣言的期待結果の判定、レポート保存 |
| `AgentRunHost` / `ReviewContent` | ui/AgentPanel.kt | 実行ダイアログ: 選択→進捗ストリーミング・質問応答→変更セットレビュー（部分採用・再検証）→適用。レビューは承認待ちからも共用 |
| `AutomationPanel` | ui/AutomationPanel.kt | 自動実行パネル: 監視ON/OFF・キュー・承認待ちレビュー・履歴・ログ・停止 |
| `ImprovementPanel` | ui/ImprovementPanel.kt | 自己改善パネル: 実行メトリクスの表示・評価セットの実行と変異別結果の比較 |
| `SkillsPanel` | ui/SkillsPanel.kt | skillパネル: 一覧・エクスポート実行・インポートのプレビュー→検証つき適用 |
| `OpenDocument` | app/AppState.kt | 開いている1文書。ツリー・履歴（Undo/Redo）・dirty・外部競合の管理 |
| `AppState` | app/AppState.kt | ワークスペース・文書キャッシュ・索引・ナビゲーション・ファイル操作・一括更新・外部変更検出 |
| `AiGenerator` ほか | ai/ | スキーマ→JSON Schema 変換、REF候補の enum 制約、プロバイダ振り分け |
| `OpenAiGenerator` / `AnthropicGenerator` | ai/ | 各API（Responses / Messages）のリクエスト構築とレスポンス解析 |
| `FormView` / `FormCtx` | ui/FormView.kt | スキーマ駆動フォームと汎用エディタ（中核UI）。`FormCtx` が編集操作と参照解決を仲介 |
| `IdCrossView` | ui/IdCrossView.kt | ID横ぐしビュー: 同一論理IDの全出現・参照・被参照・カバレッジ充足の集約表示 |
| `ViewGridView` | ui/ViewGridPane.kt | ビューグリッド: `_views` 定義の編集可能な一覧表示。セル編集は文書キャッシュ上のツリー直接編集（Slot＋markEdited）、行アクションで詳細文書作成・新規行追加 |
| `Slot` (Map/Seq/Root) | ui/Common.kt | 「値の置き場所」の抽象。後述 |
| `AiGenerationHost` | ui/AiGenerationDialog.kt | AI生成ダイアログの状態機械（入力→生成→プレビュー→適用）と提案合成 |
| `SchemaEditor` | ui/SchemaEditor.kt | `_schemas` 用の構造化エディタ（設計・プレビュー・適用対象） |
| その他 ui | FileTreePane / SourcePane / DiffPane / SidePanel / WorkspaceViews / Common | 各ペイン。[FEATURES.md](FEATURES.md) の対応表を参照 |

## 4. 設計方針（変更時に守ること）

### 4.1 ツリーが単一の正（single source of truth）

- `OpenDocument.root: YNode` が唯一の編集対象。フォームはツリーを**直接ミューテート**し、`markEdited()`（= `version` インクリメント）で再コンポーズを駆動する。
- `YNode` は意図的に「Composeの`State`ではない単純な可変オブジェクト」。UI側は `doc.version` / `state.indexVersion` を読むことで再コンポーズを購読する。新しいビューを書くときはこの購読を忘れないこと。
- ソース編集は例外的にテキストを持つが、「変更を反映」でパースしてツリーに合流させる。保存は常に `canonicalText()`（ツリーの正規化ダンプ）を書き出す。

### 4.2 履歴・dirty 判定は正規化テキストで行う

Undo/Redo は `YamlIO.dump` したスナップショット文字列の履歴（最大100件）。dirty は保存時スナップショットとの文字列比較。ツリーの構造比較は実装しない。

### 4.3 Slot 抽象

マップのキー（`MapSlot`）・配列要素（`SeqSlot`）・文書ルート（`RootSlot`）を `Slot`（get/set/remove）で統一し、スキーマフォームと汎用エディタがエディタ部品を共有する。新しい「値の置き場所」（例: `_document` メタデータ）が必要なら `Slot` 実装を追加する。

### 4.4 YPath ベースのナビゲーション

要素位置は `YPath = List<Any>` で統一。検証結果・ハイライト・ジャンプ・AI生成対象はすべて YPath で紐付く。`state.highlightPath` に設定すると `Highlightable` がスクロール＋一時ハイライトする。

### 4.5 索引はディスク基準

`IdIndex` は保存済みファイルから再構築される（開く・再読込・保存・外部変更・一括編集のタイミング）。未保存編集中の参照解決は、`DocScanner.scan` を編集中ツリーへ都度実行した「ローカルID」を索引に**重ねて**判定する（`FormCtx.resolvable`, `Validator`）。この二層構造を崩さないこと。

### 4.6 ID・参照のセマンティクス

- 同一IDの複数文書出現は「同一論理要素の段階的詳細化」として正当。エラーは同一文書内の重複のみ。
- 参照の解決可否は `idPatterns` × `scope.schemaIds` × `scope.documentIds` のAND（各配列内はOR）。候補表示と検証で同じ判定（`IdEntry.isAllowedBy`）を使う。判定ロジックを追加する場合も候補・検証・AI制約（`refCandidateIds`）の三箇所で一貫させる。
- 参照には任意の**関係種別**（`relation`）を付けられる。関係種別は解決可否には影響せず、表示（SidePanel・横ぐしビュー・グラフ）と**カバレッジ判定**にのみ使う。
- カバレッジルール（スキーマの `coverage`）は「対象IDが、条件（参照元スキーマ・関係）を満たす参照を minCount 件以上受けること」。判定は `Validator.coverageStatuses` に一元化し、検証（Issue化）とUI表示の両方から使う。索引基準のため未保存編集は保存まで反映されない。

### 4.7 スキーマの `$ref` 解決と実効フィールド

`SchemaDef.resolve()` は参照先定義に呼び出し側の `key`/`label`/`required` 等を合成して返す。フィールドを使う側は**必ず** `schema.resolve(field)` を通す（`FormView`・`Validator`・`DocScanner`・`ai` すべて同じ流儀）。

enum の `cases`（値ごとの追加フィールド。判別共用体）を持ちうる object の子フィールドを列挙する側は、**必ず** `schema.effectiveFields(fields, node)` を通す（`Validator`・`FormView.ObjectFields`・`DocScanner`・初期値生成 `defaultNode`/`defaultFor`/`generateSample` すべて同じ流儀）。データノードの enum 値に応じて case フィールドが判別フィールドの直後に合成され、非アクティブな case のキー（→ 有効になる値）の逆引きも同時に得られる。素の `field.fields` を直接列挙すると case フィールドが漏れる。なお `ai.fieldToJsonSchema` のみ、データノードが存在しない（これから生成する）ため `effectiveFields` を使わず、判別 enum が1つなら値ごとの `anyOf` 分岐、複数なら全 case の和集合に変換する。

### 4.8 安全性の原則

- 破壊的操作（削除・一括編集・ワークスペース切替・終了）は、未保存文書があれば拒否または確認ダイアログを挟む。
- 外部変更は dirty でなければ自動反映、dirty なら競合バナーで利用者に選択させる。無断上書きはしない。
- AI提案は必ずプレビュー→明示的な適用、適用は Undo 可能、自動保存しない。APIキーはディスクへ保存しない。

### 4.9 AI生成の構造保証

生成値の型保証は「対象フィールドのスキーマ→JSON Schema 変換＋Structured Outputs」で行い、プロンプトに依存しない。ただし `map` 型（additionalProperties がスキーマ）を含む場合のみ構造化出力を無効化してプロンプト指示にフォールバックする。生成結果は `value` プロパティに包んだレスポンススキーマで受け、`parseGeneratedValue` で `YNode` 化する。

### 4.10 エージェント実行（P2）

エージェントの実行は「LLMが自ら次の行動を選ぶマルチターンのツール使用ループ」（`AgentKernel`）。設計の要点:

- **編集は必ずステージング経由**。`Staging` がベース内容のスナップショットに対するオーバーレイとしてパッチを記録し、提出物はそのままP1の変更セットになる（適用・競合検出・レビューの再利用）。原本はレビュー承認まで変わらない。
- **検証は「ステージ済み＋影響ファイル」**。影響ファイル＝ステージ対象が変更前後に定義するIDの参照元。ID改名で壊れる未修正の参照元をステージング外でも検出しつつ、無関係な既存エラーで提出を塞がない（ゲートの意味は「自分の変更が新たな破れを生まないこと」）。
- **権限はツール側で強制**。許可ファイルglob・許可パスglob・予約ディレクトリ（`_schemas` / `_agents` / `_activities` / `_runs`）書込禁止は、プロンプトではなく `AgentToolbox` が拒否する。
- **終了手続きの強制**。`submit_changeset`（ゲート通過必須）か `abort` でのみ正常終了。上限（ターン・トークン・時間）到達時はステージングを未提出ドラフトとして保全する。
- **テスト容易性**。LLMセッションは `sessionFactory` 注入で疑似化でき、ツール実行・ステージング・検証は本物を通した結合テストが書ける（`KernelTest`）。

### 4.11 自動実行（P3）

`AutomationEngine` がトリガ・キュー・承認・連鎖・リトライを司る。守るべき不変条件:

- **直列実行**: エージェント実行は常に1件ずつ。並行変更と索引競合を構造的に排除する。
- **自動適用の3条件**: ゲート通過・書込スコープ内・対象がエディタで非編集中。ひとつでも欠けたら承認待ちへ**降格**する（黙って失敗させない）。
- **自己トリガの防止**: 適用（自動・採用とも）の直後にファイルスタンプ基準を取り直す。予約ディレクトリ（`_schemas` / `_agents` / `_activities` / `_evals` / `_skills` / `_runs`）は監視対象外。
- **Undo保証**: 自動適用は適用前の原文を実行記録へバックアップしてから書き込む（`AutoApply`）。巻き戻しは適用後の追加変更を競合として検出する。
- **連鎖のガード**: 深さ上限（既定5）と visited 集合による循環検出。人間承認の実行では、連鎖は採否決定後に発火する。
- **リトライの収束判定**: 同一の失敗メッセージを繰り返したら `maxAttempts` 未満でも打ち切る。
- **時刻とLLMは注入可能**: `tick(now)` / `processOnce()` / `sessionFactory` により、タイマー・実APIなしで全経路をテストできる（`AutomationTest`）。

### 4.12 自己改善（P4）

メトリクス・評価・メタアクティビティの3部品で「測る→比べる→定義を直す」を回す。守るべき不変条件:

- **メトリクスは読むだけ**: `RunMetrics` は既存の実行記録（採否・自動適用・巻き戻しの追記を含む）だけから集計し、実行時に追加の記録を要求しない。記録の形式を変えるときは `RunLog` と `RunMetrics` の両方を更新する。
- **評価はサンドボックス**: `EvalRunner` はケース×変異ごとにワークスペースの一時コピー（`_runs` 除外）を作って実行し、原本には一切書かない。レポート（`_runs/eval-*.yaml`）は実行記録の必須キーを持たないため、メトリクス集計から自動的に除外される。
- **期待結果は宣言的**: 終了状態・検証エラー・変更ファイル（glob）・パス内容（exists/contains/equals）の構造条件のみ。出力文字列の完全一致は提供しない（モデルの揺らぎで壊れる評価を書かせない）。
- **メタは人間承認固定**: `meta: true` は `_agents` / `_activities` / `_evals` への書込だけを解禁する。`_schemas` / `_runs` は常に不可（`AgentToolbox`）。`meta` かつ `approval: auto` はロード時に定義エラー。ステージされた定義は実際のローダでパースできることが提出ゲートに加わる（`Staging.validateAll`）。
- **LLMは注入可能**: `EvalRunner` もカーネルと同じ `SessionFactory` を受け取り、テストは台本セッションで決定的に行う（`EvalTest` / `MetaActivityTest`）。

### 4.13 skill機構（P5）

宣言型の入出力変換で外部フォーマットと接続する。守るべき不変条件:

- **YAMLが唯一の原本**: インポートは取り込んだ時点でYAML側を正とする。エクスポートは原本からいつでも再生成できる派生成果物であり、**YAMLへの出力は定義段階で拒否**する（派生物を原本と取り違えさせない）。
- **インポートは変更セット経由**: 直接ファイルへ書かず、P1の `Workspace.apply` を通す（baseハッシュ競合検出・オール・オア・ナッシング・`requireValid` ゲートを再利用）。既存文書は**マップした項目だけ**を、**値が変わる場合だけ** set する。これにより (1) YAML側の追記が保持され、(2) エクスポート→再インポートが空の変更セットになる（往復の無損失を機械確認できる）。
- **宣言型のみ**: skill にコード実行はない（設計原則3・§4.3）。書けない変換が必要になったら、コード実行を安易に足すのではなく、明示許可制のサンドボックス設計とセットで検討する（FEATURES L-12）。
- **形式は Table 抽象に集約**: xlsx / csv / json / markdown の差は `skill/Tables.kt` に閉じる。形式追加はここだけを触る。
- **`_skills` も予約ディレクトリ**: 通常のエージェントは書込不可、メタアクティビティのみ提案可（パースゲートつき）。自動実行の監視対象外。

### 4.14 ビュー機構（P6）

`_views` の宣言的ビュー定義を「行（＝論理ID）×列（＝出現要素からの相対YPath）」の編集面に射影する。守るべき不変条件:

- **ビューはデータを持たない**: ビューは原本の射影であり、独自の永続化を一切持たない。セルは必ず原本上の座標 `(file, YPath)` に解決され、編集はその座標の原本ツリーへルーティングされる（原本主義）。
- **編集経路は既存と同一**: UIのセル編集は文書キャッシュ（`AppState.ensureDoc`）上のツリーを `Slot` 経由で直接変更し `markEdited()` する（＝dirty・Undo・保存・外部変更検出がそのまま効く）。エージェントは `read_view` で座標を得て既存の `apply_patch` で編集する。**ビュー専用の書込経路を作らない**（権限強制・ゲートを一元に保つ）。
- **行はディスク索引基準・値は編集中優先**: 行の集合（ID）と座標は `IdIndex`（§4.5）から解決し、セル値の表示は loader 注入で「編集中ツリー（UI: docCache / エージェント: ステージング）→ ディスク」の順に読む。未保存の新規IDが行に現れないのは索引の設計方針どおり（FEATURES L-14）。
- **`_views` は予約ディレクトリ**: 書込はメタアクティビティのみ（`AgentToolbox`）、自動実行の監視対象外（`AutomationEngine`）、ステージされた定義は `Views.parseView` のパース可否が提出ゲートに加わる（`Staging.validateAll`）。
- **セルエディタの型解決**: 列の相対パスから `Views.fieldAt` でフィールド定義を辿る（`resolve` / `effectiveFields` を通す。§4.7）。直接編集はスカラー系フィールドに限定し、複合値はジャンプで個別フォームへ誘導する。

### 4.15 エラー処理

- ファイルI/O・パースは `runCatching` で受け、ユーザー操作の結果は `Result` を返して呼び出し側（UI）が `state.status` に反映する。
- 入力検証の失敗は `require(...) { "日本語メッセージ" }`、内部異常は `error("日本語メッセージ")`。メッセージはそのままUIに表示される前提で書く。

## 5. 主要データフロー

- **編集**: フォーム操作 → `Slot.set` / ツリー直接変更 → `markEdited()` → `version`++ → 再コンポーズ＋履歴追加
- **保存**: `canonicalText()` をファイルへ書き出し → スキーマファイルなら再読込 → `rebuildIndex()` → `indexVersion`++
- **ジャンプ**: `navigateToId` → 索引の出現検索（複数なら選択ダイアログ／横ぐしビュー）→ `openFile(file, focus)` → `highlightPath` 設定 → `Highlightable` がスクロール
- **ビューグリッド**: `_views` 定義 → `Views.resolve`（行=ディスク索引、セル値=編集中ツリー優先の loader）→ セル編集は該当文書の `Slot` 直接変更＋`markEdited()` → 保存 → `rebuildIndex()` で行集合にも反映。行アクションは `AppState.createViewDetail`（スキャフォールド→書込→開く）／`addViewRow`（一覧文書の配列へ未保存編集として追加）
- **AI生成**: ✨/ボタン → `AppState.requestAi(field, path)` → `AiGenerationHost` が現ツリーを複製して入力構築 → プロバイダ呼び出し → `combineProposal`/`replaceInClone` で適用後ツリーを合成 → 差分プレビュー＋`Validator` で事前検証 → 適用（`doc.root` 差し替え）
- **ヘッドレス変更セット適用**（CLI／エージェント）: `ChangeSets.parse` → 全ファイルの base ハッシュ照合＋パッチ適用（メモリ上）→ 適用後状態を重ねた**オーバーレイ索引**で検証（複数ファイル連動の変更も正しく判定）→ **全件成功時のみ**書き込み（`--require-valid` 指定時は検証エラーでも中止）→ `refresh()` で索引更新。1件でも失敗すれば何も書かないオール・オア・ナッシング
- **エージェント実行**: アクティビティ起動 → `AgentKernel` がシステムプロンプト（persona＋規範）と初期文脈（指示・カタログ・権限・ゲート）を構築 → ツール使用ループ（探索→パッチ→検証→…、必要なら `delegate` で相談）→ `submit_changeset`（ゲート通過）→ `RunLog` 書き出し → レビューUIで部分採用（採用サブセットを `Staging` で再検証）→ `Workspace.apply` で反映 → `AppState.reload()`
- **自動実行**: `AutomationEngine.tick(now)` がトリガ判定（on-change はデバウンス、on-invalid は変更ファイルの検証）→ キュー投入 → `processOnce` がカーネル実行 → 提出後は承認方式で分岐（auto: `AutoApply.applyWithBackup` → スタンプ更新 → 連鎖／human: 承認待ち＋変更セットのファイル保存 → 採否後に連鎖）→ 失敗はフィードバック付きリトライ（収束打ち切りあり）
- **評価**: `Evals.load` → `EvalRunner.run` がケース×変異ごとに（サンドボックスコピー作成 → `setup` のゴールデン入力を上書き → カーネル実行 → 宣言的期待結果の判定 → コピー破棄）→ レポートを原本の `_runs/eval-*.yaml` に保存 → 自己改善パネル／CLI `eval` で変異別の合格率・コストを比較
- **インポート**: `Tables.read`（形式→Table正規化）→ `Importer.build`（行→取り込み先ファイル解決・列マッピング・既存値との差分判定）→ プレビュー（作成/更新/変更なし/エラー）→ 人間の確認 → `Workspace.apply`（検証つき）→ `AppState.reload()`
- **エクスポート**: `Exporter.run`（glob→文書→行×列に展開）→ `Tables.write`（拡張子で形式決定）→ 派生成果物（再実行でいつでも再生成）

## 6. 拡張ポイント

- **フィールド型の追加**: `FieldType` に enum を追加し、`Validator.checkField`・`FormView.ValueEditor`・`ai.fieldToJsonSchema`・`SchemaDef.generateSample`/`defaultNode` に分岐を追加する。
- **コメント保持**: `YamlIO` を SnakeYAML の Node API（`LoaderOptions.setProcessComments(true)`）ベースに差し替える。ツリー変換層が `YamlIO` に隔離されているため影響範囲は限定的。
- **横断ビューの追加**: データソースとして `AppState.workspaceRecords()` と `IdIndex` を利用し、`Main.kt` の `workspaceView` 分岐に追加する。
- **ビュー定義の項目追加**: `core/Views.kt` の `parseView` と `schema/BuiltinSchemas.kt` の `builtin-view` スキーマを**対で**更新する（フォーム編集・検証と実行時パースの二重定義のため）。
- **AIプロバイダの追加**: `AiProvider` に enum を追加し、`AiGenerator.generate` の分岐と専用クライアント（`generate` + `parseResponse` を持つ object）を実装する。
- **入出力形式の追加**: `TableFormat` に enum を追加し、`skill/Tables.kt` の read / write に分岐を実装する（Importer / Exporter は形式非依存のため変更不要）。
- **テンプレートパックの追加**: `skill/TemplatePacks.kt` の `all` に `Pack`（ファイルパス→内容の埋め込み）を登録する。`ImportExportTest` に倣い、展開後の検証エラー0をテストで保証する。
