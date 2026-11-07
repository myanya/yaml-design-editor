---
name: sync-docs
description: コード変更後にプロジェクトドキュメント（docs/README.md の表にある文書）との整合を検査し、更新漏れと消し忘れを直す。機能の追加・変更・削除、クラスやパッケージの変更、規約変更を行ったとき、またはコミット前の最終確認に使う。
---

# ドキュメント同期チェック

現在の変更（未コミットの差分。指示があればコミット範囲）をドキュメント体系と突き合わせ、更新漏れと消し忘れを検出・修正する。
各文書の役割と更新のタイミングは docs/README.md の表が正。規則は docs/CONVENTIONS.md「ドキュメント更新規約」。

## 手順

1. `git status` / `git diff`（必要なら `git log`）で変更内容を把握する。
2. 変更を次の観点で分類し、docs/README.md の表で対応する文書の該当箇所を実際に開いて照合する:
   - 機能の追加・変更・削除 → devtrack の `features/<ID>.yaml`（新機能は 1 件足す。`action devtrack register-feature` でも足せる。機能の欄は 200 字以内）。FEATURES.md は直接直さず `./gradlew devtrackDocs` で作り直す
   - 利用者から見える仕様・操作・UI・スキーマ定義構文 → manual/ の該当ファイル（その機能の FEATURES の行の「操作の説明」が指す見出し。節を足したら `features/<ID>.yaml` の `manual` も直す）
   - クラス／パッケージの追加・移動・責務変更、データフロー・設計方針の変更 → architecture/ の該当する主題（部品・流れ・規律・拡張ポイント）
   - テストの追加 → その機能の `features/<ID>.yaml` の `tests`（UI のテストは `kind: ui`、`./gradlew test` に入らない検査は `kind: check`）
   - ロードマップのテーマの着手・完了 → ROADMAP の状態表と該当テーマ。完了したら本文を archive/roadmap-history.md へ移す
   - BACKLOG の指摘の登録・解決 → devtrack で行い（devtrack/README.md「運用」）、`./gradlew devtrackDocs` で docs/BACKLOG.md と archive/backlog-closed.md を作り直す
   - 規約・ビルド手順の変更 → CONVENTIONS、AGENTS.md のコマンド節
   - 新しい造語・語の意味の変更・案内先の見出しの改名 → GLOSSARY の該当行
3. **消し忘れ**を見る: 完了した計画・移した経緯が今の姿の文書に残っていないか。設計・マニュアル・規約・機能索引・用語集・AGENTS.md（モジュールのものも） にチケット番号や日付を書き足していないか。
4. `scripts/docs-lint.sh` を実行し、指摘（サイズ・行長・チケット番号・セルの文数・切れたリンク）を直す。
5. 見つかった漏れを修正する。修正時の注意:
   - 詳細は役割を持つ 1 つの文書にだけ書き、他からはリンクする。
   - 参照は「ファイル名＋見出しの文言」で書き、§番号や行番号を書かない。
   - 日本語で、既存の文体・表形式に合わせる。
6. 結果を報告する: 更新した文書と理由、更新不要と判断した理由、lint の結果。
