# AGENTS.md

開発管理のワークスペース。指摘・テーマ・所感メモと機能索引の原本。運用と操作は [README.md](README.md)「運用」。

- **原本はここ**。docs/BACKLOG.md・docs/archive/backlog-closed.md・docs/FEATURES.md は生成物で、直接直さない。YAML を直したら `./gradlew devtrackDocs` で作り直し、一緒にコミットする（作り直し忘れはコミット前のフックと CI の `DevtrackTest` が止める）。
- 機能は `features/<ID>.yaml`（1 機能 1 ファイル）。足すときは `action devtrack register-feature`（系統ごとに採番）か、同じ形の文書を書く。テストの種類は、画面を組み立てるテストなら `kind: ui`、`./gradlew test` に入らない検査なら `kind: check`。手動で確かめるなら `manual_check: true`（テストを足したら外す）。
- 登録・状態の変更・解決・再開は操作（`action devtrack register-item` など）で行う。登録で ID を省けば採番する。関わる機能が分かれば `features`（FEATURES の ID の並び）を渡す（機能の行に未解決の指摘として出る）。
  ファイルを直接書くときは、起点の日（`origin_on`）と、解決なら解決の日（`closed_on`）と解決の記録（`resolutions`。種類 `kind` つき）も書く。
- 手で番号を決めるなら `backlog/` の最大の番号の次（解決済みの指摘も同じ場所にある）。
- 操作の受け取る値や項目のキーに `on` を使わない（YAML が真偽値に読む）。日付は `date`。
- 定義（`.yde/`）を変えたら `validate devtrack` を通し、`./gradlew devtrackDocs` で生成物の変化を確かめる。
