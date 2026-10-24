# 編集と履歴

編集は木を直接変え、`markEdited()` で履歴・dirty・検証・見たまま判定が追随する。履歴（Undo/Redo）と dirty の正は、カーネルのセッションが持つワークスペース横断の単一履歴で、文書ごとの履歴は持たない（ADR-0006）。
文書の作成と削除も履歴に入り、Undo でファイルの有無まで確定する。

## 部品

| 部品 | ファイル | 責務 |
|---|---|---|
| `OpenDocument` | app/AppState.kt | 開いている 1 文書の Compose シェル（`version` / `dirty` / `parseError` の購読）。複数ドキュメントの再出力と保存内容との比較は `EditBuffer` へ委譲し、外部競合を管理する |
| `EditBuffer` | core/Documents.kt | 複数ドキュメントの再出力と、保存した内容との比較（履歴は持たない）。ここの dirty はセッションが文書として扱わないパス用で、聞かれたときにだけ計算する |
| `WorkspaceSession.recordEdit` / `endEditGroup` | workspace-core | 編集の記録（合体キーと時刻つき）と連続入力の区切り。Undo / Redo の実体 |
| `AppState.undo` / `redo` / `historyNotice` / `undoPreview` | app/AppState.kt | 横断 Undo の実行と、戻った内容の通知・予告 |
| `AppState.createDocumentFile` / `afterHistoryStep` | app/AppState.kt | 文書の新規作成の単一経路と、作成・削除を含む履歴の確定 |
| `FormCtx.writeScalar` | ui/FormView.kt | スカラー入力の書込。合体キーを渡す |
| `OpenDocument.source` / `applyParsed` / `syncSourceFromTree` / `discardDraft` / `enterTextOnly` | app/AppState.kt | ソースの下書き（文字列・カーソル・解析エラー）の所有と、木との同期 |
| `SourceHistorySync` | ui/SourceHistorySync.kt | 履歴の値を優先した書式の差し込みと、全 YAML 文書の再解析による内容一致の確認 |
| `SourcePane` / `SourceDraftBanner` | ui/SourcePane.kt | ソースの表示と入力（入力が止まってから背景で解析）。他のタブに出す書きかけ・壊れたまま保存のバナー |
| `SourceSyntaxBar` / `sourceErrorLocation` | ui/SourceSyntaxBar.kt | 本文の位置を変えない構文状態と詳細の表示、解析エラー文面からの問題位置の取得 |
| `LineMerge` | ui/SourceMerge.kt | 行の差分（共通部分の切り落とし＋LCS）と 3 者マージ・文字列とカーソルへの適用（純ロジック） |
| `SourceEditing` | ui/SourceEditing.kt | 基本操作の規則（改行のインデント・インデント・コメント切替・行の移動と複製・検索と置換）。文字列と選択範囲を受けて返す純ロジック |
| `SourceContext` | ui/SourceContext.kt | カーソル位置の文脈（親のパス・キーか値か・打ちかけの語）。木でなく文字列のインデントから読むので、解析できない書きかけでも働く（純ロジック） |
| `SourceCompletion` | ui/SourceCompletion.kt | Ctrl+Space の候補（キー名＝スキーマの実効フィールド・ID＝上書き索引・enum の値・真偽値・file＝ワークスペースのファイル） |
| `YamlPositions` / `SourcePositions` | yaml-core yaml/YamlPositions.kt | 解析できた文字列のノードの位置の表（パス→行・列、位置→パス、行→見出しのキー）。指摘の行・ホバー・Ctrl+クリック・折り畳みの鍵 |
| `SourceStructure` | ui/SourceStructure.kt | 折り畳める区画と畳んだ見え方（隠れる範囲・表示と元の位置の行き来）・保存済みとの差分の印・木のノード→パス・選択範囲の整形（純ロジック） |
| `YamlHighlighter` | ui/SourceHighlight.kt | 行ごとの覚えつきの色分け（純ロジック） |
| `AppState.saveDraftAsText` / `prepareHistoryStep` / `executeHistoryStep` / `ensureAttached` | app/AppState.kt | 壊れたまま保存、下書きを捨てる確認つきの Undo/Redo、テキスト文書を文書集合へ戻す |
| `WorkspaceSession.saveAsText` / `attachDocument` | workspace-core | テキストのまま保存して文書集合から外す（履歴は残す）。解析できる内容で文書集合へ戻す |
| `DiffPane.calculateDiff` / `charDiffRanges` | ui/DiffPane.kt | 保存済みと編集中の差分（行整列・文字単位の強調。純ロジック） |

## 編集の経路

- フォーム操作 → `Slot.set` または木の直接変更 → `markEdited()`（`AppState.recordEditToSession` が履歴と dirty をセッションへ記録）→ `version` が進み再コンポーズ。
- ビューグリッド・エンティティページ・スキーマ設計 UI も同じ経路（文書キャッシュ `AppState.ensureDoc` の木を `Slot` で変えて `markEdited()`）。専用の書込経路を作らない。
- 一括編集（ルート直下のスカラーキー）はセッションの 1 トランザクションで、Undo 1 回で全ファイルが戻る。
- 内部で変更を生成するコードは `edit.YamlOperation` を使い、`PatchOp` と変更セットはエージェント・CLI・skill との交換形式に限る（[yaml-model.md「変更の基礎命令と 2 層」](yaml-model.md)）。
- 保存（`AppState.save` / `saveAll`）は、UI の木をセッションへ写した後・書き出す前に、保存時に自動で入る値（更新日・版。[auto-values.md](auto-values.md)）をセッションの `applyAutoValues` で 1 トランザクション（履歴 1 件）として当て、入った文書の木をセッションから写し直す。保存自体は今までどおり履歴項目ではない。

## 履歴と dirty

保存の外部競合検査は [workspace.md「保存時の競合」](workspace.md#保存時の競合) を参照。

- 履歴はセッション（workspace-core）のワークスペース横断の単一履歴。開いていない文書も戻す。
- フォームの直接入力も同じ履歴へ接続する。対象と例外は [UI 操作規則「フォーム入力の Undo と Redo」](ui-interactions.md#フォーム入力の-undo-と-redo)、接続は [ui.md「入力と Undo」](ui.md#入力と-undo) を参照。
- `AppState` は実行後の通知（`historyNotice`）とホバー予告（`undoPreview` / `redoPreview`）を組み立てる。通知の強さと寿命は [UI 操作規則「横断履歴の通知」](ui-interactions.md#横断履歴の通知) に従う。
- `HistoryStep` は実行前の表示文書を保持し、実際に実行した記録の全対象と比較する。`HistoryNotice.targets` は消えたファイルも含み、`historyNoticeTargets` が現在の未保存状態と開けるかを返す。
- `historyLocations` はセッションの記録 ID に `NavLocation` を添え、成功後に `returnToHistoryLocation` が編集元へ戻す。内容の履歴は複製せず、セッションの開き直しで画面情報を破棄する。
- 通常編集は記録時、ソースは解析完了時の画面に関係なく対象文書のソースタブを記憶する。一括変更・リファクタリング・操作・作成・配置変更・自動値も既存の記録に画面情報を添える。
- 移動は既存のナビゲーション経路を使い、Alt+←で移動前の場所へ戻れる。ビュー内の絞り込み・スクロール等の覚えは `ScreenMemory` を使い、編集履歴に複製しない。
- `OpenDocument.dirty` はセッションの正の写し。`EditBuffer` の文字列比較は、セッションが文書として扱わないパスのためだけ。
- 未表示文書を開くときも本文はセッションの現在値からコピーし、未保存件数・終了確認・すべて保存にはセッション内の未表示文書を含める。
- 保存済みテキストと編集中の木を分けて保持する。内容だけの Undo/Redo では保存済みの比較基準を変えず、ソースの差分印も保存内容との差を示す。

## 履歴の合体

連続入力を Undo 1 回で戻すため、編集の記録（`recordEdit(id, document, coalesceKey, nowMillis)`）に合体キーを渡す。

- **規則**: 直前の記録と同じキー（`==`）かつ 1000ms 以内かつ履歴の先端（Undo 後でない）なら、新しいエントリを積まずに先端を置換する。キーが null・キー不一致・時間超過・Undo/Redo 直後は新規エントリ＝境界。
- **キー**: フォーム・グリッドのスカラー入力は `FormCtx.writeScalar` が変異対象の `YNode.Scalar` インスタンス（同一フィールドの連続タイプだけが合体）。スキーマ設計 UI のテキスト入力は `Pair(対象 Map, キー名)`。ソース編集は文書単位の固定キー。構造操作（追加・削除・移動・enum 選択・変換）はキーなし＝常に境界。
- アプリは文書・編集元の画面も合体キーに含める。同じ文書の同じ項目でも、フォームとビューなど異なる画面の編集は別の記録になる。
- **明示的な区切り**: `endEditGroup` は合体の覚えだけをリセットする。保存は成功・失敗にかかわらず区切りになり、保存前の履歴も残る。
- アプリの `finishEditGroup` は解析待ちのソースを入力順に反映してから区切る。タブ変更・ナビゲーションの `pushBack`・戻る／進む・すべて保存で使い、単一保存も下書きの反映後に区切る。
- 画面の往復中に編集しなくても、戻ってからの入力は別記録になる。同じ場所やタブの再選択は区切らず、区切りだけでは履歴・dirty・Redo を変えない。
- **決定性**: 時刻は `recordEdit` の `nowMillis` と `AppState` の `editClock` で注入する（両層の `HistoryCoalescingTest`・`WorkspaceSessionTest`。タイマー・sleep を使わない）。
- undo / redo / restore / resetHistory は合体キーをリセットする。

区切りの操作規則は [UI 操作規則「Undo のまとまり」](ui-interactions.md#undo-のまとまり) を参照。
Core Calculus 上は既存の変更記録のまとめ方に限り、Rewrite の意味・書込経路・YAML 語彙は追加しない。

## ソースとの同期

- 入力は `noteSourceInput`、補完・置換・行操作は `editSource` で順序を記録する。選択範囲だけの変更では順序を進めない。
- `SourceParseRequest` は文書・本文・木の版・同期の世代・入力順を保持する。解析結果の採用時に全て照合し、古い結果や二重反映を拒否する。
- Undo/Redo は `flushSourceDrafts` で入力待ちを入力順に反映してから、対象の履歴と文書を決める。保存済みのファイル配置を戻す処理の振り分けも、その対象で行う。
- 入力待ちも Undo ボタンと予告の対象になる。Redo は入力の反映待ちの間は無効で、新しい編集が反映されると以前の Redo 履歴は失われる。
- 下書きを捨てる確認はセッション・履歴・版・下書きに結び付ける。確認中に変わった場合は中止し、再操作を案内する。
- 実行した履歴を使って通知と画面を更新し、ソースへの差し込みも次の入力より前に行う。履歴の所有者はセッションに保つ。
- Undo/Redo 後は `syncSourceFromTree(preferHistory = true)` が履歴の値を優先する。入力待ちや構文エラーがある下書きは通常の保護経路を使う。
- `SourceHistorySync` は履歴の正規化テキストへ干渉しない書式を重ね、全 YAML 文書を再解析して `dumpPlain` で内容を照合する。隣接する同じ行数の書式変更は行ごとに扱う。
- マージ後に構文や値が一致しなければ履歴の正規化テキストに揃えて通知する。コメントと表記は履歴の木から出力し、カーソルは差分に沿って写す。生の書式を過去と完全に同じ形へ復元する履歴は持たない。

- ソースの構文状態は `SourceSyntaxBar` の固定高の行に表示する。詳細は本文を押し下げないポップアップで、構文の正常と検証の充足は区別する。
- `sourceApplied` は観測可能な状態で、入力との差がある間は確認中とする。同じエラー文面が続く場合も解析完了を通知し、古い結果の位置へジャンプさせない。
- この表示は既存の解析結果の射影に限る。Core Calculus の Predicate・Rewrite などの演算や保存経路は追加しない。

木が常に正で、ソースの下書きは木の表現の 1 つ。

- **下書きは文書が持つ**: 文字列・カーソルは `OpenDocument.source`（Compose 標準の `TextFieldState`）、解析エラーは `sourceError`。画面の `remember` に置かないので、タブや文書を移っても残る。閉じるときの確認は未保存と同じ（`draftDirty` が `hasDirtyDocs` に入る）。
- **入力が止まってから反映**: `SourcePane` は文字列の変化を `snapshotFlow` で見て、300ms 止まったら背景で解析し（`EditBuffer.parse`）、UI 側で `applyParsed` が木を更新する。経路は `markEdited`＝履歴・dirty・editVersion・オーバーレイ索引・検証がフォーム編集と同じに追随する（合体キーは文書単位の固定キー）。
  保存・タブを離れるときは `flushDraft`、Undo/Redo・すべて保存では `flushSourceDrafts` で入力待ちの分を先に取り込む。反映しても下書きの文字列は書き換えない（カーソル・書式を保つ）。
- **解析できない間**: 最後に解析できた木が正。`draftBroken` で `FormCtx.readOnly` が真になり、フォーム・グリッド・横ぐしが閲覧専用になる（書きかけをフォームで上書きしない）。他のタブには `SourceDraftBanner`（「ソースで直す」「書きかけを捨てる」）。その文書に関わる Undo/Redo は `prepareHistoryStep` が「下書きを捨てて戻すか」を確認してから進める（`pendingHistory`）。
- **他所の変更の差し込み**: 自分の反映で進んだ `doc.version` は同期済み（`sourceSyncedVersion`）。フォーム・操作・外部変更の再読込では `syncSourceFromTree` が 3 者マージ（`LineMerge`）で変わった行を差し込み、カーソルを写す。
  元は前回の正規化テキスト（`sourceBase`）・自分は下書き・相手は今回の正規化テキスト。重なる変更は下書きを優先して件数を知らせる。Undo/Redo は上記の履歴優先の経路を使う。
- **壊れたまま保存**: `AppState.save` は下書きが解析できなければ止めず `saveDraftAsText` へ。セッションの `saveAsText` がテキストをそのまま書き、文書を文書集合から外す（ディスク上で解析できないファイルと同じ＝索引・検証・派生の対象外。履歴は消さない）。文書は `textOnly`（`root == null`）になり、ヘッダのバナー・ツリーの「解析不可」・検証一覧のパースエラーで見える。`_schemas` の文書だけは `pendingTextSave` で確認してから。
- **直すと戻る**: 直して解析できたら `ensureAttached` が `attachDocument` で文書集合へ戻し（保存済みとの差で dirty）、保存で木の内容が書かれる。壊す前の木は `restoreLastParsed` で戻せる。テキスト文書に関わる履歴の記録は、戻るまで適用できない（`prepareHistoryStep` が案内して止める）。
- **Ctrl+Z の一本化**: 欄の中の取り消しは使わず、`SourcePane` が Ctrl+Z / Ctrl+Y / Ctrl+Shift+Z をセッションの履歴へ回す（フォームと同じ 1 本の履歴。差し込みでカーソルは留まる）。
- **基本操作**: 欄のキー（Tab・Enter・Ctrl+/・Ctrl+D・Alt+↑↓・Ctrl+F / H / G・F3）は `SourcePane.handleEditorKey` が受け、規則は `SourceEditing` に閉じる（文字列と選択範囲を受けて返す）。日本語入力の変換中（`composition != null`）は Enter と Tab を奪わない。
- **検索の見せ方**: 一致の強調は色分けと同じ `OutputTransformation`、現在行の背景は欄の `drawBehind`。一致や行へ移動したときのスクロールは `revealRequest` を見た `LaunchedEffect` が配置結果から行の位置を取って行う。検索・置換の入力と現在の一致は操作の途中なので `remember`（`SourceUi`）。
- **場所の 2 つの読み方**: 解析できた文字列（`OpenDocument.sourceParsed`）の位置の表 `YamlPositions` は正確（フロー表記・引用符つきのキーも）で、指摘のパス→行、ホバーと Ctrl+クリックの位置→パスに使う。今の文字列が解析できた文字列と違う間（打っている最中）は値の色付きを出さず、行の印だけ出す。打っている最中に親のパスを知る補完は `SourceContext` がインデントから読む。
- **スキーマと索引の使い方**: 指摘は右パネルと同じ `Validator.validate`（文書 1 つ）で、パスの無い指摘は印にしない。補完のキー名は `schema.effectiveFields`（enum の cases も展開）から既にあるキーを除いたもの、ID は `state.overlayIndex()`（未保存を含む）、enum は `values`（label は添えるだけ）。
- **指摘からソースへ**: 右パネルの指摘・参照のクリックは、ソースを見ている間は `highlightPath` をソースの行に写す（`navigateToPath` / `navigateToOccurrence` はソースのタブを保つ）。
- **折り畳みは表示だけ**: 文字列（`TextFieldState`）は変えず、`OutputTransformation` で隠れた範囲を抜いて見出しの末尾に「⋯ n 行」を添える。欄の配置は表示の文字列のものになるので、カーソル・一致・ホバーの位置は `FoldView.displayOffset` / `originalOffset` で行き来し、行番号は表示の行を描いて元の行の番号を出す。
- **折り畳みの覚えと解除**: 覚えは見出しのキーのパス（`ScreenMemory.sourceFolds`）で、行は位置の表から引く＝行がずれても追随する。検索・ジャンプで隠れた行へ行く前に `unfoldAt` で広げ、畳んだ区画にカーソルが入ったら見出しの末尾へ寄せる。
- **フォームとの位置同期**: フォームのスカラー編集は `markEdited(coalesceWith = node)` のノードを `OpenDocument.lastEditedNode` に残し、ソースへ移ったとき 1 回だけ（`formSyncPending`。木と下書きが揃ってから）そのパス（`SourceStructure.pathOfNode`）の行へ寄せる。ソースを離れてフォームへ移るときは、カーソルの位置のパスを `highlightPath` に入れる（フォームの `Highlightable` が寄せて強調する）。
- **保存済みとの差分の印**: `doc.rawText`（開いたとき・保存したときのディスクの内容）と今の下書きの行差分（`LineMerge.diff`）を今の行番号に写し、行番号の左に出す。
- **大きな文書**: 解析・出力・色分けは 1 万行で 20ms 程度（行ごとの覚え `YamlHighlighter`・共通部分を落とす `LineMerge.diff`）。Compose の欄は全文を配置し直すので上限がある（manual/limits.md）。

## 文書の作成と削除

- 新規 YAML・ビューの詳細文書と新規行ファイル・カバレッジ指摘からの詳細文書・スキーマ推測は、`AppState.createDocumentFile` の単一経路。セッションへ `CreateDocument` 1 件のトランザクションを適用 → 保存 → `refreshAfterSessionWrite`（`reload` しない）。
- 作成は履歴に入り Ctrl+Z で取り消せ、それまでの履歴も保たれる。
- 文書の作成・削除を含む履歴の Undo / Redo は `afterHistoryStep` がファイルの有無までその場で確定する（対象文書だけ `session.save`）。作成の取り消しには保存の手段が無く、削除予約のままではディスクとセッションが食い違うため。
- セッションが文書として扱わないパスと、セッション不在時だけ、直接書込＋`reload` へフォールバックする。

## 規律

- `YNode` は Compose の State ではない。木を表示するコンポーザブルは `doc.version` / `state.indexVersion` を読んで購読する。
- ツリーの変更は必ず `markEdited()` とセットで行う。
- 文書を作る機能は `AppState.createDocumentFile` を通す。`Files.writeString` ＋ `reload()` で作らない。`reload()` はセッションを開き直すため Undo 履歴が全て失われ、作成自体も Undo できなくなる。
- 内部で変更を生成するコードは `edit.YamlOperation` を使う。`patch.PatchOp` と変更セットは交換形式に限る。
- 連続入力になり得る新しい編集面は、合体キーを渡す。構造操作はキーなし。
- ソースの下書きは `OpenDocument` が持つ。木が変わったとき下書きを正規化テキストで置き換えない（`syncSourceFromTree` の差し込み）。解析できない下書きを無視してフォームを編集可能にしない（`FormCtx.readOnly` の既定に `draftBroken` が入っている）。
- 解析できないテキストを書くときは `WorkspaceSession.saveAsText`（文書集合から外す）。`reload()` は履歴を消すので、壊れた保存の取り込みに使わない。

## 拡張ポイント

- **新しい編集面**: `FormCtx` を通し（[ui.md「フォームと FormCtx」](ui.md)）、書込は `Slot` ＋ `markEdited()`。連続入力なら合体キーを渡す。
- **履歴に入れる新しい操作**: セッションのトランザクション（`YamlOperation` / `WorkspaceOperation`）として表し、ファイルの有無が変わる操作は `afterHistoryStep` の確定に載せる。

## 一般ファイルを伴う履歴

ツリーの改名・移動・削除・フォルダー作成もセッションの履歴に入る。`afterHistoryStep` は関連付けられた配置計画を取得し、選択・展開・最近のファイル・表示文書を移動先へ追随させる。全体の再読込で履歴を消さない。
削除は `.yde-trash` へ退避する。配置と定義の修正を一緒に復元し、取り消す直前に外部変更があれば拒否する。壊れたソースの下書きも未保存保護の対象。
