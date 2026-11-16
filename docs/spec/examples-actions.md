# 仕様の例: 操作

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/examples-md.md（分野ごとのレポートが同じひな形を使う） -->

操作（アクションと型の操作）の例。1 つの例は小さなワークスペース（定義と文書）で、開いて検証したときの結果を添えている。
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

