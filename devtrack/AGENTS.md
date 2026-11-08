# AGENTS.md

開発管理のワークスペース。指摘・テーマ・所感メモと機能索引とロードマップの原本。運用と操作は [README.md](README.md)「運用」。

- **原本はここ**。docs/BACKLOG.md・docs/archive/backlog-closed.md・docs/FEATURES.md・docs/ROADMAP.md・docs/archive/roadmap-history.md は生成物で、直接直さない。YAML を直したら `./gradlew devtrackDocs` で作り直し、一緒にコミットする（作り直し忘れはコミット前のフックと CI の `DevtrackTest` が止める）。
- 機能は `features/<ID>.yaml`（1 機能 1 ファイル）。足すときは `action devtrack register-feature`（系統ごとに採番）か、同じ形の文書を書く。テストの種類は、画面を組み立てるテストなら `kind: ui`、`./gradlew test` に入らない検査なら `kind: check`。手動で確かめるなら `manual_check: true`（テストを足したら外す）。
- 登録・状態の変更・解決・再開は操作（`action devtrack register-item` など）で行う。登録で ID を省けば採番する。関わる機能が分かれば `features`（FEATURES の ID の並び）を、対応するロードマップの項目が分かれば `roadmap`（R 項目かテーマの ID の並び）を渡す（機能の行に未解決の指摘として出る）。
  ファイルを直接書くときは、起点の日（`origin_on`）と、解決なら解決の日（`closed_on`）と解決の記録（`resolutions`。種類 `kind` つき）も書く。
- ロードマップは `roadmap/P<番号>.yaml`（1 テーマ 1 ファイル。`number` は ID の番号で、並びの順）。本文と節の本文は「本文のかたまり」の並びで、かたまり 1 つに文（`text`）・箇条（`points`）・R 項目の表（`columns` と `items`）のどれか 1 つ。
  R 項目の状態は `state`・`state_on`・`state_note`（・`moved_to`）で持ち、「✅ 日付（補足）」などの文字はレポートが作る。状態の変更は操作 `change-item-state`、テーマを終えるのは `complete-theme`（本文と節を記録の新しい項へ移す）、判断待ちを決めるのは `decide`。R 項目を足すときは表に書き、ID は R と番号（テーマの番号の中の次。画面の追加ボタンは採番する）。
- 本文の決定は ID（`DEC-` と 3 桁）を持つ。オーナーの判断を待つものは `status: pending`（語 `decision` は書かない）で、ダッシュボードの「判断待ちの決定」に出る。決めたら操作 `decide` で決定済みにする。
- 手で番号を決めるなら `backlog/` の最大の番号の次（解決済みの指摘も同じ場所にある）。
- 操作の受け取る値や項目のキーに `on`・`no`・`yes` を使わない（YAML が真偽値に読む）。日付は `date`。
- 定義（`.yde/`）を変えたら `validate devtrack` を通し、`./gradlew devtrackDocs` で生成物の変化を確かめる。
