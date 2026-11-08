# ロードマップの記録（完了したテーマ）

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/roadmap-history-md.md -->

[../ROADMAP.md](../ROADMAP.md) から、完了したテーマの本文をそのまま移したもの。書き換えない。
当時の文中のリンクと §番号は、移した時点の文書構成を指す。
原本は [devtrack/](../../devtrack/README.md) の各テーマの `history`（記録の項）。テーマを終えたら、本文と節を新しい項（最大の番号の次）にして移す。

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
{/each}
{#if more}

{/if}
{/each}
