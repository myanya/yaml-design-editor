# 仕様の参照文書

定義に書ける語の一覧。今の実装が読むものを、仕様のワークスペース [spec/](../../spec/README.md) の YAML から生成する。
ここの文書（この案内を除く）は直接直さない。足す・直すときは spec の YAML を書き、`./gradlew specDocs` で作り直す。

| 文書 | 内容 |
|---|---|
| [schema-fields.md](schema-fields.md) | スキーマの項目定義に書ける語と、`type` に書ける基底の種類 |

使い方の説明は [manual/](../manual/README.md)、実装の設計は [architecture/](../architecture/README.md) にある。
