# アーキテクチャ（クラス設計と設計方針）

本書は実装の構造と、変更時に守るべき設計方針を定義します。構造や方針に影響する変更を行ったら**必ず本書を更新**してください。機能の有無・実装状況は [FEATURES.md](FEATURES.md)、利用者向けの仕様は [README.md](../README.md) を参照。

## 1. 技術スタック

- Kotlin (JVM 17) + Compose for Desktop + Material 3
- YAML入出力: SnakeYAML
- AI連携: Java 11 HttpClient + kotlinx-serialization-json（外部SDK不使用）。HttpClient の生成は `ai/Http.kt` の単一ファクトリに集約し、プロキシ（システム設定尊重／手動＋Basic認証）と OpenAI 互換エンドポイント（Azure 等）を一元管理する（R9-07・R9-08。§4.18）
- Excel・Word入出力: Apache POI（poi-ooxml。skill機構のインポート・エクスポートのみで使用。docx は XWPF で表を読む）
- 永続化: `java.util.prefs.Preferences`（UIレイアウト・テーマ・履歴・AIモデル設定）
- ビルド: Gradle（Wrapper 同梱）、テスト: kotlin.test
- ヘッドレスCLI: `./gradlew runCli --args="…"`（GUIなしで探索・検索・パッチ・検証・エージェント実行・評価・入出力変換。README §16） 

## 2. パッケージ構成と依存方向

```
src/main/kotlin/
├── model/     YNode: YAMLの可変ツリー表現、YPath（パース含む） （依存なし）
├── yaml/      YamlIO: SnakeYAML との相互変換               （→ model）
├── schema/    スキーマモデル・ローダ・メタ検証・カバレッジルール・glob・
│              スキーマ推測・派生スキーマ・配置ポリシー（WorkspaceLayout）（→ model, yaml）
├── index/     ID索引・参照スキャナ（関係種別つき）          （→ model, schema, yaml）
├── validate/  スキーマ検証・参照解決・重複・カバレッジ検証  （→ model, schema, index）
├── core/      ヘッドレス層: ワークスペース操作・パッチ・段階的開示・構造検索・ビュー射影・
│              ファイル監視・性能計測・CLI（→ model, yaml, schema, index, validate。UI/Compose 非依存）
├── ai/        AI生成・LLMクライアント: JSON Schema変換・構造化出力・ツール使用チャット（→ model, schema, index）
├── skill/     入出力変換: skill定義・表形式の読み書き・インポート・エクスポート・
│              テンプレートパック （→ core ほか。UI/Compose 非依存）
├── agent/     エージェント実行: 定義・ステージング・ツールセット・カーネル・実行記録・
│              自動化・メトリクス・評価 （→ core, skill, ai, validate ほか。UI/Compose 非依存）
├── app/       AppState / OpenDocument: アプリ状態・ファイル操作（→ core とロジック層）
├── ui/        Composeビュー一式                             （→ 上記すべて）
└── Main.kt    エントリポイント・ウィンドウ・スタート画面・ツールバー・ステータスバー・パレット（→ app, ui, index。シェルの骨格＝レール・スペースは ui/Shell.kt）
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
| `YNode` (Scalar/Seq/Map) | model/YNode.kt | YAMLの可変ツリー。`YPath`（String=キー, Int=索引）で位置を表現。コメント（`YComments`）・表記スタイルヒントを編集に影響しないメタ情報として保持（R8-01） |
| `YamlIO` | yaml/YamlIO.kt | テキスト⇔`YNode` 変換。SnakeYAML の Node API（processComments）ベースで、コメント・キー順・スカラー/フロー表記を往復保持する（§4.17）。`parseAll` / `dumpAll`（複数ドキュメント）、`parseFirst`（読み取り専用の寛容パース）、`dumpPlain`（コメント・スタイル抜きの正規化ダンプ＝構造比較・派生成果物用）を提供 |
| `FieldType` / `FieldDef` / `SchemaDef` | schema/Schema.kt | スキーマモデル。`resolve()` がローカル／横断 `$ref` を属性合成しつつ解決。`FieldDef.relation` が参照の関係種別、`role` が表示役割（R10-01）、`options` が enum のリッチ選択肢（`EnumOption`。R10-03）。`effectiveFields()` が enum の `cases` を合成した object の実効フィールドを返す |
| `Display` | schema/Display.kt | 表示解決（R10-01）: 要素の1行要約（タイトル・サブタイトル・ID・畳み込みヘッダ）を role ＞ 予約語 ＞ id ＞ キー数の優先順位で解決する。索引のタイトル抽出（`DocScanner`）・UI・エージェント要約が共有する（§4.22） |
| `Projection` | schema/Projection.kt | 決定論的射影の評価器（R16-02）: `{パス}` プレースホルダのテンプレート置換（スコープ連鎖・1段評価・解決トレース）と値辞書。単一実装（§4.26） |
| `DerivationDef` / `ConsistencyRule` | schema/Derivation.kt | 導出の宣言モデル（`lookup`/`source`/`path`/`values`/`map`/`default`）と整合ルール（R16-03a/b。`assert`: equals / lte / gte）。ビューの計算列とスキーマの `consistency:` が同じ語彙を共有する |
| `Derivation` | index/Derivation.kt | 導出の解決器（R16-03a）: 対象ID（lookup 1ホップ）→ source 出現 → スコープ連鎖 → `Projection` 評価＋来歴。計算列（core/Views）と整合検証（Validator）の共有実装（§4.26） |
| `SchemaCatalog` / `SchemaKind` | schema/Schema.kt | 全スキーマ・libraryの定義を `(moduleId, definitionName)` でリンクする中央カタログ。`kind: library` はdefinitionsだけを公開し文書には適用されない |
| `PhasesDef` / `currentPhaseIndex` / `requiredAt` | schema/Schema.kt | フェーズ機構（§4.15）: スキーマの `phases` 宣言と、文書の現在フェーズ解決・フェーズ考慮の実効必須判定（検証・進捗計算が共有） |
| `CoverageRule` / `CoverageLevel` | schema/Schema.kt | スキーマの `coverage`（被参照必須などのトレーサビリティ要件）の定義。`activeFrom` でフェーズ到達までの判定保留を宣言できる |
| `SchemaLoader` | schema/Schema.kt | schemas ゾーンの読込・パース・スキーマID重複検出。ロードは「パース→リンク→導出」の三段階（§4.23） |
| `WorkspaceLayout` | schema/WorkspaceLayout.kt | ワークスペースの配置ポリシー（R13-02）: レガシー／`.yde` 構成の判定・ゾーン解決（`zoneOf`）・台帳判定（`inLedger`）・索引除外（`indexExcluded`）を一元化（§4.25） |
| `validateDefinition` / `generateSample` | schema/Schema.kt | メタスキーマ検証・サンプルデータ生成（拡張関数） |
| `BuiltinSchemas` | schema/BuiltinSchemas.kt | 予約ディレクトリ用の組込みスキーマ。`builtin-schema`（R11-02）は `_schemas` 文書に適用されるメタスキーマ（再帰 `$ref` と `cases` のドッグフーディング） |
| `RefNaming` / `SchemaInference` | schema/SchemaInference.kt | スキーマ推測（R11-01）: 既存文書群からのドラフト推測（純ロジック）。`RefNaming` は参照命名規約の共有実体（`DocScanner` が委譲。§4.23） |
| `DeriveDef` / `DerivedSchemas` | schema/Schema.kt, schema/DerivedSchemas.kt | 派生スキーマ（R11-03）: `derive:` 宣言のモデルと、導出元文書からのロード時コンパイル（§4.23） |
| `globToRegex` / `idMatchesPattern` | schema / index | glob→正規表現変換（パス用と ID 用で対応構文が異なる点に注意） |
| `DocScanner` | index/IdIndex.kt | 1文書からのID・参照抽出。スキーマ有無で検出規則を切替、未知キー境界で走査停止。参照には `relation` / `sourceSchemaId` を付与 |
| `IdIndex` | index/IdIndex.kt | ワークスペース全体の `id -> 出現一覧` 索引と全参照。**ディスク基準**（未保存編集は反映されない）。ファイル単位の走査結果を保持し、`update` で変更ファイルだけを差し替える増分更新ができる（R8-02。§4.5） |
| `Validator` | validate/Validator.kt | 文書の検証（UI非依存）。スキーマ適合＋参照解決＋文書内ID重複＋カバレッジ（`coverageStatuses` はUIとも共用）。フェーズ考慮の必須判定と記入進捗 `phaseProgress`、1行サマリ `phaseSummaryText`（UI・CLI・エージェントツールで共用。§4.15） |
| `Workspace` / `WorkspaceScan` | core/Workspace.kt | ヘッドレスなワークスペース操作（開く・読む・保存・検証・変更セット適用）。相対パス解決はワークスペース外への脱出を拒否。`noteDiskChanges` が保存・外部変更の索引更新方針（増分／スキーマ変更時の全再構築）を一元化し、UI（AppState）もこの経路を通る（R9-02） |
| `EditBuffer` / `WorkspaceRecords` | core/Documents.kt | 文書キャッシュ層の中核（R9-02）: 履歴（Undo/Redo）・dirty判定・複数ドキュメント再出力（`EditBuffer`）と、一覧レコード収集・一括編集（`WorkspaceRecords`。未保存優先の loader 注入）。`app.OpenDocument` / `AppState` はこの層への委譲＋Compose購読ミラー |
| `PatchOp` / `Patcher` / `ChangeSets` | core/Patch.kt | YPathパッチ（set/insert/delete/move）と変更セット（baseハッシュ競合検出、YAML解析） |
| `FileWatcher` | core/FileWatcher.kt | WatchService による再帰的なファイル監視（R8-03）。「変更があったか」のフラグだけを通知し、差分判定は呼び出し側のスタンプ比較に委ねる。使えない環境では open() が null（ポーリングへフォールバック） |
| `Bench` | core/Bench.kt | 索引性能の計測（R8-02）: ベンチマーク用ワークスペースの生成と、フル再構築 vs 増分更新の比較計測。CLI `bench` |
| `Disclosure` | core/Disclosure.kt | 段階的開示: カタログ→骨子→ノード全文、文字数予算付き文脈バンドル |
| `Search` | core/Search.kt | 構造検索: 本文・キー部分一致＋スキーマ／glob条件、参照・未解決参照の検索 |
| `ViewDef` / `DocViewDef` / `Views` | core/Views.kt | ビュー機構（P6 / P14）: `_views` 定義のロード（`kind: grid / document`）、グリッド＝行（論理ID）×列（相対YPath）の解決＝原本座標化、バックリンク列（被参照の逆引き。R7-02）、文書ビュー＝文書単位の表示合成（sections の解決 `resolveDocument`。R14-01）、データパスからのスキーマフィールド解決（`fieldAt`）、詳細文書スキャフォールド、テキスト表現（`renderText` / `renderDocText`） |
| `runCli` | core/Cli.kt | CLIコマンド（catalog/outline/read/bundle/search/validate/apply/hash/activities/agent/watch/revert/metrics/eval）。出力はUTF-8のYAML |
| `ToolChatSession` / `ToolChat` | ai/ToolChat.kt | ツール使用（function calling）対応のチャット抽象と OpenAI Responses / Anthropic Messages 実装。会話状態はセッションが保持。Azure は OpenAI 互換プリセットとして `OpenAiToolSession` を共用する |
| `AiConnection` / `AiHttp` | ai/Connection.kt, ai/Http.kt | OpenAI互換エンドポイントの接続先解決（ベースURL・認証ヘッダ形式・APIバージョン。Azure プリセット・ollama / vLLM 等）と、AI呼び出し用 HttpClient の単一ファクトリ（プロキシ・Basic認証。R9-07・R9-08。§4.18） |
| `GenerationRun` | agent/Generation.kt | AI生成のカーネル統合（R9-01）: 単発生成を「1ターンで提出する特殊アクティビティ」として実行する。Structured Outputs の生成値を疑似セッションが apply_patch / submit_changeset に変換し、ステージング・権限強制・実行記録を通常実行と共有する（§4.19） |
| `AgentDef` / `ActivityDef` / `Definitions` | agent/Definitions.kt | `_agents` / `_activities` 定義の実行時ロード（エラー収集つき）。フォーム編集・検証は `schema/BuiltinSchemas.kt` の組込みスキーマが担う |
| `Staging` | agent/Staging.kt | エージェントの作業オーバーレイ。パッチ記録・新規文書・オーバーレイ索引・影響ファイルを含む横断検証・変更セット化 |
| `AgentToolbox` | agent/Tools.kt | エージェントに公開する13ツール（探索・編集・検証・対話・管理）。書込権限（許可glob・予約ディレクトリ禁止）をツール側で強制 |
| `AgentKernel` / `AgentHost` | agent/Kernel.kt | マルチターンのツール使用ループ。上限（ターン・トークン・時間）、終了手続きの強制、質問の中継、イベント通知 |
| `RunLog` | agent/RunLog.kt | 実行記録を `_runs/` にYAML保存し、採否・自動適用バックアップ・巻き戻しを追記。会話記録（`AgentRunResult.transcript`＝モデルが見た・出したメッセージの全文。保存時は1件2万字で打切り）と生成実行の `exchange` 節（extras）もここで直列化する |
| `AutomationEngine` | agent/Automation.kt | 自動化エンジン: トリガ監視（`tick(now)`）・直列実行キュー（`processOnce`）・承認ポリシー・連鎖・リトライ・常時停止。時刻注入でテスト可能 |
| `AutoApply` | agent/AutoApply.kt | 巻き戻し可能な適用: 適用前原文を実行記録へバックアップし、`revert` で復元（適用後の変更は競合として拒否） |
| `SkillDef` / `Skills` | skill/Skills.kt | `_skills` の定義（import / export の宣言型変換と run のコード実行）のロードとパース |
| `Runner` | skill/Runner.kt | コード実行skill（R9-06）: 人間の明示承認（`Approval`。定義内容の指紋に紐づき定義変更で失効）を必須とする外部コマンド実行。実行後に `writes` 宣言外・予約ディレクトリの変更を検出して報告。プロセス起動は注入可能（§4.21） |
| `Table` / `Tables` | skill/Tables.kt | 表形式の共通抽象と xlsx / csv / json / markdown / docx（読み）/ html（書き）の読み書き（セル値は型を保持） |
| `Importer` | skill/Importer.kt | 表 → 変更セット。新規行は文書作成、既存文書はマップ項目のみ更新（値が変わる場合だけ）。エラーは行・列つきで宣言的に報告 |
| `Exporter` | skill/Exporter.kt | 対象glob×行×列 → 派生成果物（xlsx / csv / markdown / json / html）。`source.view` で `_views` のビュー定義を行×列の源にできる（R9-05）。YAML出力は拒否 |
| `TemplatePacks` | skill/TemplatePacks.kt | テンプレートパック（コード埋め込み）。`init` で展開、既存ファイルは上書きしない |
| `RunMetrics` | agent/Metrics.kt | 実行メトリクス（R4-01）: `_runs` の実行記録だけから提出率・採用率・却下ファイル率・自動適用/巻き戻し・コストを集計（実行時に追加記録しない） |
| `EvalDef` / `Evals` / `EvalRunner` | agent/Evals.kt | 評価セット（R4-02）と比較実験（R4-04）: `_evals` 定義のロード、サンドボックスコピー上でのケース×変異の実行、宣言的期待結果の判定、レポート保存 |
| `ActivityRail` / `LedgerSpace` | ui/Shell.kt | UIシェル（§4.24）: 4スペースを切り替えるレールと、一覧・ビューグリッド・グラフ・ID横ぐしをタブで束ねる台帳スペース |
| `RunController` / `RunsSpace` | ui/RunsSpace.kt | 実行スペース: 受信箱（承認待ち・質問・競合の射影）・手動実行（選択→進捗→レビュー→適用）・自動化・計測評価のタブ。実行状態機械は `RunController`（AppRoot 生存期間）が保持し、`ReviewContent` は手動実行と承認待ちレビューで共用 |
| `DesignSpace` | ui/DesignSpace.kt | 設計スペース: 予約ディレクトリの定義文書の種類別カタログ・新規作成・スキーマ推測CTA。編集は編集スペースの既存エディタへジャンプする |
| `AutomationSection` | ui/AutomationPanel.kt | 自動化タブ: 監視ON/OFF・キュー・履歴・ログ・停止（承認待ちレビューは受信箱） |
| `ImprovementSection` | ui/ImprovementPanel.kt | 計測・評価タブ: 実行メトリクスの表示・評価セットの実行と変異別結果の比較 |
| `SkillsSection` | ui/SkillsPanel.kt | 連携（skill）: 一覧・エクスポート実行・インポートのプレビュー→検証つき適用（設計スペースに常設） |
| `OpenDocument` | app/AppState.kt | 開いている1文書。中核（履歴・dirty・複数ドキュメント再出力）は core.EditBuffer へ委譲し、Compose購読（version / dirty / parseError）のシェルに徹する（R9-02）。外部競合の管理を担う |
| `AppState` | app/AppState.kt | アプリ状態。走査・スキーマ・索引・更新方針は保持する core.Workspace へ委譲し、Compose購読用のミラー（schemas / yamlFiles / indexVersion）・文書キャッシュ（docCache）・ナビゲーション・ファイル操作を担う（R9-02）。閲覧専用モード（`readOnlyMode`。R10-04）・ワークスペース履歴（`recentWorkspaces`。R10-05）・スペース（`uiSpace` / `showSpace`。R12-01・§4.24）もここが持つ |
| `AiGenerator` ほか | ai/ | スキーマ→JSON Schema 変換、REF候補の enum 制約、プロバイダ振り分け |
| `OpenAiGenerator` / `AnthropicGenerator` | ai/ | 各API（Responses / Messages）のリクエスト構築とレスポンス解析 |
| `FormView` / `FormCtx` | ui/FormView.kt | スキーマ駆動フォームと汎用エディタ（中核UI）。`FormCtx` が編集操作と参照解決を仲介 |
| `IdCrossView` | ui/IdCrossView.kt | ID横ぐしビュー: 同一論理IDの全出現・参照・被参照・カバレッジ充足の集約表示。各出現はその場で編集できる埋め込みフォーム（`_views` 定義不要。§4.16） |
| `ViewGridView` | ui/ViewGridPane.kt | ビューグリッド: `_views` 定義の編集可能な一覧表示。セル編集は文書キャッシュ上のツリー直接編集（Slot＋markEdited）、行アクションで詳細文書作成・新規行追加 |
| `Slot` (Map/Seq/Root) | ui/Common.kt | 「値の置き場所」の抽象。後述 |
| `AiGenerationHost` | ui/AiGenerationDialog.kt | AI生成ダイアログの状態機械（入力→生成→プレビュー→適用）と提案合成 |
| `SchemaEditor` | ui/SchemaEditor.kt | `_schemas` 用の構造化エディタ（設計・プレビュー・適用対象） |
| その他 ui | FileTreePane / SourcePane / DiffPane / SidePanel（IDインスペクタ: 検証・関係・出現の3タブ。R12-03） / WorkspaceViews / Common | 各ペイン。[FEATURES.md](FEATURES.md) の対応表を参照。テスト可能な純ロジックは `ui/SourceHighlight.kt`（YAMLトークナイザ・置換）、`ui/GraphLayout.kt`（力学レイアウト）、`DiffPane.calculateDiff` / `charDiffRanges` に切り出す |

## 4. 設計方針（変更時に守ること）

### 4.1 ツリーが単一の正（single source of truth）

- `OpenDocument.root: YNode` が唯一の編集対象。フォームはツリーを**直接ミューテート**し、`markEdited()`（= `version` インクリメント）で再コンポーズを駆動する。
- `YNode` は意図的に「Composeの`State`ではない単純な可変オブジェクト」。UI側は `doc.version` / `state.indexVersion` を読むことで再コンポーズを購読する。新しいビューを書くときはこの購読を忘れないこと。
- ソース編集は例外的にテキストを持つが、「変更を反映」でパースしてツリーに合流させる。保存は常に `canonicalText()`（ツリーの正規化ダンプ）を書き出す。

### 4.2 履歴・dirty 判定は正規化テキストで行う

Undo/Redo は `YamlIO.dump` したスナップショット文字列の履歴（最大100件）。dirty は保存時スナップショットとの文字列比較。ツリーの構造比較は実装しない。

### 4.3 Slot 抽象

マップのキー（`MapSlot`）・配列要素（`SeqSlot`）・文書ルート（`RootSlot`）を `Slot`（get/set/remove）で統一し、スキーマフォームと汎用エディタがエディタ部品を共有する。新しい「値の置き場所」（例: `_document` メタデータ）が必要なら `Slot` 実装を追加する。ツリー上の絶対 `YPath` から `Slot` を得る `slotAt`（`ui/Common.kt`）は、ビューグリッドとID横ぐしビューの両方が共有する。

### 4.4 YPath ベースのナビゲーション

要素位置は `YPath = List<Any>` で統一。検証結果・ハイライト・ジャンプ・AI生成対象はすべて YPath で紐付く。`state.highlightPath` に設定すると `Highlightable` がスクロール＋一時ハイライトする（スクロールは要素の上下に余白を含めた矩形を可視化要求し、対象を画面端でなく**中央寄り**に出す。UX-10）。**ハイライト対象が畳みの中にある場合は自動展開する**（UX-10）: 畳まれた区画の中は `Highlightable` がコンポーズされずスクロールもハイライトも届かないため、畳みを持つエディタ（配列・ネストobject・map・テーブル行展開）は `FormCtx.highlightInside(path)`（対象が自パス配下か）で展開する。新しく畳みを持つUIを作るときも同じ判定を入れること（展開はジャンプ後も維持し、勝手に畳み直さない）。ジャンプ・ファイル切替（`openFile`）、文書内ジャンプ（`navigateToPath`。検証パネル等）、ID指定でのビュー遷移（`openCrossView` / `openViewGrid`。横ぐしのID切替・グリッドのビュー切替を含む）は直前位置（`NavLocation`＝文書ならファイルとハイライト位置、ビューならビュー番号と選択ID）を「戻る」履歴へ積み、`AppState.goBack` / `goForward` で往復できる（ブラウザ同様、新しい移動で「進む」履歴は破棄）。UI側で `highlightPath` / `crossViewId` / `viewGridId` を直接代入すると履歴に残らないため、移動は必ずこれらの経由メソッドを通す。

### 4.5 索引はディスク基準（更新は増分）・UIはオーバーレイ

`IdIndex` は保存済みファイルから構築される（ディスク索引＝正）。未保存編集中の参照解決は、`DocScanner.scan` を編集中ツリーへ都度実行した「ローカルID」を索引に**重ねて**判定する（`FormCtx.resolvable`, `Validator`）。この二層構造を崩さないこと。

**UIのオーバーレイ索引（UX-03）**: 人間のUIは「見たままの状態で判定する」（未保存で確認→OKなら保存）ため、`AppState.overlayIndex()` が **dirty な文書キャッシュの編集中ツリーだけを重ねた索引**を返す（`IdIndex.overlay`＝走査結果の複製＋dirty分だけの増分走査。`(indexVersion, editVersion)` でキャッシュ）。UI層（検証・カバレッジ・ビュー行集合・ID横ぐし・参照解決・被参照）は `state.index` ではなく **`state.overlayIndex()` を読む**のが規約。ディスク索引が正であることは不変（保存・CLI・エージェントは従来どおりで、エージェントはステージングのオーバーレイ索引を別に持つ）。オーバーレイ由来の値を `remember` する場合はキーに `state.editVersion` を含めること（含めないと他文書の未保存編集に追随しない）。

更新方式（R8-02）: 索引はファイル単位の走査結果（`byFile`）を保持し、集約ビュー（`entries` / `refs`）はそこから導出する。保存・外部変更・変更セット適用では `update`（変更・削除ファイルだけの再走査）を使い、コストを変更ファイル数に比例させる。**スキーマが変わったときは全ファイルの走査規則が変わるため必ず `rebuild`（全再構築）を使う**（`AppState` / `core.Workspace` は `_schemas/` 配下の変更、および派生スキーマの導出元文書（R11-03。§4.23）の変更を見て自動で振り分ける）。計測は CLI `bench`（`core/Bench.kt`）で行う。

### 4.6 ID・参照のセマンティクス

- 同一IDの複数文書出現は「同一論理要素の段階的詳細化」として正当。エラーは同一文書内の重複のみ。
- 参照の解決可否は `idPatterns` × `scope.schemaIds` × `scope.documentIds` のAND（各配列内はOR）。候補表示と検証で同じ判定（`IdEntry.isAllowedBy`）を使う。判定ロジックを追加する場合も候補・検証・AI制約（`refCandidateIds`）の三箇所で一貫させる。
- 参照には任意の**関係種別**（`relation`）を付けられる。関係種別は解決可否には影響せず、表示（SidePanel・横ぐしビュー・グラフ・ビューのバックリンク列）と**カバレッジ判定**にのみ使う。
- カバレッジルール（スキーマの `coverage`）は2種類: **参照ベース**（`requiredBy`）＝「対象IDが、条件（参照元スキーマ・関係）を満たす参照を minCount 件以上受けること」、**出現ベース**（`detailIn`。R7-02）＝「対象IDが、指定スキーマの文書に minCount 件以上出現すること」（詳細文書の存在検証）。1ルールに両方は書けない（パース時エラー）。判定は `Validator.coverageStatuses` に一元化し、検証（Issue化）とUI表示の両方から使う。索引基準のため未保存編集は保存まで反映されない。
- **段階的詳細化の規約**（R7-02）: 1:1 の詳細化は**同一ID**の複数文書出現（詳細文書の存在は出現ベースのカバレッジで検証）、1:N の分裂（例: 1エンティティ→単票・明細の2テーブル）は**新ID＋親への `ref`**（`relation` で種別を宣言）で逆リンクする（親側は relation 限定の参照ベースカバレッジで検証、親→分裂先の一覧はビューのバックリンク列で表示）。

### 4.7 スキーマの `$ref` 解決と実効フィールド

`SchemaDef.resolve()` は参照先定義に呼び出し側の `key`/`label`/`required` 等を合成して返す。フィールドを使う側は**必ず** `schema.resolve(field)` を通す（`FormView`・`Validator`・`DocScanner`・`ai` すべて同じ流儀）。ローカル参照は `$ref: definition`、横断参照は `$ref: moduleId#definition`。`SchemaLoader` は全ファイルをパースしてから `SchemaCatalog` を構築する二段階ロードを行うため、ファイル順に関係なく横断参照を解決できる。

`kind: library` のスキーマ文書は `definitions` だけを公開し、`matches()` は常に false になる。library は `match` / `root` / `phases` / `coverage` を持たない。各 `FieldDef` は内部的に宣言元モジュールを保持するため、libraryから解決した定義の子に非修飾 `$ref` があっても、そのlibrary内を基準に解決する。definitionsを利用側へマージしてはならない（名前衝突と参照元の喪失を防ぐため）。

enum の `cases`（値ごとの追加フィールド。判別共用体）を持ちうる object の子フィールドを列挙する側は、**必ず** `schema.effectiveFields(fields, node)` を通す（`Validator`・`FormView.ObjectFields`・`DocScanner`・初期値生成 `defaultNode`/`defaultFor`/`generateSample` すべて同じ流儀）。データノードの enum 値に応じて case フィールドが判別フィールドの直後に合成され、追加されたフィールド自身が `cases` つき enum なら同じ親 object 上で再帰的に展開される。非アクティブな case のキー（→ 有効になる値）の逆引きも同時に得られる。素の `field.fields` を直接列挙すると case フィールドが漏れる。なお `ai.fieldToJsonSchema` のみ、データノードが存在しない（これから生成する）ため `effectiveFields` を使わず、判別 enum が1つなら値ごとの `anyOf` 分岐、複数なら全 case の和集合に変換する。

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
- **監視はイベント駆動＋フォールバック**（R8-03）: `start` のループは、ネイティブ監視（`core.FileWatcher`）が使えるときは変更イベント時と10秒毎のフォールバック時だけスタンプ全走査を行う（`tick(now, scanChanges = false)` で走査を省略。デバウンス発火とスケジュール判定は毎回行う）。

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
- **宣言型が既定**: import / export にコード実行はない（設計原則3・§4.3）。宣言型で書けない変換・定型手順だけが、明示許可制のコード実行skill（kind: run。§4.21）の対象になる。宣言型で書ける変換を run へ逃さない。
- **形式は Table 抽象に集約**: xlsx / csv / json / markdown / docx / html の差は `skill/Tables.kt` に閉じる。形式追加はここだけを触る。
- **ビュー参照エクスポートは既存機構の合成**: `source.view`（R9-05）は `Views.load` / `Views.resolve` の結果をそのまま表にする。ビュー専用の解決ロジックを Exporter 側に持たない（列・行・バックリンクの解決は §4.14 と同一）。
- **`_skills` も予約ディレクトリ**: 通常のエージェントは書込不可、メタアクティビティのみ提案可（パースゲートつき）。自動実行の監視対象外。

### 4.14 ビュー機構（P6 / P14）

`_views` の宣言的ビュー定義には2種類がある: `kind: grid`（省略時既定）は「行（＝論理ID）×列（＝出現要素からの相対YPath）」の横断的な編集面、`kind: document`（R14-01）は文書単位の表示合成（フォーム表示をセクションの並びとして組み立てる）。守るべき不変条件:

- **ビューはデータを持たない**: ビューは原本の射影であり、独自の永続化を一切持たない。セルは必ず原本上の座標 `(file, YPath)` に解決され、編集はその座標の原本ツリーへルーティングされる（原本主義）。
- **編集経路は既存と同一**: UIのセル編集は文書キャッシュ（`AppState.ensureDoc`）上のツリーを `Slot` 経由で直接変更し `markEdited()` する（＝dirty・Undo・保存・外部変更検出がそのまま効く）。エージェントは `read_view` で座標を得て既存の `apply_patch` で編集する。**ビュー専用の書込経路を作らない**（権限強制・ゲートを一元に保つ）。
- **行は索引基準・値は編集中優先**: 行の集合（ID）と座標は注入された `IdIndex`（§4.5）から解決し、セル値の表示は loader 注入で「編集中ツリー（UI: docCache / エージェント: ステージング）→ ディスク」の順に読む。UIはオーバーレイ索引（`AppState.overlayIndex()`。UX-03）を渡すため、**未保存の新規IDも行に現れる**（旧 L-14「行集合はディスク索引基準」はオーバーレイ索引の導入で改訂された）。ヘッドレス経路（CLI・エクスポート）はディスク索引のまま。
- **`_views` は予約ディレクトリ**: 書込はメタアクティビティのみ（`AgentToolbox`）、自動実行の監視対象外（`AutomationEngine`）、ステージされた定義は `Views.parseView` のパース可否が提出ゲートに加わる（`Staging.validateAll`）。
- **セルエディタの型解決**: 列の相対パスから `Views.fieldAt` でフィールド定義を辿る（`resolve` / `effectiveFields` を通す。§4.7）。直接編集はスカラー系フィールドに限定し、複合値はジャンプで個別フォームへ誘導する。
- **バックリンク列は編集不可の派生表示**（R7-02）: `backlinks` 列は索引の被参照を relation・参照元スキーマで絞って解決し、出現の選択（`path` / `schemaId` / `fileGlob`）や `editable` とは排他（パース時に拒否）。参照元要素のIDは「参照位置をパスの接頭辞に含む、最も長いパスの出現」として解決する。セルが指す座標は参照値の位置＝ジャンプ先であり、書込はしない（被参照を変えるには参照元文書を編集する）。
- **新規行の入口は2モードで、どちらも原本への通常書込**（R15-01）: `newRow` は配列モード（`file`+`path`: 一覧文書の配列へ要素追加＝未保存編集として積む）とファイルモード（`filePattern`: `{id}` 置換先へ詳細文書をスキャフォールドしディスクへ書込＝索引更新で行が即座に現れる）の排他。ファイルモードは一覧文書を持たず詳細文書だけでIDを運用するレイアウト向けで、スキャフォールド（`Views.scaffold`）は `detail.filePattern` の「作成」ボタンと共通（作成先にマッチするスキーマの必須初期値＋IDフィールド設定）。リントは作成先のスキーマ有無と `detail` 認定条件との整合を検査する。ビューがデータを持たない原則は変わらない——ビューが宣言するのは「新しい原本がどこで生まれるか」だけ。

文書ビュー（`kind: document`。R14-01）に固有の不変条件:

- **スキーマ＝データ契約、ビュー＝画面の合成**という責務分離の実現形。表示決定の優先順位は「①文書ビュー（合成）＞②スキーマの固有表示ヒント（`role` / `display` / `label`。`Display` §4.22 の共有契約として存置）＞③予約語規約」。**今後、画面合成のためのオプションをスキーマ（`FieldDef`）へ足さない**——合成の要望はビュー側の語彙で受ける。
- **マッチしない文書は従来のスキーマ駆動フォーム**＝「現行表示は暗黙の既定ビュー」。文書ビューは opt-in の上書きであり、適用は `match`（glob）／`schemaId` の AND、複数定義は定義ファイル名順の先勝ち（スキーマの `match` と同じ規則。`LoadResult.docViewFor`）。ビューは台帳層の機構のため台帳の文書（`WorkspaceLayout.inLedger`）に限る。
- **fields セクションは既存フォームへの委譲**: ルート全体（`path` 省略）は従来のルート描画（`SchemaRootForm`）を、部分表示（`path` 指定）は `Views.fieldAt`＋`slotAt`＋`FieldRow` をそのまま使う。編集経路・`readOnly`・`markEdited` は通常のフォームと同一で、文書ビュー専用の編集UIを作らない。
- **backlinks セクションは編集不可の派生表示**: グリッドのバックリンク列と同じ解決規則（索引の被参照を relation・参照元スキーマで絞る。参照元要素は `IdIndex.sourceEntryOf`）で、行＝文書内のID（`idPattern` で絞り込み・文書内の出現順）。`columns` は参照元要素からの相対YPathの値を表示専用で添える。座標は参照値の位置＝ジャンプ先であり、被参照を変えるには参照元文書を編集する。
- **人間とAIが同じ面を見る**: `read_view`（`file` 引数。適用対象が1件なら省略可）・CLI `view <id> [file]` が同じ解決（`resolveDocument` / `resolveAttachments` / `renderDocText`）を返す。エクスポート（`source.view`）はグリッドのみ対象（文書ビューは定義段階で拒否）。
- **選別・アタッチも既存経路の合成**（R14-03）: fields の `include` / `exclude` は `ObjectFields` の描画対象の選別であり、編集経路・実効フィールド合成（`effectiveFields`）は変えない。要素単位アタッチ（`attachments`）は解決結果を `FormCtx.attachments`（要素パス → 被参照行）で運び、`ObjectFields` の**末尾**で描画する——ルート・ネスト object・配列要素・テーブル行展開のすべての描画経路に自然に効き、専用の描画分岐を作らない。差し込まれる被参照表は backlinks セクションと同じ解決（`DocResolver` 共有）・同じ読み取り専用原則で、対象要素にIDがなければ何も差し込まれない（`at` の解決可否とID有無はリント対象）。

definition 参照とリント（R14-02）に固有の不変条件:

- **named definition がビューの一級語彙**: ビューからスキーマ構造を指す単位は named definition（`module#name`。`SchemaCatalog` の修飾名。非修飾名は `schemaId` から補完）に統一する。無名のネスト位置を指す第二のアドレス体系を作らない——ビューから参照したい構造は `definitions` へ括り出すのが運用規約。グリッド列の `definition:` は定義のセル型フィールド（`Display.tableColumns` と同じ規則。ID型は行キーと重複するため除外）を列へ展開し、ラベル・パスをスキーマから導出する。展開は**パース時**（schemas が必要）に行われ、展開後は通常の列と完全に同一（resolve・グリッド・エクスポート・`read_view` に分岐なし）。
- **リントは load 時の助言**: `Views.load(workspace, schemas)` はビュー→スキーマの参照（schemaId・パス・definition）の整合を検査し、指摘を errors として返す（**ビューは無効化しない**——解決・表示は従来どおり）。パスの検査は「スキーマのID要素（ID型フィールドを持つ object）のいずれかから静的に解決できるか」（`staticFieldAt`。出現要素は必ずID要素のため）。enum `cases` のフィールドは全ケースの和で「存在しうる子」として扱い、ID要素が見つからないスキーマは判定しない——**誤検知で作者を煩わせない**ことを正しさより優先する（見逃しは許容）。
- **definition の存在はパースで拒否**: 未知の definition への参照は（助言でなく）パースエラーであり、ステージングの提出ゲート（`Staging.validateAll` → `parseView(raw, source, ws.schemas)`）でも自動的に拒否される。schemas を渡さない素の `parseView` では definition を使う定義がエラーになる（本番経路はすべて schemas を渡す）。

### 4.15 フェーズ機構（P7: 状態依存の検証プロファイル）

段階的詳細化の工程進行（要件定義→論理設計→物理設計など）を検証が理解するための機構。守るべき不変条件:

- **フェーズは文書が申告する**: スキーマは `phases`（順序付きのフェーズ一覧と申告キー。既定キー `phase`）を宣言し、文書はルート直下のそのキーで現在フェーズを申告する。申告がない・未知の値の場合は**先頭フェーズ**（最も緩い判定）として扱い、未知の値には警告を出す。ワークスペース全体のフェーズという概念は持たない（文書単位）。
- **`requiredFrom` は累積的**: 「そのフェーズ**以降**は必須」。現在フェーズより先のフェーズに属する未記入は**エラーにしない**（エラーの洪水を防ぐ）。`required: true` は従来どおり無条件必須で、`requiredFrom` との併記は定義エラー。`$ref` 解決では呼び出し側の `requiredFrom` が優先合成される。
- **カバレッジも同じフェーズで判定**: `CoverageRule.activeFrom` は「IDを**定義している文書**の現在フェーズがそのフェーズに達してから判定する」。判定主体は常に定義側文書（カバレッジ指摘が表示される文書）のフェーズであり、参照元のフェーズではない。
- **未到達は進捗として見せる**: 未来フェーズの未記入はエラーではなく記入進捗（`Validator.phaseProgress`: フェーズ×記入済み/総数）として可視化する。進捗の集計対象は `requiredFrom` を持つフィールドのみ（無条件 `required` は従来どおり検証エラーで扱う）。存在しない配列要素・Map項目は数えない（構造が確定してから数える）。
- **判定は Validator に一元化**: フェーズの解決（`currentPhaseIndex`）と実効必須（`requiredAt`）は `schema/Schema.kt` の拡張関数に置き、検証・進捗計算が共有する。フォーム表示は `requiredFrom` を「（フェーズ名〜必須）」の注記として表示し、無条件 required の * マークとは区別する。
- **エージェントのゲートに自然に乗る**: 提出ゲート（`requireValid` / `Staging.validateAll`）は ERROR 件数で判定するため、フェーズ未到達の未記入はゲートを塞がない。「フェーズを進める」作業自体をアクティビティ化すると、進行の合格条件が `phase` 検証エラー0として機械化できる。
- **フェーズ状態はエージェントにも能動的に見える**: `Validator.phaseSummaryText`（現在フェーズ＋進捗の1行サマリ）を `agent/Tools.kt` の `related`（出現ごと）・`catalogText`（文書ごと）・`core/Disclosure.kt` の `catalog` / `bundle` が共用する。エージェントは検証エラーを待たずに「次に何を埋めれば前進するか」を探索段階で把握できる。アクティビティ定義の `context.include: [phase]`（`agent/Kernel.kt`）は対象文書のフェーズ状態を初期文脈に載せ、フェーズ前進アクティビティの起点にする（FEATURES AG-25）。

### 4.16 編集可能なID横ぐしビュー（P7: R7-04）

`_views` 定義を書かずに、同一論理IDの全出現を1画面で編集する。守るべき不変条件:

- **ビュー機構の書込原則をそのまま適用**: §4.14 の「ビュー専用の書込経路を作らない」をID横ぐしビューにも適用する。出現の編集は `AppState.ensureDoc` で文書キャッシュへ取り込んだツリーを `Slot` 経由で直接変更し `markEdited()` する（`_views` のビューグリッドと同一経路。dirty・Undo・保存・外部変更検出がそのまま効く）。
- **フィールド解決はビュー機構と共有**: 出現の要素（`IdEntry.path`）にあるスキーマの object フィールド一覧は `Views.fieldAt(schema, root, entry.path)` で解決する（ビューグリッドのセル型解決と同じ関数。専用の解決ロジックを新設しない）。取得した `FieldDef.fields` は `ui/FormView.kt` の `ObjectFields` にそのまま渡し、フォームの行UI・型別エディタ・参照解決・enum `cases` 合成（§4.7）を複製しない。
- **スキーマなし・解決失敗はフォールバック**: `Views.fieldAt` が object を返さない場合（スキーマなし文書、または想定外の構造）は `GenericEditor` へフォールバックする。ルート直下の出現（`entry.path` が空）は `RootSlot`、それ以外は `slotAt`（`ui/Common.kt`。ビューグリッドと共有）で Slot を得る。
- **`_views` を置き換えない**: 列の選別・順序・一覧としての一覧性が要る場面は `_views`、スキーマ定義だけで即座に使える編集面が欲しい場面はID横ぐしビュー、と役割を分ける。

### 4.17 コメント・表記スタイルの保持（P8: R8-01）

`YamlIO` は SnakeYAML の Node API（processComments）で読み書きし、コメント・キー順・表記スタイルを「開く→編集→保存」の往復で保持する。守るべき不変条件:

- **メタ情報は編集の意味に影響しない**: コメント（`YComments`）とスタイルヒント（引用符・リテラル・フロー表記）は `YNode` に付随するだけで、値の比較・検証・検索・AI生成の入力構造には関与させない。構造の比較・派生成果物の出力には `YamlIO.dumpPlain`（コメント・スタイル抜きの正規化ダンプ）を使う（`Importer` の変化なし判定・`Exporter` のセル値・`Evals` の期待結果比較）。
- **ノード置換時はメタ情報を引き継ぐ**: 値の置換（`Slot.set` / `Patcher` の set）では `YNode.inheritMetaFrom` で旧ノードのコメント・スタイルを引き継ぐ。スカラーの値をその場で書き換えるフォーム編集は自然に保持される。
- **残る正規化は仕様**: アンカー／エイリアスは展開、インデントは2固定、ブロック配列要素の前行コメントは SnakeYAML エミッタの制約で `- # コメント` の形に後置される（内容は失われず冪等）。この3点を「直したい」場合はエミッタのフォークが必要になるため、安易に手を出さない。
- **日付・日時は文字列のまま**: `PlainDateResolver`（TIMESTAMP を暗黙解決しない Resolver）を読み込み・書き出しの両側で使い、引用符なしの `2026-08-21` 等を `java.util.Date` へ型付けしない（型付けすると検証の現在値表示と保存形式が壊れる。UX-29）。int / boolean 等の型付けは従来どおり。
- **書込経路は単一文書を前提**: `parse` は複数ドキュメントを拒否する。読み取り専用の経路（索引・検索・開示・ビュー）だけが `parseFirst` で先頭ドキュメントを読む。複数ドキュメントの編集・保存は `OpenDocument`（`parseAll` / `dumpAll`）に閉じる（後続ドキュメントの黙殺による消失を防ぐ）。

### 4.18 AI接続機構（P9: R9-07 / R9-08）

外部通信はAIプロバイダ呼び出しに限定されており、その接続は `ai` パッケージの2点に集約する。守るべき不変条件:

- **HttpClient は `AiHttp` だけが生成する**: プロキシ（SYSTEM=システム設定尊重が既定 / DIRECT / MANUAL）と Basic 認証はここで一元管理する。プロキシパスワードはAPIキーと同様メモリのみ（永続化しない）。JDKが既定で無効化しているHTTPSトンネル（CONNECT）のBasic認証は `AiHttp` の初期化で許可する（`jdk.http.auth.tunneling.disabledSchemes`）。新しいAI呼び出しを書くときは独自に HttpClient を作らず `AiHttp.post` を使う。
- **OpenAI互換の接続先は `AiConnection` が解決する**: 「ベースURL・認証ヘッダ形式・APIバージョン」のパラメタ化で、公式 OpenAI（Bearer）・Azure OpenAI / AI Foundry（`api-key` ヘッダ＋エンドポイント＋デプロイメント名=モデル欄）・ollama / vLLM 等の互換エンドポイント（ベースURL上書き）を同一クライアント実装（`OpenAiGenerator` / `OpenAiToolSession`）で扱う。接続先の分岐をクライアント側に書かない。
- **設定の永続化はUI層**: エンドポイント・APIバージョン・プロキシ設定は `ui/AiSettings` が Preferences へ保存し、実効値を `AiConnection` / `AiHttp` へ書き通す。CLI・ヘッドレス経路は環境変数（`AZURE_OPENAI_ENDPOINT` / `AZURE_OPENAI_API_VERSION` / `OPENAI_BASE_URL`）が初期値になる。
- **一時エラーの自動リトライは送信経路に一元化する（R18-04）**: `AiHttp.post` は 408 / 429 / 5xx を `retry-after` ヘッダ（なければ指数バックオフ 2s→4s→8s＋ジッタ、上限60秒/回・3回）で待機して再送する。マルチターンのツールループが途中ターンの 429 で丸ごと失敗する（＝それまでの消費トークンが無駄になり、キュー再試行がレート制限をさらに悪化させる）ことを防ぐのが狙い。待機は `delay` ベースのため実行キャンセル（stopAll）で即中断される。判定・待機計算は純粋関数（`isRetryable` / `retryDelayMillis` / `retryLoop`）で、テストは送信台本と待機記録の注入で実時間なしに検証する（`HttpTest`）。呼び出し側（`ToolChat` / 各 Generator）でリトライを書かない。

### 4.19 AI生成のカーネル統合（P9: R9-01）

フィールド／文書の単発生成（AI生成ダイアログ）は、`agent/Generation.kt` の `GenerationRun` が「1ターンで提出する特殊アクティビティ」としてエージェントカーネル上で実行する。守るべき不変条件:

- **型保証は Structured Outputs のまま**: 生成そのものは従来どおり `AiGenerator`（レスポンススキーマによる構造化出力）で行う。ツール使用ループに生成品質を委ねない。
- **カーネルへの橋渡しは疑似セッション**: 生成値を `apply_patch`（ディスク基準の set / 配列追記は insert）→ `submit_changeset` のツール呼び出しへ変換する `ToolChatSession` 実装を `sessionFactory` として注入する。ステージング・書込権限（対象ファイル・対象パスに限定）・実行記録（`_runs`。アクティビティID `ai-generate`）は通常実行と完全に同じ経路を通る。
- **ダイアログのUXは変えない**: 適用先は編集中ツリー（プレビュー→未保存適用・Undo可・自動保存なし）のまま。未保存編集や新規文書などディスク基準のパッチが成立しない場合は「変更なしの提出」として記録し、生成値だけをダイアログへ返す。
- **入出力メッセージは実行記録に残す**: 実際に送信したシステム指示・プロンプト全文・応答スキーマとモデルの生出力・実測トークン数は、各クライアントが `AiExchange` として返し（失敗時は `AiCallException` に載せて伝える）、`RunLog.write` の extras（`exchange` 節）へ上限つき全文で記録する。生成失敗時も実行記録を書き、`GenerationFailedException` が記録パスを保持してダイアログのエラー表示に示す。デバッグ材料を落とさないため、クライアント側でプロンプトを再構成せず送信物そのものを引き渡す。
- **テストは生成器注入**: `GenerationRun.run` の `Generator` を疑似実装に差し替え、カーネル・ステージング・記録は本物を通す（`GenerationRunTest`）。

### 4.20 文書キャッシュ層の委譲（P9: R9-02）

UI層（app）を「Compose購読のシェル」に純化し、ロジックを core へ寄せる。守るべき不変条件:

- **索引更新方針は `Workspace.noteDiskChanges` に一元化**: 保存・外部変更・変更セット適用のいずれも、「スキーマ（`_schemas/`）が変わったら全再構築、それ以外は変更ファイルだけの増分更新」（§4.5）の振り分けをこの1点で行う。AppState 側に振り分けを書かない。
- **`OpenDocument` は `core.EditBuffer` のシェル**: 履歴（Undo/Redo）・dirty判定・複数ドキュメント再出力の中核は `EditBuffer`（UI非依存・単体テスト対象）にあり、`OpenDocument` は Compose 購読（`version` / `dirty` / `parseError` のミラー）と外部競合フラグだけを持つ。編集APIの意味を変えるときは `EditBuffer` を直す。
- **一覧・一括編集は loader 注入**: `WorkspaceRecords`（一覧レコード・一括編集キー候補）は「未保存編集を優先するツリー取得」を注入で受ける（§4.5 の二層構造と同じ流儀）。UIは docCache を、ヘッドレス経路はディスクを渡す。
- **AppState のミラーは読み取り専用の写し**: `schemas` / `yamlFiles` 等の Compose 状態は core.Workspace の状態の写しであり、AppState 側で加工しない。core 側を更新したら `mirrorCore` で写し直し、`indexVersion` を進めて再コンポーズを駆動する。
- **ビューグリッドの行集合はUIではオーバーレイ索引基準**（UX-03 で旧 L-14 を改訂）: 行の増減（新しいID）も dirty 文書のオーバーレイ（`AppState.overlayIndex()`）で未保存反映される。ディスク索引（`Workspace.index`）が正であること・ヘッドレス経路がディスク基準であることは不変。

### 4.21 コード実行skill（P9: R9-06）

宣言型（import / export）で書けない変換・定型手順のための実行型skill（kind: run）。サンドボックス外への作用（任意コマンド実行）を伴うため、次の明示許可制を**機械的に**強制する:

- **承認なしに実行される経路がない**: `Runner.run` は `Runner.Approval` を必須引数に取り、`Approval` は private constructor＋`approve(def)` でしか作れない。UI（skillパネルの承認ダイアログ）・CLI（`run-skill --yes`）は、コマンド内容と権限を人間に表示した後にのみ `approve` を呼ぶ。
- **承認は定義内容に紐づく**: `Approval` はコマンド・作業ディレクトリ・書込宣言・タイムアウトの指紋を持ち、実行時点の定義と一致しなければ拒否する（承認後の定義すり替え防止）。
- **エージェント・自動実行からは起動不可**: run skill はエージェントツールとして公開せず、`AutomationEngine` からも呼ばない。メタアクティビティは定義の**提案**まで（実行は常に人間の承認つき手動）。
- **事後検査つき**: 実行前後のワークスペーススナップショットを比較し、`writes` 宣言に一致しない変更を警告、予約ディレクトリの変更を重大な逸脱として報告する（書込を防げるわけではない——だからこそ事前の人間承認が前提）。ワークスペースが変更された場合は索引を取り直す。
- **プロセス起動は注入可能**: テストは疑似ランチャで承認・失効・変更検出の全経路を検証する（`RunSkillTest`。実プロセスは `java -version` のスモークのみ）。

### 4.22 表示解決と読み手体験（P10）

「IDや生値がそのまま見えて分かりにくい」を単一機構で解消する（R10-01〜R10-04）。守るべき不変条件:

- **要素の1行要約は `schema/Display` に一元化**: タイトル・サブタイトル・ID・畳み込みヘッダ（object=「ID タイトル（n項目）」／array=「n件: 先頭要素の要約, …」）の解決は `Display`（`summarize` / `titleOf` / `headerText` / `seqHeaderText` / `inline`）だけが行う。優先順位はスキーマの `FieldDef.role: title / subtitle`（`$ref` 合成・library 共有に乗る）＞予約語（title / name / label、description / summary）＞ id ＞ キー数。消費側（畳み込み・参照候補・バックリンク・ID横ぐし・ビューグリッド・ID検索・エージェント `related`）に独自のタイトル抽出ヒューリスティクスを書かない。
- **配置は schema パッケージ**: 索引のタイトル抽出（`DocScanner` → `IdEntry.title`）が使うため、依存方向（§2）上 `core` ではなく `schema` に置く。`IdEntry.title` は表示解決の結果であり、参照候補・ID検索・ビュー行タイトルはこれ経由で自動的に追随する。人間UIとエージェント要約（AG-25）の**共有契約**（人間とAIが同じ要約を見る）。
- **バックリンクの表示は「参照を書いた要素」**: 参照値の位置を含む最内の ID 出現を `IdIndex.sourceEntryOf` で解決し、そのID・タイトルを主表示にする（SidePanel・ID横ぐし・ビューのバックリンク列・`related` で共通）。
- **enum のリッチ選択肢は表示専用**（R10-03）: `values` の `{value, label, description}` は `EnumOption` として保持し、検証・`cases` のキー・AI生成の制約・保存されるデータは常に `value`。label / description をロジックに関与させない。
- **閲覧専用モードは FormCtx で強制**（R10-04）: `FormCtx.readOnly`（既定値は `AppState.readOnlyMode`）が編集UIの表示分岐と `markEdited` / スカラー書込のガード（ツリー変異前に `check` で拒否）を担う。FormCtx を共有する全ビュー（フォーム・ID横ぐし埋め込みフォーム・ビューグリッドのセル）へ自動波及させ、ビューごとの読み取り専用分岐を別実装しない。ジャンプ・バックリンクは閲覧行為として残す。
- **配列・map の表示形式も `Display` が解決**（R12-06）: フォーム内の配列（object 要素）・map（object 値）をテーブルにするかの適否と列集合は `Display.tableColumns`（自動判定＋スキーマの `FieldDef.display: table / list` ヒント）だけが決める。列は要素 object の**宣言フィールド**のうちセル型（`Display.CELL_TYPES`）のもので、enum `cases` による行ごとの追加フィールドは列にしない（列集合を全行で安定させる）——列にならないフィールドの編集は行の展開フォーム（`ObjectFields` ＝ `effectiveFields` 経由）で行う。map のキー列は UI（`MapTable`）が先頭に足す表示専用の列で、列定義には含まれない（キーの改名はテーブルの対象外）。セル編集は通常の `Slot` 経路を再利用し、テーブル専用の書込経路を作らない（ビューグリッドの流儀 §4.14 と同じ）。

### 4.23 スキーマ工学（P11: 推測・メタスキーマ・派生スキーマ）

「スキーマを書くのがつらい」を解消する3機構（R11-01〜03）。守るべき不変条件:

- **`any` 型は「検証しない」の明示**: メタスキーマ・推測結果のように値の形を単一の型で表せない箇所のためのフィールド型。検証は必須チェックのみ（型・構造は検査しない）、フォームは汎用エディタ、AI生成は `map` と同様に構造化出力を無効化（プロンプト指示へフォールバック）、索引は未知キーと同じ走査境界になる。**業務スキーマで安易に `any` へ逃げない**（検証できる型で書けるものは書く）。
- **スキーマ推測は純ロジック**（R11-01）: `schema/SchemaInference.kt` は「相対パス×ルートツリー」のリストから `_schemas` 文書のYAMLツリー（`YNode`）を構築する（I/Oなし・Compose非依存）。型推測（boolean/int/number/date/長文text）、参照認識は `DocScanner` の命名規約（`ref`/`*_ref`/`refs`/`depends_on` 等）を**流用**し、独自の規約を増やさない。enum 候補・required 候補は複数文書の合議（全文書出現＝required 候補、小さく反復する値集合＝enum 候補）。生成物は必ず `SchemaLoader.parseSchemaText` → `validateDefinition` を通る形で出力する（推測→仕上げ→適用の入口を保証）。入口は CLI `infer-schema` と ファイルツリーの推測ダイアログ（`AppState.inferSchema`）。
- **メタスキーマも組込みスキーマの流儀**（R11-02）: `builtin-schema` は `BuiltinSchemas` に置き、`FieldDef` の再帰は再帰 `$ref`（`definitions.field`）、「`type` の値ごとに持てる属性」は `cases` で表現する（S-10 のドッグフーディング）。`_schemas/` 配下の文書には**メタスキーマ（id = `builtin-schema`）だけ**を適用する（`Workspace.schemaFor`。一般スキーマの `match` は `_schemas` に及ばない——catch-all スキーマの誤適用を防ぐ）。利用者は同IDのスキーマを `_schemas` に置けば上書きできる。エージェントの `_schemas` 書込禁止は従来どおり（メタスキーマはフォーム・検証・AI生成のためであり、書込経路は増やさない）。
- **派生スキーマは「ロード時にコンパイルされる読み取り専用の成果物」**（R11-03）: スキーマ文書の `derive:` 宣言が「導出元文書（glob）→ 検証スキーマ」の写像を定義し、`SchemaLoader.loadAllWithErrors` の第3段階（パース→リンク→**導出**）で `SchemaDef` にコンパイルされる。導出されたスキーマはファイルとして存在せず（`source` は導出元文書）、`_schemas` へ書き出さない。写像の語彙（`path` / `type` / `map` / `required`）は skill の列マッピング（SK-02）と揃える。
  - **宣言側スキーマは文書にマッチしない**: `derive` を持つスキーマは自身の `match` を持てず（テンプレートに徹する）、`root` は導出スキーマの共通フィールド（先頭に合成）になる。導出スキーマの適用先は `derive.match`（`{...}` プレースホルダ＝導出元要素の値）で決まる。
  - **再検証の契機は `noteDiskChanges` に一元化**: 導出元文書（`derive.from` に一致）の保存・外部変更・変更セット適用では索引の増分更新ではなく**全再構築**（スキーマ変更と同じ扱い）を行う。判定は `Workspace.noteDiskChanges` が持ち、UI・CLI・エージェント経路が共有する（§4.20 と同じ流儀）。
  - **間接的な検証ルール変更の抑え**: 導出元文書は通常文書のため、エージェントの変更セットが（承認を経て）検証ルールを変えうる。既定の human 承認ゲートで足りると判断するが、**自動適用（approval: auto）だけは導出元文書への書込で承認待ちへ降格**する（`AutomationEngine`。検証ルールが無人で変わる経路を塞ぐ）。

### 4.24 UIシェル（P12: スペース・受信箱・インスペクタ）

提示層の再基盤化（ROADMAP P12）。機能は削らず、UIの骨格を「編集・台帳・実行・設計」の4スペース＋左端のアクティビティレールに再編する。守るべき不変条件:

- **スペースは `app.UiSpace`（EDIT / LEDGER / RUNS / DESIGN）**: シェルの大分類。`workspaceView`（0=文書, 1=一覧, 2=グラフ, 3=ID横ぐし, 4=ビューグリッド）は**文書世界の下位ビューとして維持**する。両者の同期は AppState のナビゲーション系メソッド（`showFile` / `navigateToPath` / `openCrossView` / `openViewGrid` / 履歴復元）が担い、ジャンプは常に uiSpace を EDIT（view 0）／LEDGER（view 1〜4）へ引き戻す——実行・設計スペースからの参照ジャンプが正しく着地するための規約。レール以外の場所から `uiSpace` を直接書き換えない。
- **スペースの移動も「戻る・進む」の対象**: ユーザーにとってレール切替・台帳タブ切替・スペースからのジャンプはすべて画面の遷移のため、`NavLocation` はスペースを含み、`showSpace` / `showWorkspaceView` / `openCrossView` / `openViewGrid`（到着時）も履歴に積む。復元は EDIT=ファイル＋ハイライト、LEDGER=下位ビュー＋選択ID、RUNS / DESIGN=スペースのみ（下の文書世界には触れない）。同じ場所の再選択は積まない。検証は `NavigationHistoryTest`。
- **モーダルの新設禁止（確認ダイアログを除く）**: エージェント実行・自動化・計測評価・skill 操作は実行／設計スペースの常設画面で行う。AlertDialog を使ってよいのは破壊的操作・承認の確認（CONVENTIONS §2）だけ。エージェント質問（ask_human）もモーダルにせず、受信箱・実行タブの非モーダルカードで「文書を確認しながら回答できる」状態を保つ（気づきはレール・ステータスバーのバッジが担う）。
- **エージェント実行の状態は `RunController` へ退避**: 旧 AgentRunHost がコンポーザブルの `remember` に持っていた実行状態機械（選択→実行→レビュー→完了、イベントログ、質問、Job）は `ui/RunsSpace.kt` の `RunController`（AppRoot 生存期間・ワークスペース単位に生成）が持つ。スペースを切り替えて実行画面がコンポジションから外れても実行・レビュー状態が失われないための構造で、**実行状態を再びコンポーザブルローカルへ戻さない**。
- **受信箱はデータを持たない射影**: 承認待ち（`AutomationEngine.pendingSnapshot`）・質問（`RunController.pendingQuestion`）・外部変更競合（`AppState.conflictedDocs`）を集約表示するだけで、各機構が正。バッジ数もこの集約から導出する。
- **実行スペースの表示はプロファイルで段階的開示**: `_agents` / `_activities` の定義（または承認待ち・実行中状態）があるときだけレールに出す（R12-05）。判定は `AppState` の走査ミラーから導出し、別途の設定ファイルを増やさない。
- **設計スペースは「定義もYAML文書」（§4.2）の入口**: 種類別カタログはファイル一覧（`_schemas/` 等のプレフィックス）から導出し、編集は既存のエディタ（編集スペース）へジャンプする。定義専用の編集経路・書込経路を新設しない。

### 4.25 ワークスペース構造（P13: `.yde` 構成・`ledger` 宣言・エディタ層／台帳層）

ワークスペースの物理配置と機能の適用範囲を分離する機構（R13-01〜03）。守るべき不変条件:

- **配置の判定は `schema/WorkspaceLayout` に一元化**: 「どこが定義・記録ディレクトリか（`zoneOf` / `isReserved`）」「どこが台帳か（`inLedger`）」「索引から除外するか（`indexExcluded`）」の判定を単一のポリシーオブジェクトが持つ。従来 `startsWith("_")` の直書きが兼ねていた3つの意味（索引除外・書込可否・スキーマ適用の特例）はこの述語群に分解されている。**機能側でプレフィックスやディレクトリ名の直書き判定を書かない**。配置が core でなく schema パッケージなのは `SchemaLoader` / `BuiltinSchemas` が使うため（§4.22 の Display と同じ理由）。
- **2つの構成モード**: `.yde/` ディレクトリが無ければ**レガシー構成**（ルート直下の `_schemas` 等7予約ディレクトリ・台帳=ワークスペース全体＝従来動作そのまま。既存テスト・既存ワークスペースの互換はこのモードが保証する）。あれば **`.yde` 構成**（定義・記録は `.yde/schemas` 等アンダースコア無しのサブディレクトリ・`.yde/workspace.yaml` の `ledger:`（文字列またはリスト）で台帳ディレクトリを宣言。未宣言なら全体）。判定は `WorkspaceLayout.of(root)`。`Workspace` が保持し `refresh()` で再評価する。`.yde/workspace.yaml` の変更は走査規則が変わるためスキーマ変更と同じ扱い（`noteDiskChanges` が全再構築へ振り分け）。レガシー→`.yde` の移行は CLI `migrate`（ディレクトリ移動＋構成ファイル作成のみ。文書・定義の中身は書き換えない）。
- **エディタ層／台帳層の分割基準は「単一ファイルで完結するか」**: スキーマ適用（match→フォーム・単文書検証・表示解決）は1文書とスキーマ定義で完結するため**エディタ層**＝台帳外のYAMLにも効く。`match` は常に**ワークスペース相対**の単一座標系（root相対にしない。スキーマが台帳外にも当たるため）。**台帳層**（ID索引・参照解決・バックリンク・カバレッジ・ビュー・エージェント・自動化・入出力skill・検証の集計）は `inLedger` のファイルだけを対象にする。`ref` の解決・未解決検出は索引前提のため、台帳外の文書では未解決参照を情報レベルへ降格する（`Validator.validate` の `indexed` フラグ。呼び出し側が `layout.inLedger` を渡す）。
- **走査は台帳＋構成ディレクトリのみ**: `WorkspaceScan` は隠しディレクトリを枝刈りし（構成ディレクトリ `.yde` だけ例外）、`ledger` 宣言時は台帳外を含めない。索引・外部変更スタンプ・自動化トリガ・カタログはこの走査に載るものだけが対象。台帳外YAMLの表示・編集はUI側の別走査（`AppState.editorFiles`）で行い、ファイルツリーで視覚的に区別する（開いたときに match 解決＝遅延。大量のYAMLを持つリポジトリでも一括走査コストに乗せない）。
- **書込権限**: エージェントの書込可否は「`inLedger`（メタアクティビティは定義ゾーンも可）」に一本化（`AgentToolbox`）。`.yde/workspace.yaml` 自体と `schemas` / `runs` ゾーンはメタでも書込不可。skill・evals のパース時の静的検査は layout を参照できないため、保守的に「`_` または `.` 始まりのパス禁止」とする（レガシー予約と `.yde` の双方を覆う）。
- **構成ファイルにはスキーマを当てない**: ゾーン外の構成ディレクトリ直下ファイル（`workspace.yaml`）は `schemaFor` が null を返す（catch-all スキーマの誤適用防止。`schemas` ゾーンへのメタスキーマ限定適用と同じ理由）。

### 4.26 計算列と決定論的射影の評価器（P16: R16-02）

段階的詳細化の機械的に置換可能な領域（例: 値型の maxLength → `varchar(100)`）を、LLMではなく宣言的なルールで参照時に導出する機構の第1段。守るべき不変条件:

- **評価器は `schema/Projection` に一元化**: `{パス}` プレースホルダのテンプレート置換と値辞書（`map` / `default`。`DeriveTypeRule`・skill列マッピングと同じ語彙）は純ロジックの単一実装に置き、消費者（ビューの計算列・将来の整合検証・スキャフォールド・決定論的アクティビティ）はこれを共有する。消費側に評価ロジックを書かない（`Display` / `AiHttp` と同じ流儀）。任意式・コード実行は導入しない（kind: run の明示許可制の境界を維持）。
- **1段評価**: プレースホルダが参照できるのは**原本に書かれた値だけ**。計算値の上の計算（推移的評価）はしない——許すと依存グラフ・評価順序・循環検出を持つスプレッドシートエンジンになるため、実例による需要が証明されるまで導入しない。
- **lookup は1ホップ**: 計算列の対象IDは「行のID自身」か「行の出現の ref フィールドを1回辿った先」のみ。多段の連鎖はスコープ拡大の判断を伴うため導入しない（必要になったら循環ガードとセットで設計する）。
- **計算列は読み取り専用の派生表示**: バックリンク列と同じ流儀（§4.14）。`editable` とは排他（パースで拒否）、セルの座標は元値（テンプレート）の原本座標＝ジャンプ先で、編集はそこ（規則）か入力値（値型定義）への通常編集で行う。ビュー専用の書込経路を作らない原則は不変。
- **評価失敗は作業を止めない**: 解決できないプレースホルダは `{...}` のまま表示に残す（見れば分かる・エラーにしない）。元値が無いセルは `default`、それも無ければ値なし。検証と結合する際（R16-03）は「判定保留」として扱う方針（ビューリントの誤検知回避と同じ哲学）。
- **未保存編集に追随する（R16-04 / UX-02）**: 計算セルの値はビュー解決（`Views.resolve`）時に評価され、値の読み取りは loader（docCache 優先）が担うため、UIのグリッドは `AppState.editVersion`（どの文書かの未保存編集・Undo/Redo で進む軽量カウンタ。`OpenDocument.onEdited` で通知）を購読して再解決する。「未保存で確認→OKなら保存」の流れを成立させ、保存を強いない。行の集合（新規ID）はディスク索引基準のまま（§4.5 / L-14 は不変）。エージェント経路はステージング loader で従来どおり。
- **来歴（provenance）はセルの一級情報（R16-04 / UX-01）**: 計算セルは評価結果に加えて「元値（テンプレート）とプレースホルダごとの解決値・座標」（`ViewCell.trace`）を持つ。UIのクリックは直接ジャンプではなく、**導出の説明（元値→入力値→結果）と行き先の選択**（導出規則・各入力値）をポップアップメニューで提示する（規則を確認したい場合と入力値を確認したい場合の両方があるため。モーダル新設禁止 §4.24 と整合）。`read_view` / CLI にも同じ来歴を出力し、人間とAIが同じ導出説明を見る。

整合検証（R16-03a）に固有の不変条件:

- **導出の語彙と解決は1実装**: 導出の宣言モデル（`DerivationDef`: `lookup` / `source` / `path` / `values` / `map` / `default`）は `schema/Derivation.kt`、対象ID→出現→スコープ→評価の解決は `index/Derivation.kt` に置き、ビューの計算列（`core/Views`）と整合検証（`validate/Validator`）が**同じモデル・同じ解決**を共有する（依存方向 §2 上、core の Views に置いたままでは Validator から使えないための配置）。ビューで見えている導出値と検証される期待値は常に一致する（人間・AI・検証器が同じ導出を見る）。
- **整合ルールは検査される文書のスキーマが宣言する**: スキーマの `consistency:`（`idPattern`＝対象ID要素の絞り込み・`path`＝検査する値の要素からの相対パス・`expected`＝導出宣言・`level`・`activeFrom`）。判定は `Validator.validate` に一元化し、検証パネル・CLI・エージェントゲートへ自然に乗せる。`activeFrom` はカバレッジと同じく自文書のフェーズで判定する。
- **判定は `assert` で選ぶ**: `equals`（宣言値 = 導出値。既定）に加え、`lte` / `gte`（**適合判定** R16-03b: 両辺を数値として以下／以上で比較。数値でなければ判定保留）。適合判定は「画面入力の maxLength ≦ 値型の maxLength」のような**制約の流れ**（設定できない値を許す入力欄の検出）に使う。文字列・enum 集合の包含は実需が出たら拡張する。
- **判定保留は沈黙**: 検査する値が未記入・期待値の元（写像行）が無い・プレースホルダ未解決で期待値を評価できない場合は指摘しない（未記入は required／写像行の欠落は detailIn カバレッジの領分。エラーの二重計上を避ける）。
- **テンプレート健全性はソース文書側の指摘（UX-12）**: いずれかのスキーマの整合ルールが `expected.source` にこの文書を選んでいるとき、この文書のID要素のテンプレート（`expected.path` の値）のプレースホルダが「自要素＋`values` で選んだ同一IDの出現＋同一IDの他出現」から解決できなければ、**テンプレートの座標に WARNING** を出す（書いたその場で気づける。編集中ツリーで判定されるため未保存でも出る）。lookup 起点（対象要素）由来のプレースホルダは静的に判定できないため、この検査で誤検知しうる場合はルール側の設計を見直す。
- **整合判定は注入された索引・loader 基準**: 期待値の材料（他文書のツリー）は loader（既定はディスク）で読む。UIはオーバーレイ索引＋未保存優先 loader（UX-03。§4.5）、変更セット適用・ステージングは適用後オーバーレイを渡すため、それぞれ「見たままの状態」「適用後の状態」で判定される。

### 4.27 一括実行（foreach: 対象列挙とキュー展開。R18-01）

「1実行=1文書」のアクティビティを保ったまま、同一アクティビティを対象違いで連続実行する機構。3つの関心事を分離する:

- **列挙は機械が行う**: 何が「残作業」かはアクティビティ定義の `foreach` 宣言（`agent/Foreach.kt`）が、ワークスペースの照会機構から決定的に列挙する。列挙元は `view`（横断グリッドの行。`where: detail-missing` = 詳細未作成の行）/ `issues`（検証の指摘）/ `search`（検索述語 = `core/Search` と同語彙）/ `files`（glob）/ `ids`（idPattern×schemaIds）の5種。**「次の対象選び」をLLMに委ねない**（決定性とコストのため）。files / issues の列挙は予約ディレクトリを対象にしない。
- **実行は既存のアクティビティのまま**: 展開された各項目は「対象を明示した追加指示」（`as` テンプレート。プレースホルダ `{id}` `{title}` `{file}` `{path}` `{message}` 等）を注入した通常の実行になる。ステージング・権限・ゲート・承認の仕組みは一切変えない。
- **編成はキューが行う**: `AutomationEngine.enqueueForeach` が起動時に**1回だけ**列挙し、`QueueItem.targetKey`（アクティビティ×対象キー）付きで直列キューへ積む。重複ガードは targetKey 単位（同一対象の二重投入だけを防ぎ、対象違いは並べられる）。項目の失敗は既存のリトライ・収束打ち切りで隔離され、後続の項目を塞がない。履歴には `[対象キー]` を前置して項目単位で追える。

収束と暴走防止の規約:

- **展開は起動時1回・`limit`（既定20）で上限**。実行中に対象が増えても追いかけない（再投入は人間の再操作）。繰り返し投入して収束するのは「完了した項目が次回の列挙に現れない」列挙条件のとき（view の詳細未作成・issues の指摘解消はこの性質を自然に持つ。files / ids は毎回同じ集合を返すため反復投入には向かない）。
- **`trigger: manual` 専用**（パースで強制）: 自動起動（トリガ・連鎖）からの展開は、契機・承認・再発火の設計（BACKLOG T-01）と絡むため導入していない。連鎖で foreach アクティビティへ遷移した場合は従来どおり単発実行になる。
- CLI は `agent --foreach` が同じ列挙を直列に回す（`--apply` 併用時は1件ごとに適用し、後続の項目から先行の成果が見える）。GUI は手動実行タブの「一括実行（対象を選択）」が列挙結果（`previewForeach`）をチェックボックス一覧で提示し、選択分だけを投入する（`enqueueForeachItems`）。一覧はモーダルではなく SELECT ステージ内のインライン区画で、選択状態は `RunController` が保持する（§4.24）。
- 将来の一般化（検証指摘へのアクティビティ紐付け＝ゴール駆動、プラン/ワークフロー層）は BACKLOG（UX-59・T-02）で管理する。列挙源に `issues` を最初から含めてあるのは、この合流先（UX-04 の指摘→アクション紐付け）と土台を共有するため。

### 4.28 コンテキスト会計（R19-01）

P19（コンテキストエンジニアリングの一級化）の計測基盤。「どの宣言がいくらトークンを払わせたか」を実行記録から機械取得できるようにし、コンテキストレシピのチューニングを勘でなくデータで行う。

- **区分の単位**: 初期コンテキストは `AgentKernel.initialContextSections`（`ContextSection(label, body)` のリスト）で組み立て、送信文字列はその合成（書式は従来と同一）。会計は「システム指示」「ツール定義」「指示」＋ `context` 宣言由来の各セクション（カタログ・フェーズ状態・ビュー）を `ContextSectionStat(section, chars, tokensEst)` として記録する。
- **概算と実測の両建て**: `estimateTokens`（ASCII 4文字≒1・非ASCII 1文字≒1の目安）はセクション別に出せるが実測ではない。実測はAPI報告の送信別入力トークン（`turnInputTokens`。時系列）で持つ——全履歴再送のため単調増加が正常で、急増はツール結果の肥大を示す。両者は相互補完（見積り=内訳、実測=総額）。delegate（相談）の消費は従来どおり合算のみでターン別には含めない。
- **キャッシュの正規化**: `ChatUsage.cachedInputTokens` は「入力トークンの内数」。OpenAI は `input_tokens_details.cached_tokens`（input_tokens の内数）をそのまま使い、Anthropic は `input_tokens` がキャッシュ分を**含まない**ため `input_tokens + cache_read + cache_creation` を総入力へ正規化したうえで `cache_read` を内数として持つ。**予算判定（`limits.maxTokens`）は総入力基準を維持**する（キャッシュ割引は課金の問題であり、暴走防止の予算とは別物——R19-07 実装時に確定した判断）。
- **prompt caching（R19-07）**: Anthropic 経路は `AnthropicToolSession.buildRequestBody` が `cache_control`（ephemeral）の切れ目を①system ②ツール定義の末尾 ③最新メッセージの末尾ブロックに自動配置する。③は毎ターン前進する切れ目で、2ターン目以降は再送プレフィクス全体（system・ツール・初期コンテキスト・過去の会話）がキャッシュ読取になる。保持中の履歴は変更せず送信時の複製にだけ印を付ける。トラバース・レシピの決定的出力の規約は、このプレフィクス安定性を壊さないための前提でもある。
- **実行記録の形式**: `_runs` に `usage.cachedInputTokens`（>0 のときのみ）・`context.sections`（section / chars / tokensEst）・`context.turnInputTokens`・`toolCalls[].resultChars`（打切り前の全長）を追加。集計（`RunMetrics`）は cachedInputTokens を合算する（実行記録の形式を変えたら RunLog / RunMetrics を対で直す規約どおり）。

**コンテキストレシピ（R19-02）**: 初期コンテキストの「指示」以降は、アクティビティ定義の `context.items`（`ContextItemDef`）が宣言順に決める。旧語彙（`context.include` / `context.views`）は**破壊的に廃止**（移行ヒント付き定義エラー）、無指定の既定は**空**（カタログも明示宣言制）。

- **項目の種類**: `catalog`（match 絞り）／`phase`（match。省略時 target.match）／`view`（rows=行IDパターン・columns=列ラベル選別。横断グリッドのみ）／`doc`（file / path）／`related`（id）／`sample`（schema / count＝同スキーマの既存文書を記述例として開示）。解決は `AgentToolbox.contextItemSection` に一元化（探索ツールと同じオーバーレイ経路）。
- **プレースホルダ**: 文字列パラメータ（rows / file / path / id）の `{name}` は `AgentRunRequest.targetValues` で解決する。値の供給源は foreach の候補値（`ForeachItem.values`。`{target}` は id ＞ file ＞ key の優先で常に含まれる）で、キュー（`QueueItem.targetValues`）・CLI 経由で伝搬する。**解決できないプレースホルダが残る項目は開示しない**（部分置換の文字列を送らない。決定性優先）。
- **開示条件 `when`**: `has-target`＝対象指定あり（targetValues 非空**または**追加指示あり）／`no-target`＝その否定／`always`（既定）。「対象が指定された実行では全量ビューを載せない」を宣言で表現する器。
- **予算 `budgetChars`**: 超過は決定的打切り（「…（予算 n 文字で打切り）」）。全文系（doc / related / sample）は無指定でも既定8,000字を掛ける。切り方が決定的なのは prompt caching・再現性のため（トラバース（R19-03）と同じ規約）。
- **解決失敗の扱い**: 例外（ビュー不在等）は「（コンテキスト項目を解決できません: …）」を本文として開示する——エージェントと会計の両方から見える。未解決プレースホルダだけが黙ってスキップされる（対象なし実行での {target} 項目は「失敗」ではないため)。

**グラフトラバース（R19-03/04）**: ID索引を型付きグラフ（ノード＝論理ID・出現＝多段定義、エッジ＝`RefEntry`）と見て、宣言的な**経路パターン**で多段に辿る評価器 `core/Traverse`。消費者はレシピ `graph:` 項目（実行前の決定的プリフェッチ・`{target}` 連動）／エージェントツール `traverse`（実行中のオンデマンド多段取得）／CLI `traverse`（レシピの試作台）の3面で、評価器は1実装に閉じる。

- **語彙**: `start`（起点ID）＋ `steps` の連鎖。各ステップは `follow: out / in`（先頭のみ省略可＝起点自身の開示）で移動し、`relation`・`via`（参照キー名）・`schema`（到達ノードの出現スキーマ）でエッジ・到達先を絞り、到達ノードを `emit: title / summary / outline / full`（段階的開示の4階層を出力粒度に流用）で開示する。**steps は連鎖**（各ステップは前ステップの到達ノードから辿る）——「起点の out と in の両方」は2項目に分けて書く。任意クエリ言語は導入しない（P16 の規律）。
- **決定性**: フロンティアはID昇順の安定ソート・`limitPerHop`（既定10）超過は「他n件」・訪問済みIDへは戻らない（循環停止）・`budgetChars`（既定8,000）超過は決定的打切り。出力が決定的なのは prompt caching・再現性・テスト容易性のため。
- **1ホップとの役割分担**: `related` は「1つのIDの周辺全部（出現・参照・被参照）」の定型ビュー、`traverse` は「経路を指定した多段の取得」（例: 対象機能 → actor 参照 → アクター定義の全文／このIDを derives で参照するテーブル定義の一覧＝影響分析）。

**参照の名前解決とツール減量（R19-05）**: 会計の実測で「支配的コストは初期コンテキストからターン数×履歴膨張へ移った」ことを受けた対処。ID解決のためだけの read_node ターンを消すため、表示解決（S-13 `IdEntry.title`）を機械出力へ拡張する——read_node／doc 項目の「参照の解決」フッタ（`AgentToolbox.refsFooterText`: ノード配下の参照を `ID = タイトル [定義元]` で列挙・未解決も明示・上位30件）、related の参照先タイトル付与、ビュー renderText の ref 値注記（`Views.refAnnotatedCellText`: 索引でタイトル解決できるIDだけ注記し、ただの文字列配列には触れない）。履歴膨張の抑制は、related の被参照既定20件打切り（`limit` 引数）・read_view の `rows`／`columns` 引数・list_documents の `match` 引数で行う。いずれも**注記は括弧・フッタに閉じ、値そのものはIDのまま**（パッチにはIDだけを書く前提を崩さない）。

### 4.29 エラー処理

- ファイルI/O・パースは `runCatching` で受け、ユーザー操作の結果は `Result` を返して呼び出し側（UI）が `state.status` に反映する。
- 入力検証の失敗は `require(...) { "日本語メッセージ" }`、内部異常は `error("日本語メッセージ")`。メッセージはそのままUIに表示される前提で書く。

## 5. 主要データフロー

- **編集**: フォーム操作 → `Slot.set` / ツリー直接変更 → `markEdited()`（履歴・dirty は core.EditBuffer が記録） → `version`++ → 再コンポーズ
- **保存**: `canonicalText()`（コメント・スタイル込み）をファイルへ書き出し → `Workspace.noteDiskChanges`（通常文書は増分索引更新、スキーマファイル・派生スキーマの導出元文書は全再構築。§4.5／§4.20／§4.23） → `indexVersion`++
- **外部変更検出**: `FileWatcher` の変更フラグ（またはフォールバックの1秒周期）→ `pollExternalChanges` がスタンプ比較 → 変更ファイルの再読込＋増分索引更新（`_schemas` 変更時のみ全再構築）
- **ジャンプ**: `navigateToId` → 索引の出現検索（複数なら選択ダイアログ／横ぐしビュー）→ `openFile(file, focus)` → `highlightPath` 設定 → `Highlightable` がスクロール
- **ビューグリッド**: `_views` 定義 → `Views.resolve`（行=ディスク索引、セル値=編集中ツリー優先の loader）→ セル編集は該当文書の `Slot` 直接変更＋`markEdited()` → 保存 → `rebuildIndex()` で行集合にも反映。行アクションは `AppState.createViewDetail`（スキャフォールド→書込→開く）／`addViewRow`（一覧文書の配列へ未保存編集として追加）
- **AI生成**: ✨/ボタン → `AppState.requestAi(field, path)` → `AiGenerationHost` が現ツリーを複製して入力構築 → `GenerationRun.run`（カーネル上の1ターン提出。Structured Outputs で生成 → ステージング → 実行記録 `_runs`。§4.19） → `combineProposal`/`replaceInClone` で適用後ツリーを合成 → 差分プレビュー＋`Validator` で事前検証 → 適用（`doc.root` 差し替え）
- **ヘッドレス変更セット適用**（CLI／エージェント）: `ChangeSets.parse` → 全ファイルの base ハッシュ照合＋パッチ適用（メモリ上）→ 適用後状態を重ねた**オーバーレイ索引**で検証（複数ファイル連動の変更も正しく判定）→ **全件成功時のみ**書き込み（`--require-valid` 指定時は検証エラーでも中止）→ `refresh()` で索引更新。1件でも失敗すれば何も書かないオール・オア・ナッシング
- **エージェント実行**: アクティビティ起動 → `AgentKernel` がシステムプロンプト（persona＋規範）と初期文脈（指示・カタログ・権限・ゲート）を構築 → ツール使用ループ（探索→パッチ→検証→…、必要なら `delegate` で相談）→ `submit_changeset`（ゲート通過）→ `RunLog` 書き出し → レビューUIで部分採用（採用サブセットを `Staging` で再検証）→ `Workspace.apply` で反映 → `AppState.reload()`
- **自動実行**: `AutomationEngine.tick(now)` がトリガ判定（on-change はデバウンス、on-invalid は変更ファイルの検証）→ キュー投入 → `processOnce` がカーネル実行 → 提出後は承認方式で分岐（auto: `AutoApply.applyWithBackup` → スタンプ更新 → 連鎖／human: 承認待ち＋変更セットのファイル保存 → 採否後に連鎖）→ 失敗はフィードバック付きリトライ（収束打ち切りあり）
- **評価**: `Evals.load` → `EvalRunner.run` がケース×変異ごとに（サンドボックスコピー作成 → `setup` のゴールデン入力を上書き → カーネル実行 → 宣言的期待結果の判定 → コピー破棄）→ レポートを原本の `_runs/eval-*.yaml` に保存 → 自己改善パネル／CLI `eval` で変異別の合格率・コストを比較
- **インポート**: `Tables.read`（形式→Table正規化）→ `Importer.build`（行→取り込み先ファイル解決・列マッピング・既存値との差分判定）→ プレビュー（作成/更新/変更なし/エラー）→ 人間の確認 → `Workspace.apply`（検証つき）→ `AppState.reload()`
- **エクスポート**: `Exporter.run`（glob→文書→行×列に展開）→ `Tables.write`（拡張子で形式決定）→ 派生成果物（再実行でいつでも再生成）

## 6. 拡張ポイント

- **フィールド型の追加**: `FieldType` に enum を追加し、`Validator.checkField`・`FormView.ValueEditor`・`ai.fieldToJsonSchema`・`SchemaDef.generateSample`/`defaultNode` に分岐を追加する。
- **YAML表層仕様の変更**: コメント・スタイル保持は実装済み（§4.17）。挙動を変える場合も `YamlIO` に閉じる（ツリー変換層が隔離されているため影響範囲は限定的）。構造比較や派生成果物には `dumpPlain` を使い、通常ダンプの表層情報に依存させない。
- **横断ビューの追加**: データソースとして `AppState.workspaceRecords()` と `IdIndex` を利用し、`ui/Shell.kt` の `LedgerSpace`（台帳スペースのタブと `workspaceView` 分岐）に追加する。要素の1行要約（タイトル・サブタイトル）は `schema/Display`（§4.22）を使い、独自のタイトル抽出を書かない。
- **ビュー定義の項目追加**: `core/Views.kt` の `parseView` と `schema/BuiltinSchemas.kt` の `builtin-view` スキーマを**対で**更新する（フォーム編集・検証と実行時パースの二重定義のため）。
- **共通スキーマ定義の追加**: `_schemas` に `kind: library` を置き、利用側は `moduleId#definition` で参照する。解決規則を増やす場合は `SchemaCatalog` に閉じ、各利用側で参照文字列を個別解析しない。
- **派生スキーマの写像項目の追加**: `SchemaLoader.parseDerive`（宣言のパース）と `DerivedSchemas.compileOne`（コンパイル）、組込みメタスキーマ（`builtin-schema` の `derive` 定義）を**そろえて**更新する。語彙は skill の列マッピング（SK-02）と揃える（§4.23）。
- **導出・整合ルールの語彙追加**: 宣言モデル（`schema/Derivation.kt`）・パース（`SchemaLoader.parseConsistency` / `Views.parseView` の computed）・組込みメタスキーマ（`builtin-schema` の `consistency`）・`builtin-view`（computed）・解決器（`index/Derivation.kt`）を**そろえて**更新する（§4.26）。
- **AIプロバイダの追加**: OpenAI互換のエンドポイントなら `AiConnection`（ベースURL上書き・プリセット）で足りる。独自APIのプロバイダは `AiProvider` に enum を追加し、`AiGenerator.generate` の分岐と専用クライアント（`generate` + `parseResponse` を持つ object）・`ToolChat.newSession` の分岐を実装する。HTTP送信は `AiHttp.post` を使う（§4.18）。
- **入出力形式の追加**: `TableFormat` に enum を追加し、`skill/Tables.kt` の read / write に分岐を実装する（Importer / Exporter は形式非依存のため変更不要）。
- **テンプレートパックの追加**: `skill/TemplatePacks.kt` の `all` に `Pack`（ファイルパス→内容の埋め込み）を登録する。`ImportExportTest` に倣い、展開後の検証エラー0をテストで保証する。
