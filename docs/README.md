# ドキュメントの地図

文書は「今の姿」と「記録」に分ける。今の姿は書き直して小さく保ち、記録は `archive/` に置いて読まない前提にする。
更新の規則は [CONVENTIONS.md「ドキュメント更新規約」](CONVENTIONS.md) にある。機械チェックは `scripts/docs-lint.sh`。

## 今の姿

| 文書 | 役割 | 読むとき | 更新するとき |
|---|---|---|---|
| [README.md](../README.md) | 公開の入口。概要・クイックスタート・文書一覧 | 初見 | 概要やクイックスタートが変わったとき |
| [CLAUDE.md](../CLAUDE.md) | AI エージェント向けの作業ガイド。コマンド・ワークフロー・規約の要点・落とし穴 | 毎セッション自動 | 落とし穴が増減したとき。説明は設計文書へ書く |
| [.claude/skills/](../.claude/skills/) | エージェントの作業手順（skill）。文書の同期 `sync-docs`・画面の確認 `ui-check` | その作業をするとき（Claude Code では skill として呼ぶ。ほかのエージェントは SKILL.md を読む） | 手順や道具（Gradle のタスク・MCP）の癖が変わったとき |
| [manual/](manual/README.md) | 利用者マニュアル。主題ごとに 1 ファイル（画面・スキーマ定義・検証・ファイル・ビュー・ID・AI・CLI・既知の制約） | 利用者に見える仕様を確かめるとき | 利用者に見える仕様・操作・UI が変わったとき |
| [tutorial.md](tutorial.md) | 利用者向けの手引き。タスク管理を題材に、文書の作成とスキーマ設計を順に説明する | 初めて使うとき | 操作手順やスキーマの書き方が変わったとき |
| [FEATURES.md](FEATURES.md) | 機能索引。機能から実装ファイルとテストを引く | 着手前に対象機能の場所を引くとき | 機能の追加・削除・テストの追加 |
| [architecture/](architecture/README.md) | 設計。主題ごとに 1 ファイル（部品・流れ・規律・拡張ポイント） | 着手前に該当する主題を読む | クラス・責務・データフロー・方針が変わったとき |
| [GLOSSARY.md](GLOSSARY.md) | 用語集。このプロジェクトで決めた語の 1 行の意味・近い一般の語・詳しい文書の見出し | 知らない語に会ったとき | 語を足した・意味が変わった・案内先の見出しが変わったとき |
| [CONVENTIONS.md](CONVENTIONS.md) | 開発規約。スタイル・Compose・テスト・文書・UX フィードバック・モジュール・計算体系 | 実装中 | 規約を変えるとき |
| [ROADMAP.md](ROADMAP.md) | ビジョンと未完のテーマ。全テーマの状態表 | テーマの目的と残りを確かめるとき | 方向性の変更、テーマの着手・完了。完了したら本文を archive へ |
| [BACKLOG.md](BACKLOG.md) | 未解決の指摘台帳とテーマ | 気になりポイントを登録・確認するとき | 気づいたとき即時。解決したら行を archive へ |
| [UX-SCENARIOS.md](UX-SCENARIOS.md) | UX 試験の台本 | 試験の前 | 操作手順が変わったとき |
| [CORE-CALCULUS.md](CORE-CALCULUS.md) | YAML に対する計算体系の設計方針 | 決定論的機能を足す前 | 体系の原則を変えるとき。ADR とセット |
| [DESIGN-CHECK-CATALOG.md](DESIGN-CHECK-CATALOG.md) | 設計チェックのパターン台帳 | 検査を足す前 | 検証機構の追加・変更 |
| [decisions/](decisions/README.md) | ADR（設計決定） | 設計の決定を確かめるとき | 決定の追加。既存は書き換えず新しい ADR で上書き |

配布担当者向けの [releasing.md](releasing.md) は、Windows ZIP の作成・検査・公開手順を管理する。配布工程を変更したときに更新する。

共通の UI 操作規則は [architecture/ui-interactions.md](architecture/ui-interactions.md) に集約し、適用範囲と例外も管理する。
操作の意味はこの設計文書、実装への接続は `architecture/ui.md`、利用者への案内は manual、再現・確認手順は UX-SCENARIOS が持つ。
未解決の指摘は BACKLOG、実施計画が必要なテーマは ROADMAP、アーキテクチャ上の決定の変更は ADR で扱い、完了した細かな調整を未完台帳に残さない。

## 記録

| 文書 | 役割 |
|---|---|
| [archive/changelog.md](archive/changelog.md) | リリースごとの追加・変更・修正の記録。リリース時に追記する |
| [archive/](archive/README.md) | 完了した計画・台帳・経緯。追記だけで書き換えず、経緯を調べるときだけ読む |

## 読む順

着手前は FEATURES の該当行 → architecture/ の該当する主題 → 必要なら manual/ の該当ファイル。
実装中は CONVENTIONS。完了前は上の表の「更新するとき」に従って直し、`scripts/docs-lint.sh` を通す。
