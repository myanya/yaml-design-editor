# 仕様の例: 変換のひな型とレポート

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/examples-md.md（分野ごとのレポートが同じひな形を使う） -->

変換のひな型の指示と、レポートの例。1 つの例は小さなワークスペース（定義と文書）で、開いて検証したときの結果を添えている。
結果は repo-tests の `SpecTest` が実際に開いて確かめているので、ここに書いてあるとおりに動く。
**原本は [devtrack/](../../devtrack/README.md) の `spec/examples/`**（1 例 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

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

