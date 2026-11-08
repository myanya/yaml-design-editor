# 将来構想とロードマップ

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/roadmap-md.md -->

本書は方向性と、未完のテーマだけを扱う。実装済みの機能は [FEATURES.md](FEATURES.md)、現行の設計は [architecture/](architecture/README.md) が正。
完了したテーマの本文と、未完のテーマの完了した項目の詳細は [archive/roadmap-history.md](archive/roadmap-history.md) にある。
**原本は [devtrack/](../devtrack/README.md) の `roadmap/`**（1 テーマ 1 ファイルの YAML。本文・節・R 項目もその中）。ビジョン・中核コンセプト・設計原則は `notes/`、テーマの候補とリスクは `roadmap/outlook/`。直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。
テーマの項目を実装したら FEATURES に機能として登録し、R 項目の状態とテーマの状態の要約（状態の表の欄）を更新する。テーマが完了したら状態を `done` にし（未完のテーマから外れる）、本文と節を記録の項（`history`）へ移す（記録も生成する）。

{#each notes}
## {title}

{values.本文}

{/each}
## テーマの状態

完了の本文は [記録](archive/roadmap-history.md) にある。

| ID | テーマ | 状態 |
|---|---|---|
{#each themes}
| {id} | {values.表の名前} | {values.状態の要約} |
{/each}

## 未完のテーマ

各テーマの着手はオーナーの判断を待つ。完了した項目の詳細は記録にある。

{#each open}
### {id}: {title}

{#if body}
{#each body}
{#if text}
{text}
{/if}
{#if points}
{#each points}
- {#if decision}**{decision}（{date} {by}）**: {/if}{#if pending}**判断待ち（{date} {by}）**: {/if}{#if label}**{label}**{#if label_note}{label_note}{/if}: {/if}{text}
{/each}
{/if}
{#if basicTable}
| ID | 項目 | 内容 | 状態 |
|---|---|---|---|
{#each items}
| {id} | {title} | {content} | {state} |
{/each}
{/if}
{#if decisionTable}
| ID | 項目 | 内容 | 判断 | 状態 |
|---|---|---|---|---|
{#each items}
| {id} | {title} | {content} | {decision} | {state} |
{/each}
{/if}
{#if workTable}
| ID | 作業 | 主な場所 | 前提 | 完了条件 | 状態 |
|---|---|---|---|---|---|
{#each items}
| {id} | {title} | {places} | {prereq} | {done_criteria} | {state} |
{/each}
{/if}

{/each}
{/if}
{#if sections}
{#each sections}
#### {title}

{#each body}
{#if text}
{text}
{/if}
{#if points}
{#each points}
- {#if decision}**{decision}（{date} {by}）**: {/if}{#if pending}**判断待ち（{date} {by}）**: {/if}{#if label}**{label}**{#if label_note}{label_note}{/if}: {/if}{text}
{/each}
{/if}
{#if basicTable}
| ID | 項目 | 内容 | 状態 |
|---|---|---|---|
{#each items}
| {id} | {title} | {content} | {state} |
{/each}
{/if}
{#if decisionTable}
| ID | 項目 | 内容 | 判断 | 状態 |
|---|---|---|---|---|
{#each items}
| {id} | {title} | {content} | {decision} | {state} |
{/each}
{/if}
{#if workTable}
| ID | 作業 | 主な場所 | 前提 | 完了条件 | 状態 |
|---|---|---|---|---|---|
{#each items}
| {id} | {title} | {places} | {prereq} | {done_criteria} | {state} |
{/each}
{/if}

{/each}
{/each}
{/if}
{/each}
## テーマの候補（計画前）

{outlook.values.候補の前書き}

| 候補 | 必要になる土台 | 補足 |
|---|---|---|
{#each outlook.values.テーマの候補}
| {name} | {foundation} | {note} |
{/each}

## リスクと未解決課題

| リスク | 方針 |
|---|---|
{#each outlook.values.リスク}
| {risk} | {policy} |
{/each}
