# 仕様の例: スキーマ

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/examples-md.md（分野ごとのレポートが同じひな形を使う） -->

スキーマの項目定義と、スキーマ文書の種類ごとの決まりの例。1 つの例は小さなワークスペース（定義と文書）で、開いて検証したときの結果を添えている。
結果は repo-tests の `SpecTest` が実際に開いて確かめているので、ここに書いてあるとおりに動く。
**原本は [devtrack/](../../devtrack/README.md) の `spec/examples/`**（1 例 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

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

## entities と enforcement は、関係レジストリ（kind: relations）にだけ書ける

- ID: EX-schema-entities-only-in-registry
- 示す仕様: KW-schema.entities・KW-schema.enforcement・KW-schema.kind
- 結果: スキーマの読み込みの誤りになる（文面に「entities」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "tasks/*.yaml"
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
entities:
  - { type: task, label: 作業 }
```

## entity は、文書のスキーマ（kind: document）にだけ書ける

- ID: EX-schema-entity-only-in-document
- 示す仕様: KW-schema.entity・KW-schema.kind
- 結果: スキーマの読み込みの誤りになる（文面に「entity」を含む）

`.yde/schemas/common.yaml`

```yaml
id: common
kind: library
entity: { type: task }
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

## scaffold は、derive を書いたスキーマには書けない

- ID: EX-schema-scaffold-not-with-derive
- 示す仕様: KW-schema.scaffold・KW-schema.derive
- 結果: スキーマの読み込みの誤りになる（文面に「scaffold」を含む）

`.yde/schemas/record.yaml`

```yaml
id: record-template
derive:
  from: "tables/*.yaml"
  id: "record-{id}"
  match: "records/{id}/*.yaml"
  fields: { source: columns, key: name }
scaffold: { name: 新しい記録 }
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

## files の outside・missing は、allow・warning・error のどれか

- ID: EX-workspace-config-files-value
- 示す仕様: KW-workspace-files.outside・KW-workspace-files.missing
- 結果: 検証のエラーになる（文面に「deny」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "doc/tasks/*.yaml"
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
```

`doc/tasks/a.yaml`

```yaml
id: TASK-1
```

`.yde/workspace.yaml`

```yaml
files:
  outside: deny
```

## ワークスペースの構成（.yde/workspace.yaml）は、構成のメタスキーマで検証される

- ID: EX-workspace-config-ok
- 示す仕様: CTX-workspace・CTX-workspace-files・CTX-workspace-scope・CTX-workspace-stage
- 結果: 通る（誤りも警告も無い）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "doc/tasks/*.yaml"
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
```

`doc/tasks/a.yaml`

```yaml
id: TASK-1
```

`.yde/workspace.yaml`

```yaml
ledger: [doc]
files:
  outside: error
  missing: warning
scopes:
  - { id: business, name: 業務モデル, include: ["schemas/**"] }
stages:
  - { id: basic, name: 基本設計 }
```

## 構成のファイルに、決まった語でないキーを書くと警告になる（綴りの誤りに気づける）

- ID: EX-workspace-config-unknown-key
- 示す仕様: CTX-workspace・KW-workspace.ledger
- 結果: 検証の警告になる（文面に「leger」を含む）

`.yde/schemas/task.yaml`

```yaml
id: task
match: "doc/tasks/*.yaml"
root:
  type: object
  fields:
    - { key: id, type: id, required: true }
```

`doc/tasks/a.yaml`

```yaml
id: TASK-1
```

`.yde/workspace.yaml`

```yaml
leger: [doc]
```

