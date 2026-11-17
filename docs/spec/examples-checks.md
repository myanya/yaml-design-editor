# 仕様の例: 検査と振る舞い

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/examples-md.md（分野ごとのレポートが同じひな形を使う） -->

検査（constraints と組込みの語彙）、関係レジストリ、State と Lifecycle の例。1 つの例は小さなワークスペース（定義と文書）で、開いて検証したときの結果を添えている。
結果は repo-tests の `SpecTest` が実際に開いて確かめているので、ここに書いてあるとおりに動く。
**原本は [devtrack/](../../devtrack/README.md) の `spec/examples/`**（1 例 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

## 原因（by）は、action か event のどちらか 1 つだけを書く

- ID: EX-behavior-cause-one-of
- 示す仕様: CTX-lifecycle-cause
- 結果: スキーマの読み込みの誤りになる（文面に「どちらか一つ」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: status, type: enum, values: [open, closed] }
actions:
  - id: open-task
    do:
      - create: { schema: task, params: { status: open } }
  - id: close-task
    target: { entity: task }
    do:
      - set: { path: status, value: closed }
```

`.yde/schemas/behavior.yaml`

```yaml
id: task-behavior
kind: behavior
states:
  - { id: task-open, entity: task, predicate: { match: [ { node: { entity: task, bind: t } } ], where: [ { of: t, path: status, equals: open } ], expect: exists } }
  - { id: task-closed, entity: task, predicate: { match: [ { node: { entity: task, bind: t } } ], where: [ { of: t, path: status, equals: closed } ], expect: exists } }
lifecycles:
  - id: task-lifecycle
    entity: task
    states: [task-open, task-closed]
    entry:
      - { to: task-open, by: { action: open-task } }
    transitions:
      - { from: task-open, to: task-closed, by: { action: close-task, event: closed } }
    termination: { terminal: [task-closed] }
```

## State と Lifecycle を、振る舞いの宣言（kind: behavior）に書く

- ID: EX-behavior-lifecycle-ok
- 示す仕様: CTX-state・CTX-lifecycle・CTX-lifecycle-entry・CTX-lifecycle-transition・KW-schema.lifecycles
- 結果: 通る（誤りも警告も無い）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: status, type: enum, values: [open, closed] }
actions:
  - id: open-task
    do:
      - create: { schema: task, params: { status: open } }
  - id: close-task
    target: { entity: task }
    do:
      - set: { path: status, value: closed }
```

`.yde/schemas/behavior.yaml`

```yaml
id: task-behavior
kind: behavior
states:
  - { id: task-open, entity: task, predicate: { match: [ { node: { entity: task, bind: t } } ], where: [ { of: t, path: status, equals: open } ], expect: exists } }
  - { id: task-closed, entity: task, predicate: { match: [ { node: { entity: task, bind: t } } ], where: [ { of: t, path: status, equals: closed } ], expect: exists } }
lifecycles:
  - id: task-lifecycle
    entity: task
    states: [task-open, task-closed]
    entry:
      - { to: task-open, by: { action: open-task } }
    transitions:
      - { from: task-open, to: task-closed, by: { action: close-task } }
    termination: { terminal: [task-closed] }
```

## 振る舞いの宣言の位置に、決まった語でないキーは書けない

- ID: EX-behavior-unknown-key
- 示す仕様: CTX-lifecycle-transition
- 結果: スキーマの読み込みの誤りになる（文面に「note」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: status, type: enum, values: [open, closed] }
actions:
  - id: open-task
    do:
      - create: { schema: task, params: { status: open } }
  - id: close-task
    target: { entity: task }
    do:
      - set: { path: status, value: closed }
```

`.yde/schemas/behavior.yaml`

```yaml
id: task-behavior
kind: behavior
states:
  - { id: task-open, entity: task, predicate: { match: [ { node: { entity: task, bind: t } } ], where: [ { of: t, path: status, equals: open } ], expect: exists } }
  - { id: task-closed, entity: task, predicate: { match: [ { node: { entity: task, bind: t } } ], where: [ { of: t, path: status, equals: closed } ], expect: exists } }
lifecycles:
  - id: task-lifecycle
    entity: task
    states: [task-open, task-closed]
    entry:
      - { to: task-open, by: { action: open-task } }
    transitions:
      - { from: task-open, to: task-closed, by: { action: close-task }, note: メモ }
    termination: { terminal: [task-closed] }
```

## Lifecycle が宣言していない State を指すと、読み込みの点検で誤りになる

- ID: EX-behavior-unknown-state
- 示す仕様: KW-lifecycle.states・KW-lifecycle-transition.to
- 結果: スキーマの読み込みの誤りになる（文面に「task-archived」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: status, type: enum, values: [open, closed] }
actions:
  - id: open-task
    do:
      - create: { schema: task, params: { status: open } }
  - id: close-task
    target: { entity: task }
    do:
      - set: { path: status, value: closed }
```

`.yde/schemas/behavior.yaml`

```yaml
id: task-behavior
kind: behavior
states:
  - { id: task-open, entity: task, predicate: { match: [ { node: { entity: task, bind: t } } ], where: [ { of: t, path: status, equals: open } ], expect: exists } }
  - { id: task-closed, entity: task, predicate: { match: [ { node: { entity: task, bind: t } } ], where: [ { of: t, path: status, equals: closed } ], expect: exists } }
lifecycles:
  - id: task-lifecycle
    entity: task
    states: [task-open, task-closed]
    entry:
      - { to: task-open, by: { action: open-task } }
    transitions:
      - { from: task-open, to: task-archived, by: { action: close-task } }
    termination: { terminal: [task-closed] }
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

## 検査の count は、起点ごとの解の件数が範囲に入ることを求める

- ID: EX-constraint-count
- 示す仕様: KW-constraint.count・CTX-constraint-count
- 結果: 検証のエラーになる（文面に「要件 REQ-1 を確かめる試験が 2 件あります」を含む）

`.yde/schemas/req.yaml`

```yaml
id: req
match: "reqs/*.yaml"
entity: { type: req, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
constraints:
  - id: at-most-one-test
    match:
      - node: { entity: req, bind: r }
      - edge: { relation: covers, dir: in }
      - node: { entity: test, bind: t }
    count: { max: 1 }
    level: error
    message: "要件 {id} を確かめる試験が {count} 件あります"
```

`.yde/schemas/test.yaml`

```yaml
id: test
match: "tests/*.yaml"
entity: { type: test, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: covers, type: ref, relation: covers }
```

`reqs/a.yaml`

```yaml
id: REQ-1
title: ログインできる
```

`tests/a.yaml`

```yaml
id: TEST-1
covers: REQ-1
```

`tests/b.yaml`

```yaml
id: TEST-2
covers: REQ-1
```

## 検査の expect: absent は、クエリの解があると違反になる

- ID: EX-constraint-expect-absent
- 示す仕様: KW-constraint.expect・KW-constraint.match・KW-constraint.where・KW-constraint.message
- 結果: 検証のエラーになる（文面に「試験 TEST-1 が、題の無い要件 REQ-1 を確かめています」を含む）

`.yde/schemas/req.yaml`

```yaml
id: req
match: "reqs/*.yaml"
entity: { type: req, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
```

`.yde/schemas/test.yaml`

```yaml
id: test
match: "tests/*.yaml"
entity: { type: test, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: covers, type: ref, relation: covers }
constraints:
  - id: no-self-check
    match:
      - node: { entity: test, bind: t }
      - edge: { relation: covers }
      - node: { entity: req, bind: r }
    where:
      - { of: r, path: title, empty: true }
    expect: absent
    level: error
    message: "試験 {id} が、題の無い要件 {r.id} を確かめています"
```

`reqs/a.yaml`

```yaml
id: REQ-1
```

`tests/a.yaml`

```yaml
id: TEST-1
covers: REQ-1
```

## 検査の定義（functions）は、use と with で名前を指定して使う

- ID: EX-constraint-use-function
- 示す仕様: CTX-check-function・KW-constraint.use・KW-constraint.with
- 結果: 検証のエラーになる（文面に「REQ-1 に題がありません」を含む）

`.yde/schemas/common.yaml`

```yaml
id: common
kind: library
functions:
  - id: has-title
    params: [type, level]
    checks:
      - id: has-title
        match:
          - node: { entity: $type, bind: x }
        where:
          - { of: x, path: title, empty: true }
        expect: absent
        level: $level
        message: "{id} に題がありません"
```

`.yde/schemas/req.yaml`

```yaml
id: req
match: "reqs/*.yaml"
entity: { type: req, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
constraints:
  - use: has-title
    with: { type: req, level: error }
```

`reqs/a.yaml`

```yaml
id: REQ-1
```

## coverage は、ID が詳細のスキーマの文書にも書かれていることを求める

- ID: EX-coverage-detail-missing
- 示す仕様: CTX-coverage・CTX-coverage-detail・KW-schema.coverage
- 結果: 検証のエラーになる（文面に「REQ-2」を含む）

`.yde/schemas/req.yaml`

```yaml
id: req
match: "reqs/*.yaml"
entity: { type: req, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
coverage:
  - id: req-has-detail
    idPattern: "REQ-*"
    detailIn: { schemaIds: [req-detail] }
    level: error
```

`.yde/schemas/req-detail.yaml`

```yaml
id: req-detail
match: "details/*.yaml"
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: body, type: string }
```

`reqs/a.yaml`

```yaml
id: REQ-1
title: ログインできる
```

`reqs/b.yaml`

```yaml
id: REQ-2
title: ログアウトできる
```

`details/a.yaml`

```yaml
id: REQ-1
body: 詳しい説明
```

## coverage の語彙は、detailIn.schemaIds を必ず書く（語彙の requires）

- ID: EX-coverage-requires-detail
- 示す仕様: KW-check-vocabulary.requires・KW-coverage-detail.schemaIds
- 結果: スキーマの読み込みの誤りになる（文面に「detailIn.schemaIds」を含む）

`.yde/schemas/req.yaml`

```yaml
id: req
match: "reqs/*.yaml"
entity: { type: req, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
coverage:
  - id: req-has-detail
    detailIn: { minCount: 1 }
```

## 関係レジストリがあるとき、宣言していない名前の関係を使うと、スキーマの読み込みの点検に出る

- ID: EX-enforcement-undeclared-relation
- 示す仕様: KW-relation.name・KW-enforcement.undeclaredRelation
- 結果: スキーマの読み込みの誤りになる（文面に「関係「covers」は関係レジストリに宣言されていません」を含む）

`.yde/schemas/req.yaml`

```yaml
id: req
match: "reqs/*.yaml"
entity: { type: req, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
```

`.yde/schemas/test.yaml`

```yaml
id: test
match: "tests/*.yaml"
entity: { type: test, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: covers, type: ref, relation: covers }
```

`.yde/schemas/relations.yaml`

```yaml
id: relations
kind: relations
relations:
  - { name: verifies }
enforcement:
  undeclaredRelation: error
```

`reqs/a.yaml`

```yaml
id: REQ-1
title: ログインできる
```

`tests/a.yaml`

```yaml
id: TEST-1
covers: REQ-1
```

## 横断の検査は、関係レジストリにも書ける（matrix を関係レジストリに書いた、満たしている例）

- ID: EX-matrix-in-registry
- 示す仕様: KW-schema.matrix・CTX-matrix・KW-schema.kind
- 結果: 通る（誤りも警告も無い）

`.yde/schemas/req.yaml`

```yaml
id: req
match: "reqs/*.yaml"
entity: { type: req, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
```

`.yde/schemas/env.yaml`

```yaml
id: env
match: "envs/*.yaml"
entity: { type: env, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
```

`.yde/schemas/test.yaml`

```yaml
id: test
match: "tests/*.yaml"
entity: { type: test, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: covers, type: ref, relation: covers }
    - { key: env, type: ref, relation: runs-on }
```

`reqs/a.yaml`

```yaml
id: REQ-1
title: ログインできる
```

`envs/a.yaml`

```yaml
id: ENV-1
```

`envs/b.yaml`

```yaml
id: ENV-2
```

`tests/a.yaml`

```yaml
id: TEST-1
covers: REQ-1
env: ENV-1
```

`.yde/schemas/relations.yaml`

```yaml
id: relations
kind: relations
relations:
  - { name: covers }
  - { name: runs-on }
matrix:
  - id: every-req-on-every-env
    rows: { entity: req }
    columns: { entity: env }
    witness:
      node: { entity: test }
      row: { relation: covers }
      column: { relation: runs-on }
    level: error
```

`tests/b.yaml`

```yaml
id: TEST-2
covers: REQ-1
env: ENV-2
```

## matrix は、行と列のすべての組み合わせに証人があることを求める（行の要素のスキーマに書く）

- ID: EX-matrix-missing-cell
- 示す仕様: CTX-matrix・CTX-matrix-witness・KW-schema.matrix
- 結果: 検証のエラーになる（文面に「ENV-2」を含む）

`.yde/schemas/req.yaml`

```yaml
id: req
match: "reqs/*.yaml"
entity: { type: req, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
matrix:
  - id: every-req-on-every-env
    rows: { entity: req }
    columns: { entity: env }
    witness:
      node: { entity: test }
      row: { relation: covers }
      column: { relation: runs-on }
    level: error
```

`.yde/schemas/env.yaml`

```yaml
id: env
match: "envs/*.yaml"
entity: { type: env, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
```

`.yde/schemas/test.yaml`

```yaml
id: test
match: "tests/*.yaml"
entity: { type: test, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: covers, type: ref, relation: covers }
    - { key: env, type: ref, relation: runs-on }
```

`reqs/a.yaml`

```yaml
id: REQ-1
title: ログインできる
```

`envs/a.yaml`

```yaml
id: ENV-1
```

`envs/b.yaml`

```yaml
id: ENV-2
```

`tests/a.yaml`

```yaml
id: TEST-1
covers: REQ-1
env: ENV-1
```

## acyclic の関係で循環を作ると、いつもエラーになる

- ID: EX-relation-acyclic
- 示す仕様: KW-relation.acyclic
- 結果: 検証のエラーになる（文面に「TASK-1」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
entity: { type: task, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: after, type: ref, relation: after }
```

`.yde/schemas/relations.yaml`

```yaml
id: relations
kind: relations
relations:
  - { name: after, acyclic: true }
```

`tasks/a.yaml`

```yaml
id: TASK-1
after: TASK-2
```

`tasks/b.yaml`

```yaml
id: TASK-2
after: TASK-1
```

## 多重度の inMin は、参照先の要素へ入ってくる参照の本数の下限

- ID: EX-relation-cardinality-in-min
- 示す仕様: CTX-cardinality・KW-relation.cardinality・KW-relation.level
- 結果: 検証のエラーになる（文面に「REQ-2」を含む）

`.yde/schemas/req.yaml`

```yaml
id: req
match: "reqs/*.yaml"
entity: { type: req, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
```

`.yde/schemas/test.yaml`

```yaml
id: test
match: "tests/*.yaml"
entity: { type: test, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: covers, type: ref, relation: covers }
```

`.yde/schemas/relations.yaml`

```yaml
id: relations
kind: relations
relations:
  - name: covers
    label: 確かめる要件
    inverseLabel: 確かめる試験
    source: [test]
    target: [req]
    cardinality: { inMin: 1 }
    level: error
```

`reqs/a.yaml`

```yaml
id: REQ-1
title: ログインできる
```

`reqs/b.yaml`

```yaml
id: REQ-2
title: ログアウトできる
```

`tests/a.yaml`

```yaml
id: TEST-1
covers: REQ-1
```

## 多重度を満たしていれば通る

- ID: EX-relation-cardinality-ok
- 示す仕様: CTX-cardinality
- 結果: 通る（誤りも警告も無い）

`.yde/schemas/req.yaml`

```yaml
id: req
match: "reqs/*.yaml"
entity: { type: req, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
```

`.yde/schemas/test.yaml`

```yaml
id: test
match: "tests/*.yaml"
entity: { type: test, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: covers, type: ref, relation: covers }
```

`.yde/schemas/relations.yaml`

```yaml
id: relations
kind: relations
relations:
  - name: covers
    label: 確かめる要件
    inverseLabel: 確かめる試験
    source: [test]
    target: [req]
    cardinality: { inMin: 1 }
    level: error
```

`reqs/a.yaml`

```yaml
id: REQ-1
title: ログインできる
```

`tests/a.yaml`

```yaml
id: TEST-1
covers: REQ-1
```

## 関係の target を宣言すると、参照先の型が違う参照は指摘になる

- ID: EX-relation-target-type
- 示す仕様: KW-relation.target・KW-relation.source
- 結果: 検証のエラーになる（文面に「宣言（target: req）に一致しません」を含む）

`.yde/schemas/req.yaml`

```yaml
id: req
match: "reqs/*.yaml"
entity: { type: req, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: title, type: string, role: title }
```

`.yde/schemas/test.yaml`

```yaml
id: test
match: "tests/*.yaml"
entity: { type: test, defines: true }
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
    - { key: covers, type: ref, relation: covers }
```

`.yde/schemas/relations.yaml`

```yaml
id: relations
kind: relations
relations:
  - { name: covers, source: [test], target: [req], level: error }
```

`reqs/a.yaml`

```yaml
id: REQ-1
title: ログインできる
```

`tests/a.yaml`

```yaml
id: TEST-1
covers: REQ-1
```

`tests/b.yaml`

```yaml
id: TEST-2
covers: TEST-1
```

