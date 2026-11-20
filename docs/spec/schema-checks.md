# 検査

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/schema-checks-md.md -->

検査（スキーマ文書の `constraints`・`functions`・`vocabulary` と、組込みの語彙 `coverage`・`consistency`・`matrix`・`flow`）に書ける語の一覧。クエリ（`match`）・条件（`where`・`check`）・値の式の書き方は [manual/checks.md](../manual/checks.md) にある。
今の実装が読む語を書いたもので、スキーマ文書のトップレベルの語は [schema-document.md](schema-document.md)、項目定義の語は [schema-fields.md](schema-fields.md)。
**原本は [devtrack/](../../devtrack/README.md) の `spec/`**（1 語 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

## 検査 1 件

constraints の要素（object の項目定義の constraints も同じ形）。文書のスキーマに書いた検査の指摘は、起点の要素がそのスキーマの文書に現れればそこへ、現れなければ定義の出現へ出ます（そのスキーマの文書の要素だけを起点にするなら、起点の node に schema を書きます）。書き方は 3 通りで、クエリと判定（match と、expect・count・check・forAll のどれか 1 つ）を直接書くか、定義を名前で使うか（use と with だけ）、専用チェッカーを使います（checker。一緒に書けるのは id・description・args・messages・level・levelFrom・activeFrom）。

- メタスキーマ: builtin-schemas/schema.yaml の root.constraints.item
- 読む実装: semantic-core/schema/QuerySpec.kt の ConstraintRule
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: V-09

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `id` |  | 文字列 |  | 規則の ID。指摘の出どころとして表示します。 |
| `description` |  | 文字列 |  | 規則の説明。 |
| `match` |  | node と edge を交互に並べた配列 |  | クエリ。先頭の node が起点で、エンティティを線（参照・保有）でたどる形を書きます。 |
| `also` |  | {from, steps} |  | 分岐。束縛したノードからの、もう 1 本の連鎖です。 |
| `where` |  | 条件の配列 |  | ノードを値で絞る条件。並べた条件はすべて成り立つこと。{not: 条件} と {or: [条件, …]} も書けます。 |
| `limit` |  | 1 以上の整数 | 実装の既定の上限 | 照合する解の数の上限。 |
| `expect` |  | exists か absent |  | 解があること（exists）か、無いこと（absent）。count・check・forAll と一緒には書けません。 |
| `count` |  | {min, max} |  | 起点ごとの解の件数（重なりを除く）が範囲に入ること。expect・check・forAll と一緒には書けません。 |
| `check` |  | 比較の配列 |  | 値の比較。並べた比較はすべて成り立つこと。{演算子: [左, 右]} の形で、左右は値の式です。expect・count・forAll と一緒には書けません。 |
| `forAll` |  | {node, from, steps} |  | もう 1 つの集まり（node）のすべての要素について、起点から steps をたどった流れに解があること。expect・count・check と一緒には書けません。 |
| `message` |  | ひな型の文字列 | 判定の種類ごとの既定の文面 | 指摘の文面。{id}・{束縛名.id}・{count}・{left}・{right} などを埋め込めます。 |
| `reportAt` |  | {of, in, path} か {ref} | 起点を定義した場所 | 指摘を出す場所。値が書かれた場所か、参照が書かれた場所を指します。 |
| `status` |  | ひな型の文字列 |  | 充足の状況の表示。書いた検査は、要素ごとの「検査の状況」の一覧に出ます。reportAt とは組み合わせられません。 |
| `fixSite` |  | {schema, message} |  | 直せる場所への案内。指摘が別の文書へ書き足すことで直るとき、書き足す先のスキーマの文書に、指摘が出ている ID の一覧を案内として出します。 |
| `responses` |  | 対応の宣言の配列 |  | 指摘への対応（値の出どころへ移る・値を書き込む・詳細の文書を作る など）。 |
| `level` |  | warning か error | warning | 指摘の重さ。 |
| `levelFrom` |  | undeclaredRelation か definingUniqueness |  | 関係レジストリの enforcement のキーの名前。そのキーが宣言されていれば、その重さで指摘します。 |
| `activeFrom` |  | フェーズの名前の文字列 | いつも判定します | 判定を始めるフェーズ。文書がそのフェーズに達するまでは保留にします。 |
| `use` |  | 定義の名前の文字列 |  | functions に定義した検査を名前で使います。use を書いた検査に書けるのは use と with だけです。 |
| `with` |  | 受け取る値の名前 → 値 のマップ |  | 定義へ渡す値。 |
| `checker` |  | flow |  | 専用チェッカーの名前。flow は遷移の検証です。 |
| `args` |  | チェッカーごとの object |  | 専用チェッカーへ渡す値。 |
| `messages` |  | 指摘の種類 → ひな型 のマップ |  | 専用チェッカーの指摘の種類ごとの文面。書かなかった種類は指摘しません。 |

## 解の件数の範囲

検査の count。

- メタスキーマ: builtin-schemas/schema.yaml の root.constraints.item.count
- 読む実装: semantic-core/schema/QuerySpec.kt の ConstraintRule
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: V-09

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `min` |  | 整数 |  | 件数の下限。 |
| `max` |  | 整数 |  | 件数の上限。 |

## 検査の定義

functions の要素。名前と受け取る値を持つ検査の定義です。どのスキーマ文書に書いても、ワークスペース全体で使えます。同じ名前は書けません。

- メタスキーマ: builtin-schemas/schema.yaml の root.functions.item
- 読む実装: semantic-core/schema/CheckDefinitions.kt の CheckLibrary
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: V-14

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `id` | 必須 | 文字列 |  | 定義の名前。constraints の use と、語彙の uses から指します。 |
| `description` |  | 文章 |  | 説明。 |
| `params` |  | 名前の配列 |  | 受け取る値の名前。 |
| `checks` | 必須 | 検査の配列 |  | 中身。constraints と同じ書き方で、文字列の $名前 に渡された値が入ります（文字列がちょうど $名前 なら値そのもの、文の中なら文字として。$ そのものは $$）。 |

## 語彙の定義

vocabulary の要素。検査を短く書くための書き方（語彙）を作ります。ここに無いキーは誤りになります。

- メタスキーマ: builtin-schemas/schema.yaml の root.vocabulary.item
- 読む実装: semantic-core/schema/CheckDefinitions.kt の CheckLibrary
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: V-14

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `word` | 必須 | 文字列 |  | 語彙の名前。 |
| `description` |  | 文章 |  | 説明。 |
| `in` | 必須 | 場所の文字列 |  | 書ける場所。スキーマ文書の直下の配列の各要素（例 consistency[]）か、関係の宣言（relations[]）です。 |
| `uses` | 必須 | {when, unless, use, with} の配列 |  | 書かれた内容から、どの定義をどの値で使うか。1 件以上が必要です。when・unless は、そのキーが書かれている・いないときだけ有効にします。 |
| `requires` |  | キーのパスの配列 |  | 必ず書くキー。外れていれば、スキーマの読み込みの誤りになります。 |
| `keys` |  | キーの配列 |  | 書けるキーの一覧。外れていれば、スキーマの読み込みの誤りになります。 |
| `removed` |  | 廃止したキー → 案内 のマップ |  | 廃止したキーと、書かれたときに出す案内。 |

## 詳細の文書の要求（coverage）

coverage の要素。このスキーマの文書に書かれた ID が、指定したスキーマの文書にも必要な数だけ書かれていることを求めます。

- メタスキーマ: builtin-schemas/schema.yaml の root.coverage.item
- 読む実装: builtin-definitions/coverage.yaml
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: S-09・V-05

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `id` |  | 文字列 |  | 規則の ID。指摘の出どころとして表示します。 |
| `description` |  | 文字列 |  | 規則の説明。 |
| `idPattern` |  | glob の文字列 | このスキーマの文書の ID すべて | 対象にする ID の形。 |
| `detailIn` | 必須 | {schemaIds, minCount} |  | 詳細を書く先。 |
| `level` |  | warning か error | warning | 指摘の重さ。 |
| `activeFrom` |  | フェーズの名前の文字列 | いつも判定します | 判定を始めるフェーズ。文書がそのフェーズに達するまでは保留にします。 |

## 詳細を書く先

coverage の detailIn。

- メタスキーマ: builtin-schemas/schema.yaml の root.coverage.item.detailIn
- 読む実装: builtin-definitions/coverage.yaml
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: S-09・V-05

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `schemaIds` |  | スキーマ ID か、その配列 |  | 詳細を書く文書のスキーマ。必ず書きます（語彙の点検が確かめます）。 |
| `minCount` |  | 整数 | 1 | 必要な出現の数。 |

## 値の整合（consistency）

consistency の要素。ID を持つ要素の値が、導出した期待の値と合うことを確かめます。

- メタスキーマ: builtin-schemas/schema.yaml の root.consistency.item
- 読む実装: builtin-definitions/consistency.yaml
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: V-07

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `id` |  | 文字列 |  | 規則の ID。指摘の出どころとして表示します。 |
| `description` |  | 文字列 |  | 規則の説明。 |
| `idPattern` |  | glob の文字列 | このスキーマの文書の ID すべて | 対象にする ID の形。 |
| `path` | 必須 | パスの文字列 |  | 確かめる値の場所（ID を持つ要素からの相対パス）。 |
| `expected` | 必須 | {lookup, source, path, values, map, default} |  | 期待の値の導出。 |
| `assert` |  | equals・lte・gte のどれか | equals | 比べ方。確かめる値が期待の値と等しい・以下・以上であること。 |
| `level` |  | warning か error | warning | 指摘の重さ。 |
| `activeFrom` |  | フェーズの名前の文字列 | いつも判定します | 判定を始めるフェーズ。文書がそのフェーズに達するまでは保留にします。 |

## 期待の値の導出

consistency の expected。元の値を読む場所と、読んだ値の置き換えを書きます。

- メタスキーマ: builtin-schemas/schema.yaml の root.consistency.item.expected
- 読む実装: builtin-definitions/consistency.yaml
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: V-07

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `lookup` |  | {schemaId, fileGlob, via} |  | 参照を 1 段たどって、相手の要素から元の値を読みます。 |
| `source` | 必須 | {schemaId, fileGlob} |  | 元の値を読む出現（同じ ID が書かれた文書のうち、どれから読むか）。 |
| `path` | 必須 | パスの文字列 |  | 元の値の場所（要素からの相対パス）。 |
| `values` |  | {schemaId, fileGlob} の配列 |  | path の中の {パス} を埋めるのに使う出現。 |
| `map` |  | 元の値 → 期待の値 のマップ |  | 値の置き換えの辞書。 |
| `default` |  | 文字列 |  | 元の値が辞書に無いときの値。 |

## 参照の 1 段

expected の lookup。

- メタスキーマ: builtin-schemas/schema.yaml の root.consistency.item.expected.lookup
- 読む実装: builtin-definitions/consistency.yaml
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: V-07

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `schemaId` |  | スキーマ ID |  | 参照を読む出現のスキーマ。 |
| `fileGlob` |  | glob の文字列 |  | 参照を読む出現のファイル。 |
| `via` | 必須 | パスの文字列 |  | たどる参照の項目（要素からの相対パス）。 |

## 出現の指定

expected の source と values の要素。同じ ID が書かれた文書のうち、どれを読むかをスキーマかファイルで指します。

- メタスキーマ: builtin-schemas/schema.yaml の root.consistency.item.expected.source
- 読む実装: builtin-definitions/consistency.yaml
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: V-07

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `schemaId` |  | スキーマ ID |  | 読む出現のスキーマ。 |
| `fileGlob` |  | glob の文字列 |  | 読む出現のファイル。 |

## 網羅マトリクス（matrix）

matrix の要素。行と列のすべての組み合わせに、両方を参照する証人の要素があることを確かめます。対象外にする組み合わせは、証人の側に excluded: true の行として残します。

- メタスキーマ: builtin-schemas/schema.yaml の root.matrix.item
- 読む実装: builtin-definitions/matrix.yaml
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: V-11

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `id` |  | 文字列 |  | 規則の ID。指摘の出どころとして表示します。 |
| `description` |  | 文字列 |  | 規則の説明。 |
| `rows` | 必須 | エンティティの選択（{entity, schema, id}） |  | 行にする要素の集まり。 |
| `columns` | 必須 | エンティティの選択 |  | 列にする要素の集まり。 |
| `witness` | 必須 | {node, row, column} |  | 証人。行と列の両方を参照していれば、その組み合わせを満たします。 |
| `level` |  | warning か error | warning | 指摘の重さ。 |
| `activeFrom` |  | フェーズの名前の文字列 | いつも判定します | 判定を始めるフェーズ。文書がそのフェーズに達するまでは保留にします。 |

## 証人

matrix の witness。

- メタスキーマ: builtin-schemas/schema.yaml の root.matrix.item.witness
- 読む実装: builtin-definitions/matrix.yaml
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: V-11

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `node` | 必須 | エンティティの選択 |  | 証人にする要素の集まり。 |
| `row` | 必須 | {relation} |  | 証人から行への参照。 |
| `column` | 必須 | {relation} |  | 証人から列への参照。 |

## 関係の指定

関係の名前で参照を指す object。matrix の row・column と、flow の edges・usage の from・to、start・terminal が同じ形です。

- メタスキーマ: builtin-schemas/schema.yaml の root.matrix.item.witness.row
- 読む実装: builtin-definitions/matrix.yaml・flow.yaml
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: V-11

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `relation` | 必須 | 関係の名前の文字列 |  | 参照の関係の名前。 |

## 遷移の検証（flow）

flow の要素。宣言した遷移の集まりをたどって、到達できない要素・行き止まり・宣言の外の遷移を見つけます。

- メタスキーマ: builtin-schemas/schema.yaml の root.flow.item
- 読む実装: builtin-definitions/flow.yaml
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: V-12

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `id` |  | 文字列 |  | 規則の ID。指摘の出どころとして表示します。 |
| `description` |  | 文字列 |  | 規則の説明。 |
| `nodes` | 必須 | エンティティの選択 |  | 遷移の点にする要素の集まり。 |
| `edges` | 必須 | {from, to} |  | 宣言した遷移。同じ行の object の直下にある from の参照と to の参照の組を、遷移 1 本として読みます。 |
| `start` |  | {relation} | 到達できない要素を判定しません | 開始の点を指す参照。 |
| `terminal` |  | {relation} |  | 終端として許す点を指す参照。行き止まりの判定から外します。 |
| `usage` |  | {from, to} | 判定しません | 使っている遷移。この集まりが、宣言した遷移に含まれることを求めます。 |
| `level` |  | warning か error | warning | 指摘の重さ。 |
| `activeFrom` |  | フェーズの名前の文字列 | いつも判定します | 判定を始めるフェーズ。文書がそのフェーズに達するまでは保留にします。 |

## 遷移の組

flow の edges と usage。

- メタスキーマ: builtin-schemas/schema.yaml の root.flow.item.edges
- 読む実装: builtin-definitions/flow.yaml
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: V-12

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `from` | 必須 | {relation} |  | 遷移の元への参照。 |
| `to` | 必須 | {relation} |  | 遷移の先への参照。 |

