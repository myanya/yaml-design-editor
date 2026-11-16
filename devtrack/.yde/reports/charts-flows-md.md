# チャートと業務フロー図

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/charts-flows-md.md -->

あとから足せる種類のビュー、チャート（`kind: chart`）と業務フロー図（`kind: flow`）に書ける語の一覧。ビューに共通の語（`id`・`name`・`description`・`kind`）と並び（`order`）は [views.md](views.md) にある。
今の実装が読む語を書いたもの。ほかの参照文書は [README.md](README.md) の表にある。
**原本は [devtrack/](../../devtrack/README.md) の `spec/`**（1 語 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

{#each sections}
## {context.values.名前}

{context.values.説明}

{#if context.values.メタスキーマ}
- メタスキーマ: {context.values.メタスキーマ}
{/if}
- 読む実装: {context.values.読む実装}

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
{#each rows}
{#each values.形}
| `{values.キー}` | {#if required}必須{/if} | {value:cell} | {#if default}{default:cell}{/if} | {meaning:cell} |
{/each}
{/each}

{/each}
