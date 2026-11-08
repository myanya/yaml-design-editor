# ロードマップの記録（完了したテーマ）

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/roadmap-history-md.md -->

[../ROADMAP.md](../ROADMAP.md) から、完了したテーマの本文をそのまま移したもの。書き換えない。
当時の文中のリンクと §番号は、移した時点の文書構成を指す。
原本は [devtrack/](../../devtrack/README.md) の各テーマの `history`（記録の項）。テーマを終えると、操作「テーマを終える」が本文と節を新しい項（最大の番号の次。末尾に出る）へ移す。

{#each entries}
{#if part}

## {part}

{part_intro}

{/if}
### {title}
{#each body}

{#if text}
{text}
{/if}
{#if head}
{head}
{#each rows}
| {label} | {title}{#if cells}{#each cells} | {.}{/each}{/if} |
{/each}
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
{#if sections}
{#each sections}

#### {title}
{#each body}

{#if text}
{text}
{/if}
{#if head}
{head}
{#each rows}
| {label} | {title}{#if cells}{#each cells} | {.}{/each}{/if} |
{/each}
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
{#if more}

{/if}
{/each}
