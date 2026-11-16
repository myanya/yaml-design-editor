# 仕様の例

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/examples-md.md -->

仕様の語と決まりを示す例。1 つの例は小さなワークスペース（定義と文書）で、開いて検証したときの結果を添えている。
結果は repo-tests の `SpecTest` が実際に開いて確かめているので、ここに書いてあるとおりに動く。
**原本は [devtrack/](../../devtrack/README.md) の `spec/examples/`**（1 例 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

## create は文書を作り、id の決まりで採番する（同じ接頭辞の最大の番号の次）

- ID: EX-action-create-numbering
- 示す仕様: KW-action.do・KW-schema.filePattern
- 結果: 通る（誤りも警告も無い）

操作 `add`、渡す値: title＝実装

適用のあとの `tasks/TASK-2.yaml`

```yaml
id: TASK-2
title: 実装
status: todo
```

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
filePattern: "tasks/{id}.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: note, type: string }
actions:
  - id: add
    params:
      - { key: title, type: string, required: true }
    do:
      - create: { schema: task, id: { prefix: "TASK-", digits: 1 }, params: { title: $title, status: todo } }
```

`tasks/TASK-1.yaml`

```yaml
id: TASK-1
title: 設計
status: doing
```

## delete は値を消す

- ID: EX-action-delete
- 示す仕様: KW-action.do
- 結果: 通る（誤りも警告も無い）

操作 `clear-note`（受け手 TASK-1）

適用のあとの `tasks/TASK-1.yaml`

```yaml
id: TASK-1
title: 設計
status: doing
```

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
filePattern: "tasks/{id}.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: note, type: string }
actions:
  - id: clear-note
    target: { entity: task }
    do:
      - delete: { path: note }
```

`tasks/TASK-1.yaml`

```yaml
id: TASK-1
title: 設計
status: doing
note: あとで見る
```

## insert は配列へ足し、配列の追加の決まり（methods.insert）で採番する。同じ鍵の要素があれば足さない

- ID: EX-action-insert-numbering
- 示す仕様: CTX-insert-rule・CTX-id-numbering・KW-field.methods
- 結果: 通る（誤りも警告も無い）

操作 `add-step`（受け手 TASK-1）、渡す値: name＝下書き

適用のあとの `tasks/TASK-1.yaml`

```yaml
id: TASK-1
title: 設計
status: doing
steps:
  - { id: S-01, name: 下調べ }
  - { id: S-02, name: 下書き }
```

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
filePattern: "tasks/{id}.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: note, type: string }
    - key: steps
      type: array
      methods:
        insert:
          id: { path: id, prefix: "S-", digits: 2 }
          key: [name]
      item:
        type: object
        fields:
          - { key: id, type: id }
          - { key: name, type: string }
actions:
  - id: add-step
    target: { entity: task }
    params:
      - { key: name, type: string, required: true }
    do:
      - insert: { path: steps, value: { name: $name } }
```

`tasks/TASK-1.yaml`

```yaml
id: TASK-1
title: 設計
status: doing
steps:
  - { id: S-01, name: 下調べ }
```

## 前提（require）を満たさないと、操作は断られる

- ID: EX-action-require-fails
- 示す仕様: KW-action.require
- 結果: 通る（誤りも警告も無い）

操作 `finish`（受け手 TASK-1）

断られる（文面に「TASK-1 は完了しています」を含む）。

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
filePattern: "tasks/{id}.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: note, type: string }
actions:
  - id: finish
    label: 完了にする
    target: { entity: task }
    params:
      - { key: note, type: string }
    require:
      - id: not-done
        match: [ { node: { entity: task, bind: t } } ]
        where: [ { of: t, path: status, notEquals: done } ]
        expect: exists
        message: "{id} は完了しています"
    do:
      - set: { path: status, value: done }
      - when: { path: note, exists: true }
        set: { path: note, value: { $format: "完了: {note}" } }
```

`tasks/TASK-1.yaml`

```yaml
id: TASK-1
title: 設計
status: done
```

## 必須の受け取る値を渡さないと、操作は断られる

- ID: EX-action-required-param
- 示す仕様: KW-action.params・KW-field.required
- 結果: 通る（誤りも警告も無い）

操作 `rename`（受け手 TASK-1）

断られる（文面に「title」を含む）。

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
filePattern: "tasks/{id}.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: note, type: string }
actions:
  - id: rename
    target: { entity: task }
    params:
      - { key: title, type: string, required: true }
    do:
      - set: { path: title, value: $title }
```

`tasks/TASK-1.yaml`

```yaml
id: TASK-1
title: 設計
status: doing
```

## 操作は受け手の要素に手を当てる。set は値を入れ、$format は受け取った値で埋める

- ID: EX-action-set
- 示す仕様: CTX-action・KW-action.do・KW-action.target・KW-action.params
- 結果: 通る（誤りも警告も無い）

操作 `finish`（受け手 TASK-1）、渡す値: note＝レビュー済み

適用のあとの `tasks/TASK-1.yaml`

```yaml
id: TASK-1
title: 設計
status: done
note: "完了: レビュー済み"
```

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
filePattern: "tasks/{id}.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: note, type: string }
actions:
  - id: finish
    label: 完了にする
    target: { entity: task }
    params:
      - { key: note, type: string }
    require:
      - id: not-done
        match: [ { node: { entity: task, bind: t } } ]
        where: [ { of: t, path: status, notEquals: done } ]
        expect: exists
        message: "{id} は完了しています"
    do:
      - set: { path: status, value: done }
      - when: { path: note, exists: true }
        set: { path: note, value: { $format: "完了: {note}" } }
```

`tasks/TASK-1.yaml`

```yaml
id: TASK-1
title: 設計
status: doing
```

## 手の when は、条件に合うときだけ当てる（受け取った値を読む）

- ID: EX-action-when-skips
- 示す仕様: KW-action.do
- 結果: 通る（誤りも警告も無い）

操作 `finish`（受け手 TASK-1）

適用のあとの `tasks/TASK-1.yaml`

```yaml
id: TASK-1
title: 設計
status: done
```

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
filePattern: "tasks/{id}.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: note, type: string }
actions:
  - id: finish
    label: 完了にする
    target: { entity: task }
    params:
      - { key: note, type: string }
    require:
      - id: not-done
        match: [ { node: { entity: task, bind: t } } ]
        where: [ { of: t, path: status, notEquals: done } ]
        expect: exists
        message: "{id} は完了しています"
    do:
      - set: { path: status, value: done }
      - when: { path: note, exists: true }
        set: { path: note, value: { $format: "完了: {note}" } }
```

`tasks/TASK-1.yaml`

```yaml
id: TASK-1
title: 設計
status: doing
```

## 条件の oneOf と、順序の比較（項目の型で比べる）

- ID: EX-condition-oneof-order
- 示す仕様: KW-condition.oneOf・KW-condition.gte・KW-condition.exists
- 結果: 通る（誤りも警告も無い）
- ビュー `example` の行（並びの順）:
  - `TASK-2`: 期限＝2026-11-20

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: date }
    - { key: parent, type: ref, relation: child-of }
    - key: comments
      type: array
      item:
        type: object
        fields:
          - { key: date, type: date }
          - { key: text, type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: 設計
status: done
points: 3
due: 2026-10-05
comments:
  - { date: 2026-10-01, text: 着手 }
  - { date: 2026-10-04, text: 完了 }
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 5
due: 2026-11-20
parent: TASK-1
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
points: 2
parent: TASK-1
```

`.yde/views/example.yaml`

```yaml
id: example
name: 例
rows:
  match:
    - node: { entity: task, bind: t }
  where:
    - { of: t, path: status, oneOf: [doing, done] }
    - { of: t, path: due, gte: "2026-11-01" }
columns:
  - { label: 期限, path: due }
```

## 型の検査（constraints）は、要素ごとに値の比較を確かめる

- ID: EX-constraint-check
- 示す仕様: KW-field.constraints・CTX-comparison・CTX-expr
- 結果: 検証のエラーになる（文面に「金額が単価×数量と違います」を含む）

`.yde/schemas/task.yaml`

```yaml
id: order
match: "orders/*.yaml"
root:
  type: object
  constraints:
    - id: amount-matches
      check:
        - eq: [ { path: amount }, { multiply: [ { path: price }, { path: qty } ] } ]
      level: error
      message: "金額が単価×数量と違います"
  fields:
    - { key: id, type: id, required: true }
    - { key: price, type: int }
    - { key: qty, type: int }
    - { key: amount, type: int }
```

`orders/a.yaml`

```yaml
id: ORD-1
price: 100
qty: 3
amount: 200
```

## ダッシュボードに、ダッシュボードの語でないキーは書けない

- ID: EX-dashboard-unknown-key
- 示す仕様: CTX-dashboard
- 結果: ビューの定義の誤りになる（文面に「rows」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: date }
    - { key: parent, type: ref, relation: child-of }
    - key: comments
      type: array
      item:
        type: object
        fields:
          - { key: date, type: date }
          - { key: text, type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: 設計
status: done
points: 3
due: 2026-10-05
comments:
  - { date: 2026-10-01, text: 着手 }
  - { date: 2026-10-04, text: 完了 }
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 5
due: 2026-11-20
parent: TASK-1
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
points: 2
parent: TASK-1
```

`.yde/views/example.yaml`

```yaml
id: example
name: 例
kind: dashboard
sections: []
rows: { idPattern: "TASK-*" }
```

## パスの [*] は配列を返し、集計は配列を受け取る

- ID: EX-expr-aggregate-array
- 示す仕様: KW-expr.max・KW-expr.count・KW-expr.path・KW-expr.type
- 結果: 通る（誤りも警告も無い）
- ビュー `example` の行（並びの順）:
  - `TASK-1`: 最後のコメント＝2026-10-04、コメントの数＝2
  - `TASK-2`
  - `TASK-3`

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: date }
    - { key: parent, type: ref, relation: child-of }
    - key: comments
      type: array
      item:
        type: object
        fields:
          - { key: date, type: date }
          - { key: text, type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: 設計
status: done
points: 3
due: 2026-10-05
comments:
  - { date: 2026-10-01, text: 着手 }
  - { date: 2026-10-04, text: 完了 }
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 5
due: 2026-11-20
parent: TASK-1
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
points: 2
parent: TASK-1
```

`.yde/views/example.yaml`

```yaml
id: example
name: 例
rows: { idPattern: "TASK-*" }
columns:
  - label: 最後のコメント
    value: { max: { path: "comments[*].date", type: date } }
  - label: コメントの数
    value: { count: { path: "comments[*].date" } }
```

## all はクエリの解から配列を作り、count で件数になる

- ID: EX-expr-all-count
- 示す仕様: KW-expr.all・KW-expr.count・CTX-query-edge
- 結果: 通る（誤りも警告も無い）
- ビュー `example` の行（並びの順）:
  - `TASK-1`: 子の数＝2
  - `TASK-2`: 子の数＝0
  - `TASK-3`: 子の数＝0

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: date }
    - { key: parent, type: ref, relation: child-of }
    - key: comments
      type: array
      item:
        type: object
        fields:
          - { key: date, type: date }
          - { key: text, type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: 設計
status: done
points: 3
due: 2026-10-05
comments:
  - { date: 2026-10-01, text: 着手 }
  - { date: 2026-10-04, text: 完了 }
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 5
due: 2026-11-20
parent: TASK-1
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
points: 2
parent: TASK-1
```

`.yde/views/example.yaml`

```yaml
id: example
name: 例
rows: { idPattern: "TASK-*" }
columns:
  - label: 子の数
    value:
      count:
        all:
          of: c
          match:
            - node: { entity: task, bind: p }
            - edge: { relation: child-of, dir: in }
            - node: { entity: task, bind: c }
```

## 四則演算は数を計算し、結果の表記をそろえる

- ID: EX-expr-arithmetic
- 示す仕様: KW-expr.multiply・KW-expr.add・KW-expr.divide
- 結果: 通る（誤りも警告も無い）
- ビュー `example` の行（並びの順）:
  - `TASK-1`: 倍＝30、半分＝1.5
  - `TASK-2`: 倍＝50、半分＝2.5
  - `TASK-3`: 倍＝20、半分＝1

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: date }
    - { key: parent, type: ref, relation: child-of }
    - key: comments
      type: array
      item:
        type: object
        fields:
          - { key: date, type: date }
          - { key: text, type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: 設計
status: done
points: 3
due: 2026-10-05
comments:
  - { date: 2026-10-01, text: 着手 }
  - { date: 2026-10-04, text: 完了 }
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 5
due: 2026-11-20
parent: TASK-1
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
points: 2
parent: TASK-1
```

`.yde/views/example.yaml`

```yaml
id: example
name: 例
rows: { idPattern: "TASK-*" }
columns:
  - label: 倍
    value: { multiply: [ { path: points }, 10 ] }
  - label: 半分
    value: { divide: [ { path: points }, 2 ] }
```

## 日付の部分を取り出す

- ID: EX-expr-date-parts
- 示す仕様: KW-expr.yearMonth・KW-expr.year・KW-expr.weekStart
- 結果: 通る（誤りも警告も無い）
- ビュー `example` の行（並びの順）:
  - `TASK-1`: 年月＝2026-10、年＝2026、週の初め＝2026-10-05
  - `TASK-2`: 年月＝2026-11、年＝2026、週の初め＝2026-11-16
  - `TASK-3`: 年月＝''、年＝''

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: date }
    - { key: parent, type: ref, relation: child-of }
    - key: comments
      type: array
      item:
        type: object
        fields:
          - { key: date, type: date }
          - { key: text, type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: 設計
status: done
points: 3
due: 2026-10-05
comments:
  - { date: 2026-10-01, text: 着手 }
  - { date: 2026-10-04, text: 完了 }
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 5
due: 2026-11-20
parent: TASK-1
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
points: 2
parent: TASK-1
```

`.yde/views/example.yaml`

```yaml
id: example
name: 例
rows: { idPattern: "TASK-*" }
columns:
  - label: 年月
    value: { yearMonth: { path: due } }
  - label: 年
    value: { year: { path: due } }
  - label: 週の初め
    value: { weekStart: { path: due } }
```

## format は、埋まらない穴があると「得られない」になり、first で次の式に進む。式の中の文字列は、{パス} を含んでいても決まった値

- ID: EX-expr-format-missing
- 示す仕様: KW-expr.format・KW-expr.first
- 結果: 通る（誤りも警告も無い）
- ビュー `example` の行（並びの順）:
  - `TASK-1`: 期限＝2026-10-05 まで、式の中の文字列＝{due} まで
  - `TASK-2`: 期限＝2026-11-20 まで
  - `TASK-3`: 期限＝期限なし、式の中の文字列＝{due} まで

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: date }
    - { key: parent, type: ref, relation: child-of }
    - key: comments
      type: array
      item:
        type: object
        fields:
          - { key: date, type: date }
          - { key: text, type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: 設計
status: done
points: 3
due: 2026-10-05
comments:
  - { date: 2026-10-01, text: 着手 }
  - { date: 2026-10-04, text: 完了 }
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 5
due: 2026-11-20
parent: TASK-1
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
points: 2
parent: TASK-1
```

`.yde/views/example.yaml`

```yaml
id: example
name: 例
rows: { idPattern: "TASK-*" }
columns:
  - label: 期限
    value: { first: [ { format: "{due} まで" }, 期限なし ] }
  - label: 式の中の文字列
    value: { first: [ "{due} まで", 期限なし ] }
```

## {パス} を含む文字列は、format の短い書き方

- ID: EX-expr-format-short
- 示す仕様: KW-expr.format・KW-view-column.value
- 結果: 通る（誤りも警告も無い）
- ビュー `example` の行（並びの順）:
  - `TASK-1`: 要約＝設計（done）
  - `TASK-2`: 要約＝実装（doing）
  - `TASK-3`: 要約＝試験（todo）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: date }
    - { key: parent, type: ref, relation: child-of }
    - key: comments
      type: array
      item:
        type: object
        fields:
          - { key: date, type: date }
          - { key: text, type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: 設計
status: done
points: 3
due: 2026-10-05
comments:
  - { date: 2026-10-01, text: 着手 }
  - { date: 2026-10-04, text: 完了 }
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 5
due: 2026-11-20
parent: TASK-1
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
points: 2
parent: TASK-1
```

`.yde/views/example.yaml`

```yaml
id: example
name: 例
rows: { idPattern: "TASK-*" }
columns:
  - { label: 要約, value: "{title}（{status}）" }
```

## 辞書での置き換えと、文字の一部の取り出し

- ID: EX-expr-map-extract
- 示す仕様: KW-expr.map・KW-expr.using・KW-expr.extract
- 結果: 通る（誤りも警告も無い）
- ビュー `example` の行（並びの順）:
  - `TASK-1`: 状態の名前＝完了、番号＝1
  - `TASK-2`: 状態の名前＝doing、番号＝2
  - `TASK-3`: 状態の名前＝未着手、番号＝3

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: date }
    - { key: parent, type: ref, relation: child-of }
    - key: comments
      type: array
      item:
        type: object
        fields:
          - { key: date, type: date }
          - { key: text, type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: 設計
status: done
points: 3
due: 2026-10-05
comments:
  - { date: 2026-10-01, text: 着手 }
  - { date: 2026-10-04, text: 完了 }
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 5
due: 2026-11-20
parent: TASK-1
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
points: 2
parent: TASK-1
```

`.yde/views/example.yaml`

```yaml
id: example
name: 例
rows: { idPattern: "TASK-*" }
columns:
  - label: 状態の名前
    value: { map: { path: status }, using: { todo: 未着手, done: 完了 } }
  - label: 番号
    value: { extract: [ { path: id }, "[0-9]+$" ] }
```

## refCount は、出ていく・入ってくる参照の本数を数える

- ID: EX-expr-ref-count
- 示す仕様: KW-expr.refCount
- 結果: 通る（誤りも警告も無い）
- ビュー `example` の行（並びの順）:
  - `TASK-1`: 入る参照＝2、出る参照＝0
  - `TASK-2`: 入る参照＝0、出る参照＝1
  - `TASK-3`: 入る参照＝0、出る参照＝1

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: date }
    - { key: parent, type: ref, relation: child-of }
    - key: comments
      type: array
      item:
        type: object
        fields:
          - { key: date, type: date }
          - { key: text, type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: 設計
status: done
points: 3
due: 2026-10-05
comments:
  - { date: 2026-10-01, text: 着手 }
  - { date: 2026-10-04, text: 完了 }
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 5
due: 2026-11-20
parent: TASK-1
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
points: 2
parent: TASK-1
```

`.yde/views/example.yaml`

```yaml
id: example
name: 例
rows: { idPattern: "TASK-*" }
columns:
  - label: 入る参照
    value: { refCount: { dir: in, relation: child-of } }
  - label: 出る参照
    value: { refCount: { dir: out } }
```

## date の項目は、形に合っていても存在しない日付はエラーになる

- ID: EX-field-date-invalid
- 示す仕様: CTX-field
- 結果: 検証のエラーになる（文面に「2026-02-30」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: due, type: date }
```

`tasks/a.yaml`

```yaml
id: TASK-1
due: 2026-02-30
```

## enum の項目に、values に無い値を書くとエラーになる

- ID: EX-field-enum-values
- 示す仕様: KW-field.values・CTX-field
- 結果: 検証のエラーになる（文面に「doing」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: status, type: enum, values: [todo, done] }
```

`tasks/a.yaml`

```yaml
id: TASK-1
status: doing
```

## min・max の範囲の外の数はエラーになる

- ID: EX-field-min-max
- 示す仕様: KW-field.min・KW-field.max
- 結果: 検証のエラーになる（文面に「points」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: points, type: int, min: 1, max: 5 }
```

`tasks/a.yaml`

```yaml
id: TASK-1
points: 8
```

## pattern に合わない値は検証のエラーになる

- ID: EX-field-pattern
- 示す仕様: KW-field.pattern
- 結果: 検証のエラーになる（文面に「TASK」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
root:
  type: object
  fields:
    - { key: id, type: id, required: true, pattern: "^TASK-[0-9]+$" }
```

`tasks/a.yaml`

```yaml
id: T-1
```

## 参照先の ID が無い参照は、検証の指摘になる

- ID: EX-field-ref-unresolved
- 示す仕様: CTX-field
- 結果: 検証のエラーになる（文面に「TASK-9」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: parent, type: ref }
```

`tasks/a.yaml`

```yaml
id: TASK-1
parent: TASK-9
```

## 必須の項目が無いと検証のエラーになる

- ID: EX-field-required-missing
- 示す仕様: KW-field.required
- 結果: 検証のエラーになる（文面に「title」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, required: true }
```

`tasks/a.yaml`

```yaml
id: TASK-1
```

## 必須の項目がそろっていれば通る

- ID: EX-field-required-ok
- 示す仕様: KW-field.required
- 結果: 通る（誤りも警告も無い）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, required: true }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: 最初の作業
```

## requiredFrom のフェーズに達する前は、値が無くても通る

- ID: EX-field-requiredFrom-before
- 示す仕様: KW-field.requiredFrom・KW-schema.phases
- 結果: 通る（誤りも警告も無い）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
phases: [draft, review]
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: phase, type: enum, values: [draft, review] }
    - { key: reviewer, type: string, requiredFrom: review }
```

`tasks/a.yaml`

```yaml
id: TASK-1
phase: draft
```

## requiredFrom のフェーズに達すると、値が無いとエラーになる

- ID: EX-field-requiredFrom-reached
- 示す仕様: KW-field.requiredFrom・KW-schema.phases
- 結果: 検証のエラーになる（文面に「reviewer」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
phases: [draft, review]
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: phase, type: enum, values: [draft, review] }
    - { key: reviewer, type: string, requiredFrom: review }
```

`tasks/a.yaml`

```yaml
id: TASK-1
phase: review
```

## 型の名前を書くと、型の定義の決まりを引き継ぐ

- ID: EX-field-type-narrowing
- 示す仕様: KW-field.type・KW-schema.definitions
- 結果: 検証のエラーになる（文面に「price」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
definitions:
  金額:
    type: int
    min: 0
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: price, type: 金額 }
```

`tasks/a.yaml`

```yaml
id: TASK-1
price: -100
```

## unique: true の配列に同じ値を 2 回書くとエラーになる

- ID: EX-field-unique
- 示す仕様: KW-field.unique
- 結果: 検証のエラーになる（文面に「急ぎ」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: tags, type: array, unique: true, item: { type: string } }
```

`tasks/a.yaml`

```yaml
id: TASK-1
tags: [急ぎ, 急ぎ]
```

## スキーマに無いキーは、既定では警告になる

- ID: EX-field-unknown-fields-default
- 示す仕様: KW-field.unknownFields
- 結果: 検証の警告になる（文面に「memo」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, required: true }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: 最初の作業
memo: スキーマに無い項目
```

## unknownFields: error を書くと、スキーマに無いキーはエラーになる

- ID: EX-field-unknown-fields-error
- 示す仕様: KW-field.unknownFields
- 結果: 検証のエラーになる（文面に「memo」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
root:
  type: object
  unknownFields: error
  fields:
    - { key: id, type: id, required: true }
```

`tasks/a.yaml`

```yaml
id: TASK-1
memo: スキーマに無い項目
```

## レンズの絞り込みに、決まった語でないキーは書けない

- ID: EX-lens-filter-unknown-key
- 示す仕様: CTX-lens-filter
- 結果: ビューの定義の誤りになる（文面に「color」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: date }
    - { key: parent, type: ref, relation: child-of }
    - key: comments
      type: array
      item:
        type: object
        fields:
          - { key: date, type: date }
          - { key: text, type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: 設計
status: done
points: 3
due: 2026-10-05
comments:
  - { date: 2026-10-01, text: 着手 }
  - { date: 2026-10-04, text: 完了 }
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 5
due: 2026-11-20
parent: TASK-1
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
points: 2
parent: TASK-1
```

`.yde/views/example.yaml`

```yaml
id: example
name: 例
kind: lens
filters:
  - { id: open, mode: hide, where: { path: data.status, equals: done }, color: red }
```

## 型の操作（methods）は、その object の要素を受け手にする。アクションの call から呼べる

- ID: EX-method-call
- 示す仕様: CTX-method・KW-field.methods・KW-action.do
- 結果: 通る（誤りも警告も無い）

操作 `reopen`（受け手 TASK-1）

適用のあとの `tasks/TASK-1.yaml`

```yaml
id: TASK-1
title: 設計
status: todo
note: やり直し
```

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
filePattern: "tasks/{id}.yaml"
entity: { type: task, defines: true }
root:
  type: object
  methods:
    reopen:
      params:
        - { key: reason, type: string, required: true }
      do:
        - set: { path: status, value: todo }
        - set: { path: note, value: $reason }
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: note, type: string }
actions:
  - id: reopen
    target: { entity: task }
    do:
      - call: { method: reopen, params: { reason: やり直し } }
```

`tasks/TASK-1.yaml`

```yaml
id: TASK-1
title: 設計
status: done
```

## レポートには template が必要

- ID: EX-report-needs-template
- 示す仕様: KW-report.template
- 結果: レポートの定義の誤りになる（文面に「template」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: string }
    - key: tags
      type: array
      item: { type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: "  設計  の  見直し "
status: done
points: 3
due: 2026/10/5
tags: [設計, 急ぎ]
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 12
due: 2026年11月20日
tags: [実装]
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
```

`.yde/views/tasks.yaml`

```yaml
id: tasks
rows: { idPattern: "TASK-*" }
columns:
  - { label: 題, path: title }
  - { label: 状態, path: status }
  - { label: 点, path: points }
  - { label: 期限, path: due }
  - { label: 札, path: tags }
```

`.yde/reports/example.yaml`

```yaml
id: example
# template を書かない例
build:
  x: 1
```

`.yde/reports/example.md`

```yaml
x
```

## actions は、関係レジストリには書けない

- ID: EX-schema-actions-not-in-registry
- 示す仕様: KW-schema.actions・KW-schema.kind
- 結果: スキーマの読み込みの誤りになる（文面に「actions」を含む）

`.yde/schemas/relations.yaml`

```yaml
id: relations
kind: relations
relations:
  - { name: depends }
actions:
  - { id: noop }
```

## derive を書いたスキーマに、match は書けない

- ID: EX-schema-derive-with-match
- 示す仕様: KW-schema.derive・KW-schema.match
- 結果: スキーマの読み込みの誤りになる（文面に「match」を含む）

`.yde/schemas/record.yaml`

```yaml
id: record-template
match: "records/*.yaml"
derive:
  from: "tables/*.yaml"
  id: "record-{id}"
  match: "records/{id}/*.yaml"
  fields: { source: columns, key: name }
```

## relations は、関係レジストリ（kind: relations）にだけ書ける

- ID: EX-schema-relations-only-in-registry
- 示す仕様: KW-schema.relations・KW-schema.kind
- 結果: スキーマの読み込みの誤りになる（文面に「relations」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, required: true }
relations:
  - { name: depends }
```

## root は、文書のスキーマ（kind: document）にだけ書ける

- ID: EX-schema-root-not-in-library
- 示す仕様: KW-schema.root・KW-schema.kind
- 結果: スキーマの読み込みの誤りになる（文面に「root」を含む）

`.yde/schemas/common.yaml`

```yaml
id: common
kind: library
root:
  type: object
  fields:
    - { key: id, type: id }
```

## states は、振る舞いの宣言（kind: behavior）にだけ書ける

- ID: EX-schema-states-only-in-behavior
- 示す仕様: KW-schema.states・KW-schema.kind
- 結果: スキーマの読み込みの誤りになる（文面に「behavior」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, required: true }
states:
  - { id: open, entity: task }
```

## $concat は配列をつなげ、$flatten は $each の結果を 1 段ならす

- ID: EX-template-concat-flatten
- 示す仕様: KW-template.$concat・KW-template.$flatten
- 結果: 通る（誤りも警告も無い）

レポート `example` の出力:

```text
設計・急ぎ・実装
最初・最後
```

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: string }
    - key: tags
      type: array
      item: { type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: "  設計  の  見直し "
status: done
points: 3
due: 2026/10/5
tags: [設計, 急ぎ]
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 12
due: 2026年11月20日
tags: [実装]
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
```

`.yde/views/tasks.yaml`

```yaml
id: tasks
rows: { idPattern: "TASK-*" }
columns:
  - { label: 題, path: title }
  - { label: 状態, path: status }
  - { label: 点, path: points }
  - { label: 期限, path: due }
  - { label: 札, path: tags }
```

`.yde/reports/example.yaml`

```yaml
id: example
build:
  tags:
    $each: { $view: tasks }
    $flatten: true
    $build: { $copy: values.札 }
  more:
    $concat:
      - [最初]
      - [最後]
template: example.md
```

`.yde/reports/example.md`

```yaml
{#each tags}{.}{#if more}・{/if}{/each}
{#each more}{.}{#if more}・{/if}{/each}
```

## $format は、埋まらない穴があると「無い」になり、マップのキーごと落ちる

- ID: EX-template-format-missing
- 示す仕様: KW-template.$format・KW-template.$first
- 結果: 通る（誤りも警告も無い）

レポート `example` の出力:

```text
- TASK-1（3 点） / 3 点
- TASK-2（12 点） / 12 点
- TASK-3（点なし）
```

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: string }
    - key: tags
      type: array
      item: { type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: "  設計  の  見直し "
status: done
points: 3
due: 2026/10/5
tags: [設計, 急ぎ]
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 12
due: 2026年11月20日
tags: [実装]
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
```

`.yde/views/tasks.yaml`

```yaml
id: tasks
rows: { idPattern: "TASK-*" }
columns:
  - { label: 題, path: title }
  - { label: 状態, path: status }
  - { label: 点, path: points }
  - { label: 期限, path: due }
  - { label: 札, path: tags }
```

`.yde/reports/example.yaml`

```yaml
id: example
build:
  rows:
    $each: { $view: tasks }
    $build:
      id: { $copy: id }
      points: { $format: "{values.点} 点" }
      label:
        $first:
          - { $format: "{id}（{values.点} 点）" }
          - { $format: "{id}（点なし）" }
template: example.md
```

`.yde/reports/example.md`

```yaml
{#each rows}
- {label}{#if points} / {points}{/if}
{/each}
```

## $group は、キーが同じ要素をまとめ、まとまりごとに組み立てる

- ID: EX-template-group
- 示す仕様: KW-template.$group・KW-template.$by・KW-template.$bind
- 結果: 通る（誤りも警告も無い）

レポート `example` の出力:

```text
done: TASK-1
doing: TASK-2
todo: TASK-3
```

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: string }
    - key: tags
      type: array
      item: { type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: "  設計  の  見直し "
status: done
points: 3
due: 2026/10/5
tags: [設計, 急ぎ]
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 12
due: 2026年11月20日
tags: [実装]
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
```

`.yde/views/tasks.yaml`

```yaml
id: tasks
rows: { idPattern: "TASK-*" }
columns:
  - { label: 題, path: title }
  - { label: 状態, path: status }
  - { label: 点, path: points }
  - { label: 期限, path: due }
  - { label: 札, path: tags }
```

`.yde/reports/example.yaml`

```yaml
id: example
build:
  groups:
    $group: { $view: tasks }
    $by: { $copy: values.状態 }
    $build:
      key: { $copy: key }
      ids:
        $each: items
        $build: { $copy: id }
template: example.md
```

`.yde/reports/example.md`

```yaml
{#each groups}
{key}: {#each ids}{.}{#if more}, {/if}{/each}
{/each}
```

## $map・$number・$date は、値を置き換え・数として読み・年月日をそろえる

- ID: EX-template-map-number-date
- 示す仕様: KW-template.$map・KW-template.$using・KW-template.$number・KW-template.$date・KW-template.$as
- 結果: 通る（誤りも警告も無い）

レポート `example` の出力:

```text
TASK-1 完了 003 2026-10-05
TASK-2 doing 012 2026-11-20
```

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: string }
    - key: tags
      type: array
      item: { type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: "  設計  の  見直し "
status: done
points: 3
due: 2026/10/5
tags: [設計, 急ぎ]
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 12
due: 2026年11月20日
tags: [実装]
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
```

`.yde/views/tasks.yaml`

```yaml
id: tasks
rows: { idPattern: "TASK-*" }
columns:
  - { label: 題, path: title }
  - { label: 状態, path: status }
  - { label: 点, path: points }
  - { label: 期限, path: due }
  - { label: 札, path: tags }
```

`.yde/reports/example.yaml`

```yaml
id: example
build:
  rows:
    $each: { $view: tasks }
    $build:
      $format: "{id} {state} {num} {due}"
      $with:
        state: { $map: { $copy: values.状態 }, $using: { todo: 未着手, done: 完了 } }
        num: { $number: { $copy: values.点 }, $as: "000" }
        due: { $date: { $copy: values.期限 } }
template: example.md
```

`.yde/reports/example.md`

```yaml
{#each rows}
{.}
{/each}
```

## 受け取る値は $名前 で読み、繰り返しの中では $position が使える

- ID: EX-template-params-loop
- 示す仕様: KW-report.params・CTX-template
- 結果: 通る（誤りも警告も無い）

レポート `example` の出力（渡す値: who＝田中）:

```text
田中 さんの一覧
1. TASK-1
2. TASK-2
3. TASK-3
```

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: string }
    - key: tags
      type: array
      item: { type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: "  設計  の  見直し "
status: done
points: 3
due: 2026/10/5
tags: [設計, 急ぎ]
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 12
due: 2026年11月20日
tags: [実装]
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
```

`.yde/views/tasks.yaml`

```yaml
id: tasks
rows: { idPattern: "TASK-*" }
columns:
  - { label: 題, path: title }
  - { label: 状態, path: status }
  - { label: 点, path: points }
  - { label: 期限, path: due }
  - { label: 札, path: tags }
```

`.yde/reports/example.yaml`

```yaml
id: example
params: [who]
build:
  who: $who
  rows:
    $each: { $view: tasks }
    $build:
      n: $position
      id: { $copy: id }
template: example.md
```

`.yde/reports/example.md`

```yaml
{who} さんの一覧
{#each rows}
{n}. {id}
{/each}
```

## rules は名前を付けた組み立てで、同じ名前は上から when を調べて最初に合ったものを使う

- ID: EX-template-rules-apply
- 示す仕様: CTX-template-rule・KW-template.$apply
- 結果: 通る（誤りも警告も無い）

レポート `example` の出力:

```text
TASK-1 小
TASK-2 大
TASK-3 点なし
```

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: string }
    - key: tags
      type: array
      item: { type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: "  設計  の  見直し "
status: done
points: 3
due: 2026/10/5
tags: [設計, 急ぎ]
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 12
due: 2026年11月20日
tags: [実装]
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
```

`.yde/views/tasks.yaml`

```yaml
id: tasks
rows: { idPattern: "TASK-*" }
columns:
  - { label: 題, path: title }
  - { label: 状態, path: status }
  - { label: 点, path: points }
  - { label: 期限, path: due }
  - { label: 札, path: tags }
```

`.yde/reports/example.yaml`

```yaml
id: example
build:
  rows: { $each: { $view: tasks }, $apply: size }
rules:
  - { id: size, when: [ { path: values.点, gte: 10 } ], build: { $format: "{id} 大" } }
  - { id: size, when: [ { path: values.点, exists: true } ], build: { $format: "{id} 小" } }
  - { id: size, build: { $format: "{id} 点なし" } }
template: example.md
```

`.yde/reports/example.md`

```yaml
{#each rows}
{.}
{/each}
```

## $trim は空白をまとめ、$case は名前の書き方をそろえる

- ID: EX-template-trim-case
- 示す仕様: KW-template.$trim・KW-template.$case
- 結果: 通る（誤りも警告も無い）

レポート `example` の出力:

```text
[設計 の 見直し] designReviewItem
```

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: string }
    - key: tags
      type: array
      item: { type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: "  設計  の  見直し "
status: done
points: 3
due: 2026/10/5
tags: [設計, 急ぎ]
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 12
due: 2026年11月20日
tags: [実装]
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
```

`.yde/views/tasks.yaml`

```yaml
id: tasks
rows: { idPattern: "TASK-*" }
columns:
  - { label: 題, path: title }
  - { label: 状態, path: status }
  - { label: 点, path: points }
  - { label: 期限, path: due }
  - { label: 札, path: tags }
```

`.yde/reports/example.yaml`

```yaml
id: example
build:
  rows:
    $each: { $view: tasks, $where: [ { path: id, equals: TASK-1 } ] }
    $build:
      $format: "[{title}] {name}"
      $with:
        title: { $trim: { $copy: values.題 } }
        name: { $case: design review item, $as: camel }
template: example.md
```

`.yde/reports/example.md`

```yaml
{#each rows}
{.}
{/each}
```

## $value は、値の式をいまの場所で計算する

- ID: EX-template-value-expr
- 示す仕様: KW-template.$value・CTX-expr
- 結果: 通る（誤りも警告も無い）

レポート `example` の出力:

```text
TASK-1 6
TASK-2 24
```

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: string }
    - key: tags
      type: array
      item: { type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: "  設計  の  見直し "
status: done
points: 3
due: 2026/10/5
tags: [設計, 急ぎ]
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 12
due: 2026年11月20日
tags: [実装]
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
```

`.yde/views/tasks.yaml`

```yaml
id: tasks
rows: { idPattern: "TASK-*" }
columns:
  - { label: 題, path: title }
  - { label: 状態, path: status }
  - { label: 点, path: points }
  - { label: 期限, path: due }
  - { label: 札, path: tags }
```

`.yde/reports/example.yaml`

```yaml
id: example
build:
  rows:
    $each: { $view: tasks }
    $build:
      $format: "{id} {double}"
      $with:
        double: { $value: { multiply: [ { path: values.点 }, 2 ] } }
template: example.md
```

`.yde/reports/example.md`

```yaml
{#each rows}
{.}
{/each}
```

## レポートは $view でビューの行を受け取り、ひな形の繰り返しで並べる

- ID: EX-template-view-each
- 示す仕様: CTX-report・KW-template.$view・KW-template.$each・KW-template.$build・KW-template.$copy
- 結果: 通る（誤りも警告も無い）

レポート `example` の出力:

```text
- TASK-1: done
- TASK-2: doing
- TASK-3: todo
```

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: string }
    - key: tags
      type: array
      item: { type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: "  設計  の  見直し "
status: done
points: 3
due: 2026/10/5
tags: [設計, 急ぎ]
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 12
due: 2026年11月20日
tags: [実装]
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
```

`.yde/views/tasks.yaml`

```yaml
id: tasks
rows: { idPattern: "TASK-*" }
columns:
  - { label: 題, path: title }
  - { label: 状態, path: status }
  - { label: 点, path: points }
  - { label: 期限, path: due }
  - { label: 札, path: tags }
```

`.yde/reports/example.yaml`

```yaml
id: example
build:
  rows:
    $each: { $view: tasks }
    $build:
      id: { $copy: id }
      status: { $copy: values.状態 }
template: example.md
```

`.yde/reports/example.md`

```yaml
{#each rows}
- {id}: {status}
{/each}
```

## $when は条件に合うときだけ出し、$where はビューの行を絞る

- ID: EX-template-when-where
- 示す仕様: KW-template.$when・KW-template.$then・KW-template.$where
- 結果: 通る（誤りも警告も無い）

レポート `example` の出力:

```text
- TASK-2（対応中）
- TASK-3
```

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: string }
    - key: tags
      type: array
      item: { type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: "  設計  の  見直し "
status: done
points: 3
due: 2026/10/5
tags: [設計, 急ぎ]
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 12
due: 2026年11月20日
tags: [実装]
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
```

`.yde/views/tasks.yaml`

```yaml
id: tasks
rows: { idPattern: "TASK-*" }
columns:
  - { label: 題, path: title }
  - { label: 状態, path: status }
  - { label: 点, path: points }
  - { label: 期限, path: due }
  - { label: 札, path: tags }
```

`.yde/reports/example.yaml`

```yaml
id: example
build:
  open:
    $each: { $view: tasks, $where: [ { path: values.状態, notEquals: done } ] }
    $build:
      id: { $copy: id }
      mark: { $when: { path: values.状態, equals: doing }, $then: 対応中 }
template: example.md
```

`.yde/reports/example.md`

```yaml
{#each open}
- {id}{#if mark}（{mark}）{/if}
{/each}
```

## ひな形の穴は、形と表のセルの書き方を添えられる

- ID: EX-text-template-format-cell
- 示す仕様: KW-report.template
- 結果: 通る（誤りも警告も無い）

レポート `example` の出力:

```text
合計 1,234,567
| a\|b |
{そのまま}
```

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: string }
    - key: tags
      type: array
      item: { type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: "  設計  の  見直し "
status: done
points: 3
due: 2026/10/5
tags: [設計, 急ぎ]
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 12
due: 2026年11月20日
tags: [実装]
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
```

`.yde/views/tasks.yaml`

```yaml
id: tasks
rows: { idPattern: "TASK-*" }
columns:
  - { label: 題, path: title }
  - { label: 状態, path: status }
  - { label: 点, path: points }
  - { label: 期限, path: due }
  - { label: 札, path: tags }
```

`.yde/reports/example.yaml`

```yaml
id: example
build:
  total: 1234567
  note: "a|b"
template: example.md
```

`.yde/reports/example.md`

```yaml
合計 {total:#,##0}
| {note:cell} |
{{そのまま}}
```

## cells は、クロス集計（kind: matrix）にだけ書ける

- ID: EX-view-cells-only-in-matrix
- 示す仕様: KW-view-matrix.cells・CTX-view-grid
- 結果: ビューの定義の誤りになる（文面に「matrix」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: date }
    - { key: parent, type: ref, relation: child-of }
    - key: comments
      type: array
      item:
        type: object
        fields:
          - { key: date, type: date }
          - { key: text, type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: 設計
status: done
points: 3
due: 2026-10-05
comments:
  - { date: 2026-10-01, text: 着手 }
  - { date: 2026-10-04, text: 完了 }
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 5
due: 2026-11-20
parent: TASK-1
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
points: 2
parent: TASK-1
```

`.yde/views/example.yaml`

```yaml
id: example
name: 例
rows: { idPattern: "TASK-*" }
columns:
  - { label: 状態, path: status }
cells: { of: c }
```

## グリッドには columns が必要

- ID: EX-view-grid-needs-columns
- 示す仕様: KW-view-grid.columns
- 結果: ビューの定義の誤りになる（文面に「columns」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: date }
    - { key: parent, type: ref, relation: child-of }
    - key: comments
      type: array
      item:
        type: object
        fields:
          - { key: date, type: date }
          - { key: text, type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: 設計
status: done
points: 3
due: 2026-10-05
comments:
  - { date: 2026-10-01, text: 着手 }
  - { date: 2026-10-04, text: 完了 }
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 5
due: 2026-11-20
parent: TASK-1
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
points: 2
parent: TASK-1
```

`.yde/views/example.yaml`

```yaml
id: example
name: 例
rows: { idPattern: "TASK-*" }
```

## グリッドは ID の形で行を選び、order の値の順に並べる

- ID: EX-view-grid-path-order
- 示す仕様: CTX-view-grid・CTX-view-rows・CTX-view-order・KW-view-column.path
- 結果: 通る（誤りも警告も無い）
- ビュー `example` の行（並びの順）:
  - `TASK-2`: 状態＝doing、点＝5
  - `TASK-1`: 状態＝done、点＝3
  - `TASK-3`: 状態＝todo、点＝2

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: date }
    - { key: parent, type: ref, relation: child-of }
    - key: comments
      type: array
      item:
        type: object
        fields:
          - { key: date, type: date }
          - { key: text, type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: 設計
status: done
points: 3
due: 2026-10-05
comments:
  - { date: 2026-10-01, text: 着手 }
  - { date: 2026-10-04, text: 完了 }
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 5
due: 2026-11-20
parent: TASK-1
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
points: 2
parent: TASK-1
```

`.yde/views/example.yaml`

```yaml
id: example
name: 例
rows: { idPattern: "TASK-*" }
order: { schemaId: task, path: points, type: number, direction: desc }
columns:
  - { label: 状態, path: status }
  - { label: 点, path: points }
```

## クロス集計は、行の要素を起点に集めた要素を列の軸ごとに数え、既定では合計の行を出す

- ID: EX-view-matrix-count
- 示す仕様: CTX-view-matrix・CTX-view-cells・CTX-view-cells-columns・KW-view-matrix.totals
- 結果: 通る（誤りも警告も無い）
- ビュー `example` の行（並びの順）:
  - `TASK-1`: todo＝1、doing＝1、done＝0
  - `TASK-2`
  - `TASK-3`
  - `合計`: todo＝1、doing＝1、done＝0

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: date }
    - { key: parent, type: ref, relation: child-of }
    - key: comments
      type: array
      item:
        type: object
        fields:
          - { key: date, type: date }
          - { key: text, type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: 設計
status: done
points: 3
due: 2026-10-05
comments:
  - { date: 2026-10-01, text: 着手 }
  - { date: 2026-10-04, text: 完了 }
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 5
due: 2026-11-20
parent: TASK-1
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
points: 2
parent: TASK-1
```

`.yde/views/example.yaml`

```yaml
id: example
name: 例
kind: matrix
rows: { idPattern: "TASK-*" }
cells:
  match:
    - node: { entity: task, bind: p }
    - edge: { relation: child-of, dir: in }
    - node: { entity: task, bind: c }
  of: c
  columns: { path: status, values: [todo, doing, done] }
```

## クエリの行は、解が行になり、of でほかの束縛の値を列にする

- ID: EX-view-query-rows
- 示す仕様: KW-view-rows.match・KW-view-rows.where・KW-view-column.of・CTX-query-node・CTX-condition
- 結果: 通る（誤りも警告も無い）
- ビュー `example` の行（並びの順）:
  - `TASK-2`: 親＝設計、状態＝doing
  - `TASK-3`: 親＝設計、状態＝todo

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
    - { key: status, type: enum, values: [todo, doing, done] }
    - { key: points, type: int }
    - { key: due, type: date }
    - { key: parent, type: ref, relation: child-of }
    - key: comments
      type: array
      item:
        type: object
        fields:
          - { key: date, type: date }
          - { key: text, type: string }
```

`tasks/a.yaml`

```yaml
id: TASK-1
title: 設計
status: done
points: 3
due: 2026-10-05
comments:
  - { date: 2026-10-01, text: 着手 }
  - { date: 2026-10-04, text: 完了 }
```

`tasks/b.yaml`

```yaml
id: TASK-2
title: 実装
status: doing
points: 5
due: 2026-11-20
parent: TASK-1
```

`tasks/c.yaml`

```yaml
id: TASK-3
title: 試験
status: todo
points: 2
parent: TASK-1
```

`.yde/views/example.yaml`

```yaml
id: example
name: 例
rows:
  match:
    - node: { entity: task, bind: c }
    - edge: { relation: child-of }
    - node: { entity: task, bind: p }
  where:
    - { of: c, path: status, notEquals: done }
columns:
  - { label: 親, of: p, path: title }
  - { label: 状態, path: status }
```

