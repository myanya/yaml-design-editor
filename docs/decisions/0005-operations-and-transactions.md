# ADR-0005: すべての変更をoperation/transactionで表し、applyとsaveを分離する

- 日付: 2026-08-16
- 状態: 承認済み

## 決定

- 単一文書の書き換えは型付き `YamlOperation`（SetScalar / Insert / Remove / Move / RenameKey / Replace）で表し、適用と同時に逆操作を得る。
- 文書集合の書き換えは `WorkspaceTransaction`（baseVersion + 操作列）で表す。
- `apply` はworking snapshotだけを更新し、ファイルへの書込は `save` だけが行う。保存は履歴項目ではない（保存とUndo/Redoは独立）。
- coreの書込経路は `DocumentStore` と `WorkspaceSession.save` へ集約する。

## 帰結

- 未保存確認・Undo・AI提案・dry-runが同じ候補snapshot機構で共通化できる。
- `apply` のテストではDocumentStoreの書込回数0を必須にする。
- 現行 `Workspace.apply`（検証してそのままファイルへ書く）とは意図的差分（[意図的差分一覧](../archive/intentional-diffs.md) D-01）。
