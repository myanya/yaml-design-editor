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

使い方の説明は [manual/](../manual/README.md)、実装の設計は [architecture/](../architecture/README.md) にある。
