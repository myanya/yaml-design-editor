# 生成（業務モデルから文書へ）

業務モデル（スキーマの操作・State・Lifecycle・エンティティ型）から、設計書のような YAML の文書を作ります。
作った文書は生成物です。手で直さず、業務モデルか生成の定義（段を読むなら段の規則・補正も）を直して作り直します。実例は `samples/todos-sysdev` です。

## 業務モデルの定義を指す（定義の索引）

業務モデルの定義は、定義を書いた場所の「定義の出現」として索引に載ります。設計書などの文書は、同じ ID を詳細化の出現として書くか、参照で指します。

| 種類（エンティティ型） | ID | 定義の場所 |
|---|---|---|
| `yde-action` | 操作の ID（`complete-todo`）・型の操作は `エンティティ型.操作名`（`todo-task.finish`） | `actions` の要素・型の `methods` |
| `yde-require` | `操作ID.前提ID`（`complete-todo.no-open-task`。名前を付けた前提だけ） | 操作の `require` の要素 |
| `yde-state` | State の ID（`todo-CLOSED`・詳細化の親 `todo-ACTIVE`） | `states` の要素（列挙型から作ったものは `source` の宣言） |
| `yde-lifecycle` | Lifecycle の ID | `lifecycles` の要素 |
| `yde-entity` | エンティティ型の名前（`todo`） | `entity: {type: …}` を書いた object（定義の宣言を優先） |
| `yde-view` | ビューの ID（ダッシュボード `todo-dashboard`・表 `todo-management` など） | ビューの定義の文書（`.yde/views`）のルート |

```yaml
# 設計書のスキーマ: 業務の操作と同じ ID の詳細化
entity: { type: yde-action, defines: false }
```

業務モデルから消えた操作の設計書が残っていると、「定義出現がありません（浮遊詳細化）」と指摘されます。
`yde-*` を定義（`defines: true`）として宣言するとスキーマの誤りです。

## 業務モデルを展開した形

生成の入力は、業務モデルを展開した 1 つの項です。`generate <ws> --model` で見られます。

- `entities`: エンティティ型と、その側面（どのスキーマの文書の、どこに書かれ、どの項目を持つか）。項目は型を解いた形（型の名前 `type`・基底の種類 `base`・必須・文字数・選択肢など）です。
  参照の項目には参照先のエンティティ型（`target`）、State の値を持つ列挙型の項目には、その State のエンティティ型（`stateOf`）が付きます。
  型には ID の振り方（`numbering`。配列の追加の決まり `methods.insert` か、文書を作る手の `id`）と、入れ子で書かれる型なら外側の型と項目（`owner`。作業項目なら `{entity: todo, field: tasks}`）、
  見出しの項目（`title`。表示解決と同じ順＝`role: title`・title・name・label）が付きます。参照の項目には、参照先の見出しの項目（`targetTitle`）と参照先の型の名前（`targetLabel`）も付きます。
  型には、関係レジストリの宣言の説明（`description`）と、集約（`aggregate`。入れ子の持ち主をたどった、いちばん外側の型。持ち主がいなければ自分）が付きます。
  項目には、項目に書いた説明（無ければ型の説明。`description`）が付き、要素がエンティティの配列には要素の型の名前（`item.entityLabel`）が付きます。
- `actions`: 操作と型の操作。受け取る値・前提（`id` を付けたもの）・主体のエンティティ型（`subject`）・作るエンティティ型（`creates`）・従う Lifecycle（入口・遷移・断りの文）と、効果（`effects`）です。効果は、do の手・Lifecycle が書く状態の値と履歴の行・保存時に自動で入る値の 3 か所を 1 列にまとめ、書き込む項目の種類（`field`）を添えます。
  文書を作る手と要素を足す手には、書く値の項目ごとの基底の種類（`written`）と採番（`numbering`）が付きます。
  受け取る値のうち、別の真偽値の受け取る値で消せるもの（効果の条件が `clear.category が true` で、その項目を消す）には `clearedBy` が付きます。
  操作の `checks` は、その操作が書くと破れうるデータの決まりです（下の `constraints`）。操作が書く項目と決まりが読む項目が重なる（要素の直下のキーで比べる）決まりのうち、
  書いた後の要素が決まりの絞り込み（`where`）に入らないと定義から分かるもの（状態を「中断」に書く操作と「完了した TODO には…」・完了日を書かずに作る要素と「完了日は…」・
  再開予定日を書かない明細の行と「再開予定日は…」）を除きます。重なった項目（`items`）と、決まりが読む値を入れる受け取る値（`params`。あれば、受け取る値が原因で破れうる）、
  決まりの形と重さ（`form`・`level`・`kind`・`multiple`）を持ちます。
- `states`・`lifecycles`: State（列挙型の値・詳細化の親子）と、詳細化を展開した Lifecycle です。State は ID・名前・値の組で書きます。入口と遷移には、操作の名前（`actionLabel`）が付きます。
- `relations`: 関係レジストリの関係です。名前（`id`）・表示名（`label`）・逆向きの表示名（`inverseLabel`）・説明・元と先の型（`source`・`target`）・宣言した多重度（`cardinality`）・
  非循環（`acyclic`）と、その関係の参照を書く項目（`fields`。型・キー・複数か）を持ちます。
- `constraints`: データの決まりです。型の検査・スキーマの検査（`constraints` に直接書いたもの）・関係レジストリの宣言を 1 列にし、名前（`label`＝説明）・文（`message`）・
  重さ（`level`）・形（`form`）・起点のエンティティ型（`entity`）・明細の行の決まりなら場所（`at`。`history[*]`）・読む項目（`reads`。`todo.status` のように型と項目）・
  書かれた形（`rule`。State の名前は値の条件へ置き換えたもの）・書かれた場所（`source`）を持ちます。形は、要素ごとの決まり `element`・入れ子の線や参照を辿る決まり `pattern`・
  書かれた場所の数の決まり `occurrence`・関係レジストリの宣言 `relation`（`kind` は acyclic・outMax・outMin・inMax・inMin・types）です。
  どの決まりをどう確かめるか（設計書の上の確かめ方）は、生成の定義が決めます（`samples/todos-sysdev` のエラー定義書と処理設計書）。
- `views`・`dashboards`: ビュー（ID・名前・種類 grid / matrix / chart・行の型 `rows`・行の条件 `where`・並び `order`・列の見出しと読む項目と、項目の型を解いた `field`・
  計算の列の値の式 `value`。集計は数える要素の型 `counts` と、そこへの線 `match`）と、ダッシュボード（絞り込みの入力と、その型を解いた `field`・
  上部の操作・区画・区画の条件が基準日を使うか `usesToday`）です。条件の State の名前は、値の条件（`{path: status, oneOf: [OPEN, …]}`）へ置き換えて持ちます。
  スコープを書いたときは、そのスコープのビューだけを読みます。
- `reach`（と各ダッシュボードの `reach`）: ダッシュボードからたどれる範囲です。区画のビューの行と数える要素の型から、参照の項目の参照先をたどった
  エンティティ型（`entities`）、その中に入れ子で書かれた型（`nested`）、上部の操作と、たどれる型を受け手にする操作（`operations`）。
  業務の流れ（`flow`）の図は、いまは数えません。ダッシュボードからたどれるものを画面にする生成は、ここを読みます（`samples/todos-sysdev` の画面系）。

## スコープ（業務モデルとして読む範囲）

業務モデルと設計書を同じワークスペースに置くと、設計書独自のエンティティ型（アクター定義書のアクターなど）を、生成が業務モデルと取り違えます（業務の型と同じ決まりで、テーブルや Controller の設計書を作ってしまう）。
`.yde/workspace.yaml` にスコープを定義し、生成の定義に読むスコープを書きます。

```yaml
# .yde/workspace.yaml
scopes:
  - id: business
    name: 業務モデル
    include: [schemas/business/**, views/business/**]    # 入る定義体（.yde から見た場所の glob）
  - id: design
    name: 設計
    include: [schemas/design/**, views/design/**, transforms/design/**]
    uses: [business]                                     # 設計は業務モデルを使ってよい（逆は誤り）
```

```yaml
# .yde/transforms/design/table-designs.yaml
scope: business      # 業務モデルのスコープのスキーマだけから、業務モデルを展開する
each: entities
```

- スコープは名前のある定義で、置き場所（`include`）は中身を指定する書き方の 1 つです。1 つの定義体が入るスコープは 1 つだけです。
- `uses` は使ってよい別のスコープです。スキーマが、`uses` に書いていないスコープのスキーマを使う（型の名前 `スキーマ#型`・参照先のスキーマ・エンティティ型・操作の受け手）と、スキーマの誤りになります。使う向きは一方向で、たどって戻る書き方は誤りです。
- スコープを書かないワークスペースと、`scope` を書かない生成の定義は、今までどおりワークスペースのすべてのスキーマから読みます。
- `generate <ws> --model --scope business` で、スコープから展開した業務モデルを見られます。

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

- 同じ値ごとにまとめるのは `$group`、キーを計算で決めるマップは `$entries` です（[schema.md](schema.md) の変換のひな型）。例: 操作の効果のうち、同じテーブルへの値の書き込みを 1 つの update にまとめる・CRUD 表の行（列が操作の ID）を作る。

  ```yaml
  methods:
    $group: { $apply: methods }     # 操作ごとのメソッドの並び（規則で組み立てる）
    $by: { $copy: name }            # メソッド名が同じものを 1 つに
    $build:
      name: { $copy: key }
      set: { $each: items, $build: { $copy: set } }
      operations: { $group: items, $by: { $copy: operation }, $build: { $copy: key } }
  ```

- `build` が何も作らなかった要素（`$when` で外したもの）は、文書を作りません。
- 作った文書の先頭には「# 生成物（定義ID）」の見出しが付きます。作り直すたびに、出力の場所（穴を `*` にした場所）にある、同じ定義の生成物で作らなかったものは消します。見出しの無い文書は消しません。
- 定義はゾーンの下のフォルダに分けて置けます。ID はゾーン全体で 1 つです。

## 段（基本設計・詳細設計）と、規則・補正

設計書を、業務モデルの項から直接ではなく、段の項から作れます（段階的詳細化）。段の項は、前の段の項に、その段の規則と補正を当てたものです。
業務モデルに無いが、システムとして当たり前に要るもの（更新者・テナント分離・名前の決まりなど）を、段の規則として外付けで差し込みます。

```yaml
# .yde/workspace.yaml
stages:                      # 並びの順に当てる。最初の段の入力は業務モデルの項
  - id: basic
    name: 基本設計
  - id: detail
    name: 詳細設計
```

```yaml
# .yde/transforms/design/table-designs.yaml
scope: business
stage: detail                # 詳細設計の段の項を読む（基本設計の規則・補正も当たっている）
each: entities
```

規則と補正は `.yde/stages/` に置きます。

```yaml
# .yde/stages/design/audit-updated-by.yaml
id: audit-updated-by
name: 更新者を記録する
stage: basic
kind: rule                                   # rule＝全体の方針・patch＝個別の例外
changes:
  - each: "entities[*].aspects[*].fields"    # 並びの要素ごとに（[*] で入れ子の並びにも広がる）
    where: [{ path: auto, equals: updated }] # 選ぶ条件（変換の $when と同じ書き方）
    after:                                   # 選んだ要素の後ろへ足す
      - { key: updated_by, label: 更新者, type: 利用者ID, base: string, required: true, maxLength: 50 }
  - each: "actions[*].effects"
    where: [{ path: auto, equals: updated }]
    after:
      - { op: set, schema: { $copy: schema }, entity: { $copy: entity }, path: updated_by, value: { $$copy: actor, $$of: context } }
```

- 変更の書き方は、選ぶ（`each`・`where`）と、置き換える（`set: {場所: 値}`）・中へ足す（`add: {並びの場所: 値}`）・後ろへ足す（`after`）です。入れ子の `changes` と、選んだ要素の名前 `bind` も書けます。
- 値は変換のひな型です。いまの場所は選んだ要素で、当てる前の項は `$of: model`、外側の変更で選んだ要素は `$of: <bind>` で読めます。出力のキーを `$` で始めるときは `$$` と書きます。
- 段の中では、規則を先に、補正を後に当てます（どちらも置き場の名前の順）。補正は規則の結果も直せます。
- 補正は、当たる要素が 1 つも無い変更を誤りにします（業務モデルの ID や名前が変わったことに気づくため）。規則は誤りにしません。
- 足した要素には出どころ `origin`（段・規則か補正の ID と名前・種類）が付き、置き換えた場所は要素の `origins` に残ります。設計書はこれを写して、業務モデルに無い項目がなぜあるかを示せます。段の項の頭の `stages` は、当てた段と規則・補正の一覧です。
- 直すところは 3 つです。業務の事実は業務モデル、全体の方針は段の規則、個別の例外は段の補正に書きます。生成物は直しません。
- `generate <ws> --model --scope business --stage basic` で、段の項を見られます。

## 作る・作り直す

```bash
./gradlew runCli -q --args="generate samples/todos-sysdev"            # 計画だけ（作る・作り直す・消す文書と検証）
./gradlew runCli -q --args="generate samples/todos-sysdev --apply"    # 書く
./gradlew runCli -q --args="generate samples/todos-sysdev --check"    # 最新でなければ失敗（終了コード 1）
```

- 作る文書は、書く前に今までの検証を通します。検証エラーがあれば書きません（`--allow-errors` で書きます）。
- 書き込みは 1 トランザクションです。生成物は git に入れておくと、作り直した後の差分で、何が変わったかを読めます。
- 生成物が最新かをテストで確かめると、業務モデルを直したのに作り直し忘れた、を防げます（`samples/todos-sysdev` は app-core の `TodosSysdevTest`）。

## 生成の系譜（どの概念から作るか）

```bash
./gradlew runCli -q --args="generate samples/todos-sysdev --lineage"
```

生成の定義ごとに、業務モデルの項のどこを読むかを出します。定義を当てずに読むので、業務モデルの中身に関係なく分かります。

- `reads`: 読む場所のパターン（`actions[*].effects[*].field.key`）。`$copy`・`$format` の穴・`$each` の源・条件・値の式・規則（同じ名前の規則はすべて）から集めます。
  `$group` の入力が場所なら、まとまりの `items[n]` はその要素として数えます。計算した値の中（まとまりのキーなど）の読み取りは数えません。
- `concepts`: 読む場所の見出し（頭のキーと、その下で中を読む入れ物。`actions.effects`・`entities.aspects`）。設計書がどの概念から作られるかの一覧になります。
