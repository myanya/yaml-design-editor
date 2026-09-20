# アーキテクチャ（クラス設計と設計方針）

本書は実装の構造と、変更時に守るべき設計方針を定義します。構造や方針に影響する変更を行ったら**必ず本書を更新**してください。機能の有無・実装状況は [FEATURES.md](FEATURES.md)、利用者向けの仕様は [README.md](../README.md) を参照。

## 1. 技術スタック

- Kotlin (JVM 17) + Compose for Desktop + Material 3
- YAML入出力: SnakeYAML
- AI連携: Java 11 HttpClient + kotlinx-serialization-json（外部SDK不使用）。HttpClient の生成は `ai/Http.kt` の単一ファクトリに集約し、プロキシ（システム設定尊重／手動＋Basic認証）と OpenAI 互換エンドポイント（Azure 等）を一元管理する（R9-07・R9-08。§4.18）
- Excel・Word入出力: Apache POI（poi-ooxml。skill機構のインポート・エクスポートのみで使用。docx は XWPF で表を読む）
- 永続化: `java.util.prefs.Preferences`（UIレイアウト・テーマ・履歴・AIモデル設定）
- ビルド: Gradle（Wrapper 同梱）マルチモジュール——ルート＝Composeアプリ＋ `yaml-core` / `semantic-core` / `workspace-core` / `cli`（3層コア移行中。[THREE-LAYER-CORE-MIGRATION-PLAN.md](THREE-LAYER-CORE-MIGRATION-PLAN.md)）、テスト: kotlin.test
- ヘッドレスCLI: `./gradlew runCli --args="…"`（GUIなしで探索・検索・パッチ・検証・エージェント実行・評価・入出力変換。README §16） 

## 2. パッケージ構成と依存方向

> **3層コア移行中**（[計画](THREE-LAYER-CORE-MIGRATION-PLAN.md)・[台帳](MIGRATION-LEDGER.md)・[ADR](decisions/README.md)）: 以下のパッケージは段階的にGradleモジュール（`yaml-core` / `semantic-core` / `workspace-core` / `cli`）へ**移動**する。パッケージ名は移行中不変のため本節の論理構成はそのまま有効だが、台帳で done の単位はファイルの物理位置がモジュール側にある（例: `model/`・`yaml/` → `yaml-core/src/`）。モジュール間の依存方向は ADR-0002 を正とする。

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
| `EntityDecl` / `RelationDef` / `Relations` | schema/Relations.kt | 二層モデルの宣言語彙（P20。§4.30）: スキーマの `entity:` 宣言（型・idPattern・定義出現・アスペクト `aspect` / `owns`。R23-01）と関係レジストリ（`kind: relations` の `relations:`・`entities:` 節＝`EntityTypeDef` / `AspectDecl`）。`Relations.labelOf` / `inverseLabelOf` が relation 名→表示ラベルの解決を、`ownedFieldKeys` が出現の所有フィールド解決（合成・重複所有検証の共有実装。§4.33）を一元化 |
| `Aspects` | core/Aspects.kt | エンティティ合成（R23-01。§4.33): 全出現を「定義出現→アスペクト宣言順」に並べ所有フィールドを座標つきでマージした `EntityComposite` を決定的に構築する純ロジック。エンティティページ・`read_entity`・CLI `entity show` が同じ合成を消費（共有契約）。`ownerSection` は書き先解決 |
| `Projection` | schema/Projection.kt | 決定論的射影の評価器（R16-02）: `{パス}` プレースホルダのテンプレート置換（スコープ連鎖・1段評価・解決トレース）と値辞書。単一実装（§4.26） |
| `DerivationDef` | schema/Derivation.kt | 導出の宣言モデル（`lookup`/`source`/`path`/`values`/`map`/`default`）。ビューの計算列が使う。スキーマの `consistency:`（整合検証）は専用の型を持たず、組込み定義体（YAML）が値の式（`schema/ValueExpr.kt`）へ展開する（P29 Phase 6 第1段。§4.36） |
| `CheckLibrary` / `ValueExpr` | schema/CheckDefinitions.kt・schema/ValueExpr.kt（semantic-core） | 定義体の仕組み（関数のような定義 `functions`・語彙の定義 `vocabulary`・基本の検査への展開）と、値の式（読み出し・決まった値・テンプレートの展開・辞書での置き換え・最初に得られた値）。§4.36 |
| `Derivation` | index/Derivation.kt | 導出の解決器（R16-03a）: 対象ID（lookup 1ホップ）→ source 出現 → スコープ連鎖 → `Projection` 評価＋来歴。計算列（core/Views）と整合検証（Validator）の共有実装（§4.26） |
| `SchemaCatalog` / `SchemaKind` | schema/Schema.kt | 全スキーマ・libraryの定義を `(moduleId, definitionName)` でリンクする中央カタログ。`kind: library` はdefinitionsだけを公開し文書には適用されない |
| `PhasesDef` / `currentPhaseIndex` / `requiredAt` | schema/Schema.kt | フェーズ機構（§4.15）: スキーマの `phases` 宣言と、文書の現在フェーズ解決・フェーズ考慮の実効必須判定（検証・進捗計算が共有） |
| `CoverageLevel`（`CoverageRule` は P29 Phase 6 第3段で削除＝coverage は組込み定義体が作る語彙） | schema/Relations.kt | 検査の重大度（warning / error）。旧: スキーマの `coverage`（被参照必須などのトレーサビリティ要件）の定義。`activeFrom` でフェーズ到達までの判定保留を宣言できる |
| `SchemaLoader` | schema/Schema.kt | schemas ゾーンの読込・パース・スキーマID重複検出。ロードは「パース→リンク→導出」の三段階（§4.23） |
| `WorkspaceLayout` | schema/WorkspaceLayout.kt | ワークスペースの配置ポリシー（R13-02）: レガシー／`.yde` 構成の判定・ゾーン解決（`zoneOf`）・台帳判定（`inLedger`）・索引除外（`indexExcluded`）を一元化（§4.25） |
| `validateDefinition` / `generateSample` | schema/Schema.kt | メタスキーマ検証・サンプルデータ生成（拡張関数） |
| `BuiltinSchemas` | schema/BuiltinSchemas.kt | 予約ディレクトリ用の組込みスキーマ。`builtin-schema`（R11-02）は `_schemas` 文書に適用されるメタスキーマ（再帰 `$ref` と `cases` のドッグフーディング） |
| `RefNaming` / `SchemaInference` | schema/SchemaInference.kt | スキーマ推測（R11-01）: 既存文書群からのドラフト推測（純ロジック）。`RefNaming` は参照命名規約の共有実体（`DocScanner` が委譲。§4.23） |
| `IdSuggest` | schema/IdSuggest.kt | ID自動採番（UX-64）: 既存ID集合からの次連番の決定的提案（純ロジック）。形式の源は field の `pattern`（UX-79）と entity 宣言・ビュー行の idPattern＝検証と同じ宣言を読む。フォームのID入力メニュー・ビュー新規行の初期値が消費 |
| `DeriveDef` / `SchemaDerivation` / `DerivedSchemas` | semantic-core/schema/SchemaDerivation.kt（宣言・パース・展開コア）, schema/DerivedSchemas.kt（SchemaDef への包装シェル） | 派生スキーマ（R11-03 / C-2）: `derive:` 宣言のモデルと、導出元文書からのロード時コンパイル。コアは root と reader / session（`WorkspaceReader.buildSchemas`）が共有（§4.23） |
| `globToRegex` / `idMatchesPattern` | schema / index | glob→正規表現変換（パス用と ID 用で対応構文が異なる点に注意） |
| `DocScanner` | index/IdIndex.kt | 1文書からのID・参照抽出。スキーマ有無で検出規則を切替、未知キー境界で走査停止。参照には `relation` / `sourceSchemaId` を付与 |
| `IdIndex` | index/IdIndex.kt | ワークスペース全体の `id -> 出現一覧` 索引と全参照。**ディスク基準**（未保存編集は反映されない）。ファイル単位の走査結果を保持し、`update` で変更ファイルだけを差し替える増分更新ができる（R8-02。§4.5）。`IdEntry` はエンティティ型・役割（定義／詳細化）を、`RefEntry` は属性付き参照の属性を運ぶ（P20。§4.30） |
| `EntityGraph` | index/EntityGraph.kt | 走査結果から導出する読み取り専用のエンティティグラフ（R20-04。§4.30）: エンティティ表・出次／入次の隣接・文書ノード表。`IdIndex` に lazy 保持され `derive()` で無効化。**B-2（RB-02）で導出の実体はカーネル `semantic.SemanticEntityGraph` へ一本化**——ここは Path 座標の変換シェル（SemanticBridge の1:1変換）＋被参照の全体順（backlinksTo）だけを持つ |
| `Validator` | validate/Validator.kt | 文書の検証（UI非依存）。スキーマ適合＋参照解決（エンティティ／文書の名前空間分離。R21-03）＋文書内ID重複＋カバレッジ（`coverageStatuses` はUIとも共用）。フェーズ考慮の必須判定と記入進捗 `phaseProgress`、1行サマリ `phaseSummaryText`（UI・CLI・エージェントツールで共用。§4.15） |
| `GraphIntegrity` | validate/GraphIntegrity.kt | グラフ整合検証（R21-01。§4.31）: enforcement スイッチの「宣言のない関係名」の判定（**対象型と多重度は P29 Phase 6 第2段で、非循環は第4段で、組込み定義体 `builtin-definitions/relations.yaml` になった**＝§4.36）。同ファイルの `ReferenceSources` は、エラー時の対応「参照を書き足せそうな文書へ移動」（`jump: referenceSources`。UX-100）の候補の導出。カバレッジと共有する入次数・出現数カウンタ（`inboundRefs` / `occurrenceCount`） |
| `QuerySpec` / `ConstraintRule` | schema/Query.kt | クエリコアの宣言モデルとパース（R22-01 / R22-03。§4.32）: node / edge の交互連鎖・1段分岐（also）・プロパティ述語（where）・limit と、パターン制約（expect: exists / absent）。パースは1実装で、ビュー `rows: match`・foreach `source: query`・スキーマ `constraints:`・CLI `query` が共用する |
| `Query` | index/Query.kt | パターン照合エンジン（R22-01。§4.32）: `QuerySpec` を EntityGraph の隣接上で評価し束縛の表（`QueryResult`）を返す。決定性（起点ID昇順・束縛名辞書順の行比較・決定的打切り）・経路内の再訪禁止・判定保留（未解決参照・型未宣言は黙る）。`neighborIds` はトラバースと共有する隣接1段、`anyMatchFrom` / `startMatches` は `Validator` の制約判定が使う |
| `Workspace` / `WorkspaceScan` | core/Workspace.kt | ヘッドレスなワークスペース操作（開く・読む・保存・検証・変更セット適用）。相対パス解決はワークスペース外への脱出を拒否。`noteDiskChanges` が保存・外部変更の索引更新方針（増分／スキーマ変更時の全再構築）を一元化し、UI（AppState）もこの経路を通る（R9-02） |
| `EditBuffer` / `WorkspaceRecords` | core/Documents.kt | 文書キャッシュ層の中核（R9-02）: 履歴（Undo/Redo）・dirty判定・複数ドキュメント再出力（`EditBuffer`）と、一覧レコード収集・一括編集（`WorkspaceRecords`。未保存優先の loader 注入）。`app.OpenDocument` / `AppState` はこの層への委譲＋Compose購読ミラー |
| `PatchOp` / `Patcher` / `ChangeSets` | core/Patch.kt | YPathパッチ（set/insert/delete/move/rename＝順序・コメント保持のキー改名/reorder＝キー並べ替え。R25-01/02）と変更セット（baseハッシュ競合検出、YAML解析） |
| `RefactorImpact` | core/RefactorImpact.kt | リファクタリングのアプリ層影響解決（B-1・RB-01。D-10 のアプリ層再接続）: 計画生成はカーネル（`semantic.SemanticRefactor`＝`session.planRefactoring`）へ一本化され、ここはビュー追随の追加変更（SchemaDef 静的解決・スカラー Set のみ）と宣言節・定義文書の言及警告で計画を**補強**する（旧 EntityOps / SchemaRefactor / DocMove は B-1 で削除） |
| `FileWatcher` | core/FileWatcher.kt | WatchService による再帰的なファイル監視（R8-03）。「変更があったか」のフラグだけを通知し、差分判定は呼び出し側のスタンプ比較に委ねる。使えない環境では open() が null（ポーリングへフォールバック） |
| `Disclosure` | core/Disclosure.kt | 段階的開示: カタログ→骨子→ノード全文、文字数予算付き文脈バンドル |
| `Search` | core/Search.kt | 構造検索: 本文・キー部分一致＋スキーマ／glob条件、参照・未解決参照の検索 |
| `ViewDef` / `DocViewDef` / `Views` | core/Views.kt | ビュー機構（P6 / P14）: `_views` 定義のロード（`kind: grid / document`）、グリッド＝行（論理ID）×列（相対YPath）の解決＝原本座標化、クエリ行（`rows: match`＝パターン照合の束縛行。列の `of` で結合先の値をセル化。R22-02。§4.32）、バックリンク列（被参照の逆引き。R7-02）、文書ビュー＝文書単位の表示合成（sections の解決 `resolveDocument`。R14-01）、データパスからのスキーマフィールド解決（`fieldAt`＝schema 層 `SchemaDef.fieldAt` への委譲。R23-01 で移設 §4.33）、詳細文書スキャフォールド、テキスト表現（`renderText` / `renderDocText`） |
| `runCli` | core/Cli.kt | CLIコマンド（catalog/outline/read/bundle/search/traverse/query/view/validate/apply/hash/entity show/activities/agent/watch/revert/metrics/eval ほか）。出力はUTF-8のYAML。変更系リファクタリング（entity rename 等・move・refactor・bench）は B-1 で新CLI（cli モジュール `workspace refactor` / `workspace bench`）へ移動 |
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
| `IdCrossView` | ui/IdCrossView.kt | エンティティページ（R23-02。§4.33。ID横ぐしビューの改築）: ヘッダ（型・操作メニュー＝エンティティ操作の変更セットプレビュー）＋合成全体像（`Aspects` の合成。所有フィールドを `ObjectFields` の include 選別で編集＝§4.16 の Slot 経路）＋関係パネル（両方向）＋カバレッジ。出現別フォームはトグルで残置 |
| `GraphLensView` | ui/WorkspaceViews.kt | グラフレンズ（R23-03。§4.33。参照グラフの刷新）: エンティティ起点の近傍（深さ1〜2）と型・relation で絞る部分グラフを力学配置で描画。クリックはエンティティページへ |
| `ViewGridView` | ui/ViewGridPane.kt | ビューグリッド: `_views` 定義の編集可能な一覧表示。セル編集は文書キャッシュ上のツリー直接編集（Slot＋markEdited）、行アクションで詳細文書作成・新規行追加 |
| `Slot` (Map/Seq/Root) | ui/Common.kt | 「値の置き場所」の抽象。後述 |
| `AiGenerationHost` | ui/AiGenerationDialog.kt | AI生成ダイアログの状態機械（入力→生成→プレビュー→適用）と提案合成。アプリシェル直下にマウント（スペース非依存）・対象文書は `AiRequest` が持つ（R24-01） |
| `SchemaEditor` | ui/SchemaEditor.kt | `_schemas` 用の構造化エディタ（設計・プレビュー・適用対象） |
| その他 ui | FileTreePane / SourcePane / DiffPane / SidePanel（IDインスペクタ: 検証・関係・出現の3タブ。R12-03） / WorkspaceViews / Common | 各ペイン。[FEATURES.md](FEATURES.md) の対応表を参照。テスト可能な純ロジックは `ui/SourceHighlight.kt`（YAMLトークナイザ・置換）、`ui/GraphLayout.kt`（力学レイアウト）、`DiffPane.calculateDiff` / `charDiffRanges` に切り出す |

## 4. 設計方針（変更時に守ること）

### 4.1 ツリーが単一の正（single source of truth）

- `OpenDocument.root: YNode` が唯一の編集対象。フォームはツリーを**直接ミューテート**し、`markEdited()`（= `version` インクリメント）で再コンポーズを駆動する。
- `YNode` は意図的に「Composeの`State`ではない単純な可変オブジェクト」。UI側は `doc.version` / `state.indexVersion` を読むことで再コンポーズを購読する。新しいビューを書くときはこの購読を忘れないこと。
- ソース編集は例外的にテキストを持つが、「変更を反映」でパースしてツリーに合流させる。保存は常に `canonicalText()`（ツリーの正規化ダンプ）を書き出す。

### 4.2 履歴・dirty 判定は正規化テキストで行う

Undo/Redo は `YamlIO.dump` したスナップショット文字列の履歴（最大100件）。dirty は保存時スナップショットとの文字列比較。ツリーの構造比較は実装しない。

移行後リファクタリング（A-3・RA-06 / D-07）以降、アプリの Undo/Redo・dirty の正は**セッション（workspace-core）のワークスペース横断の単一履歴**で、`EditBuffer` の履歴はセッション不在時のフォールバック。横断履歴は開いていない文書も戻すため、`AppState` は実行後に「何が戻ったか」の通知（`historyNotice`＝変更の意図＋対象ファイルへのジャンプ導線＋未保存化（●）の明示。次の編集・保存・✕で消える）と、Undo/Redo ボタンのホバー予告（`undoPreview` / `redoPreview`）を組み立てる（UX-86 / UX-93。FEATURES E-05）。

### 4.3 Slot 抽象

マップのキー（`MapSlot`）・配列要素（`SeqSlot`）・文書ルート（`RootSlot`）を `Slot`（get/set/remove）で統一し、スキーマフォームと汎用エディタがエディタ部品を共有する。新しい「値の置き場所」（例: `_document` メタデータ）が必要なら `Slot` 実装を追加する。ツリー上の絶対 `YPath` から `Slot` を得る `slotAt`（`ui/Common.kt`）は、ビューグリッドとID横ぐしビューの両方が共有する。

### 4.4 YPath ベースのナビゲーション

要素位置は `YPath = List<Any>` で統一。検証結果・ハイライト・ジャンプ・AI生成対象はすべて YPath で紐付く。`state.highlightPath` に設定すると `Highlightable` がスクロール＋一時ハイライトする（スクロールは要素の上下に余白を含めた矩形を可視化要求し、対象を画面端でなく**中央寄り**に出す。UX-10）。**ハイライト対象が畳みの中にある場合は自動展開する**（UX-10）: 畳まれた区画の中は `Highlightable` がコンポーズされずスクロールもハイライトも届かないため、畳みを持つエディタ（配列・ネストobject・map・テーブル行展開）は `FormCtx.highlightInside(path)`（対象が自パス配下か）で展開する。新しく畳みを持つUIを作るときも同じ判定を入れること（展開はジャンプ後も維持し、勝手に畳み直さない）。ジャンプ・ファイル切替（`openFile`）、文書内ジャンプ（`navigateToPath`。検証パネル等）、ID指定でのビュー遷移（`openCrossView` / `openViewGrid`。横ぐしのID切替・グリッドのビュー切替を含む）は直前位置（`NavLocation`＝文書ならファイルとハイライト位置、ビューならビュー番号と選択ID）を「戻る」履歴へ積み、`AppState.goBack` / `goForward` で往復できる（ブラウザ同様、新しい移動で「進む」履歴は破棄）。UI側で `highlightPath` / `crossViewId` / `viewGridId` を直接代入すると履歴に残らないため、移動は必ずこれらの経由メソッドを通す。

### 4.5 索引はディスク基準（更新は増分）・UIはオーバーレイ

`IdIndex` は保存済みファイルから構築される（ディスク索引＝正）。未保存編集中の参照解決は、`DocScanner.scan` を編集中ツリーへ都度実行した「ローカルID」を索引に**重ねて**判定する（`FormCtx.resolvable`, `Validator`）。この二層構造を崩さないこと。

**UIのオーバーレイ索引（UX-03）**: 人間のUIは「見たままの状態で判定する」（未保存で確認→OKなら保存）ため、`AppState.overlayIndex()` が **dirty な文書キャッシュの編集中ツリーだけを重ねた索引**を返す（`IdIndex.overlay`＝走査結果の複製＋dirty分だけの増分走査。`(indexVersion, editVersion)` でキャッシュ）。UI層（検証・カバレッジ・ビュー行集合・ID横ぐし・参照解決・被参照）は `state.index` ではなく **`state.overlayIndex()` を読む**のが規約。ディスク索引が正であることは不変（保存・CLI・エージェントは従来どおりで、エージェントはステージングのオーバーレイ索引を別に持つ）。オーバーレイ由来の値を `remember` する場合はキーに `state.editVersion` を含めること（含めないと他文書の未保存編集に追随しない）。

更新方式（R8-02）: 索引はファイル単位の走査結果（`byFile`）を保持し、集約ビュー（`entries` / `refs`）はそこから導出する。保存・外部変更・変更セット適用では `update`（変更・削除ファイルだけの再走査）を使い、コストを変更ファイル数に比例させる。**スキーマが変わったときは全ファイルの走査規則が変わるため必ず `rebuild`（全再構築）を使う**（`AppState` / `core.Workspace` は `_schemas/` 配下の変更、および派生スキーマの導出元文書（R11-03。§4.23）の変更を見て自動で振り分ける）。計測は新CLI `workspace bench`（cli モジュール。カーネルの意味解析計測）で行う。

### 4.6 ID・参照のセマンティクス

- 同一IDの複数文書出現は「同一論理要素の段階的詳細化」として正当。エラーは同一文書内の重複のみ。
- 参照の解決可否は `idPatterns` × `scope.schemaIds` × `scope.documentIds` のAND（各配列内はOR）。候補表示と検証で同じ判定（`IdEntry.isAllowedBy`）を使う。判定ロジックを追加する場合も候補・検証・AI制約（`refCandidateIds`）の三箇所で一貫させる。
- 参照には任意の**関係種別**（`relation`）を付けられる。関係種別は解決可否には影響せず、表示（SidePanel・横ぐしビュー・グラフ・ビューのバックリンク列）と**カバレッジ判定**にのみ使う。
- カバレッジルール（スキーマの `coverage`）は**出現ベース**（`detailIn`。R7-02）＝「対象IDが、指定スキーマの文書に minCount 件以上出現すること」（詳細文書の存在検証）のみ。**参照ベース（`requiredBy`）は R29-01（意図的差分 D-16）で削除**——被参照数の正準は関係レジストリの cardinality（1ホップ・無条件）と constraints の count / exists（条件付き・多段）で、`requiredBy` を書いたスキーマは移行先を案内する定義エラーになる。detailIn は「同一IDの出現集合への集約」＝参照エッジでは書けない演算面（CALC-21）の専用語彙。判定は `Validator.coverageStatuses` に一元化し、検証（Issue化）とUI表示の両方から使う。索引基準のため未保存編集は保存まで反映されない。
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
- **入口はスペース非依存（P24: R24-01）**: `AppState.AiRequest` が対象文書（`OpenDocument`）を持ち、`AiGenerationHost` はアプリシェル直下にマウントする。生成・プレビュー検証・参考文書候補・実行記録の対象は常に要求が持つ文書で、`state.openDoc`（編集スペースで開いている文書）には依存しない。フォーム部品（`FieldRow`・配列エディタ）からの✨は `FormCtx.doc` を渡すため、フォームを再利用する画面（エンティティページ等）に自動で波及する。新しい入口を作るときは対象文書を明示して `requestAi(doc, field, path)` を呼ぶこと——`openDoc` 前提の入口を作らない。
- **関連文書は「文脈」・参考文書は「手本」の2系統（P24: R24-02）**: プロンプトに添える文書は、同一スキーマの参考文書（書き方・粒度の手本＝`referenceDocs`・`<reference-document>`）と、対象に関連する文書（内容の事実関係＝`contextDocs`・`<related-document>`）を区別して送る。関連文書の候補は呼び出し元が `AiRequest.relatedFiles` で注入する（エンティティページは `AppState.entityRelatedFiles`＝出現ファイル・参照先・参照元。`FormCtx.aiRelatedFiles` 経由でフィールド✨にも波及）。自動選択は関連文書があれば関連文書から最大3件（なければ従来どおり同一スキーマの新しい順3件）、文字数予算（合計12万字）は関連→参考の順に共有で消費する。
- **出現単位の生成はマージ適用・IDは対象外（P24: R24-02）**: エンティティページの「この出現をAIで作成」は、出現の object フィールドを生成対象にするが、①生成フィールドは所有キー（アスペクトカードは `ownedKeys`・出現カードは全宣言フィールド）に限定し **`type: id` は常に除外**（IDの改変は R21-02 のエンティティ操作の領分——参照の書き換えを伴うため生成で触らない）、②適用は既存 Map への**キー単位マージ**（`AiRequest.mergeObjectKeys`。非生成キーとキー順は保持・生成キーだけ上書き・新キーは末尾）。ディスク基準のパッチ（`GenerationRun`）も同じマージ結果の set 1操作にする。丸ごと置換（set）にしないのは、所有外フィールド・スキーマ外キー・IDを生成が黙って消す事故を構造的に防ぐため。**新規作成の連結（R24-03）**も同じ機構: ファイルツリー・グリッドの「作成→たたき台」は `requestDocumentDraft` がルート object を id 抜きでマージ生成する（スキャフォールドのID・`_document` を保持）。ルート対象（パス空）のマージはディスク基準のパッチを出さず、適用はダイアログに閉じる（文書全体生成と同じ扱い）。

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

整合検証（R16-03a）に固有の不変条件（**P29 Phase 6 第1段で、整合検証は専用の検査プログラムではなく組込み定義体（YAML）になった**——`consistency` という語彙が、クエリ＋値の比較（`check`）の基本の検査へ展開されて評価される。§4.36。下の箇条のうち「導出の語彙と解決は1実装」「出現選択」はビューの計算列についての記述として残る。整合検証の側は、指定に合う文書をすべて調べる値の比較の共通規則に従う＝意図的差分 D-19）:

- **導出の語彙と解決は1実装**: 導出の宣言モデル（`DerivationDef`: `lookup` / `source` / `path` / `values` / `map` / `default`）は `schema/Derivation.kt`、対象ID→出現→スコープ→評価の解決は `index/Derivation.kt` に置き、ビューの計算列（`core/Views`）と整合検証（`validate/Validator`）が**同じモデル・同じ解決**を共有する（依存方向 §2 上、core の Views に置いたままでは Validator から使えないための配置）。ビューで見えている導出値と検証される期待値は常に一致する（人間・AI・検証器が同じ導出を見る）。
- **出現選択は σ 選択方針（R29-02・D-17・CALC-19）**: lookup / source / values の selector が同一IDの複数出現に一致するときは、**定義出現優先→索引順**の決定的な先勝ちで1出現を選ぶ（共有評価部 `SemanticExpression.select`。CALC-11 の代表出現と同じ規則。テンプレート健全性検査 UX-12 の values 選択も同じ関数を通す＝検査と実際の導出が別の出現を見ない）。式（Expression）は単一値の計算なので量化しない——述語側の量化（∃σ／反例∃σ）との非対称は Core Calculus の原理（CALC-12/13）であり、揃えない。
- **Expression／要素述語の共有評価部（R29-05。P29 Phase 3）**: `semantic/Expression.kt` の `SemanticExpression` が「Get（σ が選んだ1出現からの単一値の読み出し）」「要素述語 `compare`（eq / ne は文字列等値・順序系は数値比較・数値として読めなければ null＝Unknown の3値）」「式側の σ 選択 `select`」の単一実装。check 判定述語（整合検証の `assert` も、組込み定義体の中で check の演算子へ読み替えられてここを通る）・where 選択述語（σ 候補の列挙 `sigmaCandidates` を check と共有）・期待値導出が通る。**値の比較や出現からの値読みを新しく書くときはここを通す**（集合性＝どの出現を読むかの量化は述語境界＝呼び出し側の責務で、ここへ持ち込まない。CALC-12/13）。
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

### 4.30 二層モデル①: 関係モデルの宣言化（P20: R20-01〜04）

情報管理を「物理層＝文書／論理層＝エンティティグラフ」の2層で捉え直す再定義（ROADMAP §4.9）の第1弾。グラフを走査の副産物から**宣言されたモデル**へ昇格させる宣言語彙と lint を**非破壊**で導入する。守るべき不変条件:

- **非破壊が本フェーズの契約**: 宣言（`entity:` / 関係レジストリ）のないワークスペース・スキーマは挙動不変。lint は WARNING（検証）またはスキーマ読込エラー一覧の1行（ロード時）にとどめ、エラー化・ヒューリスティクス廃止のスイッチは P21 の `enforcement:` に閉じ込める。スキーマなし文書のヒューリスティクス検出（I-02）は拡張しない（属性付き参照・エンティティ型は宣言があるときだけの機能）。
- **宣言の置き場所**: エンティティ型はスキーマ文書ルートの `entity:`（単一 object または複数型のリスト。`{type, idPattern, defines}`。`match` / `phases` / `coverage` と同列）。関係レジストリは `_schemas` の専用文書 `kind: relations`（`kind: library` と同様 `matches()` は常に false）で、`relations:` に `{name, label, inverseLabel, source, target, cardinality, acyclic, description}` を列挙する。cardinality / acyclic は **P20 では宣言のみ**（強制は P21 の R21-01）。モデル（`EntityDecl` / `RelationDef`）は `schema/Relations.kt` に置く（表示解決が index / UI 双方から使うため。`Display` §4.22 と同じ配置理由）。`builtin-schema`（entity 追加）・新設 `builtin-relations` メタスキーマ・スキーマ設計UI（S-05）を**対で**更新する（§4.23 の対更新規約）。
- **schemas ゾーンのメタスキーマは kind でディスパッチ**: `Workspace.schemaFor` は schemas ゾーンの文書のうち `kind: relations` の文書にだけ `builtin-relations` を適用する（それ以外は従来どおり `builtin-schema`）。レジストリ文書は設計スペースのカタログ・フォーム編集・検証に自動で乗る（セルフホスティング §4.2）。
- **索引への反映は走査時**: エンティティ型と役割（定義／詳細化）は `DocScanner` がID検出時にスキーマの `entity:` 宣言と `idPattern` で解決し、`IdEntry.entityType` / `IdEntry.role`（null = 宣言なし）へ付与する。「スキーマが変わったら全再構築」（§4.5）の既存規約が整合性を保証し、増分更新の仕組みは変えない。`IdIndex.resolve(id)` は**定義出現（role=DEFINING）があればそれを優先**する（表示解決・ジャンプ先が「定義」に安定する。「最初の出現＝定義」ヒューリスティクスの**廃止**は P21）。
- **lint は既存の報告経路に乗せる**: ①エンティティ整合（定義出現0＝浮遊詳細化／2以上＝定義競合・`idPattern` 逸脱・同一IDの複数型競合）は `Validator.validate` のワークスペース横断判定（カバレッジと同じ流儀。座標は各出現のID要素・すべて WARNING。台帳外では判定しない）。②未宣言 relation（レジストリが1つでもあるとき、スキーマの ref フィールドの `relation` がレジストリにない）は `SchemaLoader.loadAllWithErrors` のエラー一覧（スキーマ読込エラーの流儀）。判定保留の哲学（§4.26）を踏襲し、宣言のないスキーマのIDには黙る。
- **関係レジストリの消費は表示と lint のみ（P20）**: バックリンクを表示する全面（SidePanel 関係タブ・ID横ぐし・ビュー backlinks 列・文書ビュー・エージェント `related`・グラフ・bundle）は relation 名の代わりに `label`（参照方向）／`inverseLabel`（被参照方向）を表示する。解決は `schema/Relations` の `labelOf` / `inverseLabelOf` に一元化し、未宣言 relation は従来どおり名前のまま。**検証・解決の意味論には関与させない**（enum のリッチ選択肢 §4.22 と同じ「表示専用」の規律）。レジストリは `SchemaLoader.LoadResult.relations`（name → `RelationDef`。重複 name はロードエラー）→ `Workspace.relations` → AppState ミラーで配られる。
- **属性付き参照は「IDを持たない注記」**: `type: ref` のフィールドに `fields:`（エッジ属性の型宣言）がある場合のみ、オブジェクト形式 `{to: <ID>, <属性>...}` を許可する（宣言なしにオブジェクトが来たら従来どおり型不一致）。予約キー `to` が参照値で、`RefEntry.path` は **to 値の座標**（ジャンプ・パッチ・バックリンクの既存セマンティクスを保つ）。属性は走査境界（属性内の入れ子 ref / id はメタスキーマ検証で不許可）で、`RefEntry.attributes`（スカラー属性の写し）として索引に載り、表示は「（role: 承認者）」の括弧注記に閉じる（値はIDのまま。R19-05 の規約踏襲）。検証は to の解決（従来の ref と同一）＋属性の object 検証。フォームは参照行（to）＋属性行（`ObjectFields` 再利用）、AI生成は `{to: enum制約, 属性...}` の object へ変換。`YamlIO` は変更不要（通常の Map として往復＝コメント・スタイル保持は自動）。関係自体を参照・詳細化したくなったら従来どおり 1:N 具体化（新ID＋逆リンク。§4.6）を使う——使い分けは README に明文化。
- **EntityGraph は導出専用（グラフストアを持たない）**: `index/EntityGraph.kt` は `IdIndex` の走査結果から導出する読み取り専用ビュー——エンティティ表（id → 型・定義出現・出現一覧・タイトル）・隣接（出次＝出現サブツリー配下の参照、入次＝被参照、参照→最内の参照元要素）・文書ノード表（documentId → file・contains エンティティ。識別は既存 `documentIdOf`）・包含エッジは**書かずに導出**。`IdIndex` インスタンスに lazy 保持し `derive()`（rebuild / update / overlay）で無効化するため、ディスク・オーバーレイ（UX-03）・ステージングの各 `IdIndex` が自然に自分のグラフを持ち、§4.5 の二層構造を崩さない。`sourceEntryOf` / `backlinksTo` / `Traverse.neighbors`（out）／`related` の出次列挙は EntityGraph の事前計算へ委譲した（**公開APIと出力は不変**の載せ替え。`backlinksTo` は元の全参照順を order 索引で保存する）。導出コストは CLI `bench` の `graphDeriveMillis` で計測する。
- **レジストリの自動推測は入口保証つき**: CLI `infer-relations` は索引の全 `RefEntry` から relation ごとに viaKey・参照元／先の entityType 実績を集計してレジストリ文書のドラフトを生成する（inverseLabel は TODO コメント）。生成物は必ず `SchemaLoader.parseSchemaText` → `validateDefinition` を通る形で出力する（スキーマ推測 R11-01 と同じ入口保証）。

### 4.31 二層モデル②: グラフ整合とエンティティ操作（P21: R21-01〜03）

P20 の宣言（`entity:`・関係レジストリ・EntityGraph）を**制約**と**操作**に変える第2弾。本シリーズの破壊的変更はこのフェーズに集約し、ワークスペース単位の opt-in スイッチに閉じ込める（ROADMAP P21）。守るべき不変条件:

- **`enforcement:` はレジストリ文書に置く3値スイッチ**: 関係レジストリ文書（`kind: relations`）のルート `enforcement:` 節で、`undeclaredRelation` / `definingUniqueness` を `warning` / `error` で宣言する。**未宣言＝P20 の挙動そのまま**（非破壊の既定）が第3の状態で、`EnforcementDef` は各キーを nullable な `CoverageLevel` で持つ。複数レジストリで同一キーを重複宣言したらロードエラー（relation name 重複と同じ流儀）。集約は `SchemaLoader.LoadResult.enforcement` → `Workspace.enforcement` → AppState ミラーで配られる（relations と同じ経路）。
  - `undeclaredRelation`: 未宣言時はロード時 lint（P20 §4.30）のみ。宣言すると `Validator` が**文書側でも**レジストリにない relation を使う参照を参照値の座標で指摘する（warning / error）。
  - `definingUniqueness`: 「最初の出現＝定義」ヒューリスティクスの**廃止**スイッチ。`error` でエンティティ整合 lint のうち**定義出現の一意性**（定義出現0＝浮遊詳細化／2以上＝定義競合）を ERROR へ昇格する（型競合・idPattern 逸脱は P20 の WARNING のまま——一意性の強制と語彙の逸脱は別の関心事）。`resolve` のフォールバック（先頭出現）は機構として残るが、検証がエラーで塞ぐため運用上は定義宣言が必須になる。
- **対象型と多重度は組込み定義体になった**（P29 Phase 6 第2段・意図的差分 D-20。§4.36）: 以下の「対象型」「多重度」の判定内容（母集団・数え方・指摘の場所・重大度）は変わらないが、実体はカーネルの検査プログラムではなく、`builtin-definitions/relations.yaml` が関係の宣言から展開する基本の検査（`constraints` と同じ経路で評価）。カーネルの `SemanticGraphIntegrity` とルートの `GraphIntegrity` が判定するのは、非循環と、宣言のない関係名だけ。
- **グラフ整合検証は `Validator` に一元化**（R21-01）: 判定器は `validate/GraphIntegrity.kt`。検証パネル・CLI `validate`・変更セット適用（`Workspace.apply` のオーバーレイ検証）・エージェントの提出ゲート（`Staging`）へ自然に乗る＝カバレッジと同じ流儀。判定材料は `indexObj.graph`（EntityGraph）＋関係レジストリで、per-file の検証から「このファイルに座標を持つ指摘」だけを報告する。判定保留の哲学（§4.26）を踏襲し、**型未宣言のエンティティ・未解決参照には黙る**（未解決は V-02 の領分＝二重計上しない）。台帳外（indexed=false）では判定しない。
  - **対象型**: relation 宣言に `source` / `target` があるとき、エッジ両端のエンティティ型の不一致を**参照値の座標**へ指摘。参照元の型は `graph.sourceEntryOf(ref)`、参照先は解決先の `entityType`（いずれも null なら黙る）。
  - **多重度**: `cardinality` の out はエンティティ単位の合算（`graph.outgoingRefs(id)` を relation で絞る。同一関係の参照を複数出現に分けて書けるため）で、**母集団は `source` 型宣言に一致する型つきエンティティ**。in は入次数（`graph.backlinksTo`）で、母集団は `target` 型宣言に一致する型つきエンティティ。型の母集団が決められない（source / target 未宣言の）relation では多重度を判定しない——「誰が満たすべきか」を推測しない。座標は該当エンティティの定義出現（なければ先頭出現）。
  - **非循環**: `acyclic: true` の relation のエッジ集合で DFS の循環検出を行い、「循環経路（A → B → A）」を添えて、このファイル内の循環構成参照値の座標へ **ERROR**（非循環は宣言した時点で構造の前提であり、warning に意味がない）。
  - **重大度**: 対象型・多重度の指摘は relation 宣言の `level`（warning / error。既定 warning）に従う。カバレッジの `level` と同じ語彙で、レジストリ側の宣言だけで提出ゲートに載せられる。
- **coverage は組込み定義体になった**（P29 Phase 6 第3段・意図的差分 D-21。§4.36）: 下の記述の判定内容（書かれた場所の数）は変わらないが、実体は `builtin-definitions/coverage.yaml` が展開する基本の検査で、`SemanticCoverage` と `CoverageRule` は削除した。
- **coverage は出現ベース（detailIn）専用**: 「詳細化出現の存在制約」＝出現集合への集約（CALC-21）。参照ベース（requiredBy＝入次数制約）は R29-01・D-16 で削除し、数え上げの正準をレジストリ cardinality／constraints へ一本化した。**B-3（RB-03）で判定コアはカーネル `semantic.SemanticCoverage`（宣言 `CoverageRule` も semantic-core schema/）へ移設**——root `Validator.coverageStatuses` はこの ID の出現とフェーズ現在位置を写す per-ID 変換シェル。activeFrom の判定式は全検証語彙共有の `semantic.phaseActive`（R29-03）。
- **エンティティ操作は計画の生成**（R21-02 → B-1 でカーネルへ一本化）: 計画生成は `semantic.SemanticRefactor`（`session.planRefactoring`＝rename / merge / delete を文書ごとの型付き操作列 `RefactoringPlan` へ束ねる）、適用は `session.applyRefactoring`（**1トランザクション＝Undo 1回**・保存は `session.save`。D-12）。新しい書込経路を作らない原則は「カーネルの操作適用に一本化」として引き継ぐ。エージェントツールとしての公開は**しない**（既存 `apply_patch` で実現可能。ツール増はコンテキスト税 R19-05 に逆行——P23 で再判断）。
  - rename(old, new): 全出現のIDフィールド値（スキーマの `type: id` フィールド。スキーマなし文書は `id` キー）＋全参照（スカラー値／属性付き参照の `to`）を set。new が既存IDと衝突したら生成段階で拒否・エンティティ型の idPattern 逸脱は警告として結果に添える。
  - merge(from, into): from への全参照を into へ張り替え、from の出現は呼び出し側の選択で「into のIDへ書き換え（詳細化として残す）」または「要素ごと削除」。delete(id): 入次数が 0 でなければ参照元一覧つきで拒否（黙って壊さない）。同一配列内の複数要素削除はインデックス降順に並べる（ops は順次適用のため）。
  - 入口は新CLI `workspace refactor rename-entity|merge-entity|delete-entity`（cli モジュール。`--apply` なしは計画のプレビュー出力）と、GUI のコマンドパレット「エンティティを改名…」・エンティティページの操作メニュー（計画を確認ダイアログでプレビュー→適用）。計画はセッションの working 状態＝「見たまま」基準（D-12）。
- **文書参照は対象種別の宣言制**（R21-03）: ref フィールドの `target: document`（既定 entity。**値から推測しない**）で参照先の名前空間を切り替える。`RefEntry.targetKind` を走査時に付与し、エンティティ参照の解決・バックリンク・カバレッジには**混ぜない**（別種ノードの原則。ROADMAP §4.9 原則3）。解決は `graph.documents`（documentId ＝ `_document.id` ＞ 相対パス）に対して行い、未解決は台帳内 ERROR／台帳外 INFO（§4.25 と同じ降格）。参照候補（RefEditor・AI制約）は documentId＋文書名で提示。文書側の被参照は SidePanel 関係タブの「この文書への参照」（文書のページ化はしない＝現状維持）。
- **ファイル移動は「参照の書き換え＋移動」の1トランザクション**（R21-03 → B-1 でカーネルへ一本化）: `RefactoringRequest.MoveDocument` がパスID（`_document.id` 未宣言）の文書参照の書き換えと物理移動（`WorkspaceOperation.MoveDocument`）を1計画にし、`session.applyRefactoring` が一括適用する（旧 DocMove の「移動成功→書き換え失敗」の継ぎ目を解消）。プレビューには書き換え対象参照・スキーマ `match` の当たりが変わる警告（セッション付与）・ビュー定義の file / fileGlob が外れる警告（`core/RefactorImpact.moveWarnings`＝アプリ層補強）・`_document.id` 宣言済みなら「参照は安定・書き換え不要」を含める。入口は新CLI `workspace refactor move-document` と既存 W-03（ツリーの改名）の確認ダイアログ。

### 4.32 二層モデル③: クエリコア（P22: R22-01〜03）

照会5系統（構造検索・トラバース・ビューの行決定・foreach の列挙・導出 lookup）が断片的に再実装していた「グラフパターン照合」を単一エンジンへ統合する第3弾（ROADMAP §4.9 原則5）。「1実装・多面開示」（Display / Projection / Traverse の流儀）を照会にも適用する。

**配置（依存方向の帰結）**: 宣言モデルとパースは `schema/Query.kt`（`QuerySpec` ほか。スキーマの `constraints:` が `SchemaDef` に載るため schema 層）、評価器と結果モデルは `index/Query.kt`（`Query`。`IdIndex` / `EntityGraph` の上で評価し、`Validator`（validate 層）からも使うため index 層）。ROADMAP のスケッチでは core/Query.kt としていたが、R22-03（制約検証は `Validator` に一元化）と依存ルール（§2: validate は core に依存できない）から、整合検証（§4.26 の `schema/Derivation.kt`＋`index/Derivation.kt`）と同じ2分割に確定した。消費側（core/Views・core/Traverse・agent/Foreach・CLI）に評価ロジックを書かない。

**パターン語彙**（最終形。パースは `QuerySpec.parse` の1実装で、ビュー・foreach・制約・CLI が共用する）:

```yaml
match:                                   # node で始まり node で終わる node / edge の交互連鎖
  - node: { entity: screen, bind: scr }  # kind: entity（既定）/ document、entity: 型名、schema: 出現スキーマID、id: IDパターン、bind: 束縛名
  - edge: { relation: uses, dir: out }   # dir: out（既定）/ in。relation・via（キー名）で絞る
  - node: { entity: usecase, bind: uc }
also:                                    # 任意: 束縛済みノードからの分岐（1段のみ・内部結合）
  from: scr
  steps:                                 # edge で始まり node で終わる交互連鎖
    - edge: { relation: belongs }
    - node: { entity: domain, bind: dom }
where:                                   # 任意: プロパティ述語（equals / notEquals / exists）
  - { of: scr, path: status, equals: approved }
limit: 100                               # 既定 100（total は打切り前の全行数）
# node: { same: 束縛名 } はノード一致（R28-07）: 先行して束縛済みのノードそのものへの到達を要求
```

- **結果は束縛の表**: 各行 = 束縛名 → ノード（`QueryNodeRef`: kind・id・タイトル・エンティティ型・座標）。エンティティの座標は定義出現（なければ先頭出現）、文書ノードはファイル＋空パス。行は束縛値の組で重複除去する（束縛されない中間ノードは行の同一性に関与しない）。
- **文書ノードは最初から語彙に含める**（`kind: document`）: ノードは `graph.documents`（documentId）、`entity` / `schema` フィルタは併用不可・`id` は documentId のパターン。エッジの端の解決は次の node ステップの kind が決める——out で到達する端は「entity なら `targetKind: entity` の解決済み参照先／document なら `targetKind: document` の解決済み文書」、in で到達する端は「entity なら参照を書いた最内のID要素（`sourceEntryOf`）／document なら参照が書かれた文書」。文書ノードの out エッジは「その文書に書かれた全参照」。
- **プロパティ述語の値**: エンティティは「いずれかの出現の該当パスのスカラー」（多段定義の OR。`equals` はいずれかが一致すれば真、`exists: false` は全出現に無いとき真）。文書ノードのプロパティは `_document` 配下に限る（§4.9 原則3）。値の読み取りは loader 注入（UI: docCache 優先／エージェント: ステージング／CLI・検証既定: ディスク＝§4.5 の流儀）。
- **決定性**: 起点候補はID昇順・エッジ展開は索引の安定順・行の並びは「束縛名の辞書順に並べた束縛値（ID昇順）の辞書式比較」・`limit` 超過は決定的打切り（列の表示順は宣言順のまま）。1本の照合経路の中で同じノードを二度通らない（循環照合の停止）。未解決参照・型未宣言のエンティティは黙って照合から外す（判定保留の哲学 §4.26——起点フィルタに一致しないだけで、エラーにしない）。
- **ノード一致 `same`（R28-07）**: `node: { same: 束縛名 }` は「先行して束縛済みのノードそのもの」への到達を要求する（他のキーと同居不可・先頭 node には書けない・宣言順で先行する束縛のみ参照可＝パースで固定）。「2経路が同一ノードで合流すること」の照合（例: カラム→実装属性→業務型 と カラム→業務型 が同じ型ノード＝型不一致検出）で、評価器では**二度通らない規則の意図的な例外**（束縛済みノードへの再到達のみ許可・到達先の kind は束縛ノードから引き継ぐ）。束縛間の**値（表記）比較ではなくノードの同一性**で判定する（ADR-0008 の筋。束縛間の値比較は R28-08 の `check` 判定述語が担う——同一性は `same`・値の比較は `check` と使い分ける）。
- **やらないこと（P22 の不変条件）**: 集約は件数のみ（行数・制約の存在判定。sum / group を作らない）・任意深さの再帰パス（`*`）なし・任意式なし・optional 結合（外部結合）なし・分岐は `also` の1段まで。スプレッドシートエンジン／Cypher を作らない（P16・P19 の規律の継承）。語彙の解禁は実例の需要で段階的に判断する。

**消費面（R22-02）**:

- **トラバースは Query の上の emit レンダラ**: 隣接1段の展開（out / in・relation / via / schema 絞り・distinct・ID昇順）は `Query.neighborIds` に一元化し、`core/Traverse` は visited・limitPerHop・emit 描画だけを担う。`TraverseSpec` の構文・出力は不変（`TraverseTest` が回帰基準）。レシピ `graph:` 項目・エージェント `traverse`・CLI `traverse` は従来どおり Traverse を呼ぶ。
- **ビューの `rows: match:`**（従来の `rows: idPattern` と排他）: 行 = クエリの束縛行。行キーは `rowKey:`（省略時は先頭の束縛。kind: entity 限定）で、詳細出現・カバレッジ表示・新規行は行キーのエンティティに対して従来どおり効く。列の `of:`（束縛名。省略時は行キー）で**結合先のノードの値をセル化**でき、セルは各束縛ノードの原本座標 `(file, YPath)` へ解決される——セル編集のルーティング・loader・`editable` は既存経路のまま（§4.14 の不変条件は不変）。バックリンク列・計算列も `of` の束縛IDを対象にできる。結合行はIDが重複しうるため、行の同一性は `ViewRow.key`（束縛値の組）で持つ（UIの行キーも `key`）。`order` は併用不可（並びはクエリの決定的順序）。
- **foreach の `source: query`**: クエリの束縛行を1件=1実行に展開する（既存5種は存置）。対象キーは束縛値の組、プレースホルダは行キー相当の既定（`{id}` `{title}` `{file}` = 先頭束縛）に加え、**束縛名で参照できる**（`{scr}` = そのID・`{scr_file}` = 定義出現のファイル）——コンテキストレシピ（R19-02）の `targetValues` にもそのまま載る。
- **CLI `query`**: YAML のスペック（ファイルまたは `--spec`）→ 束縛の表（YAML）。ビュー・レシピ・制約の試作台。

**パターン制約（R22-03・R28-02/07/08 で拡張）**: スキーマの `constraints:`（`match`（＋`where` / `also` / `limit`・ノード一致 `same` 含む）＋ `expect: exists / absent`・`count: {min, max}`（R28-02）・`check`（R28-08）の**三者排他**・`level`・`activeFrom`）。「起点束縛に一致する各エンティティについて、残りのパターンに解が存在すること（exists）／しないこと（absent）／解の件数が範囲内であること（count。件数はクエリ結果の行と同じ同一性＝bind 列の組の distinct。`SemanticQuery.countMatchesFrom` は limit 打切り前の total を返す）／解ごとに束縛間の値比較が成り立つこと（check）」をカバレッジの一般形として検証する。`where` の述語は `equals` / `notEquals` / `exists` のいずれか一つ（R28-02。notEquals =「値が1つ以上あり、どの出現の値も指定値でない」＝equals の OR 解釈の否定。値がなければ黙る）。判定は `Validator.validate` に一元化（検証パネル・CLI・変更セット適用・提出ゲートに同一判定）。座標は起点束縛の定義出現（なければ先頭出現）で、その出現を持つファイルの検証でのみ報告する（多重報告しない）。起点は `kind: entity` のみ（パースで強制）。`activeFrom` はカバレッジと同じく自文書のフェーズで判定し、型未宣言・未解決参照には黙る（§4.26）。**Aggregate の Unknown 降格（CALC-17・意図的差分 D-15）**: 解探索のエッジ展開で参照の端をノードへ解決できなかった場合（OUT: 対象IDが索引に存在しない／IN: 参照の書き手をエンティティへ解決できない）は「候補の喪失」として `SemanticQueryResult.unknownDropped` に記録され、**解の不足を理由とする違反（exists の解なし・count の下限未達）は判定保留＝沈黙へ降格**する。解の実在を根拠とする違反（absent で解あり・count の上限超過）と、実在するノードの絞り込み（型・idPattern・where 不一致＝選択）は降格しない。未解決参照そのものは参照検証（§4.5）が別途指摘する。3.9 節の他出現への案内も同じ判定。既存 coverage は置き換えない（P21 で吸収済みの判定器の上位語彙として追加）。

**束縛間の値比較 `check`（R28-08＝Core Calculus 初の calculus-first 実装。[CORE-CALCULUS.md](CORE-CALCULUS.md) §7/§8・CONVENTIONS §9 チェックリスト通過）**: `check:` は `{lte|gte|lt|gt|eq|ne: [{of: 束縛, path: 相対パス}, {of: 束縛, path: 相対パス}]}` の配列（すべて成立が期待・束縛の解決と演算子はパースで固定＝where の `of` と同じ規約）。評価カーネルは `SemanticQuery.checkFrom`——解（束縛の表の行）ごとに**出現割当て σ**（各束縛にどの出現＝定義／詳細化を選ぶか）を**反例量化**し、要素述語（σ 固定の単一値比較。順序系は数値・eq / ne はスカラー文字列＝consistency の LTE/GTE と同じ規約）が False になる最初の組を反例として返す（決定的）。**1項目内の σ は一貫**（同一束縛の両辺は同一出現から読む＝出現をまたいだ突合はしない）・値が読めない／数値でない組は評価不能として飛ばし、評価可能な組がなければ判定保留（黙る）。反例（束縛・出現・値）は `SemanticCheckViolation` として `Validator` へ渡り、メッセージの反例列挙（違反した関係の記号つき）＋`relatedIds` ジャンプになる＝**provenance の最小形**。選択述語（where＝存在量化）と判定述語（check＝反例量化）で量化の向きが逆なのは意図的な仕様（CALC-13）。

**網羅マトリクス（R28-03）**: スキーマの `matrix:`（`rows` / `columns` = エンティティ選択・`witness` = セルの証人（`node`＋`row: {relation}`・`column: {relation}`）・`level`・`activeFrom`）。「行×列の全組合せに、行と列の両方を参照する証人エンティティが存在すること」を検証する（宣言は `schema/Matrix.kt`・判定カーネルは `semantic/Matrix.kt` の `SemanticMatrix.missingColumns`・報告はパターン制約と同じく `Validator.validate` の行の定義出現）。証人への参照キーを `relation` にしてあるのは、rename-relation リファクタリングの書換 walker（`entryKey == "relation"`）がそのまま効くようにするため。明示的除外は証人側の `excluded: true` 行（存在＝充足。UX-16 の流儀＝理由がデータとして残る）。列の母集団が空・行の母集団外は判定保留。

**遷移検証（R28-04）**: ノード母集団を定義するスキーマの `flow:`（`nodes` = エンティティ選択・`edges: {from: {relation}, to: {relation}}`・`start` / `terminal` / `usage`・`level`・`activeFrom`）。エッジ文書の行（from 参照と to 参照が**同じ行オブジェクト直下**に並ぶ組）を有向エッジとみなし、**到達閉包**（BFS）で①到達不能（start 宣言＋開始データがあるときのみ）②行き止まり（出エッジなし・terminal 除外）③宣言外遷移（usage エッジ ⊆ 宣言エッジ）を判定する。Query は任意深さの再帰パスを持たない（C-12 の不変条件）ため、到達性は matrix と同格の**専用高水準演算**として追加した（CORE-CALCULUS §20 チェックリスト通過・§21 の語彙表に記載）。宣言は `schema/Flow.kt`・判定カーネルは `semantic/Flow.kt` の `SemanticFlow.findings`（入力はグラフ＋**全 refs**——ID出現を含まない文書の参照はグラフのエッジ表に載らないため `KernelIndexView.refs` を渡す）・報告はノードの定義出現（他出現への案内を含め、配送は下記「規則の診断と配送」）。**CALC-17 の降格**: 未解決参照でエッジ・開始が失われたら到達不能を、終端が失われたら行き止まりを、宣言エッジが失われたら宣言外遷移を黙らせる。行き止まりは from 側が解決していれば to 未解決でも「出るエッジあり」と数える（未解決の from は実在ノードを指せないため、行き止まり判定はエッジ喪失で壊れない＝非対称の根拠）。関係参照のキーはすべて `relation`（walker 追随）。実在ノードの母集団外は選択（非降格）。

**規則の診断と配送（R29-06。P29 Phase 3）**: パターン制約・網羅マトリクス・遷移検証の指摘は、`validate/RuleDiagnostics.kt` が**規則×主語ID の判定を1回だけ診断（`RuleDiagnosis`）へ写し**、`Validator.validate` が2つの配送先へ届ける——**報告先**＝主語の定義出現（なければ先頭出現）を持つ文書（`reportIssue`。重大度は規則の level。規則はその文書のスキーマの宣言・activeFrom はその文書のフェーズ）／**出現先**＝同じIDの他文書の出現（`noticeIssue`。R28-05a の配送規則: エラー級は同格の ERROR・警告級は INFO の案内・主語IDを relatedIds の先頭に加える。規則と activeFrom は定義出現の文書のスキーマ・フェーズで判定）。診断は本文の2形（report / notice の見出し）と共有部分（根拠の列挙・関連ID・規則の説明）を持ち、配送を知らない。**新しい規則系の検証語彙を足すときは `RuleDiagnostics` に診断関数を1つ足し、`Validator` の2つの配送ループへ1行ずつ足す**（判定式・メッセージ組立を配送ごとに書かない——旧 3.7〜3.9 節の二重実装の再発防止）。規則の level → 重大度の写像は `CoverageLevel.toSeverity()`（全検証語彙で共通）。修正先（主語の出現を持たない文書）への配送と対処語彙の統一は指摘の三面モデル（BACKLOG UX-101・保留）の領分で、着手時は配送先の追加として乗る。

**準備完了レポート（R28-05b）**: `validate/Readiness.kt` が全文書の検証結果（INFO 除く）を**エンティティ単位に帰属**させ、「帰属エラー0＝準備完了」を集計する（CLI `readiness`）。帰属規則は「指摘のパスを含む最も内側のID出現」＝グラフの `sourceEntryOf` と同じ最内規則で、どの出現にも含まれない指摘は文書レベル（unassigned）に別掲する。警告は判定に使わない（意図的警告の運用を塞がない）。フェーズは定義出現のある文書の現在フェーズを解決する。提出ゲートの `no-new-errors`（点検系。UX-47）は `Staging.newErrors`——ステージ適用後の検証エラーから、同じ文書の**ディスク原本の検証エラー**（(パス, メッセージ) の多重集合）を差し引いた残り——が空であることを要求する（既存エラーで提出を塞がない。ベースラインの保存は不要＝実行中に原本は変わらない前提で提出時に都度比較）。

### 4.33 二層モデル④: エンティティページとグラフレンズ（P23: R23-01〜04）

論理層の読み書き面を人間とAIの共有契約として確立する第4弾（ROADMAP §4.9）。ノード中心の「エンティティページ」（ID横ぐし I-07 の改築）と、参照グラフ（X-03）を置き換えるグラフレンズ、機械面の `read_entity` を、単一の**エンティティ合成**の上に載せる。守るべき不変条件:

- **アスペクト宣言は既存宣言の拡張**（R23-01）: スキーマの `entity:` 宣言に `aspect`（この出現が所有する側面名。省略時はスキーマID）と `owns`（所有フィールドキーの限定。省略時はこの出現要素の宣言フィールド全部——ただし `type: id` のフィールドは合流キーであり所有に数えない）を追加する。型ごとの表示名とアスペクト順序は関係レジストリ文書（`kind: relations`）の `entities:` 節（`{type, label, description, aspects: [{name, label}]}`）で宣言する。モデル（`EntityTypeDef` / `AspectDecl`）は `schema/Relations.kt`、集約は `SchemaLoader.LoadResult.entityTypes` → `Workspace.entityTypes` → AppState ミラー（relations と同じ経路。type の重複宣言はロードエラー）。`builtin-relations` メタスキーマを対で更新する（§4.30 の対更新規約）。
- **合成は core・UIは購読**: エンティティ全体像の合成は `core/Aspects.kt` の純ロジック（`Aspects.compose`）に一元化し、エンティティページ（UI）・`read_entity`（エージェント）・CLI `entity show` が同じ `EntityComposite` を消費する（S-13 と同じ共有契約——人間とAIが同じ「エンティティの1枚」を見る）。出現の並びは「定義出現 → レジストリのアスペクト宣言順 → ファイルパス順 → 要素パス → アスペクト名」で決定的（宣言のないスキーマ群がスキーマIDのアルファベット順に落ちて detail が先頭に来ないよう、フォールバックはファイルパス順＝番号付きディレクトリの流れ。UX-80）。値の読み取りは loader 注入（UI: docCache 優先／エージェント: ステージング／CLI: ディスク＝§4.5 の流儀）。スキーマなし・object 解決不可の出現は合成に混ぜず `unassigned`（従来の出現カード表示）へ落とす——既存のID横ぐし利用を退行させない。
- **重複所有は隠さず注記する**: 複数出現が同じフィールドを所有していても、合成はどちらの値も落とさない（`AspectField.duplicateOf` で先行所有の座標を注記）。読み面で黙って情報を落とさないためで、是正は検証の指摘が促す。
- **重複所有の検証は誤検知回避優先**: `Validator` のエンティティ整合 lint（§4.30）に追加。同一エンティティの2出現が同じフィールドを所有し、**双方の出現に entity 宣言があり、少なくとも一方が明示的な `owns` を持つ**ときだけ WARNING（`owns` 宣言のない出現同士は判定しない＝ビューリント §4.14 と同じ「見逃しは許容」）。所有キーの解決は `schema.ownedFieldKeys`（合成と検証の共有実装）。
- **`fieldAt` は schema 層へ移設**: データパス→フィールド定義の解決（旧 `core/Views.fieldAt`）は validate 層（重複所有の判定）からも使うため `schema/Schema.kt` の `SchemaDef.fieldAt` へ移し、`Views.fieldAt` は委譲で互換維持する（§4.26 の Derivation・§4.32 の Query と同じ「依存方向の帰結」の配置変更。解決ロジックは1実装のまま）。
- **編集経路は増やさない**: エンティティページの合成全体像の編集は、各所有フィールドの原本座標への `Slot` ルーティング（`ObjectFields` の include 選別描画＝文書キャッシュのツリー直接編集＋`markEdited()`）で、§4.16 の不変条件（`fieldAt` / `slotAt` 共有・ビュー専用の書込経路を作らない）をそのまま維持する。エンティティ操作（rename / merge / delete）はページの操作メニューから R21-02 の変更セット生成→確認ダイアログのプレビュー→`Workspace.apply` の既存経路。閲覧専用モード（R10-04）は FormCtx 共有で自動波及する。
- **`read_entity` は related の上位互換・related は互換維持**（R23-04): 合成（アスペクト・座標つき）＋関係（label / inverseLabel の両方向・属性注記）＋フェーズ／カバレッジを1ツールで返す。`related` は軽量な隣接確認用に残し、説明文で使い分け（related=隣接1段の要約／read_entity=エンティティの全体像）を明示して重複開示を避ける（コンテキスト税 R19-05）。コンテキストレシピに `kind: entity` 項目（`{target}` 連動・when / budgetChars 対応）を追加する。
- **グラフレンズはEntityGraphの導出表示**（R23-03）: 全件力学配置の一枚絵（旧 X-03）をやめ、①エンティティ起点の近傍（深さ1〜2。エンティティページの「グラフで見る」から遷移）②エンティティ型・relation のチェックボックスで絞った部分グラフ、を `GraphLayout`（力学配置）の再利用で描く。ノードは `EntityGraph.entities`（エンティティのみ＝解決済みエッジの両端）、エッジのラベルは関係レジストリの label、ノードクリックはエンティティページへ（`openCrossView`＝ナビゲーション履歴 I-08 に乗る）。宣言的レンズ定義（`_views` の `kind: lens`）は需要待ちで導入しない。

### 4.34 編集履歴の合体とソースライブ同期（R17-03 第4波: UX-11 / UX-23）

編集の根幹2点の設計。いずれも「ツリー（EditBuffer）が単一の正・履歴はスナップショット」という既存モデル（§4.2 / §4.20）を変えずに、その上の記録規則・同期規則だけを足す。

**履歴の合体（UX-11）**: `EditBuffer.recordEdit(coalesceKey: Any?, nowMillis)` に合体キーを導入する。

- **規則**: 直前の記録と「同じキー（`==` 比較）かつ 1000ms 以内かつ 履歴の先端（Undo後でない）」なら、新しいエントリを積まずに先端スナップショットを**置換**する。キーが null（既定）・キー不一致・時間超過・Undo/Redo 直後は従来どおり新規エントリ＝**境界**になる。
- **キーの正体**: 連続入力になり得る編集面だけがキーを渡す。①フォーム・グリッドのスカラー入力＝`FormCtx.writeScalar` が**変異対象の `YNode.Scalar` インスタンス**をキーにする（同一フィールドへの連続タイプだけが合体し、フィールド移動・Undo後はインスタンスが変わり自然に境界になる）②スキーマ設計UIのテキスト入力＝`Pair(対象Map, キー名)` ③ソース編集＝文書単位の固定キー。構造操作（追加・削除・移動・enum選択・変換）はキーなし＝常に境界。
- **決定性とテスト**: 時刻は `nowMillis` 引数で注入する（`DocumentsTest`。タイマー・sleep を使わない規約は AutomationTest と同じ）。
- **効かない場所**: undo/redo/restore/resetHistory は合体キーをリセットする（復元状態への合体を構造的に防ぐ）。

**ソースライブ同期（UX-23）**: ソースタブの明示「変更を反映」を廃止し、パース成功時だけツリーへ自動反映する。

- **正の所在**: ツリーが常に正。ソースのテキストバッファは「ツリーの表現の1つ」で、**パース成功のたびに `applySourceText` でツリーを更新**する（markEdited＝履歴・dirty・editVersion・オーバーレイ索引・検証がフォーム編集と同じ経路で追随する）。反映後もバッファのテキストは書き換えない（カーソル・書式を保つ。コメント／スタイル保持 R8-01 により正規化テキストとの乖離は軽微）。
- **パース不能時**: ツリーは**最後に解析できた状態のまま**が正であり、保存もそれを書く。バッファの未解析テキストは画面上にだけ存在し、エラーバナー（行・列つき）でその旨を明示する。「破棄」でツリーの正規化テキストへ戻せる。ソースタブの Ctrl+S はパース不能の間は保存せずステータスで案内する（黙って最後の解析状態を書かない）。
- **外部変更の追随**: 自分の反映で進んだ `doc.version` は同期済みとして無視し、**他所からの変更**（フォーム・グリッド・Undo/Redo・外部変更の再読込）で進んだ場合だけテキストを正規化テキストから取り直す（`syncedVersion` の突合）。
- **Undoの粒度**: ソース連続入力は上記の合体キーで1エントリにまとまる。フォーム↔ソースの行き来はキーが変わるため自然に境界になる。

### 4.35 スキーマ・リファクタリング（P25: R25-01〜04）

スキーマ定義の変更（改名・移動・順序・型変換・定義／関係の改名）に既存文書・ビューを**1つの計画**で追随させる機構。方式は「操作ベース」——スキーマ差分からの推測（改名と削除＋追加が判別不能）は採らず、明示的な操作が生成器（B-1 でカーネル `semantic.SemanticSchemaRefactor`＝`session.planRefactoring` へ一本化。`RefactoringPlan` = 文書ごとの型付き操作列＋警告＋サマリ）を通って `session.applyRefactoring`（1トランザクション＝Undo 1回。D-12）で適用される。ビュー追随・言及警告は root の `core/RefactorImpact` がアプリ層で計画に補強する（D-10 の再接続。CLI 経路は警告案内のまま）。`_schemas` はエージェント書込不可（§4.10）のため入口は人間専用（スキーマエディタの「一括改名…」「一括削除…」「文書をスキーマ順へ整列…」・新CLI `workspace refactor` の12操作）。

**フィールドの同定はインスタンス同一性**: 対象フィールドはドット区切りパス（データと同じ形。配列・map は暗黙降下、cases 由来キーは兄弟として指定）からスキーマ定義上の実体（保持モジュール・YAML座標・`FieldDef` インスタンス）へ解決し、以降は **`FieldDef` の同一インスタンス（`===`）** で同定する。パーサが定義を1回だけ生成し `$ref` 解決（`resolve` は浅いコピー）が子リストのインスタンスを保つため、再帰スキーマ・横断参照・derive 派生（テンプレート root の共有）を通じても走査で正しく同定できる。この前提（**resolve が fields / item / value / cases のインスタンスを再生成しない**）はこの機構の生命線——スキーマ層の変更で FieldDef を deep copy する場合はここを見直すこと。enum 値の改名だけは「`$ref` の最終実体（値の保持サイト）」で同定する（複数スキーマが同じ定義を参照する場合に全使用側の文書へ波及させるため）。

**書換の3層と安全規則**:

1. **文書**: 該当スキーマ（`$ref` 共有・derive 派生を含む「対象インスタンスへ到達可能なスキーマ」）の全文書をスキーマ誘導で走査し、キー改名は `PatchOp.RenameKey`・整列は `PatchOp.ReorderKeys`（いずれも順序・コメント保持。C-02 に一級追加＝CLI・エージェントの patch 語彙でも `{op: rename|reorder}`）を**深いパスから**発行する。cases は `effectiveFields`（現在の enum 値の実効フィールドだけ）を通すため、**別 case の同名キーをデータとして誤って触らない**。改名・移動先の既存キー／値・変換できない値は生成段階で拒否（一覧つき＝黙って上書きしない）。
2. **スキーマ**: 定義キーは `fields[i].key` への Set（`$ref` 先は定義側モジュールの `definitions.<name>.fields[i].key`）。ルート直下フィールドなら `phases.key`、enum 値なら `cases` キー・`phases.order`・`requiredFrom` / `activeFrom`、削除なら `entity.owns` も追随。生成前に**ディスクYAMLの実体を照合**し、解決結果とずれていれば中止（未保存編集・派生の安全確認）。派生スキーマ（derive。source が schemas ゾーン外）・組込みスキーマ（source がワークスペース外）の直接指定は拒否し導出元へ誘導。
3. **ビュー**: 静的解決が**全て対象を指す**箇所だけ自動書換——通常列 `path`・計算列の `computed.path` / `lookup.via`・`newRow.path`・文書ビューの `sections[].fields.path` と `include` / `exclude`・被参照表の列（コンテキストは schemaId / fileGlob。未指定は到達可能スキーマ×ID要素起点）。同名の別フィールドにも解決しうる多義は書き換えず警告（`Views.fieldRenameImpact`）。

**移動の決定性（R25-02）**: 移動元と移動先は**要素コンテキスト署名**（ルートからの配列・map 横断の列＝横断キー列×コンテナ実体の同一性）が一致するときだけ許可する。境界をまたぐ移動は1↔Nの対応になり決定的に解決できないため拒否。共有定義（definitions）をまたぐ移動も拒否（他の使用箇所の文書が壊れる）。文書側は要素ごとに「中間 object の生成 → Move → 空になった親の削除（移動先の経路上は除外）」を発行する。

**警告に留める消費者（自動書換しない）**: スキーマ内の宣言節（consistency / constraints / coverage / derive）・定義文書（`_activities` / `_agents` / `_skills` / `_evals`）の言及・多義な列パス・アタッチ（`at`）・skill の写像。語境界つきの近似スキャンで**言及箇所を列挙**し、対応判断は人間に委ねる。フィールドパス・関係名・スキーマIDを参照する機能を増やすときは、この「書換 or 警告」のどちらに載せるかを必ず決めること（§6 の変更箇所リストと同じ役割）。なお relation / schemaId の改名は YAML キー基準の横断書換（`relation:`・`schemaId(s)`・修飾 `$ref` 等）で、レジストリ・スキーマ・ビューを対で書き換える。
**計画生成の共有基盤（R29-07。P29 Phase 4）**: 高水準操作（スキーマ・リファクタリング12操作・エンティティ操作）は、Core Calculus の Rewrite＝「場所の列挙（Query）× 絞り込みと中止条件（Predicate）× 場所ごとの操作（Edit*）」の形で書かれ、列挙と集積は `semantic/Rewrite.kt`（`SemanticRewrite`）の単一実装を通る——`walkDocument`（スキーマ誘導の文書走査。object へ入った時点／値のあるフィールドごとの2つの訪問点。cases は実効フィールドだけを辿る）・`reach`（対象フィールドへ到達する文書スキーマとその文書）・`forEachSchemaEntry`（書き換え可能な全スキーマ文書の全エントリ）・`rewriteDocuments`（基本戦略: 全一致を列挙→文書ごとの操作列。件数集計つき）・`PlanOps`（文書ID順の集積。エンティティ操作は削除を後ろへ寄せる）。各操作に残るのは「1文書分の規則」と事前条件・スキーマ側の追随・サマリだけ。**中止条件**（改名先キーの衝突・移動先の既存値・変換できない値）に当たった文書は空の操作列を返して理由を集め、全文書の評価後にまとめて中止する（部分計画を返さない）。**基本形に落とさないもの**: フィールド移動（中間生成→Move→空親の削除の列。`moveDataOps`）・定義の抽出／インライン化・型変換のスキーマ側は、操作列の組み立て自体に意味がある**戦略**として操作側が持つ（列挙器だけ共有）。**新しい変更操作を足すときは、走査や文書ループを書かずにこの基盤の列挙器と基本戦略を使う**。計画の決定性（同じ入力から操作順まで同じ計画）は `RefactoringPlanGoldenTest`（workspace-core。全操作＋拒否ケースの計画を正準テキストで固定。意図した変更時のみ `./gradlew :workspace-core:test -DupdateGolden=true` で期待値を更新し差分をレビュー）が守る。写像の全体と判断の記録は `specs/20-semantics/calculus/rewrite-model.yaml`。

**変更語彙の2層（R29-08。RP-02）**: 変更命令の基礎語彙は6種（Set / Insert / Delete / Move / RenameKey / ReorderKeys）で、**内部の正準は型付き操作 `edit.YamlOperation`（逆操作つき）＋ `WorkspaceTransaction`（版の検査・履歴・原子適用）**。`core.PatchOp` と変更セット（`core.ChangeSet`。基準ハッシュで衝突検出）は同じ基礎命令の**外部との交換形式**（エージェントの `apply_patch`・CLI・skill の取込）で、適用の入口（`Workspace.applyInternal`／エージェントのステージング）でだけ解釈される。**内部で計画を作るコードは PatchOp を作らない**（ビュー追随 `Views.fieldRenameImpact` も型付き操作で生成し、計画へ変換なしで合成する）。変更セット型の workspace-core への再配置は P27 と合流。

**文書の新規作成（R29-09。RP-03・D-18）**: 新規 YAML・ビューの詳細文書／新規行ファイル・カバレッジ指摘からの詳細文書・スキーマ推測は、`AppState.createDocumentFile` の単一経路——セッションへ `CreateDocument` 1件のトランザクションを適用→保存→`refreshAfterSessionWrite`（`reload` しない）。作成は履歴に入り Ctrl+Z で取り消せ、それまでの履歴も保たれる（`reload` はセッションを開き直すため履歴を失う）。**文書の作成・削除を含む履歴の Undo / Redo は `afterHistoryStep` がファイルの有無までその場で確定する**（対象文書だけ `session.save`）——内容の編集の Undo は「未保存●」に留めて保存で確定できるが、作成の取り消し（文書が消える）には保存の手段が無く、削除予約のままではディスクとセッションが食い違うため。セッションが文書として扱わないパス（隠しパス等）とセッション不在時だけ、従来の直接書込＋`reload` へフォールバックする。**新しい「文書を作る」機能はこの経路を通す**（`Files.writeString` ＋ `reload()` を書かない）。
### 4.36 定義層: 検査の書き方の3段と組込み定義体（P29 Phase 6）

Core Calculus の本来の目的（[CORE-CALCULUS.md](CORE-CALCULUS.md) §25）——機能を直交させて個別実装をなくし、**プリミティブな実装＋組込み定義＋ユーザ定義**だけで検証を行う——を実装に下ろす層。検証に関わるものを4層に分ける: **文法プリミティブ**（yaml-core。YAML の構造と最小の構造チェックだけ・小さく保つ）／**意味プリミティブ**（semantic-core。どう解釈するか。定義の中で組み合わせる「部品」と、複雑なアルゴリズムをまるごと実装する「専用チェッカー」）／**組込み定義体**（同梱の YAML）／**ユーザ定義**（利用者の YAML）。

**検査の書き方の3段**（上の段は、検証の前に下の段へ展開される）:

1. **基本の書き方**: スキーマの `constraints` に1件ずつ——プリミティブを直接書く（`match` ＋ `expect` / `count` / `check`）か、定義を名前で使う（`use` ＋ `with`）。
2. **関数のような定義**（`functions`）: 名前・受け取る値（`params`）・中身（`checks`＝基本の検査のひな型）。渡された値をひな型の `$名前` へ差し込む（文字列がちょうど `$名前` なら値そのもの・文中なら文字列・`$$` は `$`）。
3. **語彙の定義**（`vocabulary`）: どこに書けるか（`in`。第1段はスキーマ直下の配列 `名前[]`）と、書かれた内容からどの定義をどの値で使うか（`uses`。`when` / `unless` と、`with` の値の指定＝そのままの値・`{from, default, map}`・`{schema: id}`）。

**実装の配置**: 展開は `semantic-core/schema/CheckDefinitions.kt` の `CheckLibrary`（YAML を読んだままの値の上の操作だけ。検査の意味を知らない。差し込みは値の置き換えだけ＝条件分岐・繰り返し・定義からの定義の呼び出しなし）。組込み定義体の原文は workspace-core の資源 `builtin-definitions/`（供給は `workspace.BuiltinDefinitionResources`。**検査を定義体へ移すたびに、ここへ YAML を1つ足す**）。`SchemaLoader` は全スキーマ文書から定義（`functions` / `vocabulary`。置き場は `kind: library` が向く）を先に集め、組込み定義体と合成してから、各スキーマの検査を展開して `ConstraintRule.parse` へ渡す。`SchemaDef.constraints` は展開済みの検査、`SchemaDef.expandedChecks` は展開後の姿と由来（CLI `checks`＝**展開の透明性**。CALC-29）。派生スキーマ（`derive`）は導出元の語彙を**自分のスキーマIDで展開し直して**引き継ぐ（`VocabularySource.checksFor`）。利用者が定義した語彙のキーは、メタスキーマの「知っている項目」に足される（書き方の形の検証は後続）。**組込みは特権を持たない**（CALC-28）——組込み定義体も利用者の定義も同じ `CheckLibrary` を通り、テスト `CheckDefinitionTest` が利用者の定義だけで同じことができるのを固定する。

**第1段で足した意味プリミティブ**: ①値の式（`schema/ValueExpr.kt`。読み出し `{of, in, path}`・決まった値・テンプレートの展開 `format`・辞書での置き換え `map`・最初に得られた値 `first`。`check` の左右に書ける）②値を読む文書の指定（`in: {schemaId, fileGlob}`。値の読み出しと `edge` に書ける）③メッセージの宣言（`message`）と指摘の場所（`reportAt`＝値が書かれた場所。`RuleDiagnostics.valueIssues` が、この文書に書かれた要素ごとに、その値だけを調べて報告する）④エラー時の対応の宣言（`responses`。対応の種類は閉じた一覧＝移動 `jump`・値の書き込み `apply`。`Issue.responses: List<IssueResponse>` を検証パネルのメニューが宣言の順に並べる＝**メッセージと対応の直交性**。CALC-30）。評価は `SemanticQuery.checkFrom`——項目が読む「束縛と文書の指定」ごとに値を読む出現の候補を並べ、全ての組を決まった順で調べて、条件を満たさない最初の組を返す（値が得られない組は評価しない＝判定保留）。違反した組は、値の出どころ（元の値とその場所・テンプレートへ差し込んだ値の場所）を持つ（`SemanticCheckSide`）。

**値の条件と値の計算の部品（R29-11。第2段の前にまとめて用意）**: 値の条件は `schema.CheckOp` の一式（`eq` / `ne`・`lte` / `gte` / `lt` / `gt`・`oneOf`・`matches`・`startsWith` / `endsWith` / `contains`）で、**判定（`check`）とノードの絞り込み（`where`）が同じ演算子・同じ判定を共有する**（`where` はこれに `exists` / `empty` が加わり、比べる相手は決まった値）。値の計算は `ValueExpr` に `Unary`（文字数 `length`・`upper` / `lower`・数値の表記を揃える `number`）と `Arith`（`add` / `subtract` / `multiply` / `divide`）を足した。計算そのものは `semantic.SemanticExpression` の純粋な関数（`test` / `compare` / `unary` / `arith` / `isEmpty`）で、評価できないとき（数値として読めない・0 で割る・正規表現として読めない）は null＝判定保留を返す。四則演算は BigDecimal で行い小数の誤差を持ち込まない。`eq` は文字列の等値のままで、数値どうしを等値で比べるときは `number` で表記を揃える。指摘の文面には何を計算したかを添える（`SemanticCheckSide.note`＝「name の文字数: 14」・`raw`＝「「300」（= 100 × 3）」）。決まった値は式の位置にそのまま書ける（`{value: ...}` は、ひな型で渡されないかもしれない値を受けるための形）。

**第2段で足した意味プリミティブ（R29-12）**: ①**条件の否定と「または」**（SP-02。`where` は `QueryWhereClause`＝条件の「または」・`QueryWhere.negated`、`check` は `ConstraintCheck`＝値の条件 `CheckTerm` の「または」・`CheckTerm.negated`。入れ子は1段・3値で判定し、判定できない条件は否定しても判定できないまま。node は `entities`（型の並び＝どれか）と `not: QueryNodeFilter`。ノードの絞り込みは `QueryNodeStep.matchesEntity` の1実装をクエリ・網羅マトリクス・遷移検証が共有）②**参照の本数と集計**（SP-03。値の式 `ValueExpr.RefCount`＝グラフの隣接から数える。出ていく参照は相手が見つからないものも数える＝CALC-20／`ValueExpr.Aggregate`＝同じ起点の全ての解にわたる合計・最小・最大・件数。値は定義した側を優先して1つから読む＝CALC-19。集計を含む項目は同じ組を解の数だけ並べない）③**参照の束縛と参照の場所への指摘**（`QueryEdgeStep.bind`・`SemanticQueryRow.refs`・`ReportAt.ref`・`RuleDiagnostics.refIssues`。束縛した参照は解の同一性に加わる）④**文面のひな型をどの判定にも**（`{id}` `{束縛名.id/type/title}` `{count}`）⑤**エラー時の対応「参照を書き足せそうな文書へ移動」**（`jump: referenceSources`＋`relation`・`note`。`Issue.openFiles` は廃止し `IssueResponse.Jump` に一本化）⑥語彙の `when` の並び・空の並びは書かれていない扱い・`with` の `join`。**関係レジストリの語彙**: 語彙の `in: relations[]` は `kind: relations` の文書にも効き、展開された検査は特定のスキーマに属さない検査として `Validator` がどの文書の要素にも適用する（報告先・出現先の配送は他の検査と同じ）。語彙が要素から読むキー（`VocabularyWord.readKeys`）は、メタスキーマ（`builtin-schema`・`builtin-relations`）の該当する要素の項目へ足す。

**第5段: すべての要素について（R29-16。SP-04）**: 網羅マトリクスを、検査の基本の書き方 `forAll`（`schema.ConstraintForAll`＝もう1つの集まり `node`・辿り始める束縛 `from`・連鎖 `steps`）へ展開する語彙にした。評価は `SemanticQuery.forAllFrom`——集まりの要素ごとに、`steps` の連鎖を「あらかじめ決まった束縛」（`Evaluator` の `fixed`。`node: {same: 束縛名}` から指す）つきで解く。解がなく、探す途中で候補の喪失（CALC-17）も無かった要素が不足。`RuleDiagnostics.constraint` が不足を列挙して報告する（`{count}` `{missing}`）。関数のような定義のひな型に `$merge`（渡された object を混ぜ込む）を足した（`rows` / `columns` / `witness.node` の絞り込みへ束縛名を足すため）。`SemanticMatrix`・`MatrixRule`・`RuleDiagnostics.matrix`・`index.Query.matrixMissingColumns`・`Validator` の matrix の配送を削除（意図的差分 D-25）。

**何段でも辿る線と、行を線とみなす指定（R29-15。SP-06・SP-07）**: オーナーの方針「辿る計算は専用アルゴリズム・判定は YAML で外付け」。`QueryEdgeStep.repeatMin`（`repeat: true` / `{min: 0}`）は、その線をくり返して辿り着ける要素の集まりを幅優先で求める（`SemanticQuery` の `reach`。一度調べた要素は二度調べないため必ず止まる・隣は ID 昇順で決定的・最短の道すじを覚える）。出発した要素へ戻る循環があれば、出発した要素も辿り着ける要素に入る（`node: {same}` で循環を調べられる）。0回でとどまる歩（`Step.stayed`）は「同じ要素を二度通らない」規則の例外。`QueryEdgeStep.row`（`FlowEdges`）は、同じ行の直下の2つの参照を1本の線として辿る（`expandOnce` の row の分岐）。道すじは `SemanticQueryRow.trail` → 文面の `{path}`。判定保留は今までどおり「辿った範囲で出会った、相手の見つからない参照」で決まる（CALC-17）。**非循環（`acyclic`）はこの部品で YAML に書き直し、循環の検出の専用チェッカー（R29-14 の `cycle`・`SemanticCycles`）は廃止した**（意図的差分 D-24。変わるのは循環の経路の始点だけ）。遷移検証（`flow`）は専用チェッカーのまま——到達できない・行き止まり・宣言にない遷移は、開始が1つも無ければ判定しない・開始の一覧を文面に出す・ワークスペース全体での判定保留、を要し、YAML へ書き直すと今の挙動を保てないため（書き直すには、検査の前提条件と、別のクエリの結果を文面へ出す部品が要る）。

**第4段: 専用チェッカーを定義から使う（R29-14。SP-13）**: 到達の計算（遷移検証）と循環の検出（非循環）は、何段でも辿った結果と、ワークスペース全体での判定保留（相手の見つからない参照が1つでもあれば、解の不足を理由とする指摘を黙らせる＝CALC-17）を要する。クエリの部品の組合せで書くと不自然になるため、**専用チェッカー**（意味プリミティブの2つ目の形。DL-04）としてコードに残し、語彙への結び付け・文面・重大度・配送だけを定義体へ移した。宣言は `schema/Checker.kt`（`CheckerKind`＝閉じた一覧（今は `flow` だけ。R29-14 で足した `cycle` は R29-15 で廃止）・`CheckerArgs`＝チェッカーごとの渡す値〔`FlowArgs`〕・`CheckerRule`＝`checker` ＋ `args` ＋ `messages`〔指摘の種類→文面。書かなかった種類は指摘しない〕＋ `level` / `levelFrom` / `activeFrom`）、実行は `semantic/Checkers.kt`（`SemanticCheckers.findings`→`SemanticFlow`。グラフと全参照だけを入力にした純粋な関数）。`SchemaDef.checkers` が展開後の検査で、`RuleDiagnostics.checker`（要素を定義した場所へ報告＋他の出現へ案内）・`checkerRefIssues`（参照が書かれた場所へ報告）が文面を作り、`Validator` がパターン制約と同じ単位（報告先の決め方 `reportAnchorOf`）で配送する。派生スキーマは、専用チェッカーを使う検査を引き継がない（対象の母集団を検査が自分で宣言するため）。語彙の書き方の形に `keys`（書けるキーの一覧）を足した。**チェッカーを足すとき**は、`CheckerKind` と渡す値の型・`SemanticCheckers` の分岐・メタスキーマ（`builtin-schema` の constraints の `checker`）を揃えて更新する。

**第3段で足した意味プリミティブ（R29-13）**: ①**書かれた場所の数**（SP-05。値の式 `ValueExpr.OccurrenceCount`＝その ID が書かれている場所のうち、スキーマ・ファイル・役割の指定に合うものの数。`distinct: type` は宣言されたエンティティ型の種類の数）②**検査の状況の表示**（`ConstraintRule.status`。`Validator.ruleStatuses` → `RuleDiagnostics.status` が、満たしている検査も値つきで返す＝`SemanticQuery.checkEvaluationsFrom`。右パネル・ID 横ぐし・ビューの状態列・エージェントの `related` が共用し、表示の文言は `RuleStatus.text` の1か所）③**直せる場所への案内**（`ConstraintRule.fixSite`。書き足し先のスキーマの文書の検証へ、指摘が出ている ID の一覧を INFO で出す。指摘の三面モデルの「修正先」を、検査の宣言にしたもの）④**エラー時の対応「文書を作る」**（`create: detailDocument`。`IssueResponse.CreateDetail`。`Issue.scaffold` は廃止）と文面の `{create.places}` ⑤**重大度の切り替え**（`levelFrom`＝関係レジストリの強制スイッチのキー名。解決は `Validator` が `RuleDiagnostics` へ渡す `levelOf`）⑥node の `typed`（型が宣言された要素）⑦語彙の書き方の形の最小の点検（`requires`・`removed`）と `with` の `format`。**特定のスキーマに属さない検査**は、関係レジストリの語彙に加えて、`kind: library` の文書と組込み定義体に直接書いた `constraints`（`SchemaLoader.builtinRules`）。**検査の報告先**（`Validator.reportAnchorOf`）: スキーマの検査は、定義出現がそのスキーマの文書にあればそこ、なければそのスキーマの文書の先頭の出現（意図的差分 D-21 ④。BACKLOG UX-103 のうちパターン制約の分を解消）。特定のスキーマに属さない検査は、定義出現（なければ先頭の出現）。

**定義体へ移した検査**: 網羅マトリクス（`matrix`。第5段・意図的差分 D-25。`builtin-definitions/matrix.yaml`）。遷移検証（`flow`）と非循環（`acyclic`）（第4段・意図的差分 D-23。`builtin-definitions/flow.yaml`・`relations.yaml`。`SchemaDef.flow`・`FlowRule`（→`FlowArgs`）・`RuleDiagnostics.flow` / `describeFlowFinding`・`index.Query.flowFindings`・`Validator` の flow の配送・`SemanticGraphIntegrity` の非循環の部分（→`SemanticCycles`）を削除または移動。カーネルの `SemanticGraphIntegrity` に残るのは、宣言のない関係名の検査だけ）。詳細文書の存在（`coverage`。第3段・意図的差分 D-21。`semantic/Coverage.kt`・`schema/Coverage.kt`（`CoverageRule`）・`SchemaLoader.parseCoverage`・`Validator.coverageStatuses` / `coverageMessage`・`CoverageStatus`・`IssueScaffold` と、出現先への不足案内の専用コードを削除）。エンティティの整合のうち浮遊詳細化・定義競合・型競合（第3段・意図的差分 D-22。組込み定義体 `entities.yaml` に直接書いた検査。idPattern 逸脱と重複所有は、スキーマの構造に依存する lint として `Validator` に残す）。多重度と対象型（関係の宣言の `cardinality`・`source` / `target`。第2段・意図的差分 D-20。`SemanticGraphIntegrity` の該当部分・`SemanticShortage`・`SemanticIssue.shortage` を削除。評価は constraints と同じ経路になり、カーネルの snapshot には出ない＝検査の実行をカーネルへ移す P27・D-4 で揃える）。整合検証（`consistency`。第1段・意図的差分 D-19）。`semantic/Consistency.kt`・`ConsistencyRule`・`ConsistencyAssert`・`SchemaLoader.parseConsistency`・`IssueDerivation` は削除した。**これで、検査の語彙はすべて定義体になった**（P29 Phase 6 の第1〜5段）。コードに残る検査は、専用チェッカー（遷移検証の到達の計算）と、定義体にしないと決めたもの（宣言のない関係名＝宣言の検査・エンティティの idPattern 逸脱と重複所有＝スキーマの構造に依存する lint・構造検証と参照解決＝検査の土台）だけ。

### 4.37 検査の結果の4つと、指摘の出どころ（P27 段A）

検査の語彙がすべて定義体になった（§4.36）あとに残った弱点——①判定を保留した検査が「充足」と区別できない ②指摘が、どの検査の・どの要素についての指摘かを文面でしか持たない——を、検査の実行をカーネルへ移す（P27 段B）前に、出力の形から直した。

**検査の結果は4つ**（`semantic.CheckOutcome`。仕様 CALC-31）: 検査1件を要素1つについて評価した結果は、次のどれか1つになる。

| 結果 | 意味 | 理由（`semantic.CheckReason`） |
|---|---|---|
| 違反 | 検査に反している。**指摘が出るのはこれだけ** | — |
| 充足 | 確かめて、問題が無かった | — |
| 保留 | データの不備のために、充足とも違反とも決められなかった（CALC-17 の判定保留） | 辿る途中に相手の見つからない参照がある／比べる値を数値として読めない |
| 対象外 | この要素・この時点では、確かめるものが無い | 検査の対象でない／フェーズ未到達／比べる値が書かれていない／比べる組が無い／調べる相手の集まりが空／チェッカーの前提が宣言されていない |

「比べる値が書かれていない」を保留ではなく対象外にしたのは、任意の項目を比べる検査（最大文字数の比較など）では値が無いのがふつうで、これを保留に数えると、本当に確かめられなかった検査（データの不備）が埋もれるため。

**判定の置き場**: どの結果になるかを決めるのはカーネルの `semantic.SemanticRules.evaluate`（検査 × 要素 → `SemanticRuleResult`＝結果・理由・文面を作るための根拠）だけ。判定の保留の規則（解の不足を理由とする違反だけを保留にし、見つかった解が根拠になる違反＝あってはならない解・上限の超過・条件を満たさない組は保留にしない）もここに集めた。root の `validate.RuleDiagnostics` は、結果が違反のときに根拠を文面へ写すだけで、同じ「検査 × 要素」は検証1回の中で1回だけ評価する（`result` のキャッシュ）。専用チェッカーは1回分（`SemanticCheckerRun`＝対象の要素・指摘・判定しなかった指摘の種類とその理由）を返す。フェーズ（`activeFrom`）は文書の状態で決まるため、呼び出し側が先に判定する。

**検査の出どころ**（`schema.RuleOrigin`）: どの文書の（`owner`＝スキーマの id。組込み定義体に直接書いた検査は `(builtin)`）・どの書き方から展開された（`at`＝展開の記録と同じ文字列。`constraints[2]`・`relations[18]（定義 relation-in-count）` など）・何番目の検査か（`index`）。スキーマの読み込みが、展開の記録（`ExpandedCheck`）から `ConstraintRule.origin`・`CheckerRule.origin` に付ける。検査の `id` は利用者が付ける呼び名で、定義のひな型から展開した検査どうしでは重なりうるため、見分けには使わない。

**指摘の出どころ**（`validate.IssueSource`。仕様 CALC-32）: 検査の出どころ・呼び名・専用チェッカーの指摘の種類・対象の要素・配り方（`IssueDelivery`: `REPORT`＝報告先／`NOTICE`＝同じ要素の他の出現への案内／`FIX_SITE`＝直せる場所への案内／`VALUE`＝値の場所／`REF`＝参照の場所）・根拠に関わった要素（対象の要素を除く・ID 昇順・**全件**。`relatedIds` は表示用に先頭だけ）。`identity` は「同じ検査の・同じ要素についての・同じ配り方の指摘」を見分けるキーで、場所と文面を含めない。定義体を通らない指摘（構造の検証・参照の解決・ID の重複など）は、まだ出どころを持たない（`source = null`。段B で扱う）。

**保留の残し方**: 判定を保留した検査は指摘にしない（検証パネルには出さない）。`Validator.validateDetailed` が返す `ValidationResult.withheld`（`WithheldCheck`＝検査・対象の要素・場所・理由）に残し、`validate` はその指摘だけを返す薄い入口。集めるのは報告先（対象の要素を定義した場所）の文書の検証でだけ——他の出現の文書では集めない（二重に数えない）。値・参照の場所へ出す検査の保留は集めていない（段B）。

**載せ替えたもの**（意図的差分 D-26）:

- 提出ゲート `no-new-errors`（`Staging.newErrors`）: 出どころを持つ指摘は `identity` で見分ける。同じ検査 × 要素のエラーが適用前にもあれば既存のエラーとし、**根拠に関わる要素が増えたときだけ**新しいエラーとする（不足が3件→2件は既存・2件→3件は新しい。要素の挿入で場所の番号がずれても既存のまま）。出どころを持たない指摘は今までどおり（場所, 文面）
- 準備完了レポート（`Readiness`）: 他の出現への案内（`NOTICE`）は、報告先の指摘の写しなので数えない。保留の件数を要素ごとに出し、内訳の一覧を付ける（`--pending` でも全件。「エラー0」の要素にも、確かめられなかった検査がありうるため）
- 検査の状況（`RuleStatus`）: `outcome` と `reason` を持ち、保留は「判定保留（理由）」と表示して充足に数えない。UI は充足・不足と別の見せ方（情報のアイコン・淡色）
- CLI `validate`: 保留の内訳（文書ごとの `withheld`）と件数（あるときだけ）。`--sources` で指摘の出どころを出す

**検証の指摘（文面・重大度・場所）は変わらない**——テンプレート・サンプル・specs の `validate` と `readiness` の出力は、変更の前後で完全に一致する。

### 4.38 クエリの意味の整理: 参照を書いた要素・入れ子の線・via（P27）

利用者が自分で検査を書き始める前に、クエリの線（`edge`）の意味の食い違いを直した（意図的差分 D-27。BACKLOG UX-108・UX-106・UX-109）。

- **参照を書いた要素は1つ**: 参照の線は、向きによらず「その参照を含む最も内側の ID を持つ要素」が書いたものとして辿る。`dir: out` はその要素が直接書いた参照だけ（`SemanticEntityGraph.ownRefs`）、`dir: in` の相手はその参照を直接書いた要素（`sourceEntryOf`）。以前は `dir: out` だけが入れ子の要素の参照も辿り（`outgoingRefs`＝配下の全参照）、「A から出て X に着くのに、X へ入ってくる相手に A がいない」ことがあった（対象型の検査を定義体へ移したとき、外側の要素に誤った型の不一致が出た原因）。値の式 `refCount` の `dir: out` も同じ捉え方にした。**影響の実測**: 変更の前後で、テンプレート・サンプル・specs の検証結果と全テストは1件も変わらなかった（入れ子の要素の参照まで辿る挙動に依存する定義・データ・テストは無かった）。
- **入れ子の線（`edge` の `contains: true`）**: 参照ではなく、要素の入れ子を辿る線。`dir: out` は直下に入れ子になった ID を持つ要素（`childIds`）、`dir: in` は外側の要素（`parentIds`）。文書ノードとの間は、その文書にいちばん外側で書かれた要素（`topLevelIds` / `topLevelDocuments`）。入れ子の関係は、同じ文書の中の ID 出現のパスの包含だけで決まる（`SemanticEntityGraph.build` が出現ごとに外側の出現を求める）。`repeat` と組み合わせられる（何段の入れ子でも）。参照を通らないため、`relation` / `via` / `in` / `bind` / `row` とは一緒に書けない（パースで拒否）。「外側の要素から、配下のどの要素が書いた参照でも辿る」は、部品の組合せ（`contains` を `repeat: {min: 0}` で辿ってから参照の線）で書く——以前の `dir: out` の挙動は、この組合せとして残る。
- **`via` は場所の末尾のキーの並び**（`QueryEdgeStep.viaMatches`。`edge`・`refCount`・文脈の開示 `neighborIds` が共有）: 1語ならキー名（今までどおり `viaKey` と比べる）、複数語なら参照が書かれた場所のキー（配列の番号を除く）の末尾と比べる。整合ルールの `lookup.via` に `target.ref` のような入れ子の場所を書いても、その場所の参照だけを辿れる。
- **読むための機能は今までどおり**: 文脈の開示（Traverse の `follow: out`＝`SemanticQuery.neighborIds`）とエンティティページの関係の一覧は、入れ子の要素が書いた参照も見せる（`outgoingRefs`）。判定ではなく、その要素の配下を読む目的のため。
- **まだ文書に入れていない内容の検証**（UX-109）: 定義体の検査は索引にある要素だけを判定するため、AI 生成のプレビューは、提案の内容を重ねた索引（`AppState.overlayIndexWith`。未保存の編集も重ねる）で検証する。提案で新しく書かれた ID にも、詳細文書の不足・エンティティの整合・利用者の `constraints` が効く。

## 5. 主要データフロー

- **編集**: フォーム操作 → `Slot.set` / ツリー直接変更 → `markEdited()`（履歴・dirty は core.EditBuffer が記録。連続入力は合体キーで1エントリへ＝§4.34） → `version`++ → 再コンポーズ。ソースタブはライブ同期（パース成功時だけ `applySourceText` で同じ経路へ。§4.34）
- **保存**: `canonicalText()`（コメント・スタイル込み）をファイルへ書き出し → `Workspace.noteDiskChanges`（通常文書は増分索引更新、スキーマファイル・派生スキーマの導出元文書は全再構築。§4.5／§4.20／§4.23） → `indexVersion`++
- **外部変更検出**: `FileWatcher` の変更フラグ（またはフォールバックの1秒周期）→ `pollExternalChanges` がスタンプ比較 → 変更ファイルの再読込＋増分索引更新（`_schemas` 変更時のみ全再構築）
- **ジャンプ**: `navigateToId` → 索引の出現検索（複数なら選択ダイアログ／横ぐしビュー）→ `openFile(file, focus)` → `highlightPath` 設定 → `Highlightable` がスクロール
- **ビューグリッド**: `_views` 定義 → `Views.resolve`（行=ディスク索引、セル値=編集中ツリー優先の loader）→ セル編集は該当文書の `Slot` 直接変更＋`markEdited()` → 保存 → `rebuildIndex()` で行集合にも反映。行アクションは `AppState.createViewDetail`（スキャフォールド→書込→開く）／`addViewRow`（一覧文書の配列へ未保存編集として追加）
- **AI生成**: ✨/ボタン → `AppState.requestAi(doc, field, path)`（対象文書は押したフォームの `FormCtx.doc`。R24-01） → シェル直下の `AiGenerationHost` が対象文書のツリーを複製して入力構築 → `GenerationRun.run`（カーネル上の1ターン提出。Structured Outputs で生成 → ステージング → 実行記録 `_runs`。§4.19） → `combineProposal`/`replaceInClone` で適用後ツリーを合成 → 差分プレビュー＋`Validator` で事前検証 → 適用（対象文書の `root` 差し替え。編集スペース以外＝エンティティページ等からでも同経路）
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
- **派生スキーマの写像項目の追加**: `semantic-core/schema/SchemaDerivation.kt` の `parse`（宣言のパース）と `compile`（コンパイル。root `SchemaLoader.parseDerive`・`DerivedSchemas` はこの委譲シェル）、組込みメタスキーマ（`builtin-schema` の `derive` 定義）を**そろえて**更新する。語彙は skill の列マッピング（SK-02）と揃える（§4.23 / C-2）。
- **導出（計算列）の語彙追加**: 宣言モデル（`schema/Derivation.kt`）・パース（`Views.parseView` の computed）・`builtin-view`（computed）・解決器（`index/Derivation.kt`）を**そろえて**更新する（§4.26）。
- **カバレッジ（`coverage`）とエンティティの整合の変更**: コードではなく**組込み定義体** `builtin-definitions/coverage.yaml`・`entities.yaml` を更新する（coverage の書き方の形は組込みメタスキーマ `builtin-schema` の `coverage`）。充足状況の表示の文言は検査の `status`、詳細文書の側への案内は `fixSite`、「詳細文書を作成」は `responses` の `create` の宣言（§4.36）。
- **関係の宣言の検査（多重度・対象型）の変更**: コードではなく**組込み定義体** `workspace-core/src/main/resources/builtin-definitions/relations.yaml` と、書き方の形を持つ組込みメタスキーマ（`builtin-relations`）を更新する（§4.36）。
- **整合ルール（`consistency`）の変更**: コードではなく**組込み定義体** `workspace-core/src/main/resources/builtin-definitions/consistency.yaml`（検査の定義と語彙の定義）と、書き方の形を持つ組込みメタスキーマ（`builtin-schema` の `consistency`）を更新する（§4.36）。
- **値の条件・値の計算の追加**: 値の条件は `schema.CheckOp`（演算子と、文面で左右の間に置く言葉）と `SemanticExpression.compare` / `test` に足す——`check` と `where` の両方で使えるようになる。値の計算は `schema.ValueExpr`（パースと `sources` / `reads` / `templateReads`）・`SemanticExpression`（純粋な関数）・`SemanticQuery.evalExpr`（値の出どころの引き継ぎ）に足す。どちらも、評価できないときは null（判定保留）を返す規則を守る。組込みメタスキーマ（`builtin-schema` の constraints の `where` / `check` の説明・`builtin-view` の `where`）と README を**そろえて**更新する（§4.36）。
- **検査を新しく足すとき**: まず YAML の定義体で書けるかを考える（§4.36・CONVENTIONS §9）。書けないなら、足りない部分を小さな意味プリミティブ（semantic-core）として足す。複雑なアルゴリズムの検査は専用チェッカーとして個別に実装してよい。
- **エンティティ・関係語彙の追加**: 宣言モデル（`schema/Relations.kt`）・パース（`SchemaLoader` の `entity:` / `kind: relations` / `entities:` / `enforcement:`）・組込みメタスキーマ（`builtin-schema` の entity・`builtin-relations`）・スキーマ設計UI（`SchemaEditor` のエンティティ節）を**そろえて**更新する（§4.30 / §4.33）。グラフの導出項目を増やすときは `EntityGraph` に閉じ、消費側で走査を書かない。グラフ整合の制約を増やすときは `validate/GraphIntegrity.kt` に閉じる（判定保留の哲学＝型未宣言・未解決には黙る。§4.31）。エンティティ合成の項目を増やすときは `core/Aspects.kt` に閉じ、消費側（ページUI・read_entity・CLI）で合成を書かない（§4.33）。
- **クエリ語彙の追加**: 宣言モデル・パース（`schema/Query.kt`）・評価器（`index/Query.kt`）・組込みメタスキーマ（`builtin-view` の rows（match / where / also / rowKey）・`builtin-activity` の foreach.query・`builtin-schema` の constraints）を**そろえて**更新する（§4.32）。消費側（Views / Foreach / Validator / CLI）に評価ロジックを書かない。「やらないこと」（集約は件数のみ・任意深さの再帰なし・任意式なし・optional 結合なし）の解禁は実例の需要で段階判断する。
- **AIプロバイダの追加**: OpenAI互換のエンドポイントなら `AiConnection`（ベースURL上書き・プリセット）で足りる。独自APIのプロバイダは `AiProvider` に enum を追加し、`AiGenerator.generate` の分岐と専用クライアント（`generate` + `parseResponse` を持つ object）・`ToolChat.newSession` の分岐を実装する。HTTP送信は `AiHttp.post` を使う（§4.18）。
- **入出力形式の追加**: `TableFormat` に enum を追加し、`skill/Tables.kt` の read / write に分岐を実装する（Importer / Exporter は形式非依存のため変更不要）。
- **テンプレートパックの追加**: `skill/TemplatePacks.kt` の `all` に `Pack`（ファイルパス→内容の埋め込み）を登録する。`ImportExportTest` に倣い、展開後の検証エラー0をテストで保証する。
