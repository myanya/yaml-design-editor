# desktop（画面）で守ること

画面のコード（`desktop`・`desktop.ui`）を書く・直すときの決まり。全体の決まりはリポジトリの根の [AGENTS.md](../AGENTS.md)。説明は各行の設計文書の「規律」にある。

- `YNode` は Compose の State ではない。ビューは `doc.version` / `state.indexVersion` を読んで購読する → architecture/ui.md「UI シェル」
- UI は `state.index` でなく `state.overlayIndex()` を読み、`remember` のキーに `state.editVersion` を含める → architecture/index-references.md「索引の二層」
- フォームの編集 UI は `FormCtx` を通し `ctx.readOnly` の分岐を入れる。長く続く作業はスペース内の常設画面で、浮いたパネル（AlertDialog）にしてよいのは値を集めて 1 回で適用する短い作業と確認だけ。全体のショートカットは `Main.kt` の `handleShortcut` → architecture/ui.md「UI シェル」
- 画面を離れても残したい見え方（畳み・タブ・絞り込み・チェック・展開）は `remember` に置かず `state.memory`（`ScreenMemory`）へ。覚えは「あれば使う・無ければ既定」で、再読込で無くなった鍵を誤りにしない。「戻る」が戻すのは場所（`NavLocation`）だけ → architecture/ui.md「UI シェル」
- ソースの文字列・カーソル・解析エラーは `OpenDocument.source` ほかが持つ（画面の `remember` に持たない）。木が変わったら正規化テキストで置き換えず `syncSourceFromTree`（3 者マージ）で差し込む。解析できない下書きは `draftBroken` でフォームが閲覧専用になり、保存は `saveDraftAsText`（テキスト文書＝文書集合の外）。セッションの `reload()` は履歴を消すので、壊れた保存に使わない → architecture/editing-history.md「ソースとの同期」
- 画面のファイルは役割のパッケージ（common・form・source・schema・view・graph・file・run・action・shell）に置く。`ui.common` に機能の画面を足さない → architecture/ui.md「画面のパッケージ」
