# 関係レジストリとエンティティ型

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/schema-relations-md.md -->

関係レジストリ（`kind: relations` のスキーマ文書）の中身と、エンティティ型の宣言に書ける語の一覧。
今の実装が読む語を書いたもので、スキーマ文書のトップレベルの語は [schema-document.md](schema-document.md)、項目定義の語は [schema-fields.md](schema-fields.md)。
**原本は [devtrack/](../../devtrack/README.md) の `spec/`**（1 語 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

## 関係の宣言

関係レジストリの relations の要素。関係 1 つの名前・表示・向き・多重度を宣言します。

- メタスキーマ: builtin-schemas/relations.yaml の root.relations.item
- 読む実装: semantic-core/schema/SemanticDeclParser.kt の parseRelations

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `name` | 必須 | 文字列 |  | 関係の名前。参照の項目の relation（保有なら項目の relation かキーの名前）と同じ名前にします。 |
| `label` |  | 文字列 |  | 参照する向きの表示名（例 利用する）。 |
| `inverseLabel` |  | 文字列 |  | 参照される向きの表示名（例 利用される）。 |
| `source` |  | エンティティ型の名前の配列 | 限りません | 参照を書く側のエンティティ型。書くと、参照元の型と、出ていく側の多重度の検査が効きます。 |
| `target` |  | エンティティ型の名前の配列 | 限りません | 参照先のエンティティ型。書くと、参照先の型と、入ってくる側の多重度の検査が効きます。 |
| `cardinality` |  | {inMin, inMax, outMin, outMax} |  | 多重度。source・target を宣言した側にだけ効きます。組込みの語彙で、検証の前に基本の検査（参照の本数の比較）へ展開します。 |
| `acyclic` |  | 真偽値 |  | この関係で循環を作らない宣言。違反はいつもエラーです。 |
| `level` |  | warning か error | warning | 型と多重度の違反の重さ。 |
| `description` |  | 文章 |  | 関係の説明。 |

## 多重度

関係の宣言の cardinality。1 つの要素について、その関係の参照の本数の下限と上限を書きます。

- メタスキーマ: builtin-schemas/relations.yaml の root.relations.item.cardinality
- 読む実装: semantic-core/schema/SemanticDeclParser.kt の parseRelations

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `inMin` |  | 整数 |  | 参照先の要素 1 つへ入ってくる参照の本数の下限。 |
| `inMax` |  | 整数 |  | 参照先の要素 1 つへ入ってくる参照の本数の上限。 |
| `outMin` |  | 整数 |  | 参照元の要素 1 つから出ていく参照の本数の下限。 |
| `outMax` |  | 整数 |  | 参照元の要素 1 つから出ていく参照の本数の上限。 |

## エンティティ型の一覧の要素

関係レジストリの entities の要素。エンティティ型の表示名と、側面の並びを宣言します。

- メタスキーマ: builtin-schemas/relations.yaml の root.entities.item
- 読む実装: semantic-core/schema/SemanticDeclParser.kt の parseEntityTypes

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `type` | 必須 | 文字列 |  | エンティティ型の名前。スキーマの entity の type と同じ名前にします。 |
| `label` |  | 文字列 |  | 型の表示名。 |
| `description` |  | 文章 |  | 型の説明。 |
| `aspects` |  | {name, label} の配列 |  | エンティティページで側面を並べる順。 |

## 側面の並びの要素

エンティティ型の aspects の要素。同じ ID を書いた文書ごとの側面 1 つです。

- メタスキーマ: builtin-schemas/relations.yaml の root.entities.item.aspects.item
- 読む実装: semantic-core/schema/SemanticDeclParser.kt の parseEntityTypes

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `name` | 必須 | 文字列 |  | 側面の名前。スキーマの entity の aspect（省いたスキーマではスキーマ ID）と同じ名前にします。 |
| `label` |  | 文字列 |  | 側面の表示名。 |

## 強制の切り替え

関係レジストリの enforcement。ワークスペース単位で、検査を強める切り替えです。これ以外のキーは誤りになります。

- メタスキーマ: builtin-schemas/relations.yaml の root.enforcement
- 読む実装: semantic-core/schema/SemanticDeclParser.kt の parseEnforcement

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `undeclaredRelation` |  | warning か error | スキーマの読み込みの点検にだけ出ます | 関係レジストリに無い名前の関係を使う参照を、文書の側でも指摘します。 |
| `definingUniqueness` |  | warning か error | warning | 定義の出現がどこにも無い ID と、定義の出現が 2 つ以上ある ID の指摘の重さ。 |

## エンティティ型の宣言（文書のスキーマ）

文書のスキーマのトップレベルの entity。object 1 つか、その配列（ID の形で型を見分ける）で書きます。メタスキーマでは形を決めていないので、語は読み取りの実装から書いています。

- 読む実装: semantic-core/schema/SemanticDeclParser.kt の parseEntities

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `type` | 必須 | 文字列 |  | エンティティ型の名前。 |
| `idPattern` |  | glob の文字列 |  | この型の ID の形。複数の型を並べたとき、ID がどの型かを見分けます。 |
| `idType` |  | 型の名前の文字列 |  | この型の ID の型（type: id の型の定義の名前）。idPattern と両方書くと、両方に合う ID がこの型です。 |
| `defines` |  | 真偽値 |  | このスキーマの文書に書いた出現が、そのエンティティの定義であること。 |
| `aspect` |  | 文字列 | スキーマ ID | このスキーマの文書が持つ側面の名前。 |
| `owns` |  | 項目のキーの配列 | 宣言した項目の全部 | この側面が所有する項目。 |

