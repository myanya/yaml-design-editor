# 機能一覧と実装状況

本書はプロジェクトの全機能を管理する台帳です。機能の追加・変更・削除を行ったら**必ず本書を更新**してください。

- 利用方法・仕様の詳細は [README.md](../README.md)、実装構造は [ARCHITECTURE.md](ARCHITECTURE.md) を参照（本書には重複記載しない）。
- 中長期の将来構想・新機能計画は [ROADMAP.md](ROADMAP.md) で管理する。本書の §11 は**現行実装の制約**のみを扱う。
- **状態**: ✅ 実装済み ／ 🚧 一部実装 ／ 📋 未実装（計画のみ）
- **テスト**: `単体` = src/test 配下に単体テストあり ／ `手動` = 手動確認のみ

## 1. ワークスペース・ファイル管理

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| W-01 | ワークスペース選択（起動引数／ダイアログ／起動時スタート画面。W-08） | ✅ | `Main.kt`, `AppState.openWorkspace` | 手動 |
| W-02 | ファイルツリー表示（展開／折りたたみ、未保存●表示） | ✅ | `ui/FileTreePane.kt` | 手動 |
| W-03 | ファイル／ディレクトリの作成・改名・削除（未保存保護、削除確認、新規YAMLはスキーマ初期値で生成。作成ダイアログは作成先パスを表示し Enter で確定でき、「ワークスペース直下に作成する」への切替と失敗理由のダイアログ内表示に対応。ツリーの空白クリックで選択解除＝未選択はルート直下が作成先。UX-21/22/40/41） | ✅ | `AppState.createYaml` ほか, `ui/FileTreePane.kt`, `ui/Common.kt`（`confirmOnEnter`） | 手動 |
| W-04 | 外部変更検出（WatchServiceによるOSネイティブ監視＋使えない環境では1秒ポーリングへフォールバック。未編集は自動再読込、未保存は競合バナーで選択。ROADMAP R8-03） | ✅ | `core/FileWatcher.kt`, `AppState.pollExternalChanges`, `Main.kt` | 単体（`FileWatcherTest`）＋手動 |
| W-05 | 最近開いたファイル履歴（12件、ワークスペース単位で Preferences に永続化） | ✅ | `AppState.recentFiles` | 手動 |
| W-06 | UIレイアウト永続化（左右ペイン幅・開閉、テーマ選択） | ✅ | `Main.kt`（`uiPreferences`） | 手動 |
| W-07 | 未保存確認（アプリ終了時・ワークスペース切替時） | ✅ | `Main.kt` | 手動 |
| W-08 | ワークスペース管理（最近開いたワークスペース履歴＝アプリグローバルの Preferences・起動時スタート画面＝履歴選択・コマンドパレットからの切替。未保存確認は W-07 を共用。ROADMAP R10-05） | ✅ | `Main.kt`（`StartScreen`, `CommandPalette`）, `AppState.recentWorkspaces` | 手動 |
| W-09 | UIシェル（アクティビティレールで編集／台帳／実行／設計の4スペースを切替。文書ビュー切替は台帳スペースのタブへ移動、モーダル4種（エージェント・自動化・自己改善・skill）は実行／設計スペースへ常設化。実行スペースは定義が空でも常時表示し、定義ゼロのときは空状態の案内が導線になる（UX-20 で R12-05 のプロファイル判定を撤廃）。ROADMAP R12-01/04/05） | ✅ | `app/AppState.kt`（`UiSpace`, `showSpace`）, `ui/Shell.kt`（`ActivityRail`, `LedgerSpace`）, `Main.kt` | 単体（`NavigationHistoryTest` のスペース同期）＋手動 |
| W-10 | 受信箱（承認待ち変更セット・エージェントの質問（非モーダルカード）・外部変更競合を実行スペース先頭タブに集約。レール・ステータスバーにバッジ、ステータスバーに検証・実行サマリ。ROADMAP R12-02） | ✅ | `ui/RunsSpace.kt`（`InboxTab`, `QuestionCard`）, `Main.kt`（`StatusBar`） | 手動 |
| W-11 | ワークスペース構造の再編（`.yde/` 構成ディレクトリ＝定義・記録を `.yde/schemas` 等へ集約・`.yde/workspace.yaml` の `ledger:` で台帳範囲を宣言。配置判定は `WorkspaceLayout` に一元化、レガシー構成（ルートの `_schemas` 等）はゼロコンフィグ互換で従来どおり。走査は隠しディレクトリを枝刈り（`.yde` のみ例外）。CLI `migrate` でレガシー→`.yde` 移行。ROADMAP R13-01/02/04） | ✅ | `schema/WorkspaceLayout.kt`, `core/Workspace.kt`（`WorkspaceScan`）, `core/Cli.kt`（`migrate`） | 単体（`WorkspaceLayoutTest`） |
| W-12 | エディタ層／台帳層の分離（`ledger` 宣言時、台帳外のYAMLも match によるスキーマフォーム・単文書検証の対象＝エディタ層。未解決参照は情報レベルへ降格・カバレッジ判定なし。索引・カタログ・検証集計・エージェント・自動化は台帳のみ。ツリーは台帳外を薄色・既定折りたたみで表示。ROADMAP R13-03/04） | ✅ | `validate/Validator.kt`（`indexed`）, `app/AppState.kt`（`editorOnlyFiles`）, `ui/FileTreePane.kt` | 単体（`WorkspaceLayoutTest`） |

## 2. 文書編集

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| E-01 | スキーマ駆動フォーム（全13型のエディタ、必須マーク（`requiredFrom` はフェーズ名つきで区別表示）、説明表示、折りたたみ） | ✅ | `ui/FormView.kt` | 手動 |
| E-02 | 汎用ツリーエディタ（スキーマなし文書、スキーマ外キーの汎用行表示、キー名変更＝順序・値・コメントを保った改名。ROADMAP R8-05） | ✅ | `ui/FormView.kt`（`GenericEditor`, `renameMapKey`） | 単体（`RenameKeyTest`）＋手動 |
| E-03 | ソース編集（行番号、文書内検索・置換（1件／すべて）、YAML構文ハイライト、「変更を反映」、パースエラーの行・列表示、Tab キーでスペース2個を挿入（フォーカス移動しない。UX-25）。ROADMAP R8-04） | ✅ | `ui/SourcePane.kt`, `ui/SourceHighlight.kt` | 単体（`SourceHighlightTest`）＋手動 |
| E-04 | 差分表示（保存済み vs 編集中、LCSによる行整列、変更行内の文字単位強調、空行差の弱調表示。ROADMAP R8-04） | ✅ | `ui/DiffPane.kt` | 単体（`DiffPaneTest`, `SourceHighlightTest`） |
| E-05 | Undo/Redo（正規化テキストのスナップショット履歴、最大100件） | ✅ | `app/OpenDocument` | 手動 |
| E-06 | 保存／すべて保存（Ctrl+S / Ctrl+Shift+S、ソース編集中は反映→保存。コメント・キー順・表記スタイルを保持して書き出す。引用符なしの日付・日時スカラーは Date へ型付けせず文字列のまま往復する（UX-29）。リストは親プロパティから一段下げて整形。ROADMAP R8-01） | ✅ | `AppState.save*`, `yaml/YamlIO.kt`（`PlainDateResolver`）, `ui/SourcePane.kt` | 単体（`YamlIOTest`）＋手動 |
| E-07 | 文書メタデータ `_document`（id / name / description）の編集・ヘッダー表示 | ✅ | `ui/FormView.kt`（`DocumentMetadataEditor`）, `index/IdIndex.kt` | 手動 |
| E-08 | 文書キャッシュ（ファイル切替時も未保存編集をメモリ保持） | ✅ | `AppState.docCache` | 手動 |
| E-09 | 同一IDの他文書出現ストリップ（フォーム上部。文書ルートのIDが他文書にも出現する場合に相手側のスカラー情報を表示・ジャンプ。詳細文書に一覧側情報を重複記載しない運用を支える） | ✅ | `ui/FormView.kt`（`SameIdOccurrencesStrip`） | 手動 |
| E-10 | 複数ドキュメント（`---` 区切り）YAML（フォームは先頭ドキュメントを編集、後続は保持して保存時に再出力。ソースタブでは全ドキュメント編集可。索引・検索・一覧は先頭ドキュメント対象。ROADMAP R8-05） | ✅ | `yaml/YamlIO.kt`（`parseAll` / `dumpAll` / `parseFirst`）, `app/OpenDocument` | 単体（`OpenDocumentTest`, `YamlIOTest`） |
| E-11 | 畳み込みサマリ（畳んだ object は「ID タイトル（n項目）」、array は「n件: 先頭要素の要約, …」。表示解決 S-13 の最初の消費者。既定は編集・閲覧共通でルートから2階層まで展開・以深は畳み（`FormCtx.defaultExpanded`。ID横ぐしの埋め込みフォームは要素基準の相対深さ）。モード切替でも展開状態を維持。ROADMAP R10-02） | ✅ | `ui/FormView.kt`, `schema/Display.kt`（`headerText` / `seqHeaderText`） | 単体（`DisplayTest`, `DefaultExpandTest`）＋手動 |
| E-12 | 閲覧専用モード（`FormCtx.readOnly`: 入力欄→テキスト表示、追加/削除/AIボタン非表示、パディング圧縮。既定畳みは編集モードと共通（E-11）。ジャンプ・バックリンクは残す。ID横ぐしの埋め込みフォーム・ビューグリッドへ FormCtx 共有で自動波及。トグルはツールバー＋uiPreferences 永続化。ROADMAP R10-04） | ✅ | `ui/FormView.kt`（`FormCtx`）, `app/AppState.readOnlyMode`, `Main.kt` | 単体（`ReadOnlyTest`）＋手動 |
| E-13 | 配列・map のテーブル表示（スカラー中心の object 配列と「キー→object」の map をフォーム内で表形式に: 適否は自動判定＝「列2〜8 かつ 宣言フィールドの過半がセル型」、スキーマの `display: table / list` で固定可。map は先頭にキー列＝表示のみ（キー追加は表の下の共通行、削除は行操作）。セル編集は通常の編集経路（Slot）を再利用、長文（text）・ネスト・enum case 追加分・スキーマ外キーは行頭 ▶ の展開フォームで編集。列ヘッダ＝表示ラベル＋必須マーカー、配列は行の並べ替え/削除。列幅は型別の最小幅を保証しつつ余白を文字系の列へ等分して右端まで使い、収まらなければ横スクロール。閲覧専用モードはテキストセル。不適格な配列・map は従来の縦リスト＋要素単位の畳み込みサマリ（E-11）のまま。ROADMAP R12-06） | ✅ | `ui/FormView.kt`（`ObjectTable` / `ArrayTable` / `MapTable` / `tableColumnWidths`）, `schema/Display.kt`（`tableColumns`）, `schema/Schema.kt`（`CollectionDisplay`） | 単体（`DisplayTest` の適否判定・ヒント合成・メタ検証、`ArrayTableTest` の列幅割付)＋手動 |

## 3. スキーマ

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| S-01 | スキーマ読込（`_schemas/*.yaml`、`match` glob 先勝ち、id 重複検出） | ✅ | `schema/Schema.kt`（`SchemaLoader`） | 手動 |
| S-02 | 型システム13種（string/text/int/number/boolean/enum/date/id/ref/array/map/object/any。`any` は「検証しない」の明示＝必須チェックのみ・汎用エディタ・構造化出力の無効化。ROADMAP R11-02） | ✅ | `schema/Schema.kt`（`FieldType`） | 単体（`MetaSchemaTest`）＋手動 |
| S-03 | 名前付き定義と再帰参照（`definitions` / `$ref`、属性のオーバーライド合成） | ✅ | `SchemaDef.resolve` | 手動 |
| S-04 | メタスキーマ検証（必須情報、キー重複、子定義不足、enum選択肢、`cases` の整合、未解決 `$ref`） | ✅ | `SchemaDef.validateDefinition` | 単体（`SchemaLoaderTest`） |
| S-05 | スキーマ設計UI（スキーマ全体の説明、document / library切替、ローカル／横断`$ref`、設計／プレビュー／適用対象タブ、競合スキーマ表示、フィールドの `requiredFrom`・表示役割 `role` 入力、enum リッチ選択肢（値・ラベル・説明）の編集、`phases`（申告キー・順序）と `coverage`（参照／出現ベース切替・重大度・activeFrom）の構造化編集。ROADMAP R9-03・R10-01・R10-03） | ✅ | `ui/SchemaEditor.kt` | 手動 |
| S-06 | サンプルYAML生成（プレビュー・クリップボードコピー・新規ファイル初期値） | ✅ | `SchemaDef.generateSample` | 手動 |
| S-07 | スキーマ外キーの検証ポリシー（`unknownFields: allow / warning / error`、未知サブツリーは走査境界） | ✅ | `validate/Validator.kt`, `index/IdIndex.kt` | 手動 |
| S-08 | 参照の関係種別（`type: ref` の `relation`。表示・カバレッジ判定・`$ref`合成に対応） | ✅ | `schema/Schema.kt`, `index/IdIndex.kt`, `ui/SchemaEditor.kt` | 単体（`WorkspaceCliTest`） |
| S-09 | カバレッジルール定義（スキーマの `coverage`: 対象IDパターン・必要数・重大度。参照ベース `requiredBy`＝参照元スキーマ・関係の限定に加え、出現ベース `detailIn`＝指定スキーマの文書への出現（詳細文書の存在）を要求できる。ROADMAP R7-02） | ✅ | `schema/Schema.kt`（`CoverageRule`） | 単体（`WorkspaceCliTest`, `CoverageTest`） |
| S-10 | enum 値ごとの追加フィールド（`cases`: 判別共用体。case 由来enumの多段展開・フォーム追随・検証・索引・AI生成 `anyOf`・スキーマ設計UI対応） | ✅ | `schema/Schema.kt`（`effectiveFields`）, `validate/Validator.kt`, `index/IdIndex.kt`, `ui/FormView.kt`, `ui/SchemaEditor.kt`, `ai/AiGenerator.kt` | 単体（`SchemaLoaderTest` / `ValidatorTest` / `DocScannerTest` / `AiGeneratorTest`） |
| S-11 | フェーズ機構（ROADMAP P7）: `phases`（順序付きフェーズと申告キー）の宣言、フィールドの `requiredFrom`（累積必須）、カバレッジの `activeFrom`（判定保留） | ✅ | `schema/Schema.kt`（`PhasesDef`, `currentPhaseIndex`, `requiredAt`） | 単体（`PhaseTest`） |
| S-12 | スキーマ共通部品（ROADMAP R7-03）: `kind: library` がdefinitionsだけを公開し、`moduleId#definition` の修飾`$ref`で工程別スキーマから共有。二段階ロードの`SchemaCatalog`、宣言元基準の入れ子参照、未解決・重複検出 | ✅ | `schema/Schema.kt`（`SchemaCatalog`, `SchemaLoader`） | 単体＋統合（`SchemaLoaderTest`） |
| S-13 | 表示解決機構（ROADMAP R10-01）: 要素の1行要約（タイトル・サブタイトル・ID）を単一機構で解決。優先順位はスキーマの `FieldDef.role: title / subtitle`（`$ref` 合成・library 共有対応、object 内の重複はメタ検証）＞予約語（title/name/label、description/summary）＞ id ＞ キー数。消費側: 畳み込みヘッダ（E-11）・参照候補（I-04）・バックリンク（I-05）・ID横ぐし（I-07）・ビューグリッド（X-06）・ID検索（I-06）・エージェント `related`（AG-25）＝索引タイトル（`IdEntry.title`）への一元化 | ✅ | `schema/Display.kt`, `schema/Schema.kt`（`FieldRole`）, `index/IdIndex.kt`（`DocScanner`, `sourceEntryOf`） | 単体（`DisplayTest`） |
| S-14 | enum のリッチ選択肢（ROADMAP R10-03）: `values` 要素を文字列または `{value, label, description}` に拡張（後方互換・混在可）。検証・`cases` のキー・AI生成制約は `value` のまま、フォームは label 表示＋現在値の ✓ ＋説明表示、スキーマ設計UIに値・ラベル・説明の入力欄。値重複はメタ検証エラー | ✅ | `schema/Schema.kt`（`EnumOption`）, `ui/FormView.kt`（`EnumEditor`）, `ui/SchemaEditor.kt` | 単体（`DisplayTest`） |
| S-15 | 組込みメタスキーマ（ROADMAP R11-02）: `_schemas` 文書に適用される `builtin-schema`。`FieldDef` の再帰は再帰 `$ref`、「type の値ごとに持てる属性」は `cases`（S-10）で表現（ドッグフーディング）。`_schemas` には一般スキーマの match は及ばず（catch-all の誤適用防止）、同IDのスキーマで上書き可。スキーマ文書に「フォーム」タブ・AI文書生成・メタスキーマ検証（検証パネル・CLI `validate`）が使える。組込みスキーマ全件と実用形スキーマの検証エラー0を自動検証 | ✅ | `schema/BuiltinSchemas.kt`, `core/Workspace.kt`（`schemaFor`）, `Main.kt` | 単体（`MetaSchemaTest`） |
| S-16 | スキーマ推測（ROADMAP R11-01）: 既存YAML（glob指定・複数文書のマージ）から型推測（int/number/boolean/date/長文text）・参照認識（I-02 の命名規約 `RefNaming` を共有）・小さい値集合の enum 候補化・全出現キーの required 候補化を行い、`_schemas` ドラフト（コメント付き・メタスキーマ検証を通る形）を生成。入口はファイルツリーの推測ダイアログ・設計スペースの「既存文書から推測」・CLI `infer-schema`（`--apply` で保存・既存は上書きしない） | ✅ | `schema/SchemaInference.kt`, `app/AppState.inferSchema`, `ui/FileTreePane.kt`, `ui/DesignSpace.kt`, `core/Cli.kt` | 単体（`SchemaInferenceTest`） |
| S-17 | 派生スキーマ（ROADMAP R11-03）: スキーマの `derive:` 宣言（from / each / id / match / fields の写像。語彙は SK-02 の列マッピングと共通）で、テーブル定義書のような導出元文書から検証スキーマをロード時にコンパイル（パース→リンク→導出の第3段階）。導出元文書の保存・外部変更・変更セット適用は全再構築で再検証へ反映（`Workspace.noteDiskChanges`）。導出の失敗（プレースホルダ欠落・キー欠落・ID重複・未知の型）は理由付きでスキーマ読込エラーに報告。自動適用は導出元文書への書込で承認待ちへ降格（AG-14） | ✅ | `schema/DerivedSchemas.kt`, `schema/Schema.kt`（`DeriveDef`）, `core/Workspace.kt`, `agent/Automation.kt` | 単体（`DerivedSchemasTest`, `AutomationTest`） |
| S-18 | 制約語彙（ROADMAP R16-01）: `string` / `text` の `minLength` / `maxLength` / `pattern`（正規表現・全体一致）、`int` / `number` の `min` / `max`。違反は検証エラー（値が入っているときだけ検査）、`$ref` 解決で呼び出し側優先に合成、メタスキーマ検証が型適合（string系以外の minLength 等）・範囲逆転・不正な正規表現を指摘。AI生成の JSON Schema（Structured Outputs）へ minLength / maxLength / pattern / minimum / maximum として伝搬。フォームでは UX-14 の枠色（検証指摘）として自動表示 | ✅ | `schema/Schema.kt`（`FieldDef` / `parseField` / `resolve` / `validateDefinition`）, `validate/Validator.kt`（`checkField` / `checkRange`）, `ai/AiGenerator.kt`（`fieldToJsonSchema`）, `schema/BuiltinSchemas.kt` | 単体（`ConstraintTest`） |

## 4. 検証

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| V-01 | スキーマ適合検証（必須／型不一致／enum選択肢外／date形式警告／非アクティブなcaseキーの案内） | ✅ | `validate/Validator.kt` | 単体（`ValidatorTest`） |
| V-02 | 参照解決検証（未解決エラー、パターン・スコープ対象外の区別、未保存IDの考慮） | ✅ | `validate/Validator.kt` | 手動 |
| V-03 | ID重複検出（同一文書内のみエラー。文書間の同一IDは段階的詳細化として許容） | ✅ | `validate/Validator.kt` | 手動 |
| V-04 | 検証パネル（クリックで該当箇所へジャンプ、スキーマ定義エラーも合流表示、`phases` 宣言スキーマはフェーズ別記入進捗を表示） | ✅ | `ui/SidePanel.kt` | 手動 |
| V-05 | カバレッジ検証（「被参照必須」（参照ベース）と「詳細文書の存在」（出現ベース。R7-02）のトレーサビリティ充足を機械判定。索引＝保存済み状態基準） | ✅ | `validate/Validator.kt`（`coverageStatuses`） | 単体（`WorkspaceCliTest`, `CoverageTest`） |
| V-06 | フェーズ考慮の検証（ROADMAP P7）: `requiredFrom` は現在フェーズ以降のみ必須エラー化、`coverage.activeFrom` は定義文書のフェーズ到達まで判定保留（「フェーズ未到達」として区別表示）、`phaseProgress` でフェーズ別記入進捗を取得 | ✅ | `validate/Validator.kt`（`checkField`, `coverageStatuses`, `phaseProgress`） | 単体（`PhaseTest`） |
| V-07 | 整合検証（ROADMAP R16-03a/b）: スキーマの `consistency:` ルールで「ID要素の `path` の値が、導出（計算列 X-09 と同一の語彙 `expected`: lookup/source/path/values/map/default）の期待値と `assert` の関係を満たすこと」を検証。`assert` は `equals`（一致・既定）／`lte`・`gte`（**適合判定** R16-03b: 数値として以下／以上。数値でなければ判定保留。例: 画面入力の maxLength は参照する値型の maxLength 以下＝設定できない値を許す入力欄を検出）。導出の宣言（`schema/Derivation.kt`）と解決（`index/Derivation.kt`）は計算列と共有＝ビューで見えている導出値と検証される期待値が常に一致。判定は等値（適合＝包含判定は R16-03b）、`level`／`activeFrom` はカバレッジと同じ語彙、判定保留（値未記入・写像行なし・プレースホルダ未解決）は沈黙。**テンプレート健全性**（UX-12）: 整合ルールが導出元（`expected.source`）に選ぶ文書では、テンプレートの解決不能プレースホルダを書いたその場（編集中ツリー）で WARNING（座標つき）。期待値の材料は loader 注入（既定ディスク・変更セット適用とステージングはオーバーレイ）＝エージェントの提出ゲートにも自動で乗る。**指摘の選択メニューとワンクリック修正**（R16-03c / UX-13）: 整合指摘は来歴（`Issue.derivation`＝導出値・規則座標・入力値座標）を持ち、検証タブのクリックで「エラー箇所へ／導出規則へ／入力値へ／**導出値を適用**」を選択できる（適用は `Slot`＋`markEdited` の通常編集経路＝未保存・Undo可。閲覧専用モードでは非表示）。**フィールド枠の色づけ**（UX-14）: 指摘のあるフィールドはフォームの入力枠が色づく（エラー=赤・警告=オレンジ。`FormCtx.issues` 経由で全指摘種別に効く）。**出現先への不足案内**（UX-15）: カバレッジ（`detailIn`）の出現先スキーマの文書に「不足しているID一覧」を INFO で案内（向きの反転。未保存で行を書くとその場で消える。クリックで各IDの定義へジャンプ＝`Issue.relatedIds`）。枠色はフォームのテーブル表示（`ObjectTableCell`）のセルにも適用。**明示的除外の規約**（UX-16）: あえて実装しない項目は `excluded: true` の行（同一ID出現）として残す＝カバレッジ充足・整合は保留・理由はデータとして記録（README 整合ルール節） | ✅ | `schema/Derivation.kt`（`ConsistencyRule`）, `index/Derivation.kt`, `validate/Validator.kt`（`IssueDerivation`）, `ui/SidePanel.kt`（`IssueRow`）, `ui/FormView.kt`（`FormCtx.issues`）, `schema/BuiltinSchemas.kt`（メタスキーマ `consistency`） | 単体（`ConsistencyTest`）＋手動（UI） |

## 5. ID・参照（リンク）

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| I-01 | ID索引（ディスク基準。`type: id` ＋ スキーマなし文書のキー名 `id` を索引化、documentId 管理。保存・外部変更・変更セット適用では変更ファイルだけを差し替える増分更新＝コストが変更ファイル数に比例。ROADMAP R8-02） | ✅ | `index/IdIndex.kt`（`DocScanner`, `update`） | 単体（`IdIndexIncrementalTest`）＋手動 |
| I-02 | 参照認識（`type: ref` ＋ 命名規約ヒューリスティクス `ref`/`*_ref`/`refs`/`depends_on` 等） | ✅ | `index/IdIndex.kt`（`DocScanner`） | 手動 |
| I-03 | リンクジャンプ（→ボタン、ハイライト＋自動スクロール、複数出現時は選択ダイアログ） | ✅ | `AppState.navigateToId`, `ui/Common.kt`（`Highlightable`） | 手動 |
| I-04 | 参照候補の補完（`idPatterns` / `scope.schemaIds` / `scope.documentIds` で絞り込み。選択後は解決先タイトルを入力欄の枠内右端に併記＝IDと表示名を1つの領域で見せる。UX-31） | ✅ | `ui/FormView.kt`（`RefEditor`）, `ui/Common.kt`（`CompactTextField` の suffix）, `index/IdIndex.kt` | 手動 |
| I-05 | バックリンク（被参照一覧。参照を書いた要素のID・タイトル＝表示解決 S-13 の結果を主表示、クリックで参照元へジャンプ） | ✅ | `IdIndex.backlinksTo` / `sourceEntryOf`, `ui/SidePanel.kt` | 手動 |
| I-06 | ID検索（コマンドパレット（Ctrl+K）に統合。ID・タイトル部分一致。タイトルは表示解決 S-13 で索引化されたもの。ROADMAP R12-04） | ✅ | `Main.kt`（`CommandPalette`） | 手動 |
| I-07 | ID横ぐしビュー（同一論理IDの全出現・要素からの参照・被参照・カバレッジ充足を1画面に集約。フェーズ未到達のカバレッジルールは区別表示。各出現は`_views`定義なしでその場編集できる埋め込みフォーム＝スキーマがあれば`ObjectFields`、なければ`GenericEditor`。台帳スペースのタブとインスペクタ（I-09）から開く。ヘッダの「すべて保存」（未保存件数つき）で複数文書の編集をその場で保存できる（UX-34）。ROADMAP R7-04） | ✅ | `ui/IdCrossView.kt`, `AppState.openCrossView` / `ensureDoc` / `dirtyDocCount` | 手動 |
| I-08 | ナビゲーション履歴（参照ジャンプ・ファイル切替・**スペース切替（レール）・台帳タブ切替**・横ぐしビューのID切替・ビューグリッドのビュー切替・検証パネルの文書内ジャンプを「戻る」「進む」で往復。ツールバーの←→ボタン＋`Alt+←`/`Alt+→`。編集スペースはファイルとハイライト位置、台帳は下位ビューと選択ID、実行・設計はスペース自体を復元。同じ場所の再選択は積まない。削除済みファイルは読み飛ばし、ワークスペース切替でクリア。R12-01） | ✅ | `AppState.goBack` / `goForward` / `navigateToPath` / `showSpace`, `Main.kt`（ツールバー・ショートカット） | 単体（`NavigationHistoryTest`） |
| I-09 | IDインスペクタ（右パネルを「検証／関係＝参照・被参照／出現」の3タブへ再編。出現タブは文書ルートIDの同一論理IDの全出現・カバレッジ充足を表示し、ID横ぐしビュー（I-07）へ展開できる。ROADMAP R12-03） | ✅ | `ui/SidePanel.kt` | 手動 |

## 6. ワークスペース横断ビュー

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| X-01 | 一覧ビュー（ID・タイトル・状態・担当・種別、文字列／種別／状態で絞り込み） | ✅ | `ui/WorkspaceViews.kt`, `AppState.workspaceRecords` | 手動 |
| X-02 | 一括編集（ルート直下の任意スカラーキー。候補はワークスペースから収集、`id`・予約キー・複合値は対象外。確認ダイアログ、未保存文書があれば拒否。ROADMAP R8-05） | ✅ | `AppState.bulkUpdate` / `bulkKeyCandidates` | 手動 |
| X-03 | 参照グラフ（力学配置による全件描画、パン・ズーム、IDノード／一覧クリックで定義へジャンプ、参照元ファイルと参照先IDの色分け。ROADMAP R8-05） | ✅ | `ui/WorkspaceViews.kt`, `ui/GraphLayout.kt` | 単体（`GraphLayoutTest`）＋手動 |
| X-04 | コマンドパレット（Ctrl+K。統合検索: ID・タイトル移動（I-06）・ファイル検索・台帳ビュー切替・実行／設計スペース切替・保存・ワークスペース切替。ROADMAP R12-04） | ✅ | `Main.kt`（`CommandPalette`） | 手動 |
| X-05 | ビュー定義 `_views`（宣言的な射影。`kind: grid`（省略時既定）: 行=論理IDパターン × 列=出現からの相対YPath、並び順・詳細文書・新規行（配列モード=`file`+`path` で一覧文書の配列へ追加／ファイルモード=`filePattern` で詳細文書そのものを新規作成＝一覧文書を持たないレイアウト向け。R15-01）・バックリンク列（`backlinks`: 行IDへの被参照を relation／参照元スキーマで絞って一覧表示。1:N詳細化の分裂先の逆引き。ROADMAP R7-02）の宣言。`kind: document`: 文書ビュー（X-07）。組込みスキーマでフォーム編集・検証、メタアクティビティの提案対象） | ✅ | `core/Views.kt`, `schema/BuiltinSchemas.kt` | 単体（`ViewsTest`） |
| X-06 | ビューグリッド（ビューを編集可能な一覧として表示。スカラーセルの直接編集・複合値は表示解決 S-13 による要約表示＋ジャンプ・詳細文書の作成・新規行の追加（ファイルモードはID入力ダイアログ→詳細文書のスキャフォールドをディスクへ書込＝行が即座に現れる。R15-01）・バックリンク列＝参照元のID・タイトルの列挙とジャンプ（編集不可の派生表示）・カバレッジ表示（フェーズ未到達のルールは対象外）・絞り込み。閲覧専用モード（E-12）では全セルが表示専用） | ✅ | `ui/ViewGridPane.kt`, `AppState.ensureDoc` / `createViewDetail` / `addViewRow` / `addViewRowFile` | 単体（core経路は `ViewsTest`）＋手動 |
| X-07 | 文書ビュー（`kind: document`。ROADMAP R14-01: `match`／`schemaId` で適用先文書を決め、フォーム表示を `sections`＝fields（スキーマ駆動フォームの射影。`path` で部分表示、`include`＝列挙順の選別／`exclude`＝除外で表示キーを合成。R14-03）＋backlinks（文書内IDへの被参照表: `idPattern`・`relation`・`schemaIds` で絞り、`columns` で参照元要素の値を列表示）の並びとして合成する。`attachments`＝要素単位アタッチ（R14-03: `at` パターン＝YPath＋`[*]` に一致する要素の展開フォーム内に、その要素のIDへの被参照表を差し込む。ルート・ネスト・配列要素・テーブル行展開の全描画経路に効く）。マッチしない文書は従来のスキーマ駆動フォーム＝暗黙の既定ビュー。被参照表は読み取り専用＋参照元へのジャンプ。台帳の文書のみ対象。`read_view`（`file` 引数）・CLI `view <id> [file]` でも解決可） | ✅ | `core/Views.kt`（`DocViewDef` / `resolveDocument` / `resolveAttachments` / `renderDocText`）, `ui/FormView.kt`（`DocViewSections` / `AttachedBacklinks`）, `schema/BuiltinSchemas.kt` | 単体（`ViewsTest`）＋手動（フォーム描画） |
| X-08 | ビュー定義の一級スキーマ参照とリント（ROADMAP R14-02: グリッド列の `definition:`＝named definition（`module#name`。非修飾名は `schemaId` から補完）のセル型フィールドを列集合へ展開し、ラベル・パスをスキーマから導出（ID型は行キーと重複するため除外）。文書ビューの `fields.definition`＝参照宣言（存在はパース＝ステージング提出ゲート込み、実使用はリント）。リントはスキーマを渡した `Views.load` で有効になり、ビュー→スキーマの参照（schemaId・パス＝ID要素からの静的解決・definition・`newRow.filePattern` の作成先＝マッチするスキーマの有無と detail 認定条件との整合）の整合を検査。`fileGlob` だけで出現を選ぶ列も、glob の代表パスにマッチするスキーマで検査される（マッチするスキーマが無ければ判定しない＝誤検知回避。UX-36）。指摘はビューを無効化せず、定義エラーとしてグリッドのバナー・`read_view`・CLI `view` に表示。`_views` の定義文書を開いている間は検証パネル（右ペイン）にも表示され、編集中ツリーで判定するため未保存でもその場で追随する＝`Views.lintDocument`（UX-43）） | ✅ | `core/Views.kt`（`expandDefinitionColumns` / `lint` / `lintDocument`）, `schema/Schema.kt`（`definitionRefOf`）, `ui/SidePanel.kt` | 単体（`ViewsTest`） |
| X-09 | 計算列（ROADMAP R16-02: グリッド列の `computed:`＝対象ID（`lookup` で行の出現の ref を1ホップ辿った先、省略時は行のID自身）の `source` 出現から元値（テンプレート）を読み、`{パス}` プレースホルダを「source 要素 → `values` で選んだ出現 → lookup 起点」のスコープ連鎖で参照時に評価して表示する読み取り専用の派生列。`map`＝評価後の値辞書・`default`＝元値なし時の既定値（語彙は derive／skill 列マッピングと共通）。評価は原本の値のみ参照する1段評価（計算値の上の計算はしない）。セルの座標は元値（導出規則）の位置＝ジャンプ先。`editable`・`path`・`backlinks` とは排他（パースで拒否）、参照スキーマ・パスはリント対象。グリッドUI・`read_view`（「計算値」の印つき）・CLI `view`・エクスポート `source.view` へ自動波及。評価器 `schema/Projection` は将来の整合検証と共有（ARCHITECTURE §4.26）。**来歴と編集追随**（R16-04）: セルは来歴（`ViewCell.trace`＝元値テンプレート＋プレースホルダごとの解決値と入力値の座標）を持ち、UIのクリックは導出の説明と行き先の選択（導出規則・各入力値）のポップアップを提示（未解決プレースホルダはその旨を説明）。`read_view` の導出行・CLI `view` の `derivation` 節で人間とAIが同じ来歴を見る。グリッドは `AppState.editVersion`（全文書横断の編集カウンタ。`OpenDocument.onEdited` 通知）を購読し、**他文書の未保存編集にも評価値が追随**する（「未保存で確認→OKなら保存」。行集合はディスク索引基準のまま） | ✅ | `schema/Projection.kt`, `core/Views.kt`（`ViewComputedDef` / `computedCell` / `ComputedTrace`）, `ui/ViewGridPane.kt`（`ComputedCellLink`）, `app/AppState.kt`（`editVersion`）, `schema/BuiltinSchemas.kt` | 単体（`ViewsTest`） |

## 7. AIによる作成支援

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| A-01 | フィールド単位の生成（ラベル横 ✨ アイコン） | ✅ | `ui/AiGenerationDialog.kt`, `ui/FormView.kt` | 手動 |
| A-02 | 配列への候補追加／全体置き換えの選択 | ✅ | `ui/AiGenerationDialog.kt`（`combineProposal`） | 単体（`AiProposalTest`） |
| A-03 | 文書全体の生成（`_document` メタデータは保持） | ✅ | `ui/AiGenerationDialog.kt`（`replaceInClone`） | 単体（`AiProposalTest`） |
| A-04 | スキーマ→JSON Schema 変換による Structured Outputs（`map` / `any` 型を含む場合はプロンプト指示にフォールバック） | ✅ | `ai/AiGenerator.kt`（`fieldToJsonSchema`, `containsMap`） | 単体（`OpenAiGeneratorTest`, `MetaSchemaTest`） |
| A-05 | REF候補の enum 制約（`idPatterns`/`scope` で絞り込み、200件超は省略） | ✅ | `ai/AiGenerator.kt`（`refCandidateIds`） | 単体（`AiGeneratorTest`） |
| A-06 | 適用前の差分プレビューと適用後検証（エラー・警告数）、Undo可能な適用 | ✅ | `ui/AiGenerationDialog.kt` | 手動 |
| A-07 | プロバイダ切替（OpenAI Responses API / Anthropic Messages API / Azure OpenAI＝OpenAI互換プリセット）、モデル・エンドポイント設定の永続化、APIキーはメモリのみ | ✅ | `ai/OpenAiGenerator.kt`, `ai/AnthropicGenerator.kt`, `ui/AiSettings.kt` | 単体（レスポンス解析） |
| A-08 | 生成のキャンセル、拒否・トークン上限のエラーハンドリング | ✅ | `ui/AiGenerationDialog.kt`, `ai/*Generator.kt` | 単体（一部） |
| A-09 | OpenAI互換エンドポイント機構（Azure＝`api-key`ヘッダ＋エンドポイント＋デプロイメント名／ベースURL上書き＝ollama・vLLM等）とプロキシ対応（システム設定尊重を既定に手動上書き・Basic認証はメモリのみ・HTTPSトンネルのBasic許可）。生成系とエージェント系の両方に共通で効く（ROADMAP R9-07・R9-08） | ✅ | `ai/Connection.kt`, `ai/Http.kt`, `ui/AiSettings.kt` | 単体（`ConnectionTest`） |
| A-10 | 生成実行のカーネル統合（単発生成を「1ターンで提出する特殊アクティビティ」としてエージェントカーネル上で実行。Structured Outputs の型保証を維持したまま、ステージング・権限強制・実行記録 `_runs` をアクティビティ実行と単一系統化。ROADMAP R9-01） | ✅ | `agent/Generation.kt`, `ui/AiGenerationDialog.kt` | 単体（`GenerationRunTest`） |
| A-11 | 同一スキーマの参考文書をプロンプトに添付（更新日時の新しい順に最大3件を自動選択、ダイアログのチェックボックスで選び直し可能。候補表示は最新20件、1件2万文字・合計12万文字で打切り。未保存編集を優先） | ✅ | `ai/AiGenerator.kt`（`ReferenceDoc`, `buildUserInput`）, `app/AppState.kt`（`sameSchemaFiles`）, `ui/AiGenerationDialog.kt` | 単体（`AiGeneratorTest`） |
| A-12 | 生成実行の入出力記録（送信したシステム指示・プロンプト全文・応答スキーマ・モデル生出力を実行記録の `exchange` 節へ上限つき全文記録。usage は実測トークン数。失敗時も実行記録を書き、ダイアログのエラー表示に記録パスを添える） | ✅ | `ai/AiGenerator.kt`（`AiExchange`）, `ai/OpenAiGenerator.kt`, `ai/AnthropicGenerator.kt`, `agent/Generation.kt`, `agent/RunLog.kt` | 単体（`GenerationRunTest`） |
| A-13 | レート制限・一時エラーの自動リトライ（408 / 429 / 5xx を `retry-after` ヘッダ尊重＋指数バックオフ＋ジッタで再送。上限60秒/回・3回。送信経路 `AiHttp.post` に一元化し生成系・エージェント系・評価の全経路に効く。ROADMAP R18-04） | ✅ | `ai/Http.kt`（`retryLoop`, `isRetryable`, `retryDelayMillis`） | 単体（`HttpTest`） |
| A-14 | Anthropic prompt caching（ROADMAP R19-07）: ツールループのリクエストに `cache_control` の切れ目を system・ツール定義末尾・**最新メッセージの末尾ブロック**（毎ターン前進）の3点に自動配置し、2ターン目以降の再送プレフィクス全体をキャッシュ読取化。効果は `usage.cachedInputTokens`（AG-27 の正規化）で計測。予算判定は総入力基準を維持（割引は課金の問題で、暴走防止の予算とは別） | ✅ | `ai/ToolChat.kt`（`AnthropicToolSession.buildRequestBody`, `cacheMarked`） | 単体（`ToolChatParseTest`） |

## 8. エージェント実行（アクティビティ）

マルチターンのツール使用ループによるエージェント実行（[ROADMAP.md](ROADMAP.md) P2〜P4 の成果）。利用方法は README §12〜13 を参照。

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| AG-01 | エージェント定義 `_agents`（persona・観点・モデル設定・ツール許可。組込みスキーマでフォーム編集・検証可能） | ✅ | `agent/Definitions.kt`, `schema/BuiltinSchemas.kt` | 単体（`DefinitionsTest`） |
| AG-02 | アクティビティ定義 `_activities`（対象・指示・文脈レシピ・書込許可ファイル/パス・ツール許可・上限・提出ゲート） | ✅ | `agent/Definitions.kt`, `schema/BuiltinSchemas.kt` | 単体（`DefinitionsTest`） |
| AG-03 | ツール使用対応LLMクライアント（OpenAI Responses / Anthropic Messages の function calling。会話セッション抽象で差し替え可能） | ✅ | `ai/ToolChat.kt` | 単体（`ToolChatParseTest`） |
| AG-04 | エージェント実行カーネル（マルチターンループ、ターン・トークン・時間上限、終了手続きの強制、キャンセル、疑似セッション注入） | ✅ | `agent/Kernel.kt` | 単体（`KernelTest`） |
| AG-05 | エージェントツールセット（探索5・編集2・検証1・対話2・管理3の計13ツール。書込権限はツール側で強制） | ✅ | `agent/Tools.kt` | 単体（`KernelTest`） |
| AG-06 | ステージング（複数文書の作業オーバーレイ。影響ファイルを含む横断検証、ID改名の整合確認、未提出ドラフト保全） | ✅ | `agent/Staging.kt` | 単体（`StagingTest`） |
| AG-07 | 変更セットレビューUI（ファイル単位の部分採用、採用サブセットの再検証、左右差分、未保存文書の保護、適用。実行スペース内の常設画面＝モーダルではない。実行状態は `RunController` が保持しスペース切替で中断しない。R12-01） | ✅ | `ui/RunsSpace.kt`（`ReviewContent`, `RunController`） | 手動 |
| AG-08 | 実行中の人間との対話（`ask_human` による質問と回答＝受信箱・実行タブの非モーダルカードで文書を確認しながら回答できる、計画・メモの進捗表示、実行キャンセル。R12-02） | ✅ | `agent/Kernel.kt`, `ui/RunsSpace.kt`（`QuestionCard`） | 単体（`KernelTest` 一部）＋手動 |
| AG-09 | 実行記録 `_runs`（ターン・ツール呼び出し・質問と回答・採否・トークン使用量をYAML保存。索引対象外。会話記録 `transcript`＝システム指示・初期コンテキスト・本文テキスト・ツール呼び出し引数と結果の全文（1件2万字上限）も保存＝デバッグの主材料。委譲先の内部会話は対象外） | ✅ | `agent/RunLog.kt`, `agent/Kernel.kt`（`TranscriptEntry`） | 単体（`KernelTest`） |
| AG-10 | CLIからのアクティビティ実行（`agent` / `activities` コマンド。非対話実行では質問に「回答なし」を返す） | ✅ | `core/Cli.kt` | 手動（スモーク） |
| AG-11 | トリガ（`trigger`: manual / on-change（デバウンス付き） / schedule / on-invalid。予約ディレクトリと自己変更では発火しない） | ✅ | `agent/Automation.kt`（`tick`） | 単体（`AutomationTest`） |
| AG-12 | アクティビティ連鎖（`chain.onSuccess` / `onFailure`、`chain.rules`＝出力値による分岐: 終わり方・採用変更ファイルglob・変更数・メモの部分一致のAND条件で分岐先を選ぶ（ROADMAP R9-04）。深さ上限・循環検出。人間承認は採否決定後に発火） | ✅ | `agent/Automation.kt`（`chainNext`）, `agent/Definitions.kt`（`ChainDef.selectNext`） | 単体（`AutomationTest`, `DefinitionsTest`） |
| AG-13 | 無人リトライ（`retry.maxAttempts`。失敗理由と検証エラーをフィードバックし、同一失敗の繰り返しは収束打ち切り） | ✅ | `agent/Automation.kt`（`handleFailure`） | 単体（`AutomationTest`） |
| AG-14 | 自動承認 `approval: auto`（ゲート通過・スコープ内・非編集中のみ。条件を欠くと承認待ちへ降格。派生スキーマ（S-17）の導出元文書への書込も検証ルールの無人変更を防ぐため降格。適用前バックアップ） | ✅ | `agent/Automation.kt`, `agent/AutoApply.kt` | 単体（`AutomationTest`） |
| AG-15 | 実行キューと停止（直列実行、常時停止＝キュー消去＋実行中キャンセル、承認待ち変更セットのファイル保存による耐久性） | ✅ | `agent/Automation.kt` | 単体（`AutomationTest`） |
| AG-16 | 自動適用の巻き戻し（実行記録のバックアップから復元。適用後の変更は競合として拒否、`--force` で上書き） | ✅ | `agent/AutoApply.kt`（`revert`）, CLI `revert` | 単体（`AutomationTest`） |
| AG-17 | サブエージェント委譲（`delegate` ツール。親のステージングを読める読み取り専用の相談、所見テキストを返す。深さ1） | ✅ | `agent/Kernel.kt`（`consult`）, `agent/Tools.kt` | 単体（`KernelTest`） |
| AG-18 | 自動実行UI（実行スペースの「自動化」タブ: 監視ON/OFF・キュー・履歴・ログ。承認待ちレビューは受信箱（W-10）が担う）と CLI `watch`（`--once` 対応） | ✅ | `ui/AutomationPanel.kt`（`AutomationSection`）, `ui/RunsSpace.kt`, `core/Cli.kt` | 手動＋単体（エンジン経路） |
| AG-19 | 実行メトリクス（`_runs` の集計: 提出率・採用率・却下ファイル率＝修正量の代理指標・自動適用/巻き戻し・トークン。CLI `metrics`） | ✅ | `agent/Metrics.kt`, `core/Cli.kt` | 単体（`MetricsTest`） |
| AG-20 | 評価セット `_evals`（ゴールデン入力＋宣言的な期待結果による回帰評価。サンドボックスコピー上で実行し原本を変更しない。CLI `eval`） | ✅ | `agent/Evals.kt`, `schema/BuiltinSchemas.kt` | 単体（`EvalTest`） |
| AG-21 | 比較実験（評価セットの `variants`: モデル・エージェント・追加指示を差し替えたA/B実行と合格率・コストの比較） | ✅ | `agent/Evals.kt`（`EvalRunner`） | 単体（`EvalTest`） |
| AG-22 | メタアクティビティ（`meta: true` で `_agents` / `_activities` / `_evals` / `_skills` / `_views` への提案を許可。承認は human 固定、`_schemas`/`_runs` は常に不可、定義のパース可否を提出ゲートに追加） | ✅ | `agent/Definitions.kt`, `agent/Tools.kt`, `agent/Staging.kt` | 単体（`MetaActivityTest`, `DefinitionsTest`） |
| AG-23 | 自己改善UI（実行スペースの「計測・評価」タブ: メトリクス表示・評価セットの実行と変異別結果の比較） | ✅ | `ui/ImprovementPanel.kt`（`ImprovementSection`） | 手動＋単体（実行系経路） |
| AG-24 | エージェントへのビュー開示（`read_view` ツール: ステージング反映済みの行×列を原本座標つきで返し、そのまま `apply_patch` の対象にできる。文書ビュー（X-07）は `file` 引数で対象文書を指定（適用対象1件なら省略可）し被参照表を座標つきで返す。初期文脈への開示はコンテキストレシピ `kind: view`（AG-28。行・列の絞り込み可）経由） | ✅ | `agent/Tools.kt`（`viewText`）, `agent/Kernel.kt` | 単体（`ViewsTest`） |
| AG-25 | 段階的詳細化のAI導線（ROADMAP P7）: `related` ツールが各出現の主要フィールド要約と現在フェーズ・記入進捗を1回で返す（出現ごとの `read_node` が不要）。被参照には参照元要素のID・タイトル（表示解決 S-13。人間UIと同じ要約）を付与。`list_documents` のカタログにもフェーズ状態を付与。初期文脈への開示はコンテキストレシピ `kind: phase`（AG-28）経由 | ✅ | `agent/Tools.kt`（`relatedText`, `catalogText`, `phaseText`）, `agent/Kernel.kt`, `core/Disclosure.kt`, `validate/Validator.kt`（`phaseSummaryText`） | 単体（`ToolsTest`, `KernelTest`） |
| AG-26 | 一括実行（`foreach`: 対象列挙とキュー展開。R18-01）: アクティビティ定義の `foreach`（source: view / issues / search / files / ids、`as` テンプレート、`limit`）で残作業を機械列挙し、1件=1実行（対象キー付きキュー項目）として展開。項目単位の重複ガード・失敗隔離。GUI「一括実行（対象を選択）」＝列挙結果をチェックボックス一覧で選択して自動化キューへ投入（選択状態は `RunController` 保持・インライン区画）、CLI `agent --foreach`＝直列一括（`--apply` は1件ごと適用）。trigger: manual 専用（ARCHITECTURE §4.27） | ✅ | `agent/Foreach.kt`, `agent/Definitions.kt`, `agent/Automation.kt`（`enqueueForeach`）, `core/Cli.kt`, `ui/RunsSpace.kt` | 単体（`ForeachTest`, `DefinitionsTest`, `AutomationTest`） |
| AG-27 | コンテキスト会計（ROADMAP R19-01）: 実行記録に「どの宣言がいくらトークンを払わせたか」を記録。初期コンテキストを会計区分（システム指示・ツール定義・指示＋`context` 宣言由来のカタログ・フェーズ・ビュー各々）へ分解し、`context.sections`（文字数・概算トークン）・`context.turnInputTokens`（送信別入力の実測）・`toolCalls[].resultChars`（打切り前の全長）として保存。`usage.cachedInputTokens` はキャッシュ読取の内数（Anthropic は input_tokens がキャッシュ分を含まないため総入力へ正規化）。レビューUIに内訳・送信別実測を表示し、メトリクス（AG-19）へ合算。予算判定は総入力基準を維持（ARCHITECTURE §4.28） | ✅ | `agent/Kernel.kt`（`initialContextSections`, `estimateTokens`）, `agent/RunLog.kt`, `agent/Metrics.kt`, `ai/ToolChat.kt`（`ChatUsage`）, `ui/RunsSpace.kt` | 単体（`KernelTest`, `ToolChatParseTest`, `MetricsTest`） |
| AG-28 | コンテキストレシピ（ROADMAP R19-02）: 初期文脈をアクティビティ定義の `context.items` で利用者が設計する。項目は `catalog`（match 絞り）/ `phase` / `view`（rows=行IDパターン・columns=列選別）/ `doc`（file / path）/ `related`（id）/ `sample`（同スキーマの記述例N件）で、各項目に `when`（always / has-target / no-target）・`budgetChars`（決定的打切り。全文系は既定8,000字）。`{target}` 等のプレースホルダは foreach 対象値（`ForeachItem.values` → `QueueItem.targetValues` → `AgentRunRequest.targetValues`）で解決し、未解決の項目は開示しない。**旧語彙（`context.include` / `context.views`）は廃止＝移行ヒント付き定義エラー、無指定の既定は空**（P19 の空既定）。同梱テンプレート16定義＋ledger パックを新形式へ移行済み（ARCHITECTURE §4.28） | ✅ | `agent/Definitions.kt`（`ContextItemDef`, `parseContextItems`）, `agent/Kernel.kt`, `agent/Tools.kt`（`contextItemSection`, `sampleText`）, `agent/Foreach.kt`, `agent/Automation.kt`, `core/Views.kt`（`renderText` 列絞り）, `core/Cli.kt`, `schema/BuiltinSchemas.kt` | 単体（`DefinitionsTest`, `KernelTest`, `ForeachTest`） |
| AG-31 | 評価セットの文脈レシピA/B（ROADMAP R19-06）: variants に `context`（アクティビティの context.items を丸ごと差し替え。空 = 開示なし）を追加し、同一ケース群を「レシピA/B × 合格率 × トークン」で比較。レポートに変異別 `cachedInputTokens`・ケース別 `inputTokens` / `outputTokens` を追加（レシピの絞りすぎ→探索ターン増の逆コスト増をデータで検出） | ✅ | `agent/Evals.kt`（`EvalVariant.contextItems`）, `agent/Definitions.kt`（`parseContextItems` 共用）, `schema/BuiltinSchemas.kt` | 単体（`EvalTest`） |
| AG-30 | グラフトラバース（ROADMAP R19-03/04）: ID索引を型付きグラフ（ノード=論理ID・出現=多段定義、エッジ=参照）と見て、宣言的な経路パターン（`start`＋`steps` の連鎖: `follow: out / in` × `relation`・`via`・`schema` 絞り、`emit: title / summary / outline / full`、`limitPerHop`）で多段に辿る評価器 `core/Traverse`。決定的出力（ID昇順・「他n件」打切り・訪問済み循環停止・`budgetChars` 打切り）。開示面は3つ: コンテキストレシピ `kind: graph`（`{target}` 連動の決定的プリフェッチ）・エージェントツール `traverse`（related の多段版。相談モードでも可）・CLI `traverse`（レシピの試作台。`--steps` にYAML配列） | ✅ | `core/Traverse.kt`, `agent/Tools.kt`（`traverseText`）, `agent/Definitions.kt`, `core/Cli.kt`, `schema/BuiltinSchemas.kt` | 単体（`TraverseTest`, `ToolsTest`, `DefinitionsTest`, `KernelTest`） |
| AG-29 | 参照の名前解決とツール減量（ROADMAP R19-05）: ①read_node／doc 項目の末尾に「参照の解決」フッタ——配下の参照IDを `ID = タイトル [定義元]` で列挙（未解決IDも明示・上位30件）②related の「この要素からの参照」に参照先タイトル（`= タイトル`）を付与 ③ビュー renderText の ref 値セルを「ID（タイトル）」注記へ展開（従来「（配列 n 件）」）——IDの意味解決のための追加ツール呼び出し（＝ターン）を不要にする。④related の被参照は既定20件打切り＋`limit` 引数 ⑤read_view に `rows`（行IDパターン）／`columns`（列ラベル）引数 ⑥list_documents に `match`（glob）引数——実行中盤のツール結果膨張（被参照全列挙・全量ビュー）の抑制 | ✅ | `agent/Tools.kt`（`refsFooterText`, `relatedText`, `viewText`）, `core/Views.kt`（`refAnnotatedCellText`） | 単体（`ToolsTest`） |

## 9. コアAPI・CLI（エージェント基盤）

UIに依存しないヘッドレス操作の層（[ROADMAP.md](ROADMAP.md) P1 の成果）。CLI の使い方は README §16 を参照。

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| C-01 | ヘッドレスワークスペースAPI（開く・読む・保存・検証・相対パス解決の脱出防止） | ✅ | `core/Workspace.kt` | 単体（`WorkspaceCliTest`） |
| C-02 | YPathパッチ（set / insert / delete / move）と YPath 文字列パース | ✅ | `core/Patch.kt`（`Patcher`）, `model/YNode.kt`（`parseYPath`） | 単体（`PatchTest`, `YPathTest`） |
| C-03 | 変更セット（複数ファイルのパッチ束＋新規ファイル作成。baseハッシュ競合検出、全件成功時のみ書込、適用後状態のオーバーレイ索引で検証、`--require-valid` ゲート、ドライラン） | ✅ | `core/Patch.kt`（`ChangeSets`）, `core/Workspace.kt`（`apply`） | 単体（`WorkspaceCliTest`） |
| C-04 | 段階的開示API（カタログ→骨子→ノード全文の3段階、文字数予算付き文脈バンドル） | ✅ | `core/Disclosure.kt` | 単体（`WorkspaceCliTest`） |
| C-05 | 構造検索API（本文・キーの部分一致＋スキーマ／ファイルglob／パスglob条件、参照検索・未解決参照検索） | ✅ | `core/Search.kt` | 単体（`WorkspaceCliTest`） |
| C-06 | CLI（catalog / outline / read / bundle / search / view / validate / apply / hash / activities / agent / watch / revert / metrics / eval / bench / skills / import / export / run-skill / init / infer-schema / migrate。出力はUTF-8のYAML。`validate` は `phases` 宣言スキーマの文書に `phaseProgress` を付与し、`_schemas` 文書もメタスキーマ（S-15）で検証する） | ✅ | `core/Cli.kt`, `build.gradle.kts`（`runCli`） | 単体（`WorkspaceCliTest` 一気通貫） |
| C-07 | ビュー解決API（`_views` 定義のロード・行×セルへの解決＝原本座標化・バックリンク列の解決＝参照元の最内IDと参照座標・データパスからのスキーマフィールド解決・詳細文書スキャフォールド・文書ビューの解決（X-07: `resolveDocument`＝被参照表の座標つき解決と `renderDocText`）。CLI `view <id> [file]`） | ✅ | `core/Views.kt`, `core/Cli.kt` | 単体（`ViewsTest`） |
| C-08 | 索引性能ベンチマーク（ベンチマーク用ワークスペースの生成と、索引のフル再構築 vs 増分更新の比較計測。CLI `bench`。ROADMAP R8-02） | ✅ | `core/Bench.kt`, `core/Cli.kt` | 単体（`IdIndexIncrementalTest`） |
| C-09 | 文書キャッシュの中核（`EditBuffer`＝履歴・dirty・複数ドキュメント再出力、`WorkspaceRecords`＝一覧・一括編集、`Workspace.noteDiskChanges`＝保存・外部変更の増分／全再構築の振り分け一元化。UIの `AppState` / `OpenDocument` はこの層への委譲＋Compose購読ミラーになる。ROADMAP R9-02） | ✅ | `core/Documents.kt`, `core/Workspace.kt` | 単体（`DocumentsTest`） |

## 10. skill・テンプレート（フォーマット適合）

宣言型の入出力変換・テンプレートパック（[ROADMAP.md](ROADMAP.md) P5 の成果）と、形式拡張・コード実行skill（P9 の R9-05・R9-06）。利用方法は README §14 を参照。

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| SK-01 | skill定義 `_skills`（kind: import / export の宣言型変換と、kind: run のコード実行（SK-07）。組込みスキーマでフォーム編集・検証、メタアクティビティの提案対象） | ✅ | `skill/Skills.kt`, `schema/BuiltinSchemas.kt` | 単体（`ImportExportTest`, `RunSkillTest`） |
| SK-02 | インポート（xlsx / csv / json / markdown / docx＝Word文書内の表（`input.table` で何番目か指定。ROADMAP R9-05）→ 変更セット。新規行は文書作成、既存文書はマップ項目のみ更新＝追記保持。列マッピング: 型変換・値辞書・区切りによる配列化・必須・{列名}によるファイル解決） | ✅ | `skill/Importer.kt`, `skill/Tables.kt` | 単体（`ImportExportTest`, `TablesTest`） |
| SK-03 | エクスポート（対象glob×行（文書 or 配列要素）×列（YPath）→ xlsx / csv / markdown / json / html。`source.view` で `_views` のビュー定義を行×列の源にできる＝「ビューで見えている表をそのまま帳票へ」（ROADMAP R9-05）。YAMLへの出力は拒否＝派生成果物の原則） | ✅ | `skill/Exporter.kt`, `skill/Tables.kt` | 単体（`ImportExportTest`） |
| SK-04 | CLI（`skills` / `import`（プレビュー既定・`--apply`・`--require-valid`）/ `export` / `run-skill`（`--yes` なしは内容表示のみ）/ `init`） | ✅ | `core/Cli.kt` | 手動（スモーク）＋単体（`RunSkillTest` の `--yes` ガード） |
| SK-05 | テンプレートパック（`init --template ledger`: 課題台帳のスキーマ＋エージェント＋アクティビティ＋評価＋skill一式。既存ファイルは上書きしない） | ✅ | `skill/TemplatePacks.kt` | 単体（`ImportExportTest`） |
| SK-06 | skill（連携）UI（設計スペースの「連携」に常設: 一覧・エクスポート実行・インポートのファイル選択→プレビュー→検証つき適用・コード実行skillの承認ダイアログ→実行） | ✅ | `ui/SkillsPanel.kt`（`SkillsSection`）, `ui/DesignSpace.kt` | 手動＋単体（実行系経路） |
| SK-07 | コード実行skill（kind: run。ROADMAP R9-06）: 宣言型で書けない変換・定型手順のための任意コマンド実行。実行前に人間がコマンドと権限を承認する明示許可制（承認は定義内容の指紋に紐づき、定義が変わると失効）。エージェント・自動実行からは起動不可。実行後に `writes` 宣言外の変更を警告・予約ディレクトリの変更を逸脱として報告 | ✅ | `skill/Runner.kt`, `skill/Skills.kt`, `ui/SkillsPanel.kt`, `core/Cli.kt` | 単体（`RunSkillTest`） |

## 11. 未実装・既知の制約

利用者向けの注意事項でもあるため、変更時は README の「既知の制約」節との整合を保つこと。

| ID | 内容 | 状態 | 備考 |
|---|---|---|---|
| L-01 | YAMLコメント・アンカー・スタイルの保持 | ✅ ほぼ解消 | R8-01 で `YamlIO` を Node API ベースへ差し替え、コメント・キー順・スカラー/フロー表記を「開く→編集→保存」の往復で保持（E-06）。残る正規化: アンカーは展開・インデント2固定・リスト要素の前行コメントは初回保存で `- # コメント` の形へ移動（内容は保持・以降は安定。SnakeYAMLエミッタの制約） |
| L-02 | 複数ドキュメント（`---` 区切り）YAML | ✅ 解消 | R8-05 で対応（E-10）。フォームは先頭ドキュメント、後続は保持・ソースで編集可。索引・検索は先頭ドキュメント対象 |
| L-03 | ソースエディタの構文ハイライト・置換 | ✅ 解消 | R8-04 で対応（E-03） |
| L-04 | 文字単位の差分表示 | ✅ 解消 | R8-04 で対応（E-04。変更行内の変わった箇所を強調） |
| L-05 | 汎用ビューでのキー名変更 | ✅ 解消 | R8-05 で対応（E-02。順序・値・コメントを保った改名） |
| L-06 | 参照グラフの本格的なレイアウト・全件表示 | ✅ 解消 | R8-05 で対応（X-03。力学配置・全件描画・パン/ズーム） |
| L-07 | 一括編集の対象キー拡張 | ✅ 解消 | R8-05 で対応（X-02。ルート直下の任意スカラーキー） |
| L-08 | ファイル監視のOSネイティブ化（WatchService等） | ✅ 解消 | R8-03 で対応（W-04。ポーリングは自動フォールバックとして残置） |
| L-09 | フォーム系UI・AppState の自動テスト | 🚧 | 単体テストは ai／diff／AI提案合成／core（パッチ・開示・検索・CLI・監視・索引増分・文書キャッシュ中核）／schema（表示解決・enumリッチ選択肢）／カバレッジ検証／agent（ステージング・カーネル・定義・自動化・メトリクス・評価・メタ・生成統合）／skill（入出力・コード実行）／ui純ロジック（構文ハイライト・置換・グラフレイアウト・キー名変更・閲覧専用の編集ガード）／OpenDocument（複数ドキュメント）。R9-02 の抽出（C-09）で履歴・dirty・一覧・一括編集・索引更新方針が core で単体テスト可能になった。Compose UI 自体は引き続き手動確認（プロジェクト方針） |
| L-10 | AppState の core.Workspace への全面委譲 | ✅ 解消 | R9-02 で対応（C-09）。AppState は core.Workspace（走査・スキーマ・索引）への委譲＋Compose購読ミラーになり、保存・外部変更の索引更新方針（増分／全再構築）は `Workspace.noteDiskChanges` に一元化。`OpenDocument` は core.EditBuffer（履歴・dirty・複数ドキュメント）を包む Compose シェル |
| L-11 | AI生成ダイアログ（A-01〜03）とエージェントカーネルの完全統合 | ✅ 解消 | R9-01 で対応（A-10）。単発生成を「1ターンで提出する特殊アクティビティ」としてカーネル実行し、Structured Outputs の型保証・ダイアログのUX（プレビュー→未保存適用）は維持。実行記録が単一系統になった |
| L-12 | コード実行skill（宣言型で書けない変換・定型手順） | ✅ 解消 | R9-06 で対応（SK-07）。実行前の人間承認（定義指紋に紐づく失効つき）を機械強制する明示許可制で実装 |
| L-13 | エクスポートのHTML・JSON形式、インポートのWord等 | ✅ 解消 | R9-05 で対応（SK-02・SK-03）。エクスポート xlsx / csv / markdown / json / html、インポート + docx（Word文書内の表） |
| L-14 | ビューグリッドの行集合はディスク索引基準 | ✅ UX-03 で改訂（解消） | R9-02 の再評価で一度「ディスク索引基準を維持」と確定したが、オーバーレイ索引（`AppState.overlayIndex()`＝dirty文書の編集中ツリーだけを重ねた索引。ARCHITECTURE §4.5）の導入により**UIでは未保存の行の増減も反映される**ようになった。UI層の検証・カバレッジ・ID横ぐし・参照解決・被参照も同じオーバーレイを読む（「見たままの状態で判定」）。ディスク索引が正であること・ヘッドレス経路（CLI・エージェント・エクスポート）がディスク基準であることは不変。セル直接編集はスカラー型のみで、配列・オブジェクトはジャンプして個別フォームで編集する |
| L-15 | 出現ベースのカバレッジルール | ✅ 解消 | R7-02 で対応（S-09 の `detailIn`・V-05）。「一覧の全IDに詳細文書（特定スキーマの出現）があること」を機械検証できる |
| L-16 | `phases` / `coverage` のスキーマ設計UI未対応 | ✅ 解消 | R9-03 で対応（S-05）。フェーズ宣言（申告キー・順序）とカバレッジルール（参照／出現ベース・重大度・activeFrom）を構造化UIで編集できる |
