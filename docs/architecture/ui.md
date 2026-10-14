# UI

UI シェルは「編集・台帳・実行・設計」の 4 スペースと左端のアクティビティレール。画面はモーダルでなくスペース内の常設画面で、エージェント実行の状態はコンポーザブルの外（`RunController`）に置く。
フォームは `FormCtx` が編集操作と参照解決を仲介し、閲覧専用モードは `FormCtx` の共有で全ビューへ波及する。

## 部品

| 部品 | ファイル | 責務 |
|---|---|---|
| `Main.kt` | app/Main.kt | エントリポイント・ウィンドウ・ショートカット（`handleShortcut`）・スタート画面・コマンドパレット・ステータスバー・`uiPreferences` |
| `UiSpace` / `showSpace` / `showWorkspaceView` / `navigateToPath` / `openCrossView` / `openViewGrid` / `goBack` / `goForward` | app/AppState.kt | スペースとナビゲーション履歴（`NavLocation`） |
| `ScreenMemory` | app/ScreenMemory.kt | 画面の覚え（畳み・タブ・絞り込み・チェック・ツリーの展開。画面を離れても消えず、ワークスペース切替で消える） |
| `ActivityRail` / `LedgerSpace` | ui/Shell.kt | 4 スペースを切り替えるレールと、一覧・ビューグリッド・グラフ・エンティティをタブで束ねる台帳スペース |
| `RunController` / `RunsSpace` | ui/RunsSpace.kt | 実行スペース（受信箱・手動実行・自動化・計測評価）。実行状態機械（AppRoot 生存期間） |
| `DesignSpace` | ui/DesignSpace.kt | 設計スペース（定義文書の種類別カタログ・新規作成・スキーマ推測・skill） |
| `FormView` / `FormCtx` / `ObjectFields` / `FieldRow` | ui/FormView.kt | スキーマ駆動フォームと汎用エディタ（中核 UI） |
| `FlowViewPane` / `EmbeddedFlowPane` | ui/FlowViewPane.kt | 図と原本フォームを併記する単独・埋め込み表示（[views](views.md)） |
| `ObjectTable` / `ArrayTable` / `MapTable` / `RefCell` / `tableColumnWidths` | ui/FormView.kt | 配列と map のテーブル表示（参照セルは ID・参照先の名前・横ぐしへの Hub） |
| `Highlightable` / `slotAt` / `CompactTextField` / `Palette` / `PanelShape` / `AppFonts` | ui/Common.kt | ハイライトとスクロール・Slot の解決・共通の入力欄と色と角丸・同梱フォント（等幅 Cica。資源が無ければ OS の等幅へ戻す） |
| `VirtualTable` / `TableColumn` / `visibleColumnRange` | ui/VirtualTable.kt | 共通の表。見出し行と左の固定列は動かず、行は遅延描画、列は見える範囲だけ組み立てる（ビューグリッド・一覧ビューが使う） |
| `ReorderableList` / `ReorderRowScope` | ui/Reorder.kt | ドラッグで並べ替える縦リスト（取っ手からだけ掴める。ライブラリ Reorderable を包む） |
| `TemporalPickerButton` / `TemporalPick` | ui/TemporalEditor.kt | 日付・時刻・日時のピッカー（ポップアップ）と、正準の文字列との変換（純ロジック） |
| `FilePickers` / `rememberDirectoryChooser` / `rememberFileChooser` / `rememberFileSaver` / `LocalDialogParentWindow` | ui/FilePickers.kt | OS ネイティブのファイル・フォルダ選択と保存先の選択、最後に選んだ場所の記憶（ライブラリ FileKit を包む） |
| `MarkdownView` / `openExternally` | ui/MarkdownView.kt | Markdown の描画（ライブラリ multiplatform-markdown-renderer を包む。色と文字は Palette） |
| `MarkdownPane` / `resolveMarkdownLink` | ui/MarkdownPane.kt | Markdown 文書と内蔵ヘルプの表示（表示とソースの 2 タブ・リンクの行き先の判定は純ロジック） |
| `Help` | app/Help.kt | 内蔵ヘルプの題目と本文（ビルド時に資源 `help/` へ入れた利用者マニュアル） |
| `FileEditor` / `resolveFilePath` / `fileValueFor` | ui/FileEditor.kt | file 型の入力欄（選ぶ・OS で開く・解決した場所と有無の 1 行・画像なら下に表示）。場所の解決と選んだファイルの書き方は純ロジック |
| `ImagePane` / `EmbeddedImagePane` / `ImageCanvas` / `fitZoom` | ui/ImagePane.kt | 画像の表示（幅に合わせる・100%・±・Ctrl＋ホイール・ドラッグ＝フロービューと同じ操作）の埋め込みと全面表示で、更新時刻で読み直す |
| `BarChart` / `ChartSeries` | ui/Charts.kt | 縦棒グラフ（積み上げ・並べ・凡例・マウスで値。ライブラリ Vico を包み、系列の色は Palette の `ChartReady` / `ChartPending` など意味で決める） |
| `ReadinessPane` / `readinessChartData` | ui/ReadinessPane.kt | 準備完了レポート（台帳の「準備」タブ: タイル・型別とフェーズ別の図・エンティティの表、図のデータ整形は純ロジック） |
| `SidePanel` | ui/SidePanel.kt | ID インスペクタ（検証・関係・出現・操作の 4 タブ。台帳スペースでレンズを表示しているときは Main が右ペインに `LensSidePanel`＝ui/GraphLensView.kt の型・関係・辺の 3 タブを出し、右ペインの開閉は `AppState.rightPaneOpen`） |
| `ActionTabContent` / `ActionPanelHost` / `TargetPicker` | ui/ActionPane.kt | 右ペインの「操作」タブ（使える操作の一覧と作業中の操作の開き直し）と、操作の浮いたパネル（対象の欄・受け取る値・確認・差分・適用。[actions](actions.md)） |
| `FileTreePane` / `SourcePane` / `DiffPane` / `SchemaEditor` / `IdCrossView` / `ViewGridPane` / `WorkspaceViews` / `AiGenerationDialog` / `AutomationPanel` / `ImprovementPanel` / `SkillsPanel` | ui/ | 各ペイン |
| `GraphLensView` | ui/GraphLensView.kt | グラフレンズ（配置の切替・箱の入れ子と矢印・ひし形・点線の描画・クリックでエンティティページへ着地し、配置は背景で計算し、できるまで前の絵を出す）と操作（ホイール拡縮・箱のドラッグ＝`withMovedNested`・見出しの印で開く・畳む・ホバー強調・右クリック・PNG 保存・ミニマップ）。節点と辺の決め方は ui/LensGraph.kt（[graph-query](graph-query.md)） |
| `GraphLayouts` / `LensLayoutKind` / `LensArrangement` / `fitViewport` | ui/GraphLayouts.kt | 節点と辺の配置（放射・階層・応力はライブラリ ELK を包み、力学は `GraphLayout`）と画面に収める倍率の計算（Compose に依存しない純ロジック） |
| `SourceHighlight` / `GraphLayout` / `DiffPane.calculateDiff` | ui/ | テスト可能な純ロジック（YAML トークナイザと置換・軽い力学レイアウト・差分） |

## UI シェル

- **スペースは `app.UiSpace`（EDIT / LEDGER / RUNS / DESIGN）**。`workspaceView`（0=文書, 1=一覧, 2=グラフ, 3=エンティティ, 4=ビューグリッド, 5=フロー, 6=準備完了レポート）は文書世界の下位ビュー。両者の同期は AppState のナビゲーション系メソッドが担い、ジャンプは常に EDIT（view 0）／LEDGER（view 1〜5）へ着地する。レール以外から `uiSpace` を直接書き換えない。
- **スペースの移動も「戻る・進む」の対象**: `NavLocation` はスペースを含み、`showSpace` / `showWorkspaceView` / `openCrossView` / `openViewGrid` も履歴に積む。復元は EDIT＝ファイル＋ハイライト＋エディタのタブ、LEDGER＝下位ビュー＋選択 ID、RUNS / DESIGN＝スペースのみ。同じ場所の再選択は積まない。新しい移動で「進む」履歴は破棄。削除済みファイルは読み飛ばし、ワークスペース切替でクリア（`NavigationHistoryTest`）。
- **「戻る」が戻すのは場所で、見え方は画面が覚える**: 畳み・タブ・絞り込み・チェック・ツリーの展開のような見え方は `ScreenMemory`（`state.memory`）に置き、画面が描画の木から外れても捨てない。履歴（`NavLocation`）には積まない。
  - 覚えは「あれば使う・無ければ既定」。再読込で無くなった文書・型・種別の鍵は読まれないだけにし、画面側で今の候補と突き合わせる（一覧の種別と状態・ツリーの選択・レンズの配置）。
  - 寿命はワークスペース（切替で全部消す）。文書の削除・改名はその文書のぶんだけ消す・移す。操作の途中（ダイアログ・メニュー・入力途中の欄）は `remember` のまま（`ScreenMemoryTest`）。
- **常設画面と浮いたパネルの線引き**: エージェント実行・自動化・計測評価・skill 操作のような、実行の状態を持つ・長く続く作業は実行／設計スペースの常設画面。値を集めて 1 回で適用する短い作業（AI 生成・改名・統合・スキーマ推測・操作）は浮いたパネル（AlertDialog）にできる。それ以外の AlertDialog は破壊的操作と承認の確認だけ。エージェントの質問はモーダルにせず非モーダルカード。気づきはレールとステータスバーのバッジ。
- **エージェント実行の状態は `RunController`**（AppRoot 生存期間・ワークスペース単位）。スペースを切り替えて実行画面がコンポジションから外れても状態が失われない。
- **操作は浮いたパネル**: 入口（エンティティページの操作メニュー・フォームとビューの行の ⋯・文書ヘッダの「操作 ▾」・コマンドパレット・右ペインの「操作」タブ）はどこでも `AppState.startAction` を呼び、パネルは `Main` が `ActionPanelHost` でスペースに関係なく載せる。作業の状態は `AppState.actionWork`（`remember` に置かない）で、隠しても残り「操作」タブから開き直せる。受け取る値はフォームの部品で編集する（[actions](actions.md)）。
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
- object の子フィールドは `ObjectFields`（`effectiveFields` 経由）で描く。列挙を複製しない。
- 配列の追加ボタンは `AppState.addArrayElement` を呼ぶ（要素の中身は型の `create` と配列の `insert` の決まり。受け取る値があれば右ペインのカード。[actions](actions.md)）。フォーム側で初期値を組まない。
- 基底の種類別のエディタ（複数行と入力欄の案内は型から引き継ぐヒント）・必須マーク（`requiredFrom` はフェーズ名つき）・説明・折りたたみ。id 型は既存 ID の補完ピッカーと連番の提案（`IdSuggest`）。`format` と `display: grouped` は見せ方（`ValueFormat.display`。`CompactTextField` の `unfocusedText`＝フォーカス中は素の値）。スキーマの型と値の構造が合わない残留値は修復 UI（値を消す・配列やスカラーに変換）。
- 指摘のあるフィールドは入力枠が色づく（エラー＝赤・警告＝オレンジ。`FormCtx.issues` 経由で全指摘種別に効く）。
- 同一 ID の他文書出現ストリップ（`SameIdOccurrencesStrip`）が相手側の情報の表示とジャンプを提供する。
- id 型の欄は定義位置そのものなので、ジャンプの行き先は横ぐし（エンティティページ）だけ。右端の Hub ボタン（`EntityPageButton`。ビューグリッドの ID セルと同じ印）と「出現 n 件／被参照 n 箇所」の注記が `openCrossView(id)` へ直行し、閲覧専用のテーブルの ID セルにも同じボタンを出す。参照欄の→は「定義へ／横ぐしへ」の 2 択のまま（行き先が 2 つある）。テーブルの参照セルは → に加えて Hub を並べ、横ぐしへは 1 クリックで行ける。
- 参照先の名前の解決は `FormCtx.refTargetTitle`（未保存の編集を含む自文書の ID を優先し、idPatterns と scope で絞る）に一元化し、参照欄（欄の中の右端）とテーブルの参照セル（欄の下）が共用する。

## 閲覧専用

- `FormCtx.readOnly`（既定値は `AppState.readOnlyMode`）が編集 UI の表示分岐と `markEdited` / スカラー書込のガード（木の変異前に `check` で拒否）を担う。
- FormCtx を共有する全ビューへ自動波及させ、ビューごとの読み取り専用分岐を別実装しない。ジャンプ・バックリンクは閲覧行為として残す。トグルはツールバーで、永続化する。

## テーブル表示

- フォーム内の配列（object 要素）と map（object 値）をテーブルにするかの適否と列集合は `Display.tableColumns`（自動判定「列 2〜8 かつ宣言フィールドの過半がセル型」＋スキーマの `display: table / list`）だけが決める。
- 列は要素 object の宣言フィールドのうちセル型のもの。enum `cases` による行ごとの追加フィールドは列にしない（列集合を全行で安定させる）。列にならないフィールドは行の展開フォームで編集する。map のキー列は `MapTable` が先頭に足す表示専用の列。
- セル編集は通常の `Slot` 経路を再利用し、テーブル専用の書込経路を作らない。列幅は型別の最小幅を保証しつつ余白を文字系の列へ等分し、収まらなければ横スクロール。閲覧専用モードはテキストセル。
- エンティティ参照のセルは `RefCell`: 上段に ID、下段に参照先の名前、右端に横ぐしへの Hub（ID セルと同じ印・上書き索引に出現がある ID だけ）。編集モードも通常の参照欄（候補・→ の 2 択）のまま、名前だけ欄の下に出す（欄の中の右端はセル幅で ID を押しのける。ビューグリッドと同じ流儀）。属性付き参照は to の表示だけで、編集は行の展開フォーム。文書参照（`target: document`）は対象外。
- 配列の行とフォーム内の配列要素は取っ手（⋮⋮）からのドラッグで並べ替える（`ReorderableList`）。ドロップで 1 回だけ `moveSeqItem` ＋ `markEdited`（Undo 1 回分）。取っ手を置かなければ並べ替えられない（map の表・閲覧専用）。
- ビューグリッドと一覧ビューの表は共通部品 `VirtualTable`（ID などの固定列・見出し行の固定・見える列だけ組み立てる）。セルの中身と編集経路はそれぞれの画面のまま。
- 日付・時刻・日時の欄は右端のボタンでピッカー（ポップアップ。モーダルではない）。書込は `FormCtx.setScalar` の同じ経路で、値は正準の文字列のまま。

## 畳みとハイライト

- 要素位置は YPath で紐付き、`state.highlightPath` に設定すると `Highlightable` がスクロール＋一時ハイライトする（対象を画面端でなく中央寄りに出す）。
- ハイライト対象が畳みの中にある場合は自動展開する。畳まれた区画の中は `Highlightable` がコンポーズされないため、畳みを持つエディタ（配列・ネスト object・map・テーブル行展開）は `FormCtx.expanded(path)` で開閉を決める（ジャンプ先が中なら開く）。開閉は `ScreenMemory` が文書とパスで持ち、ジャンプ後も画面を離れても維持する。
- 畳み込みサマリ: 畳んだ object は「ID タイトル（n 項目）」、array は「n 件: 先頭要素の要約, …」（`Display`）。既定はルートから 2 階層まで展開、以深は畳む（`FormCtx.defaultExpanded`。エンティティページの埋め込みフォームは要素基準の相対深さ）。モード切替でも展開状態を維持する。
- 移動は必ずナビゲーション系メソッド（`openFile` / `navigateToPath` / `openCrossView` / `openViewGrid`）を通す。`highlightPath` / `crossViewId` / `viewGridId` を直接代入すると履歴に残らない。

## エラー処理

- ファイル I/O・パースは `runCatching` で受け、ユーザー操作の結果は `Result` を返して呼び出し側が `state.status` に反映する。
- 入力検証の失敗は `require(...) { "日本語メッセージ" }`、内部異常は `error("日本語メッセージ")`。メッセージはそのまま UI に表示される前提で書く。

## 規律

- `YNode` は Compose の State ではない。木を表示するコンポーザブルは `doc.version` / `state.indexVersion` を読んで購読する。
- フォーム系の新しい編集 UI は `FormCtx` を通し、閲覧専用モードの表示分岐を入れる。
- `uiSpace` の書き換えは `showSpace` / `showWorkspaceView` / ナビゲーション系メソッド経由に限る。新しい画面をモーダルで作らない（確認ダイアログと、値を集めて 1 回で適用する短い作業の浮いたパネルを除く）。エージェント実行の状態を `remember` へ戻さない。
- アプリ全体のショートカットは `Main.kt` の `handleShortcut` に足す。
- 新しく畳みを持つ UI は `FormCtx.expanded` / `setExpanded` を通す（ジャンプ先の自動展開と画面の覚えが付いてくる）。
- 画面を離れても残したい見え方（畳み・タブ・絞り込み・チェック・展開）は `remember` に置かず `state.memory`（`ScreenMemory`）へ。覚えは「あれば使う・無ければ既定」で、古い鍵を誤りにしない。操作の途中は `remember` のまま。
- `Dispatchers.Main` は使わない。バックグラウンドから UI へ反映するときはスナップショット状態を直接更新する（CONVENTIONS「Compose UI の書き方」）。
- 入力欄は `CompactTextField`、色は `Palette.*`、角丸は `PanelShape`。生の色や Material 既定色をビューに直接書かない。
- 等幅の文字は `AppFonts.Mono`（同梱の Cica。TTF は git に入れず、build.gradle.kts の `downloadFonts` がビルド時に取得して資源 `fonts/cica/` に入れる）。`FontFamily.Monospace` を書かない。
- 外部の UI ライブラリは ui/ の 1 ファイルで包み、その型を他のファイルへ出さない（FileKit＝FilePickers.kt・Reorderable＝Reorder.kt・Markdown＝MarkdownView.kt・Vico＝Charts.kt・ELK＝GraphLayouts.kt）。差し替えるときはその 1 ファイルだけを書き換える。
- 表は `VirtualTable`、Markdown は `MarkdownView`、ファイル選択は `rememberDirectoryChooser` / `rememberFileChooser`、並べ替えは `ReorderableList`、グラフは `BarChart`（Vico を包む）、節点と辺の配置は `GraphLayouts`（ELK を包む）を使い、画面ごとに作り直さない。
- 節点と辺の図は、配置（純ロジック。大きさは px で渡す）と描画（Canvas）を分け、配置は背景で計算してスナップショット状態へ入れる。ラベルは件数でなく倍率で出し分ける（縮小すると省く）。
- 図の描画は `DrawScope` の関数に切り出し、画面の Canvas と画像の書き出し（`CanvasDrawScope` で `ImageBitmap` に描く）で同じものを使う。ホイール・ホバー・右クリックは `PointerEventPass.Initial` で先に受け、右クリックは消費してタップにしない。
- 図の色は意味で決め、色だけに頼らない（凡例・アイコン・表を添える）。系列の色の組は色覚の差も含めて見分けられるかを確かめてから Palette に足す（準備完了＝`ChartReady`・未完＝`ChartPending`）。
- テストしたいロジックはコンポーザブルから純粋関数へ切り出して `internal` にする。

## 拡張ポイント

- **新しい画面**: スペース内の常設画面として `ui/` に 1 ファイル。状態はコンポーザブルの外に置く（見え方は `ScreenMemory`、実行のような長い処理の状態は専用のコントローラ）。
- **新しい編集面**: `FormCtx` を通し、書込は `Slot` ＋ `markEdited()`。連続入力なら合体キー（[editing-history.md](editing-history.md)）。
- **基底の種類の追加**: `FormView.ValueEditor` に分岐を足す（他の変更箇所は [schema.md「拡張ポイント」](schema.md)）。組込みの型に専用の編集欄を付けるときは `FieldType` の分岐でなく、解決した項目の連鎖で判定する（`isA(BuiltinTypes.TEXT)` が複数行の欄を出す例）。
- **横断ビューの追加**: `LedgerSpace` のタブと `workspaceView` の分岐に足す。
