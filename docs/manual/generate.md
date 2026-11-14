# 生成（業務モデルから文書へ）

業務モデル（スキーマの操作・State・Lifecycle・エンティティ型）から、設計書のような YAML の文書を作ります。
作った文書は生成物です。手で直さず、業務モデルか生成の定義を直して作り直します。実例は `samples/todos-sysdev` です。

## 業務モデルの定義を指す（定義の索引）

業務モデルの定義は、定義を書いた場所の「定義の出現」として索引に載ります。設計書などの文書は、同じ ID を詳細化の出現として書くか、参照で指します。

| 種類（エンティティ型） | ID | 定義の場所 |
|---|---|---|
| `yde-action` | 操作の ID（`complete-todo`）・型の操作は `エンティティ型.操作名`（`todo-task.finish`） | `actions` の要素・型の `methods` |
| `yde-require` | `操作ID.前提ID`（`complete-todo.no-open-task`。名前を付けた前提だけ） | 操作の `require` の要素 |
| `yde-state` | State の ID（`todo-CLOSED`・詳細化の親 `todo-ACTIVE`） | `states` の要素（列挙型から作ったものは `source` の宣言） |
| `yde-lifecycle` | Lifecycle の ID | `lifecycles` の要素 |
| `yde-entity` | エンティティ型の名前（`todo`） | `entity: {type: …}` を書いた object（定義の宣言を優先） |

```yaml
# 設計書のスキーマ: 業務の操作と同じ ID の詳細化
entity: { type: yde-action, defines: false }
```

業務モデルから消えた操作の設計書が残っていると、「定義出現がありません（浮遊詳細化）」と指摘されます。
`yde-*` を定義（`defines: true`）として宣言するとスキーマの誤りです。

## 業務モデルを展開した形

生成の入力は、業務モデルを展開した 1 つの項です。`generate <ws> --model` で見られます。

- `entities`: エンティティ型と、その側面（どのスキーマの文書の、どこに書かれ、どの項目を持つか）。項目は型を解いた形（型の名前 `type`・基底の種類 `base`・必須・文字数・選択肢など）です。
- `actions`: 操作と型の操作。受け取る値・前提（`id` を付けたもの）・主体のエンティティ型（`subject`）・従う Lifecycle（入口・遷移・断りの文）と、効果（`effects`）です。効果は、do の手・Lifecycle が書く状態の値と履歴の行・保存時に自動で入る値の 3 か所を 1 列にまとめ、書き込む項目の種類（`field`）を添えます。
- `states`・`lifecycles`: State（列挙型の値・詳細化の親子）と、詳細化を展開した Lifecycle です。State は ID・名前・値の組で書きます。

## 生成の定義（`.yde/transforms/`）

```yaml
# .yde/transforms/design/action-specs.yaml
id: action-specs
name: アクション定義書
each: actions                                 # この並びの要素ごとに 1 文書（省くと業務モデル全体から 1 文書）
output: "design/generated/actions/{id}.yaml"  # 作る文書の場所（穴は要素の値）
build:                                        # 文書の中身（変換のひな型）
  id: { $copy: id }
  name: { $copy: label }
  method: { $case: { $copy: id }, $as: camel }   # complete-todo → completeTodo
```

- `build` と `rules` は変換と同じ書き方です（[schema.md](schema.md) の変換のひな型）。いまの場所は要素で、業務モデル全体は `$of: model`、いまの要素は繰り返しの中からも `$of: element` で読めます。
- `$case` は名前の書き方をそろえます（`camel`・`pascal`・`snake`・`kebab`・`constant`）。英語の文法（複数形など）は入れません。
- 条件の比べる値に、別の場所の値を書けます。例: エンティティ型ごとの文書で、その型を主体にする操作だけを並べる。

  ```yaml
  handlers:
    $each: actions
    $of: model
    $build:
      $when: [{ path: subject, equals: { $copy: id, $of: element } }]
      action: { $copy: id }
  ```

- `build` が何も作らなかった要素（`$when` で外したもの）は、文書を作りません。
- 作った文書の先頭には「# 生成物（定義ID）」の見出しが付きます。作り直すたびに、出力の場所（穴を `*` にした場所）にある、同じ定義の生成物で作らなかったものは消します。見出しの無い文書は消しません。
- 定義はゾーンの下のフォルダに分けて置けます。ID はゾーン全体で 1 つです。

## 作る・作り直す

```bash
./gradlew runCli -q --args="generate samples/todos-sysdev"            # 計画だけ（作る・作り直す・消す文書と検証）
./gradlew runCli -q --args="generate samples/todos-sysdev --apply"    # 書く
./gradlew runCli -q --args="generate samples/todos-sysdev --check"    # 最新でなければ失敗（終了コード 1）
```

- 作る文書は、書く前に今までの検証を通します。検証エラーがあれば書きません（`--allow-errors` で書きます）。
- 書き込みは 1 トランザクションです。生成物は git に入れておくと、作り直した後の差分で、何が変わったかを読めます。
- 生成物が最新かをテストで確かめると、業務モデルを直したのに作り直し忘れた、を防げます（`samples/todos-sysdev` は app-core の `TodosSysdevTest`）。
