# グラフレンズと図の記法

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/lens-diagrams-md.md -->

グラフレンズの見え方（`kind: lens` のビュー）と、図の記法（`kind: diagram-notation` のビュー）に書ける語の一覧。
今の実装が読む語を書いたもの。ほかの参照文書は [README.md](README.md) の表にある。
**原本は [devtrack/](../../devtrack/README.md) の `spec/`**（1 語 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

## グラフレンズの見え方（kind: lens）

グラフレンズで、関係の線の向き・箱と線の記法・絞り込みを決めます。表示だけで、検証とクエリの向きは変わりません。

- メタスキーマ: builtin-schemas/view.yaml の root
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseLensView
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: X-12

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `relations` |  | {name, arrow} の配列 |  | 関係ごとの見え方。 |
| `filters` |  | 絞り込みの配列 |  | 画面で入れる・外す絞り込み。入れた絞り込みのどれかが隠す箱は隠します（起点は隠しません）。 |
| `appearance` |  | {nodes, edges} |  | 図の見え方。箱と線に当てる記法です。 |

## 関係の見え方（relations の要素）

レンズの relations の要素。

- メタスキーマ: builtin-schemas/view.yaml の root.relations.item
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseLensView
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: X-12

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `name` | 必須 | 関係の名前 |  | 関係レジストリの関係の名前か、参照の項目の relation。 |
| `arrow` |  | forward か reverse | forward | 矢印の向き。forward は参照を書いた側から相手へ（文字は label）、reverse は相手から参照を書いた側へ（文字は inverseLabel）です。 |

## 絞り込み（filters の要素）

レンズの filters の要素。ここに無いキーは誤りになります。

- メタスキーマ: builtin-schemas/view.yaml の root.filters.item
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseLensFilters
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: X-12

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `id` | 必須 | 文字列 |  | 絞り込みの ID。 |
| `label` |  | 文字列 | ID | 表示名。 |
| `mode` | 必須 | hide か show |  | 合う箱を隠す（hide）か、合う箱だけを残す（show）か。show では、値が無くて決められない箱も隠します。 |
| `level` |  | entity か type | 両方の段 | 効かせる段。 |
| `entityType` |  | エンティティ型の名前 | すべての型 | 効かせる型。対象の外の箱には効きません。 |
| `where` | 必須 | 条件か、条件の配列 |  | 箱の条件。変換の $when と同じ書き方で、箱の材料（id・title・entityType・data）を読みます。 |
| `default` |  | 真偽値 |  | 初めから入れておきます。 |
| `keepIfLinked` |  | {via, entityType, where, depth} |  | この絞り込みが隠す箱でも、線でたどって着く相手のどれかが合えば残します。 |

## つながりで残す（keepIfLinked）

絞り込みの keepIfLinked。

- メタスキーマ: builtin-schemas/view.yaml の root.filters.item.keepIfLinked
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseLensKeep
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: X-12

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `via` |  | 関係の名前の配列 | すべての線（参照と保有） | たどる線。 |
| `entityType` |  | エンティティ型の名前 | すべての型 | 相手の型。 |
| `where` |  | 条件か、条件の配列 | つながっていれば残します | 相手の箱の条件。 |
| `depth` |  | 整数 | 1 | たどる段の数。1 は直接つながる相手だけです。 |

## 図の見え方（appearance）

レンズの appearance。

- メタスキーマ: builtin-schemas/view.yaml の root.appearance
- 読む実装: workspace-core/workspace/DiagramDefinitions.kt の appearance
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: X-18

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `nodes` |  | {level, entityType, notation, inputs} の配列 |  | 箱に当てる記法。 |
| `edges` |  | {relation, notation, inputs} の配列 |  | 線に当てる記法。 |

## 箱の記法（nodes の要素）

appearance の nodes の要素。

- メタスキーマ: builtin-schemas/view.yaml の root.appearance.nodes.item
- 読む実装: workspace-core/workspace/DiagramDefinitions.kt の appearance
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: X-18

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `level` |  | entity か type |  | 当てる段。 |
| `entityType` |  | エンティティ型の名前 |  | 当てる型。 |
| `notation` | 必須 | 記法の ID |  | 使う図の記法。 |
| `inputs` |  | ひな型 |  | 記法へ渡す入力を組み立てるひな型。 |

## 線の記法（edges の要素）

appearance の edges の要素。

- メタスキーマ: builtin-schemas/view.yaml の root.appearance.edges.item
- 読む実装: workspace-core/workspace/DiagramDefinitions.kt の appearance
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: X-18

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `relation` |  | 関係の名前 |  | 当てる関係。 |
| `notation` | 必須 | 記法の ID |  | 使う図の記法。 |
| `inputs` |  | ひな型 |  | 記法へ渡す入力を組み立てるひな型。 |

## 図の記法（kind: diagram-notation）

箱か線 1 つの描き方。入力の形を決め、ひな型で表示の部品（box・text・image など）を組み立てます。

- メタスキーマ: builtin-schemas/view.yaml の root
- 読む実装: workspace-core/workspace/DiagramDefinitions.kt の parse
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: X-18

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `target` |  | node か edge |  | 記法の対象（箱か線）。 |
| `input` |  | {type, fields} |  | 入力の決まり。記法が受け取る値の形です。 |
| `build` |  | ひな型 |  | 表示の部品を組み立てるひな型。 |
| `rules` |  | {id, when, with, build} の配列 |  | 名前を付けた組み立て。 |

## 入力の決まり（input）

記法の input。

- メタスキーマ: builtin-schemas/view.yaml の root.input
- 読む実装: workspace-core/workspace/DiagramDefinitions.kt の parse
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: X-18

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `type` |  | object | object | 入力の形。 |
| `fields` |  | 入力の項目の配列 |  | 入力の項目。 |

## 入力の項目

記法の入力の項目 1 つ。スキーマの項目定義のうち、形と値の決まりだけを書けます。

- メタスキーマ: builtin-schemas/view.yaml の definitions.notationInput
- 読む実装: workspace-core/workspace/DiagramDefinitions.kt の parse
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: X-18

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `key` |  | 文字列 |  | 項目のキー。 |
| `label` |  | 文字列 |  | 表示名。 |
| `type` |  | string・int・number・boolean・enum・array・map・object・any のどれか |  | 項目の型。 |
| `required` |  | 真偽値 |  | 必須。 |
| `values` |  | 配列 |  | enum の選択肢。 |
| `fields` |  | 入力の項目の配列 |  | object の子の項目。 |
| `item` |  | 入力の項目 |  | array の要素。 |
| `value` |  | 入力の項目 |  | map の値。 |
| `min` |  | 数 |  | 値の下限。 |
| `max` |  | 数 |  | 値の上限。 |
| `minLength` |  | 整数 |  | 文字数の下限。 |
| `maxLength` |  | 整数 |  | 文字数の上限。 |
| `pattern` |  | 正規表現の文字列 |  | 値の形式。 |
| `unknownFields` |  | allow・warning・error のどれか |  | 入力に無いキーの扱い。 |

