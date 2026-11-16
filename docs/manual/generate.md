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
| `yde-type` | `スキーマID#型の名前`（`common#一行の文`。型の書き方そのもの） | スキーマの `definitions` の名前付きの型（エンティティ型を持つ object は除く） |
| `yde-field` | `エンティティ型.キー`（`todo.title`。同じ型の同じキーは先に見つけた 1 か所） | エンティティ型を持つ object の `fields` の要素（名前付きの型の定義の中・文書のルート） |

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
  原文の場所を持つ項目と名前付きの型には `declarations` が付きます。各記録の `declared` は、その場所に明記された属性です。共通型と同じ値・`false`・`0`・空文字・空配列も省略しません。
  `schema` は宣言元のモジュール、`document` はワークスペース内の文書、`path` は原文の場所です。組込みの型には `document` がありません。
  `inherited: true` はその項目の型を解決するときに継承元から加わった宣言です。object の子を合成した場合は、合成元の子の宣言も残します。入れ子は `item`・`value`・`fields`・`cases` に保持します。
  `declarations` は現在の有効値を置き換えるものではありません。操作の入力など、原文の場所を保持しない入口には付きません。
  深い入れ子も固定の深さでは省略しません。現在の経路で同じ型に戻る箇所には `recursiveType`（例 `common#node`）を付けます。その箇所の共通の子は名前付きの型 `types`（エンティティ型なら `entities` の側面）を参照し、利用箇所に明記した子があれば `fields`・`item`・`value`・`cases` に残します。したがって `recursiveType` がある値の子は、継承する全項目の一覧ではありません。
  同じ型を使う別の項目は、それぞれ展開します。再帰するエンティティの側面も、最初の再帰先に `recursiveType` を付けて記録します。
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
- `types`: 名前付きの型（スキーマの `definitions`。エンティティ型を持つ object は除く）です。名前（`name`。`スキーマID#型の名前`）と、項目と同じ形の型を解いた値（基底の種類・文字数・形・表示形式・選択肢・参照先・行の項目）を持ちます。
  設計の論理型（`samples/todos-sysdev` の基本設計項目一覧（論理型））は、ここから作ります。
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
# .yde/transforms/design/table-access.yaml
scope: business      # 業務モデルのスコープのスキーマだけから、業務モデルを展開する
each: entities
```

- スコープは名前のある定義で、置き場所（`include`）は中身を指定する書き方の 1 つです。1 つの定義体が入るスコープは 1 つだけです。
- `uses` は使ってよい別のスコープです。スキーマが、`uses` に書いていないスコープのスキーマを使う（型の名前 `スキーマ#型`・参照先のスキーマ・エンティティ型・操作の受け手）と、スキーマの誤りになります。使う向きは一方向で、たどって戻る書き方は誤りです。
- スコープを書かないワークスペースと、`scope` を書かない生成の定義は、今までどおりワークスペースのすべてのスキーマから読みます。
- `generate <ws> --model --scope business` で、スコープから展開した業務モデルを見られます。

## 生成の定義（`.yde/transforms/`）

生成の入力を先行する設計書から作る場合は、`dependsOn: [生成の定義ID]` と `input: 変換のひな型` を書きます。
依存先は同じ計画で先に生成され、`input` と `build` の `$view` はその新しい結果を読みます。指定した定義だけを生成するときも依存先を含み、循環・存在しない依存先は誤りになります。
読める生成物は、明示した依存先とその先の依存先の今回の結果だけです。依存しない定義が先に実行されても、その出力は読めません。保存済みの生成物も入力から外すため、前回の出力で欠けた対象を補いません。手書きの文書は引き続き読めます。
依存先が失敗したときは後続を実行しません。計画に誤りが一つでもあれば、成功した部分も含めて書き込みません。
`input` は段を当てる前に業務モデルを受け取り、`rules` も使えます。結果が生成の入力になります。`stages` に適用済みの段を記録した入力では、その先の段だけを当てます。
例はサンプルの `detail-tables`（補正後の基本設計からテーブルを生成）です。項目の `overrides` は共通型と異なる制限、`builtinType` は組込み型の共通の性質です。

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
# .yde/transforms/design/table-access.yaml
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

## 変更候補を合成する

`$compose` は、導出・追加・変更の候補をまとめ、競合が無いときだけ `records` と採用記録 `decisions` を返す変換の指示です。
例えば、`input` で合成し、`build` で保存する形へ整えます。入力は `$view`・`$each` などのひな型で組み立てられます。
入力用ビューで同じ ID の複数の出現を扱うときは、列にも `schemaId: 読むスキーマ` を指定します。行の対象を選ぶ `schemaIds` と、値を読む出現の指定は別です。

```yaml
id: combine
output: design/composed.yaml
input:
  $compose:
    derived:
      - id: title
        value: {type: string, maxLength: 100}
        source: {id: derived-title}
    additions: []
    changes:
      - target: title
        set: {maxLength: 200}
        priority: individual
        source: {id: correction-title, document: design/corrections.yaml, path: [corrections, 0]}
build:
  records: {$copy: records}
  decisions: {$copy: decisions}
```

| 入力 | 書き方 |
|---|---|
| `derived`・`additions` | `{id, value, source}` の配列。`id` は生成中の対象名、`value` は object |
| 同じ所属内のキーの重複検査 | 各候補に `keySpace: {scope: 所属名, path: [key]}` を付ける。同じ scope の同じ値は追加前・補正後とも誤り |
| `changes` | `{target, set, priority, source}` の配列（`set` の各キーの値を置き換え、マップの値もその属性全体を置き換える） |
| 子の属性を変更する | `set` の代わりに `path: [item, maxLength]` と `value: 100` を書く。中間の入れ物は既存であること |
| `source` | `id` は根拠の記録名で必須。`document`・`path` で原文の場所を添えられる |

三つの配列は必須で、候補が無ければ `[]` と書きます。`id`・`target` は通常の ID 索引や前回の生成物では解決しません。
`priority: rule` は規則の既定値、`individual` は個別指定です。異なる規則の値は、個別指定で採用値を明示すれば解決できます。
同じ優先度に異なる値があれば競合、同じ値ならすべての根拠を残します。異なる個別指定同士は競合です。
対象消失・ID やキーの重複・親全体と子への変更の重なりも、適用順で解決せず誤りにします。

`records` は合成後の `{id, value, source, keySpace?}`、`decisions` は `{target, path, candidates, selected}` の配列です。
各候補には `value`・`priority`・`source` を残し、不採用の規則も追えます。`false`・`0`・空文字・空配列・`null` は値として保持し、キーの欠落と区別します。
`null` は削除の指示にはなりません。原文への経路は、この明示した `source` を使います。

合成の誤りは生成計画の失敗となり、後続と書き込みを止めます。`--allow-errors` でも回避できません。
成功は型・参照・方式の成立条件まで検査した意味ではありません。それらを確認する後段を経てから、確定項目として扱います。
既存の段の補正やビューの補正は、`$compose` を使う変換へ移すまでは従来の処理です。

## 組み立てた型を解決する

生成の `input`・`build` では、`$schemaModel` でスキーマ文書の配列を組み立て、型を解いた業務モデルの項を受け取れます。
合成した候補をスキーマへ対応付ける処理も、変換のひな型で書きます。型の意味は通常のスキーマと共通です。

```yaml
id: resolved-types
output: design/resolved-types.yaml
input:
  $schemaModel:
    - id: common
      kind: library
      definitions:
        title: {type: string, maxLength: 200}
        inherited: {type: title}
        explicit: {type: title, maxLength: 100}
build:
  types: {$copy: types}
```

この例では `common#inherited` の最大文字数は 200、`common#explicit` は 100 です。
`entities`・`types` などの形は通常の業務モデルの項と同じです。初期値の `false`・`0`・空文字・空配列も保持します。

型は渡した文書と組込みの型だけで解決します。外のワークスペースに同名の型があっても補いません。
各文書には空でない文字の `id` が必要です。重複、定義の誤り、型の参照切れ、制約を緩める指定、定義の検査の保留があれば後続と書き込みを止めます。
ここで検査するのはスキーマ定義で、利用者データに対する成立条件の評価ではありません。

宣言の `document` は入力の位置に対応する仮の座標 `.yde/schemas/input/<番号>.yaml` です。
導出・補正の原本へたどるための `source` と `decisions` は別に保持してください。
単独の CLI `transform`、レポート、操作のひな型ではこの指示を使えません。

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
