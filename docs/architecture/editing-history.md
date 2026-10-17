# 編集と履歴

編集は木を直接変え、`markEdited()` で履歴・dirty・検証・見たまま判定が追随する。履歴（Undo/Redo）と dirty の正は、カーネルのセッションが持つワークスペース横断の単一履歴で、文書ごとの履歴は持たない（ADR-0006）。
文書の作成と削除も履歴に入り、Undo でファイルの有無まで確定する。

## 部品

| 部品 | ファイル | 責務 |
|---|---|---|
| `OpenDocument` | app/AppState.kt | 開いている 1 文書の Compose シェル（`version` / `dirty` / `parseError` の購読）。複数ドキュメントの再出力と保存内容との比較は `EditBuffer` へ委譲し、外部競合を管理する |
| `EditBuffer` | core/Documents.kt | 複数ドキュメントの再出力と、保存した内容との比較（履歴は持たない）。ここの dirty はセッションが文書として扱わないパス用で、聞かれたときにだけ計算する |
| `WorkspaceSession.recordEdit` | workspace-core | 編集の記録（合体キーと時刻つき）。Undo / Redo の実体 |
| `AppState.undo` / `redo` / `historyNotice` / `undoPreview` | app/AppState.kt | 横断 Undo の実行と、戻った内容の通知・予告 |
| `AppState.createDocumentFile` / `afterHistoryStep` | app/AppState.kt | 文書の新規作成の単一経路と、作成・削除を含む履歴の確定 |
| `FormCtx.writeScalar` | ui/FormView.kt | スカラー入力の書込。合体キーを渡す |
| `OpenDocument.source` / `applyParsed` / `syncSourceFromTree` / `discardDraft` / `enterTextOnly` | app/AppState.kt | ソースの下書き（文字列・カーソル・解析エラー）の所有と、木との同期 |
| `SourcePane` / `SourceDraftBanner` | ui/SourcePane.kt | ソースの表示と入力（入力が止まってから背景で解析）。他のタブに出す書きかけ・壊れたまま保存のバナー |
| `LineMerge` | ui/SourceMerge.kt | 行の差分（共通部分の切り落とし＋LCS）と 3 者マージ・文字列とカーソルへの適用（純ロジック） |
| `SourceEditing` | ui/SourceEditing.kt | 基本操作の規則（改行のインデント・インデント・コメント切替・行の移動と複製・検索と置換）。文字列と選択範囲を受けて返す純ロジック |
| `YamlHighlighter` | ui/SourceHighlight.kt | 行ごとの覚えつきの色分け（純ロジック） |
| `AppState.saveDraftAsText` / `historyStep` / `ensureAttached` | app/AppState.kt | 壊れたまま保存、下書きを捨てる確認つきの Undo/Redo、テキスト文書を文書集合へ戻す |
| `WorkspaceSession.saveAsText` / `attachDocument` | workspace-core | テキストのまま保存して文書集合から外す（履歴は残す）。解析できる内容で文書集合へ戻す |
| `DiffPane.calculateDiff` / `charDiffRanges` | ui/DiffPane.kt | 保存済みと編集中の差分（行整列・文字単位の強調。純ロジック） |

## 編集の経路

- フォーム操作 → `Slot.set` または木の直接変更 → `markEdited()`（`AppState.recordEditToSession` が履歴と dirty をセッションへ記録）→ `version` が進み再コンポーズ。
- ビューグリッド・エンティティページ・スキーマ設計 UI も同じ経路（文書キャッシュ `AppState.ensureDoc` の木を `Slot` で変えて `markEdited()`）。専用の書込経路を作らない。
- 一括編集（ルート直下のスカラーキー）はセッションの 1 トランザクションで、Undo 1 回で全ファイルが戻る。
- 内部で変更を生成するコードは `edit.YamlOperation` を使い、`PatchOp` と変更セットはエージェント・CLI・skill との交換形式に限る（[yaml-model.md「変更の基礎命令と 2 層」](yaml-model.md)）。
- 保存（`AppState.save` / `saveAll`）は、UI の木をセッションへ写した後・書き出す前に、保存時に自動で入る値（更新日・版。[auto-values.md](auto-values.md)）をセッションの `applyAutoValues` で 1 トランザクション（履歴 1 件）として当て、入った文書の木をセッションから写し直す。保存自体は今までどおり履歴項目ではない。

## 履歴と dirty

- 履歴はセッション（workspace-core）のワークスペース横断の単一履歴。開いていない文書も戻す。
- 横断 Undo は開いていない文書も変えるため、`AppState` は実行後に「何が戻ったか」の通知（`historyNotice`＝変更の意図・対象ファイルへのジャンプ導線・未保存●の明示。次の編集・保存・✕で消える）と、Undo/Redo ボタンのホバー予告（`undoPreview` / `redoPreview`）を組み立てる。
- `OpenDocument.dirty` はセッションの正の写し。`EditBuffer` の文字列比較は、セッションが文書として扱わないパスのためだけ。

## 履歴の合体

連続入力を Undo 1 回で戻すため、編集の記録（`recordEdit(id, document, coalesceKey, nowMillis)`）に合体キーを渡す。

- **規則**: 直前の記録と同じキー（`==`）かつ 1000ms 以内かつ履歴の先端（Undo 後でない）なら、新しいエントリを積まずに先端を置換する。キーが null・キー不一致・時間超過・Undo/Redo 直後は新規エントリ＝境界。
- **キー**: フォーム・グリッドのスカラー入力は `FormCtx.writeScalar` が変異対象の `YNode.Scalar` インスタンス（同一フィールドの連続タイプだけが合体）。スキーマ設計 UI のテキスト入力は `Pair(対象 Map, キー名)`。ソース編集は文書単位の固定キー。構造操作（追加・削除・移動・enum 選択・変換）はキーなし＝常に境界。
- **決定性**: 時刻は `nowMillis` 引数で注入する（`DocumentsTest`。タイマー・sleep を使わない）。
- undo / redo / restore / resetHistory は合体キーをリセットする。

## ソースとの同期

木が常に正で、ソースの下書きは木の表現の 1 つ。

- **下書きは文書が持つ**: 文字列・カーソルは `OpenDocument.source`（Compose 標準の `TextFieldState`）、解析エラーは `sourceError`。画面の `remember` に置かないので、タブや文書を移っても残る。閉じるときの確認は未保存と同じ（`draftDirty` が `hasDirtyDocs` に入る）。
- **入力が止まってから反映**: `SourcePane` は文字列の変化を `snapshotFlow` で見て、300ms 止まったら背景で解析し（`EditBuffer.parse`）、UI 側で `applyParsed` が木を更新する。経路は `markEdited`＝履歴・dirty・editVersion・オーバーレイ索引・検証がフォーム編集と同じに追随する（合体キーは文書単位の固定キー）。保存・Undo・タブを離れるときは `flushDraft` で入力待ちの分を先に取り込む。反映しても下書きの文字列は書き換えない（カーソル・書式を保つ）。
- **解析できない間**: 最後に解析できた木が正。`draftBroken` で `FormCtx.readOnly` が真になり、フォーム・グリッド・横ぐしが閲覧専用になる（書きかけをフォームで上書きしない）。他のタブには `SourceDraftBanner`（「ソースで直す」「書きかけを捨てる」）。その文書に関わる Undo/Redo は `historyStep` が「下書きを捨てて戻すか」を確認してから進める（`pendingHistory`）。
- **他所の変更の差し込み**: 自分の反映で進んだ `doc.version` は同期済み（`sourceSyncedVersion`）。他所（フォーム・Undo/Redo・操作・外部変更の再読込）で進んだら `syncSourceFromTree` が 3 者マージ（`LineMerge`）で変わった行だけを差し込み、カーソルを写す。元は前回の正規化テキスト（`sourceBase`）・自分は下書き・相手は今回の正規化テキスト。重なる変更は下書きを優先して件数をステータスで知らせる。丸ごと置き換えるのは「整形」「編集を破棄」（`discardDraft`）だけ。
- **壊れたまま保存**: `AppState.save` は下書きが解析できなければ止めず `saveDraftAsText` へ。セッションの `saveAsText` がテキストをそのまま書き、文書を文書集合から外す（ディスク上で解析できないファイルと同じ＝索引・検証・派生の対象外。履歴は消さない）。文書は `textOnly`（`root == null`）になり、ヘッダのバナー・ツリーの「解析不可」・検証一覧のパースエラーで見える。`_schemas` の文書だけは `pendingTextSave` で確認してから。
- **直すと戻る**: 直して解析できたら `ensureAttached` が `attachDocument` で文書集合へ戻し（保存済みとの差で dirty）、保存で木の内容が書かれる。壊す前の木は `restoreLastParsed` で戻せる。テキスト文書に関わる履歴の記録は、戻るまで適用できない（`historyStep` が案内して止める）。
- **Ctrl+Z の一本化**: 欄の中の取り消しは使わず、`SourcePane` が Ctrl+Z / Ctrl+Y / Ctrl+Shift+Z をセッションの履歴へ回す（フォームと同じ 1 本の履歴。差し込みでカーソルは留まる）。
- **基本操作**: 欄のキー（Tab・Enter・Ctrl+/・Ctrl+D・Alt+↑↓・Ctrl+F / H / G・F3）は `SourcePane.handleEditorKey` が受け、規則は `SourceEditing` に閉じる（文字列と選択範囲を受けて返す）。日本語入力の変換中（`composition != null`）は Enter と Tab を奪わない。
- **検索の見せ方**: 一致の強調は色分けと同じ `OutputTransformation`、現在行の背景は欄の `drawBehind`。一致や行へ移動したときのスクロールは `revealRequest` を見た `LaunchedEffect` が配置結果から行の位置を取って行う。検索・置換の入力と現在の一致は操作の途中なので `remember`（`SourceUi`）。
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
