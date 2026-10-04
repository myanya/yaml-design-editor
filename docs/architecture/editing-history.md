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
| `SourcePane` / `applySourceText` | ui/SourcePane.kt | ソース編集のライブ同期 |
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

- 木が常に正。ソースのテキストバッファは木の表現の 1 つで、パース成功のたびに `applySourceText` で木を更新する（`markEdited` の経路＝履歴・dirty・editVersion・オーバーレイ索引・検証がフォーム編集と同じに追随）。反映後もバッファは書き換えない（カーソル・書式を保つ）。
- パース不能中は、最後に解析できた状態の木が正で、保存もそれを書く。未解析のテキストは画面上にだけあり、エラーバナー（行・列）で明示する。「破棄」で正規化テキストへ戻せる。ソースタブの Ctrl+S はパース不能中は保存を拒否して案内する。
- 自分の反映で進んだ `doc.version` は同期済みとして無視し、他所からの変更（フォーム・グリッド・Undo/Redo・外部変更の再読込）で進んだ場合だけテキストを取り直す（`syncedVersion`）。
- ソースの連続入力は合体キーで 1 エントリにまとまり、フォームとの行き来はキーが変わるため境界になる。

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

## 拡張ポイント

- **新しい編集面**: `FormCtx` を通し（[ui.md「フォームと FormCtx」](ui.md)）、書込は `Slot` ＋ `markEdited()`。連続入力なら合体キーを渡す。
- **履歴に入れる新しい操作**: セッションのトランザクション（`YamlOperation` / `WorkspaceOperation`）として表し、ファイルの有無が変わる操作は `afterHistoryStep` の確定に載せる。
