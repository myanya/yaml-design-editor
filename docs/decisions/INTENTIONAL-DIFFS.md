# 意図的差分一覧

3層コア移行で**最初から意図して変える**既存挙動の台帳（計画書§2.3）。
新旧比較テストでこの表に該当する差分が出た場合、移植ミスとして扱わず、新旧それぞれの期待値を明記する。
ここに載っていない差分はすべて移植ミスの疑いとして調査する。

| ID | 現行挙動 | 新APIの挙動 | 関連ADR |
|---|---|---|---|
| D-01 | `Workspace.apply` が変更セットを検証してそのままファイルへ書く | `apply` はworking snapshotだけを更新し、`save` が別途ファイルへ書く | ADR-0005 |
| D-02 | UIの文書キャッシュ・ディスク索引・agent stagingが別々のoverlayを持つ | すべて `WorkspaceSnapshot` を入力とする共通semantic engineを使う | ADR-0004 |
| D-03 | `EditBuffer` が正規化text snapshotを履歴に持つ | 型付き `YamlOperation` とinverseを基本履歴にする | ADR-0005 / 0006 |
| D-04 | `AppState` や複数のcore機能が直接ファイルを書く | coreの書込を `DocumentStore` と `WorkspaceSession.save` へ集約する | ADR-0005 |
| D-05 | `Schema.kt` と `Validator.kt` が構造規則と意味規則を同時に扱う | structuralとsemanticのcompile/validationを分ける | ADR-0002 / 0004 |
| D-06 | `Path` がschema・index・coreへ広く渡る | `Path` はworkspace-coreのI/O境界に閉じ、下位層には `DocumentId` を渡す | ADR-0002 |
| D-07 | 単文書ごとのUndoスタック（EditBuffer単位） | Undoスタックはworkspace履歴のみ。単文書編集も文書1件の `ChangeRecord` | ADR-0006 |
| D-08 | ソース編集はパース不能テキスト状態（`rawText` / `parseError`）を保持しUndoできる | coreの履歴は型付き操作のみ。生テキスト編集のUndoは将来UI層の責務（core外） | ADR-0006 |
| D-09 | 文書移動（DocMove）は「物理移動成功→参照書き換え失敗」の継ぎ目がありうる（メッセージ案内） | 移動と参照書き換えを1トランザクションで適用し、失敗時は何も起きない。1回のUndoで移動ごと戻る | ADR-0005 |
| D-10 | リファクタリング（rename等）がビュー定義・アクティビティ等の言及を自動追随・警告スキャンする | カーネル経路（SemanticRefactor）は追随せず警告で案内する（ビューはUI層の産物でコアから見えない。6E 仮想文書の設計後に再接続） | ADR-0002 |
| D-11 | 一括更新（bulkUpdate）はファイルごとに逐次書込（途中失敗で部分適用が残りうる・Undo不可）。保存は `Files.writeString` の直接書込 | 一括更新は**1トランザクション**として適用してから保存（1件でも適用できなければ何も書かない）。保存・一括更新の書込は `WorkspaceSession.save`＝`DocumentStore` 経由（一時ファイル→atomic move）。履歴一元化（A-3）後は一括更新が Undo 1回で戻る | ADR-0005 / 0006 |

## 記入規則

- 新しい意図的差分が生じたら、実装より先にこの表へ行を足す（1行=1差分。関連ADRがなければ先にADRを書く）。
- 差分を廃止（旧挙動へ戻す）した場合は行を消さず、取り消し線と理由を残す。
