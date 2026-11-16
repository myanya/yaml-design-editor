# 仕様の参照文書

定義に書ける語の一覧。今の実装が読むものを、開発管理のワークスペース [devtrack/](../../devtrack/README.md) の `spec/` の YAML から生成する。
ここの文書（この案内を除く）は直接直さない。足す・直すときは devtrack の YAML を書き、`./gradlew devtrackDocs` で作り直す。

| 文書 | 内容 |
|---|---|
| [schema-document.md](schema-document.md) | スキーマ文書のトップレベルに書ける語と、`kind` に書ける種類 |
| [schema-fields.md](schema-fields.md) | スキーマの項目定義に書ける語と、`type` に書ける基底の種類 |
| [schema-relations.md](schema-relations.md) | 関係レジストリ（関係・多重度・エンティティ型・強制の切り替え）と、エンティティ型の宣言に書ける語 |
| [schema-behavior.md](schema-behavior.md) | State・Event・Lifecycle・State の詳細化に書ける語 |
| [schema-actions.md](schema-actions.md) | 操作（アクション・型の操作・配列への追加の決まり）に書ける語 |
| [schema-checks.md](schema-checks.md) | 検査（検査 1 件・検査の定義・語彙の定義と、組込みの語彙 coverage・consistency・matrix・flow）に書ける語 |
| [schema-derive.md](schema-derive.md) | 派生スキーマの宣言に書ける語 |
| [expressions.md](expressions.md) | クエリ（node・edge）・条件（where）・値の比較（check）・値の式に書ける語 |
| [templates.md](templates.md) | 変換のひな型の指示と、変換定義・生成の定義・レポートの定義に書ける語 |
| [views.md](views.md) | ビュー（グリッド・クロス集計・文書ビュー）に書ける語 |
| [dashboards.md](dashboards.md) | ダッシュボード（入力・業務の流れ・区画の条件）に書ける語 |
| [lens-diagrams.md](lens-diagrams.md) | グラフレンズの見え方と、図の記法に書ける語 |
| [agents.md](agents.md) | エージェント・アクティビティ・評価セットの定義に書ける語 |
| [skills.md](skills.md) | skill（取り込み・書き出し・コードの実行）の定義に書ける語 |
| [workspace-config.md](workspace-config.md) | ワークスペースの構成（workspace.yaml）と、段の規則・補正に書ける語 |
| [charts-flows.md](charts-flows.md) | チャートと業務フロー図（あとから足せる種類のビュー）に書ける語 |

使い方の説明は [manual/](../manual/README.md)、実装の設計は [architecture/](../architecture/README.md) にある。
