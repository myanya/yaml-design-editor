# State・Event・Lifecycle

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/schema-behavior-md.md -->

振る舞いの宣言（`kind: behavior` のスキーマ文書）に書ける語の一覧。どの位置も、ここに無いキーは誤りになる。
今の実装が読む語を書いたもので、スキーマ文書のトップレベルの語は [schema-document.md](schema-document.md)、項目定義の語は [schema-fields.md](schema-fields.md)。
**原本は [devtrack/](../../devtrack/README.md) の `spec/`**（1 語 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

## State の宣言

states の要素。名前付きの状態 1 つ（id と predicate）か、enum の全部の値からの展開（source と idPrefix）のどちらかで書きます。

- メタスキーマ: builtin-schemas/schema.yaml の definitions.state
- 読む実装: semantic-core/schema/Behavior.kt の StateDeclaration
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: S-28

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `id` |  | 文字列 |  | State の ID。source を書かない宣言では必須です。 |
| `entity` |  | エンティティ型の名前 |  | この状態を持つエンティティ型。source を書かない宣言では必須です。 |
| `label` |  | 文字列 |  | 表示名。 |
| `predicate` |  | 条件（クエリと expect・check） |  | 状態の判定の条件。書かない State は詳細化（refinements）した親で、子のどれかが真のとき真です。 |
| `source` |  | {entity, path, enum} |  | enum の全部の値から State を展開する宣言。書いたときは id・predicate を書きません。 |
| `idPrefix` |  | 文字列 |  | source から展開する State の ID の接頭辞。 |

## enum からの展開

State の宣言の source。状態を持つ項目と、その enum の型を指します。

- メタスキーマ: builtin-schemas/schema.yaml の definitions.state.source
- 読む実装: semantic-core/schema/Behavior.kt の StateDeclaration
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: S-28

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `entity` |  | エンティティ型の名前 |  | 状態を持つエンティティ型。 |
| `path` |  | パスの文字列 |  | 状態の値を持つ項目（エンティティの要素からの相対パス）。 |
| `enum` |  | 型の名前の文字列 |  | 状態の選択肢を持つ型への参照（モジュールID#名前）。 |

## Event の宣言

events の要素。出来事の種類 1 つです。操作の実行ではなく、起きた事実の種類を表します。

- メタスキーマ: builtin-schemas/schema.yaml の definitions.event
- 読む実装: semantic-core/schema/Behavior.kt の EventDef
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: S-31

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `id` | 必須 | 文字列 |  | Event の ID。 |
| `entity` |  | エンティティ型の名前 |  | 出来事が起きる対象のエンティティ型。Lifecycle の対象と同じでなくてかまいません。 |
| `label` |  | 文字列 |  | 表示名。 |
| `description` |  | 文章 |  | 出来事の意味と、起きる条件。 |

## Lifecycle の宣言

lifecycles の要素。1 つのエンティティ型の、State・生成・遷移・終端の宣言です。

- メタスキーマ: builtin-schemas/schema.yaml の definitions.lifecycle
- 読む実装: semantic-core/schema/Behavior.kt の LifecycleDef
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: S-28

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `id` | 必須 | 文字列 |  | Lifecycle の ID。 |
| `entity` | 必須 | エンティティ型の名前 |  | 対象のエンティティ型。 |
| `states` | 必須 | State の ID の配列 |  | この Lifecycle に含まれる State。 |
| `entry` | 必須 | {to, by} の配列 |  | 生成のときの入口。原因ごとに、最初の State を書きます。 |
| `transitions` |  | {from, to, by} の配列 |  | 許される遷移。 |
| `termination` | 必須 | {terminal, openEnded} |  | 終端の方針。 |
| `history` |  | {path} |  | 遷移の記録を書く配列の場所。Lifecycle に従う操作が、移るたびに 1 行足します。 |

## 入口

Lifecycle の entry と、詳細化の entry の要素。

- メタスキーマ: builtin-schemas/schema.yaml の definitions.lifecycle-entry
- 読む実装: semantic-core/schema/Behavior.kt の LifecycleEntry
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: S-28

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `to` | 必須 | State の ID |  | 入る State。 |
| `by` | 必須 | {action} か {event} |  | 入る原因。 |

## 遷移

Lifecycle の transitions と、詳細化の transitions の要素。

- メタスキーマ: builtin-schemas/schema.yaml の definitions.lifecycle-transition
- 読む実装: semantic-core/schema/Behavior.kt の LifecycleTransition
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: S-28

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `from` | 必須 | State の ID |  | 遷移の元の State。 |
| `to` | 必須 | State の ID |  | 遷移の先の State。 |
| `by` | 必須 | {action} か {event} |  | 遷移の原因。 |

## 原因

入口・遷移・出口の by。action か event のどちらか 1 つだけを書きます。

- メタスキーマ: builtin-schemas/schema.yaml の definitions.lifecycle-cause
- 読む実装: semantic-core/schema/Behavior.kt の TransitionCause
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: S-28

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `action` |  | 操作の ID |  | 原因になる操作。 |
| `event` |  | Event の ID |  | 原因になる出来事。 |

## 終端の方針

Lifecycle の termination。

- メタスキーマ: builtin-schemas/schema.yaml の definitions.lifecycle.termination
- 読む実装: semantic-core/schema/Behavior.kt の LifecycleDef
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: S-28

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `terminal` |  | State の ID の配列 |  | 終端の State。 |
| `openEnded` |  | 真偽値 |  | 終端せずに続くことがある Lifecycle であること。 |

## 遷移の記録

Lifecycle の history。

- メタスキーマ: builtin-schemas/schema.yaml の definitions.lifecycle.history
- 読む実装: semantic-core/schema/Behavior.kt の LifecycleHistory
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: S-28

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `path` | 必須 | パスの文字列 |  | 記録を書く配列の場所（エンティティの要素からの相対パス）。行の項目は date・action・from・to と、操作の lifecycle.history に書いた値です。 |

## State の詳細化

refinements の要素。Lifecycle の 1 つの State（親）の中の流れを、別に書きます。

- メタスキーマ: builtin-schemas/schema.yaml の definitions.refinement
- 読む実装: semantic-core/schema/Behavior.kt の RefinementDef
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: S-36

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `state` | 必須 | State の ID |  | 詳細化する State（predicate を書かずに宣言した親）。 |
| `states` | 必須 | State の ID の配列 |  | 子の State。 |
| `entry` | 必須 | {to, by} の配列 |  | 入口。親へ入る原因ごとに、入る子の State を書きます。 |
| `transitions` |  | {from, to, by} の配列 |  | 子の State の間の遷移。 |
| `exits` |  | {by, from} の配列 | どの子からも出られます | 出口。親から出る遷移を、出られる子に狭めます。 |

## 出口

詳細化の exits の要素。

- メタスキーマ: builtin-schemas/schema.yaml の definitions.refinement-exit
- 読む実装: semantic-core/schema/Behavior.kt の RefinementExit
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: S-36

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `by` | 必須 | {action} か {event} |  | 親から出る遷移の原因。 |
| `from` | 必須 | State の ID の配列 |  | その原因で出られる子の State。 |

