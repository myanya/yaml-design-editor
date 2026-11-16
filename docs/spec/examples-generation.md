# 仕様の例: 生成と段

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/examples-md.md（分野ごとのレポートが同じひな形を使う） -->

生成の定義（.yde/transforms/）と、段の規則・補正の例。1 つの例は小さなワークスペース（定義と文書）で、開いて検証したときの結果を添えている。
結果は repo-tests の `SpecTest` が実際に開いて確かめているので、ここに書いてあるとおりに動く。
**原本は [devtrack/](../../devtrack/README.md) の `spec/examples/`**（1 例 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

## 生成の定義は、業務モデルの並びの要素ごとに 1 文書を作る

- ID: EX-generate-each-actions
- 示す仕様: CTX-generator・KW-generator.each・KW-generator.output・KW-generator.build
- 結果: 通る（誤りも警告も無い）

生成する `design/actions/finish-task.yaml`

```yaml
id: finish-task
name: 完了にする
method: finishTask
```

生成する `design/actions/reopen-task.yaml`

```yaml
id: reopen-task
name: やり直す
method: reopenTask
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
    - { key: status, type: enum, values: [todo, done] }
actions:
  - id: finish-task
    label: 完了にする
    target: { entity: task }
    do:
      - set: { path: status, value: done }
  - id: reopen-task
    label: やり直す
    target: { entity: task }
    do:
      - set: { path: status, value: todo }
```

`.yde/transforms/actions.yaml`

```yaml
id: action-specs
each: actions
output: "design/actions/{id}.yaml"
build:
  id: { $copy: id }
  name: { $copy: label }
  method: { $case: { $copy: id }, $as: camel }
```

## each を書いたときは、output に要素ごとに変わる穴が要る

- ID: EX-generate-each-needs-hole
- 示す仕様: KW-generator.each・KW-generator.output
- 結果: 通る（誤りも警告も無い）

生成は誤りになる（文面に「穴」を含む）。

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
    - { key: status, type: enum, values: [todo, done] }
actions:
  - id: finish-task
    label: 完了にする
    target: { entity: task }
    do:
      - set: { path: status, value: done }
  - id: reopen-task
    label: やり直す
    target: { entity: task }
    do:
      - set: { path: status, value: todo }
```

`.yde/transforms/actions.yaml`

```yaml
id: action-specs
each: actions
output: "design/actions.yaml"
build:
  id: { $copy: id }
```

## output は YAML の文書の場所で書く

- ID: EX-generate-output-yaml
- 示す仕様: KW-generator.output
- 結果: 通る（誤りも警告も無い）

生成は誤りになる（文面に「.yaml」を含む）。

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
    - { key: status, type: enum, values: [todo, done] }
actions:
  - id: finish-task
    label: 完了にする
    target: { entity: task }
    do:
      - set: { path: status, value: done }
  - id: reopen-task
    label: やり直す
    target: { entity: task }
    do:
      - set: { path: status, value: todo }
```

`.yde/transforms/actions.yaml`

```yaml
id: summary
output: "design/summary.md"
build:
  x: 1
```

## 組み立てた共通型を解き、継承する値と項目に明記した値を生成する

- ID: EX-generate-schema-model
- 示す仕様: KW-template.$schemaModel・KW-generator.input
- 結果: 通る（誤りも警告も無い）

生成する `design/values.yaml`

```yaml
types:
  - {name: common#title, maxLength: 200}
  - {name: common#inherited, maxLength: 200}
  - {name: common#explicit, maxLength: 100}
```

`.yde/transforms/resolve.yaml`

```yaml
id: resolve
output: design/values.yaml
input:
  $schemaModel:
    - id: common
      kind: library
      definitions:
        title: {type: string, maxLength: 200}
        inherited: {type: title}
        explicit: {type: title, maxLength: 100}
build:
  types:
    $each: types
    $build:
      name: {$copy: name}
      maxLength: {$copy: maxLength}
```

## 型の制約を緩める指定があれば、生成の途中で止める

- ID: EX-generate-schema-model-invalid
- 示す仕様: KW-template.$schemaModel
- 結果: 通る（誤りも警告も無い）

生成は誤りになる（文面に「$schemaModel」を含む）。

`.yde/transforms/resolve.yaml`

```yaml
id: resolve
output: design/values.yaml
build:
  $schemaModel:
    - id: common
      kind: library
      definitions:
        title: {type: string, maxLength: 100}
        invalid: {type: title, maxLength: 200}
```

## build が何も作らなかった要素は、文書を作らない

- ID: EX-generate-skip-when
- 示す仕様: KW-generator.build・KW-template.$when
- 結果: 通る（誤りも警告も無い）

生成する `design/actions/finish-task.yaml`

```yaml
id: finish-task
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
    - { key: status, type: enum, values: [todo, done] }
actions:
  - id: finish-task
    label: 完了にする
    target: { entity: task }
    do:
      - set: { path: status, value: done }
  - id: reopen-task
    label: やり直す
    target: { entity: task }
    do:
      - set: { path: status, value: todo }
```

`.yde/transforms/actions.yaml`

```yaml
id: action-specs
each: actions
output: "design/actions/{id}.yaml"
build:
  $when: [ { path: id, equals: finish-task } ]
  $then:
    id: { $copy: id }
```

## each を省くと、業務モデル全体から 1 文書を作る

- ID: EX-generate-whole-model
- 示す仕様: KW-generator.each
- 結果: 通る（誤りも警告も無い）

生成する `design/summary.yaml`

```yaml
actions: [finish-task, reopen-task]
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
    - { key: status, type: enum, values: [todo, done] }
actions:
  - id: finish-task
    label: 完了にする
    target: { entity: task }
    do:
      - set: { path: status, value: done }
  - id: reopen-task
    label: やり直す
    target: { entity: task }
    do:
      - set: { path: status, value: todo }
```

`.yde/transforms/actions.yaml`

```yaml
id: summary
output: "design/summary.yaml"
build:
  actions:
    $each: actions
    $build: { $copy: id }
```

## 補正は、当たる要素が 1 つも無い変更を誤りにする

- ID: EX-stage-patch-no-match
- 示す仕様: KW-stage.kind
- 結果: 通る（誤りも警告も無い）

生成は誤りになる（文面に「rename」を含む）。

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
    - { key: status, type: enum, values: [todo, done] }
actions:
  - id: finish-task
    label: 完了にする
    target: { entity: task }
    do:
      - set: { path: status, value: done }
  - id: reopen-task
    label: やり直す
    target: { entity: task }
    do:
      - set: { path: status, value: todo }
```

`.yde/workspace.yaml`

```yaml
stages:
  - { id: basic, name: 基本設計 }
```

`.yde/stages/rename.yaml`

```yaml
id: rename
stage: basic
kind: patch
changes:
  - each: actions
    where: [ { path: id, equals: archive-task } ]
    set: { label: 保管する }
```

`.yde/transforms/actions.yaml`

```yaml
id: action-specs
stage: basic
each: actions
output: "design/actions/{id}.yaml"
build:
  id: { $copy: id }
  name: { $copy: label }
  audited: { $copy: audited }
```

## 段の補正は、個別の要素を選んで変える

- ID: EX-stage-patch-where
- 示す仕様: KW-stage.kind・KW-stage-change.where・KW-stage-change.set
- 結果: 通る（誤りも警告も無い）

生成する `design/actions/finish-task.yaml`

```yaml
id: finish-task
name: 完了にする
```

生成する `design/actions/reopen-task.yaml`

```yaml
id: reopen-task
name: 差し戻す
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
    - { key: status, type: enum, values: [todo, done] }
actions:
  - id: finish-task
    label: 完了にする
    target: { entity: task }
    do:
      - set: { path: status, value: done }
  - id: reopen-task
    label: やり直す
    target: { entity: task }
    do:
      - set: { path: status, value: todo }
```

`.yde/workspace.yaml`

```yaml
stages:
  - { id: basic, name: 基本設計 }
```

`.yde/stages/rename.yaml`

```yaml
id: rename
stage: basic
kind: patch
changes:
  - each: actions
    where: [ { path: id, equals: reopen-task } ]
    set: { label: 差し戻す }
```

`.yde/transforms/actions.yaml`

```yaml
id: action-specs
stage: basic
each: actions
output: "design/actions/{id}.yaml"
build:
  id: { $copy: id }
  name: { $copy: label }
  audited: { $copy: audited }
```

## 段の規則は、条件で選んだ要素を横ぐしに変える。stage を書いた生成の定義が、その段の項を読む

- ID: EX-stage-rule
- 示す仕様: CTX-stage・CTX-stage-change・KW-generator.stage・KW-workspace.stages
- 結果: 通る（誤りも警告も無い）

生成する `design/actions/finish-task.yaml`

```yaml
id: finish-task
name: 完了にする
audited: true
```

生成する `design/actions/reopen-task.yaml`

```yaml
id: reopen-task
name: やり直す
audited: true
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
    - { key: status, type: enum, values: [todo, done] }
actions:
  - id: finish-task
    label: 完了にする
    target: { entity: task }
    do:
      - set: { path: status, value: done }
  - id: reopen-task
    label: やり直す
    target: { entity: task }
    do:
      - set: { path: status, value: todo }
```

`.yde/workspace.yaml`

```yaml
stages:
  - { id: basic, name: 基本設計 }
```

`.yde/stages/audit.yaml`

```yaml
id: audit
name: 監査の対象にする
stage: basic
kind: rule
changes:
  - each: actions
    set: { audited: true }
```

`.yde/transforms/actions.yaml`

```yaml
id: action-specs
stage: basic
each: actions
output: "design/actions/{id}.yaml"
build:
  id: { $copy: id }
  name: { $copy: label }
  audited: { $copy: audited }
```

## 生成の定義の stage は、workspace.yaml の stages にある段を指す

- ID: EX-stage-unknown
- 示す仕様: KW-generator.stage・KW-workspace.stages
- 結果: 通る（誤りも警告も無い）

生成は誤りになる（文面に「detail」を含む）。

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
    - { key: status, type: enum, values: [todo, done] }
actions:
  - id: finish-task
    label: 完了にする
    target: { entity: task }
    do:
      - set: { path: status, value: done }
  - id: reopen-task
    label: やり直す
    target: { entity: task }
    do:
      - set: { path: status, value: todo }
```

`.yde/workspace.yaml`

```yaml
stages:
  - { id: basic, name: 基本設計 }
```

`.yde/transforms/actions.yaml`

```yaml
id: action-specs
stage: detail
each: actions
output: "design/actions/{id}.yaml"
build:
  id: { $copy: id }
  name: { $copy: label }
  audited: { $copy: audited }
```

