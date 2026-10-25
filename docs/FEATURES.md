# 機能索引

機能から実装ファイルとテストを引く索引。1 行 1 機能で、経緯は書かない（記録は [archive/](archive/README.md) と git log）。
利用方法は [manual/](manual/README.md)、設計は [architecture/](architecture/README.md) が正。「設計」列は設計文書の主題。

- **テスト**: `単体` は単体テストあり（代表のテスト名を添える）。`手動` は手動確認のみ。
- 未実装は [ROADMAP.md](ROADMAP.md)、既知の制約は [BACKLOG.md](BACKLOG.md) と manual/limits.mdに置く。

## 1. ワークスペース・ファイル管理

| ID | 機能 | 主な実装 | テスト | 設計 |
|---|---|---|---|---|
| W-01 | ワークスペース選択（起動引数・OS ネイティブのフォルダ選択・スタート画面・最後に選んだ場所を用途ごとに記憶） | `Main.kt`, `ui/FilePickers.kt`, `AppState.openWorkspace` | 手動 | [ui](architecture/ui.md) |
| W-02 | ファイルツリー（全形式・隠し項目・除外・パス検索・展開の永続化・現在地） | `ui/FileTreePane.kt`, `core/FileCatalog.kt` | `FileCatalogTest`・画面は手動 | [ui](architecture/ui.md) |
| W-03 | ファイル・フォルダーの配置変更と Undo | `FileArrangementPlanner`, `FileArrangementJournal`, `FileMoveDialog` | `AppStateFileArrangementTest`・`DocumentCacheSnapshotTest` ほか | [workspace](architecture/workspace.md) |
| W-04 | 外部監視と再読込（履歴保持・競合保護・確認付き破棄） | `AppState.reload`, `WorkspaceSession.refreshFrom`, `ReloadConfirmationDialog` | `ReloadHistoryTest`・`SessionRefreshTest` | [workspace](architecture/workspace.md) |
| W-05 | 最近開いたファイル履歴（12 件・ワークスペース単位で永続化） | `AppState.recentFiles` | 手動 | [ui](architecture/ui.md) |
| W-06 | UI レイアウトとテーマ（左右の領域アイコン・ペイン内の閉じる操作・幅と開閉とテーマの永続化） | `Main.kt`, `ui/AppToolbar.kt`, `ui/Common.kt` | 手動 | [ui](architecture/ui.md) |
| W-07 | 未保存確認（終了時・ワークスペース切替時） | `Main.kt` | 手動 | [ui](architecture/ui.md) |
| W-08 | ワークスペース管理（最近開いた履歴・スタート画面・パレットからの切替） | `Main.kt`（`StartScreen`）, `AppState.recentWorkspaces` | 手動 | [ui](architecture/ui.md) |
| W-09 | UI シェル（標準メニュー・移動と検索・タイトルのワークスペース絶対パス・レールで4スペースを切替） | `Main.kt`, `ui/AppToolbar.kt`, `ui/Shell.kt` | 単体 `NavigationHistoryTest`・画面は手動 | [ui](architecture/ui.md) |
| W-10 | 受信箱（承認待ち変更セット・エージェントの質問・外部変更競合を実行スペースの先頭タブに集約・バッジと集計） | `ui/RunsSpace.kt`（`InboxTab`）, `Main.kt`（`StatusBar`） | 手動 | [ui](architecture/ui.md) |
| W-11 | ワークスペース構造（`.yde/` 構成と `ledger` 宣言・レガシー構成は互換・配置判定は `WorkspaceLayout`・CLI `migrate`） | `workspace-core/layout/WorkspaceLayout.kt`, `core/Workspace.kt` | 単体 `WorkspaceLayoutTest`, `DocumentStoreTest` | [workspace](architecture/workspace.md) |
| W-12 | エディタ層と台帳層の分離（台帳外の YAML はフォームと単文書検証だけ・索引・集計・エージェントは台帳のみ） | `validate/Validator.kt`, `app/AppState.kt`（`editorOnlyFiles`） | 単体 `WorkspaceLayoutTest`, `DocumentStoreTest` | [workspace](architecture/workspace.md) |
| W-13 | 同梱フォント（等幅 Cica をビルド時に取得して資源へ・`AppFonts.Mono`・資源が無ければ OS の等幅へ戻す） | `build.gradle.kts`（`downloadFonts`）, `ui/Common.kt`（`AppFonts`） | 手動 | [ui](architecture/ui.md) |
| W-14 | Markdown の読取専用表示・相対リンク・外部変更の反映 | `ui/MarkdownPane.kt`, `ui/MarkdownView.kt` | `MarkdownLinkTest` | [ui](architecture/ui.md) |
| W-15 | 内蔵ヘルプ（利用者マニュアルをビルド時に資源へ入れ、ヘルプメニューとコマンドパレットから文書として開く・題目間のリンクで移動） | `app/Help.kt`, `build.gradle.kts`（`copyHelp`）, `ui/MarkdownPane.kt` | 単体 `MarkdownLinkTest` | [ui](architecture/ui.md) |
| W-16 | 画面の見え方の保持（タブ・畳み・選択・絞り込みは画面を離れても保持、ツリーの展開は永続化） | `app/ScreenMemory.kt` | `ScreenMemoryTest` | [ui](architecture/ui.md) |
| W-17 | Windows ZIP 配布（起動案内・サンプル・ビルド情報・SHA-256） | `build.gradle.kts`（`windowsRelease`） | `test-windows-zip.ps1`, `DistributionSmoke` | [配布](releasing.md) |
| W-18 | 複数選択・キー操作・ドラッグ（固定案内・移動先強調） | `ui/FileTreeSelection.kt`, `ui/FileTreePane.kt` | `FileTreeSelectionTest`・画面は手動 | [ui](architecture/ui.md) |
| W-19 | 参考資料の本文検索（範囲・容量上限・中止・行へ移動） | `core/ReferenceSearch.kt` | `ReferenceToolsTest`, `FileTreeBenchmark` | [ui](architecture/ui.md) |
| W-20 | ソースの宣言候補の表示と YAML 保存 | `core/SourceOutline.kt`, `ui/FilePreviewPane.kt` | `ReferenceToolsTest`, `AppStateFileArrangementTest` | [ui](architecture/ui.md) |

## 2. 文書編集

| ID | 機能 | 主な実装 | テスト | 設計 |
|---|---|---|---|---|
| E-18 | フォームの複合値は全幅配置と上限付き字下げ・単一値は幅に応じた配置・文書情報と他文書の定義は開閉可能 | `ui/FormLayout.kt`, `ui/FormView.kt` | 単体 `FormLayoutTest`, `ScreenMemoryTest`・画面は手動 | [ui](architecture/ui.md) |
| E-22 | フォーム入力の Esc で内容を保持してフォーカス解除（IME・候補を優先） | `ui/Common.kt`, `ui/TextFieldEscape.kt` | `TextFieldEscapeTest`・画面は手動 | [UI 操作規則](architecture/ui-interactions.md) |
| E-23 | 選択一覧の現在値・高さ制限・説明全文・検索 | `ui/ChoiceMenu.kt`・`ChoiceOptions.kt` | ChoiceOptionsTest・手動（長い選択一覧） | [UI 操作規則](architecture/ui-interactions.md) |
| E-21 | 表示方式のアイコン操作・固定／可変・ファイルの明示指定と確認・構成ペインの× | `app/FormDisplayMemory.kt`, `ui/FormDisplayControls.kt` | `FormDisplayMemoryTest`・画面は手動 | [ui](architecture/ui.md) |
| E-20 | フォームの構成ツリーと三つの表示方式（選択項目・全体連動・全体のみ） | `ui/StructuredForm.kt`, `ui/DocumentFormWorkspace.kt` | `FormOutlineTest`, `FormPositionsTest`, `ScreenMemoryTest`・画面は手動 | [ui](architecture/ui.md) |
| E-19 | 項目説明の短い表示・項目名とⓘのホバーで小さな予告・説明ボタンで全文固定（編集・閲覧共通） | `ui/FieldHelp.kt` | 単体 `FieldHelpPositionTest`・画面は手動 | [ui](architecture/ui.md) |
| E-01 | スキーマ駆動フォーム（基底の種類別の編集欄・型から引き継ぐヒント＝複数行と入力欄の案内と表示の形 `format`・必須マーク・説明は Markdown・折りたたみ・id 型は既存 ID の補完と連番の提案と横ぐしへの直行・int / number の桁区切り表示 `display: grouped`・残留値の修復 UI） | `ui/FormView.kt`（`IdEditor`）, `schema/IdSuggest.kt` | 単体 `IdSuggestTest` | [ui](architecture/ui.md) |
| E-15 | 日付・時刻・日時のピッカー（入力欄の右のボタンからポップアップにカレンダーと時刻入力・日付はクリックで即反映・値は正準の文字列のまま） | `ui/TemporalEditor.kt`（`TemporalPick`, `TemporalPickerButton`） | 単体 `TemporalPickTest` | [ui](architecture/ui.md) |
| E-16 | 配列要素のドラッグ並べ替え（取っ手 ⋮⋮ からだけ掴める・ドロップで 1 回だけ木を動かす＝Undo 1 回・縦リスト／テーブル／汎用エディタ共通・上下ボタンも残す） | `ui/Reorder.kt`（`ReorderableList`）, `ui/FormView.kt`（`moveSeqItem`） | 単体 `MoveSeqItemTest` | [ui](architecture/ui.md) |
| E-17 | file 型の入力欄と画像の表示（文字で書く 3 通りの書き方・「選ぶ」は OS 標準のダイアログでルート相対に書き込む・解決した場所と有無の 1 行・画像は欄の下に幅に合わせて表示し拡大率とドラッグで操作・「拡大表示」で全面） | `ui/FileEditor.kt`, `ui/ImagePane.kt`, `AppState.openImage` | 単体 `FileEditorTest` | [ui](architecture/ui.md) |
| E-02 | 汎用ツリーエディタ（スキーマなし文書・スキーマ外キー・順序とコメントを保ったキー改名） | `ui/FormView.kt`（`GenericEditor`） | 単体 `RenameKeyTest` | [ui](architecture/ui.md) |
| E-03 | ソース編集（下書き・書式保持・履歴との同期） | `SourcePane`・`SourceHistorySync` | `LineMergeTest`・`SourceHistorySyncTest`・`TextOnlySaveTest` | [editing-history](architecture/editing-history.md) |
| E-04 | 差分表示（保存済みと編集中の行整列・文字単位の強調） | `ui/DiffPane.kt` | 単体 `DiffPaneTest` | [ui](architecture/ui.md) |
| E-05 | Undo/Redo（横断履歴・編集元への復帰・通知・入力の区切り） | `AppState`, `HistoryNoticeBanner` | `HistoryLocationTest`・`HistoryNoticeTest`・`HistoryCoalescingTest` ほか | [editing-history](architecture/editing-history.md) |
| E-06 | 保存（Ctrl+S・すべて保存・外部変更の直前検査と明示上書き） | `AppState.save*`, `WorkspaceSession.save*` | アプリ・コアの `SaveConflictTest`, `YamlIOTest` | [editing-history](architecture/editing-history.md) |
| E-07 | 文書メタデータ `_document`（id・name・description）の編集とコンパクトな文書ヘッダー | `ui/FormView.kt`・`ui/EditorChrome.kt` | 手動 | [ui](architecture/ui.md) |
| E-08 | 文書キャッシュ（ファイル切替時も未保存編集を保持） | `AppState.docCache` | 手動 | [editing-history](architecture/editing-history.md) |
| E-09 | 同一 ID の他文書出現ストリップ（相手側の情報の表示とジャンプ） | `ui/FormView.kt`（`SameIdOccurrencesStrip`） | 手動 | [ui](architecture/ui.md) |
| E-10 | 複数ドキュメント YAML（フォームは先頭・後続は保持して再出力・ソースは全体を編集可） | `yaml-core/yaml/YamlIO.kt`（`parseAll` / `dumpAll`）, `app/OpenDocument` | 単体 `OpenDocumentTest` | [yaml-model](architecture/yaml-model.md) |
| E-11 | 畳み込みサマリ（畳んだ要素の 1 行要約・参照の値は参照先の名前を添える・既定はルートから 2 階層まで展開） | `ui/FormView.kt`, `schema/Display.kt` | 単体 `DisplayTest`, `DefaultExpandTest` | [ui](architecture/ui.md) |
| E-12 | 閲覧専用モード（`FormCtx.readOnly`・入力欄はテキスト表示・書込は拒否・横ぐしとグリッドへ波及） | `ui/FormView.kt`（`FormCtx`）, `app/AppState.readOnlyMode` | 単体 `ReadOnlyTest` | [ui](architecture/ui.md) |
| E-13 | 配列と map のテーブル表示（適否の自動判定と `display: table / list`・セル編集・展開フォーム・列幅・参照セルは ID と参照先の名前と横ぐしへの Hub） | `ui/FormView.kt`（`ObjectTable`・`RefCell`）, `schema/Display.kt`（`tableColumns`） | 単体 `DisplayTest`, `ArrayTableTest`, `RefTargetTitleTest` | [ui](architecture/ui.md) |
| E-14 | 保存時に自動で入る値（印 `auto`＝更新日・作成日・版。保存の直前の 1 トランザクション・変更セットの適用にも） | `semantic-core/semantic/AutoValues.kt`, `workspace-core/workspace/WorkspaceSession.kt` | 単体 `SemanticAutoValuesTest`, `AutoValuesKernelTest`, `AppStateAutoValuesTest` | [auto-values](architecture/auto-values.md) |
| E-15 | ソースの基本操作（Ctrl+F / H / G の検索・置換・行へ移動、一致の強調と F3 の移動、Aa と .*、Enter の自動インデント、選択行の Tab / Shift+Tab、Ctrl+/ コメント、Alt+↑↓ 行の移動、Ctrl+D 複製、現在行の強調） | `ui/SourceEditing.kt`, `ui/SourcePane.kt` | 単体 `SourceEditingTest` | [editing-history](architecture/editing-history.md) |
| E-16 | ソースでスキーマと索引を使う（指摘を左端の印と値の色で出し右パネルの指摘から行へ、Ctrl+Space でキー名・ID・enum・file の補完、ホバーで項目の説明、Ctrl+クリックで参照先へ） | `ui/SourceContext.kt`, `ui/SourceCompletion.kt`, `yaml-core/yaml/YamlPositions.kt` | `SourceCompletionTest` ほか | [editing-history](architecture/editing-history.md) |
| E-17 | ソースの構造（キーの入れ子単位の折り畳み＝行番号の ▾ か Ctrl+Shift+[・覚えは文書ごと、保存済みとの差分の印を左端に、フォームで最後に編集した項目とソースのカーソルの項目を行き来で揃える、選択範囲だけの整形） | `ui/SourceStructure.kt`, `ui/SourcePane.kt` | `SourceStructureTest` | [editing-history](architecture/editing-history.md) |
| E-24 | ソースの構文状態（固定高の正常・確認中・エラー表示、全文・コピー・行移動の詳細ポップアップ） | `ui/SourceSyntaxBar.kt` | 単体 `SourceSyntaxBarTest`・手動 | [editing-history](architecture/editing-history.md) |

## 3. スキーマ

| ID | 機能 | 主な実装 | テスト | 設計 |
|---|---|---|---|---|
| S-01 | スキーマ読込（`match` glob 先勝ち・id 重複検出・読むのはカーネルの経路だけ・root の `SchemaDef` は薄い型） | `workspace-core/workspace/SchemaDocuments.kt`・`WorkspaceReader.kt`, `schema/Schema.kt` | 単体 `SchemaLoaderTest`, `KernelCheckLoadingTest` | [schema](architecture/schema.md) |
| S-02 | 型システム: 基底の種類 14 種（表現の基底 9＋意味の基底型 `id` / `ref` / `date` / `time` / `datetime`。存在の検証）＋組込みの定義体 `text` / `line` / `word` | `semantic-core/schema/Fields.kt`・`ValueSemantics.kt`・`BuiltinTypes.kt` | 単体 `MetaSchemaTest`, `TypeDefinitionsTest` | [schema](architecture/schema.md) |
| S-03 | 名前付き定義と再帰参照（`definitions` / `$ref`・狭める規則の合成） | `semantic-core/schema/SchemaTree.kt`（`resolve`） | 単体 `TypeDefinitionsTest` | [schema](architecture/schema.md) |
| S-04 | メタスキーマ検証と宣言のない関係名の点検（実体はカーネル・読み込みエラーへ足す） | `workspace-core/workspace/SchemaDefinitionChecks.kt` | 単体 `SchemaLoaderTest`, `KernelOnlyChecksTest` | [schema](architecture/schema.md) |
| S-05 | スキーマ設計（三方式・型・必須・表示・フェーズ・同一性・プレビュー） | `ui/SchemaEditor.kt`, `ui/SchemaDesignWorkspace.kt` | `SchemaOutlineTest`・手動 | [ui](architecture/ui.md) |
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
| S-25 | ファイルの場所の型（`type: file`・接頭辞なし＝ルートから・`./` `../`＝文書から・絶対の 3 通り・`extensions` で拡張子を絞る・値は文字列のまま） | `semantic-core/schema/FilePath.kt`, `schema/Fields.kt`（`FieldType.FILE`, `extensions`）, `builtin-schemas/schema.yaml` | 単体 `FilePathTest` | [schema](architecture/schema.md) |
| S-26 | 設計画面の補完・操作入力・採番・子項目の構成 | `ui/SchemaAdditionalOutline.kt` | `SchemaAdditionalOutlineTest`・`SchemaAuthoringTest` | [設計画面](manual/schema-editor.md) |
| S-27 | 条件・手順・式の構造編集と追従検査 | `ui/SchemaValueEditor.kt` | `SchemaValueFormsTest`・`SchemaDefinitionCoverageTest`・`SchemaAuthoringHistoryTest` | [設計画面](manual/schema-editor.md) |

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
| V-21 | 準備完了レポートの画面（台帳の「準備」タブ・件数のタイル・型別とフェーズ別の積み上げ棒・エンティティの表から横ぐしへ・保存済みの内容で判定） | `ui/ReadinessPane.kt`, `ui/Charts.kt`（`BarChart`）, `AppState.readinessReport` | 単体 `ReadinessChartTest` | [ui](architecture/ui.md) |
| V-14 | 検査の定義と語彙の定義（`constraints` の `use` ＋ `with`・`functions`・`vocabulary`・組込みは特権を持たない・CLI `checks`） | `semantic-core/schema/CheckDefinitions.kt`, `workspace-core/workspace/CheckCatalog.kt` | 単体 `CheckDefinitionsTest`, `CheckDefinitionTest` | [checks](architecture/checks.md) |
| V-15 | 検査の結果の 4 つと指摘の出どころ（違反・充足・保留・対象外・`IssueSource`・保留は `withheld`・CLI `validate --sources`） | `semantic-core/semantic/CheckResults.kt`, `workspace-core/validate/CodedChecks.kt` | 単体 `SemanticRulesTest`, `CheckOutcomeTest` | [checks](architecture/checks.md) |
| V-16 | クエリの線の意味（参照を書いた要素は最も内側の ID を持つ要素・入れ子の線 `contains`・`via`・名前を書いた線は保有の線も辿る） | `semantic-core/semantic/Graph.kt`（`ownRefs`）, `semantic-core/semantic/Query.kt`（`holdingSteps`） | 単体 `QueryNestingTest`, `PreviewValidationTest` | [graph-query](architecture/graph-query.md) |
| V-17 | 規模の計測（CLI `bench`・合成ワークスペースで開く・検証・レポートの時間と伸び方を出す） | `core/Bench.kt`, `core/Cli.kt` | 単体 `BenchTest` | [checks](architecture/checks.md) |
| V-18 | 検査の評価の共有（同じ索引の上では検査 × 要素を 1 回だけ評価・全体の検証が文書数に比例） | `semantic-core/semantic/CheckSession.kt`, `workspace-core/workspace/WorkspaceCheckRunner.kt` | 単体 `KernelCheckLoadingTest`, `BenchTest` | [checks](architecture/checks.md) |
| V-19 | コードで実装された検査（ID パターンからの逸脱・重複所有・テンプレートの健全性・カーネルが配る） | `workspace-core/validate/CodedDocumentChecks.kt`, `workspace-core/workspace/SchemaDefinitionChecks.kt` | 単体 `KernelOnlyChecksTest` | [checks](architecture/checks.md) |
| V-20 | 文書 1 つの検証の組み立て（構造 → ID と参照 → エンティティ型 → 関係名 → 定義体 → テンプレート → 案内・台帳外の降格と重複除去） | `workspace-core/validate/DocumentValidator.kt` | 単体 `KernelCheckLoadingTest`, `KernelOnlyChecksTest` | [checks](architecture/checks.md) |
| V-22 | file 型の値の検査（書き方・拡張子・ワークスペースの外・無いファイル／外と無いファイルの扱いは `.yde/workspace.yaml` の `files:`＝許可／警告／違反と許す場所／存在はストアに聞く） | `workspace-core/validate/CodedDocumentChecks.kt`（`fileIssues`）, `workspace-core/layout/FilePolicy.kt` | 単体 `FileChecksTest` | [checks](architecture/checks.md) |

## 5. ID・参照

| ID | 機能 | 主な実装 | テスト | 設計 |
|---|---|---|---|---|
| I-01 | ID 索引（ディスク基準・`type: id` とキー名 `id`・変更ファイルだけの増分更新） | `index/IdIndex.kt`（`DocScanner`, `update`） | 単体 `IdIndexIncrementalTest` | [index-references](architecture/index-references.md) |
| I-02 | 参照認識（`type: ref` と命名規約） | `index/IdIndex.kt`（`DocScanner`） | 手動 | [index-references](architecture/index-references.md) |
| I-03 | リンクジャンプ（→ボタン・ハイライト・複数出現の選択） | `AppState.navigateToId`, `ui/Common.kt`（`Highlightable`） | 手動 | [ui](architecture/ui.md) |
| I-04 | 参照候補の補完（`idPatterns` / `scope` で絞り込み・解決先タイトルの併記・行き先の選択メニュー） | `ui/FormView.kt`（`RefEditor`） | 手動 | [ui](architecture/ui.md) |
| I-05 | バックリンク（被参照一覧・参照元の ID とタイトル） | `IdIndex.backlinksTo`, `ui/SidePanel.kt` | 手動 | [index-references](architecture/index-references.md) |
| I-06 | ID 検索（コマンドパレット・ID とタイトルの部分一致・オーバーレイ索引） | `Main.kt`（`CommandPalette`） | 手動 | [ui](architecture/ui.md) |
| I-07 | エンティティページ（合成全体像・関係パネル・カバレッジ・改名／統合／削除・グラフレンズへ。入口は `openCrossView` の 1 経路＝ID 欄の Hub ボタン・参照の→メニュー・ストリップ・出現タブ・ビューグリッド・レンズ） | `ui/IdCrossView.kt`, `core/Aspects.kt`, `AppState.openCrossView` | 手動（合成は `AspectTest`） | [graph-query](architecture/graph-query.md) |
| I-08 | ナビゲーション履歴（戻る・進む・スペースとタブと ID とビューとハイライトとエディタのタブを復元・ショートカットはウィンドウで受ける） | `AppState.goBack` / `goForward`, `Main.kt`（`handleShortcut`） | 単体 `NavigationHistoryTest`・画面は手動 | [ui](architecture/ui.md) |
| I-09 | ID インスペクタ（右パネルの検証・関係・出現の 3 タブ） | `ui/SidePanel.kt` | 手動 | [ui](architecture/ui.md) |
| I-10 | 導出グラフ（走査結果から導出する読み取り専用のエンティティグラフ・索引の更新で無効化） | `semantic-core/semantic/`（`SemanticEntityGraph`）, `index/IdIndex.kt`（`graph`） | 単体 `EntityGraphTest` | [graph-query](architecture/graph-query.md) |
| I-11 | 文書参照（`target: document`・文書ノードへの参照・エンティティ参照とは混ぜない） | `index/IdIndex.kt`（`RefEntry.targetKind`）, `ui/FormView.kt`（`DocRefScalarEditor`） | 単体 `DocRefTest` | [graph-query](architecture/graph-query.md) |
| I-12 | 場所は文書 ID（索引・検証・ビュー・クエリの場所はワークスペース相対の `DocumentId`・root の型はカーネルの別名・編集用セッションは `fork`） | `index/IdIndex.kt`, `workspace-core/workspace/WorkspaceSession.kt`（`fork`） | 単体 `SessionForkTest`, `EditingSessionForkTest` | [index-references](architecture/index-references.md) |
| I-13 | 保有と所属（入れ子で決まる関係に名前＝子を書く項目の `relation` かキー名・レジストリの宣言が効く・関係パネルと related・削除の注意事項） | `semantic-core/semantic/Graph.kt`（`SemanticHolding`）, `semantic-core/semantic/Refactor.kt` | 単体 `HoldingDeleteTest`, `SemanticEntityGraphTest` | [graph-query](architecture/graph-query.md) |

## 6. ワークスペース横断ビュー

| ID | 機能 | 主な実装 | テスト | 設計 |
|---|---|---|---|---|
| X-01 | 一覧ビュー（ID・タイトル・状態・担当・種別・絞り込み・未保存編集に追随・チェックと ID の列を左に固定） | `ui/WorkspaceViews.kt`, `ui/VirtualTable.kt`, `AppState.workspaceRecords` | 単体 `VirtualTableTest` | [views](architecture/views.md) |
| X-02 | 一括編集（自動値も含め Undo 1 回） | `AppState.bulkUpdate`, `WorkspaceSession.applyWithAutoValues` | `AppStateAutoValuesTest`・`AutoValuesKernelTest`・`SessionWriteParityTest` | [editing-history](architecture/editing-history.md) |
| X-03 | グラフレンズ（起点の近傍・型と関係の絞り込みと辺の一覧は右パネル・配置の切替・保有は箱の入れ子で畳める・一体と「側面で分ける」（箱＝出現。点線）・ホイール／ドラッグ／ホバー／右クリック／PNG／ミニマップ） | `ui/GraphLensView.kt`（`LensSidePanel`）, `ui/LensGraph.kt`, `ui/GraphLayouts.kt`（ELK を包む） | 単体 `LensGraphTest`, `GraphLayoutsTest` | [graph-query](architecture/graph-query.md) |
| X-11 | スキーマレンズ（「型」の段＝箱は型・線は関係の宣言と導出・入れ子は保有の宣言。箱を押すとスキーマへ・CLI `schema-graph`） | `semantic-core/semantic/SchemaGraph.kt`, `ui/LensGraph.kt`（`buildTypes`）, `core/Cli.kt` | 単体 `SemanticSchemaGraphTest`, `LensGraphTest`, `WorkspaceCliTest` | [graph-query](architecture/graph-query.md) |
| X-12 | レンズの見え方の定義（`_views` の `kind: lens`＝関係ごとの矢印の向き。表示だけ・レジストリに無い関係名はリント・関係の改名は警告で開示） | `semantic-core/semantic/LensViews.kt`, `workspace-core/workspace/ViewDefinitions.kt`（`parseLensView`）, `ui/LensGraph.kt` | 単体 `ViewDefinitionsTest`, `LensGraphTest` | [views](architecture/views.md) |
| X-04 | コマンドパレット（Ctrl+K・ID・ファイル・本文検索・ビュー切替・スペース切替・保存） | `Main.kt`（`CommandPalette`）, `app/AppState.kt`（`searchContent`） | 手動 | [ui](architecture/ui.md) |
| X-05 | ビュー定義 `_views`（`kind: grid` の行・列・並び〔出現順か `order.path` の値の順〕・詳細文書・新規行・バックリンク列・`kind: document`） | `core/Views.kt`, `workspace-core/workspace/ViewDefinitions.kt` | 単体 `ViewsTest` | [views](architecture/views.md) |
| X-06 | ビューグリッド（セルの直接編集・要約とジャンプ・enum と参照の併記・詳細文書の作成・新規行・バックリンク列・カバレッジ表示・絞り込み・列の桁区切り `display: grouped` と表示の形 `format`・ID 列と見出しの固定） | `ui/ViewGridPane.kt`, `ui/VirtualTable.kt`, `AppState.createViewDetail` / `addViewRow` | 単体 `ViewsTest`, `VirtualTableTest` | [views](architecture/views.md) |
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
| AG-05 | ツールセット（探索 5・編集 2・検証 1・対話 2・管理 3・許可ツール名と書込権限を実行時に強制） | `agent/Tools.kt` | 単体 `KernelTest`, `ToolsTest` | [agents](architecture/agents.md) |
| AG-06 | ステージング（複数文書のオーバーレイ・横断検証・未提出ドラフトの保全） | `agent/Staging.kt` | 単体 `StagingTest` | [agents](architecture/agents.md) |
| AG-07 | 変更セットレビュー UI（ファイル単位の部分採用・再検証・差分・適用・状態は `RunController`） | `ui/RunsSpace.kt`（`ReviewContent`, `RunController`） | 手動 | [agents](architecture/agents.md) |
| AG-08 | 実行中の対話（`ask_human` の質問カード・計画とメモの進捗・キャンセル） | `agent/Kernel.kt`, `ui/RunsSpace.kt`（`QuestionCard`） | 単体 `KernelTest` | [agents](architecture/agents.md) |
| AG-09 | 実行記録 `_runs`（ターン・ツール呼び出し・採否・トークン・会話記録） | `agent/RunLog.kt`, `agent/Kernel.kt` | 単体 `KernelTest`, `AutoApplySafetyTest` | [agents](architecture/agents.md) |
| AG-10 | CLI からの実行（`agent` / `activities`） | `core/Cli.kt` | 手動 | [agents](architecture/agents.md) |
| AG-11 | トリガ（manual / on-change / schedule / on-invalid・予約ディレクトリと自己変更では発火しない） | `agent/Automation.kt`（`tick`） | 単体 `AutomationTest` | [agents](architecture/agents.md) |
| AG-12 | アクティビティ連鎖（`chain` の成功・失敗・規則による分岐・深さ上限と循環検出） | `agent/Automation.kt`（`chainNext`）, `agent/Definitions.kt`（`ChainDef`） | 単体 `AutomationTest` | [agents](architecture/agents.md) |
| AG-13 | 無人リトライ（`retry.maxAttempts`・同一失敗の繰り返しは打ち切り） | `agent/Automation.kt`（`handleFailure`） | 単体 `AutomationTest` | [agents](architecture/agents.md) |
| AG-14 | 自動承認 `approval: auto`（条件を欠くと承認待ちへ降格・導出元文書への書込も降格・適用前バックアップ） | `agent/Automation.kt`, `agent/AutoApply.kt` | 単体 `AutomationTest`, `AutoApplySafetyTest` | [agents](architecture/agents.md) |
| AG-15 | 実行キューと停止（直列実行・常時停止・承認待ちの耐久性） | `agent/Automation.kt` | 単体 `AutomationTest` | [agents](architecture/agents.md) |
| AG-16 | 自動適用の巻き戻し（バックアップから復元・競合は拒否し `--force` で上書き） | `agent/AutoApply.kt`（`revert`） | 単体 `AutoApplySafetyTest` | [agents](architecture/agents.md) |
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
| C-17 | フロービューの解決・CLI・read_view | `semantic-core/semantic/FlowViews.kt`, `core/FlowReports.kt` | `FlowViewsTest`, `FlowViewDefinitionsTest`, `FlowViewsIntegrationTest` | [views](architecture/views.md) |
| C-18 | フローの配置切替・スイムレーン・文書埋め込み・原本フォームと履歴・改名影響 | `ui/GraphLayouts.kt`, `ui/FlowSwimlanes.kt`, `ui/FlowViewPane.kt` | `FlowLayoutsTest`, `FlowSelectionTest`, `NavigationHistoryTest`, `FlowRefactoringTest` | [views](architecture/views.md) |

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

## 11. 操作（型の操作とアクション）

| ID | 機能 | 実装 | テスト | 設計 |
|---|---|---|---|---|
| OP-01 | 型の操作（`methods`・作成・採番・引き継ぎ） | `semantic-core/schema/Action.kt`, `schema/SchemaTree.kt` | `ActionPlannerTest` | [actions](architecture/actions.md) |
| OP-02 | アクションの定義と点検 | `semantic-core/schema/Action.kt`, `workspace-core/workspace/SchemaDocuments.kt` | `ActionPlannerTest` | [actions](architecture/actions.md) |
| OP-03 | 操作の計画・前提評価・変更セット生成 | `workspace-core/workspace/ActionPlanner.kt` | `ActionPlannerTest` | [actions](architecture/actions.md) |
| OP-04 | CLI `action`（使える操作の一覧・計画＝変更セットと適用後の検証・`--apply`・`--allow-errors`） | `core/Cli.kt` | 単体 `ActionCliTest` | [actions](architecture/actions.md) |
| OP-05 | 操作の入口・フォームと横ぐしの対象引継ぎ・前提の無効化・確認と適用 | `ui/ActionPane.kt`, `ui/DocumentFormWorkspace.kt`, `AppState.kt` | `AppStateActionTest`・`FormOutlineTest` | [actions](architecture/actions.md) |
| OP-06 | 検査の指摘から対象・値を指定して起動 | `workspace-core/validate/WorkspaceChecks.kt`, `ui/SidePanel.kt` | `ActionPlannerTest`, `AppStateActionTest` | [actions](architecture/actions.md) |
| OP-07 | エージェントのツール（アクティビティの `actions` で許可した操作が `action_名前` のツールに・引数は受け取る値の定義・ステージングを重ねて計画し積む・`allowedFiles` の外でも通る） | `agent/Tools.kt`（`actionTools` / `runActionTool`）, `agent/Definitions.kt`, `builtin-schemas/activity.yaml` | 単体 `ActionToolTest` | [actions](architecture/actions.md) |
| OP-08 | 作成経路の寄せ（追加ボタン・新規行・文書の作成を型の `create` と配列の `insert` で。追加のパネルからアクションへ橋渡し） | `workspace-core/workspace/ActionPlanner.kt`, `workspace-core/workspace/DocumentScaffold.kt`, `app/AppState.kt`（`addArrayElement`） | 単体 `ActionPlannerTest`, `AppStateActionTest` | [actions](architecture/actions.md) |
| OP-09 | 現在値・本日の入力準備と変更分の送信 | `semantic-core/schema/ActionInput.kt`, `AppState.kt` | `ActionInputTest`, `AppStateActionInputTest` | [actions](architecture/actions.md) |

## 公開サンプル

[todos](../samples/todos/README.md)と[テーブルモデル](../samples/todos-sysdev-tablemodel/README.md): 操作・詳細化・SQL生成の検討。
確認: YDEの検証と各READMEの試用手順。
