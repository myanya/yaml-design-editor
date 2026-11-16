# 操作

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/schema-actions-md.md -->

操作（スキーマ文書の `actions` と、型の `methods`）に書ける語の一覧。処理の手（`do` の中身）と前提（`require`）の書き方は [manual/actions.md](../manual/actions.md) にある。
今の実装が読む語を書いたもので、スキーマ文書のトップレベルの語は [schema-document.md](schema-document.md)、項目定義の語は [schema-fields.md](schema-fields.md)。
**原本は [devtrack/](../../devtrack/README.md) の `spec/`**（1 語 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

## 操作（アクション）の定義

スキーマ文書の actions の要素。複数の要素や文書にまたがる操作 1 つです。ここに無いキーは誤りになります。

- メタスキーマ: builtin-schemas/schema.yaml の definitions.action
- 読む実装: semantic-core/schema/Action.kt の ActionDef
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: OP-02・OP-03

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `id` | 必須 | 文字列 |  | 操作の ID。画面・CLI・エージェントが同じ名前で呼びます。1 つのスキーマ文書の中で重ならないこと。 |
| `label` |  | 文字列 |  | 表示名。 |
| `description` |  | 文章 |  | 説明。 |
| `target` |  | {entity, schema, bind} | ワークスペースの操作になります | 受け手（操作の対象）。 |
| `params` |  | 項目定義の配列 |  | 受け取る値。1 つずつ、項目定義と同じ形で書きます。 |
| `input` |  | {initial, submit} |  | 入力のフォームの初期の設定。 |
| `lifecycle` |  | Lifecycle の ID か {id, history} |  | 従う Lifecycle。書くと、状態の前提・状態の値の書き込み・遷移の記録の行を、その Lifecycle の遷移（by にこの操作を書いたもの）から作ります。 |
| `require` |  | 条件（クエリと expect）の配列 |  | 前提の条件。満たさないと操作を実行できません。 |
| `do` |  | 手の配列 |  | 処理の手の並び（set・insert・delete・create・call・each）。lifecycle を書いた操作では、状態の書き込みと記録は Lifecycle が行うので省けます。 |

## 受け手

操作の target。entity と schema は、どちらか一方だけを書きます。

- メタスキーマ: builtin-schemas/schema.yaml の definitions.action.target
- 読む実装: semantic-core/schema/Action.kt の ActionTarget
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: OP-02・OP-03

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `entity` |  | エンティティ型の名前 |  | 受け手のエンティティ型。その型の要素 1 つを受け手にします。 |
| `schema` |  | スキーマ ID |  | 受け手の文書のスキーマ。そのスキーマの文書 1 つを受け手にします。 |
| `bind` |  | 文字列 | self | 手と条件の中で、受け手を指す名前。 |

## 入力のフォームの設定

操作の input。

- メタスキーマ: builtin-schemas/schema.yaml の definitions.action-input
- 読む実装: semantic-core/schema/ActionInput.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: OP-02・OP-03

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `initial` |  | 受け取る値の名前 → ひな型 のマップ |  | 入力の初期の値。受け手の値や今日の日付を、ひな型（$copy・$of）で読みます。 |
| `submit` |  | all か changed | all | 送る値。changed は変えた項目だけを送ります（必須の項目と、明示した項目はいつも送ります）。 |

## 型の操作の定義

object の項目定義の methods の値。その object の要素 1 つを受け手にする操作です。予約の名前 create は新しい要素の中身を決めます。

- メタスキーマ: builtin-schemas/schema.yaml の definitions.method
- 読む実装: semantic-core/schema/Action.kt の MethodDef
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: OP-01

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `label` |  | 文字列 |  | 表示名。 |
| `description` |  | 文章 |  | 説明。 |
| `params` |  | 項目定義の配列 |  | 受け取る値。 |
| `build` |  | 変換のひな型 | 必須の項目の初期値と、受け取った値 | 新しい要素の中身。create にだけ書きます。 |
| `do` |  | 手の配列 |  | 処理の手の並び。create 以外の操作に書きます。受け手の部分木の中だけを変えます。 |

## 配列への追加の決まり

array の項目定義の methods の insert。配列へ要素を足すときの、ID の採番と重複の見分け方です。

- メタスキーマ: builtin-schemas/schema.yaml の definitions.insert-rule
- 読む実装: semantic-core/schema/Action.kt の InsertRule
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: OP-01

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `label` |  | 文字列 |  | 表示名。 |
| `description` |  | 文章 |  | 説明。 |
| `id` |  | {path, prefix, digits} |  | ID の採番。要素の ID の項目が空のとき、接頭辞の中で使われている最大の番号の次を振ります。 |
| `key` |  | 項目のパスか、その配列 |  | 重複を見分ける項目。同じ値の要素があれば足しません。 |

## ID の採番

追加の決まりの id。

- メタスキーマ: builtin-schemas/schema.yaml の definitions.insert-rule.id
- 読む実装: semantic-core/schema/Action.kt の IdNumbering
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: OP-01

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `path` |  | パスの文字列 |  | ID を持つ項目（要素からの相対パス）。 |
| `prefix` |  | 文字列か、変換のひな型 |  | ID の接頭辞（例 SCR-）。要素や、配列を持つ要素の値から作れます。 |
| `digits` |  | 整数 | 3 | 番号の桁数。 |

