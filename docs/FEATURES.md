# 機能索引

機能から実装ファイルとテストを引く索引。1 行 1 機能で、経緯は書かない（記録は [archive/](archive/README.md) と git log）。
利用方法は [manual/](manual/README.md)、設計は [architecture/](architecture/README.md) が正。「設計」列は設計文書の主題。

- **テスト**: `単体` は単体テストあり（代表のテスト名を添える）。`手動` は手動確認のみ。
- 未実装は [ROADMAP.md](ROADMAP.md)、既知の制約は [BACKLOG.md](BACKLOG.md) と manual/limits.mdに置く。

## 1. ワークスペース・ファイル管理

| ID | 機能 | 主な実装 | テスト | 設計 |
|---|---|---|---|---|
| W-01 | ワークスペース選択（起動引数・ダイアログ・スタート画面） | `Main.kt`, `AppState.openWorkspace` | 手動 | [ui](architecture/ui.md) |
| W-02 | ファイルツリー（展開・折りたたみ・未保存●） | `ui/FileTreePane.kt` | 手動 | [ui](architecture/ui.md) |
| W-03 | ファイルとディレクトリの作成・改名・削除（新規 YAML はスキーマの初期値で生成し、作成は Undo できる・台帳内の改名は参照の書き換えと移動を 1 トランザクションで行う） | `AppState.createDocumentFile` / `renamePath`, `ui/FileTreePane.kt` | 単体 `AppStateCreateDocumentTest`, `DocRefTest` | [editing-history](architecture/editing-history.md) |
| W-04 | 外部変更検出（OS ネイティブ監視・使えない環境は 1 秒ポーリング・未編集は自動再読込・未保存は競合バナー） | `core/FileWatcher.kt`, `AppState.pollExternalChanges` | 単体 `FileWatcherTest` | [workspace](architecture/workspace.md) |
| W-05 | 最近開いたファイル履歴（12 件・ワークスペース単位で永続化） | `AppState.recentFiles` | 手動 | [ui](architecture/ui.md) |
| W-06 | UI レイアウトの永続化（ペイン幅・開閉・テーマ） | `Main.kt`（`uiPreferences`） | 手動 | [ui](architecture/ui.md) |
| W-07 | 未保存確認（終了時・ワークスペース切替時） | `Main.kt` | 手動 | [ui](architecture/ui.md) |
| W-08 | ワークスペース管理（最近開いた履歴・スタート画面・パレットからの切替） | `Main.kt`（`StartScreen`）, `AppState.recentWorkspaces` | 手動 | [ui](architecture/ui.md) |
| W-09 | UI シェル（レールで編集・台帳・実行・設計の 4 スペースを切替・実行スペースは定義が空でも表示） | `app/AppState.kt`（`UiSpace`）, `ui/Shell.kt` | 単体 `NavigationHistoryTest` | [ui](architecture/ui.md) |
| W-10 | 受信箱（承認待ち変更セット・エージェントの質問・外部変更競合を実行スペースの先頭タブに集約・バッジと集計） | `ui/RunsSpace.kt`（`InboxTab`）, `Main.kt`（`StatusBar`） | 手動 | [ui](architecture/ui.md) |
| W-11 | ワークスペース構造（`.yde/` 構成と `ledger` 宣言・レガシー構成は互換・配置判定は `WorkspaceLayout`・CLI `migrate`） | `workspace-core/layout/WorkspaceLayout.kt`, `core/Workspace.kt` | 単体 `WorkspaceLayoutTest` | [workspace](architecture/workspace.md) |
| W-12 | エディタ層と台帳層の分離（台帳外の YAML はフォームと単文書検証だけ・索引・集計・エージェントは台帳のみ） | `validate/Validator.kt`, `app/AppState.kt`（`editorOnlyFiles`） | 単体 `WorkspaceLayoutTest` | [workspace](architecture/workspace.md) |

## 2. 文書編集

| ID | 機能 | 主な実装 | テスト | 設計 |
|---|---|---|---|---|
| E-01 | スキーマ駆動フォーム（基底の種類別の編集欄・型から引き継ぐヒント＝複数行と入力欄の案内と表示の形 `format`・必須マーク・説明・折りたたみ・id 型は既存 ID の補完と連番の提案・int / number の桁区切り表示 `display: grouped`・残留値の修復 UI） | `ui/FormView.kt`, `schema/IdSuggest.kt` | 単体 `IdSuggestTest` | [ui](architecture/ui.md) |
| E-02 | 汎用ツリーエディタ（スキーマなし文書・スキーマ外キー・順序とコメントを保ったキー改名） | `ui/FormView.kt`（`GenericEditor`） | 単体 `RenameKeyTest` | [ui](architecture/ui.md) |
| E-03 | ソース編集（入力のたびにパースし成功時だけツリーへ反映・パース不能中は最後の解析結果が正・検索置換・ハイライト） | `ui/SourcePane.kt`, `ui/SourceHighlight.kt` | 単体 `SourceHighlightTest` | [editing-history](architecture/editing-history.md) |
| E-04 | 差分表示（保存済みと編集中の行整列・文字単位の強調） | `ui/DiffPane.kt` | 単体 `DiffPaneTest` | [ui](architecture/ui.md) |
| E-05 | Undo/Redo（ワークスペース横断の単一履歴・連続入力は 1 秒以内なら合体・実行後のフィードバックと予告） | `app/AppState.kt`（`undo` / `redo` / `historyNotice`）, `core/Documents.kt` | 単体 `DocumentsTest`, `HistoryNoticeTest` | [editing-history](architecture/editing-history.md) |
| E-06 | 保存（Ctrl+S・すべて保存・コメントとキー順と表記を保持・日付スカラーは文字列のまま往復） | `AppState.save*`, `yaml-core/yaml/YamlIO.kt` | 単体 `YamlIOTest` | [yaml-model](architecture/yaml-model.md) |
| E-07 | 文書メタデータ `_document`（id・name・description）の編集とヘッダー表示 | `ui/FormView.kt`（`DocumentMetadataEditor`） | 手動 | [ui](architecture/ui.md) |
| E-08 | 文書キャッシュ（ファイル切替時も未保存編集を保持） | `AppState.docCache` | 手動 | [editing-history](architecture/editing-history.md) |
| E-09 | 同一 ID の他文書出現ストリップ（相手側の情報の表示とジャンプ） | `ui/FormView.kt`（`SameIdOccurrencesStrip`） | 手動 | [ui](architecture/ui.md) |
| E-10 | 複数ドキュメント YAML（フォームは先頭・後続は保持して再出力・ソースは全体を編集可） | `yaml-core/yaml/YamlIO.kt`（`parseAll` / `dumpAll`）, `app/OpenDocument` | 単体 `OpenDocumentTest` | [yaml-model](architecture/yaml-model.md) |
| E-11 | 畳み込みサマリ（畳んだ要素の 1 行要約・既定はルートから 2 階層まで展開） | `ui/FormView.kt`, `schema/Display.kt` | 単体 `DisplayTest`, `DefaultExpandTest` | [ui](architecture/ui.md) |
| E-12 | 閲覧専用モード（`FormCtx.readOnly`・入力欄はテキスト表示・書込は拒否・横ぐしとグリッドへ波及） | `ui/FormView.kt`（`FormCtx`）, `app/AppState.readOnlyMode` | 単体 `ReadOnlyTest` | [ui](architecture/ui.md) |
| E-13 | 配列と map のテーブル表示（適否の自動判定と `display: table / list`・セル編集・展開フォーム・列幅） | `ui/FormView.kt`（`ObjectTable` ほか）, `schema/Display.kt`（`tableColumns`） | 単体 `DisplayTest`, `ArrayTableTest` | [ui](architecture/ui.md) |
| E-14 | 保存時に自動で入る値（印 `auto`＝更新日・作成日・版。保存の直前の 1 トランザクション・変更セットの適用にも） | `semantic-core/semantic/AutoValues.kt`, `workspace-core/workspace/WorkspaceSession.kt` | 単体 `SemanticAutoValuesTest`, `AutoValuesKernelTest`, `AppStateAutoValuesTest` | [auto-values](architecture/auto-values.md) |

## 3. スキーマ

| ID | 機能 | 主な実装 | テスト | 設計 |
|---|---|---|---|---|
| S-01 | スキーマ読込（`match` glob 先勝ち・id 重複検出・読むのはカーネルの経路だけ・root の `SchemaDef` は薄い型） | `workspace-core/workspace/SchemaDocuments.kt`・`WorkspaceReader.kt`, `schema/Schema.kt` | 単体 `SchemaLoaderTest`, `KernelCheckLoadingTest` | [schema](architecture/schema.md) |
| S-02 | 型システム: 基底の種類 14 種（表現の基底 9＋意味の基底型 `id` / `ref` / `date` / `time` / `datetime`。存在の検証）＋組込みの定義体 `text` / `line` / `word` | `semantic-core/schema/Fields.kt`・`ValueSemantics.kt`・`BuiltinTypes.kt` | 単体 `MetaSchemaTest`, `TypeDefinitionsTest` | [schema](architecture/schema.md) |
| S-03 | 名前付き定義と再帰参照（`definitions` / `$ref`・狭める規則の合成） | `semantic-core/schema/SchemaTree.kt`（`resolve`） | 単体 `TypeDefinitionsTest` | [schema](architecture/schema.md) |
| S-04 | メタスキーマ検証と宣言のない関係名の点検（実体はカーネル・読み込みエラーへ足す） | `workspace-core/workspace/SchemaDefinitionChecks.kt` | 単体 `SchemaLoaderTest`, `KernelOnlyChecksTest` | [schema](architecture/schema.md) |
| S-05 | スキーマ設計 UI（説明・document／library・`$ref`・プレビュー・適用対象・`requiredFrom`・`role`・enum 選択肢・制約・ヒント＝案内／サンプル値／複数行・`phases`・`coverage`・エンティティ節＝ID の型・object の同一性） | `ui/SchemaEditor.kt` | 手動 | [ui](architecture/ui.md) |
| S-06 | サンプル YAML 生成（プレビュー・コピー・新規ファイルの初期値・必須スカラーに型別の例値） | `semantic-core/schema/SchemaScaffold.kt` | 単体 `SchemaLoaderTest`, `DocumentScaffoldTest` | [schema](architecture/schema.md) |
| S-07 | スキーマ外キーの検証ポリシー（`unknownFields: allow / warning / error`） | `validate/Validator.kt`, `index/IdIndex.kt` | 手動 | [schema](architecture/schema.md) |
| S-08 | 参照の関係種別（`type: ref` の `relation`） | `schema/Schema.kt`, `index/IdIndex.kt` | 単体 `WorkspaceCliTest` | [graph-query](architecture/graph-query.md) |
| S-09 | カバレッジルール（`coverage` の `detailIn`＝詳細文書の存在・参照ベースは削除済みで書くと移行先を案内する定義エラー） | `semantic-core/schema/Coverage.kt` | 単体 `CoverageTest`, `SemanticCoverageTest` | [checks](architecture/checks.md) |
| S-10 | enum 値ごとの追加フィールド（`cases`・多段展開・フォーム・検証・索引・AI 生成・設計 UI） | `schema/Schema.kt`（`effectiveFields`）, `ui/FormView.kt` | 単体 `SchemaLoaderTest`, `ValidatorTest` | [schema](architecture/schema.md) |
| S-11 | フェーズ機構（`phases`・`requiredFrom` の累積必須・`activeFrom` の判定保留） | `schema/Schema.kt`（`PhasesDef`, `requiredAt`） | 単体 `PhaseTest` | [schema](architecture/schema.md) |
| S-12 | スキーマ共通部品（`kind: library` と `moduleId#definition` の修飾 `$ref`・二段階ロード） | `schema/Schema.kt`（`SchemaCatalog`） | 単体 `SchemaLoaderTest` | [schema](architecture/schema.md) |
| S-13 | 表示解決（1 行要約・`role: title / subtitle` ＞ 予約語 ＞ id・同一 ID の他出現からタイトルを借りる） | `schema/Display.kt`, `index/IdIndex.kt`（`DocScanner`） | 単体 `DisplayTest` | [index-references](architecture/index-references.md) |
| S-14 | enum のリッチ選択肢（`{value, label, description}`・label は表示専用） | `schema/Schema.kt`（`EnumOption`）, `ui/FormView.kt`（`EnumEditor`） | 単体 `DisplayTest` | [schema](architecture/schema.md) |
| S-15 | 組込みメタスキーマ（`_schemas` 文書に適用・一般スキーマの match は及ばない） | `schema/BuiltinSchemas.kt`, `core/Workspace.kt`（`schemaFor`） | 単体 `MetaSchemaTest` | [schema](architecture/schema.md) |
| S-16 | スキーマ推測（既存 YAML から型・参照・enum・必須を推測してドラフト生成・CLI `infer-schema`） | `schema/SchemaInference.kt`, `app/AppState.inferSchema` | 単体 `SchemaInferenceTest` | [schema](architecture/schema.md) |
| S-17 | 派生スキーマ（`derive:` で導出元文書から検証スキーマをコンパイル・導出元の変更は全再構築） | `semantic-core/schema/SchemaDerivation.kt`, `core/Workspace.kt` | 単体 `DerivedSchemasTest` | [schema](architecture/schema.md) |
| S-18 | 制約語彙（`minLength` / `maxLength` / `pattern` / `min` / `max`・AI 生成の JSON Schema へ伝搬） | `schema/Schema.kt`, `yaml-core/structure/StructureValidator.kt` | 単体 `ConstraintTest` | [schema](architecture/schema.md) |
| S-19 | エンティティ型宣言（`entity:` の `idPattern` / `idType`＝ID の形式で判別・object の `entity`＝要素の形で判別・`defines`・走査が型と役割を付与） | `semantic-core/schema/Relations.kt`（`EntityDecl`）, `index/IdIndex.kt` | 単体 `EntityDeclTest`, `DocScannerTest`, `TypeDefinitionsTest` | [graph-query](architecture/graph-query.md) |
| S-20 | 関係レジストリ（`kind: relations` の関係語彙・label／inverseLabel・`enforcement`・CLI `infer-relations`） | `semantic-core/schema/Relations.kt`（`RelationDef`）, `core/Workspace.kt` | 単体 `RelationRegistryTest` | [graph-query](architecture/graph-query.md) |
| S-21 | 属性付き参照（`type: ref` に `fields:` があるときだけ `{to, 属性...}` を許可・to の座標は従来と同じ） | `index/IdIndex.kt`（`RefEntry.attributes`）, `ui/FormView.kt`（`RefEditor`） | 単体 `DocScannerTest`, `ValidatorTest` | [graph-query](architecture/graph-query.md) |
| S-22 | アスペクト宣言とエンティティ型レジストリ（`aspect` / `owns`・レジストリの `entities:` 節） | `semantic-core/schema/Relations.kt`（`EntityTypeDef`, `ownedFieldKeys`） | 単体 `AspectTest` | [graph-query](architecture/graph-query.md) |
| S-23 | スキーマの値に式（項目の印 `expression: true`・共通の読み取りと評価と文面・`filePattern` と `scaffold`） | `semantic-core/schema/ExpressionFields.kt`, `semantic-core/semantic/Values.kt` | 単体 `ExpressionFieldsTest`, `SemanticValuesTest` | [schema](architecture/schema.md) |
| S-24 | ユーザ定義の型（`type: 名前`・型の連鎖・狭める規則・メタスキーマへの型名の合成・オブジェクト型＝object の `entity`・項目を足す／狭める・要素ごとの型の検査） | `semantic-core/schema/SchemaTree.kt`, `semantic/ElementChecks.kt`, `workspace-core/workspace/MetaSchemaTypes.kt` | 単体 `TypeDefinitionsTest`, `ObjectTypesTest` | [schema](architecture/schema.md) |

## 4. 検証

| ID | 機能 | 主な実装 | テスト | 設計 |
|---|---|---|---|---|
| V-01 | 構造の検証（必須・型・enum・date・case キー・利用者のスキーマから導出した最小の構造スキーマで行う） | `yaml-core/structure/StructureValidator.kt`, `workspace-core/workspace/DerivedStructures.kt` | 単体 `StructureValidatorTest`, `ValidatorTest` | [schema](architecture/schema.md) |
| V-02 | 参照解決の検証（未解決・パターンやスコープの対象外・未保存 ID の考慮） | `validate/Validator.kt` | 手動 | [checks](architecture/checks.md) |
| V-03 | ID 重複検出（同一文書内のみエラー・文書間は段階的詳細化） | `validate/Validator.kt` | 手動 | [checks](architecture/checks.md) |
| V-04 | 検証パネル（この文書／全体のスコープ切替・指摘のクリックは行き先の選択メニュー・スキーマ定義エラーとフェーズ進捗も表示） | `ui/SidePanel.kt`, `app/AppState.kt`（`validateWorkspace`） | 手動 | [ui](architecture/ui.md) |
| V-05 | カバレッジ検証（詳細文書の存在・組込み定義体・作成場所の併記と「詳細文書を作成」） | `builtin-definitions/coverage.yaml`, `workspace-core/workspace/DocumentScaffold.kt` | 単体 `CoverageTest`, `CoverageMessageTest` | [checks](architecture/checks.md) |
| V-06 | フェーズ考慮の検証（`requiredFrom` は到達フェーズから・`activeFrom` は到達まで保留・フェーズ別進捗） | `validate/Validator.kt`（`phaseProgress`） | 単体 `PhaseTest` | [schema](architecture/schema.md) |
| V-07 | 整合検証（`consistency`・導出した期待値と `assert` の関係・組込み定義体・来歴つきの指摘と「導出値を適用」） | `builtin-definitions/consistency.yaml`, `workspace-core/validate/WorkspaceChecks.kt` | 単体 `ConsistencyTest` | [checks](architecture/checks.md) |
| V-08 | グラフ整合検証（対象型・多重度・非循環・強制スイッチ・いずれも組込み定義体・「参照の追加先を開く」） | `builtin-definitions/relations.yaml`, `semantic-core/schema/Relations.kt`（`EnforcementDef`） | 単体 `GraphIntegrityTest` | [checks](architecture/checks.md) |
| V-09 | パターン制約（`constraints`・`match` / `also` / `where` / `expect` / `count` / `same` / `check`・否定と「または」・値の式・順序は型で・`repeat`・`forAll`） | `semantic-core/schema/Query.kt`・`semantic/Query.kt` | 単体 `QueryConstraintTest`, `CheckConstraintTest`, `TypeDefinitionsTest` | [checks](architecture/checks.md) |
| V-10 | 重複所有の検証（同一エンティティの複数出現が同じフィールドを所有したら警告・双方に entity 宣言と `owns` があるときだけ） | `workspace-core/validate/CodedDocumentChecks.kt` | 単体 `AspectTest`, `KernelOnlyChecksTest` | [checks](architecture/checks.md) |
| V-11 | 網羅マトリクス（`matrix`・行 × 列の全組合せに証人があること・組込み定義体で `forAll` へ展開） | `builtin-definitions/matrix.yaml` | 単体 `MatrixTest` | [checks](architecture/checks.md) |
| V-12 | 遷移検証（`flow`・到達不能・行き止まり・宣言外遷移・到達の計算は専用チェッカーで文面と重大度は定義体） | `semantic-core/semantic/Flow.kt`, `builtin-definitions/flow.yaml` | 単体 `FlowTest` | [checks](architecture/checks.md) |
| V-13 | 準備完了レポート（CLI `readiness`・指摘をエンティティへ帰属しエラー 0 で準備完了・型別とフェーズ別の集計） | `validate/Readiness.kt`, `core/Cli.kt` | 単体 `ReadinessTest` | [checks](architecture/checks.md) |
| V-14 | 検査の定義と語彙の定義（`constraints` の `use` ＋ `with`・`functions`・`vocabulary`・組込みは特権を持たない・CLI `checks`） | `semantic-core/schema/CheckDefinitions.kt`, `workspace-core/workspace/CheckCatalog.kt` | 単体 `CheckDefinitionsTest`, `CheckDefinitionTest` | [checks](architecture/checks.md) |
| V-15 | 検査の結果の 4 つと指摘の出どころ（違反・充足・保留・対象外・`IssueSource`・保留は `withheld`・CLI `validate --sources`） | `semantic-core/semantic/CheckResults.kt`, `workspace-core/validate/CodedChecks.kt` | 単体 `SemanticRulesTest`, `CheckOutcomeTest` | [checks](architecture/checks.md) |
| V-16 | クエリの線の意味（参照を書いた要素は最も内側の ID を持つ要素・入れ子の線 `contains`・`via`） | `semantic-core/semantic/Graph.kt`（`ownRefs`）, `semantic-core/schema/Query.kt`（`viaMatches`） | 単体 `QueryNestingTest`, `PreviewValidationTest` | [graph-query](architecture/graph-query.md) |
| V-17 | 規模の計測（CLI `bench`・合成ワークスペースで開く・検証・レポートの時間と伸び方を出す） | `core/Bench.kt`, `core/Cli.kt` | 単体 `BenchTest` | [checks](architecture/checks.md) |
| V-18 | 検査の評価の共有（同じ索引の上では検査 × 要素を 1 回だけ評価・全体の検証が文書数に比例） | `semantic-core/semantic/CheckSession.kt`, `workspace-core/workspace/WorkspaceCheckRunner.kt` | 単体 `KernelCheckLoadingTest`, `BenchTest` | [checks](architecture/checks.md) |
| V-19 | コードで実装された検査（ID パターンからの逸脱・重複所有・テンプレートの健全性・カーネルが配る） | `workspace-core/validate/CodedDocumentChecks.kt`, `workspace-core/workspace/SchemaDefinitionChecks.kt` | 単体 `KernelOnlyChecksTest` | [checks](architecture/checks.md) |
| V-20 | 文書 1 つの検証の組み立て（構造 → ID と参照 → エンティティ型 → 関係名 → 定義体 → テンプレート → 案内・台帳外の降格と重複除去） | `workspace-core/validate/DocumentValidator.kt` | 単体 `KernelCheckLoadingTest`, `KernelOnlyChecksTest` | [checks](architecture/checks.md) |

## 5. ID・参照

| ID | 機能 | 主な実装 | テスト | 設計 |
|---|---|---|---|---|
| I-01 | ID 索引（ディスク基準・`type: id` とキー名 `id`・変更ファイルだけの増分更新） | `index/IdIndex.kt`（`DocScanner`, `update`） | 単体 `IdIndexIncrementalTest` | [index-references](architecture/index-references.md) |
| I-02 | 参照認識（`type: ref` と命名規約） | `index/IdIndex.kt`（`DocScanner`） | 手動 | [index-references](architecture/index-references.md) |
| I-03 | リンクジャンプ（→ボタン・ハイライト・複数出現の選択） | `AppState.navigateToId`, `ui/Common.kt`（`Highlightable`） | 手動 | [ui](architecture/ui.md) |
| I-04 | 参照候補の補完（`idPatterns` / `scope` で絞り込み・解決先タイトルの併記・行き先の選択メニュー） | `ui/FormView.kt`（`RefEditor`） | 手動 | [ui](architecture/ui.md) |
| I-05 | バックリンク（被参照一覧・参照元の ID とタイトル） | `IdIndex.backlinksTo`, `ui/SidePanel.kt` | 手動 | [index-references](architecture/index-references.md) |
| I-06 | ID 検索（コマンドパレット・ID とタイトルの部分一致・オーバーレイ索引） | `Main.kt`（`CommandPalette`） | 手動 | [ui](architecture/ui.md) |
| I-07 | エンティティページ（合成全体像・関係パネル・カバレッジ・改名／統合／削除・グラフレンズへ） | `ui/IdCrossView.kt`, `core/Aspects.kt` | 手動（合成は `AspectTest`） | [graph-query](architecture/graph-query.md) |
| I-08 | ナビゲーション履歴（戻る・進む・スペースとタブと ID とビューとハイライトを復元・ショートカットはウィンドウで受ける） | `AppState.goBack` / `goForward`, `Main.kt`（`handleShortcut`） | 単体 `NavigationHistoryTest` | [ui](architecture/ui.md) |
| I-09 | ID インスペクタ（右パネルの検証・関係・出現の 3 タブ） | `ui/SidePanel.kt` | 手動 | [ui](architecture/ui.md) |
| I-10 | 導出グラフ（走査結果から導出する読み取り専用のエンティティグラフ・索引の更新で無効化） | `semantic-core/semantic/`（`SemanticEntityGraph`）, `index/IdIndex.kt`（`graph`） | 単体 `EntityGraphTest` | [graph-query](architecture/graph-query.md) |
| I-11 | 文書参照（`target: document`・文書ノードへの参照・エンティティ参照とは混ぜない） | `index/IdIndex.kt`（`RefEntry.targetKind`）, `ui/FormView.kt`（`DocRefScalarEditor`） | 単体 `DocRefTest` | [graph-query](architecture/graph-query.md) |
| I-12 | 場所は文書 ID（索引・検証・ビュー・クエリの場所はワークスペース相対の `DocumentId`・root の型はカーネルの別名・編集用セッションは `fork`） | `index/IdIndex.kt`, `workspace-core/workspace/WorkspaceSession.kt`（`fork`） | 単体 `SessionForkTest`, `EditingSessionForkTest` | [index-references](architecture/index-references.md) |

## 6. ワークスペース横断ビュー

| ID | 機能 | 主な実装 | テスト | 設計 |
|---|---|---|---|---|
| X-01 | 一覧ビュー（ID・タイトル・状態・担当・種別・絞り込み・未保存編集に追随） | `ui/WorkspaceViews.kt`, `AppState.workspaceRecords` | 手動 | [views](architecture/views.md) |
| X-02 | 一括編集（ルート直下の任意スカラーキー・セッション 1 トランザクション＝Undo 1 回） | `AppState.bulkUpdate`, `core/Documents.kt` | 単体 `AppStateRefactorTest`, `SessionWriteParityTest` | [editing-history](architecture/editing-history.md) |
| X-03 | グラフレンズ（エンティティ起点の近傍・型と relation で絞った部分グラフ・力学配置） | `ui/WorkspaceViews.kt`（`GraphLensView`）, `ui/GraphLayout.kt` | 単体 `GraphLayoutTest` | [graph-query](architecture/graph-query.md) |
| X-04 | コマンドパレット（Ctrl+K・ID・ファイル・本文検索・ビュー切替・スペース切替・保存） | `Main.kt`（`CommandPalette`）, `app/AppState.kt`（`searchContent`） | 手動 | [ui](architecture/ui.md) |
| X-05 | ビュー定義 `_views`（`kind: grid` の行・列・並び〔出現順か `order.path` の値の順〕・詳細文書・新規行・バックリンク列・`kind: document`） | `core/Views.kt`, `workspace-core/workspace/ViewDefinitions.kt` | 単体 `ViewsTest` | [views](architecture/views.md) |
| X-06 | ビューグリッド（セルの直接編集・要約とジャンプ・enum と参照の併記・詳細文書の作成・新規行・バックリンク列・カバレッジ表示・絞り込み・列の桁区切り `display: grouped` と表示の形 `format`） | `ui/ViewGridPane.kt`, `AppState.createViewDetail` / `addViewRow` | 単体 `ViewsTest` | [views](architecture/views.md) |
| X-07 | 文書ビュー（`kind: document`・`sections` の fields と backlinks・`attachments`・マッチしない文書は既定フォーム） | `core/Views.kt`（`resolveDocument`）, `ui/FormView.kt`（`DocViewSections`） | 単体 `ViewsTest` | [views](architecture/views.md) |
| X-08 | ビュー定義のスキーマ参照とリント（`definition:` 列の展開・スキーマとの整合を検査し定義エラーとして表示） | `workspace-core/workspace/ViewDefinitions.kt`（`lint`） | 単体 `ViewsTest`, `ViewDefinitionsTest` | [views](architecture/views.md) |
| X-10 | クロス集計ビュー（`kind: matrix`・行の論理ID × 列の参照先か値・セルは行から集めた要素の集計・未分類と合計・列を広げたグリッドへ下ろす）と、グリッドの要素ごとに列を広げる列 `each`（`$名前` で列の要素の ID を指す） | `semantic-core/semantic/VirtualViews.kt`（`lower`）, `workspace-core/workspace/ViewDefinitions.kt` | 単体 `ViewsTest` | [views](architecture/views.md) |
| X-09 | 値の式の列（`value:`＝ひな型の展開・`via`・配列を受け取る集計・集合から配列へ `all`・要素ごとの式 `each`・来歴つきで未保存編集に追随） | `semantic/VirtualViews.kt`（`valueCell`）, `semantic/Values.kt` | 単体 `ViewsTest`, `SemanticViaTest`, `SemanticAggregateTest`, `SemanticAllTest`, `AllFromCheckTest` | [transform](architecture/transform.md) |
| X-10 | クエリ行（`rows: match:` の束縛行・結合先ノードの値をセル化・行の同一性は束縛値の組） | `core/Views.kt`（`rowsQuery`）, `index/Query.kt` | 単体 `ViewsTest` | [views](architecture/views.md) |

## 7. AI による作成支援

| ID | 機能 | 主な実装 | テスト | 設計 |
|---|---|---|---|---|
| A-01 | フィールド単位の生成（✨） | `ui/AiGenerationDialog.kt`, `ui/FormView.kt` | 手動 | [agents](architecture/agents.md) |
| A-02 | 配列への候補追加と全体置き換え | `ui/AiGenerationDialog.kt`（`combineProposal`） | 単体 `AiProposalTest` | [agents](architecture/agents.md) |
| A-03 | 文書全体の生成（`_document` は保持） | `ui/AiGenerationDialog.kt`（`replaceInClone`） | 単体 `AiProposalTest` | [agents](architecture/agents.md) |
| A-04 | JSON Schema による Structured Outputs（`map` / `any` を含むとプロンプト指示へ後退） | `ai/AiGenerator.kt`（`fieldToJsonSchema`） | 単体 `OpenAiGeneratorTest`, `MetaSchemaTest` | [agents](architecture/agents.md) |
| A-05 | 参照候補の enum 制約（200 件超は省略） | `ai/AiGenerator.kt`（`refCandidateIds`） | 単体 `AiGeneratorTest` | [agents](architecture/agents.md) |
| A-06 | 適用前の差分プレビューと適用後検証・Undo できる適用 | `ui/AiGenerationDialog.kt` | 手動 | [agents](architecture/agents.md) |
| A-07 | プロバイダ切替（OpenAI Responses・Anthropic Messages・Azure）・API キーはメモリのみ | `ai/OpenAiGenerator.kt`, `ai/AnthropicGenerator.kt` | 単体（レスポンス解析） | [agents](architecture/agents.md) |
| A-08 | 生成のキャンセルとエラー処理 | `ui/AiGenerationDialog.kt`, `ai/*Generator.kt` | 単体（一部） | [agents](architecture/agents.md) |
| A-09 | OpenAI 互換の接続先とプロキシ（Azure・ベース URL 上書き・Basic 認証） | `ai/Connection.kt`, `ai/Http.kt` | 単体 `ConnectionTest` | [agents](architecture/agents.md) |
| A-10 | 生成のカーネル統合（単発生成を 1 ターンで提出する特殊アクティビティとして実行） | `agent/Generation.kt` | 単体 `GenerationRunTest` | [agents](architecture/agents.md) |
| A-11 | 同一スキーマの参考文書を添付（最大 3 件を自動選択・上限つき） | `ai/AiGenerator.kt`（`ReferenceDoc`）, `app/AppState.kt`（`sameSchemaFiles`） | 単体 `AiGeneratorTest` | [agents](architecture/agents.md) |
| A-12 | 生成の入出力記録（システム指示・プロンプト・応答スキーマ・生出力を実行記録へ） | `ai/AiGenerator.kt`（`AiExchange`）, `agent/RunLog.kt` | 単体 `GenerationRunTest` | [agents](architecture/agents.md) |
| A-13 | レート制限と一時エラーの自動リトライ（408 / 429 / 5xx・`retry-after` と指数バックオフ） | `ai/Http.kt`（`retryLoop`） | 単体 `HttpTest` | [agents](architecture/agents.md) |
| A-14 | Anthropic prompt caching（system・ツール定義・最新メッセージの 3 点） | `ai/ToolChat.kt`（`AnthropicToolSession`） | 単体 `ToolChatParseTest` | [agents](architecture/agents.md) |
| A-15 | 入口のスペース非依存化（`AiRequest` が対象文書を持ち・ホストはシェル直下） | `app/AppState.kt`（`AiRequest` / `requestAi`）, `ui/AiGenerationDialog.kt` | 手動 | [agents](architecture/agents.md) |
| A-16 | エンティティ文脈と出現単位の生成（関連文書の注入・所有キー限定の生成・キー単位マージ） | `ai/AiGenerator.kt`（`contextDocs`）, `ui/AiGenerationDialog.kt`（`mergeGenField`） | 単体 `AiGeneratorTest`, `AiProposalTest` | [agents](architecture/agents.md) |
| A-17 | 新規作成のたたき台生成（作成 → 開く → 生成の連結・ルート object のキー単位マージ） | `ui/AiGenerationDialog.kt`（`requestDocumentDraft`） | 単体 `GenerationRunTest` | [agents](architecture/agents.md) |

## 8. エージェント実行（アクティビティ）

| ID | 機能 | 主な実装 | テスト | 設計 |
|---|---|---|---|---|
| AG-01 | エージェント定義 `_agents`（persona・観点・モデル設定・ツール許可） | `agent/Definitions.kt` | 単体 `DefinitionsTest` | [agents](architecture/agents.md) |
| AG-02 | アクティビティ定義 `_activities`（対象・指示・文脈・書込許可・上限・提出ゲート `error-free` / `no-new-errors` / `none`） | `agent/Definitions.kt`, `agent/Staging.kt`（`newErrors`） | 単体 `DefinitionsTest`, `KernelTest` | [agents](architecture/agents.md) |
| AG-03 | ツール使用対応 LLM クライアント（OpenAI・Anthropic の function calling） | `ai/ToolChat.kt` | 単体 `ToolChatParseTest` | [agents](architecture/agents.md) |
| AG-04 | 実行カーネル（マルチターン・上限・終了手続き・キャンセル・疑似セッション注入） | `agent/Kernel.kt` | 単体 `KernelTest` | [agents](architecture/agents.md) |
| AG-05 | ツールセット（探索 5・編集 2・検証 1・対話 2・管理 3・書込権限はツール側で強制） | `agent/Tools.kt` | 単体 `KernelTest` | [agents](architecture/agents.md) |
| AG-06 | ステージング（複数文書のオーバーレイ・横断検証・未提出ドラフトの保全） | `agent/Staging.kt` | 単体 `StagingTest` | [agents](architecture/agents.md) |
| AG-07 | 変更セットレビュー UI（ファイル単位の部分採用・再検証・差分・適用・状態は `RunController`） | `ui/RunsSpace.kt`（`ReviewContent`, `RunController`） | 手動 | [agents](architecture/agents.md) |
| AG-08 | 実行中の対話（`ask_human` の質問カード・計画とメモの進捗・キャンセル） | `agent/Kernel.kt`, `ui/RunsSpace.kt`（`QuestionCard`） | 単体 `KernelTest` | [agents](architecture/agents.md) |
| AG-09 | 実行記録 `_runs`（ターン・ツール呼び出し・採否・トークン・会話記録） | `agent/RunLog.kt`, `agent/Kernel.kt` | 単体 `KernelTest` | [agents](architecture/agents.md) |
| AG-10 | CLI からの実行（`agent` / `activities`） | `core/Cli.kt` | 手動 | [agents](architecture/agents.md) |
| AG-11 | トリガ（manual / on-change / schedule / on-invalid・予約ディレクトリと自己変更では発火しない） | `agent/Automation.kt`（`tick`） | 単体 `AutomationTest` | [agents](architecture/agents.md) |
| AG-12 | アクティビティ連鎖（`chain` の成功・失敗・規則による分岐・深さ上限と循環検出） | `agent/Automation.kt`（`chainNext`）, `agent/Definitions.kt`（`ChainDef`） | 単体 `AutomationTest` | [agents](architecture/agents.md) |
| AG-13 | 無人リトライ（`retry.maxAttempts`・同一失敗の繰り返しは打ち切り） | `agent/Automation.kt`（`handleFailure`） | 単体 `AutomationTest` | [agents](architecture/agents.md) |
| AG-14 | 自動承認 `approval: auto`（条件を欠くと承認待ちへ降格・導出元文書への書込も降格・適用前バックアップ） | `agent/Automation.kt`, `agent/AutoApply.kt` | 単体 `AutomationTest` | [agents](architecture/agents.md) |
| AG-15 | 実行キューと停止（直列実行・常時停止・承認待ちの耐久性） | `agent/Automation.kt` | 単体 `AutomationTest` | [agents](architecture/agents.md) |
| AG-16 | 自動適用の巻き戻し（バックアップから復元・競合は拒否し `--force` で上書き） | `agent/AutoApply.kt`（`revert`） | 単体 `AutomationTest` | [agents](architecture/agents.md) |
| AG-17 | サブエージェント委譲（`delegate`・読み取り専用の相談・深さ 1） | `agent/Kernel.kt`（`consult`） | 単体 `KernelTest` | [agents](architecture/agents.md) |
| AG-18 | 自動実行 UI（自動化タブ: 監視・キュー・履歴・ログ）と CLI `watch` | `ui/AutomationPanel.kt`, `core/Cli.kt` | 手動 | [agents](architecture/agents.md) |
| AG-19 | 実行メトリクス（提出率・採用率・却下ファイル率・トークン・CLI `metrics`） | `agent/Metrics.kt` | 単体 `MetricsTest` | [agents](architecture/agents.md) |
| AG-20 | 評価セット `_evals`（ゴールデン入力と期待結果・サンドボックスコピー上で実行・CLI `eval`） | `agent/Evals.kt` | 単体 `EvalTest` | [agents](architecture/agents.md) |
| AG-21 | 比較実験（`variants` でモデル・エージェント・指示を差し替えた A/B） | `agent/Evals.kt`（`EvalRunner`） | 単体 `EvalTest` | [agents](architecture/agents.md) |
| AG-22 | メタアクティビティ（`meta: true` で定義文書への提案・承認は human 固定） | `agent/Definitions.kt`, `agent/Tools.kt` | 単体 `MetaActivityTest` | [agents](architecture/agents.md) |
| AG-23 | 自己改善 UI（計測・評価タブ） | `ui/ImprovementPanel.kt` | 手動 | [agents](architecture/agents.md) |
| AG-24 | ビューの開示（`read_view`・行 × 列を原本座標つきで返し `apply_patch` の対象にできる） | `agent/Tools.kt`（`viewText`） | 単体 `ViewsTest` | [agents](architecture/agents.md) |
| AG-25 | 段階的詳細化の導線（`related` が各出現の要約・フェーズ・進捗を 1 回で返す） | `agent/Tools.kt`（`relatedText`）, `core/Disclosure.kt` | 単体 `ToolsTest`, `KernelTest` | [agents](architecture/agents.md) |
| AG-26 | 一括実行 `foreach`（source: view / issues / search / files / ids / query・1 件 = 1 実行のキュー展開・GUI と CLI） | `agent/Foreach.kt`, `agent/Automation.kt`（`enqueueForeach`） | 単体 `ForeachTest` | [agents](architecture/agents.md) |
| AG-27 | コンテキスト会計（初期文脈の区分別トークン・送信別実測・キャッシュ読取の正規化） | `agent/Kernel.kt`（`initialContextSections`）, `agent/RunLog.kt` | 単体 `KernelTest`, `MetricsTest` | [agents](architecture/agents.md) |
| AG-28 | コンテキストレシピ（`context.items`: catalog / phase / view / doc / related / sample・`when`・`budgetChars`・既定は空） | `agent/Definitions.kt`（`ContextItemDef`）, `agent/Kernel.kt` | 単体 `DefinitionsTest`, `KernelTest` | [agents](architecture/agents.md) |
| AG-29 | 参照の名前解決とツール減量（参照の解決フッタ・タイトル注記・`limit` / `rows` / `columns` / `match` 引数） | `agent/Tools.kt`, `core/Views.kt`（`refAnnotatedCellText`） | 単体 `ToolsTest` | [agents](architecture/agents.md) |
| AG-30 | グラフトラバース（`start` と `steps` の経路パターン・レシピ `kind: graph`・ツール `traverse`・CLI） | `core/Traverse.kt`, `agent/Tools.kt`（`traverseText`） | 単体 `TraverseTest` | [agents](architecture/agents.md) |
| AG-31 | 評価セットの文脈レシピ A/B（`variants.context`・変異別のトークン） | `agent/Evals.kt`（`EvalVariant.contextItems`） | 単体 `EvalTest` | [agents](architecture/agents.md) |
| AG-32 | エンティティ全体像の開示（`read_entity`・合成と両方向の関係と充足を 1 回で返す・レシピ `kind: entity`） | `agent/Tools.kt`（`entityText`）, `core/Aspects.kt` | 単体 `ToolsTest`, `KernelTest` | [agents](architecture/agents.md) |

## 9. コア API・CLI（エージェント基盤）

| ID | 機能 | 主な実装 | テスト | 設計 |
|---|---|---|---|---|
| C-01 | ヘッドレスワークスペース API（開く・読む・保存・検証・相対パスの脱出防止） | `core/Workspace.kt` | 単体 `WorkspaceCliTest` | [workspace](architecture/workspace.md) |
| C-02 | YPath パッチ（set / insert / delete / move / rename / reorder）と YPath のパース | `yaml-core/patch/Patch.kt`, `yaml-core/model/YNode.kt`（`parseYPath`） | 単体 `PatchTest`, `YPathTest` | [yaml-model](architecture/yaml-model.md) |
| C-03 | 変更セット（複数ファイルのパッチ束と新規作成・base ハッシュの競合検出・適用後の姿の検証・`--require-valid`・適用の本体はカーネル） | `workspace-core/changeset/ChangeSets.kt`, `workspace-core/workspace/ChangeSetApplier.kt` | 単体 `ChangeSetApplierTest`, `WorkspaceCliTest` | [workspace](architecture/workspace.md) |
| C-04 | 段階的開示 API（カタログ → 骨子 → ノード全文・文字数予算つきバンドル） | `core/Disclosure.kt` | 単体 `WorkspaceCliTest` | [agents](architecture/agents.md) |
| C-05 | 構造検索 API（本文とキーの部分一致とスキーマ・glob 条件・参照検索・未解決参照） | `core/Search.kt` | 単体 `WorkspaceCliTest` | [index-references](architecture/index-references.md) |
| C-06 | root の CLI（探索・検索・クエリ・ビュー・検証・適用・エンティティ・エージェント・自動化・評価・skill・初期化・推測・移行・検査一覧・準備完了・計測・変換） | `core/Cli.kt` | 単体 `WorkspaceCliTest` | [workspace](architecture/workspace.md) |
| C-07 | ビュー解決 API（定義のロード・行 × セルの原本座標化・バックリンク列・文書ビュー・行の並びと集計の種類は項目の型から・CLI `view`） | `core/Views.kt` | 単体 `ViewsTest`, `VirtualViewsTest` | [views](architecture/views.md) |
| C-08 | 性能ベンチマーク（カーネルの全再構築・増分更新の計測・CLI `workspace bench`） | `cli/Commands.kt` | 単体 `CliTest`, `IdIndexIncrementalTest` | [workspace](architecture/workspace.md) |
| C-09 | 文書キャッシュの中核（`EditBuffer`・`WorkspaceRecords`・`noteDiskChanges` の振り分け） | `core/Documents.kt`, `core/Workspace.kt` | 単体 `DocumentsTest` | [workspace](architecture/workspace.md) |
| C-10 | エンティティ操作（rename / merge / delete を 1 つの計画に・1 トランザクションで Undo 1 回・CLI とパレットとエンティティページ） | `semantic-core/semantic/Refactor.kt`, `workspace-core/workspace/WorkspaceSession.kt`（`planRefactoring`） | 単体 `RefactoringTest`, `AppStateRefactorTest` | [graph-query](architecture/graph-query.md) |
| C-11 | ファイル移動・改名の一級化（文書参照の書き換えと物理移動を 1 計画に・影響プレビュー） | `semantic-core/semantic/Refactor.kt`, `workspace-core/workspace/RefactoringImpact.kt` | 単体 `DocRefTest`, `RefactoringTest` | [workspace](architecture/workspace.md) |
| C-12 | パターン照合エンジン（クエリコア・`match` / `also` / `where` / `limit` / `same`・束縛の表・決定的） | `semantic-core/schema/Query.kt`, `semantic-core/semantic/Query.kt` | 単体 `QueryTest`, `TraverseTest` | [graph-query](architecture/graph-query.md) |
| C-13 | エンティティ合成（全出現を決定的に並べ所有フィールドを原本座標つきでマージ・CLI `entity show`） | `core/Aspects.kt` | 単体 `AspectTest` | [graph-query](architecture/graph-query.md) |
| C-14 | スキーマ・リファクタリング（12 操作・スキーマと文書とビューを 1 つの計画で追随・カーネルが計画を作る） | `semantic-core/semantic/SchemaRefactor.kt`, `semantic-core/semantic/Rewrite.kt` | 単体 `RefactoringTest`, `RefactoringPlanGoldenTest` | [schema](architecture/schema.md) |
| C-15 | YAML から YAML への変換（選ぶ × 組み立てる × 規則を子に当てる・値をそろえる `$date` `$number` `$trim`・出どころつき・保存しない・CLI `transform`） | `semantic-core/schema/Transform.kt`, `semantic-core/semantic/Transform.kt` | 単体 `SemanticTransformTest`, `StructuralDerivationTransformTest` | [transform](architecture/transform.md) |
| C-16 | バージョン表示（外部 `appVersion`・内部 `yyyymmdd.ビルド番号`・コミット・CLI `version` とスタート画面） | `app/BuildInfo.kt`, `build.gradle.kts` | 単体 `BuildInfoTest` | [workspace](architecture/workspace.md) |

## 10. skill・テンプレート（フォーマット適合）

| ID | 機能 | 主な実装 | テスト | 設計 |
|---|---|---|---|---|
| SK-01 | skill 定義 `_skills`（import / export の宣言型と run） | `skill/Skills.kt` | 単体 `ImportExportTest`, `RunSkillTest` | [agents](architecture/agents.md) |
| SK-02 | インポート（xlsx / csv / json / markdown / docx → 変更セット。`mapping`・`build`・配列へ追記 `target.path`・採番 `target.id`・重複の `target.key`・文字コード・見出しの無い csv の `input.columns`） | `skill/Importer.kt`, `skill/Tables.kt` | 単体 `ImportExportTest`, `ArrayImportTest`, `TablesTest` | [agents](architecture/agents.md) |
| SK-03 | エクスポート（glob × 行 × 列 → xlsx / csv / markdown / json / html・`source.view`・YAML 出力は拒否） | `skill/Exporter.kt`, `skill/Tables.kt` | 単体 `ImportExportTest` | [agents](architecture/agents.md) |
| SK-04 | CLI（`skills` / `import` / `export` / `run-skill` / `init`） | `core/Cli.kt` | 単体 `RunSkillTest` | [agents](architecture/agents.md) |
| SK-05 | テンプレートパック（`init --template ledger`・既存ファイルは上書きしない） | `skill/TemplatePacks.kt` | 単体 `ImportExportTest` | [agents](architecture/agents.md) |
| SK-06 | skill UI（設計スペースの連携: 一覧・エクスポート・インポートのプレビューと適用・実行の承認） | `ui/SkillsPanel.kt` | 手動 | [agents](architecture/agents.md) |
| SK-07 | コード実行 skill（kind: run・人間の事前承認・定義変更で失効・エージェントからは起動不可） | `skill/Runner.kt` | 単体 `RunSkillTest` | [agents](architecture/agents.md) |
