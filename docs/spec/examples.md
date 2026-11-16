# 仕様の例

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/examples-md.md -->

仕様の語と決まりを示す例。1 つの例は小さなワークスペース（定義と文書）で、開いて検証したときの結果を添えている。
結果は repo-tests の `SpecTest` が実際に開いて確かめているので、ここに書いてあるとおりに動く。
**原本は [devtrack/](../../devtrack/README.md) の `spec/examples/`**（1 例 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

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

