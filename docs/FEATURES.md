# 機能索引

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/features-md.md -->

機能から実装ファイルとテストを引く索引。1 行 1 機能で、経緯は書かない（記録は [archive/](archive/README.md) と git log）。
利用方法は [manual/](manual/README.md)、設計は [architecture/](architecture/README.md) が正。「設計」列は設計文書の節（見出しまで指す。文書だけのものはその文書全体）、「操作の説明」列はその機能を説明する manual の見出し（機能を変えたらそこを直す。「—（内部）」は利用者が操作しない内部の基盤）。「未解決の指摘」はその機能に関わる [BACKLOG.md](BACKLOG.md) の指摘（着手前に読む。指摘の側の「関わる機能」から集める）。
**原本は [devtrack/](../devtrack/README.md) の `features/`**（1 機能 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

- **テスト**: 印の無い名前は単体テスト（代表の名前。`./gradlew test --tests '*名前*'` で絞って回せる）。`UI` は Compose の画面を組み立てるテスト（`./gradlew test` に含まれる）。`検査` は `./gradlew test` に含まれない別の検査。`手動` は手動確認だけ（画面は skill `ui-check`）。「ほか」は代表の名前だけを書いたもの。
- 未実装は [ROADMAP.md](ROADMAP.md)、既知の制約は [BACKLOG.md](BACKLOG.md) と manual/limits.mdに置く。
- **引き方**: 触るファイル名で grep すると「主な実装」の欄、テストのクラス名で grep すると「テスト」の欄に当たる。機能 ID は `| X-12 |` の形で、指摘の ID（UX-nnn）は「未解決の指摘」の欄で引ける。

| 接頭辞 | 節 | 範囲 |
|---|---|---|
| W | 1. ワークスペース・ファイル管理 | ワークスペースの選択と構造・ファイルツリー・外部変更・画面の枠・配布 |
| E | 2. 文書編集 | フォーム・ソース・差分・保存と Undo・入力欄 |
| S | 3. スキーマ | スキーマの読み込み・型・定義の部品・推測・派生・設計画面 |
| V | 4. 検証 | 構造と参照の検証・検査の定義と結果・準備完了 |
| I | 5. ID・参照 | ID の索引・参照・ジャンプ・エンティティページ・保有 |
| X | 6. ワークスペース横断ビュー | 一覧・ビュー（グリッド・文書・クロス集計・チャート）・レンズ・ダッシュボード・レポート・図の記法と配置 |
| A | 7. AI による作成支援 | フォームの AI 生成（項目・文書・たたき台）とプロバイダ |
| AG | 8. エージェント実行（アクティビティ） | エージェントとアクティビティの定義・実行・自動化・評価・文脈 |
| C | 9. コア API・CLI（エージェント基盤） | ヘッドレス API と CLI・変更セット・クエリ・合成・変換・リファクタリング・フロー |
| SK | 10. skill（フォーマット適合） | skill（取り込み・書き出し・コード実行） |
| OP | 11. 操作（型の操作とアクション） | 型の操作とアクション（計画・CLI・画面・エージェント） |
| LC | ローカル接続の基盤 | ローカル接続（API・MCP・Web・常駐・権限・資格情報） |

## 1. ワークスペース・ファイル管理

| ID | 機能 | 主な実装 | テスト | 設計 | 操作の説明 | 未解決の指摘 |
|---|---|---|---|---|---|---|
| W-01 | ワークスペース選択・履歴・開けない理由の通知と再試行 | `ui/shell/StartScreen.kt`・`WorkspaceOpenNotice.kt`・`ui/common/FilePickers.kt` | UI `WorkspaceOpenNoticeTest` | architecture/ui.md「UI シェル」 | manual/README.md「ビルドと起動」 |  |
| W-02 | ファイルツリー（全形式・隠し項目・除外・パス検索・展開の永続化・現在地） | `ui/file/FileTreePane.kt`・`core/FileCatalog.kt` | `FileCatalogTest`・手動（画面） | architecture/ui.md「ファイル一覧と参考資料」 | manual/README.md「ファイル一覧の表示と検索」 |  |
| W-03 | ファイル・フォルダーの配置変更と Undo | `FileArrangementPlanner`・`FileArrangementJournal`・`FileMoveDialog` | `AppStateFileArrangementTest`・`DocumentCacheSnapshotTest` ほか | architecture/workspace.md「ファイル配置の変更」 | manual/README.md「ファイルとフォルダーの整理」 |  |
| W-04 | 外部監視と再読込（走査と照合は IO・履歴保持・競合保護・確認付き破棄） | `session/DocumentSession.kt`（`reload`）・`session/ExternalCheck.kt`・`WorkspaceSession.refreshFrom`・`ReloadConfirmationDialog` | `ReloadHistoryTest`・`SessionRefreshTest`・`ExternalCheckTest`・`DesktopRuntimeTest` | architecture/workspace.md「再読込と履歴の保持」 | manual/README.md「外部変更・競合・安全性」 | UX-245・UX-305 |
| W-05 | 最近開いたファイル履歴（12 件・ワークスペース単位で永続化） | `AppState.recentFiles` | 手動 | [ui](architecture/ui.md) | manual/README.md「ビルドと起動」 |  |
| W-06 | UI レイアウトとテーマ（左右の領域アイコン・ペイン内の閉じる操作・幅と開閉とテーマの永続化） | `UiPreferences.kt`・`ui/shell/AppRoot.kt`・`ui/shell/AppToolbar.kt`・`ui/common/Common.kt` | 手動 | architecture/ui.md「UI シェル」 | manual/README.md「画面構成」・manual/README.md「テーマ」 |  |
| W-07 | 未保存確認（終了時・ワークスペース切替時） | `Main.kt` | 手動 | architecture/ui.md「UI シェル」 | manual/README.md「画面構成」 |  |
| W-09 | UI シェル（標準メニュー・移動と検索・タイトルのワークスペース絶対パス・レールで5スペースを切替） | `ui/shell/AppRoot.kt`・`ui/shell/AppToolbar.kt`・`ui/shell/Shell.kt` | `NavigationHistoryTest`・手動（画面） | architecture/ui.md「UI シェル」 | manual/README.md「画面構成」 | UX-193・UX-212 |
| W-10 | 受信箱（承認待ち変更セット・エージェントの質問・外部変更競合を実行スペースの先頭タブに集約・バッジと集計） | `ui/run/RunsSpace.kt`（`InboxTab`）・`ui/shell/AppRoot.kt`（`StatusBar`） | 手動 | architecture/ui.md「UI シェル」 | manual/ai.md「外部からの提出を受信箱で確認する」 | UX-52・UX-55 |
| W-11 | ワークスペース構造（`.yde/` 構成と `ledger` 宣言・レガシー構成は互換・配置判定は `WorkspaceLayout`・CLI `migrate`） | `workspace-core/layout/WorkspaceLayout.kt`・`core/Workspace.kt` | `WorkspaceLayoutTest`・`DocumentStoreTest` | architecture/workspace.md「ワークスペース構造と layout」 | manual/README.md「.yde 構成」 | UX-307 |
| W-12 | エディタ層と台帳層の分離（台帳外の YAML はフォームと単文書検証だけ・索引・集計・エージェントは台帳のみ） | `validate/Validator.kt`・`desktop/AppState.kt`（`editorOnlyFiles`） | `WorkspaceLayoutTest`・`DocumentStoreTest` | architecture/workspace.md「エディタ層と台帳層」 | manual/README.md「.yde 構成」 | UX-118 |
| W-13 | 同梱フォント（等幅 Cica をビルド時に取得して資源へ・`AppFonts.Mono`・資源が無ければ OS の等幅へ戻す） | `build.gradle.kts`（`downloadFonts`）・`ui/common/Common.kt`（`AppFonts`） | 手動 | [ui](architecture/ui.md) | manual/README.md「ビルドと起動」 | UX-148 |
| W-14 | Markdown の読取専用表示・相対リンク・外部変更の反映 | `ui/file/MarkdownPane.kt`・`ui/common/MarkdownView.kt` | `MarkdownLinkTest` | architecture/ui.md「ファイル一覧と参考資料」 | manual/README.md「画面構成」 | UX-149・UX-150 |
| W-15 | 内蔵ヘルプ（利用者マニュアルをビルド時に資源へ入れ、ヘルプメニューとコマンドパレットから文書として開く・題目間のリンクで移動） | `desktop/Help.kt`・`build.gradle.kts`（`copyHelp`）・`ui/file/MarkdownPane.kt` | `MarkdownLinkTest` | architecture/ui.md「UI シェル」 | manual/README.md「画面構成」 |  |
| W-16 | 画面の見え方の保持（タブ・畳み・選択・絞り込みは画面を離れても保持、ツリーの展開は永続化） | `desktop/ScreenMemory.kt` | `ScreenMemoryTest` | architecture/ui.md「UI シェル」 | manual/README.md「画面構成」 | UX-162・UX-173 |
| W-17 | Windows ZIP 配布（起動案内・サンプル・ビルド情報・SHA-256） | `build.gradle.kts`（`windowsRelease`） | 検査 `test-windows-zip.ps1`・検査 `DistributionSmoke` | [配布](releasing.md) | manual/README.md「ビルドと起動」 |  |
| W-18 | 複数選択・キー操作・ドラッグ（固定案内・移動先強調） | `ui/file/FileTreeSelection.kt`・`ui/file/FileTreePane.kt` | `FileTreeSelectionTest`・手動（画面） | architecture/ui.md「ファイル一覧と参考資料」 | manual/README.md「ファイルとフォルダーの整理」 |  |
| W-19 | 参考資料の本文検索（範囲・容量上限・中止・行へ移動） | `core/ReferenceSearch.kt` | `ReferenceToolsTest`・検査 `FileTreeBenchmark` | architecture/ui.md「ファイル一覧と参考資料」 | manual/README.md「参考資料の本文検索と構造」 |  |
| W-20 | ソースの宣言候補の表示と YAML 保存 | `core/SourceOutline.kt`・`ui/file/FilePreviewPane.kt` | `ReferenceToolsTest`・`AppStateFileArrangementTest` | architecture/ui.md「ファイル一覧と参考資料」 | manual/README.md「参考資料の本文検索と構造」 |  |
| W-21 | ワークスペースの初期化（ウィザード 4 歩・テンプレートから／自分の項目で／既存フォルダに後入れ・未初期化フォルダの案内・CLI `init`） | `ui/shell/InitWizard.kt`・`workspace-core/WorkspaceInit.kt`・`cli/InitCommands.kt` | `WorkspaceInitTest`・`WorkspaceTemplatesTest`・手動（画面） | architecture/workspace.md「ワークスペースの初期化」 | manual/README.md「新しいワークスペースを作る」 |  |
| W-22 | テンプレート（`templates/` を資源へ写し `WorkspaceTemplates` が読む・問いの答えは変換定義のひな型で組み立てる・全テンプレートの検証エラー 0 をテストで固定） | `templates/`・`workspace-core/WorkspaceTemplates.kt` | `WorkspaceInitTest`・`WorkspaceTemplatesTest` | architecture/workspace.md「ワークスペースの初期化」 | manual/README.md「新しいワークスペースを作る」 |  |

## 2. 文書編集

| ID | 機能 | 主な実装 | テスト | 設計 | 操作の説明 | 未解決の指摘 |
|---|---|---|---|---|---|---|
| E-01 | スキーマ駆動フォーム（基底の種類別の編集欄・型のヒント〔複数行・入力欄の案内・`format`〕・必須・説明は Markdown・id の補完と連番の提案・桁区切り `display: grouped`・残留値の修復） | `ui/form/FormFields.kt`・`ui/form/ScalarEditors.kt`（`IdEditor`）・`schema/IdSuggest.kt` | UI `FormViewUiTest`・`IdSuggestTest` | architecture/ui.md「フォームと FormCtx」 | manual/README.md「画面構成」・manual/schema.md「型一覧」 |  |
| E-02 | 汎用ツリーエディタ（スキーマなし文書・スキーマ外キー・順序とコメントを保ったキー改名） | `ui/form/GenericEditor.kt` | `RenameKeyTest` | architecture/ui.md「フォームと FormCtx」 | manual/README.md「操作の役割」 |  |
| E-03 | ソース編集（下書き・書式保持・履歴との同期） | `SourcePane`・`SourceHistorySync` | `LineMergeTest`・`SourceHistorySyncTest`・`TextOnlySaveTest` | architecture/editing-history.md「ソースとの同期」 | manual/README.md「操作の役割」 |  |
| E-04 | 差分表示（保存済みと編集中の行整列・文字単位の強調） | `ui/common/DiffPane.kt` | `DiffPaneTest` | [ui](architecture/ui.md) | manual/README.md「画面構成」 |  |
| E-05 | Undo/Redo（横断履歴・編集元への復帰・通知・入力の区切り） | `AppState`・`HistoryNoticeBanner` | `HistoryLocationTest`・`HistoryNoticeTest`・`HistoryCoalescingTest` ほか | architecture/editing-history.md「履歴と dirty」 | manual/README.md「操作の役割」 |  |
| E-06 | 保存（Ctrl+S・すべて保存・外部変更の直前検査と明示上書き） | `session/DocumentSession.kt`（`save*`）・`WorkspaceSession.save*` | `SaveConflictTest`・`YamlIOTest` | architecture/workspace.md「保存時の競合」 | manual/README.md「操作の役割」・manual/README.md「外部変更・競合・安全性」 | UX-168 |
| E-07 | 文書メタデータ `_document`（id・name・description）の編集とコンパクトな文書ヘッダー | `ui/form/FormView.kt`・`ui/shell/EditorChrome.kt` | 手動 | architecture/ui.md「フォームと FormCtx」 | manual/ids.md「ID の索引化」 |  |
| E-08 | 文書キャッシュ（ファイル切替時も未保存編集を保持） | `AppState.docCache`（殻）・`session/DocumentSession.kt`（論理） | 手動 | architecture/editing-history.md「編集の経路」 | manual/README.md「画面構成」 |  |
| E-09 | 同一 ID の他文書出現ストリップ（相手側の情報の表示とジャンプ） | `ui/form/FormView.kt`（`SameIdOccurrencesStrip`） | 手動 | architecture/ui.md「フォームと FormCtx」 | manual/ids.md「リンクの機能」 | UX-37 |
| E-10 | 複数ドキュメント YAML（フォームは先頭・後続は保持して再出力・ソースは全体を編集可） | `yaml-core/yaml/YamlIO.kt`（`parseAll` / `dumpAll`）・`app/OpenDocument` | `OpenDocumentTest` | architecture/yaml-model.md「複数ドキュメント」 | manual/README.md「操作の役割」 |  |
| E-11 | 畳み込みサマリ（畳んだ要素の 1 行要約・参照の値は参照先の名前を添える・選択肢は label・既定はルートから 2 階層まで展開） | `ui/form/CollectionEditors.kt`・`schema/Display.kt` | `DisplayTest`・`DefaultExpandTest` | architecture/ui.md「畳みとハイライト」 | manual/README.md「画面構成」 | UX-161 |
| E-12 | 閲覧専用モード（`FormCtx.readOnly`・入力欄はテキスト表示・書込は拒否・横ぐしとグリッドへ波及） | `ui/form/FormCtx.kt`・`desktop/AppState.readOnlyMode` | `ReadOnlyTest` | architecture/ui.md「閲覧専用」 | manual/README.md「画面構成」 |  |
| E-13 | 配列と map のテーブル表示（自動判定と `display: table / list`・セル編集・展開フォーム・列幅・行の操作は取っ手と ⋯・参照セルは名前つき） | `ui/form/FormTables.kt`（`ObjectTable`・`ArrayRowMenu`・`RefCell`）・`ui/common/ColumnWidths.kt`・`schema/Display.kt`（`tableColumns`） | `DisplayTest`・`ArrayTableTest`・`ColumnWidthsTest`・`RefTargetTitleTest` | architecture/ui.md「テーブル表示」 | manual/README.md「画面構成」 |  |
| E-14 | 保存時に自動で入る値（印 `auto`＝更新日・作成日・版。保存の直前の 1 トランザクション・変更セットの適用にも） | `semantic-core/semantic/AutoValues.kt`・`workspace-core/workspace/WorkspaceSession.kt` | `SemanticAutoValuesTest`・`AutoValuesKernelTest`・`AppStateAutoValuesTest` | [auto-values](architecture/auto-values.md) | manual/schema.md「保存時に自動で入る値」 | UX-140 |
| E-15 | 日付・時刻・日時のピッカー（入力欄の右のボタンからポップアップにカレンダーと時刻入力・日付はクリックで即反映・値は正準の文字列のまま） | `ui/form/TemporalEditor.kt`（`TemporalPick`, `TemporalPickerButton`） | `TemporalPickTest` | architecture/ui.md「フォームと FormCtx」 | manual/README.md「画面構成」 | UX-151 |
| E-16 | 配列要素のドラッグ並べ替え（取っ手 ⋮⋮ からだけ掴める・ドロップで 1 回だけ木を動かす＝Undo 1 回・縦リスト／テーブル／汎用エディタ共通・上下ボタンも残す） | `ui/common/Reorder.kt`（`ReorderableList`）・`ui/form/FormTables.kt`（`moveSeqItem`） | `MoveSeqItemTest` | architecture/ui.md「テーブル表示」 | manual/README.md「画面構成」 |  |
| E-17 | file 型の入力欄と画像の表示（3 通りの書き方・「選ぶ」は OS のダイアログ・解決した場所と有無・画像は欄の下に表示し拡大とドラッグ・「拡大表示」で全面） | `ui/file/FileEditor.kt`・`ui/file/ImagePane.kt`・`AppState.openImage` | `FileEditorTest`・UI `ImageCanvasUiTest` | architecture/ui.md「フォームと FormCtx」 | manual/files.md「フォームでの操作」・manual/files.md「画像の表示」 | UX-152・UX-154・UX-155 |
| E-18 | フォームの複合値は全幅配置と上限付き字下げ・単一値は幅に応じた配置・文書情報と他文書の定義は開閉可能 | `ui/form/FormLayout.kt`・`ui/form/FormFields.kt` | `FormLayoutTest`・`ScreenMemoryTest`・手動（画面） | architecture/ui.md「フォームと FormCtx」 | manual/README.md「画面構成」 |  |
| E-19 | 項目説明の短い表示・項目名とⓘのホバーで小さな予告・説明ボタンで全文固定（編集・閲覧共通）・説明の山括弧は文字のまま | `ui/common/FieldHelp.kt`・`ui/common/MarkdownView.kt`（`literalAngles`） | `FieldHelpPositionTest`・`LiteralAnglesTest`・手動（画面） | architecture/ui-interactions.md「項目説明の予告と全文」 | manual/README.md「画面構成」 |  |
| E-20 | フォームの構成ツリーと三つの表示方式（選択項目・全体連動・全体のみ） | `ui/form/StructuredForm.kt`・`ui/form/DocumentFormWorkspace.kt` | `FormOutlineTest`・`FormPositionsTest`・`ScreenMemoryTest`・手動（画面） | architecture/ui.md「構成ツリーとフォーム表示」 | manual/README.md「フォームの表示方式」 |  |
| E-21 | 表示方式のアイコン操作・固定／可変・ファイルの明示指定と確認・構成ペインの× | `desktop/FormDisplayMemory.kt`・`ui/form/FormDisplayControls.kt` | `FormDisplayMemoryTest`・手動（画面） | architecture/ui.md「構成ツリーとフォーム表示」 | manual/README.md「フォームの表示方式」 |  |
| E-22 | フォーム入力の Esc で内容を保持してフォーカス解除（IME・候補を優先） | `ui/common/Common.kt`・`ui/common/TextFieldEscape.kt` | `TextFieldEscapeTest`・手動（画面） | architecture/ui-interactions.md「フォーム入力からの退出」 | manual/README.md「画面構成」 | UX-264 |
| E-23 | 選択一覧の現在値・高さ制限・説明全文・検索 | `ui/common/ChoiceMenu.kt`・`ChoiceOptions.kt` | `ChoiceOptionsTest`・手動（長い選択一覧） | architecture/ui-interactions.md「選択一覧の現在値」 | manual/README.md「画面構成」 |  |
| E-24 | ソースの構文状態（固定高の正常・確認中・エラー表示、全文・コピー・行移動の詳細ポップアップ） | `ui/source/SourceSyntaxBar.kt` | `SourceSyntaxBarTest`・手動 | architecture/editing-history.md「ソースとの同期」 | manual/README.md「画面構成」 |  |
| E-25 | 配列行の削除確認・対象変更の拒否（リスト・表・汎用・設計の配列入力） | `ui/form/ArrayDeleteButton.kt`・`ui/form/ArrayDeletion.kt` | `ArrayDeletionTest`・手動（画面） | architecture/ui-interactions.md「配列要素の削除」 | manual/README.md「画面構成」 | UX-189 |
| E-26 | ソースの基本操作（検索・置換・行へ移動 Ctrl+F / H / G・F3・Aa と .*・自動インデント・Tab / Shift+Tab・Ctrl+/ コメント・Alt+↑↓ 行の移動・Ctrl+D 複製・現在行の強調） | `ui/source/SourceEditing.kt`・`ui/source/SourcePane.kt` | `SourceEditingTest` | architecture/editing-history.md「ソースとの同期」 | manual/README.md「操作の役割」・manual/README.md「ショートカット」 |  |
| E-27 | ソースでスキーマと索引を使う（指摘を左端の印と値の色で出し右パネルの指摘から行へ、Ctrl+Space でキー名・ID・enum・file の補完、ホバーで項目の説明、Ctrl+クリックで参照先へ） | `ui/source/SourceContext.kt`・`ui/source/SourceCompletion.kt`・`yaml-core/yaml/YamlPositions.kt` | `SourceCompletionTest` ほか | architecture/editing-history.md「ソースとの同期」 | manual/README.md「操作の役割」 |  |
| E-28 | ソースの構造（入れ子単位の折り畳み〔行番号の ▾・Ctrl+Shift+[〕・保存済みとの差分の印・フォームとソースの位置の行き来・選択範囲だけの整形） | `ui/source/SourceStructure.kt`・`ui/source/SourcePane.kt` | `SourceStructureTest`・`YPathTest` | architecture/editing-history.md「ソースとの同期」 | manual/README.md「操作の役割」 |  |
| E-29 | 編集画面のタブ（フォーム・ビュー・ソース・差分。ビューは当たる文書ビューを ▾ で選ぶ・当たらなければ押せない・開いたときのタブは直前のタブから） | `desktop/EditorTab.kt`・`ui/shell/EditorChrome.kt`（`EditorModeBar`）・`AppState`（`showFile`・`docViewsFor`・`landOnForm`） | `EditorTabTest`・`NavigationHistoryTest` | architecture/ui.md「UI シェル」 | manual/README.md「画面構成」 |  |

## 3. スキーマ

| ID | 機能 | 主な実装 | テスト | 設計 | 操作の説明 | 未解決の指摘 |
|---|---|---|---|---|---|---|
| S-01 | スキーマ読込（`match` glob 先勝ち・id 重複検出・読むのはカーネルの経路だけ・app-core の `SchemaDef` は薄い型） | `workspace-core/workspace/SchemaDocuments.kt`・`WorkspaceReader.kt`・`schema/Schema.kt` | `SchemaLoaderTest`・`KernelCheckLoadingTest` | architecture/schema.md「スキーマの読み込み」 | manual/schema.md「スキーマ定義リファレンス」 |  |
| S-02 | 型システム（基底の種類 14 種＝表現の基底 9 と意味の基底型 `id` / `ref` / `date` / `time` / `datetime`・組込みの定義体 `text` / `line` / `word`） | `semantic-core/schema/Fields.kt`・`ValueSemantics.kt`・`BuiltinTypes.kt` | `MetaSchemaTest`・`TypeDefinitionsTest` | architecture/schema.md「型の連鎖と狭める規則」 | manual/schema.md「型一覧」 |  |
| S-03 | 名前付き定義と再帰参照（`definitions` / `$ref`・狭める規則の合成） | `semantic-core/schema/SchemaTree.kt`（`resolve`） | `TypeDefinitionsTest` | architecture/schema.md「$ref と実効フィールド」 | manual/schema.md「名前付き定義と再帰スキーマ」 |  |
| S-04 | メタスキーマ検証と宣言のない関係名の点検（実体はカーネル・読み込みエラーへ足す） | `workspace-core/workspace/SchemaDefinitionChecks.kt` | `SchemaLoaderTest`・`KernelOnlyChecksTest` | architecture/schema.md「スキーマの読み込み」 | manual/schema.md「メタスキーマ」 | UX-127・UX-181 |
| S-05 | スキーマ設計（三方式・型・必須・表示・フェーズ・同一性・プレビュー） | `ui/schema/SchemaEditor.kt`・`ui/schema/SchemaDesignWorkspace.kt` | `SchemaOutlineTest`・手動 | architecture/ui.md「スキーマ設計」 | manual/schema-editor.md「スキーマ設計画面」 | UX-32・UX-89 |
| S-06 | サンプル YAML 生成（プレビュー・コピー・新規ファイルの初期値・必須スカラーに型別の例値） | `semantic-core/schema/SchemaScaffold.kt` | `SchemaLoaderTest`・`DocumentScaffoldTest` | architecture/schema.md「ひな型」 | manual/schema-editor.md「検証とプレビュー」 |  |
| S-07 | スキーマ外キーの検証ポリシー（`unknownFields: allow / warning / error`） | `validate/Validator.kt`・`index/IdIndex.kt` | 手動 | architecture/schema.md「構造スキーマの導出」 | manual/schema.md「フィールド定義のプロパティ」 |  |
| S-08 | 参照の関係種別（`type: ref` の `relation`） | `schema/Schema.kt`・`index/IdIndex.kt` | `WorkspaceCliTest` | architecture/graph-query.md「エンティティ型と関係レジストリ」 | manual/checks.md「関係レジストリ」 |  |
| S-09 | カバレッジルール（`coverage` の `detailIn`＝詳細文書の存在。定義体で書き、配り方はカーネル） | `builtin-definitions/coverage.yaml`・`workspace-core/validate/WorkspaceChecks.kt` | `CoverageTest`・`CoverageMessageTest` | architecture/checks.md「検査の書き方」 | manual/schema.md「カバレッジルール」 | UX-17 |
| S-10 | enum 値ごとの追加フィールド（`cases`・多段展開・フォーム・検証・索引・AI 生成・設計 UI） | `schema/Schema.kt`（`effectiveFields`）・`ui/form/FormFields.kt` | `SchemaLoaderTest`・`ValidatorTest` | [schema](architecture/schema.md) | manual/schema.md「enum 値ごとの追加フィールド」 |  |
| S-11 | フェーズ機構（`phases`・`requiredFrom` の累積必須・`activeFrom` の判定保留） | `schema/Schema.kt`（`PhasesDef`, `requiredAt`） | `PhaseTest` | architecture/schema.md「フェーズ」 | manual/schema.md「フェーズ機構」 |  |
| S-12 | スキーマ共通部品（`kind: library` と `moduleId#definition` の修飾 `$ref`・二段階ロード） | `schema/Schema.kt`（`SchemaCatalog`） | `SchemaLoaderTest` | architecture/schema.md「$ref と実効フィールド」 | manual/schema.md「名前付き定義と再帰スキーマ」 |  |
| S-13 | 表示解決（1 行要約・`role: title / subtitle` ＞ 予約語 ＞ id・同一 ID の他出現からタイトルを借りる） | `schema/Display.kt`・`index/IdIndex.kt`（`DocScanner`） | `DisplayTest` | architecture/index-references.md「表示解決」 | manual/ids.md「表示解決」 | UX-161 |
| S-14 | enum のリッチ選択肢（`{value, label, description}`・label は表示専用） | `schema/Schema.kt`（`EnumOption`）・`ui/form/ScalarEditors.kt`（`EnumEditor`） | `DisplayTest` | architecture/schema.md「表示のヒント」 | manual/schema.md「enum のリッチ選択肢」 |  |
| S-15 | 組込みメタスキーマ（`_schemas` 文書に適用・一般スキーマの match は及ばない） | `schema/BuiltinSchemas.kt`・`core/Workspace.kt`（`schemaFor`） | `MetaSchemaTest` | architecture/schema.md「推測・メタスキーマ・派生」 | manual/schema.md「メタスキーマ」 | UX-297・UX-298 |
| S-16 | スキーマ推測（既存 YAML から型・参照・enum・必須を推測してドラフト生成・CLI `infer-schema`） | `schema/SchemaInference.kt`・`session/DocumentSession.kt`（`inferSchema`） | `SchemaInferenceTest` | architecture/schema.md「推測・メタスキーマ・派生」 | manual/schema.md「スキーマ推測」 | UX-30 |
| S-17 | 派生スキーマ（`derive:` で導出元文書から検証スキーマをコンパイル・導出元の変更は全再構築） | `semantic-core/schema/SchemaDerivation.kt`・`core/Workspace.kt` | `DerivedSchemasTest` | architecture/schema.md「推測・メタスキーマ・派生」 | manual/schema.md「派生スキーマ」 | UX-139 |
| S-18 | 制約語彙（`minLength` / `maxLength` / `pattern` / `min` / `max`・AI 生成の JSON Schema へ伝搬） | `schema/Schema.kt`・`yaml-core/structure/StructureValidator.kt` | `ConstraintTest` | architecture/schema.md「型の連鎖と狭める規則」 | manual/schema.md「フィールド定義のプロパティ」 |  |
| S-19 | エンティティ型宣言（`entity:` の `idPattern` / `idType`＝ID の形で判別・object の `entity`＝要素の形で判別・`defines`） | `semantic-core/schema/Relations.kt`（`EntityDecl`）・`index/IdIndex.kt` | `EntityDeclTest`・`DocScannerTest`・`TypeDefinitionsTest` | architecture/graph-query.md「エンティティ型と関係レジストリ」 | manual/checks.md「エンティティ型宣言」 |  |
| S-20 | 関係レジストリ（`kind: relations` の関係語彙・label／inverseLabel・`enforcement`・CLI `infer-relations`） | `semantic-core/schema/Relations.kt`（`RelationDef`）・`core/Workspace.kt` | `RelationRegistryTest` | architecture/graph-query.md「エンティティ型と関係レジストリ」 | manual/checks.md「関係レジストリ」 |  |
| S-21 | 属性付き参照（`type: ref` に `fields:` があるときだけ `{to, 属性...}` を許可・to の座標は従来と同じ） | `index/IdIndex.kt`（`RefEntry.attributes`）・`ui/form/RefEditors.kt`（`RefEditor`） | `DocScannerTest`・`ValidatorTest` | architecture/graph-query.md「エンティティ型と関係レジストリ」 | manual/checks.md「属性付き参照」 |  |
| S-22 | アスペクト宣言とエンティティ型レジストリ（`aspect` / `owns`・レジストリの `entities:` 節） | `semantic-core/schema/Relations.kt`（`EntityTypeDef`, `ownedFieldKeys`） | `AspectTest` | architecture/graph-query.md「エンティティ型と関係レジストリ」 | manual/checks.md「エンティティ型宣言」 | UX-217 |
| S-23 | スキーマの値に式（項目の印 `expression: true`・共通の読み取りと評価と文面・`filePattern` と `scaffold`） | `semantic-core/schema/ExpressionFields.kt`・`semantic-core/semantic/Values.kt` | `ExpressionFieldsTest`・`SemanticValuesTest` | architecture/schema.md「値の式」 | manual/schema.md「新しい文書の置き場所とひな型」 | UX-290・UX-292 |
| S-24 | ユーザ定義の型（`type: 名前`・型の連鎖・狭める規則・メタスキーマへの型名の合成・オブジェクト型＝object の `entity`・項目を足す／狭める・要素ごとの型の検査） | `semantic-core/schema/SchemaTree.kt`・`semantic/ElementChecks.kt`・`workspace-core/workspace/MetaSchemaTypes.kt` | `TypeDefinitionsTest`・`ObjectTypesTest` | architecture/schema.md「型の連鎖と狭める規則」 | manual/schema.md「型の定義」・manual/schema.md「オブジェクト型」 |  |
| S-25 | ファイルの場所の型（`type: file`・接頭辞なし＝ルートから・`./` `../`＝文書から・絶対の 3 通り・`extensions` で拡張子を絞る・値は文字列のまま） | `semantic-core/schema/FilePath.kt`・`schema/Fields.kt`（`FieldType.FILE`, `extensions`）・`builtin-schemas/schema.yaml` | `FilePathTest` | architecture/schema.md「規律」 | manual/files.md「書き方」 | UX-152 |
| S-26 | 設計画面の補完・操作入力・採番・子項目の構成 | `ui/schema/SchemaAdditionalOutline.kt` | `SchemaAdditionalOutlineTest`・`SchemaAuthoringTest` | architecture/ui.md「スキーマ設計」 | manual/schema-editor.md「スキーマ設計画面」 |  |
| S-27 | 条件・手順・式の構造編集と追従検査 | `ui/schema/SchemaValueEditor.kt` | `SchemaValueFormsTest`・`SchemaDefinitionCoverageTest`・`SchemaAuthoringHistoryTest` | architecture/ui.md「スキーマ設計」 | manual/schema-editor.md「条件と処理手順の入力」 |  |
| S-28 | 業務 State / Lifecycle 宣言と静的完全性チェック（behavior） | `semantic-core/schema/Behavior.kt`・`workspace-core/workspace/BehaviorChecks.kt`・`workspace-core/workspace/SchemaDocuments.kt` | `BehaviorChecksTest` | architecture/schema.md「業務上の State / Lifecycle」 | manual/schema.md「業務の State と Lifecycle」 | UX-288 |
| S-29 | 業務 State の実評価と Action / Lifecycle 前後照合 | `workspace-core/workspace/BehaviorRuntime.kt`・`app-core/session/DocumentSession.kt`・`app-core/service/WorkspaceOperations.kt`・`desktop/ui/action/ActionPane.kt` | `BehaviorRuntimeTest` | architecture/schema.md「State の実評価と Action の遷移照合」 | manual/schema.md「実データの State と Action の遷移確認」 |  |
| S-30 | 参照・保有関係を介する State の間接変化の検証 | `workspace-core/workspace/BehaviorImpact.kt`・`workspace-core/workspace/BehaviorRuntime.kt`・`workspace-core/workspace/BehaviorChecks.kt`・`desktop/ui/action/ActionPane.kt` | `BehaviorImpactTest` | architecture/schema.md「関係を介する State の間接変化」 | manual/schema.md「関係先の State への波及」 |  |
| S-31 | Event の種類の宣言と Lifecycle の Event 原因の検証 | `semantic-core/schema/Behavior.kt`・`workspace-core/workspace/SchemaDocuments.kt`・`workspace-core/workspace/BehaviorChecks.kt`・`workspace-core/workspace/BehaviorRuntime.kt` | `BehaviorEventTest` | architecture/schema.md「Event の種類と Lifecycle 前後差分の照合」 | manual/schema.md「Event を原因とする State 遷移の検査」 |  |
| S-32 | Event 発生申告の記録と変更候補の Lifecycle 照合 | `workspace-core/workspace/BehaviorEventJournal.kt`・`workspace-core/workspace/BehaviorRuntime.kt`・`cli/commands/EventCommands.kt` | `BehaviorEventTest`・`EventCliTest`・`CliOwnershipTest` | architecture/schema.md「Event 発生申告と記録」 | manual/schema.md「Event の申告・記録（CLI）」 |  |
| S-33 | 業務仕様の静的診断レポート（State / Event / Lifecycle） | `workspace-core/workspace/BehaviorAudit.kt`・`cli/commands/BehaviorAuditCommands.kt` | `BehaviorAuditTest`・`BehaviorAuditCliTest`・`CliOwnershipTest` | architecture/schema.md「業務仕様の診断レポート」 | manual/schema.md「業務仕様の接続と未証明範囲の確認」 |  |
| S-34 | 業務シナリオの State 遷移検証と未実行 Lifecycle 遷移の表示 | `workspace-core/workspace/BehaviorScenarios.kt`・`cli/commands/BehaviorScenarioCommands.kt` | `BehaviorScenariosTest`・`BehaviorScenarioCliTest` | architecture/schema.md「具体例による State 遷移の検証」 | manual/schema.md「具体的な業務シナリオで遷移を検証する」 | UX-289 |

## 4. 検証

| ID | 機能 | 主な実装 | テスト | 設計 | 操作の説明 | 未解決の指摘 |
|---|---|---|---|---|---|---|
| V-01 | 構造の検証（必須・型・enum・date・case キー・利用者のスキーマから導出した最小の構造スキーマで行う） | `yaml-core/structure/StructureValidator.kt`・`workspace-core/workspace/DerivedStructures.kt` | `StructureValidatorTest`・`ValidatorTest` | architecture/schema.md「構造スキーマの導出」 | manual/checks.md「検証パネル」 |  |
| V-02 | 参照解決の検証（未解決・パターンやスコープの対象外・未保存 ID の考慮） | `validate/Validator.kt` | 手動 | architecture/checks.md「文書 1 つの検証」 | manual/ids.md「参照（リンク）の認識」 |  |
| V-03 | ID 重複検出（同一文書内のみエラー・文書間は段階的詳細化） | `validate/Validator.kt` | 手動 | architecture/checks.md「文書 1 つの検証」 | manual/ids.md「段階的詳細化の規約」 |  |
| V-04 | 検証パネル（この文書／全体のスコープ切替・指摘のクリックは行き先の選択メニュー・スキーマ定義エラーとフェーズ進捗も表示） | `ui/shell/SidePanel.kt`・`session/DocumentSession.kt`（`validateWorkspace`） | 手動 | [ui](architecture/ui.md) | manual/checks.md「検証パネル」 | UX-04・UX-07・UX-101・UX-32・UX-99 |
| V-05 | カバレッジ検証（詳細文書の存在・組込み定義体・作成場所の併記と「詳細文書を作成」） | `builtin-definitions/coverage.yaml`・`workspace-core/workspace/DocumentScaffold.kt` | `CoverageTest`・`CoverageMessageTest` | architecture/checks.md「定義層の 3 段」 | manual/schema.md「カバレッジルール」 | UX-17・UX-72 |
| V-06 | フェーズ考慮の検証（`requiredFrom` は到達フェーズから・`activeFrom` は到達まで保留・フェーズ別進捗） | `validate/Validator.kt`（`phaseProgress`） | `PhaseTest` | architecture/schema.md「フェーズ」 | manual/schema.md「フェーズ機構」 |  |
| V-07 | 整合検証（`consistency`・導出した期待値と `assert` の関係・組込み定義体・来歴つきの指摘と「導出値を適用」） | `builtin-definitions/consistency.yaml`・`workspace-core/validate/WorkspaceChecks.kt` | `ConsistencyTest` | architecture/checks.md「定義層の 3 段」 | manual/checks.md「整合ルール」 |  |
| V-08 | グラフ整合検証（対象型・多重度・非循環・強制スイッチ・いずれも組込み定義体・「参照の追加先を開く」） | `builtin-definitions/relations.yaml`・`semantic-core/schema/Relations.kt`（`EnforcementDef`） | `GraphIntegrityTest` | architecture/checks.md「定義層の 3 段」 | manual/checks.md「関係レジストリ」 | UX-101 |
| V-09 | パターン制約（`constraints`・`match` / `also` / `where` / `expect` / `count` / `same` / `check`・否定と「または」・値の式・`repeat`・`forAll`） | `semantic-core/schema/QuerySpec.kt`・`semantic/SemanticQuery.kt` | `QueryConstraintTest`・`CheckConstraintTest`・`TypeDefinitionsTest` | architecture/checks.md「検査の書き方」 | manual/checks.md「パターン制約」 | UX-277・UX-283・UX-284・UX-286 |
| V-10 | 重複所有の検証（同一エンティティの複数出現が同じフィールドを所有したら警告・双方に entity 宣言と `owns` があるときだけ） | `workspace-core/validate/CodedDocumentChecks.kt` | `AspectTest`・`KernelOnlyChecksTest` | architecture/checks.md「文書 1 つの検証」 | manual/checks.md「エンティティ型宣言」 |  |
| V-11 | 網羅マトリクス（`matrix`・行 × 列の全組合せに証人があること・組込み定義体で `forAll` へ展開） | `builtin-definitions/matrix.yaml` | `MatrixTest` | architecture/checks.md「検査の書き方」 | manual/checks.md「網羅マトリクス」 |  |
| V-12 | 遷移検証（`flow`・到達不能・行き止まり・宣言外遷移・到達の計算は専用チェッカーで文面と重大度は定義体） | `semantic-core/semantic/Flow.kt`・`builtin-definitions/flow.yaml` | `FlowTest` | architecture/checks.md「専用チェッカー」 | manual/checks.md「遷移検証」 | UX-110・UX-111 |
| V-13 | 準備完了レポート（CLI `readiness`・指摘をエンティティへ帰属しエラー 0 で準備完了・型別とフェーズ別の集計） | `validate/Readiness.kt`・`cli/ValidateCommands.kt` | `ReadinessTest` | architecture/checks.md「準備完了レポート」 | manual/cli.md「コマンド例」 | UX-206 |
| V-14 | 検査の定義と語彙の定義（`constraints` の `use` ＋ `with`・`functions`・`vocabulary`・組込みは特権を持たない・CLI `checks`） | `semantic-core/schema/CheckDefinitions.kt`・`workspace-core/workspace/CheckCatalog.kt` | `CheckDefinitionsTest`・`CheckDefinitionTest` | architecture/checks.md「定義層の 3 段」 | manual/checks.md「検査の定義と語彙の定義」 | UX-111 |
| V-15 | 検査の結果の 4 つと指摘の出どころ（違反・充足・保留・対象外・`IssueSource`・保留は `withheld`・CLI `validate --sources`） | `semantic-core/semantic/CheckResults.kt`・`workspace-core/validate/CodedChecks.kt` | `SemanticRulesTest`・`CheckOutcomeTest` | architecture/checks.md「4 つの結果」 | manual/checks.md「パターン制約」・manual/cli.md「コマンド例」 |  |
| V-16 | クエリの線の意味（参照を書いた要素は最も内側の ID を持つ要素・入れ子の線 `contains`・`via`・名前を書いた線は保有の線も辿る） | `semantic-core/semantic/Graph.kt`（`ownRefs`）・`semantic-core/semantic/SemanticQuery.kt`（`holdingSteps`） | `QueryNestingTest`・`PreviewValidationTest` | architecture/graph-query.md「線の意味」 | manual/checks.md「パターン制約」・manual/ids.md「保有と所属」 |  |
| V-17 | 規模の計測（CLI `bench`・合成ワークスペースで開く・検証・レポートの時間と伸び方を出す） | `core/Bench.kt`・`cli/BenchCommand.kt` | `BenchTest` | [checks](architecture/checks.md) | manual/cli.md「コマンド例」 |  |
| V-18 | 検査の評価の共有（同じ索引の上では検査 × 要素を 1 回だけ評価・全体の検証が文書数に比例） | `semantic-core/semantic/CheckSession.kt`・`workspace-core/workspace/WorkspaceCheckRunner.kt` | `KernelCheckLoadingTest`・`BenchTest` | architecture/checks.md「評価は 1 回」 | —（内部） |  |
| V-19 | コードで実装された検査（ID パターンからの逸脱・重複所有・テンプレートの健全性・カーネルが配る） | `workspace-core/validate/CodedDocumentChecks.kt`・`workspace-core/workspace/SchemaDefinitionChecks.kt` | `KernelOnlyChecksTest` | architecture/checks.md「配り方」 | —（内部） |  |
| V-20 | 文書 1 つの検証の組み立て（構造 → ID と参照 → エンティティ型 → 関係名 → 定義体 → テンプレート → 案内・台帳外の降格と重複除去） | `workspace-core/validate/DocumentValidator.kt` | `KernelCheckLoadingTest`・`KernelOnlyChecksTest` | architecture/checks.md「文書 1 つの検証」 | —（内部） |  |
| V-21 | 準備完了レポートの画面（台帳の「準備」タブ・件数のタイル・型別とフェーズ別の積み上げ棒・エンティティの表から横ぐしへ・保存済みの内容で判定） | `ui/view/ReadinessPane.kt`・`ui/view/Charts.kt`（`BarChart`）・`AppState.readinessReport` | `ReadinessChartTest` | [ui](architecture/ui.md) | manual/views.md「ビュー」 |  |
| V-22 | file 型の値の検査（書き方・拡張子・ワークスペースの外・無いファイル。扱いは `.yde/workspace.yaml` の `files:`） | `workspace-core/validate/CodedDocumentChecks.kt`（`fileIssues`）・`workspace-core/layout/FilePolicy.kt` | `FileChecksTest` | architecture/checks.md「文書 1 つの検証」 | manual/files.md「検査」 |  |

## 5. ID・参照

| ID | 機能 | 主な実装 | テスト | 設計 | 操作の説明 | 未解決の指摘 |
|---|---|---|---|---|---|---|
| I-01 | ID 索引（ディスク基準・`type: id` とキー名 `id`・変更ファイルだけの増分更新） | `index/IdIndex.kt`（`DocScanner`, `update`） | `IdIndexIncrementalTest` | architecture/index-references.md「索引の二層」 | manual/ids.md「ID の索引化」 |  |
| I-02 | 参照認識（`type: ref` と命名規約） | `index/IdIndex.kt`（`DocScanner`） | 手動 | architecture/index-references.md「ID と参照の意味」 | manual/ids.md「参照（リンク）の認識」 |  |
| I-03 | リンクジャンプ（→ボタン・ハイライト・複数出現の選択） | `AppState.navigateToId`・`ui/form/StructuredForm.kt`（`Highlightable`） | 手動 | architecture/ui.md「畳みとハイライト」 | manual/ids.md「リンクの機能」 |  |
| I-04 | 参照候補の補完（`idPatterns` / `scope` で絞り込み・解決先タイトルの併記・行き先の選択メニュー） | `ui/form/RefEditors.kt`（`RefEditor`） | 手動 | architecture/ui.md「フォームと FormCtx」 | manual/ids.md「参照候補の絞り込み」 | UX-183 |
| I-05 | バックリンク（被参照一覧・参照元の ID とタイトル） | `IdIndex.backlinksTo`・`ui/shell/SidePanel.kt` | 手動 | architecture/index-references.md「ID と参照の意味」 | manual/ids.md「リンクの機能」 |  |
| I-06 | ID 検索（コマンドパレット・ID とタイトルの部分一致・オーバーレイ索引） | `ui/shell/CommandPalette.kt` | 手動 | architecture/ui.md「UI シェル」 | manual/ids.md「リンクの機能」 |  |
| I-07 | エンティティページ（合成全体像・関係パネル・カバレッジ・改名／統合／削除・グラフレンズへ。入口は `openCrossView` の 1 経路） | `ui/graph/IdCrossView.kt`・`core/Aspects.kt`・`AppState.openCrossView` | `AspectTest`（合成）・手動 | architecture/graph-query.md「エンティティページ」 | manual/views.md「ビュー」・manual/README.md「画面構成」 | UX-217・UX-65 |
| I-08 | ナビゲーション履歴（戻る・進む・スペースとタブと ID とビューとハイライトとエディタのタブを復元・ショートカットはウィンドウで受ける） | `AppState.goBack` / `goForward`・`Main.kt`（`handleShortcut`） | `NavigationHistoryTest`・手動（画面） | architecture/ui.md「UI シェル」 | manual/README.md「画面構成」 |  |
| I-09 | ID インスペクタ（右パネルの検証・関係・出現の 3 タブ） | `ui/shell/SidePanel.kt` | 手動 | architecture/ui.md「UI シェル」 | manual/README.md「画面構成」 | UX-121・UX-88 |
| I-10 | 導出グラフ（走査結果から導出する読み取り専用のエンティティグラフ・索引の更新で無効化） | `semantic-core/semantic/`（`SemanticEntityGraph`）・`index/IdIndex.kt`（`graph`） | `EntityGraphTest` | architecture/graph-query.md「導出グラフ」 | —（内部） |  |
| I-11 | 文書参照（`target: document`・文書ノードへの参照・エンティティ参照とは混ぜない） | `index/IdIndex.kt`（`RefEntry.targetKind`）・`ui/form/RefEditors.kt`（`DocRefScalarEditor`） | `DocRefTest` | architecture/graph-query.md「エンティティ型と関係レジストリ」 | manual/checks.md「文書参照」 |  |
| I-12 | 場所は文書 ID（索引・検証・ビュー・クエリの場所はワークスペース相対の `DocumentId`・app-core の型はカーネルの別名・編集用セッションは `fork`） | `index/IdIndex.kt`・`workspace-core/workspace/WorkspaceSession.kt`（`fork`） | `SessionForkTest`・`EditingSessionForkTest` | architecture/index-references.md「場所は文書ID」 | —（内部） |  |
| I-13 | 保有と所属（入れ子で決まる関係に名前＝子を書く項目の `relation` かキー名・レジストリの宣言が効く・関係パネルと related・削除の注意事項） | `semantic-core/semantic/Graph.kt`（`SemanticHolding`）・`semantic-core/semantic/Refactor.kt` | `HoldingDeleteTest`・`SemanticEntityGraphTest` | architecture/graph-query.md「線の意味」 | manual/ids.md「保有と所属」 |  |

## 6. ワークスペース横断ビュー

| ID | 機能 | 主な実装 | テスト | 設計 | 操作の説明 | 未解決の指摘 |
|---|---|---|---|---|---|---|
| X-01 | 一覧ビュー（ID・タイトル・状態・担当・種別・絞り込み・未保存編集に追随・チェックと ID の列を左に固定） | `ui/view/WorkspaceViews.kt`・`ui/common/VirtualTable.kt`・`AppState.workspaceRecords` | `VirtualTableTest` | architecture/views.md「ビュー機構」 | manual/views.md「ビュー」 | UX-94 |
| X-02 | 一括編集（自動値も含め Undo 1 回） | `DocumentSession.bulkUpdate`・`WorkspaceSession.applyWithAutoValues` | `AppStateAutoValuesTest`・`AutoValuesKernelTest`・`SessionWriteParityTest` | architecture/editing-history.md「編集の経路」 | manual/README.md「操作の役割」 | UX-94 |
| X-03 | グラフレンズ（近傍・線の多い箱で止める・型と関係の絞り込み・配置と配置の調整・線のラベルの表示切替・保有・側面・PNG・ミニマップ） | `ui/graph/GraphLensView.kt`・`ui/graph/LensGraph.kt`・`ui/common/GraphLayouts.kt`・`ui/common/DiagramLayoutOptionsMenu.kt` | `LensGraphTest`・`GraphLayoutsTest`・UI `DiagramLayoutOptionsUiTest`・`DiagramLabelVisibilityTest` | architecture/graph-query.md「エンティティページ」 | manual/views.md「ビュー」 | UX-156・UX-157・UX-253・UX-255 |
| X-04 | コマンドパレット（Ctrl+K・ID・ファイル・本文検索・ビュー切替・スペース切替・保存） | `ui/shell/CommandPalette.kt`・`session/DocumentSession.kt`（`searchContent`） | 手動 | architecture/ui.md「UI シェル」 | manual/README.md「ショートカット」 |  |
| X-05 | ビュー定義 `_views`（`kind: grid` の行・列・並び〔出現順か `order.path` の値の順〕・詳細文書・新規行・バックリンク列・`kind: document`） | `core/Views.kt`・`workspace-core/workspace/ViewDefinitions.kt` | `ViewsTest` | architecture/views.md「ビュー定義の読み取り」 | manual/views.md「ビューグリッドとビュー定義」 | UX-124・UX-223・UX-299・UX-81 |
| X-06 | ビューグリッド（セル編集・原本へ移動・詳細文書と新規行の作成・バックリンク・要約と整形・固定列・列幅は見出しと値から・見出しの印） | `ui/view/ViewGridPane.kt`・`ui/view/GridWidths.kt`・`ui/common/VirtualTable.kt`・`session/DocumentSession.kt`（`createViewDetail` / `viewRowTarget`）・`AppState.addViewRow` | `ViewsTest`・`VirtualTableTest`・`GridWidthsTest` | architecture/views.md「ビュー機構」 | manual/views.md「ビューグリッドとビュー定義」 | UX-122・UX-124・UX-144・UX-151・UX-35・UX-37・UX-72・UX-81 |
| X-07 | 文書ビュー（`kind: document`・`sections` の fields と backlinks・`attachments`・複合文書ビュー〔別の側面の区画 `fields.in`・表の埋め込み `grid`〕・区画は変換のひな型で評価） | `core/Views.kt`（`resolveDocument`）・`semantic/VirtualViews.kt`（`DocResolver`）・`ui/form/FormDocView.kt`（`DocViewSections`・`OccurrenceSection`・`GridSection`） | `ViewsTest`・`CompositeDocViewTest`・`CompositeDocViewDefinitionsTest` | architecture/views.md「文書ビュー」 | manual/views.md「文書ビュー」・manual/views.md「複合文書ビュー」 | UX-129・UX-254 |
| X-08 | ビュー定義のスキーマ参照とリント（`definition:` 列の展開・スキーマとの整合を検査し定義エラーとして表示） | `workspace-core/workspace/ViewDefinitions.kt`（`lint`） | `ViewsTest`・`ViewDefinitionsTest` | architecture/views.md「definition 参照とリント」 | manual/views.md「文書ビュー」 |  |
| X-09 | 値の式の列（`value:`・`via`〔段を重ねる〕・配列の集計・`all`・`each`・`extract`・`daysBetween`・`weekStart`・来歴つき・未保存編集に追随） | `semantic/VirtualViews.kt`（`valueCell`）・`semantic/Values.kt` | `ViewsTest`・`ValueOperationsTest`・`SemanticViaTest`・`SemanticAggregateTest`・`SemanticAllTest`・`AllFromCheckTest`・`SemanticTransformTest` | architecture/transform.md「計算列と決定論的射影」 | manual/views.md「ビューグリッドとビュー定義」 | UX-128・UX-136・UX-144・UX-221・UX-241・UX-242・UX-277 |
| X-10 | クエリ行（`rows: match:` の束縛行・結合先の値をセルに・上限 `limit`・受け取る値 `params`〔where に `$名前`〕・並び `order`） | `core/Views.kt`（`rowsQuery`）・`index/Query.kt` | `ViewsTest` | architecture/views.md「ビュー機構」 | manual/views.md「ビューグリッドとビュー定義」 | UX-224 |
| X-11 | スキーマレンズ（「型」の段＝箱は型・線は関係の宣言と導出・入れ子は保有の宣言。箱を押すとスキーマへ・CLI `schema-graph`） | `semantic-core/semantic/SchemaGraph.kt`・`ui/graph/LensGraph.kt`（`buildTypes`）・`cli/SchemaCommands.kt` | `SemanticSchemaGraphTest`・`LensGraphTest`・`WorkspaceCliTest` | architecture/graph-query.md「スキーマレンズ」 | manual/views.md「ビュー」 | UX-282 |
| X-12 | レンズの見え方の定義（`_views` の `kind: lens`＝矢印の向き・箱の絞り込み `filters`〔つながりで残す `keepIfLinked`〕・関係名のリント・改名の警告） | `semantic-core/semantic/LensViews.kt`・`workspace-core/workspace/ViewDefinitions.kt`（`parseLensView`）・`ui/graph/LensGraph.kt`・`ui/graph/GraphLensView.kt` | `ViewDefinitionsTest`・`LensFiltersTest`・`LensGraphTest`・UI `LensFiltersUiTest` | architecture/views.md「規律」 | manual/views.md「レンズの見え方」・manual/views.md「箱の絞り込み」 | UX-247 |
| X-13 | ダッシュボード（開始画面・業務詳細と参照・共通基準日・区画の群と並べ方・共有の入力 `inputs`〔区画の filter と表の params へ。空＝絞らない〕・CLIとread_view） | `semantic/DashboardViews.kt`・`core/Dashboards.kt`・`ui/view/DashboardPane.kt` | `DashboardDefinitionsTest`・`DashboardsTest` | architecture/dashboards.md「概要」 | manual/dashboards.md「ダッシュボード」 | UX-205・UX-299・UX-300 |
| X-14 | チャート（`kind: chart`＝元の表〔grid / matrix〕から・棒／折れ線／面／円／散布図ほか〔`x`・`label`・`scale`〕・置き場所はビュータブ・区画 `chart`〔`rows`・`series`〕・ダッシュボード・`drill`・`order`・CLI `view`・`read_view`） | `view/chart/ChartViewKind.kt`・`ChartViews.kt`・`ChartSelection.kt`・資源 `view/chart/schema.yaml`・`core/ChartMaterials.kt`（押した形の材料）・`ui/view/ChartViewPane.kt`（`ChartRenderer`）・`ui/view/Charts.kt`（`LineChart` / `PieChart`）・`semantic-core/semantic/DashboardViews.kt` | `ChartViewsTest`・`ChartViewKindTest` | architecture/views.md「ビュー機構」 | manual/views.md「チャート」 |  |
| X-15 | レポート（`.yde/reports`＝変換〔`$view`・`$where`・`$one`・`$each` の源に行・`rules`〕→ ひな形〔`{パス}`・`{パス:形}`・`{パス:cell}`・`{#each}`〔`more`〕・`{#if}`〕・出力 `output`・CLI `report`・台帳のビュータブ） | `semantic-core/semantic/TextTemplate.kt`・`semantic-core/semantic/ReportRows.kt`・`workspace-core/workspace/ReportDefinitions.kt`・`core/Reports.kt`・`ui/view/ReportPane.kt` | `TextTemplateTest`・`ReportsTest`・`SemanticTransformTest` | architecture/transform.md「レポート」 | manual/reports.md「レポート」 | UX-220 |
| X-16 | クロス集計ビュー（`kind: matrix`・行の論理 ID × 列の参照先か値・セルは集めた要素の集計・未分類と合計）と、要素ごとに列を広げる列 `each`（`$名前`） | `semantic-core/semantic/VirtualViews.kt`（`lower`）・`workspace-core/workspace/ViewDefinitions.kt` | `ViewsTest` | architecture/views.md「ビュー機構」 | manual/views.md「クロス集計」 | UX-138・UX-281 |
| X-17 | 値の式で軸を広げるクロス集計（行 `rows.value`・列 `cells.columns.value`〔見出し `label`・並び `order`〕・列ごとの行の鍵 `cells.series`・累計 `cumulative`・未分類 `rows.unclassified`・見出しは `columnsOf`） | `semantic-core/semantic/VirtualViews.kt`（`resolvePivot` / `columnsOf`）・`workspace-core/workspace/ViewDefinitions.kt` | `PivotViewsTest` | architecture/views.md「ビュー機構」 | manual/views.md「クロス集計」 | UX-272・UX-281 |
| X-18 | 図の記法の定義・入力と表示契約の検査・変換をまたぐ原本座標 | `semantic/DiagramNotations.kt`・`workspace-core/workspace/DiagramDefinitions.kt` | `DiagramDefinitionsTest` | [図の記法](architecture/diagram-notations.md) | manual/diagram-notations.md「記法を定義する」 |  |
| X-19 | 人物カード・画像・共通部品の計測描画・レンズとフローへの適用 | `core/DiagramInputs.kt`・`ui/common/DiagramBodies.kt` | UI `DiagramBodiesTest`・`DiagramFlowTest`・`FileLocationsTest` | [図の記法](architecture/diagram-notations.md) | manual/diagram-notations.md「人物カードを使う」・manual/diagram-notations.md「フローへ適用する」 |  |
| X-20 | クラスの区画・辺の端点記号と多重度・部品から原本へ移動 | `ui/common/DiagramEdges.kt`・`core/DiagramInputs.kt` | `DiagramEdgesTest`・`DiagramInputsTest`・`LensGraphTest` | [図の記法](architecture/diagram-notations.md) | manual/diagram-notations.md「クラス図を使う」 |  |
| X-21 | 記法の条件・型の属性・CLIへの開示・レンズとフローのPNG | `semantic/SchemaGraph.kt`・`ui/view/FlowDrawing.kt`・`ui/common/DiagramPng.kt` | `SemanticSchemaGraphTest`・`DiagramDefinitionsTest`・UI `FlowDrawingTest` | [図の記法](architecture/diagram-notations.md) | manual/diagram-notations.md「条件付き表示」・manual/diagram-notations.md「型の属性を表示する」・manual/diagram-notations.md「出力と定義の読み取り」 |  |
| X-22 | flow・lens の位置・固定と再配置 | `desktop/DiagramPositions.kt` | `DiagramPositionsTest`・`DiagramStateWriteTest`・`FlowPositionsTest`・`LensPositionsTest`・UI `DiagramPositionUiTest` | architecture/diagram-positions.md「配置の規律」 | manual/diagram-positions.md「移動と解除」 | UX-162・UX-257・UX-274 |
| X-23 | 直交配線・ラベル追従・フローの折り返し | `ui/common/OrthogonalRouting.kt`・`ui/view/FlowLabelLayout.kt` | `OrthogonalRoutingTest`・UI `FlowLabelLayoutTest` | [手動位置](architecture/diagram-positions.md) | manual/flow-views.md「画面での操作」 |  |
| X-24 | flow・lens のグリッド吸着と設定保存 | `ui/common/DiagramGrid.kt` | `DiagramGridTest`・UI `DiagramPositionUiTest` | architecture/diagram-positions.md「グリッド吸着」 | manual/diagram-positions.md「グリッド吸着」・manual/diagram-positions.md「保存の単位」 |  |
| X-25 | flow・lensの部品ポートと参照ごとの線 | `semantic/DiagramPorts.kt` | `DiagramPortsTest`・UI `FlowPortLayoutTest`・UI `LensPortsTest`・UI `LensPortsUiTest` | architecture/diagram-notations.md「ポート」 | manual/diagram-notations.md「ポートで行へ接続する」 |  |
| X-26 | ダッシュボードの業務の流れ（`flow`＝段は区画・工程は操作・入口・履歴〔流れ図・工程の作業一覧・業務詳細の流れの中の位置・見出しでの畳み・台帳のフロータブでの配置の編集と同期・確認の移り先の予告と宣言外の警告・CLI `view --entity`・read_view〕） | `semantic/DashboardFlows.kt`・`workspace/ViewDefinitions.kt`（`flow` の読み取りと点検）・`core/Dashboards.kt`（`flow` / `position` / `transition`）・`session/DashboardFlowSession.kt`・`core/DashboardFlowDiagrams.kt`（業務の流れをフロービューの表示モデルにする）・`ui/view/DashboardFlowPane.kt`（`DashboardFlowOverview`・`MoveBody`・`DashboardPositionPanel`）・`ui/view/FlowViewPane.kt`（`FlowCanvas`（`editable = false` で読むだけ）・フロータブでのダッシュボードの流れ） | `DashboardFlowsTest`・`DashboardDefinitionsTest`・`DashboardFlowTest`・`DashboardFlowDiagramsTest`・UI `DashboardFlowUiTest` | architecture/dashboards.md「業務の流れ」 | manual/dashboards.md「業務の流れ（flow）」 | UX-288・UX-300・UX-303 |
| X-27 | flow の線の経由点（`flow-edge`。線のラベルのドラッグ・`withFlowVias`・選択した線を自動に戻す・読むだけの図とダッシュボードの業務の流れでも同じ形） | `ui/view/FlowEdgeVias.kt`・`ui/view/FlowViewPane.kt`（`FlowCanvas` のラベルのドラッグ・プレビュー・保存）・`ui/view/FlowLabelLayout.kt`（経由点のラベルを動かさず他のラベルの障害物にする） | `FlowEdgeViasTest`・UI `FlowEdgeViaUiTest` | architecture/diagram-positions.md「線の経由点」 | manual/diagram-positions.md「線の経由点（フロー）」 | UX-274 |

## 7. AI による作成支援

| ID | 機能 | 主な実装 | テスト | 設計 | 操作の説明 | 未解決の指摘 |
|---|---|---|---|---|---|---|
| A-01 | フィールド単位の生成（✨） | `ui/run/AiGenerationDialog.kt`・`ui/form/FormFields.kt` | 手動 | architecture/agents.md「AI 生成」 | manual/ai.md「AIによる作成支援」 | UX-218 |
| A-02 | 配列への候補追加と全体置き換え | `ui/run/AiGenerationDialog.kt`（`combineProposal`） | `AiProposalTest` | architecture/agents.md「AI 生成」 | manual/ai.md「AIによる作成支援」 |  |
| A-03 | 文書全体の生成（`_document` は保持） | `ui/run/AiGenerationDialog.kt`（`replaceInClone`） | `AiProposalTest` | architecture/agents.md「AI 生成」 | manual/ai.md「AIによる作成支援」 | UX-30 |
| A-04 | JSON Schema による Structured Outputs（`map` / `any` を含むとプロンプト指示へ後退） | `tools/JsonSchemas.kt`（`fieldToJsonSchema`） | `OpenAiGeneratorTest`・`MetaSchemaTest` | architecture/agents.md「AI 生成」 | manual/ai.md「AIによる作成支援」 |  |
| A-05 | 参照候補の enum 制約（200 件超は省略） | `tools/JsonSchemas.kt`（`refCandidateIds`） | `AiGeneratorTest` | architecture/agents.md「AI 生成」 | manual/ai.md「AIによる作成支援」 |  |
| A-06 | 適用前の差分プレビューと適用後検証・Undo できる適用 | `ui/run/AiGenerationDialog.kt` | 手動 | architecture/agents.md「AI 生成」 | manual/ai.md「AIによる作成支援」 |  |
| A-07 | プロバイダ切替（OpenAI Responses・Anthropic Messages・Azure）・API キーはメモリのみ | `agent/llm/OpenAiGenerator.kt`・`agent/llm/AnthropicGenerator.kt` | 単体（レスポンス解析） | architecture/agents.md「AI 接続」 | manual/ai.md「AIによる作成支援」 |  |
| A-08 | 生成のキャンセルとエラー処理 | `ui/run/AiGenerationDialog.kt`・`ai/*Generator.kt` | 単体（一部） | architecture/agents.md「AI 生成」 | manual/ai.md「AIによる作成支援」 |  |
| A-09 | OpenAI 互換の接続先とプロキシ（Azure・ベース URL 上書き・Basic 認証） | `agent/llm/Connection.kt`・`agent/llm/Http.kt` | `ConnectionTest` | architecture/agents.md「AI 接続」 | manual/ai.md「AIによる作成支援」 |  |
| A-10 | 生成のカーネル統合（単発生成を 1 ターンで提出する特殊アクティビティとして実行） | `agent/run/Generation.kt` | `GenerationRunTest` | architecture/agents.md「AI 生成」 | —（内部） |  |
| A-11 | 同一スキーマの参考文書を添付（最大 3 件を自動選択・上限つき） | `agent/llm/AiGenerator.kt`（`ReferenceDoc`）・`desktop/AppState.kt`（`sameSchemaFiles`） | `AiGeneratorTest` | architecture/agents.md「AI 生成」 | manual/ai.md「AIによる作成支援」 |  |
| A-12 | 生成の入出力記録（システム指示・プロンプト・応答スキーマ・生出力を実行記録へ） | `agent/llm/AiGenerator.kt`（`AiExchange`）・`agent/run/RunLog.kt` | `GenerationRunTest` | architecture/agents.md「AI 生成」 | manual/ai.md「定義の置き場所」 |  |
| A-13 | レート制限と一時エラーの自動リトライ（408 / 429 / 5xx・`retry-after` と指数バックオフ） | `agent/llm/Http.kt`（`retryLoop`） | `HttpTest` | architecture/agents.md「AI 接続」 | manual/ai.md「AIによる作成支援」 | UX-61 |
| A-14 | Anthropic prompt caching（system・ツール定義・最新メッセージの 3 点） | `agent/llm/ToolChat.kt`（`AnthropicToolSession`） | `ToolChatParseTest` | architecture/agents.md「AI 接続」 | —（内部） |  |
| A-15 | 入口のスペース非依存化（`AiRequest` が対象文書を持ち・ホストはシェル直下） | `desktop/AppState.kt`（`AiRequest` / `requestAi`）・`ui/run/AiGenerationDialog.kt` | 手動 | architecture/agents.md「AI 生成」 | —（内部） |  |
| A-16 | エンティティ文脈と出現単位の生成（関連文書の注入・所有キー限定の生成・キー単位マージ） | `agent/llm/AiGenerator.kt`（`contextDocs`）・`AiRequests.kt`（`mergeGenField`） | `AiGeneratorTest`・`AiProposalTest` | architecture/agents.md「AI 生成」 | manual/ai.md「AIによる作成支援」 |  |
| A-17 | 新規作成のたたき台生成（作成 → 開く → 生成の連結・ルート object のキー単位マージ） | `AiRequests.kt`（`requestDocumentDraft`）・`ui/run/AiGenerationDialog.kt` | `GenerationRunTest` | architecture/agents.md「AI 生成」 | manual/ai.md「AIによる作成支援」 | UX-218 |

## 8. エージェント実行（アクティビティ）

| ID | 機能 | 主な実装 | テスト | 設計 | 操作の説明 | 未解決の指摘 |
|---|---|---|---|---|---|---|
| AG-01 | エージェント定義 `_agents`（persona・観点・モデル設定・ツール許可） | `agent/Definitions.kt` | `DefinitionsTest` | architecture/agents.md「エージェント実行と Staging」 | manual/ai.md「定義の置き場所」 |  |
| AG-02 | アクティビティ定義 `_activities`（対象・指示・文脈・書込許可・上限・提出ゲート `error-free` / `no-new-errors` / `none`） | `agent/Definitions.kt`・`tools/WorkingCopy.kt`（`newErrors`） | `DefinitionsTest`・`KernelTest` | architecture/agents.md「エージェント実行と Staging」 | manual/ai.md「定義の置き場所」 | UX-38・UX-90 |
| AG-03 | ツール使用対応 LLM クライアント（OpenAI・Anthropic の function calling） | `agent/llm/ToolChat.kt` | `ToolChatParseTest` | architecture/agents.md「エージェント実行と Staging」 | manual/ai.md「プロバイダ・モデルの決まり方」 |  |
| AG-04 | 実行カーネル（マルチターン・上限・終了手続き・キャンセル・疑似セッション注入） | `agent/run/Kernel.kt` | `KernelTest` | architecture/agents.md「エージェント実行と Staging」 | manual/ai.md「実行の流れ」 | UX-46・UX-48 |
| AG-05 | ツールの一式（中核＝読む 8・書く 2・検証 1・許可した操作は app-core の `Toolset`。対話 2・管理 4 は agent。許可ツール名と書込権限を実行時に強制） | `tools/Toolset.kt`・`agent/run/Tools.kt` | `ToolsetTest`・`KernelTest`・`ToolsTest` | architecture/agents.md「エージェント実行と Staging」 | manual/ai.md「安全性」 | UX-92 |
| AG-06 | ステージング（複数文書のオーバーレイ・横断検証・未提出ドラフトの保全） | `tools/WorkingCopy.kt`・`agent/run/Staging.kt` | `StagingTest` | architecture/agents.md「エージェント実行と Staging」 | manual/ai.md「安全性」 | UX-227 |
| AG-07 | 変更セットレビュー UI（ファイル単位の部分採用・再検証・差分・適用・状態は `RunController`） | `ui/run/RunsSpace.kt`（`ReviewContent`, `RunController`） | 手動 | architecture/agents.md「エージェント実行と Staging」 | manual/ai.md「実行の流れ」 | UX-140・UX-50・UX-54・UX-55・UX-91 |
| AG-08 | 実行中の対話（`ask_human` の質問カード・計画とメモの進捗・キャンセル） | `agent/run/Kernel.kt`・`ui/run/RunsSpace.kt`（`QuestionCard`） | `KernelTest` | architecture/agents.md「エージェント実行と Staging」 | manual/ai.md「実行の流れ」 |  |
| AG-09 | 実行記録 `_runs`（ターン・ツール呼び出し・採否・トークン・会話記録） | `agent/run/RunLog.kt`・`agent/run/Kernel.kt` | `KernelTest`・`AutoApplySafetyTest` | architecture/agents.md「エージェント実行と Staging」 | manual/ai.md「定義の置き場所」 | UX-39・UX-53 |
| AG-10 | CLI からの実行（`agent` / `activities`） | `cli/AgentCommands.kt` | 手動 | architecture/agents.md「エージェント実行と Staging」 | manual/cli.md「コマンド例」 |  |
| AG-11 | トリガ（manual / on-change / schedule / on-invalid・予約ディレクトリと自己変更では発火しない） | `agent/auto/Automation.kt`（`tick`） | `AutomationTest` | architecture/agents.md「自動実行」 | manual/ai.md「自動実行」 | UX-49・UX-51 |
| AG-12 | アクティビティ連鎖（`chain` の成功・失敗・規則による分岐・深さ上限と循環検出） | `agent/auto/Automation.kt`（`chainNext`）・`agent/Definitions.kt`（`ChainDef`） | `AutomationTest` | architecture/agents.md「自動実行」 | manual/ai.md「自動実行」 | UX-60 |
| AG-13 | 無人リトライ（`retry.maxAttempts`・同一失敗の繰り返しは打ち切り） | `agent/auto/Automation.kt`（`handleFailure`） | `AutomationTest` | architecture/agents.md「自動実行」 | manual/ai.md「自動実行」 | UX-46・UX-48 |
| AG-14 | 自動承認 `approval: auto`（条件を欠くと承認待ちへ降格・導出元文書への書込も降格・適用前バックアップ） | `agent/auto/Automation.kt`・`agent/auto/AutoApply.kt` | `AutomationTest`・`AutoApplySafetyTest` | architecture/agents.md「自動実行」 | manual/ai.md「自動実行」 |  |
| AG-15 | 実行キューと停止（直列実行・常時停止・承認待ちの耐久性） | `agent/auto/Automation.kt` | `AutomationTest` | architecture/agents.md「自動実行」 | manual/ai.md「自動実行」 |  |
| AG-16 | 自動適用の巻き戻し（バックアップから復元・競合は拒否し `--force` で上書き） | `agent/auto/AutoApply.kt`（`revert`） | `AutoApplySafetyTest` | architecture/agents.md「自動実行」 | manual/ai.md「自動実行」 | UX-56 |
| AG-17 | サブエージェント委譲（`delegate`・読み取り専用の相談・深さ 1） | `agent/run/Kernel.kt`（`consult`） | `KernelTest` | architecture/agents.md「エージェント実行と Staging」 | manual/ai.md「自動実行」 |  |
| AG-18 | 自動実行 UI（自動化タブ: 監視・キュー・履歴・ログ）と CLI `watch` | `ui/run/AutomationPanel.kt`・`cli/AgentCommands.kt` | 手動 | architecture/agents.md「自動実行」 | manual/ai.md「自動実行」 | UX-45・UX-53・UX-61 |
| AG-19 | 実行メトリクス（提出率・採用率・却下ファイル率・トークン・CLI `metrics`） | `agent/eval/Metrics.kt` | `MetricsTest` | architecture/agents.md「自己改善」 | manual/ai.md「実行メトリクス」 |  |
| AG-20 | 評価セット `_evals`（ゴールデン入力と期待結果・サンドボックスコピー上で実行・CLI `eval`） | `agent/Evals.kt`・`eval/EvalRunner.kt` | `EvalTest` | architecture/agents.md「自己改善」 | manual/ai.md「評価セット」 |  |
| AG-21 | 比較実験（`variants` でモデル・エージェント・指示を差し替えた A/B） | `eval/EvalRunner.kt`（`EvalRunner`） | `EvalTest` | architecture/agents.md「自己改善」 | manual/ai.md「評価セット」 |  |
| AG-22 | メタアクティビティ（`meta: true` で定義文書への提案・承認は human 固定） | `agent/Definitions.kt`・`tools/Toolset.kt` | `MetaActivityTest` | architecture/agents.md「自己改善」 | manual/ai.md「メタアクティビティ」 |  |
| AG-23 | 自己改善 UI（計測・評価タブ） | `ui/run/ImprovementPanel.kt` | 手動 | architecture/agents.md「自己改善」 | manual/ai.md「自己改善」 |  |
| AG-24 | ビューの開示（`read_view`・行 × 列を原本座標つきで返し `apply_patch` の対象にできる） | `tools/Readouts.kt`（`viewText`） | `ViewsTest` | architecture/agents.md「コンテキスト」 | manual/ai.md「安全性」・manual/dashboards.md「CLIとエージェント」 | UX-205 |
| AG-25 | 段階的詳細化の導線（`related` が各出現の要約・フェーズ・進捗を 1 回で返す） | `tools/Readouts.kt`（`relatedText`）・`core/Disclosure.kt` | `ToolsTest`・`KernelTest` | architecture/agents.md「コンテキスト」 | manual/ai.md「コンテキストレシピ」 |  |
| AG-26 | 一括実行 `foreach`（source: view / issues / search / files / ids / query・1 件 = 1 実行のキュー展開・GUI と CLI） | `agent/auto/Foreach.kt`・`agent/auto/Automation.kt`（`enqueueForeach`） | `ForeachTest` | architecture/agents.md「一括実行」 | manual/ai.md「一括実行」 | UX-59・UX-60 |
| AG-27 | コンテキスト会計（初期文脈の区分別トークン・送信別実測・キャッシュ読取の正規化） | `agent/run/Kernel.kt`（`initialContextSections`）・`agent/run/RunLog.kt` | `KernelTest`・`MetricsTest` | architecture/agents.md「コンテキスト」 | manual/ai.md「コンテキストレシピ」 |  |
| AG-28 | コンテキストレシピ（`context.items`: catalog / phase / view / doc / related / sample・`when`・`budgetChars`・既定は空） | `agent/Definitions.kt`（`ContextItemDef`）・`agent/run/Kernel.kt` | `DefinitionsTest`・`KernelTest` | architecture/agents.md「コンテキスト」 | manual/ai.md「コンテキストレシピ」 |  |
| AG-29 | 参照の名前解決とツール減量（参照の解決フッタ・タイトル注記・`limit` / `rows` / `columns` / `match` 引数） | `tools/Readouts.kt`・`core/Views.kt`（`refAnnotatedCellText`） | `ToolsTest` | architecture/agents.md「コンテキスト」 | manual/ai.md「コンテキストレシピ」 |  |
| AG-30 | グラフトラバース（`start` と `steps` の経路パターン・レシピ `kind: graph`・ツール `traverse`・CLI） | `core/Traverse.kt`・`tools/Readouts.kt`（`traverseText`） | `TraverseTest` | architecture/agents.md「コンテキスト」 | manual/ai.md「コンテキストレシピ」・manual/cli.md「コマンド例」 |  |
| AG-31 | 評価セットの文脈レシピ A/B（`variants.context`・変異別のトークン） | `agent/Evals.kt`（`EvalVariant.contextItems`） | `EvalTest` | architecture/agents.md「自己改善」 | manual/ai.md「評価セット」 |  |
| AG-32 | エンティティ全体像の開示（`read_entity`・合成と両方向の関係と充足を 1 回で返す・レシピ `kind: entity`） | `tools/Readouts.kt`（`entityText`）・`core/Aspects.kt` | `ToolsTest`・`KernelTest` | architecture/agents.md「コンテキスト」 | manual/ai.md「コンテキストレシピ」 |  |

## 9. コア API・CLI（エージェント基盤）

| ID | 機能 | 主な実装 | テスト | 設計 | 操作の説明 | 未解決の指摘 |
|---|---|---|---|---|---|---|
| C-01 | ヘッドレスワークスペース API（開く・読む・保存・検証・相対パスの脱出防止） | `core/Workspace.kt`・`workspace/ParallelReads.kt` | `WorkspaceCliTest`・`WorkspaceLoadingTest`・`DocumentStoreTest`・`ParallelReadsTest` | architecture/workspace.md「文書キャッシュと Workspace」 | manual/cli.md「ヘッドレス CLI」 | UX-296・UX-297・UX-298・UX-301・UX-302・UX-304・UX-305・UX-306 |
| C-02 | YPath パッチ（set / insert / delete / move / rename / reorder）と YPath のパース | `yaml-core/patch/Patch.kt`・`yaml-core/model/YNode.kt`（`parseYPath`） | `PatchTest`・`YPathTest` | architecture/yaml-model.md「変更の基礎命令と 2 層」 | manual/cli.md「コマンド例」 |  |
| C-03 | 変更セット（複数ファイルのパッチ束と新規作成・base ハッシュの競合検出・適用後の姿の検証・`--require-valid`・適用の本体はカーネル） | `workspace-core/changeset/ChangeSets.kt`・`workspace-core/workspace/ChangeSetApplier.kt` | `ChangeSetApplierTest`・`WorkspaceCliTest` | architecture/workspace.md「変更セットの適用」 | manual/cli.md「コマンド例」 | UX-118・UX-168・UX-226 |
| C-04 | 段階的開示 API（カタログ → 骨子 → ノード全文・文字数予算つきバンドル） | `core/Disclosure.kt` | `WorkspaceCliTest` | architecture/agents.md「コンテキスト」 | manual/cli.md「コマンド例」 |  |
| C-05 | 構造検索 API（本文とキーの部分一致とスキーマ・glob 条件・参照検索・未解決参照） | `core/Search.kt` | `WorkspaceCliTest` | [index-references](architecture/index-references.md) | manual/cli.md「コマンド例」 |  |
| C-06 | CLI（カーネルの命令 `yaml …` / `workspace …` と応用の命令〔探索・検索・クエリ・ビュー・検証・適用・エンティティ・エージェント・skill・初期化ほか〕・全命令の `--format yaml\|json\|text`） | `cli/Main.kt`・`cli/AppCli.kt`・`cli/ExploreCommands.kt` | `AppCliTest`・`CliTest`・`WorkspaceCliTest` | [workspace](architecture/workspace.md) | manual/cli.md「ヘッドレス CLI」 | UX-09 |
| C-07 | ビュー解決 API（定義のロード・行 × セルの原本座標化・バックリンク列・文書ビュー・行の並びと集計の種類は項目の型から・CLI `view`） | `core/Views.kt` | `ViewsTest`・`VirtualViewsTest` | architecture/views.md「ビュー機構」 | manual/cli.md「コマンド例」 | UX-219・UX-247・UX-299 |
| C-08 | 性能ベンチマーク（カーネルの全再構築・増分更新の計測・CLI `workspace bench`） | `cli/Commands.kt` | `CliTest`・`IdIndexIncrementalTest` | [workspace](architecture/workspace.md) | manual/cli.md「コマンド例」 |  |
| C-09 | 文書キャッシュの中核（`EditBuffer`・`WorkspaceRecords`・`noteDiskChanges` の振り分け） | `core/Documents.kt`・`core/Workspace.kt` | `DocumentsTest` | architecture/workspace.md「文書キャッシュと Workspace」 | —（内部） |  |
| C-10 | エンティティ操作（rename / merge / delete を 1 つの計画に・1 トランザクションで Undo 1 回・CLI とパレットとエンティティページ） | `semantic-core/semantic/Refactor.kt`・`workspace-core/workspace/WorkspaceSession.kt`（`planRefactoring`） | `RefactoringTest`・`AppStateRefactorTest` | architecture/graph-query.md「エンティティ操作」 | manual/checks.md「エンティティ操作」 | UX-165 |
| C-11 | ファイル移動・改名の一級化（文書参照の書き換えと物理移動を 1 計画に・影響プレビュー） | `semantic-core/semantic/Refactor.kt`・`workspace-core/workspace/RefactoringImpact.kt` | `DocRefTest`・`RefactoringTest` | architecture/workspace.md「リファクタリングの影響」 | manual/checks.md「エンティティ操作」 |  |
| C-12 | パターン照合エンジン（クエリコア・`match` / `also` / `where` / `limit` / `same`・束縛の表・決定的） | `semantic-core/schema/QuerySpec.kt`・`semantic-core/semantic/SemanticQuery.kt` | `QueryTest`・`TraverseTest` | architecture/graph-query.md「クエリコア」 | manual/checks.md「パターン制約」 | UX-284 |
| C-13 | エンティティ合成（全出現を決定的に並べ所有フィールドを原本座標つきでマージ・CLI `entity show`） | `core/Aspects.kt` | `AspectTest` | architecture/graph-query.md「エンティティページ」 | manual/cli.md「コマンド例」 | UX-65 |
| C-14 | スキーマ・リファクタリング（12 操作・スキーマと文書とビューを 1 つの計画で追随・カーネルが計画を作る） | `semantic-core/semantic/SchemaRefactor.kt`・`semantic-core/semantic/Rewrite.kt` | `RefactoringTest`・`RefactoringPlanGoldenTest` | architecture/schema.md「リファクタリング」 | manual/schema.md「スキーマ・リファクタリング」 | UX-143・UX-211 |
| C-15 | YAML から YAML への変換（選ぶ × 組み立てる × 規則・`$date`・`$number`〔`$as`〕・`$trim`・`$value`・`$index` / `$position`・`$zip`・出どころつき・CLI `transform`） | `semantic-core/schema/TransformDef.kt`・`semantic-core/semantic/Transform.kt` | `SemanticTransformTest`・`TemplateValueTest`・`StructuralDerivationTransformTest` | architecture/transform.md「変換」 | manual/cli.md「コマンド例」 | UX-136・UX-142・UX-238・UX-239・UX-251 |
| C-16 | バージョン表示（外部 `appVersion`・内部 `yyyymmdd.ビルド番号`・コミット・CLI `version` とスタート画面） | `core/BuildInfo.kt`・`build.gradle.kts` | `BuildInfoTest` | [workspace](architecture/workspace.md) | manual/README.md「ビルドと起動」 | UX-268 |
| C-17 | フローの解決・複数文書sources・来歴・CLI | `view/flow/FlowViewKind.kt`・`FlowViews.kt`・`FlowReports.kt` | `FlowViewsTest`・`FlowViewDefinitionsTest`・`FlowViewsIntegrationTest` | architecture/views.md「フローの解決」 | manual/flow-views.md「フロービュー」 |  |
| C-18 | フローの配置切替・配置の調整・線のラベルの表示切替・スイムレーン・文書埋め込み・原本フォームと履歴・改名影響 | `ui/common/GraphLayouts.kt`・`ui/common/FlowSwimlanes.kt`・`ui/view/FlowViewPane.kt` | `FlowLayoutsTest`・UI `DiagramLayoutOptionsUiTest`・`DiagramLabelVisibilityTest`・`FlowSelectionTest`・`NavigationHistoryTest`・`FlowRefactoringTest`・`FlowRefactoringGoldenTest` | architecture/views.md「フローの画面と配置」 | manual/flow-views.md「画面での操作」・manual/flow-views.md「文書への埋め込み」 | UX-252・UX-253・UX-303 |
| C-19 | 変換の局所名と可変長の連結（`$bind`・`$flatten`） | `schema/TemplateBindings.kt`・`TransformDef.kt`・`semantic/Transform.kt` | `NestedTransformTest`・`FlowViewsTest` | architecture/transform.md「変換」 | manual/cli.md「コマンド例」 |  |
| C-20 | 開く処理の時間の記録（`Timings`＝段階・文書ごとの合計 `measureEach`・スレッドをまたぐ記録 `carrier` / `carry`・CLI `--timings`・画面の `.yde/state/timings.log`） | `workspace/Timings.kt`・`desktop/OpenTimings.kt`・`cli/AppCli.kt` | `TimingsTest`・`OpenTimingsLogTest` | [workspace](architecture/workspace.md) | manual/cli.md「コマンド例」 |  |

## 10. skill（フォーマット適合）

| ID | 機能 | 主な実装 | テスト | 設計 | 操作の説明 | 未解決の指摘 |
|---|---|---|---|---|---|---|
| SK-01 | skill 定義 `_skills`（import / export の宣言型と run） | `skill/Skills.kt` | `ImportExportTest`・`RunSkillTest` | architecture/agents.md「skill」 | manual/ai.md「skill」 |  |
| SK-02 | インポート（xlsx / csv / json / markdown / docx → 変更セット・`mapping`・`build`・`target.path` / `target.id` / `target.key`・`input.columns`・`input.tree`・`$index`） | `skill/Importer.kt`・`skill/Tables.kt`・`format/FormatCodec.kt`・`format/TextFormats.kt`・`format/JsonTree.kt`・`office/XlsxFormat.kt`・`office/DocxFormat.kt` | `ImportExportTest`・`ArrayImportTest`・`TreeImportExportTest`・`TextFormatsTest`・`JsonTreeTest`・`XlsxFormatTest`・`DocxFormatTest`・`ExtensionsTest` | architecture/agents.md「skill」 | manual/ai.md「インポート」 | UX-170・UX-237・UX-239・UX-259 |
| SK-03 | エクスポート（glob × 行 × 列 → xlsx / csv / markdown / json / html・`source.view`・JSON の木を `build` で〔`mode`・`index`・`fill`・`style`〕・まとめて `include`・書き出し先は実行時に選ぶ） | `skill/Exporter.kt`・`skill/Tables.kt`・`format/JsonTree.kt`・`ext/Extensions.kt` | `ImportExportTest`・`TreeImportExportTest`・`JsonTreeTest` | architecture/agents.md「skill」 | manual/ai.md「エクスポート」 | UX-129・UX-169・UX-240 |
| SK-04 | CLI（`skills` / `import` / `export`〔`--out`・`--dry-run`・`--yes`〕 / `run-skill`） | `cli/SkillCommands.kt` | `RunSkillTest` | architecture/agents.md「skill」 | manual/cli.md「コマンド例」 |  |
| SK-05 | テンプレート「課題管理」（Excel 往復と棚卸しのエージェント・評価を含む出発点。展開の仕組みは W-21） | `templates/issues/` | `ImportExportTest` | architecture/workspace.md「ワークスペースの初期化」 | manual/ai.md「skill を含む出発点」・manual/README.md「新しいワークスペースを作る」 |  |
| SK-06 | skill UI（設計スペースの連携: 一覧・エクスポート〔出力先のフォルダを選べる・まとめて書き出す上書きの一括確認〕・インポートのプレビューと適用・実行の承認） | `ui/shell/SkillsPanel.kt` | 手動 | architecture/agents.md「skill」 | manual/ai.md「skill」 | UX-232 |
| SK-07 | コード実行 skill（kind: run・人間の事前承認・定義変更で失効・エージェントからは起動不可） | `skill/Runner.kt` | `RunSkillTest` | architecture/agents.md「skill」 | manual/ai.md「コード実行skill」 | UX-169 |

## 11. 操作（型の操作とアクション）

| ID | 機能 | 主な実装 | テスト | 設計 | 操作の説明 | 未解決の指摘 |
|---|---|---|---|---|---|---|
| OP-01 | 型の操作（`methods`・作成・採番〔接頭辞は `$of: self`・入れ子の表は `$of: owner`〕・引き継ぎ） | `semantic-core/schema/Action.kt`・`schema/SchemaTree.kt` | `ActionPlannerTest`・`InsertNumberingTest` | architecture/actions.md「操作の共通規則」 | manual/actions.md「型の操作」 | UX-291 |
| OP-02 | アクションの定義と点検（文書を作る手の採番 `create.id`〔接頭辞は受け取った値でも埋まる〕を含む） | `semantic-core/schema/Action.kt`・`workspace-core/workspace/SchemaDocuments.kt` | `ActionPlannerTest` | architecture/actions.md「操作の共通規則」 | manual/actions.md「アクション」 | UX-166・UX-181・UX-279・UX-290・UX-291・UX-292 |
| OP-03 | 操作の計画・前提評価・変更セット生成 | `workspace-core/workspace/ActionPlanner.kt` | `ActionPlannerTest` | architecture/actions.md「流れ」 | manual/actions.md「前提」 | UX-243・UX-273・UX-278・UX-279・UX-280・UX-285・UX-287 |
| OP-04 | CLI `action`（使える操作の一覧・計画＝変更セットと適用後の検証・`--apply`・`--allow-errors`） | `cli/ActionCommands.kt` | `ActionCliTest` | [actions](architecture/actions.md) | manual/actions.md「CLI で使う」 |  |
| OP-05 | 操作の入口・フォームと横ぐしの対象引継ぎ・前提の無効化・確認と適用 | `ui/action/ActionPane.kt`・`ui/form/DocumentFormWorkspace.kt`・`AppState.kt` | `AppStateActionTest`・`FormOutlineTest` | architecture/actions.md「入力準備と確認の有効期間」 | manual/actions.md「画面で使う」 | UX-185 |
| OP-06 | 検査の指摘から対象・値を指定して起動 | `workspace-core/validate/WorkspaceChecks.kt`・`ui/shell/SidePanel.kt` | `ActionPlannerTest`・`AppStateActionTest` | [actions](architecture/actions.md) | manual/actions.md「検査の指摘から起動する」 | UX-04 |
| OP-07 | エージェントのツール（アクティビティの `actions` で許可した操作が `action_名前` のツールに・ステージングを重ねて計画・`allowedFiles` の外でも通る） | `tools/Toolset.kt`（`actionTools` / `runActionTool`）・`agent/Definitions.kt`・`builtin-schemas/activity.yaml` | `ActionToolTest` | [actions](architecture/actions.md) | manual/actions.md「AI エージェントから使う」 |  |
| OP-08 | 作成経路の寄せ（追加ボタン・新規行・文書の作成を型の `create` と配列の `insert` で。追加のパネルからアクションへ橋渡し） | `workspace-core/workspace/ActionPlanner.kt`・`workspace-core/workspace/DocumentScaffold.kt`・`session/DocumentSession.kt`（`arrayInsert` / `insertArrayElement`）・`desktop/AppState.kt`（`addArrayElement`） | `ActionPlannerTest`・`AppStateActionTest` | architecture/actions.md「操作の共通規則」 | manual/actions.md「追加ボタンと新規行」 |  |
| OP-09 | 現在値・本日の入力準備と変更分の送信 | `semantic-core/schema/ActionInput.kt`・`AppState.kt` | `ActionInputTest`・`AppStateActionInputTest` | architecture/actions.md「入力準備と確認の有効期間」 | manual/actions.md「入力欄の初期設定」 |  |

## ローカル接続の基盤

| ID | 機能 | 主な実装 | テスト | 設計 | 操作の説明 | 未解決の指摘 |
|---|---|---|---|---|---|---|
| LC-01 | 初期値 OFF の接続設定型と明示起動する loopback 待受 | `server/LocalConnectionSettings.kt`・`server/transport/LoopbackEngine.kt` | `TransportCompatibilityTest`・検査 `LocalServerDistributionSmoke` | architecture/local-connections.md「機能別の切替と停止」 | manual/local-connections.md「有効・無効と状態」 |  |
| LC-02 | 画面から独立した共通状態と監視・実行・下書き解析 | `service/WorkspaceHost.kt`・`DesktopRuntime.kt` | `WorkspaceHostTest`・`DesktopRuntimeTest` | architecture/local-connections.md「共通状態と寿命」 | —（内部） |  |
| LC-03 | 背景のファイル操作と履歴の独立実行・完了反映 | `WorkspaceSession.PendingFileOperation` | `BackgroundFileOperationTest`・`AppStateFileArrangementTest` | architecture/local-connections.md「共通状態と寿命」 | —（内部） |  |
| LC-04 | 外部操作の計画・厳格な検証・確定と履歴失効 | `service/WorkspaceOperations.kt`・`ChangeSetApplier.kt` | `WorkspaceOperationsTest`・`ExternalCommitTest` | architecture/local-connections.md「操作の計画と外部確定」 | manual/local-api-changes.md「作業方式」 |  |
| LC-05 | 外部要求の永続記録・再送抑止・部分保存と結果不明の照合 | `service/OperationJournal.kt` | `WorkspaceOperationsTest` | architecture/local-connections.md「要求記録と復旧」 | manual/local-api-changes.md「作業と結果の取得」 |  |
| LC-06 | 外部作業の提案・直接確定・破棄と再起動後の結果復元 | `service/WorkspaceWork.kt`・`service/WorkJournal.kt` | `WorkspaceWorkTest` | architecture/local-connections.md「外部作業と提出・承認」 | manual/local-api-changes.md「作業方式」 |  |
| LC-07 | 受信箱の外部提出・差分再確認・承認保存・却下と文書別の結果 | `ExternalWorkController.kt`・`ui/run/ExternalWorkPane.kt` | UI `ExternalWorkPaneTest` | architecture/local-connections.md「外部作業と提出・承認」 | manual/ai.md「外部からの提出を受信箱で確認する」 |  |
| LC-08 | API・MCP・Web の独立 ON/OFF・状態通知・世代失効・要求の取消と保存完了待ち | `server/LocalConnections.kt`・`server/transport/LocalConnectionGate.kt` | `LocalConnectionsTest`・`LocalConnectionGateTest` | architecture/local-connections.md「機能別の切替と停止」 | manual/local-connections.md「有効・無効と状態」 |  |
| LC-09 | 全 OFF 時の非待受・他機能を残した停止・最後の停止で接続終了とポート解放 | `server/transport/LoopbackEngine.kt`・`DesktopRuntime.kt` | `LocalListenerLifecycleTest`・`LocalConnectionAcceptanceTest` | architecture/local-connections.md「機能別の切替と停止」 | manual/local-connections.md「有効・無効と状態」 |  |
| LC-10 | 接続者・ワークスペース別の指定文書／全体閲覧と、提案・直接確定・業務操作の権限 | `service/ConnectionGrants.kt` | `ConnectionGrantsTest` | architecture/local-connections.md「接続者と権限」 | manual/local-connections.md「接続者と権限」 |  |
| LC-11 | 機能別の資格情報・期限・失効と要求・MCP セッションの接続者への結付け | `server/auth/LocalAccess.kt` | `LocalAccessTest`・`LocalProtocolAuthenticationTest` | architecture/local-connections.md「要求の認証」 | manual/local-connections.md「トークンの発行と失効」 |  |
| LC-12 | HTTP の Host/Origin・認証・CSRF・要求量の検査と能力別の受付 | `server/transport/LocalAuthentication.kt`・`server/LocalServer.kt` | `LocalAuthenticationTest`・`DesktopRuntimeTest` | architecture/local-connections.md「要求の認証」 | —（内部） |  |
| LC-13 | 接続・常駐設定の独立した保存と復元、接続者の登録・権限変更 | `connections/LocalConnectionController.kt` | `LocalConnectionControllerTest` | architecture/local-connections.md「設定画面と保存」 | manual/local-connections.md「保存とエラー」 |  |
| LC-14 | 設定保存の権限保護・Windows ACL 継承の停止・破損と不正権限の拒否 | `connections/ConnectionSettingsStore.kt`・`PrivateConnectionFiles.kt` | `ConnectionSettingsStoreTest` | architecture/local-connections.md「設定画面と保存」 | manual/local-connections.md「保存とエラー」 |  |
| LC-15 | 接続設定と資格情報の管理・読込失敗の修復・トークン発行不可の理由と機能の選択 | `ui/shell/LocalConnectionSettingsPage.kt` | UI `LocalConnectionSettingsPageTest` | architecture/local-connections.md「設定画面と保存」 | manual/local-connections.md「詳細設定」・manual/local-connections.md「トークンの発行と失効」 |  |
| LC-16 | 常駐・非表示起動・復帰・終了確認とトレイ失敗時の画面表示 | `lifecycle/DesktopPresence.kt` | `DesktopPresenceTest`・`DesktopRuntimeTest` | architecture/local-connections.md「トレイと画面の寿命」 | manual/local-connections.md「トレイ常駐と画面非表示での起動」 |  |
| LC-17 | トレイの受信箱件数・画面と設定への移動・完全終了 | `ui/shell/DesktopTray.kt`・`InboxNavigation.kt` | 検査 `DesktopTraySmoke`・`DesktopRuntimeTest` | architecture/local-connections.md「トレイと画面の寿命」 | manual/local-connections.md「トレイ常駐と画面非表示での起動」 |  |
| LC-18 | 常駐と非表示起動の設定、今回だけの常駐解除 | `ui/shell/ResidentSettingsPane.kt` | UI `ResidentSettingsPaneTest` | architecture/local-connections.md「トレイと画面の寿命」 | manual/local-connections.md「トレイ常駐と画面非表示での起動」 |  |
| LC-19 | 待受しないワークスペース所有・競合拒否・終了後の引継ぎ | `service/WorkspaceOwnership.kt`・`DesktopRuntime.kt` | `WorkspaceOwnershipTest`・`DesktopRuntimeTest` | architecture/local-connections.md「ワークスペースの所有」 | manual/local-connections.md「ワークスペースの二重利用」 | UX-275 |
| LC-20 | 所有中の独立 CLI の書込拒否と参照・プレビューの継続 | `cli/CliOwnership.kt` | `CliOwnershipTest` | architecture/local-connections.md「ワークスペースの所有」 | manual/cli.md「ワークスペースの所有と書込」 |  |
| LC-21 | 保存版の読取 API・原本座標・権限境界・OpenAPI 契約 | `service/WorkspaceReads.kt`・`server/api/ReadApi.kt` | `WorkspaceReadsTest`・`ReadApiTest` | architecture/local-connections.md「保存済みの読取 API」 | manual/local-connections.md「API の読み取り」 |  |
| LC-22 | 簡単な閲覧・変更設定と文書・操作名の選択、旧権限の維持 | `ConnectionPermissionEditor.kt`・`PermissionDocumentPicker.kt` | UI `LocalConnectionSettingsPageTest`・`ConnectionSettingsStoreTest` | architecture/local-connections.md「接続者と権限」 | manual/local-connections.md「簡単設定」 |  |
| LC-23 | 作業の提案・直接確定と定義済み操作の API、差分・結果照会と再送 | `server/api/WriteApi.kt`・`server/protocol/WriteReports.kt` | `WriteApiTest`・`WorkspaceWorkTest` | architecture/local-connections.md「変更 API」 | manual/local-api-changes.md「API による変更」 |  |
| LC-24 | CLI の明示接続・参照・変更と失敗時の迂回禁止 | `cli/ConnectedCli.kt`・`server/client/LocalApiClient.kt` | `ConnectedCliTest`・`ConnectedCliIntegrationTest`・`LocalApiClientTest` | architecture/local-connections.md「CLI の明示接続」 | manual/cli-connect.md「CLI の接続モード」 |  |
| LC-25 | MCP の参照・提案・直接確定・操作と、停止・失効・保存完了待ち | `server/mcp/McpEndpoint.kt`・`McpTools.kt`・`McpSchemas.kt` | `WorkspaceMcpTest`・`McpCallerTest` | architecture/local-connections.md「MCP のツールと寿命」 | manual/local-mcp.md「外部 AI からの MCP 接続」 |  |
| LC-26 | MCP の実通信・再接続・取消・期限切れ・初期化拒否後の解放と Inspector との相互運用 | `server/mcp/McpEndpoint.kt`・`transport/LocalAuthentication.kt` | `McpHttpTest`・検査 `McpInspectorCheck` | [検査手順](../local-server/README.md) | manual/local-mcp.md「結果・停止・再接続」 |  |
| LC-27 | 同梱 Web・接続コード・Cookie・ログアウト・保存版概要 | `server/web`・`auth/WebLogins.kt`・`web-ui/src` | `WebLoginsTest`・`WebHttpTest`・検査 `WebBrowserCheck` | architecture/local-connections.md「Web の配信と接続」 | manual/local-web.md「接続する」 |  |
| LC-28 | Web のダッシュボード・表・チャート・業務詳細と共有入力・参照・対応外の案内 | `BusinessReadPresentation.kt`・`BusinessWorkspace.tsx` | `WebBusinessTest`・検査 `WebBrowserCheck` | architecture/local-connections.md「Web の業務閲覧」 | manual/local-web.md「ダッシュボードと詳細」 |  |
| LC-29 | Web の業務操作・入力準備・変更確認・保存と結果照会 | `BusinessActionForms.kt`・`WebOperations.kt`・`BusinessOperations.tsx` | `WebOperationsTest`・`WorkspaceOperationsTest`・検査 `WebBrowserCheck` | architecture/local-connections.md「Web の業務操作」 | manual/local-web.md「業務操作」 |  |

## 手動確認だけの機能

自動のテストが無く、手動で確かめている機能。画面の確認は skill `ui-check`（[.claude/skills/ui-check/SKILL.md](../.claude/skills/ui-check/SKILL.md)）の手順で行う。
テストを足したら、その機能の `tests` に書いて `manual_check` を外す。

A-01・A-06・A-15・AG-07・AG-10・AG-18・AG-23・E-07・E-08・E-09・I-02・I-03・I-04・I-05・I-06・I-09・S-07・SK-06・V-02・V-03・V-04・W-05・W-06・W-07・W-10・W-13・X-04

## 公開サンプル

[todos](../samples/todos/README.md)と[テーブルモデル](../samples/todos-sysdev-tablemodel/README.md): 操作・詳細化・SQL生成の検討。
[orders](../samples/orders/README.md): 複数の種類の参照・入れ子の明細（保有）・金額の整合の検査・参照を辿る集計・参照を 2 段辿るカテゴリー別の売上・出荷と取消の操作。
[diagrams](../samples/diagrams/README.md): 人物相関図・クラス図・受注のER図。レンズとフローで共通の記法を使い、列・主キー・外部キー・多重度を表示。
確認: YDEの検証と各READMEの試用手順。
