# UI

UI シェルは「編集・台帳・実行・設計」の 4 スペースと左端のアクティビティレール。画面はモーダルでなくスペース内の常設画面で、エージェント実行の状態はコンポーザブルの外（`RunController`）に置く。
フォームは `FormCtx` が編集操作と参照解決を仲介し、閲覧専用モードは `FormCtx` の共有で全ビューへ波及する。

## 部品

| 部品 | ファイル | 責務 |
|---|---|---|
| `Main.kt` | app/Main.kt | エントリポイント・ウィンドウ・ショートカット（`handleShortcut`）・スタート画面・コマンドパレット・ステータスバー・`uiPreferences` |
| `UiSpace` / `showSpace` / `showWorkspaceView` / `navigateToPath` / `openCrossView` / `openViewGrid` / `goBack` / `goForward` | app/AppState.kt | スペースとナビゲーション履歴（`NavLocation`） |
| `ActivityRail` / `LedgerSpace` | ui/Shell.kt | 4 スペースを切り替えるレールと、一覧・ビューグリッド・グラフ・エンティティをタブで束ねる台帳スペース |
| `RunController` / `RunsSpace` | ui/RunsSpace.kt | 実行スペース（受信箱・手動実行・自動化・計測評価）。実行状態機械（AppRoot 生存期間） |
| `DesignSpace` | ui/DesignSpace.kt | 設計スペース（定義文書の種類別カタログ・新規作成・スキーマ推測・skill） |
| `FormView` / `FormCtx` / `ObjectFields` / `FieldRow` | ui/FormView.kt | スキーマ駆動フォームと汎用エディタ（中核 UI） |
| `ObjectTable` / `ArrayTable` / `MapTable` / `tableColumnWidths` | ui/FormView.kt | 配列と map のテーブル表示 |
| `Highlightable` / `slotAt` / `CompactTextField` / `Palette` / `PanelShape` | ui/Common.kt | ハイライトとスクロール・Slot の解決・共通の入力欄と色と角丸 |
| `SidePanel` | ui/SidePanel.kt | ID インスペクタ（検証・関係・出現の 3 タブ） |
| `FileTreePane` / `SourcePane` / `DiffPane` / `SchemaEditor` / `IdCrossView` / `ViewGridPane` / `WorkspaceViews` / `AiGenerationDialog` / `AutomationPanel` / `ImprovementPanel` / `SkillsPanel` | ui/ | 各ペイン |
| `SourceHighlight` / `GraphLayout` / `DiffPane.calculateDiff` | ui/ | テスト可能な純ロジック（YAML トークナイザと置換・力学レイアウト・差分） |

## UI シェル

- **スペースは `app.UiSpace`（EDIT / LEDGER / RUNS / DESIGN）**。`workspaceView`（0=文書, 1=一覧, 2=グラフ, 3=エンティティ, 4=ビューグリッド）は文書世界の下位ビュー。両者の同期は AppState のナビゲーション系メソッドが担い、ジャンプは常に EDIT（view 0）／LEDGER（view 1〜4）へ着地する。レール以外から `uiSpace` を直接書き換えない。
- **スペースの移動も「戻る・進む」の対象**: `NavLocation` はスペースを含み、`showSpace` / `showWorkspaceView` / `openCrossView` / `openViewGrid` も履歴に積む。復元は EDIT＝ファイル＋ハイライト、LEDGER＝下位ビュー＋選択 ID、RUNS / DESIGN＝スペースのみ。同じ場所の再選択は積まない。新しい移動で「進む」履歴は破棄。削除済みファイルは読み飛ばし、ワークスペース切替でクリア（`NavigationHistoryTest`）。
- **モーダルの新設禁止（確認ダイアログを除く）**: エージェント実行・自動化・計測評価・skill 操作は実行／設計スペースの常設画面。AlertDialog は破壊的操作と承認の確認だけ。エージェントの質問もモーダルにせず非モーダルカード。気づきはレールとステータスバーのバッジ。
- **エージェント実行の状態は `RunController`**（AppRoot 生存期間・ワークスペース単位）。スペースを切り替えて実行画面がコンポジションから外れても状態が失われない。
- **受信箱はデータを持たない射影**: 承認待ち（`AutomationEngine.pendingSnapshot`）・質問（`RunController.pendingQuestion`）・外部変更競合（`AppState.conflictedDocs`）を集約表示するだけ。バッジ数もこの集約から導出する。
- **設計スペースは「定義も YAML 文書」の入口**: 種類別カタログはファイル一覧から導出し、編集は編集スペースの既存エディタへジャンプする。定義専用の編集経路を新設しない。
- **アプリ全体のショートカットはウィンドウで受ける**: 戻る／進む（`Alt+←` / `→`）・保存・Undo/Redo・コマンドパレットは `Window(onKeyEvent = …)`（`Main.kt` の `handleShortcut`）。部品の `Modifier.onKeyEvent` は焦点のある子がいるときにしか呼ばれず、ジャンプの直後のように焦点が無いときはキーが届かない。テキスト入力が自分で消費するキーは入力側が先に受ける。
- **コマンドパレット**（Ctrl+K）: ID・タイトル移動・ファイル検索・本文検索（構造検索を未保存優先の loader で「見たまま」実行）・台帳ビュー切替・スペース切替・保存・ワークスペース切替。
- **永続化**: UI レイアウト（ペイン幅・開閉）・テーマ・最近開いたファイルとワークスペース・閲覧専用モードは `Preferences`。

## フォームと FormCtx

- `FormCtx` が編集操作（`writeScalar`・`markEdited`）と参照解決（`resolvable`）・対象文書（`doc`）・既定の展開（`defaultExpanded`）・ハイライト（`highlightInside`）・指摘（`issues`）・アタッチ（`attachments`）・AI の関連文書（`aiRelatedFiles`）を仲介する。フォームを再利用する画面（エンティティページ・ビューグリッド・文書ビュー）へ自動で波及する。
- object の子フィールドは `ObjectFields`（`effectiveFields` 経由）で描く。列挙を複製しない。
- 基底の種類別のエディタ（複数行と入力欄の案内は型から引き継ぐヒント）・必須マーク（`requiredFrom` はフェーズ名つき）・説明・折りたたみ。id 型は既存 ID の補完ピッカーと連番の提案（`IdSuggest`）。`format` と `display: grouped` は見せ方（`ValueFormat.display`。`CompactTextField` の `unfocusedText`＝フォーカス中は素の値）。スキーマの型と値の構造が合わない残留値は修復 UI（値を消す・配列やスカラーに変換）。
- 指摘のあるフィールドは入力枠が色づく（エラー＝赤・警告＝オレンジ。`FormCtx.issues` 経由で全指摘種別に効く）。
- 同一 ID の他文書出現ストリップ（`SameIdOccurrencesStrip`）が相手側の情報の表示とジャンプを提供する。
- id 型の欄は定義位置そのものなので、ジャンプの行き先は横ぐし（エンティティページ）だけ。右端の Hub ボタン（`EntityPageButton`。ビューグリッドの ID セルと同じ印）と「出現 n 件／被参照 n 箇所」の注記が `openCrossView(id)` へ直行し、閲覧専用のテーブルの ID セルにも同じボタンを出す。参照欄の→は「定義へ／横ぐしへ」の 2 択のまま（行き先が 2 つある）。

## 閲覧専用

- `FormCtx.readOnly`（既定値は `AppState.readOnlyMode`）が編集 UI の表示分岐と `markEdited` / スカラー書込のガード（木の変異前に `check` で拒否）を担う。
- FormCtx を共有する全ビューへ自動波及させ、ビューごとの読み取り専用分岐を別実装しない。ジャンプ・バックリンクは閲覧行為として残す。トグルはツールバーで、永続化する。

## テーブル表示

- フォーム内の配列（object 要素）と map（object 値）をテーブルにするかの適否と列集合は `Display.tableColumns`（自動判定「列 2〜8 かつ宣言フィールドの過半がセル型」＋スキーマの `display: table / list`）だけが決める。
- 列は要素 object の宣言フィールドのうちセル型のもの。enum `cases` による行ごとの追加フィールドは列にしない（列集合を全行で安定させる）。列にならないフィールドは行の展開フォームで編集する。map のキー列は `MapTable` が先頭に足す表示専用の列。
- セル編集は通常の `Slot` 経路を再利用し、テーブル専用の書込経路を作らない。列幅は型別の最小幅を保証しつつ余白を文字系の列へ等分し、収まらなければ横スクロール。閲覧専用モードはテキストセル。

## 畳みとハイライト

- 要素位置は YPath で紐付き、`state.highlightPath` に設定すると `Highlightable` がスクロール＋一時ハイライトする（対象を画面端でなく中央寄りに出す）。
- ハイライト対象が畳みの中にある場合は自動展開する。畳まれた区画の中は `Highlightable` がコンポーズされないため、畳みを持つエディタ（配列・ネスト object・map・テーブル行展開）は `FormCtx.highlightInside(path)` で展開する。展開はジャンプ後も維持する。
- 畳み込みサマリ: 畳んだ object は「ID タイトル（n 項目）」、array は「n 件: 先頭要素の要約, …」（`Display`）。既定はルートから 2 階層まで展開、以深は畳む（`FormCtx.defaultExpanded`。エンティティページの埋め込みフォームは要素基準の相対深さ）。モード切替でも展開状態を維持する。
- 移動は必ずナビゲーション系メソッド（`openFile` / `navigateToPath` / `openCrossView` / `openViewGrid`）を通す。`highlightPath` / `crossViewId` / `viewGridId` を直接代入すると履歴に残らない。

## エラー処理

- ファイル I/O・パースは `runCatching` で受け、ユーザー操作の結果は `Result` を返して呼び出し側が `state.status` に反映する。
- 入力検証の失敗は `require(...) { "日本語メッセージ" }`、内部異常は `error("日本語メッセージ")`。メッセージはそのまま UI に表示される前提で書く。

## 規律

- `YNode` は Compose の State ではない。木を表示するコンポーザブルは `doc.version` / `state.indexVersion` を読んで購読する。
- フォーム系の新しい編集 UI は `FormCtx` を通し、閲覧専用モードの表示分岐を入れる。
- `uiSpace` の書き換えは `showSpace` / `showWorkspaceView` / ナビゲーション系メソッド経由に限る。新しい画面をモーダルで作らない（確認ダイアログを除く）。エージェント実行の状態を `remember` へ戻さない。
- アプリ全体のショートカットは `Main.kt` の `handleShortcut` に足す。
- 新しく畳みを持つ UI は `FormCtx.highlightInside` の判定を入れる。
- `Dispatchers.Main` は使わない。バックグラウンドから UI へ反映するときはスナップショット状態を直接更新する（CONVENTIONS「Compose UI の書き方」）。
- 入力欄は `CompactTextField`、色は `Palette.*`、角丸は `PanelShape`。生の色や Material 既定色をビューに直接書かない。
- テストしたいロジックはコンポーザブルから純粋関数へ切り出して `internal` にする。

## 拡張ポイント

- **新しい画面**: スペース内の常設画面として `ui/` に 1 ファイル。状態はコンポーザブルの外（AppState か専用のコントローラ）に置く。
- **新しい編集面**: `FormCtx` を通し、書込は `Slot` ＋ `markEdited()`。連続入力なら合体キー（[editing-history.md](editing-history.md)）。
- **基底の種類の追加**: `FormView.ValueEditor` に分岐を足す（他の変更箇所は [schema.md「拡張ポイント」](schema.md)）。組込みの型に専用の編集欄を付けるときは `FieldType` の分岐でなく、解決した項目の連鎖で判定する（`isA(BuiltinTypes.TEXT)` が複数行の欄を出す例）。
- **横断ビューの追加**: `LedgerSpace` のタブと `workspaceView` の分岐に足す。
