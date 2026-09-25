# ドキュメントの地図

文書は「今の姿」と「記録」に分ける。今の姿は書き直して小さく保ち、記録は `archive/` に置いて読まない前提にする。
更新の規則は [CONVENTIONS.md「ドキュメント更新規約」](CONVENTIONS.md) にある。機械チェックは `scripts/docs-lint.sh`。

## 今の姿

| 文書 | 役割 | 読むとき | 更新するとき |
|---|---|---|---|
| [README.md](../README.md) | 公開の入口。概要・クイックスタート・文書一覧 | 初見 | 概要やクイックスタートが変わったとき |
| [CLAUDE.md](../CLAUDE.md) | AI エージェント向けの作業ガイド。コマンド・ワークフロー・規約の要点・落とし穴 | 毎セッション自動 | 落とし穴が増減したとき。説明は設計文書へ書く |
| [manual/](manual/README.md) | 利用者マニュアル。主題ごとに 1 ファイル（画面・スキーマ定義・検証・ビュー・ID・AI・CLI・既知の制約） | 利用者に見える仕様を確かめるとき | 利用者に見える仕様・操作・UI が変わったとき |
| [FEATURES.md](FEATURES.md) | 機能索引。機能から実装ファイルとテストを引く | 着手前に対象機能の場所を引くとき | 機能の追加・削除・テストの追加 |
| [architecture/](architecture/README.md) | 設計。主題ごとに 1 ファイル（部品・流れ・規律・拡張ポイント） | 着手前に該当する主題を読む | クラス・責務・データフロー・方針が変わったとき |
| [CONVENTIONS.md](CONVENTIONS.md) | 開発規約。スタイル・Compose・テスト・文書・UX フィードバック・モジュール・計算体系 | 実装中 | 規約を変えるとき |
| [ROADMAP.md](ROADMAP.md) | ビジョンと未完のテーマ。全テーマの状態表 | テーマの目的と残りを確かめるとき | 方向性の変更、テーマの着手・完了。完了したら本文を archive へ |
| [BACKLOG.md](BACKLOG.md) | 未解決の指摘台帳とテーマ | 気になりポイントを登録・確認するとき | 気づいたとき即時。解決したら行を archive へ |
| [UX-SCENARIOS.md](UX-SCENARIOS.md) | UX 試験の台本 | 試験の前 | 操作手順が変わったとき |
| [CORE-CALCULUS.md](CORE-CALCULUS.md) | YAML に対する計算体系の設計方針 | 決定論的機能を足す前 | 体系の原則を変えるとき。ADR とセット |
| [DESIGN-CHECK-CATALOG.md](DESIGN-CHECK-CATALOG.md) | 設計チェックのパターン台帳 | 検査を足す前 | 検証機構の追加・変更 |
| [decisions/](decisions/README.md) | ADR（設計決定） | 設計の決定を確かめるとき | 決定の追加。既存は書き換えず新しい ADR で上書き |

## 記録

| 文書 | 役割 |
|---|---|
| [archive/](archive/README.md) | 完了した計画・台帳・経緯。追記だけで書き換えず、経緯を調べるときだけ読む |

## 読む順

着手前は FEATURES の該当行 → architecture/ の該当する主題 → 必要なら manual/ の該当ファイル。
実装中は CONVENTIONS。完了前は上の表の「更新するとき」に従って直し、`scripts/docs-lint.sh` を通す。
