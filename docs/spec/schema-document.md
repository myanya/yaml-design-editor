# スキーマ文書

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/schema-document-md.md -->

スキーマ文書（`.yde/schemas/` の YAML）のトップレベルに書ける語（キー）と、`kind` に書ける種類の一覧。今の実装が読む語を書いたもので、使い方の説明は [manual/schema.md](../manual/schema.md) にある。
項目 1 つの定義（`root`・`definitions` の中身）に書ける語は [schema-fields.md](schema-fields.md)。
**原本は [devtrack/](../../devtrack/README.md) の `spec/`**（1 語 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

スキーマ文書 1 つのトップレベル。スキーマの置き場（.yde/schemas/。従来の配置は _schemas/）の YAML 1 ファイルが 1 つのスキーマ文書で、種類（kind）によって書ける語が変わります。

- メタスキーマ: builtin-schemas/schema.yaml の root（kind: relations の文書は builtin-schemas/relations.yaml の root）
- 読む実装: workspace-core/workspace/SchemaDocuments.kt の parseRaw
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: S-01・S-12・S-15

## スキーマの種類

`kind` に書ける種類。種類によって、トップレベルに書ける語が変わる。

| kind | 適用先 | 意味 |
|---|---|---|
| `behavior` | なし（文書には当たらない） | 振る舞いの宣言。名前付きの State・Event の種類・Lifecycle・State の詳細化を書きます。 |
| `document` | match に合う文書 | 文書に当てるスキーマ。kind を省くとこの種類です。root（文書のルートの定義）を持ちます。 |
| `library` | なし（文書には当たらない） | 共有する定義の置き場。型と構造の定義（definitions）と、検査の定義・操作を置き、ほかのスキーマから モジュールID#名前 で使います。root は書けません。 |
| `relations` | なし（文書には当たらない） | 関係レジストリ。関係の名前・向き・多重度と、エンティティ型の表示名を宣言します。 |

## 語

「書ける種類」は、その語を書けるスキーマ文書の種類。「すべて」は種類を限らない。

### 文書と適用先

| 語 | 書ける種類 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `derive` | `document` | 派生の宣言の object |  | 導出元の文書（例 表の定義書）から、検証用のスキーマを読み込みのときに作る宣言。適用先は derive の中の match で決まり、root は省けます。 |
| `description` | すべて | 文章 |  | スキーマの説明。 |
| `filePattern` | `document` | 値の式（{パス} を含む文字列か、式の object） |  | このスキーマの文書を新しく作るときのファイルの場所（ワークスペース相対）。穴は元の要素の値で埋めます（{id} は ID）。 |
| `id` | すべて | ID の文字列 | ファイル名（拡張子を除く） | スキーマの ID。ほかの定義から、この ID でスキーマを指します（参照の scope.schemaIds・型の モジュールID#名前 など）。ワークスペースの中で重ならないこと。 |
| `kind` | `document`・`library`・`behavior` | document・library・relations・behavior のどれか | document | スキーマの種類。 |
| `kind` | `relations` | relations |  | 関係レジストリでは必ず書きます。 |
| `match` | `document` | glob の文字列 | すべての YAML（**/*.{yaml,yml}） | このスキーマを当てる文書（ワークスペース相対の glob）。複数のスキーマが合うときは、先に読んだスキーマが勝ちます。derive と一緒には書けません。 |
| `name` | すべて | 文字列 | ID を表示します | スキーマの表示名。 |
| `scaffold` | `document` | 変換のひな型 |  | このスキーマの文書を元の要素から作るときに、必須の項目の初期値の上へ重ねる中身。derive を書いたスキーマには書けません。 |

### 形

| 語 | 書ける種類 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `definitions` | `document`・`library`・`behavior` | 名前 → 項目定義 のマップ |  | 名前付きの定義。構造の定義（$ref で使う）と、型の定義（項目の type に名前を書いて使う）を置きます。別のスキーマからは モジュールID#名前 で使います。 |
| `phases` | `document` | フェーズの名前の配列か、{key, order} | フェーズを使いません | 工程（フェーズ）の宣言。項目の requiredFrom と検査の activeFrom が、この並びで「このフェーズ以降」を決めます。文書の今のフェーズは、ルートの直下の項目に書きます。その項目のキーは {key, order} の key で決め、配列だけを書いたときと key を省いたときは phase です。order は必須です。 |
| `root` | `document` | 項目定義 |  | 文書のルートの定義。document では必須です（derive を書いたスキーマは省けます）。ほかの種類には書けません。 |

### 識別・参照・関係

| 語 | 書ける種類 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `enforcement` | `relations` | {undeclaredRelation, definingUniqueness} |  | ワークスペース単位で検査を強める切り替え。宣言していない関係の名前の扱いと、定義の出現が 1 つであることの扱いを決めます。 |
| `entities` | `relations` | エンティティ型の宣言の配列 |  | エンティティ型の一覧。1 件は {type, label, description, aspects} の形で、型の表示名と、エンティティページで側面を並べる順を宣言します。 |
| `entity` | `document` | {type, idPattern, idType, defines, aspect, owns} か、その配列 |  | このスキーマの文書に現れる ID が、どのエンティティ型かの宣言。ID の形（idPattern の glob か、idType の型の名前）で型を見分けます。object の項目定義に書いた entity の方が優先します。 |
| `relations` | `relations` | 関係の宣言の配列 |  | 関係の一覧。関係レジストリでは必須です。1 件は {name, label, inverseLabel, source, target, cardinality, acyclic, level, description} の形です。 |

### 検査

| 語 | 書ける種類 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `consistency` | `document`・`library`・`behavior` | 規則の配列 |  | ID を持つ要素の値が、導出した期待の値と一致することの検査。組込みの語彙で、検証の前に基本の検査へ展開します。 |
| `constraints` | `document`・`library`・`behavior` | 検査の配列 |  | 検査を 1 件ずつ書きます。書き方は 2 通りで、match（クエリ）と expect・count・check のどれかを直接書くか、use（定義の名前）と with（渡す値）で定義を使います。 |
| `coverage` | `document`・`library`・`behavior` | 規則の配列 |  | 詳細の文書があること（ID が、指定した場所にも書かれていること）の要求。組込みの語彙で、検証の前に基本の検査へ展開します。 |
| `flow` | `document`・`library`・`behavior` | 規則の配列 |  | 宣言した遷移の集まりで、到達できない・行き止まり・宣言の外の遷移を見つける検査。組込みの語彙で、検証の前に基本の検査へ展開します。 |
| `functions` | `document`・`library`・`behavior` | 検査の定義の配列 |  | 名前と受け取る値を持つ検査の定義。1 件は {id, description, params, checks} の形で、constraints の use から名前で使います。どのスキーマに書いてもワークスペース全体で使えます。 |
| `matrix` | `document`・`library`・`behavior` | 規則の配列 |  | 行と列のすべての組み合わせに、証人の要素があることの検査（網羅マトリクス）。組込みの語彙で、検証の前に基本の検査へ展開します。 |
| `vocabulary` | `document`・`library`・`behavior` | 語彙の定義の配列 |  | 検査を短く書くための書き方（語彙）の定義。どこに書けるか（in）と、書かれた内容からどの定義をどの値で使うか（uses）を宣言します。 |

### 操作と振る舞い

| 語 | 書ける種類 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `actions` | `document`・`library`・`behavior` | 操作の定義の配列 |  | 複数の要素や文書にまたがる操作。1 件は {id, label, description, target, params, input, lifecycle, require, do} の形です。同じ ID は書けません。関係レジストリには書けません。 |
| `events` | `behavior` | Event の宣言の配列 |  | 出来事の種類。1 件は {id, entity, label, description} の形です。 |
| `lifecycles` | `behavior` | Lifecycle の宣言の配列 |  | ライフサイクル。1 件は {id, entity, states, entry, transitions, termination, history} の形で、対象の State・生成・遷移・終端を宣言します。 |
| `refinements` | `behavior` | 詳細化の宣言の配列 |  | Lifecycle の 1 つの State（親）の中の流れ。1 件は {state, states, entry, transitions, exits} の形です。 |
| `states` | `behavior` | State の宣言の配列 |  | 名前付きの状態。1 件は {id, entity, label, predicate} か、enum の全部の値から展開する {source: {entity, path, enum}, idPrefix} の形です。 |

