# UI

UI シェルは「ダッシュボード・編集・台帳・実行・設計」の 5 スペースと左端のアクティビティレール。画面はモーダルでなくスペース内の常設画面で、エージェント実行の状態はコンポーザブルの外（`RunController`）に置く。
フォームは `FormCtx` が編集操作と参照解決を仲介し、閲覧専用モードは `FormCtx` の共有で全ビューへ波及する。

## 画面のパッケージ

画面（`desktop.ui`）は役割で 10 のパッケージに分ける。機能ごとにまとめ、共通の部品を一番下に置く。上の箱が下の箱を使う。

```
              ui.shell（骨格・スペース・右パネル・ツールバー・ウィザード）
  ui.view  ui.graph  ui.file  ui.run  ui.schema  ui.source  ui.action   ← 機能
              ui.form（フォーム編集の部品）
              ui.common（土台の部品）
  desktop の根（AppState・OpenDocument・ScreenMemory・UiPreferences・DesktopUiDispatcher・Main の main）
```

| パッケージ | 中身 | 主なファイル |
|---|---|---|
| `ui.common` | 色と字体・選択メニュー・項目説明・Markdown の描画・ファイル選択・並べ替え・仮想表・差分 | ui/common/Common.kt・ui/common/ChoiceMenu.kt・ui/common/FieldHelp.kt・ui/common/DiffPane.kt |
| `ui.form` | フォーム編集（`FormCtx`・項目の行・表・汎用エディタ・値の種類ごとの欄・配列の削除・表示方式） | ui/form/FormCtx.kt・ui/form/FormFields.kt・ui/form/FormTables.kt・ui/form/GenericEditor.kt |
| `ui.source` | ソースエディタ（編集・補完・強調・構造・構文の状態） | ui/source/SourcePane.kt・ui/source/SourceEditing.kt |
| `ui.schema` | スキーマ設計の画面 | ui/schema/SchemaEditor.kt・ui/schema/SchemaDesignWorkspace.kt |
| `ui.view` | ビューの描画（表・チャート・フロー・レポート・ダッシュボード・準備完了）と、拡張の種類の描画の一覧 | ui/view/ViewRenderers.kt・ui/view/ViewGridPane.kt・ui/view/DashboardPane.kt |
| `ui.graph` | グラフレンズとエンティティページ（配置は ELK） | ui/graph/GraphLensView.kt・ui/graph/GraphLayouts.kt・ui/graph/IdCrossView.kt |
| `ui.file` | ファイル一覧・プレビュー・移動・Markdown の画面・file 型の欄と画像 | ui/file/FileTreePane.kt・ui/file/FileEditor.kt・ui/file/ImagePane.kt |
| `ui.run` | 実行スペース・自動化・計測・AI の接続と生成 | ui/run/RunsSpace.kt・ui/run/AiGenerationDialog.kt |
| `ui.action` | 操作のパネル（型の操作とアクション） | ui/action/ActionPane.kt |
| `ui.shell` | 骨格（`AppRoot`・編集領域・ステータスバー）・スタート画面・コマンドパレット・スペース・右パネル・ツールバー・ウィザード・連携（skill）の設定 | ui/shell/AppRoot.kt・ui/shell/Shell.kt・ui/shell/SidePanel.kt |

- 入口の `Main.kt` は `main`（窓）とショートカットの `handleShortcut` だけを持ち、画面は `ui.shell` に置く。
- form は機能のパッケージを import しない。file 型の欄・文書ビューに埋め込む拡張のビュー・スキーマを書くときの欄は、form の差し込み口 `FormSlots`（ui/form/FormSlots.kt）へ上から渡す。
- パッケージの間に循環は無い。機能どうしの矢印が 4 本残っている（view → graph・view → run・file → run・graph → run）。新しく増やさない。

## 部品

| 部品 | ファイル | 責務 |
|---|---|---|
| `Main.kt` | desktop/Main.kt | エントリポイント・ウィンドウ・ショートカット（`handleShortcut`）。画面は `ui.shell` に置く |
| `AppRoot` / `StartScreen` / `CommandPalette` | ui/shell/AppRoot.kt・ui/shell/StartScreen.kt・ui/shell/CommandPalette.kt | 骨格（左右の領域・編集領域 `EditorArea`・ステータスバー `StatusBar`・改名の確認）・スタート画面・コマンドパレット。配置と表示の設定は `UiPreferences.kt` の `uiPreferences` |
| `UiSpace` / `showSpace` / `showWorkspaceView` / `navigateToPath` / `openCrossView` / `openViewGrid` / `goBack` / `goForward` | desktop/AppState.kt | スペースとナビゲーション履歴（`NavLocation`） |
| `ScreenMemory` | desktop/ScreenMemory.kt | 画面の覚え（畳み・タブ・絞り込み・チェック・ツリーの展開。画面を離れても保持し、切替時は初期化してツリーの展開を復元する） |
| `ActivityRail` / `LedgerSpace` | ui/shell/Shell.kt | 5 スペースを切り替えるレールと、一覧・ビューグリッド・グラフ・エンティティをタブで束ねる台帳スペース |
| `RunController` / `RunsSpace` | ui/run/RunsSpace.kt | 実行スペース（受信箱・手動実行・自動化・計測評価）。実行状態機械（AppRoot 生存期間） |
| `DesignSpace` | ui/shell/DesignSpace.kt | 設計スペース（定義文書の種類別カタログ・新規作成・スキーマ推測・skill） |
| `StructuredForm` / `FormAnchor` / `Highlightable` | ui/form/StructuredForm.kt | スキーマと通常文書が共有する構成一覧とスクロール連動・項目の位置の登録・ハイライトとスクロール |
| `FormView` / `DocumentMetadataEditor` / `SameIdOccurrencesStrip` | ui/form/FormView.kt | フォームの入口（文書を開いたときの描画の振り分け）・文書の情報・同じ ID の出現の帯 |
| `FormCtx` / `defaultNode` / `autoValueHint` | ui/form/FormCtx.kt | フォームの文脈（編集の入口・閲覧専用の封じ・展開の覚え）と項目の既定値 |
| `FormSlots` / `LocalFormSlots` / `SchemaAuthoring` | ui/form/FormSlots.kt | フォームの差し込み口（file 型の欄・文書ビューに埋め込む拡張のビューは `AppRoot` が、スキーマを書くときの欄は `SchemaEditor` が渡す） |
| `SchemaRootForm` / `DocViewSections` | ui/form/FormDocView.kt | スキーマ駆動フォームの既定の描画と、文書ビューの節・被参照の表 |
| `ObjectFields` / `FieldRow` / `FormFieldLayout` / `ValueEditor` | ui/form/FormFields.kt | フォームの骨組み: 項目の並び・1 行の形・値の種類で編集欄を選ぶ・型の不一致の直し |
| `ScalarTextEditor` / `BooleanEditor` / `EnumEditor` / `IdEditor` | ui/form/ScalarEditors.kt | 一つの値の編集欄（文字・真偽・選択肢・ID） |
| `RefEditor` / `RefScalarEditor` / `DocRefScalarEditor` | ui/form/RefEditors.kt | 参照の編集欄（エンティティへの参照・文書への参照。参照先の名前と飛び先） |
| `ArrayEditor` / `NestedObjectEditor` / `MapEditor` | ui/form/CollectionEditors.kt | 入れ物の編集欄（配列・入れ子のオブジェクト・map。畳みと要約） |
| `GenericEditor` / `GenericMapEntryRow` | ui/form/GenericEditor.kt | 汎用エディタ（スキーマの無い文書・スキーマの外のキー。キーの改名は表記と順序を保つ） |
| `isStructuredFormValue` / `stackFormField` | ui/form/FormLayout.kt | 解決済みの型・実際の値と利用可能幅によるフォーム配置の判定（純ロジック） |
| `FieldHelp` / `FieldHelpPosition` | ui/common/FieldHelp.kt | 説明の短い表示とホバー・固定表示、画面内への位置調整 |
| `TextFieldEscape` | ui/common/TextFieldEscape.kt | Esc の一押しと変換状態を追跡し、候補の閉鎖とフォーカス解除を分ける |
| `TextFieldHistory` / `HistoryShortcut` | ui/common/TextFieldHistory.kt | 文書入力の Undo/Redo を接続し、IME 変換中の一押しが全体履歴へ伝わるのを防ぐ |
| `ChoiceMenuItem` | ui/common/ChoiceMenuItem.kt | 選択一覧の現在値をチェック・背景色・selected で示す共通行 |
| `ChoiceMenu`・`ChoiceOption` | ui/common/ChoiceMenu.kt・ChoiceOptions.kt | 高さ制限・遅延描画・現在値への初期スクロール・検索・説明全文を共有 |
| `FlowViewPane` / `EmbeddedFlowPane` | ui/view/FlowViewPane.kt | 図と原本フォームを併記する単独・埋め込み表示（[views](views.md)） |
| `ObjectTable` / `ArrayTable` / `MapTable` / `RefCell` / `tableColumnWidths` | ui/form/FormTables.kt | 配列と map のテーブル表示（参照セルは ID・参照先の名前・横ぐしへの Hub） |
| `AppToolbar` | ui/shell/AppToolbar.kt | 全体メニュー・移動と検索・左右ペインの開閉 |
| `slotAt` / `CompactTextField` / `Palette` / `PanelShape` / `AppFonts` / `PaneHeader` / `UiTooltip` | ui/common/Common.kt | Slot の解決・共通の入力欄と色と角丸・同梱フォント（等幅 Cica。資源が無ければ OS の等幅へ戻す）・ペインの見出しと操作名の補足 |
| `ActionMenuItem` | ui/common/ActionMenuItem.kt | 操作のメニューの 1 行（行の ⋯・個票・文書ヘッダーで同じ前提と理由を出す） |
| `VirtualTable` / `TableColumn` / `visibleColumnRange` | ui/common/VirtualTable.kt | 共通の表。見出し行と左の固定列は動かず、行は遅延描画、列は見える範囲だけ組み立てる（ビューグリッド・一覧ビューが使う） |
| `ReorderableList` / `ReorderRowScope` | ui/common/Reorder.kt | ドラッグで並べ替える縦リスト（取っ手からだけ掴める。ライブラリ Reorderable を包む） |
| `TemporalPickerButton` / `TemporalPick` | ui/form/TemporalEditor.kt | 日付・時刻・日時のピッカー（ポップアップ）と、正準の文字列との変換（純ロジック） |
| `FilePickers` / `rememberDirectoryChooser` / `rememberFileChooser` / `rememberFileSaver` / `LocalDialogParentWindow` | ui/common/FilePickers.kt | OS ネイティブのファイル・フォルダ選択と保存先の選択、最後に選んだ場所の記憶（ライブラリ FileKit を包む） |
| `MarkdownView` / `openExternally` | ui/common/MarkdownView.kt | Markdown の描画（ライブラリ multiplatform-markdown-renderer を包む。色と文字は Palette） |
| `MarkdownPane` / `resolveMarkdownLink` | ui/file/MarkdownPane.kt | Markdown 文書と内蔵ヘルプの表示（表示とソースの 2 タブ・リンクの行き先の判定は純ロジック） |
| `Help` | desktop/Help.kt | 内蔵ヘルプの題目と本文（ビルド時に資源 `help/` へ入れた利用者マニュアル） |
| `FileEditor` / `resolveFilePath` / `fileValueFor` | ui/file/FileEditor.kt | file 型の入力欄（選ぶ・OS で開く・解決した場所と有無の 1 行・画像なら下に表示）。場所の解決と選んだファイルの書き方は純ロジック |
| `ImagePane` / `EmbeddedImagePane` / `ImageCanvas` / `fitZoom` | ui/file/ImagePane.kt | 画像の表示（幅に合わせる・100%・±・Ctrl＋ホイール・ドラッグ＝フロービューと同じ操作）の埋め込みと全面表示で、更新時刻で読み直す |
| `BarChart` / `ChartSeries` | ui/view/Charts.kt | 縦棒グラフ（積み上げ・並べ・凡例・マウスで値。ライブラリ Vico を包み、系列の色は Palette の `ChartReady` / `ChartPending` など意味で決める） |
| `ReadinessPane` / `readinessChartData` | ui/view/ReadinessPane.kt | 準備完了レポート（台帳の「準備」タブ: タイル・型別とフェーズ別の図・エンティティの表、図のデータ整形は純ロジック） |
| `SidePanel` | ui/shell/SidePanel.kt | ID インスペクタ（検証・関係・出現・操作の 4 タブ。台帳スペースでレンズを表示しているときは Main が右ペインに `LensSidePanel`＝ui/graph/GraphLensView.kt の型・関係・辺の 3 タブを出し、右ペインの開閉は `AppState.rightPaneOpen`） |
| `ActionTabContent` / `ActionPanelHost` / `TargetPicker` | ui/action/ActionPane.kt | 右ペインの「操作」タブ（使える操作の一覧と作業中の操作の開き直し）と、操作の浮いたパネル（対象の欄・受け取る値・確認・差分・適用。[actions](actions.md)） |
| `InitWizardHost` | ui/shell/InitWizard.kt | 初期化ウィザード（新しいワークスペースを作る浮いたパネル＝場所・始め方・内容・確認の 4 歩と、問いの素朴な入力欄。計画はカーネルの `WorkspaceInit`、[workspace](workspace.md)） |
| `FileTreePane` / `SourcePane` / `DiffPane` / `SchemaEditor` / `IdCrossView` / `ViewGridPane` / `WorkspaceViews` / `AiGenerationDialog` / `AutomationPanel` / `ImprovementPanel` / `SkillsPanel` | ui/ | 各ペイン |
| `GraphLensView` | ui/graph/GraphLensView.kt | グラフレンズ（配置の切替・箱の入れ子と矢印・ひし形・点線の描画・クリックでエンティティページへ着地し、配置は背景で計算し、できるまで前の絵を出す）と操作（ホイール拡縮・箱のドラッグ＝`withMovedNested`・見出しの印で開く・畳む・ホバー強調・右クリック・PNG 保存・ミニマップ）。節点と辺の決め方は ui/graph/LensGraph.kt（[graph-query](graph-query.md)） |
| `GraphLayouts` / `LensLayoutKind` / `LensArrangement` / `fitViewport` | ui/graph/GraphLayouts.kt | 節点と辺の配置（放射・階層・応力はライブラリ ELK を包み、力学は `GraphLayout`）と画面に収める倍率の計算（Compose に依存しない純ロジック） |
| `SourceHighlight` / `SourceMerge` / `SourceEditing` / `GraphLayout` / `DiffPane.calculateDiff` | ui/ | テスト可能な純ロジック（YAML トークナイザ・行差分と 3 者マージ・ソースの基本操作と検索・軽い力学レイアウト・差分） |

## UI シェル

- 全体操作は `AppToolbar` の標準メニュー（ファイル・編集・表示・ヘルプ）に集め、移動・検索・ペイン操作を上部の操作列に常設する。
- 左右ペインの開閉ボタンは対応する側に置き、領域アイコンと文字、表示状態、ツールチップを併用する。ペイン内の `PaneHeader` にも閉じる操作を置く。
- ネイティブのウィンドウタイトルはワークスペースの絶対パス・アプリ名・未保存の印で構成する。文書名は編集領域の見出しに表示し、相対パス・スキーマ名は文書名から開く情報メニューに置く。
- `EditorChrome` は文書ヘッダーと編集方式の二段で構成する。未保存・閲覧専用・履歴・保存は常設し、AI と文書操作はメニュー、スキーマの補助画面は編集方式の右端から開く。
- `AppTheme` は暖色寄りのライトと濃灰色のダークを共通パレットで提供する。`Palette` の選択は Compose の状態で、切替時は直接参照する部品も再描画する。


- **スペースは `app.UiSpace`（DASHBOARD / EDIT / LEDGER / RUNS / DESIGN）**。`workspaceView` は0=文書、1=一覧、2=グラフ、3=エンティティ、4=ビューグリッド、5=フロー、6=準備。
  AppStateのナビゲーションがEDIT／LEDGERとの同期と履歴を担う。業務スペースの遷移と画面の覚えは [dashboards.md](dashboards.md) を参照。
- **スペースの移動も「戻る・進む」の対象**: `NavLocation` はスペースを含み、`showSpace` / `showWorkspaceView` / `openCrossView` / `openViewGrid` も履歴に積む。復元は EDIT＝ファイル＋ハイライト＋エディタのタブ、LEDGER＝下位ビュー＋選択 ID、DASHBOARD＝ダッシュボード＋対象ID、RUNS / DESIGN＝スペースのみ。同じ場所の再選択は積まない。新しい移動で「進む」履歴は破棄。削除済みファイルは読み飛ばし、ワークスペース切替でクリア（`NavigationHistoryTest`）。
- **「戻る」が戻すのは場所で、見え方は画面が覚える**: 畳み・タブ・絞り込み・チェック・ツリーの展開のような見え方は `ScreenMemory`（`state.memory`）に置き、画面が描画の木から外れても捨てない。履歴（`NavLocation`）には積まない。編集画面のタブ（フォーム・ソース・差分）は文書ごとに最後に見ていたものを覚え、覚えの無い文書は直前の文書のタブで開く（`AppState.showFile`。ソースで書いている最中に別の文書を開いてもソースのまま）。
  - 覚えは「あれば使う・無ければ既定」。再読込で無くなった文書・型・種別の鍵は読まれないだけにし、画面側で今の候補と突き合わせる（一覧の種別と状態・ツリーの選択・レンズの配置）。
  - 寿命はワークスペース（切替で初期化し、ツリーの展開と表示設定は永続化した覚えから復元する）。文書の削除・改名はその文書のぶんだけ消す・移す。操作の途中（ダイアログ・メニュー・入力途中の欄）は `remember` のまま（`ScreenMemoryTest`）。
- **常設画面と浮いたパネルの線引き**: エージェント実行・自動化・計測評価・skill 操作のような、実行の状態を持つ・長く続く作業は実行／設計スペースの常設画面。値を集めて 1 回で適用する短い作業（AI 生成・改名・統合・スキーマ推測・操作）は浮いたパネル（AlertDialog）にできる。それ以外の AlertDialog は破壊的操作と承認の確認だけ。エージェントの質問はモーダルにせず非モーダルカード。気づきはレールとステータスバーのバッジ。
- **エージェント実行の状態は `RunController`**（AppRoot 生存期間・ワークスペース単位）。スペースを切り替えて実行画面がコンポジションから外れても状態が失われない。
- **操作は浮いたパネル**: 入口（エンティティページの操作メニュー・フォームとビューの行の ⋯・文書ヘッダーの「文書操作」メニュー・コマンドパレット・右ペインの「操作」タブ）はどこでも `AppState.startAction` を呼び、パネルは `Main` が `ActionPanelHost` でスペースに関係なく載せる。
- **初期化ウィザードも浮いたパネル**: スタート画面（ワークスペースが無い）からも開いている画面からも `AppState.startInitWizard` で開き、`Main` が `InitWizardHost` を窓の直下に載せる。値を集めて 1 回で書く短い作業なので常設画面にしない。スタート画面の第一のボタンはこれで、「ワークスペースを開く…」が隣に並ぶ。作業の状態は `AppState.actionWork`（`remember` に置かない）で、隠しても残り「操作」タブから開き直せる。受け取る値はフォームの部品で編集する（[actions](actions.md)）。
- **受信箱はデータを持たない射影**: 承認待ち（`AutomationEngine.pendingSnapshot`）・質問（`RunController.pendingQuestion`）・外部変更競合（`AppState.conflictedDocs`）を集約表示するだけ。バッジ数もこの集約から導出する。
- **設計スペースは「定義も YAML 文書」の入口**: 種類別カタログはファイル一覧から導出し、編集は編集スペースの既存エディタへジャンプする。定義専用の編集経路を新設しない。
- **アプリ全体のショートカットはウィンドウで受ける**: 戻る／進む（`Alt+←` / `→`）・保存・Undo/Redo・コマンドパレットは `Window(onKeyEvent = …)`（`Main.kt` の `handleShortcut`）。部品の `Modifier.onKeyEvent` は焦点のある子がいるときにしか呼ばれず、ジャンプの直後のように焦点が無いときはキーが届かない。テキスト入力が自分で消費するキーは入力側が先に受ける。
- **コマンドパレット**（Ctrl+K）: ID・タイトル移動・ファイル検索・本文検索（構造検索を未保存優先の loader で「見たまま」実行）・台帳ビュー切替・スペース切替・保存・ワークスペース切替。
- **永続化**: UI レイアウト（ペイン幅・開閉）・テーマ・最近開いたファイルとワークスペース・閲覧専用モード・ファイル選択で最後に選んだ場所（用途ごと）は `Preferences`。
- **画像の全面表示も「表示だけの文書」**: `AppState.openImage` は `openFile` が拡張子（`FilePath.isImage`）で振り分け、`NavLocation` の file に載る（戻る・進むの対象）。拡大率と位置は `ImageCanvas` の中の状態で、YAML には書かない。
- **Markdown 文書と内蔵ヘルプは編集スペースの「表示だけの文書」**: `AppState.openMarkdown`（ワークスペース内の `.md`）と `openHelp`（資源 `help/` の題目）は `openDoc` と排他で、`openFile` が拡張子で振り分け、`NavLocation` に載るので戻る・進むの対象。Markdown は文書キャッシュに入れず、編集・Undo の対象にしない（編集は外部のエディタ。案 B＝アプリ内編集は需要待ち）。
- **ファイル選択は OS ネイティブ**: `rememberDirectoryChooser` / `rememberFileChooser` が返す関数をボタンから呼ぶ。親ウィンドウは `Main.kt` の Window が `LocalDialogParentWindow` で渡す。Swing の `JFileChooser` は使わない。

## フォームと FormCtx

- `FormCtx` が編集操作（`writeScalar`・`markEdited`）と参照解決（`resolvable`）・対象文書（`doc`）・既定の展開（`defaultExpanded`）・ハイライト（`highlightInside`）・指摘（`issues`）・アタッチ（`attachments`）・AI の関連文書（`aiRelatedFiles`）を仲介する。フォームを再利用する画面（エンティティページ・ビューグリッド・文書ビュー）へ自動で波及する。
- フォームは 9 つのファイルに分かれる（部品の表の FormView.kt〜GenericEditor.kt）。入口と文脈・骨組み・値の種類ごとの編集欄・表・汎用エディタの順に下へ使う。ファイルの間で使う宣言だけ `internal` にする。
- object の子フィールドは `ObjectFields`（`effectiveFields` 経由）で描く。列挙を複製しない。
- 項目名と入力欄は `FormFieldLayout` で配置する。解決済みの型が object・map・array、または実値が Map・Seq なら項目名の下へ全幅で並べ、単一値は幅が狭いときだけ縦に並べる。
- 配置判定は `FormLayout` に集約し、文書メタデータ・スキーマの項目・型付き map・汎用 map で共用する。未作成の複合型も縦配置とし、属性付き参照・any・型不一致の残留値は実値の構造も見る。
- `FormSpacing` の余白と `FormGroup` の縦線・字下げで複合値を区切る。字下げは枝ごとに累計の上限を持ち、深い構造でも入力幅を奪い続けない。
- 編集時の文書情報は `_document` の畳み、他文書での定義は `ScreenMemory.documentOccurrencesOpen` で開閉を覚える。いずれも既定は畳む。
- 項目の説明は `FieldHelp` が `MarkdownView` で描く。判定範囲・表示・固定の操作は [UI 操作規則「項目説明の予告と全文」](ui-interactions.md#項目説明の予告と全文) に従い、開閉状態は `remember` に置く。
- 配列の追加ボタンは `AppState.addArrayElement` を呼ぶ（要素の中身は型の `create` と配列の `insert` の決まり。受け取る値があれば右ペインのカード。[actions](actions.md)）。フォーム側で初期値を組まない。
- 基底の種類別のエディタ（複数行と入力欄の案内は型から引き継ぐヒント）・必須マーク（`requiredFrom` はフェーズ名つき）・説明・折りたたみ。id 型は既存 ID の補完ピッカーと連番の提案（`IdSuggest`）。`format` と `display: grouped` は見せ方（`ValueFormat.display`。`CompactTextField` の `unfocusedText`＝フォーカス中は素の値）。スキーマの型と値の構造が合わない残留値は修復 UI（値を消す・配列やスカラーに変換）。
- 指摘のあるフィールドは入力枠が色づく（エラー＝赤・警告＝オレンジ。`FormCtx.issues` 経由で全指摘種別に効く）。
- 同一 ID の他文書出現ストリップ（`SameIdOccurrencesStrip`）が相手側の情報の表示とジャンプを提供する。
- id 型の欄は定義位置そのものなので、ジャンプの行き先は横ぐし（エンティティページ）だけ。右端の Hub ボタン（`EntityPageButton`。ビューグリッドの ID セルと同じ印）と「出現 n 件／被参照 n 箇所」の注記が `openCrossView(id)` へ直行し、閲覧専用のテーブルの ID セルにも同じボタンを出す。参照欄の→は「定義へ／横ぐしへ」の 2 択のまま（行き先が 2 つある）。テーブルの参照セルは → に加えて Hub を並べ、横ぐしへは 1 クリックで行ける。
- 参照先の名前の解決は `FormCtx.refTargetTitle`（未保存の編集を含む自文書の ID を優先し、idPatterns と scope で絞る）に一元化し、参照欄（欄の中の右端）とテーブルの参照セル（欄の下）が共用する。

## 入力と Esc

- 操作の意味と例外は [UI 操作規則「フォーム入力からの退出」](ui-interactions.md#フォーム入力からの退出) が定める。
- `CompactTextField` は `TextFieldValue` で選択と変換状態を保持し、文字列が変わったときだけ呼び出し元へ通知する。表示形式による文字列の切替は値の変更として通知しない。
- フォームの呼び出し元は `clearFocusOnEscape = true` を指定する。`SchemaTextField` も共用し、定義追加ダイアログでは無効にする。
- `TextFieldEscape` は preview で変換状態を覚え、子が消費しなかった KeyDown で操作を決める。キーリピートは次の層へ進めず、KeyUp またはフォーカス離脱で一押しの状態を解放する。
- ID・参照・文書参照を含む選択一覧は `ChoiceMenu` が自身のフォーカスを持つ。検索欄の `onCompositionChange` を使って IME の Enter・Esc を優先し、`ChoiceKeyPress` がキー解放まで操作を保留する。
- 日付・時刻ピッカーは自身のフォーカスと既存の閉鎖処理を使う。フォーカスを残す補助画面を追加する場合は、開いている間だけ `CompactTextField.onDismissPopup` を渡す。

## 入力と Undo

- `HistoryNoticeBanner` は `AppRoot` のツールバー直下で横断履歴を通知する。`AppState.historyNotice` を読み、対象一覧のリンクは `openHistoryTarget` を通す。
- 成功した Undo/Redo は `AppState.returnToHistoryLocation` が編集元の文書・タブまたはビューを復元する。画面情報の保持は [editing-history.md「履歴と dirty」](editing-history.md#履歴と-dirty) を参照。
- 同一文書の通知は `StatusBar`、別文書を含む通知は上部に表示する。規則と寿命は [UI 操作規則「横断履歴の通知」](ui-interactions.md#横断履歴の通知) を参照。

- 操作の意味と例外は [UI 操作規則「フォーム入力の Undo と Redo」](ui-interactions.md#フォーム入力の-undo-と-redo) が定める。
- `CompactTextField.onHistory` に接続した欄は preview で文書履歴を呼ぶ。変換中は子へ渡し、未消費のキーは欄で止める。`TextFieldHistory` が一押しの状態を保持する。
- `FormCtx.historyHandler` は `AppState.ownsDocument` でキャッシュの同一文書か確認し、一時フォームを除外する。直接入力だけに渡し、追加・改名前の欄には渡さない。
- スキーマ設計は `LocalSchemaHistory` と `SchemaTextField` を通す。定義追加の入力は `workspaceHistory = false` で除外する。
- キー判定は `historyShortcut` をウィンドウと共有し、実行は `undoFromUi`／`redoFromUi` を通す。履歴や保存の新しい経路は作らない。

## 閲覧専用

- `FormCtx.readOnly`（既定値は `AppState.readOnlyMode`）が編集 UI の表示分岐と `markEdited` / スカラー書込のガード（木の変異前に `check` で拒否）を担う。
- FormCtx を共有する全ビューへ自動波及させ、ビューごとの読み取り専用分岐を別実装しない。ジャンプ・バックリンクは閲覧行為として残す。切替は「表示」メニューで、永続化する。
- 閲覧専用が封じるのは編集（未保存の木の変更）だけ。スキーマに定義した操作は確認してから書いて保存するので閲覧専用でも通し、入口（操作メニュー・行の ⋯・ヘッダ・パレット・検査の指摘）を閲覧専用で隠さない。組込みの改名・統合・削除は隠す（[actions](actions.md)「規律」）。

## テーブル表示

- フォーム内の配列（object 要素）と map（object 値）をテーブルにするかの適否と列集合は `Display.tableColumns`（自動判定「列 2〜8 かつ宣言フィールドの過半がセル型」＋スキーマの `display: table / list`）だけが決める。
- 列は要素 object の宣言フィールドのうちセル型だけ。enum `cases` の追加項目など列外の項目は展開フォームで編集する。map は `MapTable` が表示専用のキー列を先頭に足す。
- セル編集は通常の `Slot` 経路を再利用し、テーブル専用の書込経路を作らない。列幅は型別の最小幅を保証しつつ余白を文字系の列へ等分し、収まらなければ横スクロール。閲覧専用モードはテキストセル。
- `ObjectTable` は見出しと行の列幅・横スクロールを同期する。展開フォームはスクロールの外で表示領域の幅を使う。
- エンティティ参照のセルは `RefCell`: 上段に ID、下段に参照先の名前、右端に横ぐしへの Hub（ID セルと同じ印・上書き索引に出現がある ID だけ）。編集モードも通常の参照欄（候補・→ の 2 択）のまま、名前だけ欄の下に出す（欄の中の右端はセル幅で ID を押しのける。ビューグリッドと同じ流儀）。属性付き参照は to の表示だけで、編集は行の展開フォーム。文書参照（`target: document`）は対象外。
- 配列の行とフォーム内の配列要素は取っ手（⋮⋮）からのドラッグで並べ替える（`ReorderableList`）。ドロップで 1 回だけ `moveSeqItem` ＋ `markEdited`（Undo 1 回分）。取っ手を置かなければ並べ替えられない（map の表・閲覧専用）。
- `ReorderableList` は再コンポーズごとに要素列をコピーする。可変リストの直接渡しや `remember(items)` での保持では、追加・削除・並べ替え後の位置が更新されない。
- 配列削除は `ArrayDeleteButton` を共用する（[UI 操作規則](ui-interactions.md#配列要素の削除)）。
- ビューグリッドと一覧ビューの表は共通部品 `VirtualTable`（ID などの固定列・見出し行の固定・見える列だけ組み立てる）。セルの中身と編集経路はそれぞれの画面のまま。
- 日付・時刻・日時の欄は右端のボタンでピッカー（ポップアップ。モーダルではない）。書込は `FormCtx.setScalar` の同じ経路で、値は正準の文字列のまま。

## 畳みとハイライト

- 要素位置は YPath で紐付き、`state.highlightPath` に設定すると `Highlightable` がスクロール＋一時ハイライトする（対象を画面端でなく中央寄りに出す）。
- ハイライト対象が畳みの中にある場合は自動展開する。畳まれた区画の中は `Highlightable` がコンポーズされないため、畳みを持つエディタ（配列・ネスト object・map・テーブル行展開）は `FormCtx.expanded(path)` で開閉を決める（ジャンプ先が中なら開く）。開閉は `ScreenMemory` が文書とパスで持ち、ジャンプ後も画面を離れても維持する。
- 畳み込みサマリ: 畳んだ object は「ID タイトル（n 項目）」、array は「n 件: 先頭要素の要約, …」（`Display`）。既定はルートから 2 階層まで展開、以深は畳む（`FormCtx.defaultExpanded`。エンティティページの埋め込みフォームは要素基準の相対深さ）。モード切替でも展開状態を維持する。
- 移動は必ずナビゲーション系メソッド（`openFile` / `navigateToPath` / `openCrossView` / `openViewGrid`）を通す。`highlightPath` / `crossViewId` / `viewGridId` を直接代入すると履歴に残らない。

## 構成ツリーとフォーム表示

- `StructuredForm` が選択項目・全体とツリー連動・全体のみの配置を共有する。`EditorChrome` の `FormDisplayControls` は三つの方式アイコン・固定ピン・指定パネルを既存の編集方式の行に置く。
- 通常文書は `DocumentFormWorkspace`、スキーマ設計は `SchemaDesignWorkspace` が内容を渡す。幅が足りなければツリーと編集を切り替える。
- `FormOutline` は原本パスの表示用射影で、型・条件付き項目・要約・ID は既存の `resolve`・`effectiveFields`・`Display` に委ねる。未作成値を表示のために作らない。
- 文書ビューの順序・選別を反映し、同じ原本パスはツリーに一度だけ載せる。配列の選択はノード同一性で追随し、Undo による置換時は一意な解決済み ID を使う。
- 選択・ツリーの畳みは `ScreenMemory.formNavigation` に文書と設計／通常フォームの組で保持する。フォームの畳みとは独立し、改名・削除・ワークスペース切替に追随する。
- `ScreenMemory.formDisplay`（`FormDisplayMemory`）は固定フラグ・直前表示・ファイルの明示指定を持つ。通常フォーム・スキーマ設計・汎用フォームは方式の指定を共有する。
- `modeFor` は固定なら直前表示、可変なら指定または直前表示を返す。フォームの操作行を表示した `SideEffect` で `show` を呼び、実際の表示だけを次へ引き継ぐ。ソースや差分の表示は引き継ぎを更新しない。
- 方式アイコンと構成の×は `choose` で表示と指定を更新する。同じ方式の再選択も指定で、固定中は固定する方式も変わる。×は狭幅でも全体のみへの明示変更とし、編集へ戻る操作と分ける。
- 指定パネルの `setSpecified` は固定中の表示を変えない。未指定への解除は表示を維持する。固定切替は指定を変更せず、解除時に現在ファイルの指定を再適用する。
- 自動表示は指定を作らない。改名・削除は指定の鍵を追随させ、ワークスペース切替で固定・直前表示・指定を初期化する。文書内容や Undo には関与しない。
- `FormAnchor` と `FormPositions` の座標は表示中だけ保持する。ツリー選択は祖先と行詳細を開いて見出しへ移動し、手動スクロールは上端付近の最も深い項目を選ぶ。
- 移動要求によるスクロール中は逆方向の追従を止める。終了時はその場の位置から再判定する。スクロール量と項目座標を監視し、初回配置・折り畳みの再配置にも追従する。
- ツリーへの反映は最新の配置と一致する選択だけを使い、追従による選択をフォームへの移動要求へ戻さない。選択対象の祖先を開き、選択行を可視範囲へ入れる。
- `FormFoldHeader`・`FormFold` が折り畳みの見出しと字下げを揃える。入力は既存の `FieldRow`・`Slot`・`FormCtx` を再利用する。
- Calculus 上は既存 Term の表示射影と位置選択に限る。Query・Expression・Predicate・Aggregate の意味、Rewrite の書込経路、Construct の生成規則は追加しない。

## スキーマ設計

- `SchemaDesignWorkspace` は基本設定・ルート・名前付き定義を一つの構成ペインに集め、詳細と横並びにする。狭い領域では構成と詳細を切り替え、項目選択時に詳細へ戻す。
- `SchemaOutline` は生の宣言を表示用に射影する。意味解決や参照の展開は行わず、名前付き型の基底は既存の `TypeChoices` を利用する。
- `SchemaDesignFields` は専用欄のない項目と未知キーを共通フォームへ渡す。
- `SchemaAdditionalOutline` は共通の `formValueOutline` で追加区画の子まで導出し、選択した原本パスを `Slot`・`FieldRow` へ渡す。
- 配列の選択は祖先の移動にも追随する。Undoでは一意な操作ID・入力キーを使い、削除時は親へ戻る。重複キーでは別要素を推測しない。
- 全体表示で子を選ぶと祖先を展開する。自由な値の子も `SchemaValueEditor` の `FormAnchor` でスクロールに連動する。
- 項目の追加設定にも同じ補完を使い、型ごとの `cases` はメタスキーマの実効フィールドから引く。操作の入力と採番の構造は `builtin-schemas/schema.yaml` が持ち、UIに別の型定義を作らない。
- `SchemaValueEditor` は設計画面の `any` と式の値を、値・配列・マップとして編集する。`SchemaValueForms` は手順・条件・判定・照合・ひな型の入力開始用の形と追加候補だけを持つ。
- 入力支援は Term の編集であり、式・照合・判定・処理の意味は既存の Expression / Query / Predicate / Construct / Rewrite に委ねる。独自の評価器や保存経路を持たない。
- 置換・削除は確認してから `Slot` と `FormCtx.markEdited` を通す。閲覧専用では編集操作を隠し、未知のキー・既存の別記法を表示だけで変換しない。
- メタ定義・構成と選択・入力例のテストは [開発規約](../CONVENTIONS.md) に従う。
- ルート・配列要素・マップ値・条件付き項目を同じ選択モデルで扱う。選択は YAML パスで保持し、移動操作では追随、ソース編集等で消えた場合は存在する親へ戻す。
- 区画・定義・選択位置は `ScreenMemory` に置く。構成の開閉は共通のナビゲーション状態、設定群の折り畳みは `FormCtx` を通す。
- 構造編集は `FormCtx`・既存の `Slot` と編集通知を使い、一括変更は既存のリファクタリング計画へ渡す。新しい評価器・保存経路・スキーマ語彙は持たない。
- 設定入力は共通の `CompactTextField`・`FormFieldLayout`・`Palette`・`FormSpacing` を利用し、閲覧専用状態を入力欄と編集操作へ伝える。

## エラー処理

- ファイル I/O・パースは `runCatching` で受け、ユーザー操作の結果は `Result` を返して呼び出し側が `state.status` に反映する。
- 入力検証の失敗は `require(...) { "日本語メッセージ" }`、内部異常は `error("日本語メッセージ")`。メッセージはそのまま UI に表示される前提で書く。

## 規律

- `YNode` は Compose の State ではない。木を表示するコンポーザブルは `doc.version` / `state.indexVersion` を読んで購読する。
- 可変の木を読む入力部品は、親だけでなく部品自身でも編集通知を購読する。`SchemaEditor` は `key(doc)` で入力途中の文字・ダイアログ・解析キャッシュを文書に閉じ、タブと畳みは `ScreenMemory` から復元する。
- フォーム系の新しい編集 UI は `FormCtx` を通し、閲覧専用モードの表示分岐を入れる。
- `uiSpace` の書き換えは `showSpace` / `showWorkspaceView` / ナビゲーション系メソッド経由に限る。新しい画面をモーダルで作らない（確認ダイアログと、値を集めて 1 回で適用する短い作業の浮いたパネルを除く）。エージェント実行の状態を `remember` へ戻さない。
- アプリ全体のショートカットは `Main.kt` の `handleShortcut` に足す。
- 画面のファイルは「画面のパッケージ」の役割に合うパッケージに置く。`ui.common` に機能の画面を足さない。
- 新しく畳みを持つ UI は `FormCtx.expanded` / `setExpanded` を通す（ジャンプ先の自動展開と画面の覚えが付いてくる）。
- 画面を離れても残したい見え方（畳み・タブ・絞り込み・チェック・展開）は `remember` に置かず `state.memory`（`ScreenMemory`）へ。覚えは「あれば使う・無ければ既定」で、古い鍵を誤りにしない。操作の途中は `remember` のまま。
- `Dispatchers.Main` は使わない。背景処理からの文書生成・公開・画面遷移は `DesktopUiDispatcher` で EDT へ戻す（CONVENTIONS「Compose UI の書き方」）。
- 入力欄は `CompactTextField`、色は `Palette.*`、角丸は `PanelShape`。生の色や Material 既定色をビューに直接書かない。
- 等幅の文字は `AppFonts.Mono`（同梱の Cica。TTF は git に入れず、build.gradle.kts の `downloadFonts` がビルド時に取得して資源 `fonts/cica/` に入れる）。`FontFamily.Monospace` を書かない。
- 外部の UI ライブラリは ui/ の 1 ファイルで包み、その型を他のファイルへ出さない（FileKit＝FilePickers.kt・Reorderable＝Reorder.kt・Markdown＝MarkdownView.kt・Vico＝Charts.kt・ELK＝GraphLayouts.kt）。差し替えるときはその 1 ファイルだけを書き換える。
- 表は `VirtualTable`、Markdown は `MarkdownView`、ファイル選択は `rememberDirectoryChooser` / `rememberFileChooser`、並べ替えは `ReorderableList`、グラフは `BarChart`（Vico を包む）、節点と辺の配置は `GraphLayouts`（ELK を包む）を使い、画面ごとに作り直さない。
- 節点と辺の図は、配置（純ロジック。大きさは px で渡す）と描画（Canvas）を分け、配置は背景で計算してスナップショット状態へ入れる。ラベルは件数でなく倍率で出し分ける（縮小すると省く）。
- 図の描画は `DrawScope` の関数に切り出し、画面の Canvas と画像の書き出し（`CanvasDrawScope` で `ImageBitmap` に描く）で同じものを使う。ホイール・ホバー・右クリックは `PointerEventPass.Initial` で先に受け、右クリックは消費してタップにしない。
- 図の色は意味で決め、色だけに頼らない（凡例・アイコン・表を添える）。系列の色の組は色覚の差も含めて見分けられるかを確かめてから Palette に足す（準備完了＝`ChartReady`・未完＝`ChartPending`）。
- テストしたいロジックはコンポーザブルから純粋関数へ切り出して `internal` にする。画面の振る舞い（項目・閲覧専用の分岐・入力が木に届くこと）は `runComposeUiTest` で画面無しに描いて semantics で確かめる（`FormViewUiTest`）。

## 拡張ポイント

- **新しい画面**: スペース内の常設画面として `ui/` に 1 ファイル。状態はコンポーザブルの外に置く（見え方は `ScreenMemory`、実行のような長い処理の状態は専用のコントローラ）。
- **新しい編集面**: `FormCtx` を通し、書込は `Slot` ＋ `markEdited()`。連続入力なら合体キー（[editing-history.md](editing-history.md)）。
- **基底の種類の追加**: `ValueEditor`（ui/form/FormFields.kt）に分岐を足し、編集欄は種類のファイル（ScalarEditors.kt・RefEditors.kt・CollectionEditors.kt）に置く（他の変更箇所は [schema.md「拡張ポイント」](schema.md)）。組込みの型に専用の編集欄を付けるときは `FieldType` の分岐でなく、解決した項目の連鎖で判定する（`isA(BuiltinTypes.TEXT)` が複数行の欄を出す例）。
- **横断ビューの追加**: `LedgerSpace` のタブと `workspaceView` の分岐に足す。

## ファイル一覧と参考資料

`FileCatalog` は文書の索引から独立したファイル一覧。`FileBrowserRows` が開いた枝だけを背景で読み、検索は取消可能な全体走査で行う。リンクのディレクトリは辿らない。
`FileTreePreferences` はワークスペース別の展開と表示設定を利用者側へ保存する。初期は折り畳み、検索中の表示は保存済みの展開を変更しない。
`FilePreviewPane` は一般ファイルの UTF-8 閲覧。本文の読込には容量上限を設け、バイナリ・非対応文字コードは外部アプリへの入口を表示する。索引や編集履歴へは入れない。
ツリーの表示設定と台帳の範囲は独立。見え方の変更では文書の読み込み規則を変えない。これは UI 状態の射影で、Core Calculus の演算を追加しない。

複数選択は `FileTreeSelection` が範囲・切替・親子の重複除去を計算する。ドラッグは移動先指定だけで、書込は共通の影響確認へ渡す。
件数・選択・ドラッグの案内は高さ固定の1行を共有する。移動先は行の背景と枠で強調し、直下なら一覧の枠で示す。案内や強調では行の配置を変えない。
移動先の表示とドロップは同じ `dropDestination` 判定を使う。自分自身・配下・現在の親・領域外を拒否し、衝突や定義の変更は影響確認で検査する。
ファイル操作の Undo/Redo は `AppState.historyFromUi` がカーネルの操作を背景で実行し、結果を EDT で画面へ反映する。実行中は別の書込を抑止し、履歴の所有者はセッションだけ。

`ReferenceSearch` は表示設定に従う UTF-8 本文の都度検索。ファイル単位と合計読込量・結果数・経過時間を制限し、中止・上限・読めない対象を結果に含める。結果は走査中も一定件数ごとに表示する。
本文の常設索引を持たず、内容が変わると検索をやり直す。参考資料の検索はディスク基準で、未保存 YAML の検索は従来のコマンドパレットを使う。
`FileTreeBenchmark` は一時領域に実ファイルを作り、一覧・ワークスペース読込・名前検索・本文検索を計測する任意タスク。通常テストには大量の作成を持ち込まない。

`SourceOutline` は外部ソースから宣言候補を取り込む軽量アダプタ。コメント・文字列を伏せて宣言位置を読み、型解決や呼出関係を推測しない。
Core Calculus 上は外部入力から Term への変換で、既存 YAML の Construct や意味評価を複製しない。保存は `DocumentSession.createDocument` の既存 Rewrite／Transaction 経路を使う。
保存した構造情報の索引・検証は利用者のスキーマで定義する。言語の型解決が必要になったらこのアダプタを置換し、YAML の意味評価と分離を保つ。
