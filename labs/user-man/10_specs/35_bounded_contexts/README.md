# 境界づけられたコンテキスト

用語とモデルの意味を一貫させる範囲を定義します。業務の問題領域を分けるサブドメインとは区別します。
同じ「ユーザ」でも、ユーザ管理では利用状態を持つ管理対象、認証では認証情報の主体、権限管理ではロールの付与先として捉えられます。
参考：[FowlerのBounded Context](https://www.martinfowler.com/bliki/BoundedContext.html)、[EvansのDDD Reference](https://www.domainlanguage.com/ddd/reference/)。

## 文書の形式

[一覧](0000-bounded-context-lists.yaml)にID・タイトル・概要を置き、詳細文書にモデルの境界を記述します。
共通の分担・ID・所属方針は [共通記述規約](../WRITING.md) に従います。

| 詳細の項目 | 内容 |
|---|---|
| `id`・`status` | コンテキストのIDと草案／確定 |
| `purpose`・`responsibility` | 目的と責務 |
| `out_of_scope` | 他の境界へ委ねること |
| `concepts` | この境界での用語と意味 |
| `information_ownership` | 情報の更新を管理する責任 |
| `public_contract` | 公開する操作・情報と振る舞い |
| `subdomains` | 担当領域と担当範囲を持つ参照 |
| `rule_refs` | 根拠の個別ルール |

情報の管理主体は、例えば「利用状態はユーザ管理、認証情報はユーザ認証」のように、更新の責任と他の境界での参照を区別します。
具体的なAPIのパス・クラス・テーブル・トランザクション構成は後続設計へ渡し、仕様段階では約束する振る舞いまでを記述します。
スキーマは [bounded-context-list.yaml](../../.yde/schemas/bounded-context-list.yaml) と [bounded-context-detail.yaml](../../.yde/schemas/bounded-context-detail.yaml) です。

## サブドメインとの対応

サブドメインとの対応はコンテキスト詳細の `subdomains` にだけ記録し、サブドメイン側は逆参照で表示します。
一つの対応に担当範囲を記録できる属性付き参照を使います。

```yaml
subdomains:
  - to: SDOM-0002
    responsibility: ユーザ情報と利用状態の管理
```

関係は `covers-subdomain`（担当する業務領域）です。
複数の対応を許し、分割時は各コンテキストの担当部分、統合時は複数領域をまとめる理由を記述します。
対応の存在だけでは、その領域の全業務の網羅を意味しません。
単に別領域の情報を利用することは担当の対応に含めず、[コンテキスト間の連携](../37_context_maps/README.md)で記述します。
関係の定義は [domain-relations.yaml](../../.yde/schemas/domain-relations.yaml) に置きます。

## 境界の判断

サブドメイン名との対応を出発点としても、1対1を強制しません。
用語・状態遷移・変更責任を確認し、別の境界に分離するか、一つの境界内にまとめるかを判断します。
機能ID、組織・グループによる権限範囲、テナントのデータ分離、モジュール・サービスの配置とはそれぞれ異なる軸です。
境界を分けるだけではマイクロサービス化を決めません。
利用側システムは外部境界として扱い、具体的な分割や未解決の連携は [TODO](../../TODO.md) で管理します。

対応一覧・マトリクス・グラフレンズの使い方は [操作・検証ガイド](../../OPERATIONS.md) を参照してください。
