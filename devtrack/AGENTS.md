# AGENTS.md

開発管理のワークスペース。指摘・テーマ・所感メモの原本。運用と操作は [README.md](README.md)「運用」。

- **原本はここ**。docs/BACKLOG.md と docs/archive/backlog-closed.md は生成物で、直接直さない。YAML を直したら `./gradlew devtrackDocs` で作り直し、一緒にコミットする（作り直し忘れはコミット前のフックと CI の `DevtrackTest` が止める）。
- 登録・状態の変更・解決は操作（`action devtrack register-item` など）で行う。ファイルを直接書くときは、起点の日（`origin_on`）と、解決なら解決の日（`closed_on`）と解決の記録（`resolutions`）も書く。
- 新しい番号は `backlog/` の最大の番号の次（解決済みの指摘も同じ場所にある）。
- 操作の受け取る値や項目のキーに `on` を使わない（YAML が真偽値に読む）。日付は `date`。
- 定義（`.yde/`）を変えたら `validate devtrack` を通し、`./gradlew devtrackDocs` で生成物の変化を確かめる。
